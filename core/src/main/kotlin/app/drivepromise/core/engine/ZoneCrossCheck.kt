package app.drivepromise.core.engine

import app.drivepromise.core.model.PromiseStatus

/**
 * 1.3.5(11/10 전체 점검 A4·D4): 약속 구역 이중 확인(Pleos 근접 알림 ↔ 코어 거리 판정)의 규칙을 코어 한 곳에 둠.
 *
 * 1.3.2에서 코어가 밖/안을 바꾸려면 위치 [EnginePolicy.zoneConfirmFixes]번·[EnginePolicy.zoneConfirmMs]가 필요해졌는데,
 * 앱의 비교 시점은 1.1.0 실측에 맞춘 3초 그대로여서 두 방향 모두 ‘불일치’가 나오는 구조였음(결합 결함).
 * 이제 앱은 비교 시점을 이 값에서 계산함 — 코어 정책이 바뀌면 비교 시점도 함께 바뀜(S127이 둘을 묶어 확인).
 */
object ZoneCrossCheck {
    /** 코어 확인 시간 뒤 여유 — 위치 갱신 간격(약 1초)·10 Hz 판정·알림이 첫 위치보다 먼저 오는 몫(실측 47~88 ms)을 덮음 */
    const val MARGIN_MS = 3_000L

    /** 근접 알림을 받은 뒤 코어 상태와 비교하기까지 기다릴 시간 */
    fun delayMs(p: EnginePolicy): Long = p.zoneConfirmMs + MARGIN_MS

    /** 근접 알림의 안/밖과 코어 상태가 같은 쪽인가 — 코어가 모름·해당 없음이면 일치로 세지 않음 */
    fun agree(inside: Boolean, core: PromiseStatus?): Boolean {
        val known = core != null && core != PromiseStatus.UNKNOWN && core != PromiseStatus.NOT_APPLICABLE
        return known && inside == (core != PromiseStatus.MISSED)
    }
}
