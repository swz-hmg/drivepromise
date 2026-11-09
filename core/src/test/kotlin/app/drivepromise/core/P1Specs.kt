package app.drivepromise.core

import app.drivepromise.core.engine.PromiseEngine
import app.drivepromise.core.model.Gear
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.model.Turn
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.plan.PreTripPlanner
import app.drivepromise.core.plan.SolarCalc
import app.drivepromise.core.privacy.LogMask
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
import app.drivepromise.core.speech.VoiceArbiter
import app.drivepromise.core.speech.World
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.3.2 — 11/9 종합 점검 P1(261109_종합점검_v0.1 · 프로브 ProbeB B02~B04·B07·B09·B13·B14·B16·B20)을 고친 뒤 정식 스펙으로.
 * S107~S113 = P1 코어 7건 · S114 = 내비 기록 가리기(1.3.1 run_1109_110640에서 발견, 앱에서 코어로 옮김)
 */
object P1Specs {
    val all: List<Pair<String, () -> Unit>> = listOf(
        "S107 해 지기 전 — 해 뜨기 전(새벽 1시) 주행은 못 지킴 · 자정을 넘긴 밤 주행도 주행 끝에 못 지킴(11/9 P1-1, B02·B03)" to ::s107,
        "S108 출발 전 계획 — 해 뜨기 전 출발은 ‘해 지기 전이에요’가 아니라 ‘해 뜨기 전이에요(일출 …)’(11/9 P1-1, B04)" to ::s108,
        "S109 해 지기 전 — 판정 시각은 도착 ?: 마지막으로 달린 시각: 일몰 전 세우고 세션이 늦게 끝나도 지킴 · 일몰 전 도착 소식 뒤 주차가 일몰 뒤여도 지킴(11/9 P1-2, B13·B14)" to ::s109,
        "S110 밤 문장 — 일몰 순간 잠깐 P면 ‘위험’으로 두고, 다시 달릴 때 못 지킴 + ‘전조등 켜고’ 문장(11/9 P1-3, B20)" to ::s110,
        "S111 약속 구역 — 튄 위치 1건으로는 밖이 아님(말 없음·지킴) · 실제로 나가면 2번·5초 확인 뒤 ‘밖이에요’·‘돌아왔어요’(11/9 P1-4, B07)" to ::s111,
        "S112 쉬기 — 본 알림 시각을 넘긴 채 시동을 껐다 쉬지 않고 다시 달리면 본 알림을 다시 말함(11/9 P1-5, B09)" to ::s112,
        "S113 내비 재생기 — 우리가 말하지 않아 포커스 소식이 없어도 목록이 10초 그대로면 끝난 것으로 · 다시 늘면 물러섬(11/9 P1-6, B16)" to ::s113,
        "S114 내비 기록 — 개인 장소 콜백은 개수만(대소문자 무시) · 그 밖의 콜백도 이름·주소·장소 ID는 글자 수만, 좌표는 소수 3자리(1.3.1 LogMask → 코어)" to ::s114,
    )
}

private val Z = ZoneId.of("Asia/Seoul")
private fun kst(d: Int, h: Int, mi: Int) = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, Z).toInstant().toEpochMilli()
private const val LA = 37.5665
private const val LO = 126.9780
private fun fx(t: Long, lat: Double = LA, lon: Double = LO, acc: Float = 10f) = GeoFix(t, lat, lon, 15f, accuracyM = acc)
private fun d(t: Long, kph: Double = 54.0, turn: Turn = Turn.OFF) = VehicleSnapshot(t, gear = Gear.D, speedMps = (kph / 3.6).toFloat(), turnSignal = turn)
private fun pk(t: Long) = VehicleSnapshot(t, gear = Gear.P, speedMps = 0f)
private val OPENW = object : QuietWindows {
    override fun normal(nowMs: Long, needMs: Long) = true
    override fun stopWindow(nowMs: Long) = false
}
private class PClock(var t: Long) : Clock { override fun nowMs() = t }
private class Spk : Speaker { override fun speak(id: Long, text: String, usage: AudioUsage) {}; override fun stop(id: Long) {} }
private fun sunset14() = SolarCalc.at(kst(14, 12, 0), LA, LO, Z).sunsetMs!!

private fun say(q: SpeechScheduler, w: World, out: MutableList<String>) {
    val s = q.tick(w, true)
    if (s is Step.Speak) { out += "${s.key}|${s.text}"; q.spoken(s.id, w.tMillis) }
}

private fun run(s: TripSession, c: PClock, until: Long, out: MutableList<String>, v: (Long) -> VehicleSnapshot?, fix: ((Long) -> GeoFix?)? = null) {
    while (c.t <= until) {
        v(c.t)?.let { s.onVehicle(it) }
        fix?.invoke(c.t)?.let { s.onFix(it) }
        val st = s.tick()
        if (st is Step.Speak) { out += "${st.key}@${c.t}|${st.text}"; s.onSpeechDone(st.id) }
        c.t += 1_000
    }
}

private fun s107() {
    // ① 11/15 01:00 달리는 중(도착 예상 01:30) — 그날 일몰(17시)보다 이르지만 해 뜨기 전 = 어두움
    val q = SpeechScheduler(QuietPolicy(), OPENW)
    val e = PromiseEngine(listOf(Promise.HomeBeforeDark(30)), q)
    val t = kst(15, 1, 0)
    e.tick(World(t, d(t), fx(t), etaMs = 30 * MIN))
    eq(e.statuses["dark"], PromiseStatus.MISSED, "새벽 1시 주행")
    // ② 17:00 출발 → 00:30까지 달림 → 세움: 날짜가 바뀌어 ‘다음 날 일몰’ 전이 되어도 못 지킴
    val q2 = SpeechScheduler(QuietPolicy(), OPENW)
    val e2 = PromiseEngine(listOf(Promise.HomeBeforeDark(30)), q2)
    var t2 = kst(14, 17, 0)
    val seen = linkedSetOf<String>()
    while (t2 <= kst(15, 0, 30)) { val w = World(t2, d(t2), fx(t2)); e2.tick(w); seen += e2.statuses["dark"]!!.name; say(q2, w, mutableListOf()); t2 += 60_000 }
    ok(seen.last() == "MISSED", "자정 넘긴 뒤에도 못 지킴 유지: $seen")
    // 어두울 때 달린 뒤 세우면 ‘위험’으로 돌아가지 않고 못 지킴 그대로(세워 둔 채 해가 진 경우와 구별)
    for (k in 0 until 3) { val tp = t2 + k * 60_000L; e2.tick(World(tp, pk(tp), fx(tp))) }
    eq(e2.statuses["dark"], PromiseStatus.MISSED, "밤에 달린 뒤 세움")
    eq(e2.endTrip(World(t2, pk(t2), fx(t2)))["dark"], PromiseStatus.MISSED, "00:31 주행 끝")
    // ③ 낮 주행은 그대로(회귀 없음): 11/14 10:00 출발, 10:30 도착 예상 → 지키는 중
    val q3 = SpeechScheduler(QuietPolicy(), OPENW)
    val e3 = PromiseEngine(listOf(Promise.HomeBeforeDark(30)), q3)
    val t3 = kst(14, 10, 0)
    e3.tick(World(t3, d(t3), fx(t3), etaMs = 30 * MIN))
    eq(e3.statuses["dark"], PromiseStatus.ON_TRACK, "낮 주행")
}

private fun s108() {
    fun plan(t: Long, lang: Lang) = PreTripPlanner.plan(PreTripPlanner.Input(t, LA, LO, RoutePlans(mapOf(RouteOption.RECOMMENDED to RoutePlan(RouteOption.RECOMMENDED, 20_000, 1_800))),
        listOf(Promise.HomeBeforeDark(30)), Z, lang))
    val ko = plan(kst(15, 1, 0), Lang.KO)
    ok(ko.lines.none { it.contains("해 지기 전이에요") } && ko.lines.any { it.startsWith("지금은 해 뜨기 전이에요(일출 오전 7시") }, "새벽 1시 계획: ${ko.lines}")
    eq(ko.candidates.first().darkStatus, PromiseStatus.MISSED, "후보 판정")
    val en = plan(kst(15, 1, 0), Lang.EN)
    ok(en.lines.any { it.startsWith("It is before sunrise (7:") }, "영어: ${en.lines}")
    // 낮 계획은 그대로(S77류 회귀 없음)
    ok(plan(kst(14, 9, 0), Lang.KO).lines.any { it.endsWith("약속대로 해 지기 전이에요.") }, "아침 9시 계획은 그대로")
}

private fun s109() {
    val ss = sunset14()
    // ① 도착 소식 없음·시동 신호 없음: 30분 달리고 일몰 20분 전 P → 30분 정차로 끝(끝 시각은 일몰 뒤)
    val c = PClock(ss - 50 * MIN)
    var sm: TripSession.Summary? = null
    val s = TripSession(listOf(Promise.HomeBeforeDark(30)), Spk(), c, listener = object : TripSession.Listener {
        override fun onSummary(summary: TripSession.Summary) { sm = summary }
    })
    run(s, c, ss + 20 * MIN, out = mutableListOf(), v = { t -> if (t < ss - 20 * MIN) d(t) else pk(t) }, fix = { t -> fx(t) })
    ok(sm != null && sm!!.endMs > ss, "정차 30분으로 일몰 뒤에 끝남: ${sm?.reason}")
    eq(sm!!.statuses["dark"], PromiseStatus.KEPT, "일몰 20분 전 세움")
    // ② 일몰 3분 전 도착 소식 → 주차 자리를 찾아 일몰 1분 뒤 P
    val c2 = PClock(ss - 40 * MIN)
    var sm2: TripSession.Summary? = null
    val s2 = TripSession(listOf(Promise.HomeBeforeDark(30)), Spk(), c2, listener = object : TripSession.Listener {
        override fun onSummary(summary: TripSession.Summary) { sm2 = summary }
    })
    s2.onNavi(NaviEvent.Trip(c2.t, ss - 10 * MIN, 5_000))
    val arrT = ss - 3 * MIN
    run(s2, c2, ss + 3 * MIN, out = mutableListOf(), v = { t ->
        if (t == arrT) s2.onNavi(NaviEvent.Arrived(t))
        if (t < ss + MIN) d(t, 15.0) else pk(t)
    }, fix = { t -> fx(t) })
    eq(sm2?.statuses?.get("dark"), PromiseStatus.KEPT, "일몰 3분 전 도착 소식(${sm2?.reason})")
    // ③ 회귀 없음: 일몰 10분 뒤까지 달리고 세우면 못 지킴
    val c3 = PClock(ss - 30 * MIN)
    var sm3: TripSession.Summary? = null
    val s3 = TripSession(listOf(Promise.HomeBeforeDark(30)), Spk(), c3, listener = object : TripSession.Listener {
        override fun onSummary(summary: TripSession.Summary) { sm3 = summary }
    })
    run(s3, c3, ss + 45 * MIN, out = mutableListOf(), v = { t -> if (t < ss + 10 * MIN) d(t) else pk(t) }, fix = { t -> fx(t) })
    eq(sm3?.statuses?.get("dark"), PromiseStatus.MISSED, "일몰 10분 뒤 세움")
}

private fun s110() {
    val ss = sunset14()
    val q = SpeechScheduler(QuietPolicy(), OPENW)
    val e = PromiseEngine(listOf(Promise.HomeBeforeDark(30)), q)
    val said = mutableListOf<String>()
    var t = ss - 30 * MIN
    var parkedAfterSunset: PromiseStatus? = null
    // 일몰 7분 전~8분 뒤: 편의점 앞 P → 다시 출발해 어두운 길 30분
    while (t <= ss + 40 * MIN) {
        val v = if (t in (ss - 7 * MIN)..(ss + 8 * MIN)) pk(t) else d(t)
        val w = World(t, v, fx(t)); e.tick(w); say(q, w, said)
        if (t == ss + 5 * MIN) parkedAfterSunset = e.statuses["dark"]
        t += 1_000
    }
    eq(parkedAfterSunset, PromiseStatus.AT_RISK, "해가 진 뒤 세워 둔 동안(아직 다시 달리지 않음)")
    eq(e.statuses["dark"], PromiseStatus.MISSED, "다시 달린 뒤")
    ok(said.count { it.startsWith("dark.night") } == 1, "다시 달릴 때 밤 문장 한 번: $said")
}

private fun s111() {
    // ① 반지름 3 km 구역 중심에 머묾 · 30초에 한 번 정확도 1.5 km 튄 위치(약 5.5 km 북쪽)
    val q = SpeechScheduler(QuietPolicy(), OPENW)
    val e = PromiseEngine(listOf(Promise.StayInZone(LA, LO, 3_000)), q)
    val said = mutableListOf<String>()
    var t = 0L
    while (t <= 60_000) {
        val f = if (t == 30_000L) fx(t, LA + 0.05, LO, acc = 1_500f) else fx(t)
        val w = World(t, d(t, 30.0), f); e.tick(w); say(q, w, said); t += 1_000
    }
    ok(said.isEmpty(), "튄 위치 1건에 말 없음: $said")
    eq(e.endTrip(World(t, pk(t), fx(t)))["zone"], PromiseStatus.KEPT, "주행 끝")
    // ② 실제로 나감: 1초마다 위치, 3.2 km에서 20초 → 다시 안으로 — ‘밖이에요’·‘돌아왔어요’ 한 번씩, 끝은 못 지킴
    val q2 = SpeechScheduler(QuietPolicy(), OPENW)
    val e2 = PromiseEngine(listOf(Promise.StayInZone(LA, LO, 3_000)), q2)
    val said2 = mutableListOf<String>()
    val dLat = 3_200.0 / 111_195.0
    var outAt: Long? = null
    t = 0L
    while (t <= 60_000) {
        val f = if (t in 20_000L..39_000L) fx(t, LA + dLat, LO) else fx(t)
        val w = World(t, d(t, 30.0), f); e2.tick(w); say(q2, w, said2)
        if (outAt == null && said2.any { it.startsWith("zone.out") }) outAt = t
        t += 1_000
    }
    ok(said2.count { it.startsWith("zone.out") } == 1 && said2.count { it.startsWith("zone.back") } == 1, "나갔다 돌아옴: $said2")
    ok(outAt != null && outAt!! >= 25_000 && outAt!! <= 26_000, "밖 확인은 첫 밖 위치(20초) 뒤 5초: ${outAt}")
    eq(e2.endTrip(World(t, pk(t), fx(t)))["zone"], PromiseStatus.MISSED, "주행 끝")
}

private fun s112() {
    val c = PClock(0)
    val said1 = mutableListOf<String>()
    val s1 = TripSession(listOf(Promise.RestEvery(120)), Spk(), c)
    // 1:59:50~2:00:30 방향지시등(침묵 구간) → 본 알림 대기 → 2:00:31 시동 끔
    run(s1, c, 120 * MIN + 30_000, out = said1, v = { t -> if (t >= 120 * MIN - 10_000) d(t, turn = Turn.LEFT) else d(t) })
    s1.onVehicle(VehicleSnapshot(c.t, gear = Gear.P, speedMps = 0f, ignitionOn = false)); s1.tick()
    eq(s1.phase, TripSession.Phase.ENDED, "시동 끔 → 주행 끝")
    val st = s1.restState()
    c.t += 5 * MIN   // 5분 뒤(쉼 15분 미만) 다시 시동
    val said2 = mutableListOf<String>()
    val s2 = TripSession(listOf(Promise.RestEvery(120)), Spk(), c)
    s2.restoreRest(st)
    run(s2, c, c.t + 40 * MIN, out = said2, v = { t -> d(t) })
    val main2 = said2.filter { it.startsWith("rest.main") }
    ok(main2.size >= 2, "다시 달린 세션에서 본 알림 + 15분 반복: $main2")
    // 회귀 없음: 15분 넘게 쉬고 다시 출발하면 본 알림 없음(쉼 인정)
    val c3 = PClock(0)
    val s3 = TripSession(listOf(Promise.RestEvery(120)), Spk(), c3)
    run(s3, c3, 121 * MIN, out = mutableListOf(), v = { t -> d(t) })
    s3.onVehicle(VehicleSnapshot(c3.t, gear = Gear.P, speedMps = 0f, ignitionOn = false)); s3.tick()
    val st3 = s3.restState()
    c3.t += 20 * MIN
    val said3 = mutableListOf<String>()
    val s4 = TripSession(listOf(Promise.RestEvery(120)), Spk(), c3)
    s4.restoreRest(st3)
    run(s4, c3, c3.t + 10 * MIN, out = said3, v = { t -> d(t) })
    ok(said3.none { it.startsWith("rest.main") }, "20분 쉬고 다시: $said3")
}

private fun s113() {
    val a = VoiceArbiter()
    // 우리가 말하지 않는 동안 내비 안내(효과음 + 말) → 재생기 하나가 목록에 남음(11/8 실측 26초) — 포커스 소식 없음
    a.onNavPlayers(0, 1); a.onNavPlayers(300, 2); a.onNavPlayers(2_600, 1)
    ok(!a.canStart(12_599), "10초 전에는 아직(진짜 안내일 수 있음)")
    ok(a.canStart(12_600), "10초 동안 목록이 그대로면 끝난 것으로")
    a.onNavPlayers(20_000, 2)
    ok(!a.canStart(20_100), "다시 늘면 시작 금지")
    // 포커스를 잃은 채(GAIN 없음)면 그대로 기다림(S99 c)
    val b = VoiceArbiter()
    b.onFocus(0, VoiceArbiter.Focus.LOSS_TRANSIENT); b.onNavPlayers(100, 1)
    ok(!b.canStart(60_000), "포커스 잃은 채 재생기 남음 — 시작 금지")
}

private fun s114() {
    val rs = "RouteStartInfo(duration=660, distance=3100, tollFare=0, name=판교역, remainDestinationChargingValue=77011)"
    eq(LogMask.navi("onRouteStarted", null, rs), "RouteStartInfo(duration=660, distance=3100, tollFare=0, name=#3, remainDestinationChargingValue=77011)", "경로 시작")
    val di = "DrivingInfo(destination=DrivingState(name=스타벅스, 강남점, duration=660, distance=3100), waypoints=[DrivingState(name=우리 집, duration=10, distance=5)])"
    eq(LogMask.navi("onDrivingInfo", null, di), "DrivingInfo(destination=DrivingState(name=#9, duration=660, distance=3100), waypoints=[DrivingState(name=#4, duration=10, distance=5)])", "주행 정보(쉼표 든 이름·경유지)")
    eq(LogMask.navi("onWaypointChanged", Any(), "WaypointChangedInfo(name=병원)"), "redacted n=1", "경유지 바뀜(소문자 p)")
    eq(LogMask.navi("onBookmarkInfo", listOf(1, 2, 3), "x"), "redacted n=3", "즐겨찾기 개수")
    eq(LogMask.navi("onCautionPointInfo", null, "[CautionPoint(latitude=37.4012345, longitude=127.1098765, poiName=OO초등학교 앞, poiId=12345, type=SCHOOL)]"),
        "[CautionPoint(latitude=37.401, longitude=127.110, poiName=#8, poiId=#5, type=SCHOOL)]", "좌표 3자리·장소 이름")
    val tbt = "[TBTInfo(type=TURN_RIGHT, distance=380.0, description=판교IC 방면)]"
    eq(LogMask.navi("onTBTInfo", null, tbt), tbt, "TBT 방면(공공 도로 이름)은 그대로")
    eq(LogMask.fields("roadName=경부고속도로, name=집"), "roadName=경부고속도로, name=#1", "roadName은 이름 필드 아님")
}
