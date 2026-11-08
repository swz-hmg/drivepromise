package app.drivepromise.core.ui

/**
 * 화면 모드(화면모드전환설계 v0.1, 11/4) — 주행 중 ‘화면 + 음성(DO)’과 ‘음성만’, 세워 둔 동안 ‘정차 화면’.
 * 판단·발화는 세션(포그라운드 서비스)에서 모드와 상관없이 계속 돌고, 모드는 ‘보여 주는 층’만 고릅니다.
 *
 * 11/5 Q036: DO(주행 중 허용 화면)가 인정된다고 가정하고 주행 화면을 넣음. 가정이 틀린 환경(플랫폼이 화면을 가림)에서는
 * 차단 감지로 즉시 DRIVE_VOICE 로 내려가므로 음성은 끊기지 않습니다(바닥 모드).
 */
enum class PresentationMode {
    /** 세워 둠 — 약속 타일·계획·요약·설정(전체 화면) */
    PARKED,

    /** 주행 중, 화면 + 음성 — DO 화면(약속 상태·마지막 문장·큰 버튼 둘: 다시 들려줘·말하기) */
    DRIVE_DO,

    /** 주행 중, 음성만 — 화면은 플랫폼(내비·차단 화면)에 맡김 */
    DRIVE_VOICE,
}

/** 플랫폼이 주행 중 화면을 허용하는가 */
enum class Capability { YES, NO, UNKNOWN }

/** 사용자 설정 — 기본 ON(가능하면 화면) */
enum class ScreenWhileDriving { ON, VOICE_ONLY }

object ModeResolver {
    /** 결정표: 세워 둠이면 PARKED · 주행 중엔 (플랫폼 YES ∧ 사용자 ON)일 때만 DRIVE_DO, 모르면(UNKNOWN) 안전한 쪽(DRIVE_VOICE) */
    fun resolve(driving: Boolean, cap: Capability, user: ScreenWhileDriving): PresentationMode = when {
        !driving -> PresentationMode.PARKED
        cap == Capability.YES && user == ScreenWhileDriving.ON -> PresentationMode.DRIVE_DO
        else -> PresentationMode.DRIVE_VOICE
    }

    /**
     * 전환 규칙(히스테리시스): 내려가기(DRIVE_DO → DRIVE_VOICE)는 주행 중에도 즉시, 올라가기(DRIVE_VOICE → DRIVE_DO)는
     * 세워 둔 동안(PARKED 에서 출발할 때)만. 세워 둠 ↔ 주행 전환은 언제나 — 주행 중 갑자기 화면이 켜져 시선을 끄는 일을 막습니다.
     */
    fun next(current: PresentationMode, resolved: PresentationMode, driving: Boolean): PresentationMode {
        if (resolved == current) return current
        if (!driving || current == PresentationMode.PARKED) return resolved
        return if (resolved == PresentationMode.DRIVE_VOICE) PresentationMode.DRIVE_VOICE else current
    }

    /**
     * 플랫폼 능력 판단 — 차단을 한 번이라도 봤으면 NO · 플랫폼이 ‘허용’이라 답했거나 DO 인정 가정이 켜져 있으면 YES ·
     * 플랫폼이 ‘거부’라 답하면 NO · 그 밖은 UNKNOWN. (가정은 11/5 Q036 기본값; 에뮬레이터에서는 플랫폼이 거부로 답함 — G1-B B1)
     */
    fun capability(platformOptimized: Boolean?, assumeDo: Boolean, blockedSeen: Boolean): Capability = when {
        blockedSeen -> Capability.NO
        platformOptimized == true || assumeDo -> Capability.YES
        platformOptimized == false -> Capability.NO
        else -> Capability.UNKNOWN
    }
}
