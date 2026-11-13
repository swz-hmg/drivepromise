import app.drivepromise.core.gate.GatePolicy
import kotlin.random.Random
import kotlin.math.*
fun main(args: Array<String>) {
    val n = args.getOrNull(0)?.toInt() ?: 100
    val scs = listOf(
        Scenario("도심 밀집", 150.0, 600.0, 30.0, 50.0, 0.5, 0.3, 1.0, 0.3),
        Scenario("도심", 300.0, 1200.0, 35.0, 60.0, 0.4, 0.3, 0.8, 0.2),
        Scenario("교외", 800.0, 3000.0, 50.0, 70.0, 0.3, 0.2, 0.5, 0.1))
    val vars = listOf("20 m 앞(1.3.5)" to GatePolicy(turnReachedM = 20), "도달 10m" to GatePolicy(turnReachedM = 10), "도달 0m" to GatePolicy(turnReachedM = 0))
    println("scenario\tvariant\tspoken%\texpired%\tp50\tp90\tp95\tcollide%\tturn%\tpost_after_turn%")
    for (sc in scs) for ((vn, gp) in vars) {
        val all = ArrayList<Pair<Result, List<Win>>>()
        for (i in 0 until n) { val d = genDrive(sc, Random(1000 + i)); val arr = genArrivals(d, Random(5000 + i), 1.0); all += run("gate", d, arr, gp).map { it to d.windows } }
        val sp = all.filter { it.first.startAt != null }
        val del = sp.map { it.first.startAt!! - it.first.arrival.t }
        fun share(k: Set<String>?) = 100.0 * sp.count { overlaps(it.first, it.second, k).isNotEmpty() } / sp.size
        val post = 100.0 * sp.count { (r, w) -> w.any { it.kind == "turn" && r.startAt!! >= it.b - 8000 && r.startAt!! < it.b } } / sp.size
        println(listOf(sc.name, vn, "%.1f".format(100.0*sp.size/all.size), "%.1f".format(100.0*all.count{it.first.outcome=="expired"}/all.size),
            "%.1f".format(pct(del,0.5)), "%.1f".format(pct(del,0.9)), "%.1f".format(pct(del,0.95)), "%.1f".format(share(null)), "%.1f".format(share(setOf("turn"))), "%.1f".format(post)).joinToString("\t"))
    }
}
