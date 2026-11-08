package app.drivepromise.core

import app.drivepromise.core.engine.EnginePolicy
import app.drivepromise.core.engine.PromiseEngine
import app.drivepromise.core.ledger.LedgerEvent
import app.drivepromise.core.ledger.MemoryLedger
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.model.TurnType
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.model.Wiper
import app.drivepromise.core.plan.PreTripPlanner
import app.drivepromise.core.plan.SolarCalc
import app.drivepromise.core.replay.ReplayParser
import app.drivepromise.core.scene.PromisePhrases
import app.drivepromise.core.source.RouteOption
import app.drivepromise.core.source.RoutePlan
import app.drivepromise.core.source.RoutePlans
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.speech.Step
import app.drivepromise.core.speech.World
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/** 약속 엔진 v0.1 스펙(S42~S56, S76) — Specs.all 뒤에 이어 붙음. 기준 시각은 2026-11-14(시연 날) 서울 */
object EngineSpecs {
    val all: List<Pair<String, () -> Unit>> = listOf(
        "S42 일몰 계산 — 서울·울산 7날짜가 천체력(PyEphem)·바다타임 표와 60초 안" to ::s42,
        "S43 일몰 계산 — 극지방은 null, 남은 시간 부호" to ::s43,
        "S44 해 지기 전 귀가 — 여유 있게 도착하면 정상, 말 없음" to ::s44,
        "S45 해 지기 전 귀가 — 늦으면 위험 + 한 번 말함(그때 ETA로), 빨라지면 정상" to ::s45,
        "S46 해 지기 전 귀가 — 경계에서 오락가락해도 상태가 깜빡이지 않음(5분 여유)" to ::s46,
        "S47 해 지기 전 귀가 — 해 진 뒤 주행은 못 지킴 + 밤 문장 한 번, 해 지기 전 도착은 지킴" to ::s47,
        "S48 고속도로 대신 — 제한속도 100·도로 이름으로 감지, 한 번 말하고 그 주행은 못 지킴" to ::s48,
        "S49 비 오는 날 — 와이퍼 60초면 비, 제한속도 80% 넘김 30초면 감속 문장, 과속 있었으면 주행 끝 못 지킴" to ::s49,
        "S50 2시간 쉬기 상태 — 정상·위험(10분 전)·못 지킴" to ::s50,
        "S51 약속 구역 — 끝 300 m 안쪽에서 바깥으로 움직이면 예고, 밖이면 못 지킴 + 말, 돌아오면 정상, 주행 끝은 못 지킴" to ::s51,
        "S52 리플레이 통합 — 엔진 예시 트립(16:50 출발, ETA 17:28, 앞 2.5분만 재생): 출발 때 경고, 해 지기 전 도착 → 지킴" to ::s52,
        "S53 출발 전 계획 — 추천 경로(고속도로)보다 무료 도로를 고름" to ::s53,
        "S54 출발 전 계획 — 모두 늦으면 출발 시한 또는 ‘다른 날’, 아슬아슬하면 ‘바로 출발’" to ::s54,
        "S55 출발 전 계획 — 경로가 없으면 오류 문장" to ::s55,
        "S56 시각 읽기 — 오후 5시 22분 · 오후 12시 · 오전 12시 5분 / 5:22 p.m." to ::s56,
        "S76 약속 구역 — 경계 히스테리시스: 밖에 나간 뒤 경계 안쪽 100 m 이내의 위치 흔들림에는 ‘돌아왔어요’·‘밖이에요’를 반복하지 않고, 100 m 넘게 들어오면 한 번(실제 위치 FusedLocation, 11/5)" to ::s76,
    )
}

private val Z: ZoneId = ZoneId.of("Asia/Seoul")
private const val SEOUL_LAT = 37.5665
private const val SEOUL_LON = 126.9780
private fun kst(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, s, 0, Z).toInstant().toEpochMilli()
private fun fix(t: Long, lat: Double = SEOUL_LAT, lon: Double = SEOUL_LON) = GeoFix(t, lat, lon, 15f)

/** 엔진 시험 장치 — 스케줄러 창은 항상 열림(말이 ‘만들어지는지’만 봄) */
private class EngineRig(promises: List<Promise>, policy: EnginePolicy = EnginePolicy()) {
    val ledger = MemoryLedger()
    val sch = sched(ScriptWindows(), l = ledger)
    val eng = PromiseEngine(promises, sch, ledger, policy)
    val sim = Sim(sch)
    var vehicle: (Long) -> VehicleSnapshot? = { t -> DRIVE.copy(tMillis = t) }
    var fixAt: (Long) -> GeoFix? = { t -> fix(t) }
    var etaAt: (Long) -> Long? = { null }
    var driving: (Long) -> Long = { 0 }
    fun world(t: Long) = World(t, vehicle(t), fixAt(t), drivingMs = driving(t), etaMs = etaAt(t))
    fun run(from: Long, to: Long, step: Long = 100) = sim.run(from, to, step) { t -> eng.tick(world(t)); world(t) }
    fun status(id: String) = eng.statuses[id]
    fun changes(id: String) = ledger.events.filterIsInstance<LedgerEvent.StatusChanged>().filter { it.promiseId == id }.map { it.to }
}

private fun s42() {
    val ref = listOf(   // PyEphem(JPL 기반) 서울 — 일출, 일몰
        Triple(LocalDate.of(2026, 11, 5), "07:00:45", "17:30:07"), Triple(LocalDate.of(2026, 11, 10), "07:06:02", "17:25:28"),
        Triple(LocalDate.of(2026, 11, 14), "07:10:16", "17:22:15"), Triple(LocalDate.of(2026, 11, 15), "07:11:20", "17:21:31"),
        Triple(LocalDate.of(2026, 11, 30), "07:26:41", "17:14:15"), Triple(LocalDate.of(2026, 6, 21), "05:11:00", "19:56:39"),
        Triple(LocalDate.of(2026, 12, 22), "07:43:25", "17:17:31"),
    )
    for ((d, r, s) in ref) {
        val t = SolarCalc.forDate(d, SEOUL_LAT, SEOUL_LON, Z)
        val rise = ZonedDateTime.of(d, LocalTime.parse(r), Z).toInstant().toEpochMilli()
        val set = ZonedDateTime.of(d, LocalTime.parse(s), Z).toInstant().toEpochMilli()
        ok(abs(t.sunriseMs!! - rise) <= 60_000, "서울 $d 일출 ${t.sunriseMs} vs $r")
        ok(abs(t.sunsetMs!! - set) <= 60_000, "서울 $d 일몰 ${t.sunsetMs} vs $s")
    }
    val ulsan = listOf(   // 바다타임 표(당사항, 울산 — 분 단위)
        Triple(LocalDate.of(2026, 11, 5), "06:47", "17:24"), Triple(LocalDate.of(2026, 11, 10), "06:52", "17:20"),
        Triple(LocalDate.of(2026, 11, 14), "06:56", "17:17"), Triple(LocalDate.of(2026, 11, 15), "06:57", "17:16"), Triple(LocalDate.of(2026, 11, 30), "07:12", "17:10"),
    )
    for ((d, r, s) in ulsan) {
        val t = SolarCalc.forDate(d, 35.47, 129.40, Z)
        val rise = ZonedDateTime.of(d, LocalTime.parse(r), Z).toInstant().toEpochMilli()
        val set = ZonedDateTime.of(d, LocalTime.parse(s), Z).toInstant().toEpochMilli()
        ok(abs(t.sunriseMs!! - rise) <= 60_000, "울산 $d 일출")
        ok(abs(t.sunsetMs!! - set) <= 60_000, "울산 $d 일몰")
    }
}

private fun s43() {
    val t = SolarCalc.forDate(LocalDate.of(2026, 12, 22), 69.65, 18.96, ZoneId.of("Europe/Oslo"))
    ok(t.sunriseMs == null && t.sunsetMs == null, "트롬쇠 12/22 해 안 뜸")
    ok(t.civilDuskMs != null, "시민 박명은 있음")
    val before = SolarCalc.untilSunsetMs(kst(2026, 11, 14, 16, 50), SEOUL_LAT, SEOUL_LON, Z)!!
    ok(before in 31 * MIN..33 * MIN, "16:50 → 일몰(17:22)까지 32분쯤: $before")
    ok(SolarCalc.untilSunsetMs(kst(2026, 11, 14, 18, 0), SEOUL_LAT, SEOUL_LON, Z)!! < 0, "18:00은 지남")
}

private fun s44() {
    val r = EngineRig(listOf(Promise.HomeBeforeDark(30)))
    val t0 = kst(2026, 11, 14, 15, 0)
    r.etaAt = { 40 * MIN }   // 15:40 도착, 마감 16:52
    r.run(t0, t0 + 10_000)
    eq(r.status("dark"), PromiseStatus.ON_TRACK, "정상")
    eq(r.sim.log.size, 0, "말 없음")
}

private fun s45() {
    val r = EngineRig(listOf(Promise.HomeBeforeDark(30)))
    val t0 = kst(2026, 11, 14, 16, 30)
    var eta = 58 * MIN   // 17:28 도착 — 해 진 뒤
    r.etaAt = { eta }
    r.run(t0, t0 + 5_000)
    eq(r.status("dark"), PromiseStatus.AT_RISK, "위험")
    eq(r.sim.log.map { it.text }, listOf("이대로면 5시 28분 도착, 해는 5시 22분에 져요."), "경고 한 번, 그때 ETA")
    eta = 10 * MIN   // 길이 뚫림: 16:40 도착(마감 16:52 − 여유 5분보다 앞)
    r.run(t0 + 5_100, t0 + 10_000)
    eq(r.status("dark"), PromiseStatus.ON_TRACK, "정상으로")
    eq(r.sim.log.size, 1, "더 말하지 않음")
    eq(r.changes("dark"), listOf("AT_RISK", "ON_TRACK"), "상태 기록")
}

private fun s46() {
    val r = EngineRig(listOf(Promise.HomeBeforeDark(30)))
    val t0 = kst(2026, 11, 14, 16, 0)
    // 마감 16:52 — ETA가 16:53↔16:50 사이를 오감: 위험이 된 뒤 5분 여유 안이면 그대로 위험
    r.etaAt = { t -> val sec = (t - t0) / 1000; if (sec % 2 == 0L) 53 * MIN else 50 * MIN }
    r.run(t0, t0 + 10_000)
    eq(r.changes("dark"), listOf("AT_RISK"), "한 번만 위험으로 바뀜")
    r.etaAt = { 40 * MIN }   // 16:40 → 여유 12분 → 정상
    r.run(t0 + 10_100, t0 + 12_000)
    eq(r.status("dark"), PromiseStatus.ON_TRACK, "충분히 빨라지면 정상")
}

private fun s47() {
    val r = EngineRig(listOf(Promise.HomeBeforeDark(30)))
    val t0 = kst(2026, 11, 14, 17, 21, 50)
    r.etaAt = { 30 * MIN }
    r.run(t0, t0 + 30_000)   // 17:22:15 일몰을 지남
    eq(r.status("dark"), PromiseStatus.MISSED, "해 진 뒤 주행")
    ok(r.sim.log.any { it.key == "dark.night" }, "밤 문장: ${r.sim.log.map { it.key }}")
    eq(r.sim.log.count { it.key == "dark.night" }, 1, "밤 문장 한 번")
    val r2 = EngineRig(listOf(Promise.HomeBeforeDark(30)))
    val t1 = kst(2026, 11, 14, 17, 0)
    r2.etaAt = { 5 * MIN }
    r2.run(t1, t1 + 1_000)
    r2.eng.onNavi(NaviEvent.Arrived(t1 + 2_000))
    r2.run(t1 + 2_000, t1 + 3_000)
    eq(r2.status("dark"), PromiseStatus.KEPT, "해 지기 전 도착(17:00, 마감은 지났지만 해 지기 전)")
    val fin = r2.eng.endTrip(r2.world(t1 + 4_000))
    eq(fin["dark"], PromiseStatus.KEPT, "주행 끝 판정")
}

private fun s48() {
    val r = EngineRig(listOf(Promise.AvoidHighway()))
    val t0 = kst(2026, 11, 14, 10, 0)
    r.run(t0, t0 + 1_000)
    eq(r.status("highway"), PromiseStatus.ON_TRACK, "일반도로")
    r.eng.onNavi(NaviEvent.SpeedLimit(t0 + 1_000, 100))
    r.run(t0 + 1_100, t0 + 3_000)
    eq(r.status("highway"), PromiseStatus.MISSED, "고속도로")
    eq(r.sim.log.map { it.text }, listOf("고속도로에 들어섰어요. 약속과 달라요. 다음 나들목에서 내려갈지 정해요."), "한 번 말함")
    r.eng.onNavi(NaviEvent.SpeedLimit(t0 + 3_000, 60))
    r.run(t0 + 3_100, t0 + 4_000)
    eq(r.status("highway"), PromiseStatus.MISSED, "내려와도 그 주행은 못 지킴")
    eq(r.sim.log.size, 1, "더 말하지 않음")
    val r2 = EngineRig(listOf(Promise.AvoidHighway()))
    r2.eng.onNavi(NaviEvent.Tbt(t0, 500, TurnType.KEEP_RIGHT, "경부고속도로"))
    r2.run(t0, t0 + 500)
    eq(r2.status("highway"), PromiseStatus.MISSED, "도로 이름으로 감지")
    eq(r2.eng.endTrip(r2.world(t0 + 600))["highway"], PromiseStatus.MISSED, "주행 끝")
}

private fun s49() {
    val r = EngineRig(listOf(Promise.RainCare()))
    val t0 = kst(2026, 11, 14, 10, 0)
    var wiper = Wiper.LOW
    var kph = 70f
    r.vehicle = { t -> DRIVE.copy(tMillis = t, wiper = wiper, speedMps = kph / 3.6f) }
    r.eng.onNavi(NaviEvent.SpeedLimit(t0, 60))
    r.run(t0, t0 + 59_000)
    ok(!r.eng.isRaining, "59초는 아직")
    r.run(t0 + 59_100, t0 + 95_000)
    ok(r.eng.isRaining, "60초면 비")
    eq(r.sim.log.map { it.text }, listOf("비가 와요. 약속대로 평소보다 천천히, 앞차와 거리를 두세요.", "빗길에 70예요. 48 아래로 줄여요."), "비 시작 + 30초 뒤 감속(60의 80% = 48)")
    eq(r.status("rain"), PromiseStatus.AT_RISK, "과속")
    kph = 40f
    r.run(t0 + 95_100, t0 + 100_000)
    eq(r.status("rain"), PromiseStatus.ON_TRACK, "줄이면 정상")
    wiper = Wiper.OFF
    r.run(t0 + 100_100, t0 + 100_000 + 4 * MIN)
    ok(r.eng.isRaining, "4분은 아직 비")
    r.run(t0 + 100_000 + 4 * MIN + 100, t0 + 100_000 + 5 * MIN + 1_000)
    ok(!r.eng.isRaining, "5분 멈추면 비 끝")
    eq(r.eng.endTrip(r.world(t0 + 100_000 + 5 * MIN + 1_100))["rain"], PromiseStatus.MISSED, "빗길 과속이 한 번 있었으면 그 주행은 못 지킴(11/5)")
    val r3 = EngineRig(listOf(Promise.RainCare()))
    r3.vehicle = { t -> DRIVE.copy(tMillis = t, wiper = Wiper.LOW, speedMps = 40f / 3.6f) }   // 제한속도 모름 → 기준 60의 80% = 48 아래
    r3.run(t0, t0 + 70_000)
    eq(r3.eng.endTrip(r3.world(t0 + 70_100))["rain"], PromiseStatus.KEPT, "빗길에 과속하지 않았으면 지킴")
}

private fun s50() {
    val r = EngineRig(listOf(Promise.RestEvery(120)))
    val t0 = kst(2026, 11, 14, 10, 0)
    r.driving = { 100 * MIN }; r.run(t0, t0 + 200); eq(r.status("rest"), PromiseStatus.ON_TRACK, "100분")
    r.driving = { 112 * MIN }; r.run(t0 + 300, t0 + 500); eq(r.status("rest"), PromiseStatus.AT_RISK, "112분(10분 전)")
    r.driving = { 121 * MIN }; r.run(t0 + 600, t0 + 800); eq(r.status("rest"), PromiseStatus.MISSED, "121분")
}

private fun s51() {
    val r = EngineRig(listOf(Promise.StayInZone(SEOUL_LAT, SEOUL_LON, 2_000)))
    val t0 = kst(2026, 11, 14, 10, 0)
    val dLat = 1.0 / 111_195.0   // 1 m
    var north = 1_000.0
    r.fixAt = { t -> GeoFix(t, SEOUL_LAT + north * dLat, SEOUL_LON, 15f) }
    r.run(t0, t0 + 500)
    eq(r.status("zone"), PromiseStatus.ON_TRACK, "안쪽 1 km")
    north = 1_800.0
    r.run(t0 + 600, t0 + 6_000)
    eq(r.status("zone"), PromiseStatus.AT_RISK, "끝 300 m 안쪽으로 나가는 중")
    eq(r.sim.log.map { it.key }, listOf("zone.edge"), "예고 한 번")
    north = 2_100.0
    r.run(t0 + 6_100, t0 + 12_000)
    eq(r.status("zone"), PromiseStatus.MISSED, "밖")
    eq(r.sim.log.last().text, "약속 구역 밖이에요. 안전한 곳에서 돌아가요.", "밖 문장")
    north = 1_000.0
    r.run(t0 + 12_100, t0 + 20_000)
    eq(r.status("zone"), PromiseStatus.ON_TRACK, "돌아옴")
    eq(r.sim.log.last().text, "약속 구역으로 돌아왔어요.", "돌아옴 문장")
    eq(r.changes("zone"), listOf("ON_TRACK", "AT_RISK", "MISSED", "ON_TRACK"), "상태 기록")
    eq(r.eng.endTrip(r.world(t0 + 20_100))["zone"], PromiseStatus.MISSED, "한 번 벗어났으면 그 주행은 못 지킴(고속도로와 같은 규칙, 11/5)")
}

private fun s76() {
    val r = EngineRig(listOf(Promise.StayInZone(SEOUL_LAT, SEOUL_LON, 2_000)))
    val t0 = kst(2026, 11, 14, 10, 0)
    val dLat = 1.0 / 111_195.0   // 1 m
    var north = 1_500.0
    r.fixAt = { t -> GeoFix(t, SEOUL_LAT + north * dLat, SEOUL_LON, 15f) }
    r.run(t0, t0 + 500)
    north = 2_030.0
    r.run(t0 + 600, t0 + 6_000)
    eq(r.status("zone"), PromiseStatus.MISSED, "밖(2,030 m)")
    for (i in 0 until 10) {   // 경계에 선 채 좌표가 1,970 ↔ 2,030 m로 흔들림(실제 GPS 오차 수준)
        north = if (i % 2 == 0) 1_970.0 else 2_030.0
        val a = t0 + 6_100 + i * 3_000L
        r.run(a, a + 2_900)
    }
    eq(r.status("zone"), PromiseStatus.MISSED, "경계 안쪽 100 m 이내의 흔들림은 아직 밖")
    eq(r.sim.log.count { it.key == "zone.back" }, 0, "‘돌아왔어요’를 반복하지 않음")
    eq(r.sim.log.count { it.key == "zone.out" }, 1, "‘밖이에요’는 처음 한 번")
    eq(r.changes("zone").count { it == "MISSED" }, 1, "상태가 깜빡이지 않음")
    north = 1_850.0
    r.run(t0 + 40_000, t0 + 46_000)
    eq(r.status("zone"), PromiseStatus.ON_TRACK, "경계 안쪽 150 m — 돌아옴")
    eq(r.sim.log.count { it.key == "zone.back" }, 1, "돌아옴 한 번")
}

private fun s52() {
    val trip = ReplayParser.parse(PLAN_SAMPLE)
    val base = trip.meta.baseEpochMs
    eq(base, kst(2026, 11, 14, 16, 50), "출발 16:50")
    val r = EngineRig(listOf(Promise.HomeBeforeDark(30), Promise.AvoidHighway(), Promise.RestEvery(120)))
    val fixes = trip.location
    r.fixAt = { t -> fixes.lastOrNull { it.tMillis <= t } }
    r.vehicle = { t -> trip.vehicleAt(t) }
    val navi = trip.navi.sortedBy { it.tMillis }.toMutableList()
    var t = base
    val end = trip.endMs + 1_000
    while (t <= end) {
        while (navi.isNotEmpty() && navi.first().tMillis <= t) r.eng.onNavi(navi.removeAt(0))
        r.eng.tick(r.world(t))
        val s = r.sim.loop.tick(r.world(t))
        if (s is Step.Speak) { r.sim.log += Said(t, s.key, s.text, s.short, s.repeat, s.attempt); r.sim.loop.onDone(s.id, t + s.estMs) }
        t += 100
    }
    ok(r.sim.log.any { it.key == "dark.risk" && it.t == base }, "출발 직후 경고: ${r.sim.log}")
    eq(r.sim.log.first().text, "이대로면 5시 28분 도착, 해는 5시 22분에 져요.", "경고 문장")
    eq(r.status("dark"), PromiseStatus.KEPT, "실제로는 150초 뒤(16:52:30) 도착 — 해 지기 전")
    val fin = r.eng.endTrip(r.world(end))
    eq(fin["highway"], PromiseStatus.UNKNOWN, "1.3.0: 도로 신호(제한속도·도로 이름)가 한 번도 없으면 ‘모름’ — 신호 없는 주행을 ‘지킴’으로 세지 않음")
    eq(fin["rest"], PromiseStatus.KEPT, "2시간 전 끝")
    ok(r.ledger.events.any { it is LedgerEvent.StatusChanged && it.promiseId == "dark" && it.to == "KEPT" }, "지킴 기록")
}

private fun plans(vararg p: RoutePlan) = RoutePlans(p.associateBy { it.option })

private fun s53() {
    val depart = kst(2026, 11, 14, 15, 30)
    val out = PreTripPlanner.plan(PreTripPlanner.Input(depart, SEOUL_LAT, SEOUL_LON,
        plans(RoutePlan(RouteOption.RECOMMENDED, 42_000, 50 * 60, 12_000), RoutePlan(RouteOption.FREE, 47_000, 70 * 60, 0)),
        listOf(Promise.HomeBeforeDark(30), Promise.AvoidHighway(), Promise.RestEvery(120))))
    eq(out.choice, RouteOption.FREE, "무료 도로")
    eq(out.lines, listOf("무료 도로로 가요. 70분 걸려요.", "오후 4시 40분 도착, 해는 오후 5시 22분에 져요. 약속대로 해 지기 전이에요.", "고속도로 없이 가요."), "문장")
    val rec = out.candidates.first { it.option == RouteOption.RECOMMENDED }
    eq(rec.highwayStatus, PromiseStatus.MISSED, "추천 경로는 고속도로")
    eq(rec.darkStatus, PromiseStatus.ON_TRACK, "추천 경로도 해 지기 전")
    ok(abs(out.departByMs!! - kst(2026, 11, 14, 16, 2, 13)) <= 60_000, "가장 빠른 경로(50분)로 마감 16:52까지 도착하려면 16:02 출발: ${out.departByMs}")
}

private fun s54() {
    val late = PreTripPlanner.plan(PreTripPlanner.Input(kst(2026, 11, 14, 16, 50), SEOUL_LAT, SEOUL_LON,
        plans(RoutePlan(RouteOption.RECOMMENDED, 30_000, 40 * 60, 0), RoutePlan(RouteOption.FREE, 33_000, 60 * 60, 0)), listOf(Promise.HomeBeforeDark(30))))
    eq(late.choice, RouteOption.RECOMMENDED, "모두 늦으면 가장 빠른 것")
    eq(late.lines[1], "지금 가도 오후 5시 30분 도착, 해 진 뒤예요(일몰 오후 5시 22분). 해 지기 전 도착은 어려워요 — 다른 날이나 동승을 생각해요.", "다른 날")
    val early = PreTripPlanner.plan(PreTripPlanner.Input(kst(2026, 11, 14, 10, 0), SEOUL_LAT, SEOUL_LON,
        plans(RoutePlan(RouteOption.RECOMMENDED, 30_000, 150 * 60, 0)), listOf(Promise.HomeBeforeDark(30), Promise.RestEvery(120)), departAtMs = kst(2026, 11, 14, 15, 0)))
    eq(early.lines[1], "오후 3시 출발이면 오후 5시 30분 도착, 해 진 뒤예요. 약속을 지키려면 오후 2시 22분까지 출발해야 해요.", "아침에 세우는 계획: 출발 시한")
    ok(early.lines.last().startsWith("2시간 넘게 걸려요."), "쉬기 안내: ${early.lines}")
    val tight = PreTripPlanner.plan(PreTripPlanner.Input(kst(2026, 11, 14, 16, 30), SEOUL_LAT, SEOUL_LON,
        plans(RoutePlan(RouteOption.RECOMMENDED, 30_000, 40 * 60, 0)), listOf(Promise.HomeBeforeDark(30))))
    eq(tight.candidates.single().darkStatus, PromiseStatus.AT_RISK, "17:10 도착 — 마감 뒤, 일몰 전")
    eq(tight.lines[1], "오후 5시 10분 도착이면 해 지기 직전이에요(일몰 오후 5시 22분). 바로 출발해요.", "바로 출발")
}

private fun s55() {
    val out = PreTripPlanner.plan(PreTripPlanner.Input(kst(2026, 11, 14, 15, 0), SEOUL_LAT, SEOUL_LON, RoutePlans(emptyMap(), "CAUSE_SEARCH_ROUTE"), listOf(Promise.HomeBeforeDark())))
    eq(out.choice, null, "선택 없음")
    eq(out.error, "CAUSE_SEARCH_ROUTE", "오류")
    eq(out.lines, listOf("경로를 받지 못했어요. 인터넷을 확인하고 다시 시도해요."), "문장(오류 코드는 말하지 않음 — 1.2.1)")
}

private fun s56() {
    eq(PromisePhrases.koClock(kst(2026, 11, 14, 17, 22), Z), "오후 5시 22분", "17:22")
    eq(PromisePhrases.koClock(kst(2026, 11, 14, 12, 0), Z), "오후 12시", "12:00")
    eq(PromisePhrases.koClock(kst(2026, 11, 14, 0, 5), Z), "오전 12시 5분", "00:05")
    eq(PromisePhrases.enClock(kst(2026, 11, 14, 17, 22), Z), "5:22 p.m.", "en")
    eq(PromisePhrases.darkRisk(Lang.EN, kst(2026, 11, 14, 17, 22), kst(2026, 11, 14, 17, 28), Z, false), "At this pace you arrive 5:28 p.m.; sunset is 5:22 p.m.", "영어 경고")
}
