package app.drivepromise

import app.drivepromise.core.ui.Capability
import app.drivepromise.core.ui.ModeResolver
import app.drivepromise.core.ui.PresentationMode
import app.drivepromise.core.ui.ScreenWhileDriving

/**
 * 화면 모드 결정(앱 쪽 배선) — 코어 [ModeResolver] 에 입력을 모아 넣고 바뀔 때만 알립니다.
 * 입력: 주행 중인가(세션 단계) · 플랫폼 DO 답(isActivityDistractionOptimized) · DO 인정 가정(설정, 11/5 Q036 기본 켬) ·
 *       차단 감지(주행 화면이 UX 제한 중에 가려짐) · 사용자 설정(켜기/음성만) · 시연 강제값(디버그 빌드만)
 * 출력: [mode] (PARKED / DRIVE_DO / DRIVE_VOICE) · [launchSeq] (PARKED→DRIVE_DO 전환 횟수 — 정차 화면이 주행 화면을 한 번만 띄우게)
 */
class ScreenModeController(private val onChange: (PresentationMode, PresentationMode, String) -> Unit) {
    var platformOptimized: Boolean? = null
    var assumeDo: Boolean = true
    var user: ScreenWhileDriving = ScreenWhileDriving.ON
    var blockedSeen: Boolean = false
        private set
    var force: PresentationMode? = null
    var mode: PresentationMode = PresentationMode.PARKED
        private set
    var launchSeq: Int = 0
        private set
    private var lastWhy = "init"

    val capability: Capability get() = ModeResolver.capability(platformOptimized, assumeDo, blockedSeen)

    /** 주행 화면이 UX 제한 중에 가려졌다(onStop) — 이번 서비스 수명 동안 기억(다음 정차 뒤에도 음성만) */
    fun markBlocked(why: String) {
        if (!blockedSeen) { blockedSeen = true; lastWhy = "blocked:$why" }
    }

    /** 시연·실측용 초기화(다음 출발에 다시 화면을 시도) */
    fun clearBlocked() { blockedSeen = false }

    /** 매 tick — 입력으로 모드를 다시 구함(히스테리시스는 코어 next()). 바뀌면 onChange(이전, 새, 이유) */
    fun update(driving: Boolean, why: String = "tick"): PresentationMode {
        val resolved = force ?: ModeResolver.resolve(driving, capability, user)
        val next = if (force != null) force!! else ModeResolver.next(mode, resolved, driving)
        if (next != mode) {
            val prev = mode
            mode = next
            if (next == PresentationMode.DRIVE_DO) launchSeq++
            val reason = if (lastWhy.startsWith("blocked")) lastWhy else why
            lastWhy = "tick"
            onChange(prev, next, reason)
        }
        return mode
    }

    fun describe(): String = "mode=$mode cap=$capability(platform=$platformOptimized assume=$assumeDo blocked=$blockedSeen) user=$user force=$force"
}
