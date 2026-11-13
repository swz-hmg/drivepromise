import app.drivepromise.core.gate.GatePolicy
import kotlin.random.Random
import kotlin.math.*
// 지표: 회전 앞(접근) 겹침 · 방향지시등 · 내비 안내 · 급제동 · 회전 뒤 시작 간격 · 지연 · 만료
fun main(args: Array<String>) {
    val n = args.getOrNull(0)?.toInt() ?: 200
    val perMin = args.getOrNull(1)?.toDouble() ?: 0.5
    val scs = listOf(
        Scenario("도심 밀집", 150.0, 600.0, 30.0, 50.0, 0.5, 0.3, 1.0, 0.3),
        Scenario("도심", 300.0, 1200.0, 35.0, 60.0, 0.4, 0.3, 0.8, 0.2),
        Scenario("교외", 800.0, 3000.0, 50.0, 70.0, 0.3, 0.2, 0.5, 0.1))
    val pols = listOf(Triple("바로 말하기", "naive", GatePolicy()), Triple("설 때만", "stop_only", GatePolicy()),
                      Triple("운전약속 1.3.5(20 m)", "gate", GatePolicy(turnReachedM = 20)), Triple("회전점 기준(1.3.6)", "gate", GatePolicy(turnReachedM = 0)))
    println("trips=$n perMin=$perMin")
    println("scenario\tpolicy\tN\tspoken%\texpired%\tdigest%\tp50\tp90\tp95\tpreTurn%\tsignal%\tnav%\tbrake%\tanyExceptPost%\tpostStart_med\tpostStart_p10\tpost<8s%")
    for (sc in scs) {
        val drives = (0 until n).map { i -> genDrive(sc, Random(SEED_BASE + 1000 + i)).let { d -> d to genArrivals(d, Random(SEED_BASE + 5000 + i), perMin) } }
        for ((label, pol, gp) in pols) {
            val all = ArrayList<Pair<Result, Drive>>()
            for ((d, arr) in drives) all += run(pol, d, arr, gp).map { it to d }
            val sp = all.filter { it.first.startAt != null }
            val del = sp.map { it.first.startAt!! - it.first.arrival.t }
            fun ov(r: Result, d: Drive, pred: (Win) -> Boolean): Boolean { val s = r.startAt!!; val e = s + r.durMs; return d.windows.any { pred(it) && it.a < e && s < it.b } }
            val pre = sp.count { (r, d) -> ov(r, d) { it.kind == "turn" && true } .let { _ -> d.windows.any { w -> w.kind == "turn" && r.startAt!! < w.b - 8000 && w.a < r.startAt!! + r.durMs } } }
            val sig = sp.count { (r, d) -> ov(r, d) { it.kind == "turn_signal" } }
            val nav = sp.count { (r, d) -> ov(r, d) { it.kind == "nav_prompt" } }
            val brk = sp.count { (r, d) -> ov(r, d) { it.kind == "hard_brake" } }
            val any = sp.count { (r, d) -> d.windows.any { w -> val s = r.startAt!!; val e = s + r.durMs
                when (w.kind) { "turn" -> s < w.b - 8000 && w.a < e; else -> w.a < e && s < w.b } } }
            // 회전 뒤 시작 간격: 시작 시각 기준 직전 회전(15초 안)
            val gaps = sp.mapNotNull { (r, d) -> val s = r.startAt!!; d.windows.filter { it.kind == "turn" }.map { s - (it.b - 8000) }.filter { it in 0..15_000 }.minOrNull() }
            fun p(x: Int) = "%.1f".format(100.0 * x / sp.size)
            println(listOf(sc.name, label, all.size, p(sp.size).let { "%.1f".format(100.0 * sp.size / all.size) },
                "%.1f".format(100.0 * all.count { it.first.outcome == "expired" } / all.size),
                "%.1f".format(100.0 * all.count { it.first.outcome.startsWith("digest") } / all.size),
                "%.1f".format(pct(del, .5)), "%.1f".format(pct(del, .9)), "%.1f".format(pct(del, .95)),
                p(pre), p(sig), p(nav), p(brk), p(any),
                "%.1f".format(pct(gaps, .5)), "%.1f".format(pct(gaps, .1)), "%.1f".format(100.0 * gaps.count { it < 8000 } / max(1, gaps.size))).joinToString("\t"))
        }
    }
}
