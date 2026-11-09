package app.drivepromise.core.engine

import app.drivepromise.core.ledger.LedgerEvent
import app.drivepromise.core.ledger.LedgerSink
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.model.TurnType
import app.drivepromise.core.model.Wiper
import app.drivepromise.core.plan.SolarCalc
import app.drivepromise.core.scene.PromisePhrases
import app.drivepromise.core.speech.Priority
import app.drivepromise.core.speech.SayCtx
import app.drivepromise.core.speech.SpeechScheduler
import app.drivepromise.core.speech.Utterance
import app.drivepromise.core.speech.Validity
import app.drivepromise.core.speech.World
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 약속 판정 값 — 가설값은 문서 ‘가정’ 표에, 바꾸면 스펙이 잡음 */
data class EnginePolicy(
    /** 와이퍼가 이만큼 계속 움직이면 ‘비’ */
    val rainOnMs: Long = 60_000,
    /** 와이퍼가 이만큼 멈춰 있으면 ‘비 그침’ */
    val rainOffMs: Long = 300_000,
    /** 빗길 감속 기준: 제한속도의 이 비율(도로교통법 시행규칙 — 젖은 노면 20% 감속) */
    val rainSpeedFactor: Double = 0.8,
    /** 제한속도를 모를 때 빗길 기준 속도(km/h) */
    val rainDefaultLimitKph: Int = 60,
    /** 빗길 과속이 이만큼 이어지면 한 번 말함 */
    val rainFastHoldMs: Long = 30_000,
    /** 제한속도가 이 이상이면 고속도로로 봄 */
    val highwaySpeedLimitKph: Int = 100,
    val highwayRoadKeywords: List<String> = listOf("고속도로", "고속국도", "Expressway"),
    /** 경계에서 이만큼 안쪽부터 ‘끝이 가까움’ */
    val zoneEdgeM: Int = 300,
    /** 밖에 나간 뒤에는 경계에서 이만큼 안쪽으로 들어와야 ‘돌아옴’ — 실제 위치(FusedLocation)의 흔들림에 말·상태가 깜빡이지 않게(11/5, S76) */
    val zoneBackM: Int = 100,
    /** 해 지기 전 귀가: 위험 → 정상으로 되돌리는 여유(깜빡임 방지) */
    val darkHysteresisMs: Long = 5 * 60_000,
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    /** 1.3.0(C03): 마지막 위치가 이보다 오래되면 구역 판정은 ‘모름’(codex R03 — 하루 지난 좌표로 ‘지키는 중’이던 문제). 리플레이 트립 간격(최대 약 1분)보다 넉넉히 */
    val fixTtlMs: Long = 5 * 60_000,
    /** 1.3.0(C09): ‘구역 끝으로 가는 중’ = 차가 움직이고, 이 시간 동안 경계 쪽으로 이만큼 이상 다가감(GPS 흔들림 σ≈3 m에 오경보 200/200이던 문제) */
    val zoneEdgeWindowMs: Long = 10_000,
    val zoneEdgeMoveM: Double = 15.0,
    /** 1.3.0(PT 과제 ADV-01): 고속도로로 들어가는 갈림길 예고 — TBT가 진출입(EXIT·MERGE)이고 방면 설명에 이 말이 있을 때, 이 거리 안 */
    val highwayAhead: Boolean = true,
    val highwayAheadWords: List<String> = listOf("IC", "JC", "고속도로", "고속국도", "톨게이트", "나들목", "Expressway"),
    val highwayAheadMinM: Int = 150,
    val highwayAheadMaxM: Int = 1_500,
    /** 1.3.2(P1-4): 구역 안↔밖을 바꾸려면 새 쪽 위치가 이만큼 이어져야(서로 다른 위치 수 · 첫 위치부터 시간) — 지하 출구·터널의 튄 위치 1건으로 ‘못 지킴’이 되던 문제(B07) */
    val zoneConfirmFixes: Int = 2,
    val zoneConfirmMs: Long = 5_000,
)

/**
 * 약속 엔진 — 약속 5종의 상태(정차 화면 타일)와 상태가 바뀔 때의 말을 만듭니다. 순수 로직, 10 Hz [tick].
 * 말은 [SpeechScheduler]에 넣고(언제 말할지는 스케줄러·침묵 구간·물러서기 몫), 상태 변화는 [LedgerSink]에 기록합니다.
 * 2시간 쉬기의 말은 RestTracker가 맡고, 여기서는 상태만 냅니다.
 */
class PromiseEngine(
    val promises: List<Promise>,
    private val scheduler: SpeechScheduler,
    private val ledger: LedgerSink = LedgerSink { },
    private val policy: EnginePolicy = EnginePolicy(),
) {
    private val status = LinkedHashMap<String, PromiseStatus>().also { m -> promises.forEach { m[it.id] = PromiseStatus.UNKNOWN } }

    // 내비·위치 상태
    private var etaEpochMs: Long? = null
    private var speedLimitKph: Int? = null
    private var road: String? = null
    private var arrivedAt: Long? = null
    private var lastFix: GeoFix? = null
    private var prevDist: Double? = null

    // 약속별 내부 상태
    private var darkWarned = false
    /** 1.3.0d: 이번 세션에서 차가 한 번이라도 움직였는가(기어 R·N·D 또는 주행 시간 > 0) */
    private var movedEver = false
    private var drivingMsAtStart: Long? = null   // 1.3.1(P0-2): 세션 첫 틱의 운전 시간(복원값)
    private var darkSpokeNight = false
    /** 1.3.2(P1-2): 마지막으로 달린 시각 — 도착 소식이 없을 때 ‘주차한 시각’으로 판정(세션이 늦게 끝나도) */
    private var lastDrivingAt: Long? = null
    private var lastDrivingMs = -1L
    /** 1.3.2(P1-1): 어두울 때(해 진 뒤 또는 해 뜨기 전) 달린 적이 있음 — 자정을 넘겨 날짜가 바뀌어도 ‘못 지킴’ 유지 */
    private var darkDriven = false
    private var onHighway = false
    private var highwayEver = false
    private var wiperOnSince: Long? = null
    private var wiperOffSince: Long? = null
    private var raining = false
    private var rainFastSince: Long? = null
    private var rainFastSpoken = false
    private var rainFastEver = false   // 11/5: 빗길 과속이 한 번이라도 있었으면 주행 끝 판정은 못 지킴
    private var zoneEdgeWarned = false
    private var outside = false
    private var outsideEver = false    // 11/5: 구역을 한 번이라도 벗어났으면 주행 끝 판정은 못 지킴(고속도로와 같은 규칙)
    private var tripEnded = false
    // 1.3.0(C03): 신호를 한 번이라도 받았는가 — 못 받았으면 주행 끝 판정은 ‘모름’(‘모르면 판단 보류’)
    private var roadSeen = false
    private var wiperSeen = false
    // 1.3.0(C09): 구역 경계까지 거리 기록(위치가 바뀔 때만) — 10초 창으로 ‘바깥으로 가는 중’ 판단
    private val zoneHist = ArrayDeque<Pair<Long, Double>>()
    // 1.3.0(ADV-01): 아직 판단하지 않은 갈림길 TBT · 이미 예고한 방면
    private var aheadTbt: NaviEvent.Tbt? = null
    private var aheadWarned: String? = null
    // 1.3.2(P1-4): 구역 상태를 바꿀 후보 — 다른 쪽으로 처음 본 위치 시각과 이어진 위치 수
    private var zoneCandSince: Long? = null
    private var zoneCandN = 0
    private var zoneLastFixT = Long.MIN_VALUE

    val statuses: Map<String, PromiseStatus> get() = status.toMap()
    val isRaining: Boolean get() = raining
    val isOnHighway: Boolean get() = onHighway

    fun onNavi(e: NaviEvent) {
        when (e) {
            is NaviEvent.Trip -> etaEpochMs = e.etaEpochMs
            is NaviEvent.SpeedLimit -> { speedLimitKph = e.kph; if (e.kph > 0) roadSeen = true }   // 0 = 이 도로 정보 없음(1.3.0: 어댑터가 0도 전달)
            is NaviEvent.Tbt -> { if (e.road != null) { road = e.road; roadSeen = true }; aheadTbt = e }
            is NaviEvent.Arrived -> { arrivedAt = e.tMillis; speedLimitKph = null; road = null }
            // 1.3.0(C11): 경로 취소·오류면 도로 정보도 비움 — 고속도로 상태가 남아 있던 문제(codex R14)
            is NaviEvent.RouteError -> { etaEpochMs = null; speedLimitKph = null; road = null; aheadTbt = null }
            is NaviEvent.Caution -> Unit
        }
    }

    /** 10 Hz — World.fix·vehicle·etaMs·drivingMs로 약속 상태를 판정 */
    fun tick(w: World) {
        if (tripEnded) return
        w.fix?.let { lastFix = it }
        if (drivingMsAtStart == null) drivingMsAtStart = w.drivingMs
        if (w.vehicle?.driving == true || w.drivingMs > (drivingMsAtStart ?: 0L)) movedEver = true   // 1.3.1(P0-2): 복원된 운전 시간만으로는 ‘움직임’이 아님(S100 회귀)
        if (drivingNow(w)) lastDrivingAt = w.tMillis
        lastDrivingMs = w.drivingMs
        for (p in promises) {
            val s = when (p) {
                is Promise.HomeBeforeDark -> dark(p, w)
                is Promise.AvoidHighway -> highway(p, w)
                is Promise.RainCare -> rain(p, w)
                is Promise.RestEvery -> rest(p, w)
                is Promise.StayInZone -> zone(p, w)
            }
            set(p.id, s, w.tMillis)
        }
    }

    /** 주행 끝(도착·긴 정차) — 그 시점의 판정을 지킴/못 지킴으로 닫음 */
    fun endTrip(w: World): Map<String, PromiseStatus> {
        tick(w)
        tripEnded = true
        val now = w.tMillis
        for (p in promises) {
            val cur = status[p.id] ?: PromiseStatus.UNKNOWN
            val fin = when (p) {
                // 1.3.0d: 움직이지 않은 세션(세워 둔 채 끝남)은 판정하지 않음.
                // 1.3.2(P1-1·P1-2): 어두울 때 달렸으면 못 지킴 · 아니면 ‘도착 ?: 마지막으로 달린 시각 ?: 지금’이 어두웠는지로 — 세션이 늦게 끝나도(30분 정차) 주차 시각 기준
                is Promise.HomeBeforeDark -> if (!movedEver) PromiseStatus.UNKNOWN
                    else if (darkDriven) PromiseStatus.MISSED
                    else darkAt(arrivedAt ?: lastDrivingAt ?: now, w)?.let { dk -> if (dk) PromiseStatus.MISSED else PromiseStatus.KEPT } ?: cur
                // 1.3.0(C03): 도로·와이퍼 신호를 한 번도 못 받았으면 ‘모름’ — 빗길은 30초 이어진 과속만 ‘못 지킴’(끝 순간 1틱 과속은 세지 않음)
                is Promise.AvoidHighway -> if (highwayEver) PromiseStatus.MISSED else if (!roadSeen) PromiseStatus.UNKNOWN else PromiseStatus.KEPT
                is Promise.RainCare -> if (rainFastEver) PromiseStatus.MISSED else if (!wiperSeen) PromiseStatus.UNKNOWN else PromiseStatus.KEPT
                is Promise.RestEvery -> if (w.drivingMs >= p.everyMin * 60_000L) PromiseStatus.MISSED else PromiseStatus.KEPT
                is Promise.StayInZone -> if (outside || outsideEver) PromiseStatus.MISSED else if (cur == PromiseStatus.UNKNOWN) cur else PromiseStatus.KEPT
            }
            set(p.id, fin, now, "trip_end")
        }
        return statuses
    }

    // ---------------------------------------------------------------- 해 지기 전 귀가
    private fun sunset(w: World): Long? {
        val f = w.fix ?: lastFix ?: return null
        return SolarCalc.at(w.tMillis, f.lat, f.lon, policy.zone).sunsetMs
    }

    private fun arrival(w: World): Long? = w.etaMs?.let { w.tMillis + it } ?: etaEpochMs

    /** 1.3.2(P1-1): 그 시각이 어두운가 — 그 지역 날짜의 일몰 뒤이거나 일출 전. 해가 지지 않는 곳(일몰 없음)은 null */
    private fun darkAt(t: Long, w: World): Boolean? {
        val f = w.fix ?: lastFix ?: return null
        val st = SolarCalc.at(t, f.lat, f.lon, policy.zone)
        val ss = st.sunsetMs ?: return null
        return t > ss || (st.sunriseMs?.let { t < it } ?: false)
    }

    /** 지금 달리는 중인가 — 차량 신호가 없으면 운전 시간이 늘었는지로 */
    private fun drivingNow(w: World): Boolean = w.vehicle?.driving ?: (lastDrivingMs >= 0 && w.drivingMs > lastDrivingMs)

    private fun dark(p: Promise.HomeBeforeDark, w: World): PromiseStatus {
        val now = w.tMillis
        val ss = sunset(w) ?: return PromiseStatus.UNKNOWN
        val deadline = ss - p.marginMin * 60_000L
        // 1.3.2(P1-1): 도착 시각도 ‘어두운가’로(새벽 도착은 그날 일몰 전이어도 못 지킴)
        if (arrivedAt != null) return if (darkDriven || darkAt(arrivedAt!!, w) == true) PromiseStatus.MISSED else PromiseStatus.KEPT
        if (darkDriven) return PromiseStatus.MISSED   // 1.3.2(P1-1): 자정을 넘겨 날짜가 바뀌어도 그대로
        if (darkAt(now, w) == true) {
            // 1.3.0d(11/8 실행 화면): 이번 세션에서 아직 움직이지 않았으면(세워 둔 채 해가 짐) ‘못 지킴’이 아니라 ‘위험’(지금 출발하면 해 진 뒤) — 말은 하지 않음.
            // 밤에 집에서 앱을 켜기만 해도 빨간 ‘못 지킴’이 뜨고, 주행 없이 끝내면 요약이 ‘해 지기 전 귀가도 지켜요’라고 나무라던 것
            if (!movedEver) return PromiseStatus.AT_RISK
            // 1.3.2(P1-2·P1-3): 세워 둔 동안 해가 지면 아직 ‘위험’(주차 시각 기준으로는 지킴) — 다시 달릴 때 못 지킴 + 밤 문장.
            // 일몰 순간 잠깐 P(편의점)였다고 밤 문장을 버리던 것(B20)과, 일몰 전 세웠는데 세션이 늦게 끝나 ‘못 지킴’이던 것(B13)
            if (!drivingNow(w)) return PromiseStatus.AT_RISK
            darkDriven = true
            if (!darkSpokeNight) {
                darkSpokeNight = true
                submit(p.id, "night", now, Priority.PROMISE, 3_500, 2_000, Validity.WHILE_STATE, deadline = now,
                    stillValid = { arrivedAt == null },
                    render = { c -> PromisePhrases.darkNow(c.world.lang, c.short) })
            }
            return PromiseStatus.MISSED
        }
        val arr = arrival(w) ?: return PromiseStatus.UNKNOWN
        val cur = status[p.id]
        val risk = if (cur == PromiseStatus.AT_RISK) arr > deadline - policy.darkHysteresisMs else arr > deadline
        if (risk && !darkWarned) {
            darkWarned = true
            submit(p.id, "risk", now, Priority.PROMISE, 4_000, 2_000, Validity.UNTIL_DEADLINE, deadline = ss,
                stillValid = { ww -> arrivedAt == null && (arrival(ww)?.let { it > deadline - policy.darkHysteresisMs } ?: true) },
                render = { c -> PromisePhrases.darkRisk(c.world.lang, ss, arrival(c.world) ?: arr, policy.zone, c.short) },
                // 1.3.0(C01): 밤 문장은 실제로 해가 진 뒤에만 — 위험이 풀려 지워질 때 낮에 “해가 졌어요”가 나오던 문제(codex R04)
                followup = { ww -> if (arrivedAt == null && ww.tMillis > ss) nightLine(p.id, ww.tMillis, deadline) else null })
        }
        if (!risk) darkWarned = false   // 다시 늦어지면 한 번 더 말함(히스테리시스 안에서는 유지)
        return if (risk) PromiseStatus.AT_RISK else PromiseStatus.ON_TRACK
    }

    private fun nightLine(id: String, now: Long, deadline: Long): Utterance? {
        if (darkSpokeNight) return null
        darkSpokeNight = true
        // 1.3.2(P1-3): 세워 둔 동안엔 버리지 않고 미룸(speakWhen) — 다시 달릴 때 말함
        return Utterance("$id.night", Priority.PROMISE, 3_500, estShortMs = 2_000, deadlineAtMs = now, validity = Validity.WHILE_STATE,
            stillValid = { arrivedAt == null }, speakWhen = { ww -> ww.vehicle?.driving != false },
            render = { c -> PromisePhrases.darkNow(c.world.lang, c.short) })
    }

    // ---------------------------------------------------------------- 고속도로 대신
    private fun highway(p: Promise.AvoidHighway, w: World): PromiseStatus {
        val byLimit = (speedLimitKph ?: 0) >= policy.highwaySpeedLimitKph
        val byRoad = road?.let { r -> policy.highwayRoadKeywords.any { r.contains(it, ignoreCase = true) } } ?: false
        val now = byLimit || byRoad
        if (!now) aheadCheck(p, w)
        if (now && !onHighway && !highwayEver) {   // 1.3.1(P0-3): 한 주행에 한 번 — 제한속도 정보가 깜박여도 다시 말하지 않음
            highwayEver = true
            submit(p.id, "on", w.tMillis, Priority.PROMISE, 4_000, 2_000, Validity.WHILE_STATE, deadline = w.tMillis,
                stillValid = { onHighway }, render = { c -> PromisePhrases.highwayEntered(c.world.lang, c.short) })
        }
        onHighway = now
        return if (now) PromiseStatus.MISSED else if (highwayEver) PromiseStatus.MISSED else PromiseStatus.ON_TRACK
    }

    /** 1.3.0(PT 과제 ADV-01): 고속도로 진출입 갈림길 예고 — 들어서기 전에 한 번(같은 방면은 한 번만) */
    private fun aheadCheck(p: Promise.AvoidHighway, w: World) {
        val t = aheadTbt ?: return
        if (!policy.highwayAhead) return
        aheadTbt = null
        val toward = t.toward?.trim().orEmpty()
        if (toward.isEmpty() || toward == aheadWarned) return
        if (t.turn != TurnType.EXIT && t.turn != TurnType.MERGE) return
        if (t.distM !in policy.highwayAheadMinM..policy.highwayAheadMaxM) return
        val hit = policy.highwayAheadWords.any { k -> if (k.all { it.isUpperCase() }) toward.contains(k) else toward.contains(k, ignoreCase = true) }
        if (!hit) return
        aheadWarned = toward
        val v = maxOf((w.vehicle?.speedMps ?: 0f).toDouble(), 5.0)
        val until = w.tMillis + (t.distM / v * 1000).toLong().coerceIn(10_000L, 90_000L)
        submit(p.id, "ahead", w.tMillis, Priority.PROMISE, 4_000, 2_000, Validity.UNTIL_DEADLINE, deadline = until,
            stillValid = { ww -> !onHighway && ww.tMillis <= until },
            render = { c -> PromisePhrases.highwayAhead(c.world.lang, toward, c.short) })
    }

    // ---------------------------------------------------------------- 비 오는 날
    private fun rain(p: Promise.RainCare, w: World): PromiseStatus {
        val v = w.vehicle ?: return if (raining) PromiseStatus.ON_TRACK else PromiseStatus.UNKNOWN
        val now = w.tMillis
        if (v.wiper != Wiper.UNKNOWN) wiperSeen = true
        val wiping = v.wiper == Wiper.INTERMITTENT || v.wiper == Wiper.LOW || v.wiper == Wiper.HIGH
        if (wiping) { wiperOffSince = null; if (wiperOnSince == null) wiperOnSince = now } else { wiperOnSince = null; if (wiperOffSince == null) wiperOffSince = now }
        if (!raining && wiperOnSince != null && now - wiperOnSince!! >= policy.rainOnMs) {
            raining = true; rainFastSpoken = false; rainFastSince = null
            submit(p.id, "start", now, Priority.PROMISE, 4_000, 2_000, Validity.WHILE_STATE, deadline = now,
                stillValid = { raining }, render = { c -> PromisePhrases.rainStart(c.world.lang, c.short) })
        }
        if (raining && wiperOffSince != null && now - wiperOffSince!! >= policy.rainOffMs) raining = false
        if (!raining) return if (v.wiper == Wiper.UNKNOWN && wiperOnSince == null) PromiseStatus.UNKNOWN else PromiseStatus.ON_TRACK
        val limit = speedLimitKph?.takeIf { it > 0 } ?: policy.rainDefaultLimitKph   // 1.3.1(P0-1): 0 = 이 도로 정보 없음 → 기본값(‘0 아래로 줄여요’ 방지)
        val target = (limit * policy.rainSpeedFactor).toInt()
        val kph = ((v.speedMps ?: 0f) * 3.6f).toInt()
        val fast = kph > target
        if (fast) { if (rainFastSince == null) rainFastSince = now } else rainFastSince = null
        if (fast && rainFastSince != null && now - rainFastSince!! >= policy.rainFastHoldMs) rainFastEver = true
        if (fast && !rainFastSpoken && now - rainFastSince!! >= policy.rainFastHoldMs) {
            rainFastSpoken = true
            submit(p.id, "fast", now, Priority.PROMISE, 3_500, 2_000, Validity.WHILE_STATE, deadline = now,
                stillValid = { ww -> raining && ((ww.vehicle?.speedMps ?: 0f) * 3.6f).toInt() > target },
                render = { c -> PromisePhrases.rainFast(c.world.lang, ((c.world.vehicle?.speedMps ?: 0f) * 3.6f).toInt(), target, c.short) })
        }
        return if (fast) PromiseStatus.AT_RISK else PromiseStatus.ON_TRACK
    }

    // ---------------------------------------------------------------- 2시간 쉬기(말은 RestTracker)
    private fun rest(p: Promise.RestEvery, w: World): PromiseStatus {
        val target = p.everyMin * 60_000L
        return when {
            w.drivingMs >= target -> PromiseStatus.MISSED
            w.drivingMs >= target - 10 * 60_000L -> PromiseStatus.AT_RISK
            else -> PromiseStatus.ON_TRACK
        }
    }

    // ---------------------------------------------------------------- 약속 구역
    private fun zone(p: Promise.StayInZone, w: World): PromiseStatus {
        val f = w.fix ?: lastFix ?: return PromiseStatus.UNKNOWN
        if (w.tMillis - f.tMillis > policy.fixTtlMs) return PromiseStatus.UNKNOWN   // 1.3.0(C03): 오래된 위치로는 판단하지 않음
        val d = distanceM(f.lat, f.lon, p.lat, p.lon)
        // 1.3.0(C09): 위치가 바뀔 때만 기록하고, 10초 전(없으면 3초 이상 된 가장 오래된 기록)보다 15 m 이상 멀어졌고 차가 움직일 때만 ‘바깥으로’
        if (zoneHist.isEmpty() || zoneHist.last().first < f.tMillis) zoneHist.addLast(f.tMillis to d)
        while (zoneHist.size > 2 && zoneHist[1].first <= f.tMillis - policy.zoneEdgeWindowMs) zoneHist.removeFirst()
        val ref = zoneHist.first()
        val moving = w.vehicle?.stopped != true
        val movingOut = moving && f.tMillis - ref.first >= 3_000 && d - ref.second >= policy.zoneEdgeMoveM
        prevDist = d
        val now = w.tMillis
        val rawOut = if (outside) d > p.radiusM - policy.zoneBackM else d > p.radiusM   // 히스테리시스: 나갈 때는 경계, 돌아올 때는 경계 안쪽 zoneBackM
        // 1.3.2(P1-4): 새 쪽 위치가 zoneConfirmFixes번·zoneConfirmMs 이어져야 상태를 바꿈(위치가 바뀔 때만 셈)
        if (f.tMillis != zoneLastFixT) {
            zoneLastFixT = f.tMillis
            if (rawOut != outside) { if (zoneCandSince == null) { zoneCandSince = f.tMillis; zoneCandN = 1 } else zoneCandN++ }
            else { zoneCandSince = null; zoneCandN = 0 }
        }
        val confirmed = rawOut != outside && zoneCandN >= policy.zoneConfirmFixes && f.tMillis - (zoneCandSince ?: f.tMillis) >= policy.zoneConfirmMs
        val isOut = if (confirmed) rawOut else outside
        if (confirmed) { zoneCandSince = null; zoneCandN = 0 }
        if (isOut && !outside) {
            outside = true
            outsideEver = true
            submit(p.id, "out", now, Priority.PROMISE, 4_000, 2_000, Validity.WHILE_STATE, deadline = now,
                stillValid = { outside }, render = { c -> PromisePhrases.zoneOut(c.world.lang, c.short) })
        } else if (!isOut && outside) {
            outside = false; zoneEdgeWarned = false
            scheduler.submit(Utterance("${p.id}.back", Priority.INFO, 2_500, render = { c -> PromisePhrases.zoneBack(c.world.lang) }), now)
        }
        if (isOut) return PromiseStatus.MISSED
        if (rawOut) return PromiseStatus.AT_RISK   // 1.3.2(P1-4): 밖 위치를 아직 확인하지 못함 — 말하지 않고 ‘위험’만
        val nearEdge = d > p.radiusM - policy.zoneEdgeM && movingOut
        if (nearEdge && !zoneEdgeWarned) {
            zoneEdgeWarned = true
            submit(p.id, "edge", now, Priority.PROMISE, 4_000, 2_000, Validity.UNTIL_DEADLINE, deadline = now + 60_000,
                stillValid = { !outside && (prevDist ?: 0.0) > p.radiusM - policy.zoneEdgeM },
                render = { c -> PromisePhrases.zoneEdge(c.world.lang, c.short) })
        }
        if (d <= p.radiusM - policy.zoneEdgeM) zoneEdgeWarned = false
        return if (nearEdge || zoneEdgeWarned) PromiseStatus.AT_RISK else PromiseStatus.ON_TRACK
    }

    // ---------------------------------------------------------------- 공통
    private fun submit(
        id: String, key: String, now: Long, priority: Priority, est: Long, short: Long, validity: Validity, deadline: Long,
        stillValid: (World) -> Boolean, render: (SayCtx) -> String, followup: (World) -> Utterance? = { null },
    ) {
        ledger.accept(LedgerEvent.Due(now, "$id.$key", 0))
        scheduler.submit(
            Utterance("$id.$key", priority, est, render = render, deadlineAtMs = deadline, validity = validity, stillValid = stillValid,
                followup = followup, estShortMs = short, speakWhen = { ww -> ww.vehicle?.driving != false }),
            now,
        )
    }

    private fun set(id: String, s: PromiseStatus, now: Long, note: String = "") {
        val prev = status[id]
        if (prev != s) {
            status[id] = s
            ledger.accept(LedgerEvent.StatusChanged(now, id, prev?.name ?: "NONE", s.name, note))
        }
    }

    companion object {
        /** 두 점 사이 거리(m, 하버사인) */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_008.8
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
