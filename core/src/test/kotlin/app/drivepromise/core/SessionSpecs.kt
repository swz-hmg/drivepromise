package app.drivepromise.core

import app.drivepromise.core.gate.QuietGate
import app.drivepromise.core.ledger.LedgerEvent
import app.drivepromise.core.ledger.LedgerJson
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.model.Turn
import app.drivepromise.core.model.TurnType
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.plan.PreTripPlanner
import app.drivepromise.core.replay.ReplayParser
import app.drivepromise.core.replay.ReplayPlayer
import app.drivepromise.core.rest.RestTracker
import app.drivepromise.core.session.SessionPolicy
import app.drivepromise.core.session.TripSession
import app.drivepromise.core.source.Clock
import app.drivepromise.core.source.Place
import app.drivepromise.core.source.RouteOption
import app.drivepromise.core.source.RoutePlan
import app.drivepromise.core.source.RoutePlans
import app.drivepromise.core.source.RouteSource
import app.drivepromise.core.source.Sink
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.speech.Priority
import app.drivepromise.core.speech.Step
import app.drivepromise.core.speech.Utterance
import app.drivepromise.core.speech.Validity
import app.drivepromise.core.speech.VoiceArbiter
import app.drivepromise.core.speech.World
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** 앱 구현 v0.1 스펙(S57~S71) — 게이트(침묵 구간)·세션(상태 기계·배선)·리플레이 재생기·기록 JSON. 기준 시각 2026-11-14 16:50 KST */
object SessionSpecs {
    val all: List<Pair<String, () -> Unit>> = listOf(
        "S57 게이트 — 회전 앞 8초 침묵, 좌회전·유턴은 10초, 저속은 5 m/s로 셈" to ::s57,
        "S58 게이트 — 끝낼 시간 확보: 말이 회전 앞 침묵 전에 끝나야 시작" to ::s58,
        "S59 게이트 — 내비 안내 피하기: 300·100 m 예측 안내 앞뒤에서 시작하지 않음" to ::s59,
        "S60 게이트 — 방향지시등·급제동 뒤 2초 침묵, 경로가 없으면 차량 신호만 봄" to ::s60,
        "S61 게이트 — 회전 통과(20 m 또는 다음 회전으로 넘어감) 뒤 8초 침묵, 그 뒤 보류된 말이 나옴" to ::s61,
        "S62 게이트 — 신호 대기 창은 주행 중 정지 2초 안만, 세워 둔 차(P)는 아님" to ::s62,
        "S63 게이트 — 안전 말은 침묵 구간에서도 즉시, 약속 말은 기다림" to ::s63,
        "S64 세션 — 세워 둠 → 주행 → 도착 뒤 정차에 끝: 판정 닫힘·요약 문장을 세워 둔 동안 말함" to ::s64,
        "S65 세션 — 시동 끔·30분 정차·수동으로도 끝, 끝은 한 번만" to ::s65,
        "S66 세션 — 리플레이 통합: 계획서 예시 트립을 재생기로 끝까지(출발 경고 → 도착 → 요약)" to ::s66,
        "S67 세션 — 출발 전 계획: 세워 둔 동안 계획 문장을 말하고, 주행 중엔 미룸" to ::s67,
        "S68 세션 — 이어 가기: 쉬기 상태를 복원해 2시간 본 알림이 이어짐" to ::s68,
        "S69 리플레이 재생기 — 세 원천을 시간 순으로 섞어 보냄, 배속 3의 가상 시계" to ::s69,
        "S70 기록 JSON — 모든 종류 왕복, 깨진 줄은 건너뜀" to ::s70,
        "S71 세션 — 내비에 물러섬·1.5초 뒤 짧게 다시 말하기가 세션 배선에서도 동작" to ::s71,
        "S72 시연 트립 4개(demo/trips) — 기대 발화 열과 주행 끝 판정·요약 문장" to ::s72,
    )
}

private val Z: ZoneId = ZoneId.of("Asia/Seoul")
private const val SEOUL_LAT = 37.5665
private const val SEOUL_LON = 126.9780
private fun kst(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, s, 0, Z).toInstant().toEpochMilli()
private val T0 = kst(2026, 11, 14, 16, 50)
private fun seoul(t: Long) = GeoFix(t, SEOUL_LAT, SEOUL_LON, 15f)
private fun drive(t: Long, speed: Float = 15f) = DRIVE.copy(tMillis = t, speedMps = speed)
private fun tbt(t: Long, d: Int, turn: TurnType = TurnType.RIGHT) = NaviEvent.Tbt(t, d, turn)

private class FakeClock(var t: Long) : Clock {
    override fun nowMs(): Long = t
}

/** 세션 시험 장치 — 가짜 시계·가짜 말하기, 말은 예상 길이 뒤 끝난 것으로 */
private class SessionRig(
    promises: List<Promise>,
    policy: SessionPolicy = SessionPolicy(),
    lang: Lang = Lang.KO,
    val t0: Long = T0,
) {
    val clock = FakeClock(t0)
    val spk = FakeSpeaker()
    val log = mutableListOf<Said>()
    val phases = mutableListOf<String>()
    val plans = mutableListOf<PreTripPlanner.Plan>()
    val ses = TripSession(promises, spk, clock, lang, policy, listener = object : TripSession.Listener {
        override fun onPhase(phase: TripSession.Phase, nowMs: Long) { phases += "${phase.name}@${(nowMs - t0) / 1000}" }
        override fun onSpeak(step: Step.Speak, nowMs: Long) { log += Said(nowMs - t0, step.key, step.text, step.short, step.repeat, step.attempt) }
        override fun onPlan(plan: PreTripPlanner.Plan, nowMs: Long) { plans += plan }
    })
    private var cur: Step.Speak? = null
    private var endAt = 0L
    /** 지금(상대 ms) */
    val t: Long get() = clock.t - t0
    /** 상대 ms → 절대 시각 */
    fun abs(rel: Long) = t0 + rel

    /** 상대 시각 untilMs(ms)까지 100 ms씩 — before(상대 시각)에서 입력을 넣음 */
    fun run(untilMs: Long, step: Long = 100, before: (Long) -> Unit = {}) {
        while (clock.t - t0 <= untilMs) {
            before(clock.t - t0)
            val c = cur
            if (c != null && clock.t >= endAt) { ses.onSpeechDone(c.id); cur = null }
            val s = ses.tick()
            if (s is Step.Speak) { cur = s; endAt = clock.t + s.estMs }
            clock.t += step
        }
    }

    fun cutSpeech() { cur = null }
    fun keys() = log.map { it.key }
}

private fun promiseU(key: String, deadline: Long) = Utterance(key, Priority.PROMISE, 3_000, render = { c -> "$key${if (c.short) " 짧게" else ""}" },
    deadlineAtMs = deadline, validity = Validity.WHILE_STATE, estShortMs = 1_500)

// ---------------------------------------------------------------- 게이트
private fun s57() {
    val g = QuietGate()
    g.update(0, drive(0, 15f))
    g.onNavi(tbt(0, 140))                       // 140 m ÷ 15 m/s = 9.3초
    ok(!g.quiet(0), "우회전 9.3초 전은 말해도 됨")
    eq(g.timeToTurnMs, 9_333L, "남은 시간")
    g.update(2_100, drive(2_100, 15f))          // 31.5 m 더 감 → 108.5 m → 7.2초
    ok(g.quiet(2_100), "우회전 7.2초 전은 침묵")
    val l = QuietGate()
    l.update(0, drive(0, 15f)); l.onNavi(tbt(0, 140, TurnType.LEFT))
    ok(l.quiet(0), "좌회전은 9.3초 전부터 침묵(10초)")
    val u = QuietGate()
    u.update(0, drive(0, 15f)); u.onNavi(tbt(0, 140, TurnType.U_TURN))
    ok(u.quiet(0), "유턴도 10초")
    val slow = QuietGate()
    slow.update(0, drive(0, 0f)); slow.onNavi(tbt(0, 30))
    ok(slow.quiet(0), "정지 중 30 m 앞 회전 = 5 m/s로 6초 → 침묵")
    slow.onNavi(tbt(0, 50))
    ok(!slow.quiet(0), "정지 중 50 m = 10초 → 말해도 됨")
    val none = QuietGate()
    none.update(0, drive(0, 15f))
    ok(!none.quiet(0) && none.normal(0, 4_000) && none.timeToTurnMs == null, "경로 없으면 회전 예측 없음")
}

private fun s58() {
    val g = QuietGate()
    g.update(0, drive(0, 15f))
    g.onNavi(tbt(0, 172))                       // 11.47초 → 침묵 시작까지 3.47초
    ok(!g.quiet(0), "아직 침묵 아님")
    ok(g.normal(0, 3_000), "3초 말은 침묵 전에 끝남")
    ok(!g.normal(0, 4_000), "4초 말은 침묵에 걸림 → 시작하지 않음")
    g.onNavi(tbt(0, 180))                       // 12초 → 4초 확보
    ok(g.normal(0, 4_000), "12초 전이면 4초 말 가능")
}

private fun s59() {
    fun at(d: Int, need: Long): Boolean {
        val g = QuietGate()
        g.update(0, drive(0, 20f))
        g.onNavi(tbt(0, d))
        return g.normal(0, need)
    }
    ok(at(2_000, 4_000), "회전 100초 전, 안내 85초 전 → 됨")
    ok(at(380, 3_000), "300 m 안내가 4초 뒤 — 3초 말(+0.5 여유)은 됨")
    ok(!at(380, 4_000), "300 m 안내가 4초 뒤 — 4초 말은 겹침 → 안 됨")
    ok(!at(290, 2_000), "300 m 안내가 0.5초 전에 시작(2.5초) → 안 됨")
    ok(at(230, 2_000), "300 m 안내가 3.5초 전에 끝남 → 됨")
    ok(!at(120, 2_000), "100 m 안내가 1초 뒤 → 안 됨")
}

private fun s60() {
    val g = QuietGate()
    g.update(0, drive(0, 15f).copy(turnSignal = Turn.LEFT))
    ok(g.quiet(0) && !g.normal(0, 2_000), "방향지시등 켜짐 → 침묵")
    g.update(100, drive(100, 15f).copy(turnSignal = Turn.HAZARD))
    ok(g.quiet(100), "비상등도 침묵")
    g.update(200, drive(200, 15f))
    ok(!g.quiet(200), "꺼지면 바로 말해도 됨")
    g.update(1_000, drive(1_000, 15f))
    g.update(1_500, drive(1_500, 8f))           // 0.5초에 7 m/s 감속 = 14 m/s²
    ok(g.quiet(1_500), "급제동 → 침묵")
    ok(g.quiet(3_400), "급제동 뒤 1.9초 아직 침묵")
    g.update(3_600, drive(3_600, 8f))
    ok(!g.quiet(3_600), "2초 지나면 풀림")
    g.update(10_000, drive(10_000, 8f))
    g.update(10_500, drive(10_500, 7f))         // 2 m/s² 는 급제동 아님
    ok(!g.quiet(10_500), "완만한 감속은 침묵 아님")
}

private fun s61() {
    val g = QuietGate()
    val sch = sched(g)
    val sim = Sim(sch)
    sch.submit(promiseU("p1", 10 * MIN), 0)
    sim.at(0) { g.onNavi(tbt(0, 100)) }        // 100 m, 15 m/s → 6.7초 전: 침묵
    sim.at(20_000) { g.onNavi(tbt(20_000, 400)) }   // 다음 회전(거리 뜀 — 이미 센 회전은 다시 안 셈)
    sim.at(44_000) { g.onNavi(tbt(44_000, 500, TurnType.LEFT)) }   // 추정 40 m에서 500 m로 뜀 = 둘째 회전 통과
    sim.at(45_000) { sch.submit(promiseU("p2", 10 * MIN), 45_000) }
    sim.run(0, 60_000) { t -> g.update(t, drive(t, 15f)); w(t, v = drive(t, 15f)) }
    eq(sim.log.map { it.key }, listOf("p1", "p2"), "둘 다 말함")
    ok(sim.log[0].t in 13_300..13_600, "회전 통과(5.4초) + 8초 뒤에 말함: ${sim.log[0].t}")
    ok(sim.log[1].t in 52_000..52_200, "둘째 회전(44초) + 8초 뒤: ${sim.log[1].t}")
    eq(g.lastTurnPassedAt, 44_000L, "마지막 회전 통과 시각")
}

private fun s62() {
    val g = QuietGate()
    g.update(0, drive(0, 15f))
    ok(!g.stopWindow(0), "달리는 중엔 창 없음")
    g.update(1_000, drive(1_000, 0f))
    ok(g.stopWindow(1_000) && g.stopWindow(2_900), "정지 뒤 2초 안")
    ok(!g.stopWindow(3_100), "2초 지나면 닫힘")
    g.update(4_000, PARK.copy(tMillis = 4_000))
    ok(!g.stopWindow(4_000) && !g.stopWindow(5_000), "P는 신호 대기가 아님")
    g.update(6_000, drive(6_000, 3f))
    g.update(7_000, drive(7_000, 0f))
    ok(g.stopWindow(7_500), "다시 서면 새 창")
}

private fun s63() {
    val g = QuietGate()
    val sch = sched(g)
    val sim = Sim(sch)
    sch.submit(promiseU("p", 10 * MIN), 0)
    sch.submit(Utterance("safety", Priority.SAFETY, 2_000, render = { "safety" }), 0)
    sim.run(0, 5_000) { t -> g.update(t, drive(t, 15f).copy(turnSignal = Turn.LEFT)); w(t, v = drive(t, 15f)) }
    eq(sim.log.map { it.key }, listOf("safety"), "방향지시등 중엔 안전 말만")
    sim.run(5_100, 8_000) { t -> g.update(t, drive(t, 15f)); w(t, v = drive(t, 15f)) }
    eq(sim.log.map { it.key }, listOf("safety", "p"), "꺼지면 약속 말")
}

// ---------------------------------------------------------------- 세션
private val FOUR = listOf(Promise.HomeBeforeDark(30), Promise.AvoidHighway(), Promise.RestEvery(120), Promise.StayInZone(SEOUL_LAT, SEOUL_LON, 5_000))

private fun s64() {
    val r = SessionRig(FOUR)
    r.ses.onFix(seoul(T0))
    r.ses.onVehicle(PARK.copy(tMillis = T0))
    r.run(5_000)
    eq(r.ses.phase, TripSession.Phase.PARKED, "세워 둠")
    ok(r.ses.statuses["zone"] == PromiseStatus.ON_TRACK, "세워 둔 동안도 구역 상태는 계산: ${r.ses.statuses}")
    r.ses.onVehicle(drive(T0 + 5_100))
    r.ses.onNavi(NaviEvent.Trip(T0 + 5_100, T0 + 20 * MIN, 8_000))
    r.run(60_000) { t -> if (t == 60_000L) r.ses.onNavi(NaviEvent.Arrived(T0 + t)) }
    eq(r.ses.phase, TripSession.Phase.DRIVING, "도착 소식만으론 아직 주행")
    eq(r.phases, listOf("DRIVING@5"), "주행 시작 소식")
    r.ses.onVehicle(PARK.copy(tMillis = T0 + 70_000))
    r.run(80_000)
    eq(r.ses.phase, TripSession.Phase.ENDED, "도착 뒤 정차 → 끝")
    val s = r.ses.summary!!
    eq(s.reason, "arrived", "끝난 이유")
    eq(s.statuses, mapOf("dark" to PromiseStatus.KEPT, "highway" to PromiseStatus.KEPT, "rest" to PromiseStatus.KEPT, "zone" to PromiseStatus.KEPT), "판정 닫힘")
    eq(s.lines, listOf("오늘 약속 4개를 모두 지켰어요. 수고하셨어요."), "요약 문장")
    ok(r.keys().contains("summary.0"), "세워 둔 동안 요약을 말함: ${r.keys()}")
    ok(s.startMs == T0 + 5_100 || s.startMs == T0 + 5_200, "주행 시작 시각 ${s.startMs?.minus(T0)}")
    ok(s.rest.segments == 0 || s.rest.exposed == 0, "짧은 주행 — 쉬기 노출 없음")
}

private fun s65() {
    val a = SessionRig(FOUR)
    a.ses.onFix(seoul(T0)); a.ses.onVehicle(drive(T0))
    a.run(2_000)
    a.ses.onVehicle(PARK.copy(tMillis = T0 + 2_100, ignitionOn = false))
    a.run(3_000)
    eq(a.ses.summary?.reason, "ignition_off", "시동 끔")
    val first = a.ses.end("manual")
    ok(first === a.ses.summary && first.reason == "ignition_off", "끝은 한 번만(두 번째 end는 같은 요약)")

    val b = SessionRig(FOUR)
    b.ses.onFix(seoul(T0)); b.ses.onVehicle(drive(T0))
    b.run(2_000)
    b.ses.onVehicle(PARK.copy(tMillis = T0 + 2_100))
    b.run(29 * MIN)
    eq(b.ses.phase, TripSession.Phase.DRIVING, "29분 정차는 아직(쉼은 15분에 인정)")
    ok(b.ses.memory.events.any { it is LedgerEvent.RestTaken }, "15분 뒤 쉼 기록")
    b.run(31 * MIN)
    eq(b.ses.summary?.reason, "parked_long", "30분 정차 → 끝")

    val c = SessionRig(FOUR)
    c.ses.onFix(seoul(T0)); c.ses.onVehicle(drive(T0))
    c.run(1_000)
    eq(c.ses.end().reason, "manual", "수동 끝")
    eq(c.phases.last(), "ENDED@1", "끝 소식")
    c.run(8_000)
    ok(c.keys().none { it.startsWith("summary") }, "달리는 중엔 요약을 말하지 않음(미룸): ${c.keys()}")
    c.ses.onVehicle(PARK.copy(tMillis = c.abs(8_100)))
    c.run(12_000)
    ok(c.keys().any { it == "summary.0" }, "세우면 요약을 말함: ${c.keys()}")
}

private fun s66() {
    val trip = ReplayParser.parse(PLAN_SAMPLE)
    val real = FakeClock(1_000_000L)
    val player = ReplayPlayer(trip, real, speedup = 1.0)
    val spk = FakeSpeaker()
    val said = mutableListOf<Said>()
    val ses = TripSession(listOf(Promise.HomeBeforeDark(30), Promise.AvoidHighway(), Promise.RestEvery(120)), spk, player.clock,
        listener = object : TripSession.Listener {
            override fun onSpeak(step: Step.Speak, nowMs: Long) { said += Said(nowMs - trip.meta.baseEpochMs, step.key, step.text, step.short, step.repeat, step.attempt) }
        })
    ses.attachReplay(player)
    var cur: Step.Speak? = null
    var endAt = 0L
    var n = 0
    while (n < 3_000) {   // 최대 300초(가상)
        val c = cur
        if (c != null && real.t >= endAt) { ses.onSpeechDone(c.id); cur = null }
        val s = ses.pumpReplay(player)
        if (s is Step.Speak) { cur = s; endAt = real.t + s.estMs }
        if (ses.phase == TripSession.Phase.ENDED && said.any { it.key.startsWith("summary") } && cur == null) break
        real.t += 100
        n++
    }
    ok(said.isNotEmpty() && said[0].key == "dark.risk" && said[0].t < 1_000, "출발 때 경고: ${said.map { it.key + "@" + it.t }}")
    ok(said[0].text.startsWith("이대로면 오후 5시 28분 도착, 해는 오후 5시 22분에 져요"), "문장: ${said[0].text}")
    eq(ses.phase, TripSession.Phase.ENDED, "끝")
    eq(ses.summary!!.reason, "arrived", "도착 뒤 정차로 끝")
    eq(ses.summary!!.statuses, mapOf("dark" to PromiseStatus.KEPT, "highway" to PromiseStatus.KEPT, "rest" to PromiseStatus.KEPT), "모두 지킴")
    val sm = said.first { it.key == "summary.0" }
    ok(sm.t >= 150_000 && sm.text == "오늘 약속 3개를 모두 지켰어요. 수고하셨어요.", "도착 뒤 요약: ${sm.t} ${sm.text}")
    ok(said.none { it.key == "rest.headsup" || it.key == "rest.main" }, "2분 30초 주행엔 쉬기 말 없음")
    ok(player.finished, "재생 끝")
}

private class FakeRoute(val plans: RoutePlans) : RouteSource {
    var requested = 0
    override fun start(sink: Sink<NaviEvent>): AutoCloseable = AutoCloseable { }
    override fun planRoutes(dest: Place, options: List<RouteOption>, done: Sink<RoutePlans>): AutoCloseable {
        requested++
        done.accept(plans)
        return AutoCloseable { }
    }
}

private fun s67() {
    val route = FakeRoute(RoutePlans(mapOf(RouteOption.RECOMMENDED to RoutePlan(RouteOption.RECOMMENDED, 5_000, 1_200, highwayM = 0))))
    val r = SessionRig(FOUR, t0 = kst(2026, 11, 14, 16, 0))   // 16:00 출발 계획 → 16:20 도착(해 지기 전 여유)
    r.ses.onFix(seoul(r.t0))
    r.ses.onVehicle(PARK.copy(tMillis = r.t0))
    r.run(1_000)
    r.ses.plan(Place("집", 37.60, 127.00), route)
    eq(route.requested, 1, "경로 후보 요청")
    val p = r.plans.single()
    eq(p.choice, RouteOption.RECOMMENDED, "추천 경로 선택")
    eq(p.lines, listOf("추천 경로로 가요. 20분 걸려요.", "오후 4시 20분 도착, 해는 오후 5시 22분에 져요. 약속대로 해 지기 전이에요.", "고속도로 없이 가요."), "계획 문장")
    r.run(3_000)
    eq(r.log.map { it.key }, listOf("plan"), "세워 둔 동안 계획 문장을 말함")
    eq(r.log[0].text, "추천 경로로 가요. 20분 걸려요. 오후 4시 20분 도착, 해는 오후 5시 22분에 져요. 약속대로 해 지기 전이에요.", "앞 두 문장을 합쳐 말함")
    // 주행 중엔 미룸(버리지 않음)
    r.ses.onVehicle(drive(r.abs(3_100)))
    r.run(4_000)
    r.ses.plan(Place("집", 37.60, 127.00), route)
    r.run(14_000)
    eq(r.log.map { it.key }, listOf("plan"), "주행 중 새 계획 문장은 말하지 않음")
    r.ses.onVehicle(PARK.copy(tMillis = r.abs(14_100)))
    r.run(17_000)   // 급제동(15 → 0 m/s) 뒤 2초 침묵을 지나서
    eq(r.log.map { it.key }, listOf("plan", "plan"), "세우면 말함")
    ok(r.log[1].t in 16_100..16_300, "급제동 침묵 2초 뒤: ${r.log[1].t}")
}

private fun s68() {
    val r = SessionRig(listOf(Promise.RestEvery(120)))
    r.ses.restoreRest(RestTracker.State(119 * MIN, T0, false, false))
    r.ses.onFix(seoul(T0)); r.ses.onVehicle(drive(T0))
    r.run(3 * MIN)
    eq(r.keys(), listOf("rest.main"), "복원 1분 뒤 2시간 → 본 알림")
    ok(r.log[0].t in 60_000..61_000, "시각 ${r.log[0].t}")
    val st = r.ses.restState()
    ok(st.drivingMs >= 122 * MIN - 1_000 && st.mainDone, "저장할 상태 $st")
    eq(r.ses.statuses["rest"], PromiseStatus.MISSED, "쉬기 상태 못 지킴")
}

private fun s69() {
    val trip = ReplayParser.parse(PLAN_SAMPLE)
    val real = FakeClock(500_000L)
    val p = ReplayPlayer(trip, real)   // 배속 3
    val base = trip.meta.baseEpochMs
    eq(p.virtualAt(500_000L), base, "시작 = 기준 시각")
    eq(p.virtualAt(501_000L), base + 3_000, "실시간 1초 = 가상 3초")
    val seen = mutableListOf<Pair<String, Long>>()
    p.location.start(Sink { seen += "loc" to (it.tMillis - base) })
    p.vehicle.start(Sink { seen += "veh" to (it.tMillis - base) })
    p.route.start(Sink { seen += "nav" to (it.tMillis - base) })
    eq(p.pump(base + 12_500), 6, "12.5초까지 6건")
    eq(seen.map { it.second }, seen.map { it.second }.sorted(), "시간 순")
    eq(seen.filter { it.second == 0L }.map { it.first }, listOf("veh", "loc", "nav"), "같은 시각은 차량 → 위치 → 내비")
    ok(!p.finished, "아직")
    p.pump(base + 150_000)
    ok(p.finished, "150초에 끝")
    eq(seen.count { it.first == "nav" }, 7, "내비 7건")
    p.route.planRoutes(Place("x", 0.0, 0.0), listOf(RouteOption.RECOMMENDED), Sink { plans ->
        eq(plans.plans[RouteOption.RECOMMENDED], RoutePlan(RouteOption.RECOMMENDED, 8_200, 38 * 60), "리플레이 경로 후보 = 첫 trip")
    })
}

private fun s70() {
    val all = listOf<LedgerEvent>(
        LedgerEvent.Due(1, "rest.main", 7_200_000),
        LedgerEvent.Spoken(2, "rest.main", 1, 2, 5_000, -30),
        LedgerEvent.Spoken(3, "dark.risk", 0, 0, 6_000, null),
        LedgerEvent.Yielded(4, "x", 1),
        LedgerEvent.Dropped(5, "y", "expired"),
        LedgerEvent.Digested(6, "z", "info_timeout"),
        LedgerEvent.RestTaken(7, 1, 1_000),
        LedgerEvent.TripEnded(8, 9),
        LedgerEvent.StatusChanged(9, "dark", "AT_RISK", "KEPT", "trip_end"),
        LedgerEvent.Dropped(10, "q\"uote\\슬래시", "줄\n바꿈"),
    )
    for (e in all) eq(LedgerJson.decode(LedgerJson.encode(e)), e, "왕복 $e")
    ok(LedgerJson.encode(all[0]) == """{"t":1,"e":"Due","key":"rest.main","drivingMs":7200000}""", "모양: ${LedgerJson.encode(all[0])}")
    ok(LedgerJson.encode(all[0]).count { it == '\n' } == 0 && LedgerJson.encode(all.last()).count { it == '\n' } == 0, "한 줄")
    eq(LedgerJson.decode("garbage"), null, "깨진 줄")
    eq(LedgerJson.decode("""{"t":1,"e":"Future"}"""), null, "모르는 종류")
    val text = all.joinToString("\n") { LedgerJson.encode(it) } + "\nnot json\n\n"
    eq(LedgerJson.decodeAll(text), all, "파일 전체 읽기(깨진 줄 건너뜀)")
}

private fun s71() {
    val r = SessionRig(FOUR)
    r.ses.onFix(seoul(T0)); r.ses.onVehicle(drive(T0))
    r.run(1_000)
    r.ses.scheduler.submit(promiseU("p", T0 + 10 * MIN), r.clock.t)
    r.run(1_500)
    eq(r.keys(), listOf("p"), "말 시작")
    val a = r.ses.onNavPlayers(1)
    ok(a is VoiceArbiter.Action.Yield && r.spk.stopped.size == 1, "내비 시작 → 멈춤 ${a}")
    r.cutSpeech()
    r.run(4_000)
    eq(r.keys(), listOf("p"), "내비 중엔 다시 말하지 않음")
    r.ses.onNavPlayers(0)   // 4.0초에 내비 끝
    r.ses.onFocus(VoiceArbiter.Focus.GAIN)
    r.run(8_000)
    eq(r.keys(), listOf("p", "p"), "다시 말함")
    ok(r.log[1].short && r.log[1].text == "p 짧게" && r.log[1].t in 5_500..5_700, "1.5초 뒤 짧게: ${r.log[1]}")
}

// ---------------------------------------------------------------- 시연 트립
private val FIVE = listOf(Promise.HomeBeforeDark(30), Promise.AvoidHighway(), Promise.RainCare(), Promise.RestEvery(120), Promise.StayInZone(SEOUL_LAT, SEOUL_LON, 10_000))

/** 트립 파일을 세션으로 끝까지 돌림(가상 100 ms 걸음) → (말한 것, 세션) */
private fun demoRun(json: String, promises: List<Promise> = FIVE): Pair<List<Said>, TripSession> {
    val trip = ReplayParser.parse(json)
    val real = FakeClock(0L)
    val player = ReplayPlayer(trip, real, speedup = 1.0)
    val said = mutableListOf<Said>()
    val ses = TripSession(promises, FakeSpeaker(), player.clock, listener = object : TripSession.Listener {
        override fun onSpeak(step: Step.Speak, nowMs: Long) { said += Said(nowMs - trip.meta.baseEpochMs, step.key, step.text, step.short, step.repeat, step.attempt) }
    })
    ses.attachReplay(player)
    var cur: Step.Speak? = null
    var endAt = 0L
    val limit = (trip.endMs - trip.meta.baseEpochMs) / 100 + 600
    var n = 0
    while (n < limit) {
        val c = cur
        if (c != null && real.t >= endAt) { ses.onSpeechDone(c.id); cur = null }
        val s = ses.pumpReplay(player)
        if (s is Step.Speak) { cur = s; endAt = real.t + s.estMs }
        if (ses.phase == TripSession.Phase.ENDED && cur == null && ses.scheduler.pendingKeys.isEmpty()) break
        real.t += 100
        n++
    }
    return said to ses
}

private fun s72() {
    val dir = listOf("demo/trips", "../demo/trips", "../../demo/trips").map { File(it) }.firstOrNull { it.isDirectory } ?: fail("demo/trips 폴더를 찾지 못함(작업 폴더 ${File(".").absolutePath})")
    fun load(n: String) = File(dir, "$n.json").readText()
    // 1) 병원→집: 출발 때 해 지기 전 경고, 17:22 전 도착 → 모두 지킴
    val (a, sa) = demoRun(load("hospital_home"))
    eq(a.map { it.key }.filterNot { it.startsWith("summary") }, listOf("dark.risk"), "병원→집 발화")
    eq(sa.summary!!.statuses.values.toSet(), setOf(PromiseStatus.KEPT), "병원→집 판정 ${sa.summary!!.statuses}")
    eq(sa.summary!!.lines, listOf("오늘 약속 5개를 모두 지켰어요. 수고하셨어요."), "병원→집 요약")
    eq(sa.summary!!.reason, "arrived", "병원→집 끝 이유")
    // 2) 두 시간 쉬기: 1:50 예고 → 2:00 본 알림 → 2:02 도착 → 쉬기 요약 + 약속 요약
    val (b, sb) = demoRun(load("rest_two_hours"))
    eq(b.map { it.key }.filterNot { it.startsWith("summary") }, listOf("rest.headsup", "rest.main"), "두 시간 쉬기 발화 ${b.map { it.key + "@" + it.t / 1000 }}")
    ok(b[0].t in 6_600_000L..6_606_000L && b[1].t in 7_200_000L..7_206_000L, "예고 1:50·본 알림 2:00: ${b.map { it.t / 1000 }}")
    eq(sb.summary!!.statuses["rest"], PromiseStatus.MISSED, "쉬기 못 지킴")
    eq(sb.summary!!.statuses.filterKeys { it != "rest" }.values.toSet(), setOf(PromiseStatus.KEPT), "나머지 지킴 ${sb.summary!!.statuses}")
    eq(sb.summary!!.lines, listOf("오늘은 두 시간 2분을 쉬지 않고 운전하셨어요. 다음엔 한 시간 50분쯤 쉬어 가요.", "약속 5개 중 4개를 지켰어요. 다음엔 2시간마다 쉬기도 같이 지켜요."), "두 시간 쉬기 요약")
    // 3) 약속 구역 경계: 예고 → 밖 → 돌아옴, 주행 끝은 못 지킴
    val (c, sc) = demoRun(load("zone_edge"))
    eq(c.map { it.key }.filterNot { it.startsWith("summary") }, listOf("zone.edge", "zone.out", "zone.back"), "구역 발화 ${c.map { it.key + "@" + it.t / 1000 }}")
    eq(sc.summary!!.statuses["zone"], PromiseStatus.MISSED, "구역 못 지킴")
    eq(sc.summary!!.lines, listOf("약속 5개 중 4개를 지켰어요. 다음엔 약속 구역도 같이 지켜요."), "구역 요약")
    // 4) 고속도로·비: 고속도로 진입 → 비 시작 → 빗길 과속 → 둘 다 못 지킴
    val (d, sd) = demoRun(load("highway_rain"))
    eq(d.map { it.key }.filterNot { it.startsWith("summary") }, listOf("highway.on", "rain.start", "rain.fast"), "고속도로·비 발화 ${d.map { it.key + "@" + it.t / 1000 }}")
    eq(d[2].text, "빗길에 95예요. 80 아래로 줄여요.", "감속 문장(제한 100의 80%)")
    eq(sd.summary!!.statuses.filterValues { it == PromiseStatus.MISSED }.keys, setOf("highway", "rain"), "고속도로·비 못 지킴 ${sd.summary!!.statuses}")
    eq(sd.summary!!.lines, listOf("약속 5개 중 3개를 지켰어요. 다음엔 고속도로 대신·비 오는 날 조심도 같이 지켜요."), "고속도로·비 요약")
    for ((name, ss) in listOf("병원→집" to sa, "두 시간" to sb, "구역" to sc, "고속도로·비" to sd)) {
        ok(ss.summary!!.digestLines.isEmpty(), "$name — 못 전한 말 없음: ${ss.summary!!.digestLines}")
        ok(ss.summary!!.reason == "arrived", "$name — 도착 뒤 정차로 끝: ${ss.summary!!.reason}")
    }
}
