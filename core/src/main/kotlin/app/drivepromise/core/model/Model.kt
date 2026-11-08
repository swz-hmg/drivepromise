package app.drivepromise.core.model

// 운전약속 코어 데이터 모델 — 계약 v0.1 (11/5 고정). 바꾸려면 docs/contracts.md 의 변경 절차(PR + 승인)를 따릅니다.
// 시간은 모두 epoch 밀리초(tMillis). 값을 모르면 null 또는 UNKNOWN — 에뮬레이터·차종마다 주는 신호가 다릅니다.

enum class Gear { P, R, N, D, UNKNOWN;
    companion object { fun of(raw: String?): Gear = when (raw?.trim()?.uppercase()) {
        "P", "PARK" -> P; "R", "REVERSE" -> R; "N", "NEUTRAL" -> N; "D", "DRIVE" -> D; else -> UNKNOWN } }
}

enum class Turn { OFF, LEFT, RIGHT, HAZARD;
    companion object { fun of(raw: String?): Turn = when (raw?.trim()?.uppercase()) {
        "LEFT", "BLINKLEFT" -> LEFT; "RIGHT", "BLINKRIGHT" -> RIGHT; "HAZARD", "EMERGENCY" -> HAZARD; else -> OFF } }
}

enum class Wiper { OFF, INTERMITTENT, LOW, HIGH, UNKNOWN }

enum class HeadlampMode { OFF, AUTO, LOW, HIGH, UNKNOWN }

enum class Door { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT, TRUNK, HOOD }

/** 위치 1건 — FusedLocation(주) 또는 리플레이 geo fix. 약속 판단의 위치 기준(내비 엔진 위치는 geo fix를 따르지 않음, 11/4 확인) */
data class GeoFix(
    val tMillis: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val accuracyM: Float? = null,
)

/** 차량 상태 1건(전체 상태). 어댑터·리플레이는 부분 변경([VehicleDelta])을 이전 값에 합쳐 만듭니다 — [merge] */
data class VehicleSnapshot(
    val tMillis: Long,
    val gear: Gear = Gear.UNKNOWN,
    val speedMps: Float? = null,
    val turnSignal: Turn = Turn.OFF,
    val brakeOn: Boolean? = null,
    val steeringDeg: Float? = null,
    val wiper: Wiper = Wiper.UNKNOWN,
    val headlamp: HeadlampMode = HeadlampMode.UNKNOWN,
    val ignitionOn: Boolean? = null,
    val doorsOpen: Set<Door> = emptySet(),
    val telltales: Set<String> = emptySet(),
) {
    /** 기어 P 또는 시동 꺼짐 = 정차(쉼 후보). 신호 대기(기어 D, 속도 0)는 주행으로 셉니다 */
    val parked: Boolean get() = gear == Gear.P || ignitionOn == false

    /** 주행 중: 정차가 아니고 기어를 알거나(R·N·D) 움직이는 중 */
    val driving: Boolean get() = !parked && (gear != Gear.UNKNOWN || (speedMps ?: 0f) > 0.5f)

    val stopped: Boolean get() = (speedMps ?: 0f) < 0.5f
}

/** 부분 변경 — 속성별 콜백·리플레이 vehicle 항목. null은 ‘바뀌지 않음’ */
data class VehicleDelta(
    val tMillis: Long,
    val gear: Gear? = null,
    val speedMps: Float? = null,
    val turnSignal: Turn? = null,
    val brakeOn: Boolean? = null,
    val steeringDeg: Float? = null,
    val wiper: Wiper? = null,
    val headlamp: HeadlampMode? = null,
    val ignitionOn: Boolean? = null,
    val doorsOpen: Set<Door>? = null,
)

fun VehicleSnapshot.merge(d: VehicleDelta): VehicleSnapshot = copy(
    tMillis = d.tMillis,
    gear = d.gear ?: gear,
    speedMps = d.speedMps ?: speedMps,
    turnSignal = d.turnSignal ?: turnSignal,
    brakeOn = d.brakeOn ?: brakeOn,
    steeringDeg = d.steeringDeg ?: steeringDeg,
    wiper = d.wiper ?: wiper,
    headlamp = d.headlamp ?: headlamp,
    ignitionOn = d.ignitionOn ?: ignitionOn,
    doorsOpen = d.doorsOpen ?: doorsOpen,
)

enum class TurnType { LEFT, RIGHT, U_TURN, STRAIGHT, KEEP_LEFT, KEEP_RIGHT, EXIT, MERGE, ROUNDABOUT, DESTINATION, OTHER;
    companion object { fun of(raw: String?): TurnType = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OTHER }
}

/** 주의 지점 종류 — NaviHelper CautionPointType 중 쓰는 것. 원래 이름은 [NaviEvent.Caution.raw]에 그대로 둡니다 */
enum class CautionKind { SPEED_CAMERA, SCHOOL_ZONE, SILVER_ZONE, WRONG_WAY_DRIVING_ALERT, DOWNHILL_CAUTION_AREA, ACCIDENT_BLACKSPOT, SHARP_CURVE, OTHER;
    companion object { fun of(raw: String?): CautionKind = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OTHER }
}

/** 내비 이벤트 — NaviHelper 3.0.5 또는 리플레이. 회전 문장은 만들지 않고(순정 내비 몫) 침묵 구간·충돌 예고에만 씁니다 */
sealed interface NaviEvent {
    val tMillis: Long

    /** 다음 회전까지 거리(TBT). distM은 이벤트 시각 기준 */
    data class Tbt(override val tMillis: Long, val distM: Int, val turn: TurnType, val road: String? = null,
                   /** 1.3.0: 갈림길 방면 설명(NaviHelper TBTInfo.description — 실측 ‘판교IC 방면’). 고속도로 사전 예고에만 씀 */
                   val toward: String? = null) : NaviEvent

    data class Caution(override val tMillis: Long, val kind: CautionKind, val distM: Int, val raw: String = kind.name) : NaviEvent

    data class SpeedLimit(override val tMillis: Long, val kph: Int) : NaviEvent

    /** 경로 요약 — 도착 예정 시각·남은 거리 */
    data class Trip(override val tMillis: Long, val etaEpochMs: Long, val remainM: Int) : NaviEvent

    data class Arrived(override val tMillis: Long) : NaviEvent

    /** 경로 시작·재탐색 실패 — 예: CAUSE_SEARCH_ROUTE(에뮬레이터 인터넷·DNS 끊김, G1-B v0.7). 경로에 기대는 약속은 ‘판단 못 함’으로 */
    data class RouteError(override val tMillis: Long, val cause: String) : NaviEvent
}

/** 사용자가 스스로 정한 약속(설정 값만). 판정은 engine/PromiseEngine(11/6) */
sealed interface Promise {
    val id: String

    /** 해 지기 전 귀가: 일몰 marginMin분 전까지 도착 */
    data class HomeBeforeDark(val marginMin: Int = 30, override val id: String = "dark") : Promise

    /** 고속도로 대신 일반도로 */
    data class AvoidHighway(override val id: String = "highway") : Promise

    /** 비 오는 날 조심(와이퍼 = 비 신호). 세부 규칙은 engine에서 */
    data class RainCare(override val id: String = "rain") : Promise

    /** 연속 운전 everyMin분마다 쉬기 — rest/RestTracker */
    data class RestEvery(val everyMin: Int = 120, override val id: String = "rest") : Promise

    /** 약속 구역 안에서만(중심·반경) — 근접 알림(G1-A E1)으로 이탈 확인 */
    data class StayInZone(val lat: Double, val lon: Double, val radiusM: Int, override val id: String = "zone") : Promise
}

enum class PromiseStatus { ON_TRACK, AT_RISK, KEPT, MISSED, NOT_APPLICABLE, UNKNOWN }
