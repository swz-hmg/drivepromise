package app.drivepromise.core.speech

/** 재생 용도 — 1순위 NAVIGATION_GUIDANCE(G1-B v0.6 확정: ASSISTANT는 내비 안내를 막아 금지), 2순위 NOTIFICATION(v0.7 확인 중) */
enum class AudioUsage { NAVIGATION_GUIDANCE, NOTIFICATION }

/** 말하기 — 플랫폼 구현(Pleos TextToSpeech ON_DEVICE). 결과는 [VoiceLoop.onDone]·[VoiceLoop.onError]로 알려 줌 */
interface Speaker {
    fun speak(id: Long, text: String, usage: AudioUsage)
    fun stop(id: Long)
}

/**
 * 스케줄러·조정자·말하기를 잇는 얇은 고리 — 앱 서비스와 시험이 같은 배선을 씁니다.
 * 앱: 10 Hz [tick] · 재생 목록 콜백 → [onNavPlayers] · 포커스 콜백 → [onFocus] · TTS 끝/오류 → [onDone]/[onError]
 */
class VoiceLoop(
    val scheduler: SpeechScheduler,
    val arbiter: VoiceArbiter,
    private val speaker: Speaker,
    var usage: AudioUsage = AudioUsage.NAVIGATION_GUIDANCE,
) {
    /** 1.3.0(C14): 감시 타이머가 말을 멈췄을 때(id, key) — 앱이 기록 */
    var onWatchdog: (Long, String) -> Unit = { _, _ -> }

    /** realNowMs = 실제 시계(감시 타이머용). 리플레이가 아니면 w.tMillis 와 같음 */
    fun tick(w: World, realNowMs: Long = w.tMillis): Step {
        val stuck = scheduler.watchdog(realNowMs)
        if (stuck != null) {   // 완료·오류 소식 없이 너무 오래 — 멈추고 오류로 처리(3번이면 요약으로, 아니면 2초 뒤 다시)
            val key = scheduler.speakingKey ?: "?"
            speaker.stop(stuck)
            arbiter.finished(stuck)
            scheduler.failed(stuck, w.tMillis, "watchdog")
            onWatchdog(stuck, key)
        }
        val s = scheduler.tick(w, arbiter.canStart(w.tMillis), realNowMs)
        if (s is Step.Speak) {
            arbiter.started(s.id)
            speaker.speak(s.id, s.text, usage)
        }
        return s
    }

    fun onNavPlayers(nowMs: Long, active: Int): VoiceArbiter.Action = handle(arbiter.onNavPlayers(nowMs, active), nowMs)

    fun onFocus(nowMs: Long, change: VoiceArbiter.Focus): VoiceArbiter.Action = handle(arbiter.onFocus(nowMs, change), nowMs)

    fun onDone(id: Long, nowMs: Long) {
        arbiter.finished(id)
        scheduler.spoken(id, nowMs)
    }

    fun onError(id: Long, nowMs: Long, cause: String) {
        arbiter.finished(id)
        scheduler.failed(id, nowMs, cause)
    }

    private fun handle(a: VoiceArbiter.Action, nowMs: Long): VoiceArbiter.Action {
        if (a is VoiceArbiter.Action.Yield) {
            speaker.stop(a.id)
            scheduler.yielded(a.id, nowMs)
        }
        return a
    }
}
