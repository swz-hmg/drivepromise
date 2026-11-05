package app.drivepromise.platform

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.TurnSignal
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import app.drivepromise.L
import app.drivepromise.core.model.Gear
import app.drivepromise.core.model.HeadlampMode
import app.drivepromise.core.model.Turn
import app.drivepromise.core.model.VehicleDelta
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.model.Wiper
import app.drivepromise.core.model.merge
import app.drivepromise.core.source.Sink
import app.drivepromise.core.source.VehicleSource
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions

/**
 * 차량 상태 원천 — AAOS CarPropertyManager(기어·속도·방향지시등·시동·와이퍼·전조등) + Pleos Vehicle SDK 2.3.2(방향지시등 보조).
 * 속성 콜백마다 부분 변경을 이전 상태에 합쳐 전체 상태([VehicleSnapshot])를 보냅니다. 모두 main 스레드.
 * 에뮬레이터·차종마다 없는 속성은 등록 오류만 기록하고 그 값은 UNKNOWN/null로 둡니다(계약 §1).
 */
class PleosVehicleSource(private val ctx: Context, private val main: Handler) : VehicleSource {
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var vehicle: Vehicle? = null
    private var sink: Sink<VehicleSnapshot>? = null
    private var snap = VehicleSnapshot(System.currentTimeMillis())
    private val callbacks = mutableListOf<CarPropertyManager.CarPropertyEventCallback>()

    val current: VehicleSnapshot get() = snap

    override fun start(sink: Sink<VehicleSnapshot>): AutoCloseable {
        this.sink = sink
        initCar()
        initVehicleSdk()
        return AutoCloseable { stop() }
    }

    private fun stop() {
        sink = null
        try { callbacks.forEach { props?.unregisterCallback(it) } } catch (_: Throwable) {}
        callbacks.clear()
        try { vehicle?.release() } catch (_: Throwable) {}
        try { car?.disconnect() } catch (_: Throwable) {}
        vehicle = null; props = null; car = null
    }

    private fun emit(d: VehicleDelta) {
        if (main.looper.isCurrentThread) deliver(d) else main.post { deliver(d) }
    }

    private fun deliver(d: VehicleDelta) {
        snap = snap.merge(d)
        sink?.accept(snap)
    }

    // ---------------------------------------------------------------- AAOS 속성
    private fun initCar() {
        try {
            val c = Car.createCar(ctx)
            car = c
            props = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            reg("gear", id("GEAR_SELECTION", VehiclePropertyIds.GEAR_SELECTION), CarPropertyManager.SENSOR_RATE_ONCHANGE) { v, t ->
                VehicleDelta(t, gear = gearOf(v as? Int))
            }
            reg("speed", id("PERF_VEHICLE_SPEED", VehiclePropertyIds.PERF_VEHICLE_SPEED), CarPropertyManager.SENSOR_RATE_FAST) { v, t ->
                VehicleDelta(t, speedMps = (v as? Number)?.toFloat()?.let { kotlin.math.abs(it) })
            }
            reg("turn", id("TURN_SIGNAL_STATE", VehiclePropertyIds.TURN_SIGNAL_STATE), CarPropertyManager.SENSOR_RATE_ONCHANGE) { v, t ->
                VehicleDelta(t, turnSignal = turnOf(v as? Int))
            }
            reg("ignition", id("IGNITION_STATE", VehiclePropertyIds.IGNITION_STATE), CarPropertyManager.SENSOR_RATE_ONCHANGE) { v, t ->
                VehicleDelta(t, ignitionOn = (v as? Int)?.let { it >= 4 })   // 4 ON · 5 START, 그 외(LOCK·OFF·ACC)는 꺼짐
            }
            reg("wiper", id("WINDSHIELD_WIPERS_STATE", 322964421), CarPropertyManager.SENSOR_RATE_ONCHANGE) { v, t ->
                VehicleDelta(t, wiper = when (v as? Int) { 2 -> Wiper.OFF; 3 -> Wiper.LOW; else -> null })
            }
            reg("wiper_sw", id("WINDSHIELD_WIPERS_SWITCH", 322964423), CarPropertyManager.SENSOR_RATE_ONCHANGE) { v, t ->
                VehicleDelta(t, wiper = when (val s = v as? Int) { null -> null; 2 -> Wiper.OFF; in 3..8 -> Wiper.INTERMITTENT; in 9..10 -> Wiper.LOW; in 11..13 -> Wiper.HIGH; 14 -> Wiper.LOW; else -> null })
            }
            reg("headlamp", id("HEADLIGHTS_STATE", VehiclePropertyIds.HEADLIGHTS_STATE), CarPropertyManager.SENSOR_RATE_ONCHANGE) { v, t ->
                VehicleDelta(t, headlamp = when (v as? Int) { 0 -> HeadlampMode.OFF; 1 -> HeadlampMode.LOW; else -> null })
            }
            L.log("vehicle_aaos", "ok" to true, "props" to callbacks.size)
        } catch (e: Throwable) {
            L.log("vehicle_aaos_error", "err" to e)
        }
    }

    private fun id(name: String, fallback: Int): Int =
        try { VehiclePropertyIds::class.java.getField(name).getInt(null) } catch (_: Throwable) { fallback }

    private fun reg(name: String, propId: Int, rate: Float, map: (Any?, Long) -> VehicleDelta?) {
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val d = try { map(value.value, System.currentTimeMillis()) } catch (e: Throwable) { L.log("prop_map_error", "name" to name, "err" to e); null }
                if (d != null) emit(d)
            }

            override fun onErrorEvent(propId: Int, zone: Int) {
                L.log("prop_error", "name" to name, "zone" to zone)
            }
        }
        try {
            val ok = props?.registerCallback(cb, propId, rate) ?: false
            if (ok) callbacks += cb
            L.log("prop_reg", "name" to name, "ok" to ok)
            if (ok) try {   // 현재 값으로 첫 상태를 채움(등록 때 초기 콜백이 안 오는 속성 대비) — 속도만 실수형
                val cur: Any = if (rate == CarPropertyManager.SENSOR_RATE_FAST) props!!.getFloatProperty(propId, 0) else props!!.getIntProperty(propId, 0)
                map(cur, System.currentTimeMillis())?.let { emit(it) }
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            L.log("prop_reg_error", "name" to name, "err" to e)
        }
    }

    // ---------------------------------------------------------------- Pleos Vehicle SDK(방향지시등 보조 — AAOS 콜백이 없는 차종 대비)
    private fun initVehicleSdk() {
        try {
            val v = Vehicle(ctx.applicationContext)
            v.initialize()
            vehicle = v
            registerByReflection("TurnSignal", v.getTurnSignal(), TurnSignal::class) { args ->
                val s = args?.joinToString(" ") { it.toString() } ?: ""
                val t = turnOfWords(s)
                if (t != null) emit(VehicleDelta(System.currentTimeMillis(), turnSignal = t))
            }
            L.log("vehicle_sdk", "ok" to true)
        } catch (e: Throwable) {
            L.log("vehicle_sdk_error", "err" to e)
        }
    }

    /** register* 함수에 동적 리스너를 붙임(공식 샘플 VehicleApiExplorer 방식) */
    private fun registerByReflection(domain: String, target: Any, api: KClass<*>, on: (Array<out Any?>?) -> Unit) {
        for (f in api.declaredMemberFunctions.filter { it.name.startsWith("register") }) {
            val params = f.parameters.filter { it.kind == KParameter.Kind.VALUE }
            val lp = params.firstOrNull {
                (it.type.classifier as? KClass<*>)?.qualifiedName?.startsWith("ai.pleos.playground.vehicle.listener.") == true
            }
            if (lp == null || params.size != 1) continue
            val lc = (lp.type.classifier as KClass<*>).java
            val listener = Proxy.newProxyInstance(lc.classLoader, arrayOf(lc)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "DP(${lc.simpleName})"
                    else -> { if (!method.name.startsWith("onFail")) on(args); null }
                }
            }
            try {
                f.call(target, listener)
                L.log("vsdk_reg", "domain" to domain, "fn" to f.name)
            } catch (e: Throwable) {
                L.log("vsdk_reg_error", "domain" to domain, "fn" to f.name, "err" to (e.cause ?: e))
            }
        }
    }

    companion object {
        fun gearOf(g: Int?): Gear? = when (g) {
            null -> null
            4 -> Gear.P
            2 -> Gear.R
            1 -> Gear.N
            8 -> Gear.D
            else -> if (g >= 16) Gear.D else Gear.UNKNOWN   // GEAR_1..GEAR_9 는 수동 변속 단
        }

        fun turnOf(t: Int?): Turn? = when (t) { null -> null; 0 -> Turn.OFF; 1 -> Turn.RIGHT; 2 -> Turn.LEFT; else -> Turn.OFF }

        fun turnOfWords(s: String): Turn? {
            val l = s.lowercase()
            return when {
                l.contains("hazard") || l.contains("emergency") || l.contains("both") -> Turn.HAZARD
                l.contains("left") -> Turn.LEFT
                l.contains("right") -> Turn.RIGHT
                l.contains("off") || l.contains("none") -> Turn.OFF
                else -> Regex("\\b([0-2])\\b").find(l)?.groupValues?.get(1)?.toIntOrNull()?.let { turnOf(it) }
            }
        }
    }
}
