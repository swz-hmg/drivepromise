package app.drivepromise.platform

import ai.pleos.playground.vehicle.Vehicle
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
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 차량 상태 원천 — AAOS CarPropertyManager + Pleos Vehicle SDK 2.3.2 를 함께 씁니다. 둘 다 부분 변경([VehicleDelta])을
 * 이전 상태에 합쳐 전체 상태([VehicleSnapshot])로 보냅니다. 모두 main 스레드.
 *
 * 11/5 에뮬레이터 실측(run_1105_105602): CarPropertyManager 는 GEAR_SELECTION·PERF_VEHICLE_SPEED 만 등록되고
 * TURN_SIGNAL_STATE·IGNITION_STATE·WINDSHIELD_WIPERS_*·HEADLIGHTS_STATE 는 "propertyId is not in carPropertyConfig list"
 * (VHAL 에 없음). 반면 Pleos Vehicle SDK 는 TURN_SIGNAL_STATE 를 바로 주었고(첫 콜백 0), 문서상 Wiper(registerWiperState)·
 * Light(registerLowBeam/HighBeamLightState) 도 있습니다. 그래서 방향지시등·와이퍼·전조등은 Vehicle SDK 를 주 원천으로,
 * AAOS 는 되는 차종에서 보조로 둡니다(같은 값이 두 번 와도 합치면 그만). 시동 상태는 에뮬레이터에 없음 → null(세션은
 * 기어 P·도착·시간으로 끝냄). 없는 속성은 등록 실패만 기록하고 값은 UNKNOWN/null(계약 §1).
 *
 * 한 번 초기화한 뒤에는 세션이 바뀌어도(리플레이 ↔ 실차) 다시 초기화하지 않고 받는 쪽(sink)만 바꿉니다 — [shutdown] 때만 해제.
 */
class PleosVehicleSource(private val ctx: Context, private val main: Handler) : VehicleSource {
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var vehicle: Vehicle? = null
    private var sink: Sink<VehicleSnapshot>? = null
    private var inited = false
    private var snap = VehicleSnapshot(System.currentTimeMillis())
    private val callbacks = mutableListOf<CarPropertyManager.CarPropertyEventCallback>()
    private val lastArgs = HashMap<String, String>()
    private var lowBeamOn: Boolean? = null
    private var highBeamOn: Boolean? = null

    val current: VehicleSnapshot get() = snap

    /** 등록에 성공한 원천 이름(판정·화면용): aaos:gear, aaos:speed, sdk:TurnSignal.registerTurnSignalState … */
    val sources: List<String> get() = okSources.toList()
    private val okSources = LinkedHashSet<String>()

    override fun start(sink: Sink<VehicleSnapshot>): AutoCloseable {
        this.sink = sink
        if (!inited) {
            inited = true
            initCar()
            initVehicleSdk()
        } else {
            sink.accept(snap)   // 이미 아는 현재 상태를 새 세션에 바로 전달
        }
        return AutoCloseable { if (this.sink === sink) this.sink = null }
    }

    /** 서비스가 끝날 때만 — 차량 연결을 모두 해제 */
    fun shutdown() {
        sink = null
        try { callbacks.forEach { props?.unregisterCallback(it) } } catch (_: Throwable) {}
        callbacks.clear()
        try { vehicle?.release() } catch (_: Throwable) {}
        try { car?.disconnect() } catch (_: Throwable) {}
        vehicle = null; props = null; car = null
        inited = false
        okSources.clear()
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
            @Suppress("DEPRECATION")
            val ok = props?.registerCallback(cb, propId, rate) ?: false
            if (ok) { callbacks += cb; okSources += "aaos:$name" }
            L.log("prop_reg", "name" to name, "ok" to ok)
            if (ok) try {   // 현재 값으로 첫 상태를 채움(등록 때 초기 콜백이 안 오는 속성 대비) — 속도만 실수형
                val cur: Any = if (rate == CarPropertyManager.SENSOR_RATE_FAST) props!!.getFloatProperty(propId, 0) else props!!.getIntProperty(propId, 0)
                map(cur, System.currentTimeMillis())?.let { emit(it) }
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            L.log("prop_reg_error", "name" to name, "err" to e)
        }
    }

    // ---------------------------------------------------------------- Pleos Vehicle SDK(방향지시등·와이퍼·전조등 — 에뮬레이터의 주 원천)
    private fun initVehicleSdk() {
        try {
            val v = Vehicle(ctx.applicationContext)
            v.initialize()
            vehicle = v
            registerByReflection("TurnSignal", v.getTurnSignal(), setOf("registerTurnSignalState")) { _, s ->
                turnOfWords(s)?.let { emit(VehicleDelta(System.currentTimeMillis(), turnSignal = it)) }
            }
            registerByReflection("Wiper", domain(v, "getWiper"), setOf("registerWiperState")) { _, s ->
                wiperOfWords(s)?.let { emit(VehicleDelta(System.currentTimeMillis(), wiper = it)) }
            }
            registerByReflection("Light", domain(v, "getLight"), setOf("registerLowBeamLightState", "registerHighBeamLightState")) { fn, s ->
                val on = onOfWords(s) ?: return@registerByReflection
                if (fn.contains("High")) highBeamOn = on else lowBeamOn = on
                val mode = when { highBeamOn == true -> HeadlampMode.HIGH; lowBeamOn == true -> HeadlampMode.LOW; else -> HeadlampMode.OFF }
                emit(VehicleDelta(System.currentTimeMillis(), headlamp = mode))
            }
            L.log("vehicle_sdk", "ok" to true, "regs" to okSources.filter { it.startsWith("sdk:") }.joinToString("+") { it.removePrefix("sdk:") })
        } catch (e: Throwable) {
            L.log("vehicle_sdk_error", "err" to e)
        }
    }

    /** Vehicle 의 도메인 접근자(getWiper·getLight …)를 이름으로 호출 — SDK 판에 없으면 null */
    private fun domain(v: Vehicle, getter: String): Any? =
        try { v.javaClass.getMethod(getter).invoke(v) } catch (e: Throwable) { L.log("vsdk_domain_missing", "getter" to getter, "err" to (e.cause ?: e)); null }

    /**
     * register*(listener) 함수에 동적 리스너를 붙임(공식 샘플 VehicleApiExplorer 방식). 자바 리플렉션만 써서 난독화된
     * 구현 클래스에서도 동작합니다. 콜백은 (함수 이름, 인자 toString) 으로 받고, 인자가 바뀔 때만 기록합니다.
     * 11/6: 1.1.0 실측에서 Wiper.registerWiperState 를 못 찾음 → ① 리스너가 Kotlin 함수형(kotlin.jvm.functions.*)이어도 받고
     * ② 그래도 없으면 그 도메인의 register*·add* 함수 모양을 모두 기록(vsdk_methods)하고 ③ (영역, 리스너) 두 인자 꼴이면 앞 유리 영역으로 붙여 봄.
     * ④ 모든 꼴이 실패했을 때만 vsdk_reg_missing(1.2.2 — 1.2.1 실행에서 두 인자 꼴 등록 성공인데도 ‘없음’이 먼저 찍혀 판정이 ‘실패’로 셈).
     */
    private fun registerByReflection(domain: String, target: Any?, names: Set<String>, on: (String, String) -> Unit) {
        if (target == null) return
        val all = target.javaClass.methods
        val single = all.filter { it.name in names && it.parameterTypes.size == 1 && isListener(it.parameterTypes[0]) }
        if (single.isNotEmpty()) {
            for (m in single) attach(domain, target, m, on) { l -> arrayOf(l) }
            return
        }
        val regs = all.filter { (it.name.startsWith("register") || it.name.startsWith("add")) && it.parameterTypes.isNotEmpty() }
        L.log("vsdk_methods", "domain" to domain, "fns" to regs.joinToString("+") { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.name.substringAfterLast('.') } + ")" }.ifEmpty { "없음" }.take(700))
        // (영역, 리스너) 꼴 — 와이퍼는 창 영역 속성(AAOS WINDSHIELD_WIPERS_STATE)이라 영역 인자를 받을 수 있음
        val pair = all.filter { it.name in names && it.parameterTypes.size == 2 && it.parameterTypes.count { p -> isListener(p) } == 1 }
        var attached = 0
        for (m in pair) {
            val li = m.parameterTypes.indexOfFirst { isListener(it) }
            val other = m.parameterTypes[1 - li]
            val area: Any = areaArg(other) ?: continue
            if (attach(domain, target, m, on) { l -> if (li == 0) arrayOf(l, area) else arrayOf(area, l) }) attached++
        }
        // 1.2.2(11/6 실행): ‘없음’은 모든 꼴이 실패했을 때만 — 1.2.1은 한 인자 꼴이 없으면 먼저 적어서 등록 성공에도 판정이 ‘실패’로 셈
        if (attached == 0) L.log("vsdk_reg_missing", "domain" to domain, "want" to names.joinToString("+"), "tried" to pair.size)
    }

    /** 리스너로 볼 인자 — Pleos 리스너 인터페이스 또는 Kotlin 함수형 */
    private fun isListener(c: Class<*>): Boolean = c.isInterface && (c.name.startsWith("ai.pleos.") || c.name.startsWith("kotlin.jvm.functions.") || c.name.startsWith("kotlin.jvm.internal."))

    /**
     * 영역 인자 후보: 정수면 앞 유리(Pleos WindowValue 의 FRONT… 상수, 없으면 AAOS FRONT_WINDSHIELD=1), 열거형이면 FRONT/WINDSHIELD 가 든 값,
     * 그 밖의 클래스면 [areaObject] — 1.2.1: 11/6 실측 모양 registerWiperState(WindowArea, WiperStateListener),
     * 공식 문서 WindowArea(vararg values: WindowValue)(JVM 생성자는 WindowValue[]) · WindowValue 는 열거형(FRONT_WINDSHIELD …).
     */
    private fun areaArg(c: Class<*>): Any? {
        val v: Any? = when {
            c == Int::class.javaPrimitiveType || c == Int::class.javaObjectType -> frontWindowArea()
            c.isEnum -> frontEnum(c)
            else -> areaObject(c)
        }
        L.log("vsdk_area", "type" to c.name.substringAfterLast('.'), "value" to (v?.let { describe(it) } ?: "없음"))
        return v
    }

    private fun frontEnum(c: Class<*>): Any? =
        c.enumConstants?.firstOrNull { e -> (e as Enum<*>).name.uppercase().let { it.contains("FRONT") && (it.contains("WIND") || it.contains("SHIELD")) } }
            ?: c.enumConstants?.firstOrNull { e -> (e as Enum<*>).name.uppercase().contains("FRONT") }
            ?: c.enumConstants?.firstOrNull()

    /** 영역 객체 만들기: ① 정적 필드·companion 에 같은 타입 값(FRONT… 우선) ② 생성자(열거형 배열 = vararg · 열거형 하나 · 정수) */
    private fun areaObject(c: Class<*>): Any? {
        try {
            val statics = c.fields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && c.isAssignableFrom(it.type) }.mapNotNull { f -> runCatching { f.get(null) }.getOrNull()?.let { f.name to it } }
            (statics.firstOrNull { it.first.uppercase().contains("FRONT") } ?: statics.firstOrNull())?.let { return it.second }
        } catch (_: Throwable) {}
        for (k in c.constructors.sortedBy { it.parameterTypes.size }) {
            val p = k.parameterTypes
            if (p.size != 1) continue
            val t = p[0]
            try {
                when {
                    t.isArray && t.componentType.isEnum -> {
                        val one = frontEnum(t.componentType) ?: continue
                        val arr = java.lang.reflect.Array.newInstance(t.componentType, 1)
                        java.lang.reflect.Array.set(arr, 0, one)
                        return k.newInstance(arr)
                    }
                    t.isEnum -> return k.newInstance(frontEnum(t) ?: continue)
                    t == Int::class.javaPrimitiveType || t == Int::class.javaObjectType -> return k.newInstance(frontWindowArea())
                }
            } catch (e: Throwable) {
                L.log("vsdk_area_error", "type" to c.name.substringAfterLast('.'), "ctor" to t.simpleName, "err" to (e.cause ?: e))
            }
        }
        return null
    }

    private fun describe(v: Any): String = when (v) {
        is Array<*> -> v.joinToString("+") { it.toString() }
        else -> runCatching {   // WindowArea(values=[FRONT_WINDSHIELD]) 처럼 보이게 — values 가 배열이면 풀어서
            val vals = v.javaClass.methods.firstOrNull { it.name == "getValues" && it.parameterTypes.isEmpty() }?.invoke(v)
            if (vals is Array<*>) "${v.javaClass.simpleName}(${vals.joinToString("+")})" else v.toString()
        }.getOrDefault(v.toString())
    }.take(80)

    private fun frontWindowArea(): Int {
        for (cn in listOf("ai.pleos.playground.vehicle.constant.area.WindowValue", "ai.pleos.playground.vehicle.constant.area.AreaValue")) {
            try {
                val c = Class.forName(cn)
                val f = c.fields.firstOrNull { it.type == Int::class.javaPrimitiveType && it.name.uppercase().contains("FRONT") && (it.name.uppercase().contains("WIND") || it.name.uppercase().contains("SHIELD")) }
                    ?: c.fields.firstOrNull { it.type == Int::class.javaPrimitiveType && it.name.uppercase().contains("FRONT") }
                if (f != null) return f.getInt(null)
            } catch (_: Throwable) {}
        }
        return 1   // VehicleAreaWindow.FRONT_WINDSHIELD
    }

    private fun attach(domain: String, target: Any, m: Method, on: (String, String) -> Unit, args: (Any) -> Array<Any>): Boolean {
        val lc = m.parameterTypes.first { isListener(it) }
        val listener = Proxy.newProxyInstance(lc.classLoader, arrayOf(lc)) { proxy, method, a ->
            when (method.name) {
                "equals" -> proxy === a?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "DP(${lc.simpleName})"
                else -> {
                    val s = a?.joinToString(" ") { it.toString() } ?: ""
                    val k = "$domain.${method.name}"
                    if (lastArgs[k] != s) { lastArgs[k] = s; L.log("vsdk", "domain" to domain, "cb" to method.name, "args" to s.take(200)) }
                    if (!method.name.startsWith("onFail") && !method.name.startsWith("onError")) on(m.name, s)
                    if (lc.name.startsWith("kotlin.jvm.functions.")) Unit else null   // 함수형(invoke)은 Unit 을 돌려줘야 함
                }
            }
        }
        return try {
            m.invoke(target, *args(listener))
            okSources += "sdk:$domain.${m.name}"
            L.log("vsdk_reg", "domain" to domain, "fn" to m.name, "argc" to m.parameterTypes.size)
            true
        } catch (e: Throwable) {
            L.log("vsdk_reg_error", "domain" to domain, "fn" to m.name, "err" to (e.cause ?: e))
            false
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

        private fun tokens(s: String): List<String> = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

        /** 인자 문자열의 숫자 값: `value=3` 이 있으면 그것, 아니면 홀로 선 숫자들 — 열거형 이름이 없을 때의 마지막 수단 */
        private fun nums(s: String): List<Int> {
            Regex("value\\s*=\\s*(-?\\d+)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { return listOf(it) }
            return tokens(s).mapNotNull { it.toIntOrNull() }
        }

        /** 방향지시등 콜백 인자(열거형 이름·숫자 어느 쪽이든) → Turn */
        fun turnOfWords(s: String): Turn? {
            val l = s.lowercase()
            return when {
                l.contains("hazard") || l.contains("emergency") || l.contains("both") -> Turn.HAZARD
                l.contains("left") -> Turn.LEFT
                l.contains("right") -> Turn.RIGHT
                l.contains("off") || l.contains("none") -> Turn.OFF
                else -> nums(s).firstOrNull()?.let { turnOf(it) }
            }
        }

        /** 와이퍼 상태 콜백 인자 → Wiper. 이름을 모르는 값은 ‘0/off 가 아니면 켜짐(LOW)’ — 실측(11/8) 뒤 표를 좁힘 */
        fun wiperOfWords(s: String): Wiper? {
            val t = tokens(s)
            if (t.isEmpty()) return null
            val n = nums(s)
            return when {
                t.any { it == "off" || it == "none" || it == "stop" || it == "stopped" } -> Wiper.OFF
                t.any { it == "high" || it == "fast" } -> Wiper.HIGH
                t.any { it == "low" || it == "slow" || it == "on" } -> Wiper.LOW
                t.any { it.startsWith("int") || it == "mist" || it == "auto" } -> Wiper.INTERMITTENT
                n.isNotEmpty() -> if (n.all { it == 0 }) Wiper.OFF else Wiper.LOW
                else -> null
            }
        }

        /** 켜짐/꺼짐 콜백 인자(ON/OFF·true/false·1/0·열거형) → Boolean */
        fun onOfWords(s: String): Boolean? {
            val t = tokens(s)
            if (t.isEmpty()) return null
            val n = nums(s)
            return when {
                t.any { it == "off" || it == "false" || it == "none" } -> false
                t.any { it == "on" || it == "true" || it == "low" || it == "high" || it == "auto" || it == "dim" } -> true
                n.isNotEmpty() -> n.any { it != 0 }
                else -> null
            }
        }
    }
}
