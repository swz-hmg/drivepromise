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
import app.drivepromise.core.rest.Phrases
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
import app.drivepromise.core.ui.Capability
import app.drivepromise.core.ui.ModeResolver
import app.drivepromise.core.ui.PresentationMode
import app.drivepromise.core.ui.ScreenWhileDriving
import app.drivepromise.core.scene.VoicePhrases
import app.drivepromise.core.voice.ListenTrigger
import app.drivepromise.core.voice.VoiceCommands
import app.drivepromise.core.voice.VoiceIntent
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
        "S73 화면 모드 — 결정표: 세워 둠은 PARKED, 주행 중엔 플랫폼 허용 ∧ 사용자 켜기일 때만 DRIVE_DO, 모르면 음성만" to ::s73,
        "S74 화면 모드 — 전환 규칙: 내려가기(화면→음성)는 즉시, 올라가기(음성→화면)는 세워 둔 동안만 · 능력 판단(차단·가정)" to ::s74,
        "S75 세션 — ‘다시 들려줘’: 마지막 문장을 INFO 로 다시 말함, 말한 것이 없으면 false, 침묵 구간에선 기다림" to ::s75,
        "S77 음성 명령 뜻 — 닫힌 문법(한·영): 다시·약속·조용히·응·아니, ‘약속대로 쉴게’는 대답, 한 음절은 낱말일 때만, 모르면 UNKNOWN" to ::s77,
        "S78 음성 질문(B) — STT가 있으면 2시간 본 알림이 질문, 끝나면 듣기 요청 한 번 · 응/아니 대답과 기록 · ‘다시’는 질문을 다시 들려주고 한 번 더 들음" to ::s78,
        "S79 음성 버튼(A) — ‘약속 알려 줘’는 위험한 약속만 이름, ‘조용히’는 INFO만 10분 미룸(약속 경고·다시·대답은 그대로), 질문 없는 응/아니는 도움말" to ::s79,
        "S80 듣는 동안 — 새 말을 시작하지 않고(마이크가 우리 목소리를 듣지 않게) 끝나면 이어 말함 · 같은 질문에서 ‘다시’ 뒤 다시 듣기는 한 번만" to ::s80,
        "S81 질문 판정 — 도착 15분 안 본 알림(‘곧 도착이에요…’)은 질문이 아니라 듣지 않음 · 질문형일 때만 듣기 요청 · 주행 끝 순간 말하던 문장은 반복을 예약하지 않음(11/6 실행)" to ::s81,
        "S82 리플레이 멈춤 — 듣는 동안 가상 시계가 섬 · 시연 트립 ‘두 시간 쉬기’를 STT 켜고 60배속으로: 질문 → 5초 듣는 동안 트립 멈춤 → ‘응’ → 대답 → 도착 → 요약 2줄 말한 뒤에야 끝(11/6 실행)" to ::s82,
        "S83 ‘약속 알려 줘’ 대답 — 판단 전(UNKNOWN)인 약속이 있으면 ‘모두’라고 하지 않고 이름(하나)·개수(둘 이상)로 따로 말함, 위험한 약속과 섞여도 개수가 맞음(11/6 실행: 약속 5개 화면에 ‘4개 모두’)" to ::s83,
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
    g.update(0, drive(0, 2f))
    ok(!g.stopWindow(0), "달리는 중엔 창 없음")
    g.update(1_000, drive(1_000, 0f))   // 2 m/s → 0: 2 m/s² (급제동 3 m/s² 아님)
    ok(g.stopWindow(1_000) && g.stopWindow(2_900), "정지 뒤 2초 안")
    ok(!g.stopWindow(3_100), "2초 지나면 닫힘")
    g.update(4_000, PARK.copy(tMillis = 4_000))
    ok(!g.stopWindow(4_000) && !g.stopWindow(5_000), "P는 신호 대기가 아님")
    g.update(6_000, drive(6_000, 2f))
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
    r.ses.onNavi(NaviEvent.SpeedLimit(T0 + 5_100, 50))   // 1.3.0: 도로 신호를 받아야 고속도로 약속을 판정(못 받으면 ‘모름’)
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
        }, realClock = real)
    ses.attachReplay(player)
    ses.onNavi(NaviEvent.SpeedLimit(trip.meta.baseEpochMs, 50))   // 1.3.0: 도로 신호(일반도로) — 못 받으면 고속도로 약속은 ‘모름’
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
    ok(said[0].text == "이대로면 5시 28분 도착, 해는 5시 22분에 져요.", "문장(1.3.0: ‘쉬지 말고’ 삭제·짧은 시각): ${said[0].text}")
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
        LedgerEvent.VoiceCommand(11, "QUESTION", "YES", "rest.main"),
        LedgerEvent.VoiceCommand(12, "BUTTON", "STATUS"),
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
    }, realClock = real)
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

private fun demoDir(): File =
    listOf("demo/trips", "../demo/trips", "../../demo/trips").map { File(it) }.firstOrNull { it.isDirectory } ?: fail("demo/trips 폴더를 찾지 못함(작업 폴더 ${File(".").absolutePath})")

private fun s72() {
    fun load(n: String) = File(demoDir(), "$n.json").readText()
    // 1) 병원→집(11/8 Q050: 17:16 출발·도착 예상 17:19·1.2 km — 재생 구간과 도착 시각이 맞게): 출발 때 해 지기 전 경고, 17:18:30 도착(일몰 17:22 전) → 모두 지킴
    val (a, sa) = demoRun(load("hospital_home"))
    eq(a.map { it.key }.filterNot { it.startsWith("summary") }, listOf("dark.risk"), "병원→집 발화")
    eq(a[0].text, "이대로면 5시 19분 도착, 해는 5시 22분에 져요.", "병원→집 경고 문장(도착 예상이 일몰 전 — 짧은 문장은 ‘해 지기 직전’)")
    eq(sa.summary!!.statuses.values.toSet(), setOf(PromiseStatus.KEPT), "병원→집 판정 ${sa.summary!!.statuses}")
    eq(sa.summary!!.lines, listOf("오늘 약속 5개를 모두 지켰어요. 수고하셨어요."), "병원→집 요약")
    eq(sa.summary!!.reason, "arrived", "병원→집 끝 이유")
    // 2) 두 시간 쉬기(트립 v0.2, 11/6): 1:50 예고 → 2:00 본 알림(도착까지 18분이라 ‘곧 도착’ 문장이 아님) → 2:13:20 도착(15분 반복 전) → 쉬기 요약 + 약속 요약
    val (b, sb) = demoRun(load("rest_two_hours"))
    eq(b.map { it.key }.filterNot { it.startsWith("summary") }, listOf("rest.headsup", "rest.main"), "두 시간 쉬기 발화 ${b.map { it.key + "@" + it.t / 1000 }}")
    ok(b[0].t in 6_600_000L..6_606_000L && b[1].t in 7_200_000L..7_206_000L, "예고 1:50·본 알림 2:00: ${b.map { it.t / 1000 }}")
    eq(sb.summary!!.statuses["rest"], PromiseStatus.MISSED, "쉬기 못 지킴")
    eq(sb.summary!!.statuses.filterKeys { it != "rest" }.values.toSet(), setOf(PromiseStatus.KEPT), "나머지 지킴 ${sb.summary!!.statuses}")
    eq(b[1].text, "두 시간째예요. 약속대로 쉬어 가요.", "STT 없으면 질문 아닌 본 알림(도착까지 18분 — ‘곧 도착’ 아님)")
    eq(sb.summary!!.lines, listOf("오늘은 두 시간 13분을 쉬지 않고 운전하셨어요. 다음엔 한 시간 50분쯤 쉬어 가요.", "약속 5개 중 4개를 지켰어요. 다음엔 2시간마다 쉬기도 같이 지켜요."), "두 시간 쉬기 요약")
    // 3) 약속 구역 경계: 예고 → 밖 → 돌아옴, 주행 끝은 못 지킴
    val (c, sc) = demoRun(load("zone_edge"))
    eq(c.map { it.key }.filterNot { it.startsWith("summary") }, listOf("zone.edge", "zone.out", "zone.back"), "구역 발화 ${c.map { it.key + "@" + it.t / 1000 }}")
    eq(sc.summary!!.statuses["zone"], PromiseStatus.MISSED, "구역 못 지킴")
    eq(sc.summary!!.lines, listOf("약속 5개 중 4개를 지켰어요. 다음엔 약속 구역도 같이 지켜요."), "구역 요약")
    // 4) 고속도로·비: 고속도로 진입 → 비 시작 → 빗길 과속 → 둘 다 못 지킴
    val (d, sd) = demoRun(load("highway_rain"))
    eq(d.map { it.key }.filterNot { it.startsWith("summary") }, listOf("highway.ahead", "highway.on", "rain.start", "rain.fast"), "고속도로·비 발화 ${d.map { it.key + "@" + it.t / 1000 }}")
    eq(d[0].text, "앞 갈림길은 판교IC 방면이에요. 약속대로 일반도로로 가요.", "1.3.0 갈림길 예고(내비 방면 설명)")
    eq(d[3].text, "빗길에 95예요. 80 아래로 줄여요.", "감속 문장(제한 100의 80%)")
    eq(sd.summary!!.statuses.filterValues { it == PromiseStatus.MISSED }.keys, setOf("highway", "rain"), "고속도로·비 못 지킴 ${sd.summary!!.statuses}")
    eq(sd.summary!!.lines, listOf("약속 5개 중 3개를 지켰어요. 다음엔 고속도로 대신·비 오는 날 조심도 같이 지켜요."), "고속도로·비 요약")
    for ((name, ss) in listOf("병원→집" to sa, "두 시간" to sb, "구역" to sc, "고속도로·비" to sd)) {
        ok(ss.summary!!.digestLines.isEmpty(), "$name — 못 전한 말 없음: ${ss.summary!!.digestLines}")
        ok(ss.summary!!.reason == "arrived", "$name — 도착 뒤 정차로 끝: ${ss.summary!!.reason}")
    }
}

// ---------------------------------------------------------------- S73~S75 (11/5 Q036: DO 인정 가정 + 주행 화면)
private fun s73() {
    val Y = Capability.YES; val N = Capability.NO; val U = Capability.UNKNOWN
    val ON = ScreenWhileDriving.ON; val VO = ScreenWhileDriving.VOICE_ONLY
    // 화면모드전환설계 v0.1 결정표(기어 P / D·R·N × 플랫폼 DO × 사용자 설정)
    eq(ModeResolver.resolve(false, Y, ON), PresentationMode.PARKED, "P · 허용 · 켜기 → 정차 화면")
    eq(ModeResolver.resolve(false, N, VO), PresentationMode.PARKED, "P 는 설정과 상관없이 정차 화면")
    eq(ModeResolver.resolve(true, Y, ON), PresentationMode.DRIVE_DO, "주행 · 허용 · 켜기 → 화면+음성")
    eq(ModeResolver.resolve(true, Y, VO), PresentationMode.DRIVE_VOICE, "주행 · 허용 · 음성만 → 음성만")
    eq(ModeResolver.resolve(true, N, ON), PresentationMode.DRIVE_VOICE, "주행 · 거부 · 켜기 → 음성만")
    eq(ModeResolver.resolve(true, N, VO), PresentationMode.DRIVE_VOICE, "주행 · 거부 · 음성만 → 음성만")
    eq(ModeResolver.resolve(true, U, ON), PresentationMode.DRIVE_VOICE, "주행 · 모름 → 음성만(안전한 기본값)")
}

private fun s74() {
    val P = PresentationMode.PARKED; val DO = PresentationMode.DRIVE_DO; val V = PresentationMode.DRIVE_VOICE
    eq(ModeResolver.next(P, DO, true), DO, "세워 둠에서 출발: 화면+음성으로")
    eq(ModeResolver.next(P, V, true), V, "세워 둠에서 출발: 음성만으로")
    eq(ModeResolver.next(DO, V, true), V, "주행 중 내려가기(차단·설정·UX 제한)는 즉시")
    eq(ModeResolver.next(V, DO, true), V, "주행 중 올라가기는 하지 않음 — 다음 정차까지 음성만 유지")
    eq(ModeResolver.next(V, P, false), P, "세우면 정차 화면")
    eq(ModeResolver.next(DO, P, false), P, "세우면 정차 화면(화면에서도)")
    eq(ModeResolver.next(V, DO, false), DO, "세워 둔 동안의 올라가기는 허용")
    eq(ModeResolver.next(DO, DO, true), DO, "같으면 그대로")
    // 능력 판단: 차단을 봤으면 NO 가 우선, 가정이 켜져 있으면 플랫폼 답과 상관없이 YES, 둘 다 아니면 플랫폼 답, 답이 없으면 UNKNOWN
    eq(ModeResolver.capability(platformOptimized = false, assumeDo = true, blockedSeen = false), Capability.YES, "에뮬레이터(거부) + 가정 → YES")
    eq(ModeResolver.capability(platformOptimized = false, assumeDo = true, blockedSeen = true), Capability.NO, "차단을 봤으면 가정보다 우선 NO")
    eq(ModeResolver.capability(platformOptimized = true, assumeDo = false, blockedSeen = false), Capability.YES, "플랫폼 허용 → YES")
    eq(ModeResolver.capability(platformOptimized = false, assumeDo = false, blockedSeen = false), Capability.NO, "플랫폼 거부 → NO")
    eq(ModeResolver.capability(platformOptimized = null, assumeDo = false, blockedSeen = false), Capability.UNKNOWN, "답 없음 → UNKNOWN")
    // 전체 흐름: 가정 ON 으로 출발 → 주행 중 차단 감지 → 즉시 음성만 → 세우면 정차 → 다음 출발도 음성만(차단 기억)
    var cur = P
    fun step(driving: Boolean, blocked: Boolean): PresentationMode {
        val cap = ModeResolver.capability(false, true, blocked)
        cur = ModeResolver.next(cur, ModeResolver.resolve(driving, cap, ScreenWhileDriving.ON), driving); return cur
    }
    eq(step(true, false), DO, "출발: 화면+음성")
    eq(step(true, true), V, "차단 감지: 즉시 음성만")
    eq(step(false, true), P, "정차")
    eq(step(true, true), V, "다음 출발: 차단을 기억해 음성만")
}

private fun s75() {
    val r = SessionRig(FOUR)
    r.ses.onFix(seoul(T0))
    r.ses.onVehicle(PARK.copy(tMillis = T0))
    eq(r.ses.repeatLast(), false, "말한 것이 없으면 false")
    r.ses.onVehicle(drive(T0 + 1_000))
    r.ses.onNavi(NaviEvent.Trip(T0 + 1_000, T0 + 60 * MIN, 30_000))   // 17:50 도착 → 해 지기 전 귀가 위험 → dark.risk
    r.run(10_000)
    ok(r.keys().contains("dark.risk"), "출발 경고를 말함: ${r.keys()}")
    val first = r.ses.lastSpoken!!.text
    eq(r.ses.repeatLast(), true, "마지막 문장이 있으면 true")
    eq(r.ses.repeatLast(), true, "연타해도 받음")
    r.run(20_000)
    val rep = r.log.filter { it.key == "repeat" }
    eq(rep.size, 1, "연타해도 다시 말함은 1회(앞 요청 취소): ${r.keys()}")
    eq(rep[0].text, first, "같은 문장을 다시 말함")
    // 침묵 구간(방향지시등 켜짐)에서는 기다렸다가 꺼지면 말함
    r.ses.onVehicle(drive(T0 + 20_100).copy(turnSignal = Turn.LEFT))
    eq(r.ses.repeatLast(), true, "요청은 받음")
    r.run(26_000)
    eq(r.log.count { it.key == "repeat" }, 1, "방향지시등 켜진 동안은 말하지 않음")
    r.ses.onVehicle(drive(T0 + 26_100))
    r.run(40_000)
    eq(r.log.count { it.key == "repeat" }, 2, "꺼지면 말함: ${r.keys()}")
}


// ---------------------------------------------------------------- S77~S79 (11/6 Q040: STT C안 — A 버튼 + B 물을 때만 듣기)
private fun s77() {
    val ko = listOf(
        "다시 들려줘" to VoiceIntent.REPEAT, "뭐라고?" to VoiceIntent.REPEAT, "한 번 더" to VoiceIntent.REPEAT, "아니 다시 말해 줘" to VoiceIntent.REPEAT,
        "약속 알려 줘" to VoiceIntent.STATUS, "내 약속 어때" to VoiceIntent.STATUS, "약속" to VoiceIntent.STATUS, "상태 알려줘" to VoiceIntent.STATUS,
        "조용히 해" to VoiceIntent.QUIET, "그만해" to VoiceIntent.QUIET, "말 좀 그만" to VoiceIntent.QUIET,
        "응" to VoiceIntent.YES, "네." to VoiceIntent.YES, "그래 쉴게" to VoiceIntent.YES, "약속대로 쉴게요" to VoiceIntent.YES, "알겠어" to VoiceIntent.YES,
        "아니" to VoiceIntent.NO, "아니요 괜찮아요" to VoiceIntent.NO, "안 쉬어도 돼" to VoiceIntent.NO, "조금 더 갈게" to VoiceIntent.NO, "나중에" to VoiceIntent.NO,
        "네비 켜 줘" to VoiceIntent.UNKNOWN, "음" to VoiceIntent.UNKNOWN, "" to VoiceIntent.UNKNOWN, "오늘 날씨" to VoiceIntent.UNKNOWN,
    )
    for ((t, want) in ko) eq(VoiceCommands.match(t, Lang.KO), want, "한국어 ‘$t’")
    val en = listOf("say that again" to VoiceIntent.REPEAT, "my promises" to VoiceIntent.STATUS, "be quiet" to VoiceIntent.QUIET,
        "yes" to VoiceIntent.YES, "okay let's rest" to VoiceIntent.YES, "no" to VoiceIntent.NO, "not now" to VoiceIntent.NO, "navigate home" to VoiceIntent.UNKNOWN)
    for ((t, want) in en) eq(VoiceCommands.match(t, Lang.EN), want, "영어 ‘$t’")
    eq(VoiceCommands.match("응", Lang.EN), VoiceIntent.YES, "영어 설정에서도 한국어 대답을 알아들음")
    eq(VoicePhrases.josa("해 지기 전 귀가", "이", "가"), "가", "받침 없음 → 가")
    eq(VoicePhrases.josa("약속 구역", "이", "가"), "이", "받침 있음 → 이")
}

private fun s78() {
    // STT 없음(1.1.0과 같음): 문장 그대로, 듣기 요청 없음
    val off = SessionRig(listOf(Promise.RestEvery(120)))
    off.ses.restoreRest(RestTracker.State(119 * MIN, T0, true, false))
    off.ses.onFix(seoul(T0)); off.ses.onVehicle(drive(T0))
    off.run(70_000)
    eq(off.log.map { it.text }, listOf("두 시간째예요. 약속대로 쉬어 가요."), "STT가 없으면 질문으로 바꾸지 않음")
    eq(off.ses.takeListenRequest(), null, "듣기 요청 없음")
    // STT 있음: 질문 → 끝나면 듣기 요청 한 번 → ‘응’ → 대답 + 기록
    val r = SessionRig(listOf(Promise.RestEvery(120)))
    r.ses.voiceInput = true
    r.ses.restoreRest(RestTracker.State(119 * MIN, T0, true, false))
    r.ses.onFix(seoul(T0)); r.ses.onVehicle(drive(T0))
    var req: ListenTrigger? = null
    r.run(66_000) { if (req == null) req = r.ses.takeListenRequest() }
    eq(r.log.map { it.text }, listOf("두 시간째예요. 쉬어 갈까요?"), "질문형 본 알림")
    eq(req, ListenTrigger.QUESTION, "질문이 끝나면 듣기 요청")
    eq(r.ses.takeListenRequest(), null, "요청은 한 번만")
    eq(r.ses.onVoice("응 쉴게", ListenTrigger.QUESTION), VoiceIntent.YES, "대답 ‘응 쉴게’")
    r.run(72_000)
    eq(r.log.last().text, "좋아요. 다음 쉴 곳에서 쉬어요.", "응 → 대답")
    val vc = r.ses.memory.events.filterIsInstance<LedgerEvent.VoiceCommand>()
    eq(vc, listOf(LedgerEvent.VoiceCommand(vc[0].tMillis, "QUESTION", "YES", "rest.main")), "기록: 뜻과 질문만(원문 없음)")
    eq(r.ses.onVoice("응", ListenTrigger.BUTTON), VoiceIntent.YES, "질문이 닫힌 뒤의 ‘응’")
    r.run(78_000)
    eq(r.log.last().key, "voice.help", "질문 없는 대답 → 도움말")
    // ‘다시’ → 질문을 다시 들려주고 한 번 더 들음(한 번만), 그다음 ‘아니’
    val q = SessionRig(listOf(Promise.RestEvery(120)))
    q.ses.voiceInput = true
    q.ses.restoreRest(RestTracker.State(119 * MIN, T0, true, false))
    q.ses.onFix(seoul(T0)); q.ses.onVehicle(drive(T0))
    q.run(66_000)
    eq(q.ses.takeListenRequest(), ListenTrigger.QUESTION, "첫 질문 뒤 듣기")
    eq(q.ses.onVoice("뭐라고?", ListenTrigger.QUESTION), VoiceIntent.REPEAT, "다시")
    var again: ListenTrigger? = null
    q.run(72_000) { if (again == null) again = q.ses.takeListenRequest() }
    eq(q.log.last().key, "repeat", "질문을 다시 들려줌")
    eq(q.log.last().text, "두 시간째예요. 쉬어 갈까요?", "같은 질문")
    eq(again, ListenTrigger.QUESTION, "다시 들려준 뒤 한 번 더 들음")
    eq(q.ses.onVoice("아니 괜찮아", ListenTrigger.QUESTION), VoiceIntent.NO, "아니")
    q.run(78_000)
    eq(q.log.last().text, "알겠어요. 조심히 가요.", "아니 → 대답")
    // 질문이 끝나고 30초 안에 ‘말하기’로 한 대답도 그 질문의 대답
    val b = SessionRig(listOf(Promise.RestEvery(120)))
    b.ses.voiceInput = true
    b.ses.restoreRest(RestTracker.State(119 * MIN, T0, true, false))
    b.ses.onFix(seoul(T0)); b.ses.onVehicle(drive(T0))
    b.run(80_000)
    eq(b.ses.onVoice("네", ListenTrigger.BUTTON), VoiceIntent.YES, "버튼으로 한 대답")
    b.run(86_000)
    eq(b.log.last().key, "voice.yes", "30초 안이면 질문의 대답")
    eq(b.ses.memory.events.filterIsInstance<LedgerEvent.VoiceCommand>().single().question, "rest.main", "기록에 질문 key")
}

private fun s79() {
    val r = SessionRig(FOUR)
    r.ses.voiceInput = true
    r.ses.onFix(seoul(T0)); r.ses.onVehicle(PARK.copy(tMillis = T0))
    r.run(2_000)
    eq(r.ses.onVoice("약속 알려 줘", ListenTrigger.BUTTON), VoiceIntent.STATUS, "약속 알려 줘")
    r.run(8_000)
    ok(r.log.last().key == "voice.status", "상태 대답: ${r.keys()}")
    val st = r.log.last().text
    eq(st, "약속 3개는 지키는 중이에요. 해 지기 전 귀가는 아직 몰라요.", "위험 없으면 개수만 — 목적지가 없어 판단 전인 약속은 따로(‘모두’라고 하지 않음, 1.2.2)")
    eq(r.ses.onVoice("다시", ListenTrigger.BUTTON), VoiceIntent.REPEAT, "다시")
    r.run(14_000)
    eq(r.log.last().key, "repeat", "다시 → 마지막 문장")
    eq(r.log.last().text, st, "같은 문장")
    // 주행 시작: 해 지기 전 귀가 위험(dark.risk, PROMISE) — ‘조용히’ 중에도 말함, INFO(출발 전 계획 문장)는 미룸
    eq(r.ses.onVoice("조용히 해", ListenTrigger.BUTTON), VoiceIntent.QUIET, "조용히")
    r.run(20_000)
    eq(r.log.last().text, "네, 10분 동안 조용히 할게요.", "조용히 대답은 말함")
    r.ses.scheduler.submit(Utterance("info.test", Priority.INFO, 2_000, render = { "INFO 문장" }), r.clock.t)
    r.ses.onVehicle(drive(r.clock.t))
    r.ses.onNavi(NaviEvent.Trip(r.clock.t, T0 + 60 * MIN, 30_000))
    r.run(40_000)
    ok(r.keys().contains("dark.risk"), "약속 경고는 조용히 중에도: ${r.keys()}")
    ok(!r.keys().contains("info.test"), "INFO는 미룸: ${r.keys()}")
    eq(r.ses.onVoice("다시", ListenTrigger.BUTTON), VoiceIntent.REPEAT, "조용히 중에도 다시는 됨")
    r.run(46_000)
    eq(r.log.last().key, "repeat", "직접 청한 말은 조용히 중에도")
    r.run(10 * MIN + 30_000)
    val fate = r.ses.memory.events.filter { (it is LedgerEvent.Digested && it.key == "info.test") || (it is LedgerEvent.Spoken && it.key == "info.test") }
    ok(fate.isNotEmpty(), "미뤄진 INFO는 늦은 알림 규칙대로 정리(말하거나 ‘못 전한 말’로): ${r.keys()}")
    r.ses.scheduler.submit(Utterance("info.after", Priority.INFO, 2_000, render = { "다시 말함" }), r.clock.t)
    r.run(10 * MIN + 40_000)
    ok(r.keys().contains("info.after"), "10분이 지나면 INFO를 다시 말함: ${r.keys()}")
    val risky = VoicePhrases.status(Lang.KO, FOUR, mapOf("dark" to PromiseStatus.AT_RISK, "highway" to PromiseStatus.ON_TRACK, "rest" to PromiseStatus.ON_TRACK, "zone" to PromiseStatus.MISSED))
    eq(risky, "해 지기 전 귀가·약속 구역이 위험해요. 나머지는 지키는 중이에요.", "위험한 약속만 이름(조사 맞춤)")
    eq(VoicePhrases.status(Lang.KO, FOUR, emptyMap()), "아직 판단할 신호가 없어요.", "신호 없음")
}

private fun s80() {
    // 듣는 동안(listening=true) 큐에 든 말은 시작하지 않음 → 끝나면 말함
    val r = SessionRig(FOUR)
    r.ses.voiceInput = true
    r.ses.onFix(seoul(T0)); r.ses.onVehicle(PARK.copy(tMillis = T0))
    r.run(2_000)
    r.ses.listening = true
    r.ses.scheduler.submit(Utterance("info.hold", Priority.INFO, 2_000, render = { "기다린 말" }), r.clock.t)
    r.run(7_000)
    ok(!r.keys().contains("info.hold"), "듣는 동안엔 말하지 않음: ${r.keys()}")
    r.ses.listening = false
    r.run(9_000)
    ok(r.keys().contains("info.hold"), "듣기가 끝나면 이어 말함: ${r.keys()}")
    // ‘다시’ 뒤 다시 듣기: 한 질문에 한 번만(새 질문이 시작되면 횟수는 0으로 — TripSession.tick)
    val q = SessionRig(listOf(Promise.RestEvery(120)))
    q.ses.voiceInput = true
    q.ses.restoreRest(RestTracker.State(119 * MIN, T0, true, false))
    q.ses.onFix(seoul(T0)); q.ses.onVehicle(drive(T0))
    q.run(66_000)
    eq(q.ses.takeListenRequest(), ListenTrigger.QUESTION, "첫 질문 뒤 듣기")
    q.ses.onVoice("다시", ListenTrigger.QUESTION)
    var again: ListenTrigger? = null
    q.run(72_000) { if (again == null) again = q.ses.takeListenRequest() }
    eq(again, ListenTrigger.QUESTION, "다시 들려준 뒤 한 번 더 들음")
    q.ses.onVoice("다시", ListenTrigger.QUESTION)
    var third: ListenTrigger? = null
    q.run(78_000) { if (third == null) third = q.ses.takeListenRequest() }
    eq(third, null, "같은 질문에서 두 번째 ‘다시’ 뒤엔 더 듣지 않음")
}

// ---------------------------------------------------------------- S81~S82 (11/6 Q041: 실행 결과 보정 — 질문 판정·리플레이 멈춤·주행 끝 뒤 반복)
private fun s81() {
    // 1) 도착 15분 안: 본 알림은 ‘곧 도착이에요…’(질문 아님) → 듣기 요청 없음(11/6 실행: 이 문장 뒤에 5초 들으며 요약이 밀림)
    val soon = SessionRig(listOf(Promise.RestEvery(120)))
    soon.ses.voiceInput = true
    soon.ses.restoreRest(RestTracker.State(119 * MIN, T0, true, false))
    soon.ses.onFix(seoul(T0)); soon.ses.onVehicle(drive(T0))
    soon.ses.onNavi(NaviEvent.Trip(T0, T0 + 10 * MIN, 8_000))   // 10분 뒤 도착
    var req: ListenTrigger? = null
    soon.run(70_000) { if (req == null) req = soon.ses.takeListenRequest() }
    eq(soon.log.map { it.text }, listOf("곧 도착이에요. 도착하면 꼭 쉬어요."), "도착 15분 안 본 알림")
    eq(req, null, "질문이 아니면 듣기 요청 없음")
    // 2) 도착까지 멀면 질문 → 듣기 요청(S78과 같음)
    val far = SessionRig(listOf(Promise.RestEvery(120)))
    far.ses.voiceInput = true
    far.ses.restoreRest(RestTracker.State(119 * MIN, T0, true, false))
    far.ses.onFix(seoul(T0)); far.ses.onVehicle(drive(T0))
    far.ses.onNavi(NaviEvent.Trip(T0, T0 + 40 * MIN, 30_000))
    var req2: ListenTrigger? = null
    far.run(70_000) { if (req2 == null) req2 = far.ses.takeListenRequest() }
    eq(far.log.map { it.text }, listOf("두 시간째예요. 쉬어 갈까요?"), "도착까지 40분 — 질문")
    eq(req2, ListenTrigger.QUESTION, "질문이면 듣기 요청")
    ok(Phrases.isQuestion("Shall we take a break? ") && !Phrases.isQuestion("Almost there. Please rest when you arrive."), "영어 문장 질문 판정")
    // 3) 주행 끝 순간 말하던 본 알림이 끝나도 15분 반복을 예약하지 않음(11/6 실행: 끝난 뒤 반복이 대기열에 남음)
    val e = SessionRig(listOf(Promise.RestEvery(120)))
    e.ses.restoreRest(RestTracker.State(119 * MIN, T0, true, false))
    e.ses.onFix(seoul(T0)); e.ses.onVehicle(drive(T0))
    e.run(61_000)
    eq(e.ses.scheduler.speakingKey, "rest.main", "본 알림을 말하는 중")
    e.ses.end("manual")
    ok(e.ses.pendingAfterEnd, "끝났지만 말하는 중·요약 대기 → 아직 바쁨")
    e.ses.onVehicle(PARK.copy(tMillis = e.clock.t))
    e.run(90_000)
    ok("rest.main" !in e.ses.scheduler.pendingKeys, "끝난 뒤 반복 예약 없음: ${e.ses.scheduler.pendingKeys}")
    ok(e.keys().count { it.startsWith("summary.") } == 2, "세운 뒤 요약 2줄: ${e.keys()}")
    ok(!e.ses.pendingAfterEnd, "요약까지 말하면 한가함")
    ok(e.ses.memory.events.any { it is LedgerEvent.Spoken && it.key == "rest.main" }, "본 알림은 끝까지 말함")
}

private fun s82() {
    val trip = ReplayParser.parse(File(demoDir(), "rest_two_hours.json").readText())
    val base = trip.meta.baseEpochMs
    // 1) 재생기 멈춤 — 듣는 동안 가상 시계가 섬(60배속: 실시간 1초 = 가상 1분)
    val real = FakeClock(0L)
    val p = ReplayPlayer(trip, real, speedup = 60.0)
    real.t = 1_000
    eq(p.clock.nowMs() - base, 60_000L, "1초 = 가상 1분")
    p.hold(true); real.t = 6_000
    eq(p.clock.nowMs() - base, 60_000L, "멈춘 5초 동안 가상 시각 그대로")
    ok(p.held, "멈춤 상태")
    p.hold(true); real.t = 6_500
    eq(p.clock.nowMs() - base, 60_000L, "같은 값으로 다시 불러도 그대로")
    p.hold(false); real.t = 7_500
    eq(p.clock.nowMs() - base, 120_000L, "다시 흐름(멈춘 5.5초는 뺌)")
    eq(p.heldMs, 5_500L, "멈춘 시간 합")
    // 2) 시연 트립을 앱처럼(실시간 100 ms · 60배속 · STT 켬) 끝까지 — 질문 뒤 5초 듣는 동안 트립이 멈추고, ‘응’ 뒤 이어서 도착·요약
    val real2 = FakeClock(0L)
    val p2 = ReplayPlayer(trip, real2, speedup = 60.0)
    val said = mutableListOf<Said>()
    val ses = TripSession(FIVE, FakeSpeaker(), p2.clock, listener = object : TripSession.Listener {
        override fun onSpeak(step: Step.Speak, nowMs: Long) { said += Said(nowMs - base, step.key, step.text, step.short, step.repeat, step.attempt) }
    }, realClock = real2)
    ses.voiceInput = true
    ses.attachReplay(p2)
    var cur: Step.Speak? = null
    var endAt = 0L
    var listenUntil = -1L
    var vAtListen = 0L
    var frozen = true
    var listens = 0
    var n = 0
    while (n < 4_000) {   // 실시간 400초 한도(트립 8,003초 ÷ 60 ≈ 134초 + 듣기·말)
        val c = cur
        if (c != null && real2.t >= endAt) { ses.onSpeechDone(c.id); cur = null }
        ses.takeListenRequest()?.let { t -> if (t == ListenTrigger.QUESTION) { listens++; ses.listening = true; listenUntil = real2.t + 5_000; vAtListen = p2.clock.nowMs() } }
        if (listenUntil >= 0 && real2.t >= listenUntil) {
            if (p2.clock.nowMs() != vAtListen) frozen = false
            eq(ses.onVoice("응", ListenTrigger.QUESTION), VoiceIntent.YES, "대답 ‘응’")
            ses.listening = false
            listenUntil = -1
        }
        val s = ses.pumpReplay(p2)
        if (s is Step.Speak) { cur = s; endAt = real2.t + s.estMs }
        if (ses.phase == TripSession.Phase.ENDED && cur == null && !ses.pendingAfterEnd) break
        real2.t += 100
        n++
    }
    ok(n < 4_000, "트립이 끝나고 할 말을 다 함(${n / 10}초)")
    eq(listens, 1, "질문 뒤 듣기 한 번")
    ok(frozen, "듣는 5초 동안 가상 시각 그대로(60배속이면 5분이 흐를 뻔)")
    val keys = said.map { it.key }
    eq(keys.filterNot { it.startsWith("summary") }, listOf("rest.headsup", "rest.main", "voice.yes"), "발화 열 ${said.map { it.key + "@" + it.t / 1000 }}")
    eq(said[1].text, "두 시간째예요. 쉬어 갈까요?", "질문형 본 알림(도착까지 18분)")
    eq(said[2].text, "좋아요. 다음 쉴 곳에서 쉬어요.", "응 → 대답")
    eq(keys.filter { it.startsWith("summary") }, listOf("summary.0", "summary.1"), "요약 2줄을 말함")
    eq(ses.summary!!.lines[0], "오늘은 두 시간 13분을 쉬지 않고 운전하셨어요. 다음엔 한 시간 50분쯤 쉬어 가요.", "쉬기 요약")
    eq(ses.summary!!.statuses["rest"], PromiseStatus.MISSED, "쉬기 못 지킴(대답은 의향일 뿐 — 실제로 쉬지 않음)")
    eq(ses.summary!!.reason, "arrived", "도착 뒤 정차로 끝")
    val vc = ses.memory.events.filterIsInstance<LedgerEvent.VoiceCommand>()
    eq(vc.map { Triple(it.trigger, it.intent, it.question) }, listOf(Triple("QUESTION", "YES", "rest.main")), "기록: 뜻과 질문만")
    ok(p2.heldMs in 4_900L..5_200L, "멈춘 시간 ≈ 5초: ${p2.heldMs}")
}

// ---------------------------------------------------------------- S83 (11/6 Q043: run_1106_135232 — ‘약속 4개 모두 지키는 중이에요.’)
private fun s83() {
    val five = listOf(Promise.HomeBeforeDark(30), Promise.AvoidHighway(), Promise.RainCare(), Promise.RestEvery(120), Promise.StayInZone(SEOUL_LAT, SEOUL_LON, 5_000))
    val on = PromiseStatus.ON_TRACK
    val all = mapOf("dark" to on, "highway" to on, "rain" to on, "rest" to on, "zone" to on)
    eq(VoicePhrases.status(Lang.KO, five, all), "약속 5개 모두 지키는 중이에요.", "모두 판단됨 → ‘모두’")
    // 11/6 실행 그대로: 세워 둔 채 목적지 없음 → 해 지기 전 귀가만 UNKNOWN
    val noDest = all - "dark"
    val ko1 = VoicePhrases.status(Lang.KO, five, noDest)
    eq(ko1, "약속 4개는 지키는 중이에요. 해 지기 전 귀가는 아직 몰라요.", "판단 전 하나는 이름으로")
    ok(!ko1.contains("모두"), "‘모두’라고 하지 않음: $ko1")
    eq(VoicePhrases.status(Lang.EN, five, noDest), "4 promises are on track; home before dark is not known yet.", "영어도 같은 구조")
    eq(VoicePhrases.status(Lang.KO, five, noDest + ("dark" to PromiseStatus.UNKNOWN)), ko1, "UNKNOWN을 직접 넣어도 같음")
    // 받침 있는 이름(조사 ‘은’)
    eq(VoicePhrases.status(Lang.KO, five, all - "zone"), "약속 4개는 지키는 중이에요. 약속 구역은 아직 몰라요.", "받침 → ‘은’")
    // 둘 이상 판단 전 → 개수
    eq(VoicePhrases.status(Lang.KO, five, all - "dark" - "zone"), "약속 3개는 지키는 중이에요. 2개는 아직 몰라요.", "판단 전 둘 이상은 개수로")
    // 위험 + 판단 전: ‘나머지’가 판단 전 약속까지 지킨다고 말하지 않게 개수로
    val mix = noDest + ("zone" to PromiseStatus.AT_RISK)
    eq(VoicePhrases.status(Lang.KO, five, mix), "약속 구역이 위험해요. 3개는 지키는 중이에요. 해 지기 전 귀가는 아직 몰라요.", "위험 하나 + 판단 전 하나(1.3.0: 판단 전도 말함)")
    eq(VoicePhrases.status(Lang.KO, five, all + ("zone" to PromiseStatus.AT_RISK)), "약속 구역이 위험해요. 나머지는 지키는 중이에요.", "판단 전이 없으면 ‘나머지’ 그대로")
    // 해당 없음(NOT_APPLICABLE)은 판단 전으로 세지 않음
    eq(VoicePhrases.status(Lang.KO, five, all + ("highway" to PromiseStatus.NOT_APPLICABLE)), "약속 4개 모두 지키는 중이에요.", "해당 없음은 빼고 ‘모두’")
    eq(VoicePhrases.status(Lang.KO, five, emptyMap()), "아직 판단할 신호가 없어요.", "신호 없음은 그대로")
}
