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
 * 가상 시각은 구간마다 기울기가 다른 직선입니다: 주행 중 = 배속(예: 60), 듣는 동안 = 0(멈춤), 주행이 끝난 뒤 = 1(실시간).
 * 앱은 100 ms마다 [pump]를 부르고, 세션의 시계로 [clock]을 씁니다.
 * 그래서 리플레이에서는 ‘해 지기 전 귀가’의 일몰도 트립 파일의 날짜·시각(예: 11/14 16:50 KST)으로 계산됩니다.
 * JVM 시험은 실시간 없이 [pump]에 가상 시각을 직접 넣습니다.
 */
class ReplayPlayer(
    val trip: ReplayTrip,
    private val real: Clock,
    val speedup: Double = trip.meta.speedup,
) {
    private val snapshots = trip.vehicleSnapshots()
    private var li = 0
    private var vi = 0
    private var ni = 0
    private var locSink: Sink<GeoFix>? = null
    private var vehSink: Sink<VehicleSnapshot>? = null
    private var navSink: Sink<NaviEvent>? = null

    // 가상 시계 = 기준점(anchorVirtual) + (실시간 − anchorReal) × 기울기. 기울기가 바뀔 때마다 기준점을 옮김
    private var anchorReal = real.nowMs()
    private var anchorVirtual = trip.meta.baseEpochMs
    private var holding = false
    private var ended = false
    private var heldSince: Long? = null
    private var heldTotal = 0L
    private val rate: Double get() = when { holding -> 0.0; ended -> 1.0; else -> speedup }

    /** 세션에 넣는 가상 시계 */
    val clock: Clock = Clock { virtualAt(real.nowMs()) }

    fun virtualAt(realMs: Long): Long = anchorVirtual + ((realMs - anchorReal) * rate).toLong()

    private fun rebase() {
        val r = real.nowMs()
        anchorVirtual = virtualAt(r)
        anchorReal = r
    }

    /**
     * 멈춤 켜기/끄기(같은 값이면 아무것도 안 함) — [TripSession.pumpReplay]가 세션의 listening 으로 부름(11/6 1.2.1).
     * 60배속에서 5초 듣기 = 가상 5분이라 질문 뒤 장면(대답 → 이어지는 주행)이 사라지던 문제를 막음. 실차(REAL)에는 해당 없음.
     */
    fun hold(on: Boolean) {
        if (on == holding) return
        rebase()
        holding = on
        val r = real.nowMs()
        val h = heldSince
        if (on) heldSince = r else if (h != null) { heldTotal += (r - h).coerceAtLeast(0); heldSince = null }
    }

    /**
     * 주행이 끝난 뒤엔 가상 시계를 실시간(1배)으로 — 60배속 그대로면 요약 첫 줄을 말하는 4초가 가상 4분이 되어
     * 둘째 줄이 늦은 알림 규칙(INFO 60초)에 걸려 사라짐(11/6 S82에서 발견). 세션이 주행 끝에 부름. 되돌리지 않음.
     */
    fun realTimeAfterEnd() {
        if (ended) return
        rebase()
        ended = true
    }

    /** 지금 멈춰 있는가 */
    val held: Boolean get() = holding

    /** 멈춘 실시간 합(ms) — 기록·시험용 */
    val heldMs: Long get() = heldTotal + (heldSince?.let { (real.nowMs() - it).coerceAtLeast(0) } ?: 0L)

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
