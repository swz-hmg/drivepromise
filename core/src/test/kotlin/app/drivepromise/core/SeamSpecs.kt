package app.drivepromise.core

import app.drivepromise.core.engine.EnginePolicy
import app.drivepromise.core.engine.PromiseEngine
import app.drivepromise.core.engine.ZoneCrossCheck
import app.drivepromise.core.model.Gear
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.speech.QuietPolicy
import app.drivepromise.core.speech.QuietWindows
import app.drivepromise.core.speech.SpeechScheduler
import app.drivepromise.core.speech.World

/**
 * 1.3.5 — 이음매(코어 ↔ 앱) 계약 스펙. 11/10 전체 점검에서 결함이 모두 코어 바깥의 이음매에 있었으므로,
 * 앱이 코어 값에 기대는 곳은 코어 쪽 스펙으로 묶어 둡니다.
 */
object SeamSpecs {
    val all: List<Pair<String, () -> Unit>> = listOf(
        "S127 약속 구역 이중 확인 — 근접 알림 뒤 ZoneCrossCheck.delayMs(=확인 5초+3초)에 비교하면 나갈 때·돌아올 때 모두 코어와 일치(세워 둔 채 1 Hz 위치·10 Hz 판정) · 옛 3초 비교는 두 방향 모두 불일치였음(11/10 전체 점검 A4·D4)" to ::s127,
    )
}

private const val ZLA = 37.5665
private const val ZLO = 126.9780
private val ZOPEN = object : QuietWindows {
    override fun normal(nowMs: Long, needMs: Long) = true
    override fun stopWindow(nowMs: Long) = false
}

private fun s127() {
    val pol = EnginePolicy()
    val delay = ZoneCrossCheck.delayMs(pol)
    eq(delay, pol.zoneConfirmMs + ZoneCrossCheck.MARGIN_MS, "비교 시점은 코어 확인 시간에서 계산")
    ok(delay > pol.zoneConfirmMs + 1_000, "확인 시간 + 위치 한 번 간격보다 늦게 비교: $delay")
    // LOC 시험과 같은 모양: 반지름 3 km, 세워 둔 차(P), 위치는 1초마다(같은 자리도 새 시각), 판정은 10 Hz
    val q = SpeechScheduler(QuietPolicy(), ZOPEN)
    val e = PromiseEngine(listOf(Promise.StayInZone(ZLA, ZLO, 3_000)), q)
    val dLat = 3_500.0 / 111_195.0
    val outFrom = 20_000L; val backFrom = 50_000L
    val st = sortedMapOf<Long, PromiseStatus?>()
    var t = 0L
    while (t <= 80_000) {
        val fixT = t / 1_000 * 1_000
        val lat = if (fixT in outFrom until backFrom) ZLA + dLat else ZLA
        val w = World(t, VehicleSnapshot(t, gear = Gear.P, speedMps = 0f), GeoFix(fixT, lat, ZLO, 0f, accuracyM = 10f))
        e.tick(w)
        if (t % 100 == 0L) st[t] = e.statuses["zone"]
        t += 100
    }
    fun at(ms: Long) = st[ms - ms % 100]
    // 근접 알림은 첫 밖/안 위치와 거의 같은 때(실측 47~88 ms 먼저) — tP = 그 위치 시각으로 봄
    val exitAt = outFrom; val enterAt = backFrom
    ok(ZoneCrossCheck.agree(false, at(exitAt + delay)), "나감: ${delay}ms 뒤 코어 ${at(exitAt + delay)} = 밖")
    ok(ZoneCrossCheck.agree(true, at(enterAt + delay)), "돌아옴: ${delay}ms 뒤 코어 ${at(enterAt + delay)} = 안")
    // 옛 비교 시점(3초)은 확인 전이라 두 방향 모두 불일치 — 1.3.2~1.3.4 문서의 ‘agree=true’가 성립하지 않던 까닭
    ok(!ZoneCrossCheck.agree(false, at(exitAt + 3_000)), "옛 3초 비교(나감)는 불일치: ${at(exitAt + 3_000)}")
    ok(!ZoneCrossCheck.agree(true, at(enterAt + 3_000)), "옛 3초 비교(돌아옴)는 불일치: ${at(enterAt + 3_000)}")
    // 일치 규칙: 모름·해당 없음·null은 일치로 세지 않음
    ok(!ZoneCrossCheck.agree(true, PromiseStatus.UNKNOWN) && !ZoneCrossCheck.agree(false, PromiseStatus.UNKNOWN), "모름은 일치 아님")
    ok(!ZoneCrossCheck.agree(true, null) && !ZoneCrossCheck.agree(true, PromiseStatus.NOT_APPLICABLE), "기록 없음·해당 없음은 일치 아님")
    ok(ZoneCrossCheck.agree(true, PromiseStatus.ON_TRACK) && ZoneCrossCheck.agree(true, PromiseStatus.AT_RISK) && ZoneCrossCheck.agree(false, PromiseStatus.MISSED), "안 = 못 지킴이 아닌 모든 판단")
}
