package app.drivepromise.core.source

import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.VehicleSnapshot

// 입력 원천 계약 v0.1 — 실차(Pleos·AAOS 어댑터, app/platform 패키지)와 리플레이(core/replay/ReplayPlayer)가 같은 모양으로 바꿔 끼웁니다.
// 코어는 코루틴·Flow에 기대지 않습니다(순수 Kotlin, JVM 시험만으로 검증). 플랫폼은 이 콜백을 Flow로 감싸 써도 됩니다.

/** 값을 하나씩 받는 곳 — 어댑터·리플레이가 부릅니다(같은 스레드에서 차례로 부른다고 가정) */
fun interface Sink<in T> {
    fun accept(value: T)
}

/** 이벤트 원천 — start로 받기 시작, 돌려받은 AutoCloseable로 끝냄 */
interface Source<out T> {
    fun start(sink: Sink<T>): AutoCloseable
}

/** 위치 — FusedLocation(주), 안 되면 LocationManager GPS, 시연은 geo fix 리플레이 */
interface LocationSource : Source<GeoFix>

/** 차량 상태 — Vehicle SDK 2.3.2 + CarPropertyManager. 속성별 변경을 합친 전체 상태를 보냅니다 */
interface VehicleSource : Source<VehicleSnapshot>

/** 내비 — NaviHelper 3.0.5(경로·ETA·TBT·주의 지점) 또는 리플레이 */
interface RouteSource : Source<NaviEvent> {
    /** 출발 전 계획: 경로 후보 계산. 결과(실패 포함)는 done으로 한 번만 */
    fun planRoutes(dest: Place, options: List<RouteOption>, done: Sink<RoutePlans>): AutoCloseable
}

data class Place(val name: String, val lat: Double, val lon: Double, val poiId: String? = null)

/** NaviHelper RouteOption 중 쓰는 것(이름 대응은 어댑터에서) */
enum class RouteOption { RECOMMENDED, FREE, COMFORTABLE, SHORTEST, OTHER }

data class RoutePlan(
    val option: RouteOption,
    val distanceM: Int,
    val durationSec: Int,
    val highwayM: Int? = null,
)

/** 경로 후보 결과 — 실패면 plans가 비고 error에 원인(예: CAUSE_SEARCH_ROUTE) */
data class RoutePlans(val plans: Map<RouteOption, RoutePlan>, val error: String? = null)

/** 시각 — 시험에서는 고정 시각을 주입 */
fun interface Clock {
    fun nowMs(): Long
}
