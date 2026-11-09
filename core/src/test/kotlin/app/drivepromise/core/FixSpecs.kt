package app.drivepromise.core

import app.drivepromise.core.engine.PromiseEngine
import app.drivepromise.core.model.Gear
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.model.Turn
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.model.Wiper
import app.drivepromise.core.plan.PreTripPlanner
import app.drivepromise.core.plan.SolarCalc
import app.drivepromise.core.rest.RestTracker
import app.drivepromise.core.scene.Josa
import app.drivepromise.core.scene.PromisePhrases
import app.drivepromise.core.scene.SummaryPhrases
import app.drivepromise.core.session.TripSession
import app.drivepromise.core.source.Clock
import app.drivepromise.core.source.RouteOption
import app.drivepromise.core.source.RoutePlan
import app.drivepromise.core.source.RoutePlans
import app.drivepromise.core.speech.AudioUsage
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.speech.QuietPolicy
import app.drivepromise.core.speech.QuietWindows
import app.drivepromise.core.speech.Speaker
import app.drivepromise.core.speech.SpeechScheduler
import app.drivepromise.core.speech.Step
import app.drivepromise.core.speech.World
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.3.1 — 11/9 종합 점검(261109_종합점검_v0.1)에서 프로브로 재현한 결함을 고친 뒤 정식 스펙으로 승격.
 * S101~S106 = P0 코어 6건. (프로브 원본: 종합점검 _src/evidence/ProbeB.kt — B01·B08·B25·B15·B12·B19)
 */
object FixSpecs {
    val all: List<Pair<String, () -> Unit>> = listOf(
        "S101 비 — 제한속도 0(이 도로 정보 없음)은 기본 제한(60)으로: 30 km/h는 말 없음·지킴, 55 km/h는 ‘48 아래로’(11/9 P0-1)" to ::s101,
        "S102 해 지기 전 — 새 세션은 복원된 운전 시간만으로 ‘움직임’이 아님: 세워 둔 밤은 위험, 이 세션에서 달리면 못 지킴(11/9 P0-2, S100 회귀)" to ::s102,
        "S103 고속도로 — 제한속도 정보가 0으로 깜박여도 ‘들어섰어요’는 한 주행에 한 번, 상태는 못 지킴 유지(11/9 P0-3)" to ::s103,
        "S104 세운 뒤 비상등 — 주행 끝 요약은 말함 · 주행 중 방향지시등 침묵은 그대로(11/9 P0-4)" to ::s104,
        "S105 쉬기 이름 — 90분은 ‘1시간 30분마다 쉬기’·‘90 minutes’·계획 ‘1시간 30분 넘게’, 120분은 그대로 ‘2시간마다 쉬기’(11/9 P0-5)" to ::s105,
        "S106 서술격 조사 — 60이에요·95예요·100이에요·방면이에요·IC예요(11/9 P0-6)" to ::s106,
    )
}

private val Z = ZoneId.of("Asia/Seoul")
private fun kst(d: Int, h: Int, mi: Int) = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, Z).toInstant().toEpochMilli()
private const val LA = 37.5665
private const val LO = 126.9780
private fun fx(t: Long) = GeoFix(t, LA, LO, 15f, accuracyM = 10f)
private fun d(t: Long, kph: Double = 54.0, wiper: Wiper = Wiper.UNKNOWN, turn: Turn = Turn.OFF) =
    VehicleSnapshot(t, gear = Gear.D, speedMps = (kph / 3.6).toFloat(), wiper = wiper, turnSignal = turn)
private fun pk(t: Long, turn: Turn = Turn.OFF) = VehicleSnapshot(t, gear = Gear.P, speedMps = 0f, turnSignal = turn)
private val OPENW = object : QuietWindows {
    override fun normal(nowMs: Long, needMs: Long) = true
    override fun stopWindow(nowMs: Long) = false
}
private class FClock(var t: Long) : Clock { override fun nowMs() = t }
private class FSpk : Speaker { override fun speak(id: Long, text: String, usage: AudioUsage) {}; override fun stop(id: Long) {} }

private fun say(q: SpeechScheduler, w: World, out: MutableList<String>) {
    val s = q.tick(w, true)
    if (s is Step.Speak) { out += "${s.key}|${s.text}"; q.spoken(s.id, w.tMillis) }
}

/** 세션을 1 Hz로 — 말은 바로 끝난 것으로 */
private fun run(s: TripSession, c: FClock, until: Long, out: MutableList<String>, v: (Long) -> VehicleSnapshot?, fix: ((Long) -> GeoFix?)? = null) {
    while (c.t <= until) {
        v(c.t)?.let { s.onVehicle(it) }
        fix?.invoke(c.t)?.let { s.onFix(it) }
        val st = s.tick()
        if (st is Step.Speak) { out += "${st.key}@${c.t}|${st.text}"; s.onSpeechDone(st.id) }
        c.t += 1_000
    }
}

private fun rainRun(kph: Double): Pair<List<String>, PromiseStatus?> {
    val q = SpeechScheduler(QuietPolicy(), OPENW)
    val e = PromiseEngine(listOf(Promise.RainCare()), q)
    e.onNavi(NaviEvent.SpeedLimit(0, 60))
    e.onNavi(NaviEvent.SpeedLimit(500, 0))   // 어댑터: 값이 있던 도로 뒤 ‘정보 없음’ → 0
    val said = mutableListOf<String>()
    var t = 1_000L
    while (t <= 3 * MIN) { val w = World(t, d(t, kph, Wiper.LOW)); e.tick(w); say(q, w, said); t += 100 }
    return said to e.endTrip(World(t, d(t, kph, Wiper.LOW)))["rain"]
}

private fun s101() {
    val (slow, slowFin) = rainRun(30.0)
    ok(slow.none { it.startsWith("rain.fast") } && slowFin == PromiseStatus.KEPT, "30 km/h(기본 60 → 기준 48): said=$slow · 끝=$slowFin")
    val (fast, _) = rainRun(55.0)
    val line = fast.firstOrNull { it.startsWith("rain.fast") }
    ok(line != null && line.contains("48 아래로") && !line.contains(" 0 아래로"), "55 km/h: 기본 제한 기준 ‘48 아래로’ — $fast")
}

private fun s102() {
    val ss = SolarCalc.at(kst(14, 12, 0), LA, LO, Z).sunsetMs!!
    // ① 앞 주행이 끝난 뒤 새 세션(쉬기 상태 40분 복원) — 세워 둔 채 해가 짐 → 위험
    val c = FClock(ss - 30 * MIN)
    val s = TripSession(listOf(Promise.HomeBeforeDark(30)), FSpk(), c)
    s.restoreRest(RestTracker.State(40 * MIN, c.t - MIN, headsUpDone = false, mainDone = false, parkedAtSave = true))
    val said = mutableListOf<String>()
    run(s, c, ss + 2 * MIN, out = said, v = { t -> pk(t) }, fix = { t -> fx(t) })
    ok(s.statuses["dark"] == PromiseStatus.AT_RISK, "복원만 된 새 세션 · 해 진 뒤 상태=${s.statuses["dark"]}(위험이어야)")
    ok(said.none { it.startsWith("dark.") }, "세워 둔 채로는 말 없음: $said")
    // ② 같은 세션에서 해 진 뒤 출발 → 못 지킴
    run(s, c, c.t + 3 * MIN, out = said, v = { t -> d(t) }, fix = { t -> fx(t) })
    ok(s.statuses["dark"] == PromiseStatus.MISSED, "해 진 뒤 달리면 못 지킴: ${s.statuses["dark"]}")
}

private fun s103() {
    val q = SpeechScheduler(QuietPolicy(), OPENW)
    val e = PromiseEngine(listOf(Promise.AvoidHighway()), q)
    val said = mutableListOf<String>()
    var t = 0L
    while (t <= 20 * MIN) {
        val sec = (t / 1000) % 180
        if (t % 1000 == 0L && sec == 0L) e.onNavi(NaviEvent.SpeedLimit(t, 100))
        if (t % 1000 == 0L && sec == 150L) e.onNavi(NaviEvent.SpeedLimit(t, 0))
        val w = World(t, d(t, 95.0)); e.tick(w); say(q, w, said); t += 500
    }
    val n = said.count { it.startsWith("highway.on") }
    ok(n == 1, "고속도로 한 번 · 제한속도 정보만 끊김: ‘들어섰어요’ ${n}번 — $said")
    ok(e.endTrip(World(t, d(t, 95.0)))["highway"] == PromiseStatus.MISSED, "주행 끝 고속도로 = 못 지킴")
}

private fun s104() {
    val c = FClock(kst(14, 10, 0))
    val said = mutableListOf<String>()
    val s = TripSession(listOf(Promise.RestEvery(120), Promise.AvoidHighway()), FSpk(), c)
    s.onNavi(NaviEvent.SpeedLimit(c.t, 50))
    run(s, c, c.t + 10 * MIN, out = said, v = { t -> d(t) })
    s.onNavi(NaviEvent.Arrived(c.t))
    run(s, c, c.t + 2 * MIN, out = said, v = { t -> pk(t, Turn.HAZARD) })
    ok(s.phase == TripSession.Phase.ENDED, "도착 뒤 P → 끝")
    ok(said.any { it.startsWith("summary.") }, "비상등을 켜고 세워도 요약을 말함: said=$said · digest=${s.scheduler.digest.map { it.key }}")
    // 주행 중 방향지시등 침묵은 그대로
    val g = app.drivepromise.core.gate.QuietGate()
    g.update(10_000, d(10_000, turn = Turn.LEFT))
    ok(g.quiet(10_000), "주행 중 방향지시등이면 여전히 침묵")
    val g2 = app.drivepromise.core.gate.QuietGate()   // 회전 뒤 침묵이 섞이지 않게 새 게이트
    g2.update(11_000, pk(11_000, Turn.HAZARD))
    ok(!g2.quiet(11_000), "P에서 비상등이면 침묵 아님")
}

private fun s105() {
    val ko90 = SummaryPhrases.name(Lang.KO, Promise.RestEvery(90))
    val en90 = SummaryPhrases.name(Lang.EN, Promise.RestEvery(90))
    eq(ko90, "1시간 30분마다 쉬기", "90분 이름(ko)")
    eq(en90, "rest every 90 minutes", "90분 이름(en)")
    eq(SummaryPhrases.name(Lang.KO, Promise.RestEvery(120)), "2시간마다 쉬기", "120분은 그대로 — 시연 요약 문장(S72)과 같음")
    eq(SummaryPhrases.name(Lang.EN, Promise.RestEvery(120)), "rest every 2 hours", "120분(en)")
    eq(SummaryPhrases.name(Lang.EN, Promise.RestEvery(60)), "rest every hour", "60분(en)")
    val p = PreTripPlanner.plan(PreTripPlanner.Input(kst(14, 9, 0), LA, LO, RoutePlans(mapOf(RouteOption.RECOMMENDED to RoutePlan(RouteOption.RECOMMENDED, 100_000, 100 * 60))),
        listOf(Promise.RestEvery(90)), Z, Lang.KO))
    ok(p.lines.any { it.startsWith("1시간 30분 넘게 걸려요") }, "계획: ${p.lines}")
}

private fun s106() {
    eq(PromisePhrases.rainFast(Lang.KO, 60, 48, false), "빗길에 60이에요. 48 아래로 줄여요.", "60 → 이에요")
    eq(PromisePhrases.rainFast(Lang.KO, 95, 80, false), "빗길에 95예요. 80 아래로 줄여요.", "95 → 예요(시연 문장 그대로)")
    eq(Josa.copula("100"), "이에요", "100(백)")
    eq(Josa.copula("72"), "예요", "72(칠십이)")
    eq(Josa.copula("판교IC 방면"), "이에요", "방면")
    eq(Josa.copula("판교IC"), "예요", "IC(아이씨)")
    eq(Josa.copula("서울TG"), "예요", "TG(티지)")
}
