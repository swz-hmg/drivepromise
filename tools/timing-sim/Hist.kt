import app.drivepromise.core.gate.GatePolicy
import kotlin.random.Random
fun main() {
    val sc = Scenario("도심 밀집", 150.0, 600.0, 30.0, 50.0, 0.5, 0.3, 1.0, 0.3)
    val drives = (0 until 300).map { i -> genDrive(sc, Random(1000 + i)).let { d -> d to genArrivals(d, Random(5000 + i), 0.5) } }
    var turns = 0; var drvMin = 0.0
    for ((d, _) in drives) { turns += d.windows.count { it.kind == "turn" }; drvMin += d.ticks.count { it.gear == app.drivepromise.core.model.Gear.D } * 0.1 / 60 }
    println("turns=$turns drivingMin=${"%.0f".format(drvMin)} turnsPer10min=${"%.1f".format(turns / drvMin * 10)}")
    for ((label, pol, gp) in listOf(Triple("naive", "naive", GatePolicy()), Triple("20m(1.3.5)", "gate", GatePolicy(turnReachedM = 20)), Triple("fix", "gate", GatePolicy(turnReachedM = 0)))) {
        val bins = IntArray(16)
        var n = 0
        for ((d, arr) in drives) for (r in run(pol, d, arr, gp)) {
            val s = r.startAt ?: continue
            val g = d.windows.filter { it.kind == "turn" }.map { s - (it.b - 8000) }.filter { it in 0..15_999 }.minOrNull() ?: continue
            bins[(g / 1000).toInt()]++; n++
        }
        println("$label n=$n " + bins.joinToString(","))
    }
}
