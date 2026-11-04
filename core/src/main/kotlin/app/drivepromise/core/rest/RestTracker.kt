package app.drivepromise.core.rest

import app.drivepromise.core.ledger.LedgerEvent
import app.drivepromise.core.ledger.LedgerSink
import app.drivepromise.core.ledger.RestCompliance
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.speech.Priority
import app.drivepromise.core.speech.RestPolicy
import app.drivepromise.core.speech.SayCtx
import app.drivepromise.core.speech.SpeechScheduler
import app.drivepromise.core.speech.Utterance
import app.drivepromise.core.speech.Validity
import app.drivepromise.core.speech.World

/**
 * 2시간마다 쉬기(상태 지속형 약속) — 늦은알림정책 v0.1(Q027).
 *  - 1:50 예고: PROMISE·UNTIL_DEADLINE, 1:58까지만(본 알림과 붙지 않게) — 못 하면 말하지 않음
 *  - 2:00 본 알림: PROMISE·WHILE_STATE — 주행 중 버리지 않음, 말하기 직전 ‘아직 안 쉬었나’ 재판정, 문장은 그때 값
 *    (“두 시간 5분째예요. 약속대로 쉬어 가요.”), 말한 뒤 15분마다 최대 2번 반복
 *  - 쉼 = 기어 P(또는 시동 끔) 15분 이상: 운전 시간 0, 대기 중 쉬기 말 제거, 기록, 다시 출발할 때 칭찬 한 번
 *  - 신호 대기(기어 D, 속도 0)는 운전 시간에 넣음
 * 앱은 10 Hz로 [update]를 부르고, World.drivingMs에 [drivingMs]를 넣습니다.
 */
class RestTracker(
    private val policy: RestPolicy = RestPolicy(),
    private val scheduler: SpeechScheduler,
    private val ledger: LedgerSink = LedgerSink { },
) {
    /** 앱이 꺼졌다 켜져도 이어지게 저장할 값 */
    data class State(val drivingMs: Long, val lastDrivingAtMs: Long?, val headsUpDone: Boolean, val mainDone: Boolean)

    private val targetMs = policy.targetMin * MIN
    private val headsUpAtMs = (policy.targetMin - policy.leadMin) * MIN
    private val headsUpUntilMs = (policy.targetMin - policy.headsUpCutoffMin) * MIN
    private val restResetMs = policy.restResetMin * MIN
    private val repeatMs = policy.repeatMin * MIN

    /** 마지막 쉼 이후 운전 시간 */
    var drivingMs = 0L
        private set
    private var lastT: Long? = null
    private var lastDrivingAt: Long? = null
    private var parkedSince: Long? = null
    private var headsUpDone = false
    private var mainDone = false
    private var praisePending = false

    /** 10 Hz. 차량 상태를 아직 모르면(null) 운전 시간을 늘리지 않습니다 */
    fun update(nowMs: Long, v: VehicleSnapshot?) {
        val prev = lastT
        lastT = nowMs
        val driving = v?.driving == true
        if (prev != null && nowMs > prev && driving) {
            drivingMs += nowMs - prev
            lastDrivingAt = nowMs
        }
        if (v?.parked == true) {
            val since = parkedSince ?: nowMs.also { parkedSince = it }
            if (drivingMs > 0 && nowMs - since >= restResetMs) rest(since, nowMs)
        } else if (driving) {
            parkedSince = null
            if (praisePending) {
                praisePending = false
                scheduler.submit(praise(), nowMs)
            }
            checkThresholds(nowMs)
        }
    }

    /** 주행 끝 — 쉬지 못하고 본 알림 시각을 넘겼으면 요약 문장(말하기는 요약 담당). 운전 시간은 쉼 규칙으로만 0이 됨 */
    fun endTrip(nowMs: Long, lang: Lang = Lang.KO): String? {
        ledger.accept(LedgerEvent.TripEnded(nowMs, drivingMs))
        return if (drivingMs >= targetMs) Phrases.digestNoRest(lang, drivingMs, policy) else null
    }

    fun state(): State = State(drivingMs, lastDrivingAt, headsUpDone, mainDone)

    /** 저장해 둔 값으로 이어 가기. 꺼져 있던 동안 쉼 기준 시간이 지났으면 쉼으로 인정 */
    fun restore(s: State, nowMs: Long) {
        lastT = nowMs
        val last = s.lastDrivingAtMs
        lastDrivingAt = last
        if (s.drivingMs > 0 && last != null && nowMs - last >= restResetMs) {
            ledger.accept(LedgerEvent.RestTaken(nowMs, last, s.drivingMs))
            drivingMs = 0
            headsUpDone = false
            mainDone = false
            praisePending = s.headsUpDone || s.mainDone
            parkedSince = null
        } else {
            drivingMs = s.drivingMs
            headsUpDone = s.headsUpDone
            mainDone = s.mainDone
            parkedSince = last   // 아직 세워 둔 채라면 꺼진 시간도 쉼에 넣음
        }
    }

    private fun checkThresholds(nowMs: Long) {
        if (!headsUpDone && !mainDone && drivingMs >= headsUpAtMs && drivingMs < headsUpUntilMs) {
            headsUpDone = true
            ledger.accept(LedgerEvent.Due(nowMs, KEY_HEADS_UP, drivingMs))
            scheduler.submit(headsUp(nowMs + (headsUpUntilMs - drivingMs)), nowMs)
        }
        if (!mainDone && drivingMs >= targetMs) {
            mainDone = true
            headsUpDone = true
            scheduler.cancel(KEY_HEADS_UP, nowMs, "main_due")
            ledger.accept(LedgerEvent.Due(nowMs, KEY_MAIN, drivingMs))
            scheduler.submit(main(nowMs), nowMs)
        }
    }

    private fun rest(since: Long, nowMs: Long) {
        val exposed = headsUpDone || mainDone
        ledger.accept(LedgerEvent.RestTaken(nowMs, since, drivingMs))
        scheduler.cancel("rest.", nowMs, "rested")
        drivingMs = 0
        headsUpDone = false
        mainDone = false
        praisePending = exposed
    }

    private fun headsUp(deadline: Long) = Utterance(
        key = KEY_HEADS_UP,
        priority = Priority.PROMISE,
        estMs = policy.estMs,
        estShortMs = policy.estShortMs,
        deadlineAtMs = deadline,
        validity = Validity.UNTIL_DEADLINE,
        stillValid = { w -> w.drivingMs in headsUpAtMs until headsUpUntilMs },
        speakWhen = DRIVING,
        render = { c -> Phrases.headsUp(c.world.lang, policy, c.short) },
    )

    private fun main(nowMs: Long) = Utterance(
        key = KEY_MAIN,
        priority = Priority.PROMISE,
        estMs = policy.estMs,
        estShortMs = policy.estShortMs,
        deadlineAtMs = nowMs,
        validity = Validity.WHILE_STATE,
        stillValid = { w -> w.drivingMs >= targetMs },
        speakWhen = DRIVING,   // 세워 둔 동안(P)엔 미룸 — ‘가까운 곳에 세워요’를 이미 선 차에 말하지 않게
        repeatAfterMs = repeatMs,
        maxRepeats = policy.maxRepeats,
        render = { c -> Phrases.main(c, policy) },
    )

    private fun praise() = Utterance(KEY_PRAISE, Priority.INFO, 2_500, render = { c -> Phrases.praise(c.world.lang) }, speakWhen = DRIVING)

    companion object {
        const val KEY_HEADS_UP = RestCompliance.HEADS_UP_KEY
        const val KEY_MAIN = RestCompliance.MAIN_KEY
        const val KEY_PRAISE = "rest.praise"
        private const val MIN = 60_000L
        private val DRIVING: (World) -> Boolean = { w -> w.vehicle?.driving == true }
    }
}

/** 쉬기 문장(ko/en) — 4초 이하, 회전 문장 없음. 시는 고유어(두 시간), 분은 숫자(5분)로 써서 TTS가 바르게 읽게 */
object Phrases {
    private val NATIVE = arrayOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "열한", "열두")

    fun koDuration(totalMin: Long): String {
        val h = totalMin / 60
        val m = totalMin % 60
        val hs = if (h in 1..12) NATIVE[h.toInt()] + " 시간" else "${h}시간"
        return when {
            h == 0L -> "${m}분"
            m == 0L -> hs
            else -> "$hs ${m}분"
        }
    }

    fun koDuration(totalMin: Int): String = koDuration(totalMin.toLong())

    fun enDuration(totalMin: Long): String {
        val h = totalMin / 60
        val m = totalMin % 60
        val hs = if (h == 1L) "1 hour" else "$h hours"
        val ms = if (m == 1L) "1 minute" else "$m minutes"
        return when {
            h == 0L -> ms
            m == 0L -> hs
            else -> "$hs $ms"
        }
    }

    fun enDuration(totalMin: Int): String = enDuration(totalMin.toLong())

    fun headsUp(lang: Lang, p: RestPolicy, short: Boolean): String = when (lang) {
        Lang.KO -> if (short) "곧 ${koDuration(p.targetMin)}이에요. 쉴 곳을 봐 두세요."
        else "${p.leadMin}분쯤 뒤면 ${koDuration(p.targetMin)}이에요. 다음 쉴 곳에서 쉬어 가요."
        Lang.EN -> if (short) "Almost ${enDuration(p.targetMin)}. Plan a stop."
        else "About ${p.leadMin} minutes to ${enDuration(p.targetMin)}. Let's rest at the next stop."
    }

    fun main(c: SayCtx, p: RestPolicy): String {
        val w = c.world
        val min = w.drivingMs / 60_000L
        val eta = w.etaMs
        val soon = p.arrivalSoonMin > 0 && eta != null && eta <= p.arrivalSoonMin * 60_000L
        return when (w.lang) {
            Lang.KO -> when {
                soon -> "곧 도착이에요. 도착하면 꼭 쉬어요."
                c.short -> "${koDuration(p.targetMin)} 넘었어요. 쉬어 가요."
                c.repeat == 0 -> "${koDuration(min)}째예요. 약속대로 쉬어 가요."
                else -> "쉬지 않고 ${koDuration(min)}째예요. 가까운 곳에 잠깐 세워요."
            }
            Lang.EN -> when {
                soon -> "Almost there. Please rest when you arrive."
                c.short -> "Over ${enDuration(p.targetMin)}. Let's take a break."
                c.repeat == 0 -> "${enDuration(min)} now. Let's rest as promised."
                else -> "${enDuration(min)} without a break. Let's pull over nearby."
            }
        }
    }

    fun praise(lang: Lang): String = when (lang) {
        Lang.KO -> "잘 쉬셨어요. 약속 지키셨습니다."
        Lang.EN -> "Well rested — promise kept."
    }

    fun digestNoRest(lang: Lang, drivingMs: Long, p: RestPolicy): String {
        val min = drivingMs / 60_000L
        val next = p.targetMin - p.leadMin
        return when (lang) {
            Lang.KO -> "오늘은 ${koDuration(min)}을 쉬지 않고 운전하셨어요. 다음엔 ${koDuration(next)}쯤 쉬어 가요."
            Lang.EN -> "You drove ${enDuration(min)} without a break today. Next time, rest around ${enDuration(next)}."
        }
    }
}
