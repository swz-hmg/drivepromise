package app.drivepromise.core.replay

import app.drivepromise.core.model.CautionKind
import app.drivepromise.core.model.Door
import app.drivepromise.core.model.Gear
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.HeadlampMode
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.Turn
import app.drivepromise.core.model.TurnType
import app.drivepromise.core.model.VehicleDelta
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.model.Wiper
import app.drivepromise.core.model.merge
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * 리플레이 트립 스키마 0.2 (11/5 고정) — 시연(리플레이 장치)과 JVM 시험이 같은 파일을 씁니다.
 * t는 트립 시작부터의 초(소수 가능). 시각 = 기준 시각 + t. 기준 = parse(baseEpochMs) 또는 meta.start 또는 0.
 */
data class ReplayMeta(
    val name: String,
    val schema: String,
    val speedup: Double = 1.0,
    val simulated: Boolean = true,
    val originLat: Double? = null,
    val originLon: Double? = null,
    /** meta.start(ISO-8601, 오프셋 포함)를 읽은 값 */
    val startEpochMs: Long? = null,
    val baseEpochMs: Long = 0,
)

data class ReplayTrip(
    val meta: ReplayMeta,
    val location: List<GeoFix>,
    val navi: List<NaviEvent>,
    val vehicle: List<VehicleDelta>,
) {
    val endMs: Long
        get() = maxOf(location.lastOrNull()?.tMillis ?: meta.baseEpochMs, navi.lastOrNull()?.tMillis ?: meta.baseEpochMs, vehicle.lastOrNull()?.tMillis ?: meta.baseEpochMs)

    /** 부분 변경을 차례로 합친 전체 차량 상태 열 */
    fun vehicleSnapshots(): List<VehicleSnapshot> {
        var cur = VehicleSnapshot(meta.baseEpochMs)
        return vehicle.map { d -> cur.merge(d).also { cur = it } }
    }

    /** t 시각의 차량 상태(그 전까지의 변경을 모두 합침) */
    fun vehicleAt(tMillis: Long): VehicleSnapshot? {
        var cur: VehicleSnapshot? = null
        for (d in vehicle) {
            if (d.tMillis > tMillis) break
            cur = (cur ?: VehicleSnapshot(d.tMillis)).merge(d)
        }
        return cur
    }
}

object ReplayParser {
    const val SCHEMA = "drivepromise.trip/0.2"
    private val ACCEPTED = setOf(SCHEMA, "0.2")

    fun parse(json: String, baseEpochMs: Long? = null): ReplayTrip {
        val root = MiniJson.parse(json) as? Map<*, *> ?: bad("최상위", "객체여야 함")
        val m = root["meta"] as? Map<*, *> ?: bad("meta", "객체가 필요함")
        val schema = m.str("schema", "meta") ?: bad("meta.schema", "필요함")
        if (schema !in ACCEPTED) bad("meta.schema", "‘$schema’는 지원하지 않음(지원: $SCHEMA)")
        val start = m.str("start", "meta")?.let {
            runCatching { OffsetDateTime.parse(it) }.getOrNull() ?: bad("meta.start", "ISO-8601(오프셋 포함) 기대: $it")
        }
        val startMs = start?.toInstant()?.toEpochMilli()
        val base = baseEpochMs ?: startMs ?: 0L
        val origin = m["origin"] as? List<*>
        val meta = ReplayMeta(
            name = m.str("name", "meta") ?: bad("meta.name", "필요함"),
            schema = schema,
            speedup = m.num("speedup", "meta") ?: 1.0,
            simulated = m.bool("simulated", "meta") ?: true,
            originLat = (origin?.getOrNull(0) as? Number)?.toDouble(),
            originLon = (origin?.getOrNull(1) as? Number)?.toDouble(),
            startEpochMs = startMs,
            baseEpochMs = base,
        )
        fun at(e: Map<*, *>, path: String): Long {
            val t = e.num("t", path) ?: bad("$path.t", "필요함")
            if (t < 0) bad("$path.t", "0 이상이어야 함")
            return base + Math.round(t * 1000.0)
        }

        val loc = list(root, "location").mapIndexed { i, e ->
            val p = "location[$i]"
            GeoFix(
                tMillis = at(e, p),
                lat = e.num("lat", p) ?: bad("$p.lat", "필요함"),
                lon = e.num("lon", p) ?: bad("$p.lon", "필요함"),
                speedMps = (e.num("speedMps", p) ?: e.num("speedKph", p)?.div(3.6))?.toFloat(),
                bearingDeg = e.num("bearing", p)?.toFloat(),
                accuracyM = e.num("acc", p)?.toFloat(),
            )
        }.sortedBy { it.tMillis }

        val navi = list(root, "navi").mapIndexed { i, e ->
            val p = "navi[$i]"
            val t = at(e, p)
            when (val type = e.str("type", p) ?: bad("$p.type", "필요함")) {
                "trip" -> {
                    val etaSec = e.num("etaSec", p)
                    val etaLocal = e.str("etaLocal", p)
                    val eta = when {
                        etaSec != null -> base + Math.round(etaSec * 1000.0)
                        etaLocal != null -> {
                            if (start == null) bad("$p.etaLocal", "meta.start가 있어야 씀(아니면 etaSec)")
                            val lt = runCatching { LocalTime.parse(etaLocal) }.getOrNull() ?: bad("$p.etaLocal", "HH:mm 기대: $etaLocal")
                            var d = start.withHour(lt.hour).withMinute(lt.minute).withSecond(0).withNano(0)
                            if (d.isBefore(start)) d = d.plusDays(1)
                            base + (d.toInstant().toEpochMilli() - start.toInstant().toEpochMilli())
                        }
                        else -> bad(p, "trip에는 etaSec 또는 etaLocal이 필요함")
                    }
                    NaviEvent.Trip(t, eta, e.int("remainM", p) ?: bad("$p.remainM", "필요함"))
                }
                "tbt" -> NaviEvent.Tbt(t, e.int("distM", p) ?: bad("$p.distM", "필요함"), TurnType.of(e.str("turn", p) ?: bad("$p.turn", "필요함")), e.str("road", p), e.str("toward", p))
                "caution" -> {
                    val raw = e.str("kind", p) ?: bad("$p.kind", "필요함")
                    NaviEvent.Caution(t, CautionKind.of(raw), e.int("distM", p) ?: 0, raw)
                }
                "speedLimit" -> NaviEvent.SpeedLimit(t, e.int("kph", p) ?: bad("$p.kph", "필요함"))
                "arrived" -> NaviEvent.Arrived(t)
                "routeError" -> NaviEvent.RouteError(t, e.str("cause", p) ?: "UNKNOWN")
                else -> bad("$p.type", "알 수 없는 값 ‘$type’(trip·tbt·caution·speedLimit·arrived·routeError)")
            }
        }.sortedBy { it.tMillis }

        val veh = list(root, "vehicle").mapIndexed { i, e ->
            val p = "vehicle[$i]"
            VehicleDelta(
                tMillis = at(e, p),
                gear = e.str("gear", p)?.let { g -> Gear.of(g).also { if (it == Gear.UNKNOWN) bad("$p.gear", "P·R·N·D 기대: $g") } },
                speedMps = (e.num("speedMps", p) ?: e.num("speedKph", p)?.div(3.6))?.toFloat(),
                turnSignal = e.str("turnSignal", p)?.let { Turn.of(it) },
                brakeOn = e.bool("brake", p),
                steeringDeg = e.num("steeringDeg", p)?.toFloat(),
                wiper = e.str("wiper", p)?.let { w -> enumOr(w, Wiper.UNKNOWN) },
                headlamp = e.str("headlamp", p)?.let { h -> enumOr(h, HeadlampMode.UNKNOWN) },
                ignitionOn = e.bool("ignition", p),
                doorsOpen = (e["doorsOpen"] as? List<*>)?.map { d ->
                    Door.entries.firstOrNull { it.name.equals(d.toString(), ignoreCase = true) } ?: bad("$p.doorsOpen", "알 수 없는 문 ‘$d’")
                }?.toSet(),
            )
        }.sortedBy { it.tMillis }

        return ReplayTrip(meta, loc, navi, veh)
    }

    private inline fun <reified E : Enum<E>> enumOr(raw: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: fallback

    private fun list(root: Map<*, *>, key: String): List<Map<*, *>> {
        val v = root[key] ?: return emptyList()
        val l = v as? List<*> ?: bad(key, "배열이어야 함")
        return l.mapIndexed { i, e -> e as? Map<*, *> ?: bad("$key[$i]", "객체여야 함") }
    }

    private fun Map<*, *>.str(k: String, path: String): String? = when (val v = this[k]) {
        null -> null
        is String -> v
        else -> bad("$path.$k", "문자열 기대")
    }

    private fun Map<*, *>.num(k: String, path: String): Double? = when (val v = this[k]) {
        null -> null
        is Long -> v.toDouble()
        is Double -> v
        else -> bad("$path.$k", "숫자 기대")
    }

    private fun Map<*, *>.int(k: String, path: String): Int? = num(k, path)?.let { Math.round(it).toInt() }

    private fun Map<*, *>.bool(k: String, path: String): Boolean? = when (val v = this[k]) {
        null -> null
        is Boolean -> v
        else -> bad("$path.$k", "true/false 기대")
    }

    private fun bad(path: String, msg: String): Nothing = throw IllegalArgumentException("리플레이 $path: $msg")
}
