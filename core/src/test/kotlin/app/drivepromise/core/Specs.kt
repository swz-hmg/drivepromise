package app.drivepromise.core

import app.drivepromise.core.ledger.LedgerEvent
import app.drivepromise.core.ledger.MemoryLedger
import app.drivepromise.core.ledger.RestCompliance
import app.drivepromise.core.model.CautionKind
import app.drivepromise.core.model.Gear
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.Turn
import app.drivepromise.core.model.TurnType
import app.drivepromise.core.model.VehicleDelta
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.model.merge
import app.drivepromise.core.replay.MiniJson
import app.drivepromise.core.replay.ReplayParser
import app.drivepromise.core.rest.Phrases
import app.drivepromise.core.rest.RestTracker
import app.drivepromise.core.speech.AudioUsage
import app.drivepromise.core.speech.DigestReason
import app.drivepromise.core.speech.InfoOverflow
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.speech.Priority
import app.drivepromise.core.speech.QuietPolicy
import app.drivepromise.core.speech.QuietWindows
import app.drivepromise.core.speech.RestPolicy
import app.drivepromise.core.speech.Speaker
import app.drivepromise.core.speech.SpeechScheduler
import app.drivepromise.core.speech.Step
import app.drivepromise.core.speech.Utterance
import app.drivepromise.core.speech.Validity
import app.drivepromise.core.speech.VoiceArbiter
import app.drivepromise.core.speech.VoiceArbiter.Action
import app.drivepromise.core.speech.VoiceArbiter.Focus
import app.drivepromise.core.speech.VoiceLoop
import app.drivepromise.core.speech.World

/**
 * 코어 계약 v0.1 스펙 — 같은 목록을 JUnit(./gradlew :core:test, JUnitBridge)과 의존성 없는 점검 러너가 돌립니다.
 * 이름 앞 번호(Sxx)는 문서의 시험 표와 같습니다. 시각은 ms, 앱 루프와 같은 10 Hz(100 ms)로 흉내 냅니다.
 */
object Specs {
    val all: List<Pair<String, () -> Unit>> = listOf(
        "S01 모델 — 부분 변경 합치기, 신호 대기(D·속도 0)는 주행, P·시동 끔은 정차" to ::s01,
        "S02 말 계약 — PROMISE는 마감·유효성 필수, 4초 넘는 말·반복 간격 없는 반복 거절" to ::s02,
        "S03 순서 — SAFETY > PROMISE(마감 순) > INFO" to ::s03,
        "S04 창이 없으면 기다렸다가 열리면 말함" to ::s04,
        "S05 마감형 — 지난 말은 하지 않음(LATE_OK 0초), 요약 기록 + 앞을 보는 새 말" to ::s05,
        "S06 말하기 직전 재판정 — 더는 맞지 않으면 버림(마감형: 지남, 상태형: 상태 끝남)" to ::s06,
        "S07 정보 — 60초 안에 못 하면 정차 후 요약(설정에 따라 버림)" to ::s07,
        "S08 상태 지속형 — 10분 동안 창이 없어도 버리지 않고 신호 대기 창에서 말함" to ::s08,
        "S09 신호 대기 창은 마감 60초 전부터만" to ::s09,
        "S10 두 번 끊긴 말은 신호 대기 창·확실한 틈(짧은 문장 + 4초)에서만" to ::s10,
        "S11 물러선 뒤에는 짧은 문장·짧은 길이로 창을 봄" to ::s11,
        "S12 반복 — 말한 뒤 15분, 최대 2번" to ::s12,
        "S13 한 번에 하나만 말함" to ::s13,
        "S14 내비가 말하는 동안 시작하지 않지만 유효성 정리는 계속" to ::s14,
        "S15 TTS 오류 3번이면 요약으로" to ::s15,
        "S16 주행 끝 — 남은 약속·정보 말은 요약, 안전 말은 버림" to ::s16,
        "S17 문장은 말하기 직전 값으로 만듦" to ::s17,
        "S18 물러서기 — 실측 내비 타이밍(효과음 0–1056 ms, 말 99–2215 ms)이면 3715 ms에 다시" to ::s18,
        "S19 v0.6 결함 회귀 — 첫 재생기 끝 + 1.5초(2556 ms)에 다시 말하지 않음" to ::s19,
        "S20 GAIN이 안 오면 내비 끝 + 1.5초 + 3초 뒤" to ::s20,
        "S21 재생 목록을 못 읽는 플랫폼 — 포커스만으로 물러서기·다시 말하기" to ::s21,
        "S22 말하지 않을 때 내비가 시작하면 물러서기 없음, 내비 중에는 시작 금지" to ::s22,
        "S23 오래전 내비 끝 뒤의 새 포커스 잃음은 따로 셈" to ::s23,
        "S24 통합 — 말하는 중 내비 시작 → 멈춤 → 내비 끝 + 1.5초 뒤 짧게 다시" to ::s24,
        "S25 2시간 쉬기 — 1:50 예고 → 2:00 본 알림 → 15·30분 뒤 반복 → 끝, 요약 문장" to ::s25,
        "S26 2시간 쉬기 — 2:03에 말하면 ‘두 시간 3분째예요’" to ::s26,
        "S27 2시간 쉬기 — P 15분 이상이면 쉼: 대기 말 제거·기록·출발 때 칭찬 한 번, 세워 둔 동안 반복 미룸" to ::s27,
        "S28 2시간 쉬기 — P 14분은 쉼 아님: 시계 그대로, 다시 출발하면 미룬 반복을 말함" to ::s28,
        "S29 2시간 쉬기 — 예고는 1:58까지만, 못 하면 말하지 않고 요약 기록" to ::s29,
        "S30 2시간 쉬기 — 도착 15분 안이면 ‘도착하면 꼭 쉬어요’" to ::s30,
        "S31 2시간 쉬기 — 2시간 전에 끝나면 요약 문장 없음" to ::s31,
        "S32 이어 가기 — 꺼져 있던 15분 이상은 쉼, 그보다 짧으면 이어서 세움" to ::s32,
        "S33 신호 대기(D·속도 0) 10분은 운전 시간에 들어감" to ::s33,
        "S34 지표 정의 — 노출·전달·순응·약속 지킴·예고로 지킴" to ::s34,
        "S35 지표 엔드투엔드 — 본 알림 9분 뒤 쉼 = 순응 1/1" to ::s35,
        "S36 리플레이 스키마 0.2 — 계획서 예시 읽기" to ::s36,
        "S37 리플레이 — 모르는 값은 위치와 함께 거절" to ::s37,
        "S38 작은 JSON — 이스케이프·유니코드·숫자·오류 위치" to ::s38,
        "S39 시간 읽기 — 한 시간 50분·두 시간·두 시간 5분 / 2 hours 5 minutes" to ::s39,
        "S40 영어 문장" to ::s40,
        "S41 미룸 조건 — 세워 둔 동안 미루고 출발하면 말함(버리지 않음)" to ::s41,
    ) + EngineSpecs.all + SessionSpecs.all + ReviewSpecs.all + FixSpecs.all + P1Specs.all + P2Specs.all
}

// ---------------------------------------------------------------- 도우미
internal fun fail(msg: String): Nothing = throw AssertionError(msg)

internal fun ok(cond: Boolean, msg: String) {
    if (!cond) fail(msg)
}

internal fun <T> eq(actual: T, expected: T, what: String) {
    if (actual != expected) fail("$what — 기대: $expected / 실제: $actual")
}

internal inline fun <reified E : Throwable> throws(what: String, block: () -> Unit): E {
    try {
        block()
    } catch (e: Throwable) {
        if (e is E) return e
        fail("$what — ${E::class.simpleName} 기대, 실제 ${e::class.simpleName}: ${e.message}")
    }
    fail("$what — ${E::class.simpleName}가 나야 함")
}

internal const val MIN = 60_000L
internal val DRIVE = VehicleSnapshot(0, gear = Gear.D, speedMps = 15f)
internal val PARK = VehicleSnapshot(0, gear = Gear.P, speedMps = 0f)

/** 대본 창 — 시험마다 열림·닫힘을 정하고, 물어본 길이를 기록 */
internal class ScriptWindows(
    var normalOk: (Long, Long) -> Boolean = { _, _ -> true },
    var stopOk: (Long) -> Boolean = { false },
    var gapOk: ((Long, Long) -> Boolean)? = null,
) : QuietWindows {
    val normalAsked = mutableListOf<Long>()
    val gapAsked = mutableListOf<Long>()
    override fun normal(nowMs: Long, needMs: Long): Boolean {
        normalAsked += needMs
        return normalOk(nowMs, needMs)
    }
    override fun stopWindow(nowMs: Long): Boolean = stopOk(nowMs)
    override fun longGap(nowMs: Long, needMs: Long): Boolean {
        gapAsked += needMs
        return gapOk?.invoke(nowMs, needMs) ?: normal(nowMs, needMs)
    }
}

internal class FakeSpeaker : Speaker {
    val said = mutableListOf<Pair<Long, String>>()
    val stopped = mutableListOf<Long>()
    override fun speak(id: Long, text: String, usage: AudioUsage) {
        said += id to text
    }
    override fun stop(id: Long) {
        stopped += id
    }
}

internal data class Said(val t: Long, val key: String, val text: String, val short: Boolean, val repeat: Int, val attempt: Int)

/** 10 Hz 루프 흉내 — 말은 예상 길이 뒤 끝난다고 봄. 예약 사건은 그 시각 이후 첫 tick에 원래 시각으로 넣음 */
internal class Sim(val sch: SpeechScheduler, val arb: VoiceArbiter = VoiceArbiter()) {
    val spk = FakeSpeaker()
    val loop = VoiceLoop(sch, arb, spk)
    val log = mutableListOf<Said>()
    val yields = mutableListOf<Long>()
    private var cur: Step.Speak? = null
    private var endAt = 0L
    private val events = sortedMapOf<Long, MutableList<(Long) -> Unit>>()

    fun at(t: Long, f: (Long) -> Unit) {
        events.getOrPut(t) { mutableListOf() } += f
    }

    fun nav(t: Long, n: Int) = at(t) { tt -> if (loop.onNavPlayers(tt, n) is Action.Yield) { cur = null; yields += tt } }
    fun focus(t: Long, f: Focus) = at(t) { tt -> if (loop.onFocus(tt, f) is Action.Yield) { cur = null; yields += tt } }

    fun run(from: Long, to: Long, step: Long = 100, world: (Long) -> World) {
        var t = from
        while (t <= to) {
            while (events.isNotEmpty() && events.firstKey() <= t) {
                val k = events.firstKey()
                events.remove(k)!!.forEach { it(k) }
            }
            val c = cur
            if (c != null && t >= endAt) {
                loop.onDone(c.id, t)
                cur = null
            }
            val s = loop.tick(world(t))
            if (s is Step.Speak) {
                cur = s
                endAt = t + s.estMs
                log += Said(t, s.key, s.text, s.short, s.repeat, s.attempt)
            }
            t += step
        }
    }
}

internal fun sched(w: QuietWindows = ScriptWindows(), p: QuietPolicy = QuietPolicy(), l: MemoryLedger = MemoryLedger()) = SpeechScheduler(p, w, l)

private fun info(key: String, est: Long = 2_000) = Utterance(key, Priority.INFO, est, render = { key })

private fun safety(key: String) = Utterance(key, Priority.SAFETY, 2_000, render = { key })

private fun promise(
    key: String,
    deadline: Long,
    v: Validity = Validity.WHILE_STATE,
    est: Long = 3_000,
    stillValid: (World) -> Boolean = { true },
    followup: (World) -> Utterance? = { null },
    repeatAfter: Long? = null,
    maxRepeats: Int = 0,
    speakWhen: (World) -> Boolean = { true },
) = Utterance(
    key, Priority.PROMISE, est,
    render = { c -> "$key r${c.repeat}" + if (c.short) " 짧게" else "" },
    deadlineAtMs = deadline, validity = v, stillValid = stillValid, followup = followup,
    repeatAfterMs = repeatAfter, maxRepeats = maxRepeats, estShortMs = minOf(est, 2_000), speakWhen = speakWhen,
)

internal fun w(t: Long, driving: Long = 0, v: VehicleSnapshot? = DRIVE.copy(tMillis = t)) = World(t, v, drivingMs = driving)

/** 쉬기 시험 장치 — RestTracker + 스케줄러 + 루프 */
private class RestRig(
    policy: RestPolicy = RestPolicy(),
    val win: ScriptWindows = ScriptWindows(),
) {
    val ledger = MemoryLedger()
    val sch = SpeechScheduler(QuietPolicy(), win, ledger)
    val rest = RestTracker(policy, sch, ledger)
    val sim = Sim(sch)
    var vehicle: (Long) -> VehicleSnapshot? = { t -> DRIVE.copy(tMillis = t) }
    var eta: (Long) -> Long? = { null }
    var lang = Lang.KO

    fun run(from: Long, to: Long) = sim.run(from, to) { t ->
        val v = vehicle(t)
        rest.update(t, v)
        World(t, v, drivingMs = rest.drivingMs, etaMs = eta(t), lang = lang)
    }

    fun restoreAt(drivingMin: Long, t: Long = 0) = rest.restore(RestTracker.State(drivingMin * MIN, t, false, false), t)
}

// ---------------------------------------------------------------- 모델·계약
private fun s01() {
    val a = VehicleSnapshot(0, gear = Gear.D, speedMps = 10f)
    val b = a.merge(VehicleDelta(100, turnSignal = Turn.LEFT))
    eq(b.tMillis, 100L, "시각")
    eq(b.gear, Gear.D, "기어 유지")
    eq(b.speedMps, 10f, "속도 유지")
    eq(b.turnSignal, Turn.LEFT, "방향지시등")
    ok(b.driving && !b.parked, "기어 D는 주행")
    val p = b.merge(VehicleDelta(200, gear = Gear.P, speedMps = 0f))
    ok(p.parked && !p.driving, "기어 P는 정차")
    val red = VehicleSnapshot(0, gear = Gear.D, speedMps = 0f)
    ok(red.driving && red.stopped, "신호 대기(D, 속도 0)는 주행이면서 멈춤")
    ok(VehicleSnapshot(0, gear = Gear.D, ignitionOn = false).parked, "시동 끔은 정차")
    ok(!VehicleSnapshot(0).driving, "아무것도 모르면 주행 아님")
    eq(Gear.of("Drive"), Gear.D, "기어 이름")
    eq(Turn.of("BlinkLeft"), Turn.LEFT, "콘솔 방향지시등 이름")
    eq(TurnType.of("left"), TurnType.LEFT, "회전 종류")
    eq(CautionKind.of("NOPE"), CautionKind.OTHER, "모르는 주의 지점")
}

private fun s02() {
    throws<IllegalArgumentException>("마감 없는 PROMISE") { Utterance("p", Priority.PROMISE, 1_000, render = { "" }) }
    throws<IllegalArgumentException>("유효성 없는 PROMISE") { Utterance("p", Priority.PROMISE, 1_000, render = { "" }, deadlineAtMs = 0) }
    throws<IllegalArgumentException>("4초 넘는 말") { Utterance("long", Priority.INFO, 4_001, render = { "" }) }
    throws<IllegalArgumentException>("짧은 문장이 더 김") { Utterance("s", Priority.INFO, 2_000, render = { "" }, estShortMs = 2_500) }
    throws<IllegalArgumentException>("간격 없는 반복") { Utterance("r", Priority.INFO, 1_000, render = { "" }, maxRepeats = 1) }
    throws<IllegalArgumentException>("쉬기 정책 순서") { RestPolicy(targetMin = 120, leadMin = 1, headsUpCutoffMin = 2) }
    Utterance("ok", Priority.INFO, 4_000, render = { "" })
    Utterance("p", Priority.PROMISE, 3_000, render = { "" }, deadlineAtMs = 0, validity = Validity.UNTIL_DEADLINE)
}

// ---------------------------------------------------------------- 스케줄러
private fun s03() {
    val sch = sched()
    listOf(info("i"), promise("p200", 200_000), promise("p100", 100_000), safety("s")).forEach { sch.submit(it, 0) }
    val sim = Sim(sch)
    sim.run(0, 20_000) { t -> w(t) }
    eq(sim.log.map { it.key }, listOf("s", "p100", "p200", "i"), "말한 순서")
}

private fun s04() {
    val win = ScriptWindows(normalOk = { t, _ -> t >= 5_000 })
    val sch = sched(win)
    sch.submit(info("i"), 0)
    val sim = Sim(sch)
    sim.run(0, 10_000) { t -> w(t) }
    eq(sim.log.map { it.t }, listOf(5_000L), "창이 열린 때")
}

private fun s05() {
    val win = ScriptWindows(normalOk = { t, _ -> t >= 12_000 })
    val led = MemoryLedger()
    val sch = sched(win, l = led)
    sch.submit(promise("zone.edge", 10_000, Validity.UNTIL_DEADLINE, followup = { ww -> promise("zone.out", ww.tMillis) }), 0)
    val sim = Sim(sch)
    sim.run(0, 20_000) { t -> w(t) }
    eq(sim.log.map { it.key to it.t }, listOf("zone.out" to 12_000L), "지난 말 대신 새 말")
    eq(sch.digest.map { it.key to it.reason }, listOf("zone.edge" to DigestReason.EXPIRED), "요약 기록")
    ok(led.events.any { it is LedgerEvent.Dropped && it.key == "zone.edge" && it.reason == "expired" && it.tMillis == 10_100L }, "마감 직후(10.1초)에 버림: ${led.events}")
}

private fun s06() {
    val win = ScriptWindows(normalOk = { t, _ -> t >= 5_000 })
    val led = MemoryLedger()
    val sch = sched(win, l = led)
    sch.submit(promise("edge", 100_000, Validity.UNTIL_DEADLINE, stillValid = { ww -> ww.tMillis < 3_000 }), 0)
    sch.submit(promise("state", 0, Validity.WHILE_STATE, stillValid = { ww -> ww.tMillis < 4_000 }), 0)
    val sim = Sim(sch)
    sim.run(0, 10_000) { t -> w(t) }
    eq(sim.log.size, 0, "아무것도 말하지 않음")
    eq(sch.digest.map { it.key to it.reason }, listOf("edge" to DigestReason.EXPIRED, "state" to DigestReason.STATE_ENDED), "요약 사유")
    ok(led.events.any { it is LedgerEvent.Dropped && it.key == "state" && it.reason == "state_ended" }, "상태 끝남 기록")
}

private fun s07() {
    val closed = ScriptWindows(normalOk = { _, _ -> false })
    val led = MemoryLedger()
    val sch = sched(closed, l = led)
    sch.submit(info("i"), 0)
    Sim(sch).run(0, 70_000) { t -> w(t) }
    eq(sch.digest.map { it.key to it.reason }, listOf("i" to DigestReason.INFO_TIMEOUT), "요약으로")
    ok(led.events.any { it is LedgerEvent.Digested && it.key == "i" && it.tMillis == 60_100L }, "60초 직후 요약: ${led.events}")
    val led2 = MemoryLedger()
    val sch2 = sched(closed, QuietPolicy(infoOverflow = InfoOverflow.DROP), led2)
    sch2.submit(info("j"), 0)
    Sim(sch2).run(0, 70_000) { t -> w(t) }
    eq(sch2.digest.size, 0, "DROP이면 요약 없음")
    ok(led2.events.any { it is LedgerEvent.Dropped && it.key == "j" && it.reason == "info_timeout" }, "DROP 기록")
}

private fun s08() {
    val win = ScriptWindows(normalOk = { _, _ -> false }, stopOk = { t -> t in 600_000..602_000 })
    val sch = sched(win)
    sch.submit(promise("rest.like", 0), 0)
    val sim = Sim(sch)
    sim.run(0, 610_000) { t -> w(t) }
    eq(sim.log.map { it.t }, listOf(600_000L), "10분 뒤 신호 대기 창에서")
    eq(sch.digest.size, 0, "요약으로 가지 않음")
}

private fun s09() {
    val win = ScriptWindows(normalOk = { _, _ -> false }, stopOk = { t -> t in 30_000..30_500 || t in 41_000..41_500 })
    val sch = sched(win)
    sch.submit(promise("p", 100_000, Validity.UNTIL_DEADLINE), 0)
    val sim = Sim(sch)
    sim.run(0, 50_000) { t -> w(t) }
    eq(sim.log.map { it.t }, listOf(41_000L), "마감 60초 전(40초) 뒤의 창만")
}

private fun s10() {
    val win = ScriptWindows()
    val sch = sched(win)
    sch.submit(promise("p", 0, est = 3_000), 0)
    val a = sch.tick(w(0), true) as Step.Speak
    sch.yielded(a.id, 100)
    val b = sch.tick(w(200), true) as Step.Speak
    ok(b.short && b.attempt == 2, "두 번째는 짧게: $b")
    sch.yielded(b.id, 300)
    win.stopOk = { false }
    win.gapOk = { _, _ -> false }
    eq(sch.tick(w(400), true), Step.Idle, "두 번 끊긴 뒤 일반 창만으로는 말하지 않음")
    win.gapOk = { _, _ -> true }
    val c = sch.tick(w(500), true) as Step.Speak
    ok(c.short && c.attempt == 3, "세 번째도 짧게: $c")
    eq(win.gapAsked.last(), 2_000L + 4_000L, "확실한 틈 = 짧은 문장 2초 + 4초")
    win.gapOk = { _, _ -> false }
    sch.yielded(c.id, 600)
    win.stopOk = { true }
    ok(sch.tick(w(700), true) is Step.Speak, "신호 대기 창이면 말함")
}

private fun s11() {
    val win = ScriptWindows()
    val sch = sched(win)
    sch.submit(promise("p", 0, est = 3_000), 0)
    val a = sch.tick(w(0), true) as Step.Speak
    eq(win.normalAsked.last(), 3_000L, "처음엔 원래 길이로 창 확인")
    eq(a.text, "p r0", "처음 문장")
    sch.yielded(a.id, 100)
    val b = sch.tick(w(200), true) as Step.Speak
    eq(win.normalAsked.last(), 2_000L, "물러선 뒤엔 짧은 길이로")
    eq(b.text, "p r0 짧게", "짧은 문장")
    eq(b.estMs, 2_000L, "예상 길이")
}

private fun s12() {
    val sch = sched()
    sch.submit(promise("rest.main", 0, repeatAfter = 15 * MIN, maxRepeats = 2), 0)
    val sim = Sim(sch)
    sim.run(0, 50 * MIN) { t -> w(t) }
    eq(sim.log.map { it.t to it.repeat }, listOf(0L to 0, 903_000L to 1, 1_806_000L to 2), "끝나고 15분 뒤 두 번만")
}

private fun s13() {
    val sch = sched()
    sch.submit(info("a"), 0)
    sch.submit(info("b"), 0)
    val sim = Sim(sch)
    sim.run(0, 10_000) { t -> w(t) }
    eq(sim.log.map { it.key to it.t }, listOf("a" to 0L, "b" to 2_000L), "앞 말이 끝난 뒤")
}

private fun s14() {
    val led = MemoryLedger()
    val sch = sched(l = led)
    sch.submit(info("i"), 0)
    var t = 0L
    while (t <= 70_000) {
        eq(sch.tick(w(t), voiceFree = false), Step.Idle, "내비 중 시작 금지($t)")
        t += 100
    }
    eq(sch.digest.map { it.key }, listOf("i"), "그래도 60초 정리")
}

private fun s15() {
    val sch = sched()
    sch.submit(info("i"), 0)
    for (k in 1..3) {
        val s = sch.tick(w(k * 3_000L), true) as Step.Speak
        eq(s.attempt, k, "시도 번호")
        sch.failed(s.id, k * 3_000L + 10, "engine")
        if (k < 3) eq(sch.tick(w(k * 3_000L + 1_000), true), Step.Idle, "1.3.0: 오류 뒤 2초 안엔 다시 하지 않음")
    }
    eq(sch.tick(w(12_000), true), Step.Idle, "세 번 실패 뒤 대기 없음")
    eq(sch.digest.map { it.key to it.reason }, listOf("i" to DigestReason.TTS_FAILED), "요약으로")
}

private fun s16() {
    val led = MemoryLedger()
    val sch = sched(ScriptWindows(normalOk = { _, _ -> false }), l = led)
    sch.submit(promise("p", 1_000), 0)
    sch.submit(info("i"), 0)
    sch.submit(safety("s"), 0)   // 안전 말은 창과 무관하지만 내비가 말하는 중이라 못 함
    sch.tick(w(0), voiceFree = false)
    sch.endTrip(1_000)
    eq(sch.digest.map { it.key to it.reason }.toSet(), setOf("p" to DigestReason.TRIP_END, "i" to DigestReason.TRIP_END), "요약")
    ok(led.events.any { it is LedgerEvent.Dropped && it.key == "s" && it.reason == "trip_end" }, "안전 말은 버림")
    eq(sch.pendingKeys, emptyList<String>(), "대기 없음")
}

private fun s17() {
    val sch2 = sched(ScriptWindows(normalOk = { t, _ -> t >= 180_000 }))
    sch2.submit(
        Utterance("y", Priority.PROMISE, 2_000, render = { c -> "운전 ${c.world.drivingMs / MIN}분" }, deadlineAtMs = 0, validity = Validity.WHILE_STATE),
        0,
    )
    val sim = Sim(sch2)
    sim.run(0, 200_000) { t -> w(t, driving = 120 * MIN + t) }
    eq(sim.log.map { it.text }, listOf("운전 123분"), "3분 뒤 값")
}

// ---------------------------------------------------------------- 조정자(물러서기)
private fun s18() {
    val a = VoiceArbiter()
    a.started(7)
    val y = a.onNavPlayers(0, 1)
    ok(y is Action.Yield && y.id == 7L && y.via == "play" && y.stopWithinMs == 250L, "효과음 시작 즉시 물러서기: $y")
    eq(a.onFocus(68, Focus.LOSS_TRANSIENT), Action.None, "이미 물러섬")
    a.onNavPlayers(99, 2)
    a.onNavPlayers(1_056, 1)
    ok(!a.canStart(2_000), "말 재생기가 남아 있음")
    a.onNavPlayers(2_215, 0)
    a.onFocus(2_230, Focus.GAIN)
    ok(!a.canStart(3_714), "3714 ms는 아직")
    ok(a.canStart(3_715), "내비 끝 + 1.5초 = 3715 ms")
}

private fun s19() {
    val a = VoiceArbiter()
    a.started(1)
    a.onNavPlayers(0, 1)
    a.onNavPlayers(99, 2)
    a.onNavPlayers(1_056, 1)
    ok(!a.canStart(1_056 + 1_500), "v0.6처럼 첫 재생기 끝 + 1.5초(2556 ms)에 시작하면 안 됨")
    a.onNavPlayers(2_215, 0)
    ok(!a.canStart(2_556), "내비 끝 뒤 341 ms")
}

private fun s20() {
    val a = VoiceArbiter()
    a.started(1)
    a.onNavPlayers(0, 1)
    a.onFocus(68, Focus.LOSS_TRANSIENT)
    a.onNavPlayers(2_215, 0)
    ok(!a.canStart(3_715), "GAIN 없이 1.5초만으로는 안 됨")
    ok(!a.canStart(6_714), "6714 ms는 아직")
    ok(a.canStart(6_715), "내비 끝 + 1.5초 + 3초")
}

private fun s21() {
    val a = VoiceArbiter()
    a.started(1)
    val y = a.onFocus(68, Focus.LOSS_TRANSIENT)
    ok(y is Action.Yield && y.via == "focus", "포커스로 물러서기: $y")
    a.onFocus(2_230, Focus.GAIN)
    ok(!a.canStart(3_729), "GAIN 뒤 1.5초 전")
    ok(a.canStart(3_730), "GAIN 뒤 1.5초")
    val b = VoiceArbiter()
    b.started(2)
    b.onFocus(68, Focus.LOSS_TRANSIENT)
    ok(!b.canStart(4_567) && b.canStart(4_568), "GAIN이 끝내 안 오면 잃은 뒤 4.5초")
}

private fun s22() {
    val a = VoiceArbiter()
    eq(a.onNavPlayers(0, 1), Action.None, "말하지 않으면 물러설 것 없음")
    ok(!a.canStart(100) && a.navActive, "내비 중 시작 금지")
    a.onNavPlayers(1_000, 0)
    ok(!a.canStart(2_499) && a.canStart(2_500), "끝 + 1.5초")
}

private fun s23() {
    val a = VoiceArbiter()
    a.onNavPlayers(90_000, 1)
    a.onNavPlayers(100_000, 0)
    ok(a.canStart(150_000), "오래전 내비 끝")
    a.started(3)
    ok(a.onFocus(200_000, Focus.LOSS_TRANSIENT) is Action.Yield, "새 포커스 잃음(예: 전화)")
    ok(!a.canStart(201_000), "잃은 동안 시작 금지")
    a.onFocus(201_000, Focus.GAIN)
    ok(!a.canStart(202_499) && a.canStart(202_500), "GAIN 뒤 1.5초")
}

private fun s24() {
    val led = MemoryLedger()
    val sch = sched(l = led)
    sch.submit(promise("rest.main", 0, est = 3_500), 0)
    val sim = Sim(sch)
    sim.nav(1_000, 1); sim.focus(1_068, Focus.LOSS_TRANSIENT); sim.nav(1_099, 2); sim.nav(2_056, 1); sim.nav(3_215, 0); sim.focus(3_230, Focus.GAIN)
    sim.run(0, 10_000) { t -> w(t) }
    eq(sim.log.map { Triple(it.t, it.short, it.attempt) }, listOf(Triple(0L, false, 1), Triple(4_800L, true, 2)), "멈췄다가 4.8초(내비 끝 3.215 + 1.5 → 다음 tick)에 짧게")
    eq(sim.yields, listOf(1_000L), "효과음 시작에 물러섬")
    eq(sim.spk.stopped.size, 1, "TTS 멈춤 1번")
    val sp = led.events.filterIsInstance<LedgerEvent.Spoken>()
    eq(sp.map { Triple(it.tMillis, it.yields, it.endMs) }, listOf(Triple(4_800L, 1, 7_600L)), "끝까지 들려준 기록(1.3.0: 길이 = 선언값과 글자 수 길이 중 큰 쪽)")
}

// ---------------------------------------------------------------- 2시간 쉬기
private fun s25() {
    val r = RestRig()
    r.run(0, 160 * MIN)
    eq(
        r.sim.log.map { it.t to it.text },
        listOf(
            6_600_000L to "10분쯤 뒤면 두 시간이에요. 다음 쉴 곳에서 쉬어 가요.",
            7_200_000L to "두 시간째예요. 약속대로 쉬어 가요.",
            8_103_600L to "쉬지 않고 두 시간 15분째예요. 가까운 곳에 잠깐 세워요.",   // 1.3.0: 본 알림(20자) 3.6초 뒤 15분
            9_009_400L to "쉬지 않고 두 시간 30분째예요. 가까운 곳에 잠깐 세워요.",   // 1.3.0: 앞 반복 문장(33자) 길이 5.75초(100 ms 걸음으로 5.8초) 뒤 15분
        ),
        "예고·본 알림·반복 2번",
    )
    eq(r.rest.endTrip(160 * MIN), "오늘은 두 시간 40분을 쉬지 않고 운전하셨어요. 다음엔 한 시간 50분쯤 쉬어 가요.", "요약 문장")
    val due = r.ledger.events.filterIsInstance<LedgerEvent.Due>().map { it.key to it.tMillis }
    eq(due, listOf("rest.headsup" to 6_600_000L, "rest.main" to 7_200_000L), "노출 기록")
    val m = RestCompliance.compute(r.ledger.events)
    eq(listOf(m.exposed, m.delivered, m.compliedAfterMain, m.keptWithinWindow, m.headsUpExposed), listOf(1, 1, 0, 0, 1), "지표(쉬지 않음)")
    eq(m.medianDeliveryLateMs, 0L, "창이 열려 있으면 바로 전달")
}

private fun s26() {
    val r = RestRig(win = ScriptWindows(normalOk = { t, _ -> t < 60_000 || t >= 240_000 }))
    r.restoreAt(119)
    r.run(0, 5 * MIN)
    eq(r.sim.log.map { it.t to it.text }, listOf(240_000L to "두 시간 3분째예요. 약속대로 쉬어 가요."), "그때 값으로")
}

private fun s27() {
    val r = RestRig()
    r.restoreAt(119)
    r.vehicle = { t -> if (t < 120_000 || t >= 1_080_000) DRIVE.copy(tMillis = t) else PARK.copy(tMillis = t) }
    r.run(0, 20 * MIN)
    eq(
        r.sim.log.map { it.t to it.text },
        listOf(60_000L to "두 시간째예요. 약속대로 쉬어 가요.", 1_080_000L to "잘 쉬셨어요. 약속 지키셨습니다."),
        "세워 둔 동안 반복은 말하지 않고, 쉼 뒤 출발할 때 칭찬",
    )
    val rt = r.ledger.events.filterIsInstance<LedgerEvent.RestTaken>()
    eq(rt.map { Triple(it.tMillis, it.restStartMs, it.segmentDrivingMs) }, listOf(Triple(1_020_000L, 120_000L, 7_259_900L)), "쉼 기록")
    ok(r.ledger.events.any { it is LedgerEvent.Dropped && it.key == "rest.main" && it.reason == "rested" }, "미룬 반복은 쉼으로 지움")
    eq(r.rest.drivingMs, 120_100L, "쉼 뒤 0부터 다시 셈(출발 18분 → 20분)")
}

private fun s28() {
    val r = RestRig()
    r.restoreAt(119)
    r.vehicle = { t -> if (t < 120_000 || t >= 960_000) DRIVE.copy(tMillis = t) else PARK.copy(tMillis = t) }
    r.run(0, 17 * MIN)
    eq(
        r.sim.log.map { it.t to it.text },
        listOf(60_000L to "두 시간째예요. 약속대로 쉬어 가요.", 963_600L to "쉬지 않고 두 시간 1분째예요. 가까운 곳에 잠깐 세워요."),
        "14분 정차 뒤 미룬 반복",
    )
    eq(r.ledger.events.count { it is LedgerEvent.RestTaken }, 0, "쉼 아님")
}

private fun s29() {
    val win = ScriptWindows(normalOk = { t, _ -> t >= 700_000 }, stopOk = { false })
    val r = RestRig(win = win)
    r.restoreAt(109)
    r.run(0, 12 * MIN)
    eq(r.sim.log.map { it.t to it.key }, listOf(700_000L to "rest.main"), "예고는 못 하고 본 알림만")
    eq(r.sch.digest.map { it.key to it.reason }, listOf("rest.headsup" to DigestReason.EXPIRED), "예고는 지나서 말하지 않음")
    eq(r.sim.log.single().text, "두 시간째예요. 약속대로 쉬어 가요.", "본 알림 문장")
}

private fun s30() {
    val r = RestRig()
    r.restoreAt(119)
    r.eta = { 10 * MIN }
    r.run(0, 2 * MIN)
    eq(r.sim.log.map { it.text }, listOf("곧 도착이에요. 도착하면 꼭 쉬어요."), "도착이 가까울 때")
}

private fun s31() {
    val r = RestRig()
    r.run(0, 30 * MIN)
    eq(r.rest.endTrip(30 * MIN), null, "2시간 전이면 요약 없음")
    ok(r.ledger.events.last() is LedgerEvent.TripEnded, "주행 끝 기록")
}

private fun s32() {
    val led = MemoryLedger()
    val last = 1_000_000L
    val st = RestTracker.State(100 * MIN, last, headsUpDone = false, mainDone = false, parkedAtSave = true)   // 1.3.0: 저장 때 P였다는 근거가 있어야 꺼진 시간을 쉼으로
    val a = RestTracker(RestPolicy(), sched(), led)
    a.restore(st, last + 16 * MIN)
    eq(a.drivingMs, 0L, "16분 꺼짐 = 쉼")
    eq(led.events.filterIsInstance<LedgerEvent.RestTaken>().map { it.restStartMs }, listOf(last), "쉬기 시작 = 마지막 주행 시각")
    val led2 = MemoryLedger()
    val b = RestTracker(RestPolicy(), sched(), led2)
    b.restore(st, last + 10 * MIN)
    eq(b.drivingMs, 100 * MIN, "10분 꺼짐은 이어서")
    b.update(last + 10 * MIN, PARK)
    b.update(last + 15 * MIN, PARK)
    eq(b.drivingMs, 0L, "세워 둔 채 5분 더 = 꺼진 시간 포함 15분")
    eq(led2.events.filterIsInstance<LedgerEvent.RestTaken>().map { it.restStartMs }, listOf(last), "쉬기 시작")
}

private fun s33() {
    val r = RestRig()
    r.vehicle = { t -> VehicleSnapshot(t, gear = Gear.D, speedMps = 0f) }
    r.run(0, 10 * MIN)
    eq(r.rest.drivingMs, 10 * MIN, "신호 대기도 운전 시간")
}

// ---------------------------------------------------------------- 지표
private fun s34() {
    val e = mutableListOf<LedgerEvent>()
    fun spoken(t: Long) = LedgerEvent.Spoken(t, "rest.main", 0, 0, t + 3_500, 0)
    // A: 예고·본 알림·10분 뒤 쉼(순응)
    e += LedgerEvent.Due(0, "rest.headsup", 110 * MIN); e += LedgerEvent.Due(600_000, "rest.main", 120 * MIN); e += spoken(600_500)
    e += LedgerEvent.RestTaken(600_500 + 25 * MIN, 600_500 + 10 * MIN, 0)
    // B: 들었지만 25분 뒤 쉼
    e += LedgerEvent.Due(10_000_000, "rest.main", 0); e += spoken(10_000_000); e += LedgerEvent.RestTaken(10_000_000 + 40 * MIN, 10_000_000 + 25 * MIN, 0)
    // C: 노출됐지만 전달 전 스스로 쉼(5분 뒤)
    e += LedgerEvent.Due(20_000_000, "rest.main", 0); e += LedgerEvent.RestTaken(20_000_000 + 20 * MIN, 20_000_000 + 5 * MIN, 0)
    // D: 예고 뒤 본 알림 전에 쉼
    e += LedgerEvent.Due(30_000_000, "rest.headsup", 0); e += LedgerEvent.RestTaken(30_000_000 + 19 * MIN, 30_000_000 + 4 * MIN, 0)
    // E: 들었지만 쉬지 않고 도착
    e += LedgerEvent.Due(40_000_000, "rest.main", 0); e += spoken(40_002_000); e += LedgerEvent.TripEnded(40_100_000, 0)
    val m = RestCompliance.compute(e)
    eq(listOf(m.segments, m.headsUpExposed, m.keptOnHeadsUp, m.exposed, m.delivered, m.compliedAfterMain, m.keptWithinWindow), listOf(5, 2, 1, 4, 3, 1, 2), "구간별 셈")
    eq(m.deliveryRate, 0.75, "전달률 3/4")
    eq(m.complianceRate, 1.0 / 3, "순응률 1/3")
    eq(m.keptRate, 0.5, "약속 지킴 2/4")
    eq(m.medianDeliveryLateMs, 500L, "전달 지연 중앙값")
    eq(RestCompliance.compute(emptyList()).complianceRate, null, "기록 없으면 비율 없음(0이 아님)")
}

private fun s35() {
    val r = RestRig()
    r.restoreAt(119)
    r.vehicle = { t -> if (t < 600_000 || t >= 1_800_000) DRIVE.copy(tMillis = t) else PARK.copy(tMillis = t) }
    r.run(0, 40 * MIN)
    val m = RestCompliance.compute(r.ledger.events)
    eq(listOf(m.exposed, m.delivered, m.compliedAfterMain, m.keptWithinWindow), listOf(1, 1, 1, 1), "순응 1/1")
    eq(m.complianceRate, 1.0, "순응률")
}

// ---------------------------------------------------------------- 리플레이·JSON·문장
internal const val PLAN_SAMPLE = """{
  "meta": {"name": "병원→집", "speedup": 3.0, "simulated": true, "origin": [37.5665, 126.9780], "schema": "0.2", "start": "2026-11-14T16:50:00+09:00"},
  "location": [
    {"t": 0,  "lat": 37.56650, "lon": 126.97800},
    {"t": 10, "lat": 37.56712, "lon": 126.97905},
    {"t": 20, "lat": 37.56790, "lon": 126.98010}
  ],
  "navi": [
    {"t": 0,   "type": "trip",    "etaLocal": "17:28", "remainM": 8200},
    {"t": 12,  "type": "tbt",     "turn": "LEFT", "distM": 700},
    {"t": 30,  "type": "tbt",     "turn": "LEFT", "distM": 140},
    {"t": 40,  "type": "caution", "kind": "SCHOOL_ZONE", "distM": 200},
    {"t": 95,  "type": "caution", "kind": "WRONG_WAY_DRIVING_ALERT", "distM": 0},
    {"t": 120, "type": "caution", "kind": "DOWNHILL_CAUTION_AREA", "distM": 300},
    {"t": 150, "type": "arrived"}
  ],
  "vehicle": [
    {"t": 0, "gear": "D", "speedKph": 0},
    {"t": 5, "speedKph": 38},
    {"t": 60, "turnSignal": "LEFT"},
    {"t": 63, "turnSignal": "OFF"},
    {"t": 148, "speedKph": 0, "gear": "P"}
  ]
}"""

private fun s36() {
    val trip = ReplayParser.parse(PLAN_SAMPLE)
    val base = trip.meta.baseEpochMs
    eq(trip.meta.name, "병원→집", "이름")
    eq(base, 1_794_642_600_000L, "기준 = meta.start(2026-11-14 16:50 KST)")
    eq(listOf(trip.location.size, trip.navi.size, trip.vehicle.size), listOf(3, 7, 5), "항목 수")
    val tr = trip.navi[0] as NaviEvent.Trip
    eq(tr.etaEpochMs - base, 38 * MIN, "17:28 도착 = 출발 38분 뒤")
    eq(trip.navi[1], NaviEvent.Tbt(base + 12_000, 700, TurnType.LEFT), "TBT")
    eq((trip.navi[3] as NaviEvent.Caution).kind, CautionKind.SCHOOL_ZONE, "어린이보호구역")
    eq((trip.navi[4] as NaviEvent.Caution).kind, CautionKind.WRONG_WAY_DRIVING_ALERT, "역주행")
    ok(trip.navi.last() is NaviEvent.Arrived && trip.endMs == base + 150_000, "도착 150초")
    val v61 = trip.vehicleAt(base + 61_000)!!
    eq(v61.turnSignal, Turn.LEFT, "61초 방향지시등")
    ok(v61.speedMps != null && kotlin.math.abs(v61.speedMps!! - 38f / 3.6f) < 1e-3, "38 km/h → m/s")
    val last = trip.vehicleSnapshots().last()
    ok(last.parked && last.speedMps == 0f && last.turnSignal == Turn.OFF, "끝 상태 P: $last")
    val shifted = ReplayParser.parse(PLAN_SAMPLE, baseEpochMs = 1_000)
    eq((shifted.navi[0] as NaviEvent.Trip).etaEpochMs, 1_000 + 38 * MIN, "기준을 옮겨도 ETA는 상대 시간")
}

private fun s37() {
    val bad1 = throws<IllegalArgumentException>("모르는 type") {
        ReplayParser.parse("""{"meta":{"name":"x","schema":"drivepromise.trip/0.2"},"navi":[{"t":0,"type":"teleport"}]}""")
    }
    ok(bad1.message!!.contains("navi[0].type"), "위치 표시: ${bad1.message}")
    val bad2 = throws<IllegalArgumentException>("옛 스키마") { ReplayParser.parse("""{"meta":{"name":"x","schema":"0.1"}}""") }
    ok(bad2.message!!.contains("meta.schema"), "스키마: ${bad2.message}")
    val bad3 = throws<IllegalArgumentException>("시작 시각 없는 etaLocal") {
        ReplayParser.parse("""{"meta":{"name":"x","schema":"0.2"},"navi":[{"t":0,"type":"trip","etaLocal":"17:00","remainM":1}]}""")
    }
    ok(bad3.message!!.contains("meta.start"), "meta.start 안내: ${bad3.message}")
    val bad4 = throws<IllegalArgumentException>("음수 시각") {
        ReplayParser.parse("""{"meta":{"name":"x","schema":"0.2"},"vehicle":[{"t":-1,"gear":"D"}]}""")
    }
    ok(bad4.message!!.contains("vehicle[0].t"), "시각: ${bad4.message}")
    throws<IllegalArgumentException>("모르는 기어") { ReplayParser.parse("""{"meta":{"name":"x","schema":"0.2"},"vehicle":[{"t":0,"gear":"Q"}]}""") }
}

private fun s38() {
    val v = MiniJson.parse("""{"a":[1,2.5,-3e2,true,false,null,"x\"y\\é€\n"],"b":{}}""") as Map<*, *>
    val a = v["a"] as List<*>
    eq(a.take(6), listOf<Any?>(1L, 2.5, -300.0, true, false, null), "값")
    eq(a[6], "x\"y\\é€\n", "이스케이프·유니코드")
    eq(v["b"], emptyMap<String, Any?>(), "빈 객체")
    val e = throws<IllegalArgumentException>("깨진 JSON") { MiniJson.parse("""{"a":}""") }
    ok(e.message!!.contains("위치 5"), "오류 위치: ${e.message}")
    throws<IllegalArgumentException>("뒤에 남은 글자") { MiniJson.parse("[1] 2") }
}

private fun s39() {
    eq(Phrases.koDuration(110), "한 시간 50분", "1:50")
    eq(Phrases.koDuration(120), "두 시간", "2:00")
    eq(Phrases.koDuration(125), "두 시간 5분", "2:05")
    eq(Phrases.koDuration(45), "45분", "0:45")
    eq(Phrases.koDuration(13 * 60L), "13시간", "13시간은 숫자")
    eq(Phrases.enDuration(125), "2 hours 5 minutes", "영어 2:05")
    eq(Phrases.enDuration(61), "1 hour 1 minute", "영어 단수")
}

private fun s40() {
    val r = RestRig(win = ScriptWindows(normalOk = { t, _ -> t < 60_000 || t >= 240_000 }))
    r.lang = Lang.EN
    r.restoreAt(119)
    r.run(0, 5 * MIN)
    eq(r.sim.log.map { it.text }, listOf("2 hours 3 minutes now. Let's rest as promised."), "영어 본 알림")
    eq(Phrases.digestNoRest(Lang.EN, 140 * MIN, RestPolicy()), "You drove 2 hours 20 minutes without a break today. Next time, rest around 1 hour 50 minutes.", "영어 요약")
}

private fun s41() {
    val sch = sched()
    sch.submit(promise("p", 0, speakWhen = { ww -> ww.vehicle?.driving == true }), 0)
    val sim = Sim(sch)
    sim.run(0, 10_000) { t -> w(t, v = if (t < 5_000) PARK.copy(tMillis = t) else DRIVE.copy(tMillis = t)) }
    eq(sim.log.map { it.t }, listOf(5_000L), "출발하자마자")
    eq(sch.digest.size, 0, "미루는 동안 버리지 않음")
}
