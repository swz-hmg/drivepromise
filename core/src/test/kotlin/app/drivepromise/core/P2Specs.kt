package app.drivepromise.core

import app.drivepromise.core.engine.PromiseEngine
import app.drivepromise.core.gate.QuietGate
import app.drivepromise.core.model.Gear
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.model.TurnType
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.plan.SolarCalc
import app.drivepromise.core.rest.RestTracker
import app.drivepromise.core.session.TripSession
import app.drivepromise.core.source.Clock
import app.drivepromise.core.speech.AudioUsage
import app.drivepromise.core.speech.QuietPolicy
import app.drivepromise.core.speech.QuietWindows
import app.drivepromise.core.speech.RestPolicy
import app.drivepromise.core.speech.Speaker
import app.drivepromise.core.speech.SpeechScheduler
import app.drivepromise.core.speech.Step
import app.drivepromise.core.speech.VoiceArbiter
import app.drivepromise.core.speech.World
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.3.3 — 11/9 종합 점검 P2 중 G2와 무관한 코어 7건(프로브 B10·B11·B17·B21·B22·B23·B24)을 고친 뒤 정식 스펙으로.
 * (B05·B06은 NaviHelper 3.0.5 TBTInfo에 도로 이름 필드가 없어 실제 앱에서는 생기지 않음 — 리플레이 데이터에서만. B18 음성 문법은 G2 사전 등록 규칙으로)
 */
object P2Specs {
    val all: List<Pair<String, () -> Unit>> = listOf(
        "S115 쉬기 예고 — 침묵 구간에 밀려 늦게 나가면 남은 분을 그때 값으로(1:57:30 → ‘3분쯤 뒤면’), 제때면 ‘10분쯤 뒤면’ 그대로(11/9 P2, B10)" to ::s115,
        "S116 시계가 앞으로 뜀 — 앱이 알리면(onClockJump) 그 사이는 운전·정차 시간에 넣지 않음(운전 3시간·가짜 쉼 없음) · 알림 없는 큰 간격은 그대로(11/9 P2, B11)" to ::s116,
        "S117 시계가 1시간 뒤로 — 회전 뒤 침묵·내비 끝 간격이 1시간 동안 닫히지 않음 · 보통 회전 뒤 침묵은 그대로(11/9 P2, B23)" to ::s117,
        "S118 회전 예측 — 신호 대기 뒤 출발해도 달린 거리를 적분해 회전 앞 침묵 · 일정 속도면 예전과 같음(11/9 P2, B17)" to ::s118,
        "S119 쉬기 — 시동 신호 없이 쉬지 않고 2시간 30분 달려 목적지에 세우면 못 지킴(목적지 정차는 쉼이 아님) · 주행 중 20분 쉼은 그대로 쉼(11/9 P2, B21)" to ::s119,
        "S120 도착 소식 뒤에도 계속 달리면 도착을 지우고 주행을 이어 감 → 세운 뒤 요약을 말함(11/9 P2, B22)" to ::s120,
        "S121 시간대와 맞지 않는 좌표(마운틴뷰·0,0)로는 해 지기 전을 판단하지 않음 — 말 없음·모름 · 서울은 그대로(11/9 P2, B24)" to ::s121,
        "S122 빗길 과속 문장의 기준 속도는 말하는 순간의 제한속도로 — 밀린 사이 100 → 60 도로가 되면 ‘48 아래로’ · 60 → 100이 되어 과속이 아니면 말 없음(11/9 P2, #21)" to ::s122,
        "S123 지난 ETA(2분 넘음)는 모름 — ‘지금 도착’으로 보아 주행 중 ‘지키는 중’이 되지 않음 · 2분 안은 그대로(11/9 P2, #22)" to ::s123,
        "S124 속도를 모르면 — ‘섰다’로 보지 않음(신호 대기 창 닫힘) · 비 오는 동안 빗길 판정 ‘모름’, 주행 끝도 모름(11/9 P2, #23)" to ::s124,
    )
}

private val Z = ZoneId.of("Asia/Seoul")
private fun kst(d: Int, h: Int, mi: Int) = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, Z).toInstant().toEpochMilli()
private const val LA = 37.5665
private const val LO = 126.9780
private fun fx(t: Long, lat: Double = LA, lon: Double = LO) = GeoFix(t, lat, lon, 15f, accuracyM = 10f)
private fun d(t: Long, kph: Double = 54.0) = VehicleSnapshot(t, gear = Gear.D, speedMps = (kph / 3.6).toFloat())
private fun pk(t: Long) = VehicleSnapshot(t, gear = Gear.P, speedMps = 0f)
private val OPENW = object : QuietWindows {
    override fun normal(nowMs: Long, needMs: Long) = true
    override fun stopWindow(nowMs: Long) = false
}
private class P2Clock(var t: Long) : Clock { override fun nowMs() = t }
private class P2Spk : Speaker { override fun speak(id: Long, text: String, usage: AudioUsage) {}; override fun stop(id: Long) {} }

private fun say(q: SpeechScheduler, w: World, out: MutableList<String>) {
    val s = q.tick(w, true)
    if (s is Step.Speak) { out += "${s.key}|${s.text}"; q.spoken(s.id, w.tMillis) }
}

private fun run(s: TripSession, c: P2Clock, until: Long, out: MutableList<String>, v: (Long) -> VehicleSnapshot?) {
    while (c.t <= until) {
        v(c.t)?.let { s.onVehicle(it) }
        val st = s.tick()
        if (st is Step.Speak) { out += "${st.key}@${c.t}|${st.text}"; s.onSpeechDone(st.id) }
        c.t += 1_000
    }
}

private fun headsUpAt(openAt: Long): String? {
    var open = false
    val q = SpeechScheduler(QuietPolicy(), object : QuietWindows {
        override fun normal(nowMs: Long, needMs: Long) = open
        override fun stopWindow(nowMs: Long) = false
    })
    val r = RestTracker(RestPolicy(), q)
    val said = mutableListOf<String>()
    var t = 0L
    while (t <= 118 * MIN) {
        r.update(t, d(t)); open = t >= openAt
        say(q, World(t, d(t), drivingMs = r.drivingMs), said); t += 1_000
    }
    return said.firstOrNull { it.startsWith("rest.headsup") }
}

private fun s115() {
    val late = headsUpAt(117 * MIN + 30_000)
    ok(late != null && late.contains("3분쯤 뒤면 두 시간이에요"), "1:57:30에 나간 예고: $late")
    val onTime = headsUpAt(0)
    ok(onTime != null && onTime.contains("10분쯤 뒤면 두 시간이에요"), "제때(1:50) 예고: $onTime")
}

private fun s116() {
    // 앱이 단조 시계로 벽시계 점프를 알림(onClockJump) — 그 사이는 운전 시간에 넣지 않음
    val c = P2Clock(kst(14, 9, 0))
    val s = TripSession(listOf(Promise.RestEvery(120)), P2Spk(), c)
    run(s, c, c.t + 60_000, out = mutableListOf(), v = { t -> d(t) })
    val before = s.restState().drivingMs
    c.t += 180 * MIN; s.onClockJump(180 * MIN)   // 부팅 직후 시각 보정으로 3시간 앞으로
    run(s, c, c.t + 10_000, out = mutableListOf(), v = { t -> d(t) })
    ok(s.restState().drivingMs - before <= 12_000, "점프 뒤 운전 시간 증가 ${(s.restState().drivingMs - before) / 1000}초(10초 주행)")
    // 세워 둔 동안 앞으로 뛰어도 쉼으로 치지 않음(정차 시작도 같이 옮김)
    val c2 = P2Clock(kst(14, 9, 0))
    val s2 = TripSession(listOf(Promise.RestEvery(120)), P2Spk(), c2)
    run(s2, c2, c2.t + 30 * MIN, out = mutableListOf(), v = { t -> d(t) })
    run(s2, c2, c2.t + 60_000, out = mutableListOf(), v = { t -> pk(t) })
    c2.t += 180 * MIN; s2.onClockJump(180 * MIN)
    run(s2, c2, c2.t + 60_000, out = mutableListOf(), v = { t -> pk(t) })
    ok(s2.restState().drivingMs >= 29 * MIN, "2분 정차 + 3시간 점프는 쉼이 아님: 운전 ${s2.restState().drivingMs / MIN}분")
    // 알림이 없으면(시뮬레이션의 큰 시간 간격) 예전처럼 셈 — 코덱스 R06 방식 유지
    val r2 = RestTracker(RestPolicy(), SpeechScheduler(QuietPolicy(), OPENW))
    r2.update(0, d(0)); r2.update(91 * MIN, d(91 * MIN))
    eq(r2.drivingMs, 91 * MIN, "알림 없는 91분 간격")
}

private fun s117() {
    val T = kst(14, 10, 0)
    val g = QuietGate()
    g.update(T, d(T, 36.0)); g.onNavi(NaviEvent.Tbt(T, 15, TurnType.RIGHT)); g.update(T + 100, d(T + 100, 36.0))   // 회전점 통과
    ok(g.quiet(T + 3_000), "보통: 회전 뒤 3초는 침묵")
    val back = T + 100 - 60 * MIN + 30_000
    g.update(back, d(back, 36.0))
    ok(!g.quiet(back), "1시간 뒤로 간 시계: 회전 뒤 침묵이 닫혀 있지 않음")
    val a = VoiceArbiter()
    a.onNavPlayers(T, 1); a.onNavPlayers(T + 2_000, 0)
    ok(!a.canStart(T + 2_500), "보통: 내비 끝 뒤 1.5초 전엔 아직")
    ok(a.canStart(back + 30_000), "1시간 뒤로 간 시계: 내비 끝 간격이 닫혀 있지 않음")
}

private fun s118() {
    val g = QuietGate()
    // 신호 대기(D, 0 km/h) 중 ‘150 m 앞 우회전’ → 30초 대기 → 36 km/h로 출발
    g.update(0, d(0, 0.0)); g.onNavi(NaviEvent.Tbt(0, 150, TurnType.RIGHT))
    var t = 100L
    while (t < 30_000) { g.update(t, d(t, 0.0)); t += 100 }
    while (t <= 40_000) { g.update(t, d(t, 36.0)); t += 100 }
    ok(g.quiet(40_000), "출발 10초 뒤(남은 약 50 m, 5초): 회전 앞 침묵 — timeToTurn=${g.timeToTurnMs}")
    // 일정 속도면 예전과 같음: 36 km/h로 300 m 앞 TBT → 10초 뒤 남은 200 m = 20초
    val g2 = QuietGate()
    g2.update(0, d(0, 36.0)); g2.onNavi(NaviEvent.Tbt(0, 300, TurnType.RIGHT))
    var t2 = 100L
    while (t2 <= 10_000) { g2.update(t2, d(t2, 36.0)); t2 += 100 }
    val tt = g2.timeToTurnMs ?: -1
    ok(tt in 19_800..20_200, "일정 속도: 남은 시간 ${tt} ms(20초)")
}

private fun s119() {
    fun trip(v: (Long, Long) -> VehicleSnapshot, until: Long): TripSession.Summary? {
        val c = P2Clock(kst(14, 9, 0))
        var sm: TripSession.Summary? = null
        val s = TripSession(listOf(Promise.RestEvery(120)), P2Spk(), c, listener = object : TripSession.Listener {
            override fun onSummary(summary: TripSession.Summary) { sm = summary }
        })
        val t0 = c.t
        run(s, c, t0 + until, out = mutableListOf(), v = { t -> v(t, t0) })
        return sm
    }
    // ① 쉬지 않고 150분 → 목적지 P(시동 신호 없음) → 30분 뒤 끝
    val a = trip({ t, t0 -> if (t < t0 + 150 * MIN) d(t) else pk(t) }, 181 * MIN)
    ok(a != null && a.reason == "parked_long", "오래 세워 둬서 끝: ${a?.reason}")
    eq(a!!.statuses["rest"], PromiseStatus.MISSED, "목적지 정차는 쉼이 아님")
    ok(a.lines.none { it.contains("쉬셨어요") } && a.lines.any { it.contains("쉬지 않고 운전하셨어요") }, "요약: ${a.lines}")
    // ② 100분 → 20분 쉼(P) → 30분 → 목적지 P → 끝: 주행 중 쉼은 그대로 쉼
    val b = trip({ t, t0 -> if (t < t0 + 100 * MIN) d(t) else if (t < t0 + 120 * MIN) pk(t) else if (t < t0 + 150 * MIN) d(t) else pk(t) }, 181 * MIN)
    eq(b?.statuses?.get("rest"), PromiseStatus.KEPT, "중간에 20분 쉼")
}

private fun s120() {
    val c = P2Clock(kst(14, 10, 0))
    val said = mutableListOf<String>()
    val s = TripSession(listOf(Promise.AvoidHighway()), P2Spk(), c)
    s.onNavi(NaviEvent.SpeedLimit(c.t, 50))
    val t0 = c.t
    // 10분 뒤 내비 ‘도착’(경유지·목적지 옆 통과) — 20분까지 계속 달림 → 세움
    run(s, c, t0 + 20 * MIN, out = said, v = { t -> if (t == t0 + 10 * MIN) s.onNavi(NaviEvent.Arrived(t)); d(t) })
    ok(s.phase == TripSession.Phase.DRIVING, "도착 소식 10분 뒤에도 달리는 중 — 주행 이어 감: ${s.phase}")
    run(s, c, t0 + 52 * MIN, out = said, v = { t -> pk(t) })
    ok(s.phase == TripSession.Phase.ENDED && said.any { it.startsWith("summary.") }, "세운 뒤 끝 + 요약 말함: ${s.summary?.reason} · $said")
    // 회귀 없음: 도착 소식 뒤 바로 P → 끝(arrived)
    val c2 = P2Clock(kst(14, 10, 0))
    val s2 = TripSession(listOf(Promise.AvoidHighway()), P2Spk(), c2)
    run(s2, c2, c2.t + 5 * MIN, out = mutableListOf(), v = { t -> d(t) })
    s2.onNavi(NaviEvent.Arrived(c2.t))
    run(s2, c2, c2.t + 10_000, out = mutableListOf(), v = { t -> pk(t) })
    eq(s2.summary?.reason, "arrived", "도착 뒤 P")
}

private fun s121() {
    val t = kst(14, 11, 0)
    ok(!SolarCalc.plausible(t, 37.4220, -122.0841, Z), "마운틴뷰 + Asia/Seoul")
    ok(!SolarCalc.plausible(t, 0.0, 0.0, Z), "(0,0)")
    ok(SolarCalc.plausible(t, LA, LO, Z) && SolarCalc.plausible(t, 33.5, 126.5, Z), "서울·제주")
    for ((lat, lon) in listOf(37.4220 to -122.0841, 0.0 to 0.0)) {
        val q = SpeechScheduler(QuietPolicy(), OPENW)
        val e = PromiseEngine(listOf(Promise.HomeBeforeDark(30)), q)
        val said = mutableListOf<String>()
        var tt = t
        while (tt <= t + 2 * MIN) { val w = World(tt, d(tt), fx(tt, lat, lon), etaMs = 30 * MIN); e.tick(w); say(q, w, said); tt += 1_000 }
        ok(e.statuses["dark"] == PromiseStatus.UNKNOWN && said.isEmpty(), "($lat,$lon) 11:00 주행(30분 뒤 도착): ${e.statuses["dark"]} · $said")
        eq(e.endTrip(World(tt, pk(tt), fx(tt, lat, lon)))["dark"], PromiseStatus.UNKNOWN, "($lat,$lon) 주행 끝")
    }
    val q = SpeechScheduler(QuietPolicy(), OPENW)
    val e = PromiseEngine(listOf(Promise.HomeBeforeDark(30)), q)
    e.tick(World(t, d(t), fx(t), etaMs = 30 * MIN))
    eq(e.statuses["dark"], PromiseStatus.ON_TRACK, "서울 11:00(30분 뒤 도착)")
}

private fun s122() {
    var open = false
    val q = SpeechScheduler(QuietPolicy(), object : QuietWindows {
        override fun normal(nowMs: Long, needMs: Long) = open
        override fun stopWindow(nowMs: Long) = false
    })
    val e = PromiseEngine(listOf(Promise.RainCare()), q)
    e.onNavi(NaviEvent.SpeedLimit(0, 100))
    val said = mutableListOf<String>()
    var t = 0L
    fun v(t: Long) = VehicleSnapshot(t, gear = Gear.D, speedMps = (95 / 3.6).toFloat(), wiper = app.drivepromise.core.model.Wiper.LOW)
    while (t <= 3 * MIN) {
        if (t == 2 * MIN) e.onNavi(NaviEvent.SpeedLimit(t, 60))   // 과속 문장이 밀린 사이 제한 60 도로로
        open = t >= 2 * MIN + 10_000
        val w = World(t, v(t)); e.tick(w); say(q, w, said); t += 500
    }
    val line = said.firstOrNull { it.startsWith("rain.fast") }
    ok(line != null && line.contains("95예요. 48 아래로"), "말하는 순간 기준(60×0.8=48): $line")
    // 거꾸로: 60 도로에서 70으로 과속 → 밀린 사이 100 도로(기준 80)가 되면 이제 과속이 아니므로 말하지 않음(‘70이에요. 80 아래로’ 같은 말 없음)
    var open2 = false
    val q2 = SpeechScheduler(QuietPolicy(), object : QuietWindows {
        override fun normal(nowMs: Long, needMs: Long) = open2
        override fun stopWindow(nowMs: Long) = false
    })
    val e2 = PromiseEngine(listOf(Promise.RainCare()), q2)
    e2.onNavi(NaviEvent.SpeedLimit(0, 60))
    val said2 = mutableListOf<String>()
    t = 0L
    while (t <= 3 * MIN) {
        if (t == 2 * MIN) e2.onNavi(NaviEvent.SpeedLimit(t, 100))
        open2 = t >= 2 * MIN + 10_000
        val w = World(t, VehicleSnapshot(t, gear = Gear.D, speedMps = (70 / 3.6).toFloat(), wiper = app.drivepromise.core.model.Wiper.LOW)); e2.tick(w); say(q2, w, said2); t += 500
    }
    ok(said2.none { it.startsWith("rain.fast") }, "기준이 80이 되면 70은 과속 아님 — 말 없음: $said2")
}

private fun s123() {
    val t0 = kst(14, 14, 0)
    val q = SpeechScheduler(QuietPolicy(), OPENW)
    val e = PromiseEngine(listOf(Promise.HomeBeforeDark(30)), q)
    e.onNavi(NaviEvent.Trip(t0, t0 + 10 * MIN, 5_000))
    e.tick(World(t0 + 11 * MIN, d(t0 + 11 * MIN), fx(t0 + 11 * MIN)))
    eq(e.statuses["dark"], PromiseStatus.ON_TRACK, "ETA 1분 지남(유예 안)")
    e.tick(World(t0 + 15 * MIN, d(t0 + 15 * MIN), fx(t0 + 15 * MIN)))
    eq(e.statuses["dark"], PromiseStatus.UNKNOWN, "ETA 5분 지남·도착 소식 없음 → 모름")
    val c = P2Clock(t0)
    val s = TripSession(listOf(Promise.RestEvery(120)), P2Spk(), c)
    s.onNavi(NaviEvent.Trip(t0, t0 + 10 * MIN, 5_000))
    eq(s.world(t0 + 11 * MIN).etaMs, 0L, "세션: 1분 지남 → 0")
    eq(s.world(t0 + 15 * MIN).etaMs, null, "세션: 5분 지남 → 모름(쉬기 ‘곧 도착’ 문장에 쓰지 않음)")
}

private fun s124() {
    val u = VehicleSnapshot(0, gear = Gear.D, speedMps = null)
    ok(!u.stopped && u.driving, "기어 D·속도 모름: 섰다고 보지 않음")
    val g = QuietGate()
    var t = 0L
    while (t <= 5_000) { g.update(t, VehicleSnapshot(t, gear = Gear.D, speedMps = null)); t += 100 }
    ok(!g.stopWindow(3_000), "속도 모름: 신호 대기 창 닫힘")
    val g2 = QuietGate()
    g2.update(0, VehicleSnapshot(0, gear = Gear.D, speedMps = 0f))
    ok(g2.stopWindow(1_000), "속도 0(알고 있음): 신호 대기 창 그대로")
    val q = SpeechScheduler(QuietPolicy(), OPENW)
    val e = PromiseEngine(listOf(Promise.RainCare()), q)
    val said = mutableListOf<String>()
    t = 0L
    while (t <= 3 * MIN) { val w = World(t, VehicleSnapshot(t, gear = Gear.D, speedMps = null, wiper = app.drivepromise.core.model.Wiper.LOW)); e.tick(w); say(q, w, said); t += 1_000 }
    eq(e.statuses["rain"], PromiseStatus.UNKNOWN, "비 오는 중 속도 모름")
    eq(e.endTrip(World(t, VehicleSnapshot(t, gear = Gear.P, speedMps = null, wiper = app.drivepromise.core.model.Wiper.LOW)))["rain"], PromiseStatus.UNKNOWN, "주행 끝 — 속도를 몰랐으면 모름")
    ok(said.none { it.startsWith("rain.fast") }, "과속 문장 없음: $said")
}
