package app.drivepromise.core.speech

import app.drivepromise.core.ledger.LedgerEvent
import app.drivepromise.core.ledger.LedgerSink

/**
 * 발화 정책 v0.4 — 무엇을 언제 말할지 정합니다(순수 로직, 앱 루프가 10 Hz로 [tick]).
 * 순서: SAFETY > PROMISE(마감 순) > INFO(먼저 온 순). 한 번에 하나만 말합니다.
 * 말하기 직전에 ‘아직 맞는 말인가’를 다시 판정하고([Utterance.stillValid]) 문장은 그때 값으로 만듭니다([Utterance.render]).
 *
 * 늦은 알림(LATE_OK): 상태 지속형은 주행 중 버리지 않음 · 지점·시각 마감형은 0초 · 정보는 60초 → 정차 후 요약.
 * 물러서기(G1-B): 끊긴 말은 짧은 문장으로 처음부터 다시, 두 번 끊기면 신호 대기 창·확실한 틈에서만.
 */
class SpeechScheduler(
    private val policy: QuietPolicy,
    private val windows: QuietWindows,
    private val ledger: LedgerSink = LedgerSink { },
) {
    private class Pending(
        val id: Long,
        val u: Utterance,
        val createdAt: Long,
        var notBefore: Long,
        val repeat: Int,
    ) {
        var yields = 0
        var failures = 0
        var startedAt = 0L
        var startedReal = 0L
        var needMs = 0L
    }

    private val queue = mutableListOf<Pending>()
    private var speaking: Pending? = null
    private var nextId = 1L
    private val digestItems = mutableListOf<DigestItem>()

    /** 정차 후 요약 후보(기록 순) */
    val digest: List<DigestItem> get() = digestItems.toList()
    val pendingKeys: List<String> get() = ordered().map { it.u.key }
    val speakingKey: String? get() = speaking?.u?.key
    val speakingId: Long? get() = speaking?.id

    /**
     * 1.3.0(C05): 이 문장을 말하는 데 걸릴 시간 — 선언 길이와 글자 수 길이 중 큰 쪽. 회전 앞 ‘끝낼 시간 확보’를 이것으로 봅니다.
     * 4초로 선언한 49자 문장이 실제 8.0초였던 11/7 실측(codex) 때문 — 선언값만 보면 침묵 구간으로 최대 4초 넘칠 수 있었음.
     */
    fun needMs(declaredMs: Long, text: String, lang: Lang): Long {
        val per = if (lang == Lang.EN) policy.msPerCharEn else policy.msPerCharKo
        return maxOf(declaredMs, text.trim().length * per + policy.speechPadMs)   // 글자 수는 띄어쓰기 포함(실측 기록 chars와 같은 기준)
    }

    /**
     * 1.3.0(C14): 말하는 중인데 완료 소식이 너무 늦으면 그 말의 id(감시 타이머) — 아니면 null.
     * 시각은 실제 시계(realNowMs) — 리플레이는 가상 시계가 배속으로 가지만 말소리는 실제 시간으로 나오기 때문
     */
    fun watchdog(realNowMs: Long): Long? {
        val p = speaking ?: return null
        val limit = maxOf(policy.watchdogMinMs, (p.needMs * policy.watchdogFactor).toLong())
        return if (realNowMs - p.startedReal > limit) p.id else null
    }

    /**
     * ‘조용히’(음성 명령) — 이 시각까지 INFO 말은 미룸. 안전·약속(SAFETY·PROMISE)과, 운전자가 직접 청한 말
     * (‘다시’의 repeat, 음성 대답 voice.*)은 그대로 말함. 미뤄진 말은 대기열에 남아 유효성 규칙대로 정리됩니다.
     */
    var muteInfoUntilMs = 0L

    fun submit(u: Utterance, nowMs: Long): Long {
        val p = Pending(nextId++, u, nowMs, nowMs, 0)
        queue += p
        return p.id
    }

    /** key가 keyPrefix로 시작하는 대기 말을 지움(말하는 중인 것은 그대로) */
    fun cancel(keyPrefix: String, nowMs: Long, reason: String = "cancel"): Int {
        val gone = queue.filter { it.u.key.startsWith(keyPrefix) }
        queue.removeAll(gone.toSet())
        gone.forEach { ledger.accept(LedgerEvent.Dropped(nowMs, it.u.key, reason)) }
        return gone.size
    }

    /** voiceFree = [VoiceArbiter.canStart] (내비가 말하는 중·끝난 직후면 false) */
    fun tick(w: World, voiceFree: Boolean, realNowMs: Long = w.tMillis): Step {
        val now = w.tMillis
        for (p in queue.toList()) housekeep(p, w)   // 말할 수 없더라도 유효성 정리는 매번
        if (speaking != null || !voiceFree) return Step.Idle
        for (p in ordered()) {
            if (p.notBefore > now || !p.u.speakWhen(w)) continue
            if (p.u.priority == Priority.INFO && now < muteInfoUntilMs && p.u.key != "repeat" && !p.u.key.startsWith("voice.")) continue
            val short = p.yields > 0
            val text = p.u.render(SayCtx(w, p.repeat, short))   // 1.3.0: 먼저 만들어 실제 길이로 창을 봄(render는 값만 읽음)
            val need = needMs(if (short) p.u.estShortMs else p.u.estMs, text, w.lang)
            if (!windowOk(p, now, need)) continue
            queue.remove(p)
            speaking = p
            p.startedAt = now
            p.startedReal = realNowMs
            p.needMs = need
            return Step.Speak(p.id, p.u.key, text, p.u.priority, short, p.repeat, p.yields + p.failures + 1, need)
        }
        return Step.Idle
    }

    /** 말이 끝까지 나감 → 반복이 있으면 repeatAfterMs 뒤로 예약(주행이 끝난 뒤 끝난 말은 반복하지 않음 — 11/6 1.2.1) */
    fun spoken(id: Long, nowMs: Long) {
        val p = speaking?.takeIf { it.id == id } ?: return
        speaking = null
        ledger.accept(LedgerEvent.Spoken(p.startedAt, p.u.key, p.repeat, p.yields, nowMs, p.u.deadlineAtMs?.let { p.startedAt - it }))
        val ra = p.u.repeatAfterMs
        if (!tripEnded && ra != null && p.repeat < p.u.maxRepeats) queue += Pending(nextId++, p.u, nowMs, nowMs + ra, p.repeat + 1)
    }

    /** [endTrip] 뒤면 true — 그 뒤엔 반복을 예약하지 않음 */
    var tripEnded = false
        private set

    /** 내비에 물러섬(VoiceArbiter의 Yield) — 같은 말을 대기열로 되돌림(다음엔 짧은 문장) */
    fun yielded(id: Long, nowMs: Long) {
        val p = speaking?.takeIf { it.id == id } ?: return
        speaking = null
        p.yields++
        ledger.accept(LedgerEvent.Yielded(nowMs, p.u.key, p.yields))
        queue += p
    }

    /** TTS 오류 — maxFailures번이면 요약으로, 아니면 다시 대기 */
    fun failed(id: Long, nowMs: Long, cause: String) {
        val p = speaking?.takeIf { it.id == id } ?: return
        speaking = null
        p.failures++
        if (p.failures >= policy.maxFailures) {
            toDigest(nowMs, p.u, DigestReason.TTS_FAILED)
            ledger.accept(LedgerEvent.Dropped(nowMs, p.u.key, "tts_failed:$cause"))
        } else {
            p.notBefore = nowMs + policy.failRetryMs   // 1.3.0(C14): 바로 다시 하지 않고 잠깐 뒤(TTS 서비스 재연결 시간)
            queue += p
        }
    }

    /** 주행 끝(도착·긴 정차) — 남은 약속·정보 말은 요약으로, 안전 말과 ‘이미 한 말의 반복 예약’은 버림(11/5: 반복은 못 전한 말이 아님) */
    fun endTrip(nowMs: Long) {
        tripEnded = true   // 11/6: 주행 끝 순간 말하던 문장(예: 쉬기 본 알림)이 끝나도 15분 뒤 반복을 걸지 않게
        for (p in ordered()) {
            queue.remove(p)
            if (p.u.priority == Priority.SAFETY || p.repeat > 0) {
                ledger.accept(LedgerEvent.Dropped(nowMs, p.u.key, if (p.repeat > 0) "trip_end_repeat" else "trip_end"))
            } else {
                toDigest(nowMs, p.u, DigestReason.TRIP_END)
                ledger.accept(LedgerEvent.Digested(nowMs, p.u.key, "trip_end"))
            }
        }
    }

    private fun housekeep(p: Pending, w: World) {
        val now = w.tMillis
        val u = p.u
        when (u.priority) {
            Priority.PROMISE -> {
                val deadline = u.deadlineAtMs!!
                val pastHard = u.validity == Validity.UNTIL_DEADLINE && now > deadline + policy.lateOkUntilDeadlineMs
                if (pastHard || !u.stillValid(w)) {
                    queue.remove(p)
                    if (u.validity == Validity.UNTIL_DEADLINE) {
                        toDigest(now, u, DigestReason.EXPIRED)
                        ledger.accept(LedgerEvent.Dropped(now, u.key, "expired"))
                        u.followup(w)?.let { submit(it, now) }
                    } else {
                        toDigest(now, u, DigestReason.STATE_ENDED)
                        ledger.accept(LedgerEvent.Dropped(now, u.key, "state_ended"))
                    }
                    return
                }
                if (u.validity == Validity.WHILE_STATE && !policy.keepWhileStateWhileDriving &&
                    now - maxOf(deadline, p.notBefore) > policy.infoMaxWaitMs
                ) {
                    queue.remove(p)
                    toDigest(now, u, DigestReason.INFO_TIMEOUT)
                    ledger.accept(LedgerEvent.Digested(now, u.key, "late"))
                }
            }
            Priority.INFO -> if (now - p.createdAt > policy.infoMaxWaitMs) {
                queue.remove(p)
                if (policy.infoOverflow == InfoOverflow.REST_DIGEST) {
                    toDigest(now, u, DigestReason.INFO_TIMEOUT)
                    ledger.accept(LedgerEvent.Digested(now, u.key, "info_timeout"))
                } else {
                    ledger.accept(LedgerEvent.Dropped(now, u.key, "info_timeout"))
                }
            }
            Priority.SAFETY -> Unit
        }
    }

    private fun windowOk(p: Pending, now: Long, need: Long): Boolean {
        val u = p.u
        if (u.priority == Priority.SAFETY) return true
        if (p.yields >= policy.maxYieldsBeforeStopWindow) {
            return windows.stopWindow(now) || windows.longGap(now, need + policy.longGapExtraMs)
        }
        if (windows.normal(now, need)) return true
        return u.priority == Priority.PROMISE && now >= u.deadlineAtMs!! - policy.promiseEscalateWithinMs && windows.stopWindow(now)
    }

    private fun toDigest(now: Long, u: Utterance, reason: DigestReason) {
        digestItems += DigestItem(now, u.key, reason, u)
    }

    private fun ordered(): List<Pending> = queue.sortedWith(
        compareBy<Pending>(
            { it.u.priority.ordinal },
            { if (it.u.priority == Priority.PROMISE) it.u.deadlineAtMs!! else 0L },
            { it.createdAt },
            { it.id },
        ),
    )
}
