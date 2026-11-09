package app.drivepromise.core.privacy

/**
 * 1.3.1(P0-9 보강, run_1109_110640) · 1.3.2: 앱(platform)에서 코어로 옮겨 스펙(S114)으로 — 내비 콜백을 기록할 때 개인 장소가 드러나는 값을 가림 — 순수 함수(안드로이드 의존 없음).
 *  - 즐겨찾기·최근 목적지·목적지·경유지·현재 위치 콜백: 내용 대신 개수만(`redacted n=…`) — 이름 비교는 대소문자 무시(onWayPoint·onWaypoint 둘 다)
 *  - 그 밖의 콜백(onRouteStarted·onDrivingInfo·onCautionPointInfo …): name·address·poiName·poiId·poiSubId 값은 글자 수만(`name=#3`),
 *    위도·경도는 소수 3자리(약 100 m — 위치 기록과 같은 기준)
 *  11/9 실행에서 onRouteStarted·onDrivingInfo 기록에 목적지 이름(name=판교역)이 그대로 남은 것을 발견해 보강.
 */
object LogMask {
    val PRIVATE_CB = listOf("Bookmark", "RecentDestination", "Destination", "WayPoint", "CurrentLocation")

    private val NAME_RX = Regex("""\b(name|address|poiName|poiId|poiSubId)=(.*?)(?=, [A-Za-z_]+=|\)|]|$)""")
    private val COORD_RX = Regex("""\b(latitude|longitude|lat|lon|lng)=(-?\d+\.\d+)""")

    fun isPrivate(callback: String): Boolean = PRIVATE_CB.any { callback.contains(it, ignoreCase = true) }

    /** 콜백 이름·첫 인자·문자열 → 기록에 남길 문자열(최대 300자) */
    fun navi(callback: String, firstArg: Any?, s: String): String {
        if (isPrivate(callback)) {
            val n = when (firstArg) { is Collection<*> -> firstArg.size; is Array<*> -> firstArg.size; null -> 0; else -> 1 }
            return "redacted n=$n"
        }
        return fields(s).take(300)
    }

    /** 이름·주소·장소 ID는 글자 수만, 좌표는 소수 3자리 */
    fun fields(s: String): String {
        val a = NAME_RX.replace(s) { m -> "${m.groupValues[1]}=#${m.groupValues[2].length}" }
        return COORD_RX.replace(a) { m ->
            val v = m.groupValues[2].toDoubleOrNull()
            if (v == null) m.value else "${m.groupValues[1]}=${String.format(java.util.Locale.ROOT, "%.3f", v)}"
        }
    }
}
