package app.drivepromise.platform

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.SystemClock
import app.drivepromise.L
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.source.LocationSource
import app.drivepromise.core.source.Sink
import kotlin.math.abs

/**
 * 위치 원천(주) — Pleos FusedLocation SDK 1.1.3. 공식 샘플과 같은 순서로 켭니다.
 *   ① isSupported() → ② 권한 pleos.car.permission.FUSED_LOCATION → ③ initialize()(한 번) → ④ registerFusedLocationCallback + getLastKnownLocation
 *
 * 대체(fallback): 지원 안 함 · 권한 없음 · 등록 오류 · SDK onError · 첫 좌표가 [FIRST_FIX_TIMEOUT_MS] 안에 안 옴 · 좌표가 [PLEOS_STALE_MS] 넘게 끊김
 *   → [fallback](Android LocationManager, 1초)을 함께 켭니다. 둘 다 돌 때는 Pleos 좌표가 [PLEOS_FRESH_MS] 안에 있었으면
 *   대체 좌표를 버립니다(두 원천의 좌표가 번갈아 들어와 코어의 ‘멀어지는 중’ 판단이 흔들리지 않게).
 *
 * 약속 구역: 판정은 코어가 거리로 합니다(결정적 · 리플레이와 같은 경로). Pleos 근접 알림(ENTER/EXIT)은 [watchZone]으로
 *   같은 구역을 플랫폼이 따로 확인한 신호 — 기록 · 화면 표시 · 코어 판정과의 일치 기록에만 쓰고 코어 결정은 바꾸지 않습니다(이중 확인).
 *
 * 스레드: SDK 콜백이 어느 스레드에서 올지 문서에 없으므로 모두 [main]으로 넘겨 코어(단일 스레드)에 넣습니다.
 * 기록: 좌표는 logcat(DP)에만 소수 4자리(약 11 m)로 남기고, 기기에 저장하는 기록(ledger)에는 넣지 않습니다.
 */
class PleosFusedLocationSource(
    private val ctx: Context,
    private val main: Handler,
    private val fallback: LocationSource,
) : LocationSource {
    enum class Active { NONE, PLEOS, FALLBACK }

    /** 지금 코어에 좌표를 넣고 있는 원천(정차 화면 표시 · status 명령) */
    var active: Active = Active.NONE
        private set

    /** Pleos 근접 알림이 마지막으로 알려 준 약속 구역 안(true)/밖(false). 아직 모르면 null */
    var zoneInside: Boolean? = null
        private set

    /** 이번 세션에서 받은 좌표 수 */
    var pleosFixes = 0
        private set
    var fallbackFixes = 0
        private set

    private var fused: FusedLocation? = null
    private var initialized = false
    private var subscribed = false
    private var sink: Sink<GeoFix>? = null
    @Volatile private var gen = 0                 // start()마다 +1 — 닫힌 세션에 늦게 도착한 좌표·타이머를 버림
    private var fallbackCloser: AutoCloseable? = null
    private var lastPleosAt = 0L
    private var startedAt = 0L
    private var lastLog: Pair<GeoFix, Long>? = null

    @Volatile private var alertGen = 0            // watchZone()마다 +1
    private var alertId: String? = null

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            val g = gen
            main.post { onPleos(location, g) }
        }

        override fun onError(exception: Exception) {
            val g = gen
            main.post { onPleosError(exception, g) }
        }
    }

    // ---------------------------------------------------------------- LocationSource
    override fun start(sink: Sink<GeoFix>): AutoCloseable {
        val g = ++gen
        this.sink = sink
        active = Active.NONE
        pleosFixes = 0
        fallbackFixes = 0
        lastPleosAt = 0L
        lastLog = null
        startedAt = SystemClock.elapsedRealtime()
        val why = startPleos()
        if (why != null) startFallback(why, g) else watch(g)
        return AutoCloseable { stop(g) }
    }

    /** 5초마다: 첫 좌표가 [FIRST_FIX_TIMEOUT_MS] 안에 없거나, Pleos 좌표가 [PLEOS_STALE_MS] 넘게 끊기면 대체를 켬 */
    private fun watch(g: Int) {
        main.postDelayed({
            if (g != gen) return@postDelayed
            if (fallbackCloser == null) {
                val now = SystemClock.elapsedRealtime()
                if (pleosFixes == 0 && now - startedAt >= FIRST_FIX_TIMEOUT_MS) startFallback("no_pleos_fix_${FIRST_FIX_TIMEOUT_MS / 1000}s", g)
                else if (pleosFixes > 0 && now - lastPleosAt >= PLEOS_STALE_MS) startFallback("pleos_stale_${PLEOS_STALE_MS / 1000}s", g)
            }
            watch(g)
        }, 5_000L)
    }

    /** 서비스가 끝날 때 한 번 — 근접 알림 해제 · 콜백 해제 · SDK release */
    fun shutdown() {
        gen++
        alertGen++
        sink = null
        val f = fused
        alertId?.let { id -> runCatching { f?.removeProximityAlert(id) } }
        alertId = null
        if (subscribed) runCatching { f?.unregisterFusedLocationCallback(listener) }
        subscribed = false
        closeFallback()
        if (initialized) runCatching { f?.release() }
        initialized = false
        fused = null
        active = Active.NONE
        L.log("location", "s" to "shutdown")
    }

    /** Pleos 경로를 켬 — 성공이면 null, 아니면 대체로 넘어갈 이유 */
    private fun startPleos(): String? {
        val f = fused ?: try {
            FusedLocation(ctx.applicationContext).also { fused = it }
        } catch (e: Throwable) {
            L.log("location", "ok" to false, "src" to "pleos", "why" to "create_error", "err" to e)
            return "create_error"
        }
        val supported = try {
            f.isSupported()
        } catch (e: Throwable) {
            L.log("location", "ok" to false, "src" to "pleos", "why" to "support_error", "err" to e)
            return "support_error"
        }
        if (!supported) {
            L.log("location", "ok" to false, "src" to "pleos", "why" to "unsupported")
            return "unsupported"
        }
        if (ctx.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            L.log("location", "ok" to false, "src" to "pleos", "why" to "no_permission", "perm" to "FUSED_LOCATION")
            return "no_permission"
        }
        return try {
            if (!initialized) {
                f.initialize()
                initialized = true
            }
            if (!subscribed) {
                f.registerFusedLocationCallback(listener)
                subscribed = true
            }
            f.getLastKnownLocation(listener)
            L.log("location", "ok" to true, "src" to "pleos", "s" to "registered", "provider" to "pleos_fused", "sdk" to SDK)
            null
        } catch (e: Throwable) {
            L.log("location", "ok" to false, "src" to "pleos", "why" to "register_error", "err" to e)
            "register_error"
        }
    }

    private fun stop(g: Int) {
        if (g != gen) return          // 이미 다른 세션이 이어받음
        gen++
        sink = null
        if (subscribed) runCatching { fused?.unregisterFusedLocationCallback(listener) }
        subscribed = false
        closeFallback()
        active = Active.NONE
    }

    private fun onPleos(location: LocationData?, g: Int) {
        if (g != gen) return
        val s = sink ?: return
        if (location == null) {
            if (pleosFixes == 0) L.log("loc_null", "src" to "pleos")
            return
        }
        val lat = (location.latitude as? Number)?.toDouble()
        val lon = (location.longitude as? Number)?.toDouble()
        if (lat == null || lon == null || !valid(lat, lon)) return
        pleosFixes++
        lastPleosAt = SystemClock.elapsedRealtime()
        if (active != Active.PLEOS) {
            L.log("loc_src", "src" to "pleos", "prev" to active, "n" to pleosFixes)
            active = Active.PLEOS
        }
        val heading = (location.heading as? Number)?.toFloat()
        val f = GeoFix(
            tMillis = System.currentTimeMillis(),
            lat = lat,
            lon = lon,
            speedMps = null,      // LocationData에는 속도가 없음 — 속도는 차량 신호(CarPropertyManager)로 받음
            bearingDeg = heading?.takeIf { it.isFinite() && it >= 0f && it <= 360f },
            accuracyM = null,     // confidence는 거리(m)가 아님 — 기록에만 남김
        )
        logFix("pleos", f, location.confidence)
        s.accept(f)
    }

    private fun onPleosError(e: Exception, g: Int) {
        L.log("location_error", "src" to "pleos", "err" to e)
        if (g == gen && pleosFixes == 0) startFallback("pleos_error", g)
    }

    // ---------------------------------------------------------------- 대체(LocationManager)
    private fun startFallback(why: String, g: Int) {
        if (g != gen || fallbackCloser != null) return
        val s = sink ?: return
        L.log("location", "s" to "fallback", "why" to why)
        fallbackCloser = fallback.start(Sink { f ->
            if (g != gen) return@Sink
            val pleosFresh = pleosFixes > 0 && SystemClock.elapsedRealtime() - lastPleosAt < PLEOS_FRESH_MS
            if (pleosFresh) return@Sink
            fallbackFixes++
            if (active != Active.FALLBACK) {
                L.log("loc_src", "src" to "lm", "prev" to active, "why" to why)
                active = Active.FALLBACK
            }
            logFix("lm", f, null)
            s.accept(f)
        })
    }

    private fun closeFallback() {
        fallbackCloser?.let { runCatching { it.close() } }
        fallbackCloser = null
    }

    // ---------------------------------------------------------------- 약속 구역 근접 알림(이중 확인)
    /**
     * Pleos 근접 알림을 약속 구역(중심 · 반경)에 겁니다. ENTER/EXIT가 오면 [onSignal](안이면 true).
     * Pleos 경로가 준비되지 않았으면(지원 안 함 · 권한 없음) 아무것도 하지 않습니다. 돌려준 것을 닫으면 해제.
     */
    fun watchZone(lat: Double, lon: Double, radiusM: Int, onSignal: (Boolean) -> Unit): AutoCloseable {
        val f = fused
        if (f == null || !initialized) {
            L.log("geofence", "s" to "skip", "why" to "pleos_not_ready")
            return AutoCloseable { }
        }
        val g = ++alertGen
        alertId = null
        zoneInside = null
        val l = object : ProximityAlertListener {
            override fun onRegistered(alertId: String) {
                main.post {
                    if (g == alertGen) {
                        this@PleosFusedLocationSource.alertId = alertId
                        L.log("geofence", "s" to "registered", "id" to alertId)
                    } else {
                        runCatching { f.removeProximityAlert(alertId) }   // 이미 닫힌 구역 알림이 늦게 등록됨 → 바로 해제
                    }
                }
            }

            override fun onTransition(event: ProximityAlertEvent) {
                main.post {
                    val mine = alertId
                    if (g != alertGen || (mine != null && event.alertId != mine)) return@post
                    val tr = event.transition
                    val inside = when (tr) {
                        ProximityTransition.ENTER -> true
                        ProximityTransition.EXIT -> false
                        else -> return@post
                    }
                    zoneInside = inside
                    L.log("geofence", "s" to "transition", "tr" to tr.name, "id" to event.alertId)
                    onSignal(inside)
                }
            }

            override fun onError(exception: Exception) {
                main.post { if (g == alertGen) L.log("geofence", "s" to "error", "err" to exception) }
            }
        }
        try {
            f.addProximityAlert(
                ProximityAlertRequest(
                    latitude = lat,
                    longitude = lon,
                    radiusMeters = radiusM.toFloat(),
                    transitionTypes = setOf(ProximityTransition.ENTER, ProximityTransition.EXIT),
                ),
                l,
            )
            L.log("geofence", "s" to "add", "lat" to r3(lat), "lon" to r3(lon), "r" to radiusM)
        } catch (e: Throwable) {
            L.log("geofence", "s" to "error", "stage" to "add", "err" to e)
            return AutoCloseable { }
        }
        return AutoCloseable {
            if (g == alertGen) {
                alertGen++
                val id = alertId
                alertId = null
                zoneInside = null
                if (id != null) runCatching { f.removeProximityAlert(id) }
                L.log("geofence", "s" to "removed", "id" to id)
            }
        }
    }

    // ---------------------------------------------------------------- 기록
    /** 좌표 기록은 줄임: 원천의 첫 좌표 · 25 m 넘게 움직임 · 30초마다 */
    private fun logFix(src: String, f: GeoFix, conf: Any?) {
        val now = SystemClock.elapsedRealtime()
        val prev = lastLog
        val moved = prev == null || abs(prev.first.lat - f.lat) > 0.000225 || abs(prev.first.lon - f.lon) > 0.000285
        val first = (src == "pleos" && pleosFixes == 1) || (src == "lm" && fallbackFixes == 1)
        if (!first && !moved && prev != null && now - prev.second < 30_000L) return
        lastLog = f to now
        L.log("loc_fix", "src" to src, "lat" to r3(f.lat), "lon" to r3(f.lon),   // 1.3.0(C15): 기록 좌표는 소수 3자리(약 100 m)
            "n" to (if (src == "pleos") pleosFixes else fallbackFixes), "hd" to f.bearingDeg, "conf" to conf)
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        const val SDK = "FusedLocation-1.1.3"
        const val FIRST_FIX_TIMEOUT_MS = 15_000L
        const val PLEOS_FRESH_MS = 3_000L
        const val PLEOS_STALE_MS = 10_000L

        private fun valid(lat: Double, lon: Double) =
            lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

        private fun r3(x: Double) = String.format(java.util.Locale.US, "%.3f", x)
    }
}
