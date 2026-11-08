package app.drivepromise.core

import app.drivepromise.core.engine.PromiseEngine
import app.drivepromise.core.gate.QuietGate
import app.drivepromise.core.ledger.MemoryLedger
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.Gear
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.model.Turn
import app.drivepromise.core.model.TurnType
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.model.Wiper
import app.drivepromise.core.plan.SolarCalc
import app.drivepromise.core.rest.RestTracker
import app.drivepromise.core.scene.PromisePhrases
import app.drivepromise.core.scene.SummaryPhrases
import app.drivepromise.core.scene.VoicePhrases
import app.drivepromise.core.session.TripSession
import app.drivepromise.core.source.Clock
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.speech.Priority
import app.drivepromise.core.speech.QuietPolicy
import app.drivepromise.core.speech.RestPolicy
import app.drivepromise.core.speech.SpeechScheduler
import app.drivepromise.core.speech.Step
import app.drivepromise.core.speech.Utterance
import app.drivepromise.core.speech.Validity
import app.drivepromise.core.speech.VoiceArbiter
import app.drivepromise.core.speech.World
import app.drivepromise.core.voice.VoiceCommands
import app.drivepromise.core.voice.VoiceIntent
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * 1.3.0 검토 반영 스펙(S84~S97) — 11/7 외부 검토 2건(codex·antigravity)을 판정한 261107_외부검토평가_v0.1_claude 의 통합 항목(C01~C14·ADV-01).
 * codex 진단 R01~R15 중 채택한 것의 ‘바라는 동작’을 그대로 옮기고, 이번 검토에서 찾은 것(급제동 직후 창·‘그래 그만 좀 말해’ 등)을 더했습니다.
 */
object ReviewSpecs {
    val all: List<Pair<String, () -> Unit>> = listOf(
        "S84 일몰 — 위험이 풀려 지워질 땐 낮에 밤 문장 없음, 실제로 해가 진 뒤엔 한 번(C01·R04)" to ::s84,
        "S85 일몰 문장 — ‘쉬지 말고’ 없음, 짧은 문장은 도착이 일몰 뒤일 때만 ‘해 진 뒤’(C02·R10)" to ::s85,
        "S86 모르면 판단 보류 — 도로·와이퍼 신호가 없으면 주행 끝도 ‘모름’, 받으면 지킴, 5분 넘은 위치는 구역 ‘모름’, 요약은 이름으로(C03·R02·R03·R09)" to ::s86,
        "S87 신호 대기 창 — 방향지시등·급제동 직후엔 닫힘, 서서히 서면 열림 · 좌회전 대기 중 약속 말 없음(C04·R12)" to ::s87,
        "S88 말 길이 — 선언값과 글자 수 길이(한 165 ms/자 + 300) 중 큰 쪽으로 창을 봄(C05)" to ::s88,
        "S89 음성 문법 — 조사·부사 허용, ‘약속대로·약속 시간’은 아님, 한국어 설정에서 영어 문법 끔(C08·R13)" to ::s89,
        "S90 구역 끝 예고 — 정차 중 GPS 흔들림(σ3 m)엔 0번, 실제로 경계 쪽으로 가면 1번(C09)" to ::s90,
        "S91 ‘약속 알려 줘’ — 위험한 약속과 판단 전 약속이 섞여도 판단 전 약속을 말함(한·영)(C10)" to ::s91,
        "S92 경로 취소·도착 — 도로 정보를 비워 고속도로 상태가 남지 않음(C11·R14)" to ::s92,
        "S93 쉬기 설정 연결 — 쉬기 약속이 없으면 쉬기 말 없음, 90분이면 1:20 예고·1:30 본 알림(C12·R05·R06)" to ::s93,
        "S94 넘김 이력·앱 부재 — 넘긴 뒤 쉬면 지킴(Q027) + 요약에 넘긴 사실, 앱이 없던 시간은 근거가 있을 때만 쉼(C13·R08)" to ::s94,
        "S95 감시 타이머 — 완료 소식이 없으면 멈추고 2초 뒤 다시, 3번이면 요약, 다른 말은 막히지 않음(C14·R11)" to ::s95,
        "S96 다음 주행 — 끝난 뒤 할 말을 다 하면 readyForNext(앱이 새 세션)(C06·R01)" to ::s96,
        "S97 고속도로 갈림길 예고 — 진출입 TBT의 방면이 IC·JC면 들어서기 전 한 번, 일반 회전·이미 고속도로·같은 방면은 없음(ADV-01)" to ::s97,
        "S98 세워 둔 차(P)에는 회전 예측 없음 — 내비 안내에 끊긴 출발 전 계획 문장을 다시 말함, 신호 대기(D)는 그대로 조심(11/8 실행 run_1108_183038에서 발견)" to ::s98,
        "S99 내비 재생기가 GAIN 뒤에도 목록에 남으면(재생은 끝남) 4초 뒤 끝난 것으로 봄 — 새 재생기·포커스 잃음엔 다시 물러섬(11/8 실행 run_1108_212028)" to ::s99,
    )
}

private val ZS = ZoneId.of("Asia/Seoul")
private fun kstR(h: Int, mi: Int) = ZonedDateTime.of(2026, 11, 14, h, mi, 0, 0, ZS).toInstant().toEpochMilli()
private const val LAT = 37.5665
private const val LON = 126.9780
private fun fixAt(t: Long, lat: Double = LAT, lon: Double = LON) = GeoFix(t, lat, lon, 15f)
private fun dv(t: Long, speed: Float = 15f) = VehicleSnapshot(t, gear = Gear.D, speedMps = speed)
private val OPEN = object : app.drivepromise.core.speech.QuietWindows {
    override fun normal(nowMs: Long, needMs: Long) = true
    override fun stopWindow(nowMs: Long) = false
}
private val CLOSED = object : app.drivepromise.core.speech.QuietWindows {
    override fun normal(nowMs: Long, needMs: Long) = false
    override fun stopWindow(nowMs: Long) = false
}

private class RClock(var t: Long) : Clock { override fun nowMs(): Long = t }

// ---------------------------------------------------------------- C01
private fun s84() {
    // 16:00 출발, 도착 18:00 예정(위험) → 창이 닫혀 경고가 대기 중 → ETA가 16:05로 좋아짐 → 경고는 조용히 지워지고 밤 문장 없음
    val t0 = kstR(16, 0)
    var win: app.drivepromise.core.speech.QuietWindows = CLOSED
    val q = SpeechScheduler(QuietPolicy(), object : app.drivepromise.core.speech.QuietWindows {
        override fun normal(nowMs: Long, needMs: Long) = win.normal(nowMs, needMs)
        override fun stopWindow(nowMs: Long) = false
    })
    val e = PromiseEngine(listOf(Promise.HomeBeforeDark(30)), q)
    e.tick(World(t0, dv(t0), fixAt(t0), etaMs = 120 * MIN))
    q.tick(World(t0, dv(t0)), true)
    eq(q.pendingKeys, listOf("dark.risk"), "위험 경고 대기")
    val w1 = World(t0 + 100, dv(t0 + 100), fixAt(t0 + 100), etaMs = 5 * MIN)
    e.tick(w1)
    win = OPEN
    val s = q.tick(w1, true)
    ok(s !is Step.Speak || s.key != "dark.night", "낮에 ‘해가 졌어요’ 없음: $s")
    ok(q.pendingKeys.none { it == "dark.night" }, "밤 문장 예약도 없음 ${q.pendingKeys}")
    // 같은 엔진에서 실제로 해가 진 뒤(도착 전, 주행 중) → 밤 문장 한 번
    val ss = SolarCalc.at(t0, LAT, LON, ZS).sunsetMs!!
    val w2 = World(ss + 60_000, dv(ss + 60_000), fixAt(ss + 60_000), etaMs = 20 * MIN)
    e.tick(w2)
    val s2 = q.tick(w2, true)
    ok(s2 is Step.Speak && s2.key == "dark.night", "해 진 뒤엔 밤 문장: $s2")
}

// ---------------------------------------------------------------- C02
private fun s85() {
    val ss = SolarCalc.at(kstR(16, 0), LAT, LON, ZS).sunsetMs!!
    eq(PromisePhrases.darkRisk(Lang.KO, ss, ss - 10 * MIN, ZS, true), "해 지기 직전 도착이에요.", "일몰 10분 전 도착 — 짧은 문장")
    eq(PromisePhrases.darkRisk(Lang.KO, ss, ss + 5 * MIN, ZS, true), "해 진 뒤 도착이에요.", "일몰 5분 뒤 도착 — 짧은 문장")
    val long = PromisePhrases.darkRisk(Lang.KO, kstR(17, 22), kstR(17, 28), ZS, false)
    eq(long, "이대로면 5시 28분 도착, 해는 5시 22분에 져요.", "긴 문장")
    ok(!long.contains("쉬지") && !PromisePhrases.darkRisk(Lang.EN, kstR(17, 22), kstR(17, 28), ZS, false).contains("stop"), "휴식을 막는 말 없음(‘2시간마다 쉬기’와 충돌)")
    eq(PromisePhrases.darkRisk(Lang.EN, ss, ss - 10 * MIN, ZS, true), "Arriving just before sunset.", "영어 짧은 문장")
}

// ---------------------------------------------------------------- C03
private fun s86() {
    val t = kstR(14, 0)
    // 신호 없음 → 모름
    val a = PromiseEngine(listOf(Promise.AvoidHighway(), Promise.RainCare()), SpeechScheduler(QuietPolicy(), OPEN))
    val end1 = a.endTrip(World(t, dv(t)))
    eq(end1["highway"], PromiseStatus.UNKNOWN, "도로 신호 없음 → 고속도로 약속 모름")
    eq(end1["rain"], PromiseStatus.UNKNOWN, "와이퍼 신호 없음 → 비 약속 모름")
    // 신호 받음(일반도로 제한속도·와이퍼 꺼짐) → 지킴
    val b = PromiseEngine(listOf(Promise.AvoidHighway(), Promise.RainCare()), SpeechScheduler(QuietPolicy(), OPEN))
    b.onNavi(NaviEvent.SpeedLimit(t, 60))
    b.tick(World(t, dv(t).copy(wiper = Wiper.OFF)))
    val end2 = b.endTrip(World(t + 1_000, dv(t + 1_000).copy(wiper = Wiper.OFF)))
    eq(end2["highway"], PromiseStatus.KEPT, "일반도로 관측 → 지킴")
    eq(end2["rain"], PromiseStatus.KEPT, "와이퍼 꺼짐 관측 → 지킴")
    // 빗길: 끝 순간 1틱만 빠르면 못 지킴이 아님(30초 이어진 과속만)
    val c = PromiseEngine(listOf(Promise.RainCare()), SpeechScheduler(QuietPolicy(), OPEN))
    var tt = t
    while (tt <= t + 70_000) { c.tick(World(tt, dv(tt, 10f).copy(wiper = Wiper.LOW))); tt += 100 }
    val end3 = c.endTrip(World(tt, dv(tt, 30f).copy(wiper = Wiper.LOW)))
    eq(end3["rain"], PromiseStatus.KEPT, "비 70초 · 끝 순간 한 번 과속 → 지킴")
    // 위치가 5분 넘게 안 오면 구역 모름(하루 전 좌표로 ‘지키는 중’이던 문제)
    val z = PromiseEngine(listOf(Promise.StayInZone(LAT, LON, 10_000)), SpeechScheduler(QuietPolicy(), OPEN))
    z.tick(World(t, dv(t), fixAt(t - 86_400_000)))
    eq(z.statuses["zone"], PromiseStatus.UNKNOWN, "하루 전 좌표 → 모름")
    z.tick(World(t + 100, dv(t + 100), fixAt(t + 100)))
    eq(z.statuses["zone"], PromiseStatus.ON_TRACK, "새 좌표 → 지키는 중")
    // 요약: 판단 못 한 약속은 이름으로(둘까지), 셋 이상이면 개수
    val five = listOf(Promise.HomeBeforeDark(30), Promise.AvoidHighway(), Promise.RainCare(), Promise.RestEvery(120), Promise.StayInZone(LAT, LON, 10_000))
    val st = mapOf("dark" to PromiseStatus.KEPT, "highway" to PromiseStatus.UNKNOWN, "rain" to PromiseStatus.UNKNOWN, "rest" to PromiseStatus.KEPT, "zone" to PromiseStatus.KEPT)
    eq(SummaryPhrases.kept(Lang.KO, five, st), "오늘 약속 3개를 모두 지켰어요. 수고하셨어요.", "판단한 것만 셈")
    eq(SummaryPhrases.unknownLine(Lang.KO, five, st), "고속도로 대신·비 오는 날 조심은 판단할 신호가 없었어요.", "모른 것은 이름으로")
    eq(SummaryPhrases.unknownLine(Lang.KO, five, st + ("dark" to PromiseStatus.UNKNOWN)), "약속 3개는 판단할 신호가 없었어요.", "셋 이상은 개수")
    eq(SummaryPhrases.unknownLine(Lang.KO, five, st.mapValues { PromiseStatus.KEPT }), null, "모두 판단하면 없음")
}

// ---------------------------------------------------------------- C04
private fun s87() {
    val g = QuietGate()
    g.update(0, dv(0, 2f).copy(turnSignal = Turn.LEFT))
    g.update(1_000, dv(1_000, 0f).copy(turnSignal = Turn.LEFT))
    ok(!g.stopWindow(1_500), "좌회전 대기(방향지시등 켬) — 신호 대기 창 닫힘")
    val h = QuietGate()
    h.update(0, dv(0, 15f))
    h.update(1_000, dv(1_000, 0f))   // 15 m/s² — 급제동
    ok(!h.stopWindow(1_500), "급제동 직후 — 창 닫힘")
    val k = QuietGate()
    k.update(0, dv(0, 2f))
    k.update(1_000, dv(1_000, 0f))
    ok(k.stopWindow(1_500), "서서히 서면 창 열림")
    // 스케줄러까지: 마감 지난 약속 말(상태 지속형)이 좌회전 대기 중엔 나가지 않음
    val g2 = QuietGate()
    val q = SpeechScheduler(QuietPolicy(), g2)
    val v = dv(1_000, 0f).copy(turnSignal = Turn.LEFT)
    g2.update(0, dv(0, 2f).copy(turnSignal = Turn.LEFT)); g2.update(1_000, v)
    q.submit(Utterance("p", Priority.PROMISE, 1_000, render = { "약속" }, deadlineAtMs = 1_000, validity = Validity.WHILE_STATE), 1_000)
    eq(q.tick(World(1_000, v), true), Step.Idle, "좌회전 대기 중 약속 말 없음(codex R12)")
}

// ---------------------------------------------------------------- C05
private fun s88() {
    val q = SpeechScheduler(QuietPolicy(), OPEN)
    val t49 = "이대로면 오후 5시 28분 도착, 해는 오후 5시 22분에 져요. 쉬지 말고 바로 가요."
    ok(q.needMs(4_000, t49, Lang.KO) >= 7_500, "옛 49자 문장은 7.5초 이상으로 봄(실측 8.0초): ${q.needMs(4_000, t49, Lang.KO)}")
    eq(q.needMs(4_000, "응", Lang.KO), 4_000L, "짧은 문장은 선언값")
    val asked = mutableListOf<Long>()
    val sch = SpeechScheduler(QuietPolicy(), object : app.drivepromise.core.speech.QuietWindows {
        override fun normal(nowMs: Long, needMs: Long): Boolean { asked += needMs; return needMs <= 5_000 }
        override fun stopWindow(nowMs: Long) = false
    })
    sch.submit(Utterance("long", Priority.INFO, 4_000, render = { t49 }), 0)
    eq(sch.tick(World(0), true), Step.Idle, "회전까지 5초 여유면 8초 문장은 시작하지 않음")
    ok(asked.last() >= 7_500, "창에 실제 길이로 물음: $asked")
    val sp = SpeechScheduler(QuietPolicy(), OPEN)
    sp.submit(Utterance("k", Priority.INFO, 4_000, render = { "이대로면 5시 28분 도착, 해는 5시 22분에 져요." }), 0)
    val s = sp.tick(World(0), true) as Step.Speak
    ok(s.estMs in 4_500..5_500, "새 일몰 문장 예상 길이 ${s.estMs} ms(약 4.7초)")
}

// ---------------------------------------------------------------- C08
private fun s89() {
    for (t in listOf("약속 알려 줘", "약속을 알려 줘", "약속 좀 알려 줘", "약속 잘 지키고 있어", "오늘 약속 다 지켰어", "약속 확인해 줘", "내 약속 어때")) {
        eq(VoiceCommands.match(t, Lang.KO), VoiceIntent.STATUS, "‘$t’")
    }
    eq(VoiceCommands.match("약속대로 쉴게", Lang.KO), VoiceIntent.YES, "‘약속대로 쉴게’는 대답")
    eq(VoiceCommands.match("약속 시간에 늦겠다", Lang.KO), VoiceIntent.UNKNOWN, "‘약속 시간’은 명령 아님")
    eq(VoiceCommands.match("나중에 쉴게", Lang.KO), VoiceIntent.NO, "‘나중에 쉴게’는 아니(뜻이 뒤집히지 않음)")
    eq(VoiceCommands.match("what time is it", Lang.KO), VoiceIntent.UNKNOWN, "한국어 설정에서 영어 ‘what’ 수락 안 함(codex R13)")
    eq(VoiceCommands.match("what", Lang.EN), VoiceIntent.REPEAT, "영어 설정에선 그대로")
    eq(VoiceCommands.match("약속 알려 줘", Lang.EN), VoiceIntent.STATUS, "영어 설정에서도 한국어 명령은 받음")
}

// ---------------------------------------------------------------- C09
private fun s90() {
    val rnd = Random(7)
    var warned = 0
    val baseLat = LAT + 9_800.0 / 111_320.0   // 중심에서 북쪽 9.8 km(경계 안쪽 200 m)
    repeat(50) {
        val q = SpeechScheduler(QuietPolicy(), OPEN)
        val e = PromiseEngine(listOf(Promise.StayInZone(LAT, LON, 10_000)), q)
        var t = 0L
        var f = GeoFix(0, baseLat, LON)
        while (t <= 60_000) {
            if (t % 1_000 == 0L) f = GeoFix(t, baseLat + rnd.nextDouble(-1.0, 1.0) * 5.0 / 111_320.0, LON + rnd.nextDouble(-1.0, 1.0) * 5.0 / 88_200.0)
            e.tick(World(t, dv(t, 0f), f))
            t += 100
        }
        if ("zone.edge" in q.pendingKeys) warned++
    }
    eq(warned, 0, "정차 중 흔들림 50회 — 오경보 0(1.2.2: 200/200)")
    // 실제로 경계 쪽으로 13.9 m/s(50 km/h) — 한 번
    val q = SpeechScheduler(QuietPolicy(), OPEN)
    val e = PromiseEngine(listOf(Promise.StayInZone(LAT, LON, 10_000)), q)
    var t = 0L
    var n = 0
    while (t <= 60_000) {
        val north = 9_500.0 + 13.9 * t / 1000.0
        e.tick(World(t, dv(t, 13.9f), GeoFix(t - t % 1_000, LAT + north / 111_320.0, LON)))
        n += q.pendingKeys.count { it == "zone.edge" }
        q.cancel("zone.edge", t)
        t += 100
    }
    eq(n, 1, "경계로 다가가면 예고 한 번")
}

// ---------------------------------------------------------------- C10
private fun s91() {
    val five = listOf(Promise.HomeBeforeDark(30), Promise.AvoidHighway(), Promise.RainCare(), Promise.RestEvery(120), Promise.StayInZone(LAT, LON, 10_000))
    val mix = mapOf("dark" to PromiseStatus.UNKNOWN, "highway" to PromiseStatus.ON_TRACK, "rain" to PromiseStatus.ON_TRACK, "rest" to PromiseStatus.ON_TRACK, "zone" to PromiseStatus.MISSED)
    eq(VoicePhrases.status(Lang.KO, five, mix), "약속 구역이 위험해요. 3개는 지키는 중이에요. 해 지기 전 귀가는 아직 몰라요.", "한국어 — 다섯 개 모두 언급")
    eq(VoicePhrases.status(Lang.EN, five, mix), "Stay in zone is at risk. 3 on track. Home before dark is not known yet.", "영어도 개수와 판단 전")
}

// ---------------------------------------------------------------- C11
private fun s92() {
    val e = PromiseEngine(listOf(Promise.AvoidHighway()), SpeechScheduler(QuietPolicy(), OPEN))
    e.onNavi(NaviEvent.SpeedLimit(0, 100)); e.tick(World(0, dv(0)))
    ok(e.isOnHighway, "제한속도 100 → 고속도로")
    e.onNavi(NaviEvent.RouteError(100, "ROUTE_CANCELLED")); e.tick(World(100, dv(100)))
    ok(!e.isOnHighway, "경로 취소 → 풀림(codex R14)")
    e.onNavi(NaviEvent.SpeedLimit(200, 100)); e.tick(World(200, dv(200)))
    e.onNavi(NaviEvent.SpeedLimit(300, 0)); e.tick(World(300, dv(300)))
    ok(!e.isOnHighway, "제한속도 0(정보 없음)도 풀림 — 어댑터가 0을 전달(1.3.0)")
    eq(e.endTrip(World(400, dv(400)))["highway"], PromiseStatus.MISSED, "들어선 적이 있으면 주행 끝은 못 지킴(그대로)")
}

// ---------------------------------------------------------------- C12
private fun restRun(promises: List<Promise>, untilMin: Long): List<String> {
    val c = RClock(0)
    val said = mutableListOf<String>()
    val s = TripSession(promises, FakeSpeaker(), c, listener = object : TripSession.Listener {
        override fun onSpeak(step: Step.Speak, nowMs: Long) { said += "${step.key}@${nowMs / MIN}" }
    })
    var cur: Step.Speak? = null; var endAt = 0L
    while (c.t <= untilMin * MIN) {
        s.onVehicle(dv(c.t))
        val cc = cur
        if (cc != null && c.t >= endAt) { s.onSpeechDone(cc.id); cur = null }
        val st = s.tick()
        if (st is Step.Speak) { cur = st; endAt = c.t + st.estMs }
        c.t += 1_000
    }
    return said.filter { it.startsWith("rest.") }
}

private fun s93() {
    eq(restRun(listOf(Promise.AvoidHighway()), 125), emptyList(), "쉬기 약속이 없으면 쉬기 말 없음(codex R05)")
    eq(restRun(listOf(Promise.RestEvery(90)), 95), listOf("rest.headsup@80", "rest.main@90"), "90분 약속 — 1:20 예고·1:30 본 알림(codex R06)")
    eq(restRun(listOf(Promise.RestEvery(120)), 125), listOf("rest.headsup@110", "rest.main@120"), "120분은 그대로")
}

// ---------------------------------------------------------------- C13
private fun s94() {
    val q = SpeechScheduler(QuietPolicy(), OPEN)
    val r = RestTracker(RestPolicy(), q)
    var t = 0L
    while (t <= 121 * MIN) { r.update(t, dv(t)); t += 1_000 }
    val parkFrom = t
    while (t <= parkFrom + 16 * MIN) { r.update(t, VehicleSnapshot(t, gear = Gear.P, speedMps = 0f)); t += 1_000 }
    eq(r.drivingMs, 0L, "P 16분 = 쉼(Q027 — 알림 뒤 쉬면 지킴)")
    ok(r.overTargetEver, "넘긴 사실은 남음")
    eq(r.endTrip(t, Lang.KO), "오늘은 두 시간을 넘겨 운전했지만, 알림 뒤 쉬셨어요.", "요약에 넘긴 사실")
    // 앱이 없던 시간(R08): 저장 때 P가 아니었고 16분이면 이어서, P였으면 쉼, 61분이면 쉼
    val last = 1_000_000L
    val a = RestTracker(RestPolicy(), SpeechScheduler(QuietPolicy(), OPEN))
    a.restore(RestTracker.State(119 * MIN, last, false, false, parkedAtSave = false), last + 16 * MIN)
    eq(a.drivingMs, 119 * MIN, "주행 중 앱이 16분 꺼짐 → 쉼 근거 없음(codex R08)")
    val b = RestTracker(RestPolicy(), SpeechScheduler(QuietPolicy(), OPEN))
    b.restore(RestTracker.State(119 * MIN, last, false, false, parkedAtSave = true), last + 16 * MIN)
    eq(b.drivingMs, 0L, "P로 저장 → 16분 = 쉼")
    val c = RestTracker(RestPolicy(), SpeechScheduler(QuietPolicy(), OPEN))
    c.restore(RestTracker.State(119 * MIN, last, false, false, parkedAtSave = false), last + 61 * MIN)
    eq(c.drivingMs, 0L, "61분 꺼짐 → 쉼")
}

// ---------------------------------------------------------------- C14
private fun s95() {
    val q = SpeechScheduler(QuietPolicy(), OPEN)
    val spk = FakeSpeaker()
    val loop = app.drivepromise.core.speech.VoiceLoop(q, app.drivepromise.core.speech.VoiceArbiter(), spk)
    q.submit(Utterance("a", Priority.INFO, 1_000, render = { "a" }), 0)
    var t = 0L
    val started = mutableListOf<Long>()
    while (t <= 60_000) {   // 말하기만 하고 완료·오류 소식은 보내지 않음
        val s = loop.tick(World(t))
        if (s is Step.Speak) started += t
        t += 100
    }
    eq(started.size, 3, "15초마다 멈추고 2초 뒤 다시 — 세 번: $started")
    ok(started[1] - started[0] in 15_000..17_200, "감시 타이머 15초 + 2초 뒤 다시: $started")
    eq(spk.stopped.size, 3, "멈춤 3번")
    ok(q.digest.any { it.key == "a" }, "세 번이면 요약으로")
    q.submit(Utterance("b", Priority.INFO, 1_000, render = { "b" }), t)
    ok((loop.tick(World(t)) as? Step.Speak)?.key == "b", "다른 말은 막히지 않음")
}

// ---------------------------------------------------------------- C06
private fun s96() {
    val c = RClock(0)
    val spk = FakeSpeaker()
    val s = TripSession(listOf(Promise.AvoidHighway()), spk, c)
    s.onVehicle(dv(0)); s.tick()
    eq(s.phase, TripSession.Phase.DRIVING, "주행")
    s.end("manual")
    ok(!s.readyForNext, "요약 말하기 전엔 아님")
    s.onVehicle(VehicleSnapshot(c.t, gear = Gear.P, speedMps = 0f))
    var cur: Step.Speak? = null
    while (c.t < 30_000 && !s.readyForNext) {
        c.t += 100
        val cc = cur
        if (cc != null) { s.onSpeechDone(cc.id); cur = null }
        val st = s.tick()
        if (st is Step.Speak) cur = st
    }
    ok(s.readyForNext, "요약을 다 말한 뒤 다음 주행 준비(앱이 새 세션)")
}

// ---------------------------------------------------------------- ADV-01
private fun s97() {
    fun run(events: List<NaviEvent>, limit: Int = 60): List<String> {
        val q = SpeechScheduler(QuietPolicy(), OPEN)
        val e = PromiseEngine(listOf(Promise.AvoidHighway()), q)
        e.onNavi(NaviEvent.SpeedLimit(0, limit))
        val out = mutableListOf<String>()
        var t = 0L
        val ev = events.toMutableList()
        while (t <= 60_000) {
            while (ev.isNotEmpty() && ev.first().tMillis <= t) e.onNavi(ev.removeAt(0))
            e.tick(World(t, dv(t, 16f)))
            val s = q.tick(World(t, dv(t, 16f)), true)
            if (s is Step.Speak) { out += s.key + ":" + s.text; q.spoken(s.id, t) }
            t += 100
        }
        return out
    }
    val a = run(listOf(NaviEvent.Tbt(1_000, 900, TurnType.MERGE, toward = "판교IC 방면"), NaviEvent.Tbt(5_000, 830, TurnType.MERGE, toward = "판교IC 방면")))
    eq(a, listOf("highway.ahead:앞 갈림길은 판교IC 방면이에요. 약속대로 일반도로로 가요."), "진출입 + IC 방면 → 한 번(같은 방면 반복 없음)")
    eq(run(listOf(NaviEvent.Tbt(1_000, 380, TurnType.RIGHT, toward = "판교IC 방면"))), emptyList(), "일반 우회전은 예고 없음(11/6 실측 경로의 ‘판교IC 방면’ 우회전)")
    ok(run(listOf(NaviEvent.Tbt(1_000, 900, TurnType.MERGE, toward = "판교IC 방면")), limit = 100).none { it.startsWith("highway.ahead") }, "이미 고속도로면 예고 없음")
    eq(run(listOf(NaviEvent.Tbt(1_000, 3_000, TurnType.EXIT, toward = "서울TG 방면"))), emptyList(), "멀거나(1.5 km 넘음) 낱말이 없으면 없음")
}

// ---------------------------------------------------------------- 1.3.0b — 11/8 에뮬레이터 실행에서 발견(C05 글자 수 길이 + 정차 중 회전 예측)
private fun s98() {
    val plan = "추천 경로로 가요. 11분 걸려요. 지금 가도 오후 6시 46분 도착, 해 진 뒤예요(일몰 오후 5시 27분). 해 지기 전 도착은 어려워요 — 다른 날이나 동승을 생각해요. 고속도로가 있는지는 알 수 없어요."
    val parkedV = VehicleSnapshot(0, gear = Gear.P, speedMps = 0f)
    val g = QuietGate()
    g.update(0, parkedV)
    g.onNavi(NaviEvent.Tbt(0, 80, TurnType.LEFT))
    eq(g.timeToTurnMs, null, "세워 둔 차(P)에는 ‘곧 회전’ 없음")
    ok(g.normal(100, 17_000), "P에서는 긴 계획 문장(약 17초)도 창 열림")
    val d = QuietGate()
    d.update(0, VehicleSnapshot(0, gear = Gear.D, speedMps = 0f))
    d.onNavi(NaviEvent.Tbt(0, 80, TurnType.LEFT))
    ok(!d.normal(100, 10_000), "신호 대기(D · 80 m 앞 좌회전)는 그대로 — 10초 문장 시작 안 함")
    // 스케줄러까지: 첫 말이 내비 안내에 물러선(yield) 뒤 P에서 다시 나감
    val g2 = QuietGate()
    g2.update(0, parkedV)
    val q = SpeechScheduler(QuietPolicy(), g2)
    q.submit(Utterance("plan", Priority.INFO, 4_000, render = { plan }), 0)
    val first = q.tick(World(0, parkedV), true) as? Step.Speak ?: fail("첫 말이 나가야 함")
    g2.onNavi(NaviEvent.Tbt(100, 80, TurnType.LEFT))   // 경로 안내 시작 — 첫 회전 80 m
    q.yielded(first.id, 200)
    var again: Step.Speak? = null
    var t = 3_000L
    while (t <= 20_000 && again == null) {
        g2.update(t, parkedV.copy(tMillis = t))
        again = q.tick(World(t, parkedV.copy(tMillis = t)), true) as? Step.Speak
        t += 100
    }
    ok(again != null && again.key == "plan", "물러선 계획 문장을 P에서 다시 말함(1.3.0은 20초 안에 못 함): ${again?.key}@$t")
}

// ---------------------------------------------------------------- 1.3.0b — 11/8 두 번째 실행(run_1108_212028)에서 발견
private fun s99() {
    // 실측 순서(ms, 계획 문장 시작 = 0): 포커스 잃음 0 → 내비 재생기 1(110) → 2(294) → 1(2 643) → GAIN(2 646) → 26초 동안 1개가 그대로
    val a = VoiceArbiter()
    a.started(2)
    ok(a.onFocus(0, VoiceArbiter.Focus.LOSS_TRANSIENT) is VoiceArbiter.Action.Yield, "포커스 잃음에 물러섬")
    a.onNavPlayers(110, 1); a.onNavPlayers(294, 2); a.onNavPlayers(2_643, 1)
    a.onFocus(2_646, VoiceArbiter.Focus.GAIN)
    ok(!a.canStart(6_645), "GAIN 뒤 4초 전에는 아직(남은 재생기가 진짜 말일 수 있음)")
    ok(a.canStart(6_646), "GAIN 뒤 4초 동안 목록이 그대로면 남은 재생기는 끝난 것으로 봄")
    eq(a.staleCount, 1, "기록용 횟수")
    a.started(3)
    val y = a.onNavPlayers(9_000, 2)
    ok(y is VoiceArbiter.Action.Yield && !a.canStart(9_100), "그 뒤 내비 재생기가 늘면 다시 물러서고 시작 금지: $y")
    // GAIN 뒤에 재생기가 늘었으면(포커스 없이 말하는 경우) 남은 것으로 보지 않음
    val b = VoiceArbiter()
    b.onFocus(0, VoiceArbiter.Focus.LOSS_TRANSIENT); b.onFocus(1_000, VoiceArbiter.Focus.GAIN)
    b.onNavPlayers(1_500, 1)
    ok(!b.canStart(30_000), "GAIN 뒤에 나타난 재생기는 끝날 때까지 기다림")
    // GAIN이 없으면(포커스를 잃은 채) 남은 재생기를 끝난 것으로 보지 않음
    val c = VoiceArbiter()
    c.onFocus(0, VoiceArbiter.Focus.LOSS_TRANSIENT); c.onNavPlayers(100, 1)
    ok(!c.canStart(60_000), "GAIN 없고 재생기 남음 — 시작 금지")
    // 끝에서 끝까지(세워 둔 차 · 경로 첫 회전 80 m · 실측 내비 순서): 계획 문장이 물러선 뒤 짧게 다시 나감
    val parked = VehicleSnapshot(0, gear = Gear.P, speedMps = 0f)
    val gate = QuietGate()
    gate.update(0, parked)
    val sim = Sim(SpeechScheduler(QuietPolicy(), gate))
    val plan = "추천 경로로 가요. 11분 걸려요. 지금 가도 오후 9시 32분 도착, 해 진 뒤예요(일몰 오후 5시 27분). 해 지기 전 도착은 어려워요 — 다른 날이나 동승을 생각해요. 고속도로가 있는지는 알 수 없어요."
    sim.sch.submit(Utterance("plan", Priority.INFO, 4_000, render = { plan }), 0)
    sim.focus(50, VoiceArbiter.Focus.LOSS_TRANSIENT)
    sim.nav(110, 1); sim.nav(294, 2)
    sim.at(400) { t -> gate.onNavi(NaviEvent.Tbt(t, 80, TurnType.LEFT)) }
    sim.nav(2_643, 1); sim.focus(2_646, VoiceArbiter.Focus.GAIN)
    sim.run(0, 15_000) { t -> World(t, parked.copy(tMillis = t)) }
    eq(sim.log.map { Triple(it.key, it.attempt, it.short) }, listOf(Triple("plan", 1, false), Triple("plan", 2, true)), "계획 문장: 물러섬 → 짧게 다시")
    ok(sim.log[1].t in 6_646L..7_000L, "다시 말한 시각 ${sim.log[1].t} ms(GAIN 뒤 4초)")
}
