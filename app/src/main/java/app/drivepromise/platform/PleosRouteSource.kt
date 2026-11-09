package app.drivepromise.platform

import app.drivepromise.core.privacy.LogMask
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import app.drivepromise.L
import app.drivepromise.core.model.CautionKind
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.TurnType
import app.drivepromise.core.source.Place
import app.drivepromise.core.source.RouteOption
import app.drivepromise.core.source.RoutePlan
import app.drivepromise.core.source.RoutePlans
import app.drivepromise.core.source.RouteSource
import app.drivepromise.core.source.Sink

/**
 * 내비 원천 — Pleos NaviHelper 3.0.5. 콜백은 이름 + 인자 toString(data class)으로 받아 코어의 [NaviEvent]로 바꿉니다.
 * S5-3·G1-B에서 확인한 모양(2026-11-04):
 *   onRouteStarted(RouteStartInfo(duration=60, distance=50, tollFare=0, name=…)) · onRouteStateInfo(RouteStateInfo(naviMode=ROUTE_GUIDING, routeOption=RECOMMENDED, …))
 *   onTBTInfo([TBTInfo(type=TURN_ARRIVE, distance=50.0, description=도착)]) · onDrivingInfo(DrivingInfo(destination=DrivingState(duration=60, distance=50, …)))
 *   onNavigationStatus(ROUTE_DRIVE) · onError(…) · onRouteCancelled · onCurrentRoadSpeedLimit(0 …, 매초 — 11/5 실측)
 *
 * 출발 전 계획([planRoutes])은 requestRoute로 안내를 시작하고 첫 RouteStartInfo(거리·시간)를 추천 경로 후보 하나로 돌려줍니다 —
 * 옵션별 경로 ‘검색만’ 하는 API는 SDK 문서를 확인한 뒤(11/8) 붙입니다. 경로 실패(CAUSE_SEARCH_ROUTE 등)는 RouteError로.
 */
class PleosRouteSource(private val ctx: Context, private val main: Handler) : RouteSource {
    private var navi: NaviHelper? = null
    private var sink: Sink<NaviEvent>? = null
    private var listener: NaviHelperEventListener? = null
    private var planDone: Sink<RoutePlans>? = null
    private var planTimeout: Runnable? = null
    private var planReqAt = 0L
    private var lastSpeedLimit: Int? = null
    private var inited = false
    private val lastArgs = HashMap<String, String>()

    /** 1.3.2(P1-10): 처음 연결이 실패했으면(navi 없음) 다시 — 다시 시도했으면 true */
    fun retryIfDown(): Boolean {
        if (!inited || navi != null) return false
        init()
        return true
    }

    /** 한 번 초기화한 뒤에는 세션이 바뀌어도 NaviHelper 를 다시 만들지 않고 받는 쪽(sink)만 바꿉니다 — [shutdown] 때만 해제 */
    override fun start(sink: Sink<NaviEvent>): AutoCloseable {
        this.sink = sink
        if (!inited) { inited = true; init() }
        else lastSpeedLimit?.let { sink.accept(NaviEvent.SpeedLimit(System.currentTimeMillis(), it)) }   // 1.3.0(C11): 새 세션에 지금 도로 정보를 다시 줌(같은 값은 콜백이 다시 오지 않음)
        return AutoCloseable { if (this.sink === sink) this.sink = null }
    }

    /** 서비스가 끝날 때만 */
    fun shutdown() {
        sink = null
        planDone?.let { finishPlan(RoutePlans(emptyMap(), "SHUTDOWN")) }
        try { navi?.release() } catch (_: Throwable) {}
        navi = null
        inited = false
    }

    private fun init() {
        try {
            val h = NaviHelper(ctx.applicationContext)
            h.initialize()
            val l = Reflect.proxyOf(NaviHelperEventListener::class.java, "DPNaviListener") { m, args ->
                val s = args?.joinToString(" ") { it.toString() } ?: ""
                if (lastArgs[m] != s) {   // 같은 값이 매초 오는 콜백(onCurrentRoadSpeedLimit 0 …)은 바뀔 때만 기록
                    lastArgs[m] = s
                    L.log("navi", "cb" to m, "args" to LogMask.navi(m, args?.firstOrNull(), s))   // 1.3.1(P0-9): 개인 장소 콜백은 개수만 · 그 밖의 콜백도 이름·주소는 글자 수만, 좌표는 소수 3자리(LogMask)
                }
                main.post { onCallback(m, s) }
            }
            h.addListener(l)
            listener = l
            navi = h
            L.log("navi_init", "ok" to true, "sdk" to "navihelper-3.0.5")
        } catch (e: Throwable) {
            L.log("navi_error", "err" to e)
        }
    }

    private fun emit(e: NaviEvent) {
        sink?.accept(e)
    }

    /** 콜백 이름·인자 문자열 → NaviEvent (main 스레드) */
    internal fun onCallback(m: String, s: String) {
        val now = System.currentTimeMillis()
        when {
            m == "onRouteStarted" -> {
                val dur = Reflect.num(s, "duration")?.toInt()
                val dist = Reflect.num(s, "distance")?.toInt()
                if (dur != null && dist != null) {
                    emit(NaviEvent.Trip(now, now + dur * 1_000L, dist))
                    finishPlan(RoutePlans(mapOf(RouteOption.RECOMMENDED to RoutePlan(RouteOption.RECOMMENDED, dist, dur))))
                }
            }
            m == "onCurrentRoadSpeedLimit" -> {   // 매초 현재 도로 제한속도(없으면 0) — 11/5 에뮬레이터에서 확인한 콜백 이름
                val lim = Regex("-?\\d+").find(s)?.value?.toIntOrNull() ?: return
                // 1.3.0(C11): 0(정보 없음)으로 바뀐 것도 전달 — 고속도로를 나온 뒤 상태가 남지 않게(처음부터 0이면 보내지 않음)
                if (lim >= 0 && lim != (lastSpeedLimit ?: 0)) { lastSpeedLimit = lim; emit(NaviEvent.SpeedLimit(now, lim)) }
            }
            m == "onDrivingInfo" || m == "onRouteStateInfo" -> {
                val dur = Reflect.num(s, "duration")?.toInt()
                val dist = Reflect.num(s, "distance")?.toInt()
                if (dur != null && dist != null && dur > 0) emit(NaviEvent.Trip(now, now + dur * 1_000L, dist))
                Reflect.num(s, "speedLimit")?.toInt()?.let { lim -> if (lim > 0 && lim != lastSpeedLimit) { lastSpeedLimit = lim; emit(NaviEvent.SpeedLimit(now, lim)) } }
            }
            m == "onTBTInfo" -> {
                val items = Regex("TBTInfo\\((.*?)\\)(?=, TBTInfo\\(|\\]|$)").findAll(s).map { it.groupValues[1] }.toList()
                val first = items.firstOrNull() ?: return
                val type = Reflect.word(first, "type") ?: "OTHER"
                val dist = Reflect.num(first, "distance")?.toInt() ?: return
                val road = Reflect.text(first, "roadName") ?: Reflect.text(first, "nextRoadName")
                val toward = Reflect.text(first, "description")   // 1.3.0: 갈림길 방면(11/6 실측 ‘판교IC 방면’) — 고속도로 사전 예고(ADV-01)
                emit(NaviEvent.Tbt(now, dist, turnOf(type), road, toward))
                if (type.contains("ARRIVE", ignoreCase = true) && dist <= 20) emit(NaviEvent.Arrived(now))
            }
            m.contains("Arriv", ignoreCase = true) || m.contains("Destination", ignoreCase = true) && s.contains("ARRIV", ignoreCase = true) -> emit(NaviEvent.Arrived(now))
            m == "onNavigationStatus" -> if (s.contains("ARRIV", ignoreCase = true)) emit(NaviEvent.Arrived(now))
            m == "onRouteCancelled" -> {
                val after = now - planReqAt
                if (planDone != null && after in 0 until REPLACED_GRACE_MS) {
                    // 1.2.1: 안내 중인 경로가 있을 때 새 requestRoute를 하면 먼저 ‘앞 경로 취소’가 옴(11/6 실행: 139 ms 뒤 취소 → 5.8초 뒤 새 경로 시작).
                    // 이것을 실패로 끝내지 않고 새 경로(onRouteStarted)를 조금 더 기다림 — 안 오면 그때 취소로 끝냄
                    L.log("route_replaced", "afterMs" to after, "waitMs" to AFTER_CANCEL_WAIT_MS)
                    planTimeout?.let { main.removeCallbacks(it) }
                    val to = Runnable { finishPlan(RoutePlans(emptyMap(), "ROUTE_CANCELLED")) }
                    planTimeout = to
                    main.postDelayed(to, AFTER_CANCEL_WAIT_MS)
                } else {
                    emit(NaviEvent.RouteError(now, "ROUTE_CANCELLED"))
                    finishPlan(RoutePlans(emptyMap(), "ROUTE_CANCELLED"))
                }
            }
            m == "onError" -> {
                val cause = Reflect.word(s, "cause") ?: Reflect.word(s, "code") ?: s.take(60).ifEmpty { "UNKNOWN" }
                emit(NaviEvent.RouteError(now, cause))
                finishPlan(RoutePlans(emptyMap(), cause))
            }
            m.contains("Caution", ignoreCase = true) || m.contains("Safety", ignoreCase = true) -> {
                val type = Reflect.word(s, "type") ?: return
                emit(NaviEvent.Caution(now, CautionKind.of(type), Reflect.num(s, "distance")?.toInt() ?: 0, type))
            }
        }
    }

    override fun planRoutes(dest: Place, options: List<RouteOption>, done: Sink<RoutePlans>): AutoCloseable {
        val h = navi
        if (h == null) {
            done.accept(RoutePlans(emptyMap(), "NAVI_NOT_READY"))
            return AutoCloseable { }
        }
        planDone?.let { finishPlan(RoutePlans(emptyMap(), "REPLACED")) }
        planDone = done
        planReqAt = System.currentTimeMillis()
        val to = Runnable { finishPlan(RoutePlans(emptyMap(), "TIMEOUT_30S")) }
        planTimeout = to
        main.postDelayed(to, 30_000)
        try {
            h.requestRoute(RouteInfo(latitude = dest.lat, longitude = dest.lon, poiId = dest.poiId ?: "", poiName = dest.name, poiSubId = "0", address = ""))
            L.log("route_req", "ok" to true, "nameLen" to dest.name.length)   // 1.3.1(P0-9): 목적지 이름은 글자 수만
        } catch (e: Throwable) {
            L.log("route_error", "err" to e)
            finishPlan(RoutePlans(emptyMap(), "REQUEST_FAILED"))
        }
        return AutoCloseable { if (planDone === done) { planDone = null; planTimeout?.let { main.removeCallbacks(it) } } }
    }

    private fun finishPlan(p: RoutePlans) {
        val d = planDone ?: return
        planDone = null
        planTimeout?.let { main.removeCallbacks(it) }
        planTimeout = null
        d.accept(p)
    }

    companion object {
        /** 1.3.1(P0-9): 개인 장소가 담기는 NaviHelper 콜백 — 기록에는 개수만 */
        // 1.3.1: 개인 장소 콜백 목록은 LogMask.PRIVATE_CB 로 옮김
        /** requestRoute 뒤 이 시간 안의 onRouteCancelled 는 ‘앞 경로가 새 요청으로 바뀜’으로 봄(실패로 끝내지 않음) */
        const val REPLACED_GRACE_MS = 3_000L
        /** 그때 새 경로 시작을 더 기다리는 시간 — 넘으면 ROUTE_CANCELLED 로 끝냄(11/6 실측: 5.8초 뒤 시작) */
        const val AFTER_CANCEL_WAIT_MS = 15_000L

        /** NaviHelper TBT type 이름 → 코어 회전 종류(이름에 든 말로 판단 — 버전마다 상수가 조금 달라도 동작) */
        fun turnOf(type: String): TurnType {
            val t = type.uppercase()
            return when {
                t.contains("UTURN") || t.contains("U_TURN") -> TurnType.U_TURN
                t.contains("ARRIVE") || t.contains("DEST") -> TurnType.DESTINATION
                t.contains("ROUNDABOUT") || t.contains("ROTARY") -> TurnType.ROUNDABOUT
                t.contains("KEEP_LEFT") || t.contains("LEFT_KEEP") -> TurnType.KEEP_LEFT
                t.contains("KEEP_RIGHT") || t.contains("RIGHT_KEEP") -> TurnType.KEEP_RIGHT
                t.contains("LEFT") -> TurnType.LEFT
                t.contains("RIGHT") -> TurnType.RIGHT
                t.contains("EXIT") || t.contains("RAMP") || t.contains("IC") -> TurnType.EXIT
                t.contains("MERGE") || t.contains("JOIN") || t.contains("ENTR") || t.contains("HIGHWAY") || t.contains("EXPRESS") -> TurnType.MERGE
                t.contains("STRAIGHT") || t.contains("GO") -> TurnType.STRAIGHT
                else -> TurnType.OTHER
            }
        }
    }
}
