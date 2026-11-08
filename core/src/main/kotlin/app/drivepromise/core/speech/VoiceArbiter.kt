package app.drivepromise.core.speech

/**
 * 내비 안내와 겹치면 운전약속이 물러섭니다 — G1-B v0.6 실측으로 정한 규칙(v0.7에서 재확인 예정).
 *
 * 입력(빠른 순): ① 재생 목록의 내비 재생기 수 — 첫 소리(효과음)가 내비 말 요청보다 62 ms 앞섬
 *               ② 오디오 포커스 변화 — 우리 쪽 LOSS_TRANSIENT는 +68 ms, GAIN은 내비가 끝날 때
 *               (TBT로 예측한 안내는 시작 전 판단, 곧 [QuietWindows]가 맡습니다)
 * 말하는 중 내비가 시작되면 [Action.Yield] — 250 ms 안에 멈춤(실측 겹침 217 ms, 목표 ≤ 300 ms).
 * 다시 말하기: 내비 재생기가 모두 끝나고 1.5초 + 포커스 GAIN(오지 않으면 3초 더).
 * 내비 안내 한 번 = 재생기 2개(효과음 ≈1.05초, 말 ≈2.1초 +99 ms) — 첫 재생기만 보면 말 중간에 다시 시작합니다(v0.6 결함).
 * 1.3.0b(11/8 실행 run_1108_212028): 포커스 GAIN 뒤에도 내비 재생기 하나가 목록에 26초 남아(재생은 끝남) 계획 문장이 다시 나가지 못함
 *   → GAIN이 마지막 ‘재생기 늘어남’보다 뒤이고, 그 뒤 [staleAfterGainMs] 동안 목록이 그대로면 남은 재생기는 끝난 것으로 봄.
 *     내비가 다시 말하면 재생기가 늘거나 포커스를 다시 가져가므로 그때 다시 물러섭니다.
 */
class VoiceArbiter(
    val gapMs: Long = 1_500,
    val gainWaitMs: Long = 3_000,
    val stopBudgetMs: Long = 250,
    val staleAfterGainMs: Long = 4_000,
) {
    enum class Focus { GAIN, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK, LOSS }

    sealed interface Action {
        data object None : Action

        /** id 말을 stopWithinMs 안에 멈추고 스케줄러에 yielded(id)를 알림. via = play(재생 목록)·focus */
        data class Yield(val id: Long, val stopWithinMs: Long, val via: String) : Action
    }

    private var navPlayers = 0
    private var navEndedAt: Long? = null
    private var focusLostAt: Long? = null
    private var regainAt: Long? = null
    private var speakingId: Long? = null
    private var navIncreasedAt: Long? = null
    private var navChangedAt: Long? = null

    /** 1.3.0b: GAIN 뒤 그대로 남은 내비 재생기를 끝난 것으로 본 횟수(기록용) */
    var staleCount = 0
        private set
    private var staleNoted = false

    val navActive: Boolean get() = navPlayers > 0

    /** 재생 목록 변화: 우리 앱이 아닌 ‘내비 안내 용도’ 재생기 중 재생 중인 수(우리 재생기는 플랫폼이 뺌) */
    fun onNavPlayers(nowMs: Long, active: Int): Action {
        val was = navPlayers
        navPlayers = active.coerceAtLeast(0)
        if (navPlayers != was) navChangedAt = nowMs
        if (navPlayers > was) { navIncreasedAt = nowMs; staleNoted = false }
        if (navPlayers > 0) {
            navEndedAt = null
            return yieldIfSpeaking("play")
        }
        if (was > 0) navEndedAt = nowMs
        return Action.None
    }

    /** 우리가 포커스를 쥔 동안 오는 변화(포커스 요청 결과가 아니라 변화 콜백) */
    fun onFocus(nowMs: Long, change: Focus): Action {
        if (change == Focus.GAIN) {
            if (focusLostAt != null) regainAt = nowMs
            focusLostAt = null
            return Action.None
        }
        if (focusLostAt == null) focusLostAt = nowMs
        return yieldIfSpeaking("focus")
    }

    /** 지금 새로 말을 시작해도 되는가 */
    fun canStart(nowMs: Long): Boolean {
        if (navPlayers > 0) {
            if (!staleAfterGain(nowMs)) return false
            if (!staleNoted) { staleNoted = true; staleCount++ }
            return true
        }
        val end = navEndedAt
        val lost = focusLostAt
        if (lost != null) {   // GAIN 전: 내비가 보였으면 내비 끝, 아니면 잃은 시각부터 1.5초 + 3초
            val ref = if (end != null && end >= lost) end else lost
            return nowMs >= ref + gapMs + gainWaitMs
        }
        if (end != null && nowMs < end + gapMs) return false
        val re = regainAt   // 재생 목록에 내비가 안 보인 경우: GAIN 뒤 1.5초
        if (re != null && (end == null || re > end + gapMs) && nowMs < re + gapMs) return false
        return true
    }

    /** GAIN이 마지막 재생기 늘어남보다 뒤이고, GAIN·마지막 변화 뒤 [staleAfterGainMs] 동안 목록이 그대로 */
    private fun staleAfterGain(nowMs: Long): Boolean {
        val re = regainAt ?: return false
        if (focusLostAt != null) return false
        val inc = navIncreasedAt ?: return false
        if (inc > re) return false
        val ref = maxOf(re, navChangedAt ?: re)
        return nowMs >= ref + staleAfterGainMs
    }

    fun started(id: Long) {
        speakingId = id
    }

    fun finished(id: Long) {
        if (speakingId == id) speakingId = null
    }

    private fun yieldIfSpeaking(via: String): Action {
        val id = speakingId ?: return Action.None
        speakingId = null
        return Action.Yield(id, stopBudgetMs, via)
    }
}
