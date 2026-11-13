// 운전약속 발화 타이밍 시뮬레이션(빌드에 들어가지 않는 분석 도구 — docs/TIMING_EVAL.md) — 실제 코어(QuietGate·SpeechScheduler)를 그대로 써서
// 같은 주행·같은 말 도착에 대해 정책 3가지(바로 말하기 / 운전약속 게이트 / 설 때만)를 비교합니다.
import app.drivepromise.core.gate.QuietGate
import app.drivepromise.core.model.*
import app.drivepromise.core.speech.*
import kotlin.math.*
import kotlin.random.Random

data class Scenario(val name: String, val segMin: Double, val segMax: Double, val vMinKph: Double, val vMaxKph: Double,
                    val pLightEnd: Double, val pLightMid: Double, val laneChangePerKm: Double, val hardBrakePerKm: Double)

class Win(val a: Long, val b: Long, val kind: String)

class Drive(val ticks: List<Tick>, val tbts: Map<Long, NaviEvent.Tbt>, val windows: List<Win>, val endMs: Long)
class Tick(val t: Long, val v: Double, val turn: Turn, val gear: Gear)

const val DT = 100L
var GT_PRE_EXTRA = (System.getenv("GT_PRE_EXTRA") ?: "0").toLong()
var SEED_BASE = (System.getenv("SEED_BASE") ?: "0").toInt()

fun genDrive(sc: Scenario, rnd: Random, driveSec: Int = 1200): Drive {
    val ticks = ArrayList<Tick>(); val tbts = HashMap<Long, NaviEvent.Tbt>(); val wins = ArrayList<Win>()
    var t = 0L; var v = 0.0
    // 출발 전 10초 정차(P)
    while (t < 10_000) { ticks += Tick(t, 0.0, Turn.OFF, Gear.P); t += DT }
    val types = listOf(TurnType.LEFT, TurnType.LEFT, TurnType.RIGHT, TurnType.RIGHT, TurnType.RIGHT, TurnType.U_TURN, TurnType.STRAIGHT)
    var postSignalUntil = 0L
    var laneUntil = 0L; var laneDir = Turn.LEFT
    var brakeUntil = 0L
    while (t < driveSec * 1000L + 10_000) {
        val segLen = sc.segMin + rnd.nextDouble() * (sc.segMax - sc.segMin)
        val vCruise = (sc.vMinKph + rnd.nextDouble() * (sc.vMaxKph - sc.vMinKph)) / 3.6
        val type = types[rnd.nextInt(types.size)]
        val stopAtEnd = rnd.nextDouble() < sc.pLightEnd
        val midStop = if (rnd.nextDouble() < sc.pLightMid && segLen > 400) segLen * (0.3 + 0.4 * rnd.nextDouble()) else -1.0
        var midDone = midStop < 0
        var d = segLen
        var lastTbt = -1_000_000L
        var prompted300 = segLen < 300; var prompted100 = segLen < 100
        var holdUntil = 0L
        var endStopped = false
        val vTurn = if (type == TurnType.STRAIGHT) vCruise else 4.2
        while (true) {
            // TBT: 구간 시작 즉시 + 5초마다
            if (t - lastTbt >= 5_000) { tbts[t] = NaviEvent.Tbt(t, d.roundToInt(), type); lastTbt = t }
            // 목표 속도
            val toMid = if (!midDone) (d - (segLen - midStop)).let { segLen - midStop; d - (segLen - midStop) } else Double.MAX_VALUE
            val stopTarget = if (stopAtEnd && !endStopped) 0.0 else vTurn
            var vt = min(vCruise, sqrt(stopTarget * stopTarget + 2 * 1.8 * max(0.0, d - 2)))
            if (!midDone) vt = min(vt, sqrt(2 * 1.8 * max(0.0, toMid - 2)))
            if (t < holdUntil) vt = 0.0
            // 급제동 사건
            if (t >= brakeUntil && v > 8 && rnd.nextDouble() < sc.hardBrakePerKm * v * DT / 1000.0 / 1000.0) {
                brakeUntil = t + 1_200; wins += Win(t, t + 2_000, "hard_brake")
            }
            if (t < brakeUntil) v = max(0.0, v - 4.5 * DT / 1000.0)
            else if (v < vt) v = min(vt, v + 1.5 * DT / 1000.0)
            else v = max(vt, v - 2.5 * DT / 1000.0)
            // 정지·대기
            if (!midDone && toMid <= 3 && v < 0.3) { v = 0.0; midDone = true; holdUntil = t + (15_000 + rnd.nextLong(45_000)) }
            if (stopAtEnd && !endStopped && d <= 3 && v < 0.3) { v = 0.0; endStopped = true; holdUntil = t + (15_000 + rnd.nextLong(45_000)) }
            // 차선 변경(방향지시등)
            if (t >= laneUntil && d > 150 && v > 6 && rnd.nextDouble() < sc.laneChangePerKm * v * DT / 1000.0 / 1000.0) { laneUntil = t + 4_000; laneDir = if (rnd.nextBoolean()) Turn.LEFT else Turn.RIGHT }
            val signalTurn = type != TurnType.STRAIGHT && d <= max(30.0, 3 * max(v, 4.0))
            val sig = when {
                signalTurn -> if (type == TurnType.RIGHT) Turn.RIGHT else Turn.LEFT
                t < postSignalUntil -> Turn.LEFT
                t < laneUntil -> laneDir
                else -> Turn.OFF
            }
            ticks += Tick(t, v, sig, Gear.D)
            // 내비 안내(실제): 300·100 m를 지날 때 2.5초
            if (!prompted300 && d <= 300) { prompted300 = true; wins += Win(t - 500, t + 3_000, "nav_prompt") }
            if (!prompted100 && d <= 100) { prompted100 = true; wins += Win(t - 500, t + 3_000, "nav_prompt") }
            d -= v * DT / 1000.0
            t += DT
            if (d <= 0) {
                if (type != TurnType.STRAIGHT) {
                    val pre = (if (type == TurnType.LEFT || type == TurnType.U_TURN) 10_000L else 8_000L) + GT_PRE_EXTRA
                    wins += Win(t - pre, t + 8_000, "turn")
                    postSignalUntil = t + 2_000
                }
                break
            }
        }
    }
    val endMs = t
    // 도착 뒤 P
    var tt = t; while (tt < t + 5_000) { ticks += Tick(tt, 0.0, Turn.OFF, Gear.P); tt += DT }
    // 방향지시등 창(주행 중)
    var start: Long? = null
    for (k in ticks) {
        val on = k.turn != Turn.OFF && k.v > 0.5
        if (on && start == null) start = k.t
        if (!on && start != null) { wins += Win(start, k.t, "turn_signal"); start = null }
    }
    return Drive(ticks, tbts, wins, endMs)
}

data class Arrival(val t: Long, val kind: String, val chars: Int, val deadline: Long?)

fun genArrivals(d: Drive, rnd: Random, perMin: Double): List<Arrival> {
    val out = ArrayList<Arrival>()
    var t = 20_000L
    while (true) {
        t += (-ln(1 - rnd.nextDouble()) / (perMin / 60_000.0)).toLong()
        if (t > d.endMs - 30_000) break
        val r = rnd.nextDouble()
        out += when {
            r < 0.4 -> Arrival(t, "WHILE_STATE", 14 + rnd.nextInt(27), null)
            r < 0.8 -> Arrival(t, "UNTIL_DEADLINE", 14 + rnd.nextInt(27), t + 20_000 + rnd.nextLong(100_000))
            else -> Arrival(t, "INFO", 14 + rnd.nextInt(13), null)
        }
    }
    return out
}

class Result(val arrival: Arrival, val startAt: Long?, val durMs: Long, val outcome: String)

fun actualMs(chars: Int) = 790L + 105L * chars   // 11/8 실측 회귀(0.79초 + 105 ms/자)

fun run(policy: String, d: Drive, arr: List<Arrival>, gp: app.drivepromise.core.gate.GatePolicy = app.drivepromise.core.gate.GatePolicy()): List<Result> {
    val gate = QuietGate(gp)
    var cur: Tick? = null
    val open = object : QuietWindows { override fun normal(nowMs: Long, needMs: Long) = true; override fun stopWindow(nowMs: Long) = true }
    val stopOnly = object : QuietWindows {
        override fun normal(nowMs: Long, needMs: Long) = cur?.let { it.gear == Gear.D && it.v < 0.5 } ?: false
        override fun stopWindow(nowMs: Long) = normal(nowMs, 0)
    }
    val win: QuietWindows = when (policy) { "naive" -> open; "stop_only" -> stopOnly; else -> gate }
    val dropped = HashMap<String, String>()
    val ledger = app.drivepromise.core.ledger.LedgerSink { e ->
        when (e) {
            is app.drivepromise.core.ledger.LedgerEvent.Dropped -> dropped[e.key] = e.reason
            is app.drivepromise.core.ledger.LedgerEvent.Digested -> dropped[e.key] = "digest:" + e.reason
            else -> {}
        }
    }
    val q = SpeechScheduler(QuietPolicy(), win, ledger)
    val byKey = HashMap<String, Arrival>()
    val started = HashMap<String, Long>()
    var ai = 0
    var speakingId: Long? = null; var speakingKey = ""; var endAt = 0L
    for (k in d.ticks) {
        cur = k
        val snap = VehicleSnapshot(k.t, gear = k.gear, speedMps = k.v.toFloat(), turnSignal = k.turn)
        gate.update(k.t, snap)
        d.tbts[k.t]?.let { gate.onNavi(it) }
        while (ai < arr.size && arr[ai].t <= k.t) {
            val a = arr[ai]; val key = "u$ai"; byKey[key] = a; ai++
            val text = "가".repeat(a.chars)
            val u = when (a.kind) {
                "WHILE_STATE" -> Utterance(key, Priority.PROMISE, 4_000, render = { text }, deadlineAtMs = a.t, validity = Validity.WHILE_STATE, estShortMs = 2_000, speakWhen = { it.vehicle?.driving != false })
                "UNTIL_DEADLINE" -> Utterance(key, Priority.PROMISE, 4_000, render = { text }, deadlineAtMs = a.deadline, validity = Validity.UNTIL_DEADLINE, estShortMs = 2_000, speakWhen = { it.vehicle?.driving != false })
                else -> Utterance(key, Priority.INFO, 2_500, render = { text }, speakWhen = { it.vehicle?.driving != false })
            }
            q.submit(u, k.t)
        }
        if (speakingId != null && k.t >= endAt) { q.spoken(speakingId!!, k.t); speakingId = null }
        if (k.gear == Gear.P && k.t >= d.endMs && !q.tripEnded) q.endTrip(k.t)
        val s = q.tick(World(k.t, snap), voiceFree = true)
        if (s is Step.Speak) { speakingId = s.id; speakingKey = s.key; started[s.key] = k.t; endAt = k.t + actualMs(s.text.length) }
    }
    return byKey.entries.sortedBy { it.value.t }.map { (key, a) ->
        val st = started[key]
        Result(a, st, actualMs(a.chars), if (st != null) "spoken" else (dropped[key] ?: "pending"))
    }
}

fun overlaps(r: Result, wins: List<Win>, kinds: Set<String>? = null): Set<String> {
    val s = r.startAt ?: return emptySet(); val e = s + r.durMs
    return wins.filter { (kinds == null || it.kind in kinds) && it.a < e && s < it.b }.map { it.kind }.toSet()
}

fun pct(xs: List<Long>, p: Double): Double = if (xs.isEmpty()) Double.NaN else xs.sorted()[min(xs.size - 1, (p * (xs.size - 1)).roundToInt())] / 1000.0

fun main(args: Array<String>) {
    val nTrips = args.getOrNull(0)?.toInt() ?: 200
    val perMin = args.getOrNull(1)?.toDouble() ?: 1.0
    val scs = listOf(
        Scenario("도심 밀집", 150.0, 600.0, 30.0, 50.0, 0.5, 0.3, 1.0, 0.3),
        Scenario("도심", 300.0, 1200.0, 35.0, 60.0, 0.4, 0.3, 0.8, 0.2),
        Scenario("교외", 800.0, 3000.0, 50.0, 70.0, 0.3, 0.2, 0.5, 0.1),
    )
    println("trips=$nTrips perMin=$perMin")
    println("scenario\tpolicy\tN\tspoken%\texpired%\tdigest%\tdelay_p50\tdelay_p90\tdelay_p95\tcollide%\tturn%\tsignal%\tnav%\tbrake%\twinShare%\tturnsPerKm")
    for (sc in scs) {
        val rows = HashMap<String, MutableList<Result>>()
        val wrows = HashMap<String, MutableList<Pair<Result, List<Win>>>>()
        var winTime = 0L; var driveTime = 0L; var turns = 0; var distM = 0.0
        for (i in 0 until nTrips) {
            val rnd = Random(1000 + i)
            val d = genDrive(sc, rnd)
            val arr = genArrivals(d, Random(5000 + i), perMin)
            // 고부하 창이 주행 시간에서 차지하는 비율(겹침 구간 합집합)
            val drv = d.ticks.filter { it.gear == Gear.D }
            driveTime += drv.size * DT
            winTime += drv.count { k -> d.windows.any { it.a <= k.t && k.t < it.b } } * DT
            turns += d.windows.count { it.kind == "turn" }
            distM += drv.sumOf { it.v * DT / 1000.0 }
            for (pol in listOf("naive", "gate", "stop_only")) {
                val res = run(pol, d, arr)
                rows.getOrPut(pol) { mutableListOf() } += res
                wrows.getOrPut(pol) { mutableListOf() } += res.map { it to d.windows }
            }
        }
        for (pol in listOf("naive", "gate", "stop_only")) {
            val rs = rows[pol]!!; val n = rs.size
            val spoken = wrows[pol]!!.filter { it.first.startAt != null }
            val delays = spoken.map { it.first.startAt!! - it.first.arrival.t }
            fun share(kinds: Set<String>?) = 100.0 * spoken.count { overlaps(it.first, it.second, kinds).isNotEmpty() } / max(1, spoken.size)
            println(listOf(sc.name, pol, n,
                "%.1f".format(100.0 * spoken.size / n),
                "%.1f".format(100.0 * rs.count { it.outcome == "expired" } / n),
                "%.1f".format(100.0 * rs.count { it.outcome.startsWith("digest") } / n),
                "%.1f".format(pct(delays, 0.5)), "%.1f".format(pct(delays, 0.9)), "%.1f".format(pct(delays, 0.95)),
                "%.1f".format(share(null)), "%.1f".format(share(setOf("turn"))), "%.1f".format(share(setOf("turn_signal"))),
                "%.1f".format(share(setOf("nav_prompt"))), "%.1f".format(share(setOf("hard_brake"))),
                "%.1f".format(100.0 * winTime / driveTime), "%.2f".format(turns / (distM / 1000.0))).joinToString("\t"))
        }
    }
}
