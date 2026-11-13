import app.drivepromise.core.gate.GatePolicy
import app.drivepromise.core.model.*
import app.drivepromise.core.replay.*
import app.drivepromise.core.session.*
import app.drivepromise.core.source.Clock
import app.drivepromise.core.speech.*
import java.io.File
class FC(var t: Long) : Clock { override fun nowMs() = t }
fun demo(json: String, gp: GatePolicy): List<String> {
    val trip = ReplayParser.parse(json); val real = FC(0L)
    val player = ReplayPlayer(trip, real, speedup = 1.0)
    val said = mutableListOf<String>()
    val spk = object : Speaker { override fun speak(id: Long, text: String, usage: AudioUsage) {}; override fun stop(id: Long) {} }
    val five = listOf(Promise.HomeBeforeDark(), Promise.AvoidHighway(), Promise.RainCare(), Promise.RestEvery(), Promise.StayInZone(37.5665, 126.9780, 10_000))
    val ses = TripSession(five, spk, player.clock, policy = SessionPolicy(gate = gp), listener = object : TripSession.Listener {
        override fun onSpeak(step: Step.Speak, nowMs: Long) { said += "${step.key}@${"%.1f".format((nowMs - trip.meta.baseEpochMs) / 1000.0)}" }
    }, realClock = real)
    ses.attachReplay(player)
    var cur: Step.Speak? = null; var endAt = 0L; var n = 0
    val limit = (trip.endMs - trip.meta.baseEpochMs) / 100 + 600
    while (n < limit) {
        val c = cur; if (c != null && real.t >= endAt) { ses.onSpeechDone(c.id); cur = null }
        val s = ses.pumpReplay(player); if (s is Step.Speak) { cur = s; endAt = real.t + s.estMs }
        if (ses.phase == TripSession.Phase.ENDED && cur == null && ses.scheduler.pendingKeys.isEmpty()) break
        real.t += 100; n++
    }
    return said
}
fun main() {
    for (n in listOf("hospital_home", "rest_two_hours", "zone_edge", "highway_rain")) {
        val j = File("demo/trips/$n.json").readText()
        println("$n\n  20 m 앞(1.3.5) : ${demo(j, GatePolicy(turnReachedM = 20))}\n  회전점(1.3.6)  : ${demo(j, GatePolicy(turnReachedM = 0))}")
    }
}
