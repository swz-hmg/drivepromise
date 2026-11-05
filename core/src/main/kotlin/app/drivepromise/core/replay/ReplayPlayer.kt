package app.drivepromise.core.replay

import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.source.Clock
import app.drivepromise.core.source.LocationSource
import app.drivepromise.core.source.Place
import app.drivepromise.core.source.RouteOption
import app.drivepromise.core.source.RoutePlan
import app.drivepromise.core.source.RoutePlans
import app.drivepromise.core.source.RouteSource
import app.drivepromise.core.source.Sink
import app.drivepromise.core.source.VehicleSource

/**
 * 리플레이 재생기(시연 장치의 순수 부분) — 트립 파일의 이벤트를 가상 시계에 맞춰 세 원천(위치·차량·내비)으로 흘려 보냅니다.
 *
 * 가상 시각 = 트립 기준 시각(meta.start) + (흐른 실시간 × 배속). 앱은 100 ms마다 [pump]를 부르고, 세션의 시계로 [clock]을 씁니다.
 * 그래서 리플레이에서는 ‘해 지기 전 귀가’의 일몰도 트립 파일의 날짜·시각(예: 11/14 16:50 KST)으로 계산됩니다.
 * JVM 시험은 실시간 없이 [pump]에 가상 시각을 직접 넣습니다.
 */
class ReplayPlayer(
    val trip: ReplayTrip,
    private val real: Clock,
    val speedup: Double = trip.meta.speedup,
) {
    private val startReal = real.nowMs()
    private val snapshots = trip.vehicleSnapshots()
    private var li = 0
    private var vi = 0
    private var ni = 0
    private var locSink: Sink<GeoFix>? = null
    private var vehSink: Sink<VehicleSnapshot>? = null
    private var navSink: Sink<NaviEvent>? = null

    /** 세션에 넣는 가상 시계 */
    val clock: Clock = Clock { virtualAt(real.nowMs()) }

    fun virtualAt(realMs: Long): Long = trip.meta.baseEpochMs + ((realMs - startReal) * speedup).toLong()

    /** 모든 이벤트를 보냈고 트립 끝 시각을 지났는가 */
    val finished: Boolean get() = li >= trip.location.size && vi >= snapshots.size && ni >= trip.navi.size && lastPumped >= trip.endMs
    private var lastPumped = Long.MIN_VALUE

    /** 가상 시각 virtualNow까지의 이벤트를 시간 순으로 보냄(세 원천을 섞어서) → 보낸 개수 */
    fun pump(virtualNow: Long): Int {
        var n = 0
        while (true) {
            val tl = trip.location.getOrNull(li)?.tMillis ?: Long.MAX_VALUE
            val tv = snapshots.getOrNull(vi)?.tMillis ?: Long.MAX_VALUE
            val tn = trip.navi.getOrNull(ni)?.tMillis ?: Long.MAX_VALUE
            val m = minOf(tl, tv, tn)
            if (m > virtualNow || m == Long.MAX_VALUE) break
            when (m) {
                tv -> { vehSink?.accept(snapshots[vi]); vi++ }
                tl -> { locSink?.accept(trip.location[li]); li++ }
                else -> { navSink?.accept(trip.navi[ni]); ni++ }
            }
            n++
        }
        lastPumped = virtualNow
        return n
    }

    val location: LocationSource = object : LocationSource {
        override fun start(sink: Sink<GeoFix>): AutoCloseable {
            locSink = sink
            return AutoCloseable { if (locSink === sink) locSink = null }
        }
    }

    val vehicle: VehicleSource = object : VehicleSource {
        override fun start(sink: Sink<VehicleSnapshot>): AutoCloseable {
            vehSink = sink
            return AutoCloseable { if (vehSink === sink) vehSink = null }
        }
    }

    /** 내비 원천 — 경로 후보는 트립의 첫 trip 이벤트(ETA·남은 거리)로 추천 경로 하나를 만듦 */
    val route: RouteSource = object : RouteSource {
        override fun start(sink: Sink<NaviEvent>): AutoCloseable {
            navSink = sink
            return AutoCloseable { if (navSink === sink) navSink = null }
        }

        override fun planRoutes(dest: Place, options: List<RouteOption>, done: Sink<RoutePlans>): AutoCloseable {
            val t = trip.navi.filterIsInstance<NaviEvent.Trip>().firstOrNull()
            if (t == null) {
                done.accept(RoutePlans(emptyMap(), "NO_ROUTE_IN_REPLAY"))
            } else {
                val dur = ((t.etaEpochMs - t.tMillis) / 1000L).coerceAtLeast(1).toInt()
                done.accept(RoutePlans(mapOf(RouteOption.RECOMMENDED to RoutePlan(RouteOption.RECOMMENDED, t.remainM, dur))))
            }
            return AutoCloseable { }
        }
    }
}
