package app.drivepromise.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import app.drivepromise.L
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.source.LocationSource
import app.drivepromise.core.source.Sink

/**
 * 위치 대체 원천(fallback) — Android LocationManager(fused가 있으면 fused, 아니면 gps). 1초 간격. 권한이 없으면 아무것도 보내지 않고 기록만 남김
 * (리플레이 시연은 위치 권한 없이도 동작). S5-4(11/4): 에뮬레이터 geo fix가 gps provider에 그대로 반영됨.
 * 1.1.0부터 주 원천은 [PleosFusedLocationSource](Pleos FusedLocation SDK)이고, 이 클래스는 그쪽이 안 될 때만 켜집니다.
 */
class PleosLocationSource(private val ctx: Context, private val main: Handler) : LocationSource {
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null

    override fun start(sink: Sink<GeoFix>): AutoCloseable {
        val granted = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            L.log("location", "ok" to false, "why" to "no_permission")
            return AutoCloseable { }
        }
        try {
            val m = ctx.getSystemService(LocationManager::class.java)
            lm = m
            val provider = when {
                m.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                m.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider == null) {
                L.log("location", "ok" to false, "why" to "no_provider")
                return AutoCloseable { }
            }
            val l = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    sink.accept(fix(loc))
                }
            }
            listener = l
            m.requestLocationUpdates(provider, 1_000L, 0f, l, main.looper)
            m.getLastKnownLocation(provider)?.let { sink.accept(fix(it)) }
            L.log("location", "ok" to true, "provider" to provider)
        } catch (e: Throwable) {
            L.log("location_error", "err" to e)
        }
        return AutoCloseable {
            try { listener?.let { lm?.removeUpdates(it) } } catch (_: Throwable) {}
            listener = null
        }
    }

    private fun fix(l: Location) = GeoFix(
        tMillis = System.currentTimeMillis(),
        lat = l.latitude,
        lon = l.longitude,
        speedMps = if (l.hasSpeed()) l.speed else null,
        bearingDeg = if (l.hasBearing()) l.bearing else null,
        accuracyM = if (l.hasAccuracy()) l.accuracy else null,
    )
}
