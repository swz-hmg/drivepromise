package app.drivepromise.core.ledger

// 기기 안 기록(Ledger) 계약 v0.1 — 서버로 보내지 않습니다. 저장(파일·DB)은 :app, 계산은 여기.
// G2 측정 설계(코어계약 v0.1 문서 2장)의 ‘지표 사다리’ 중 전달(L0)·순응(L4)을 이 기록만으로 계산합니다.

sealed interface LedgerEvent {
    val tMillis: Long

    /** 약속 알림 시각이 됨(노출). 예: rest.headsup(1:50) · rest.main(2:00) */
    data class Due(override val tMillis: Long, val key: String, val drivingMs: Long) : LedgerEvent

    /** 끝까지 들려줌. tMillis = 말 시작, endMs = 끝. lateMs = 시작 − 마감(마감 없으면 null) */
    data class Spoken(
        override val tMillis: Long,
        val key: String,
        val repeat: Int,
        val yields: Int,
        val endMs: Long,
        val lateMs: Long?,
    ) : LedgerEvent

    /** 내비에 물러섬(count = 이 말이 끊긴 횟수) */
    data class Yielded(override val tMillis: Long, val key: String, val count: Int) : LedgerEvent

    /** 말하지 않고 버림 — reason: expired(마감형 지남)·state_ended(상태 끝남)·rested·cancel·tts_failed:… */
    data class Dropped(override val tMillis: Long, val key: String, val reason: String) : LedgerEvent

    /** 정차 후 요약으로 돌림 — reason: info_timeout·trip_end 등 */
    data class Digested(override val tMillis: Long, val key: String, val reason: String) : LedgerEvent

    /** 쉼 인정(P 또는 시동 끔이 기준 시간 이상). restStartMs = 쉬기 시작 시각, segmentDrivingMs = 그 전까지 연속 운전 */
    data class RestTaken(override val tMillis: Long, val restStartMs: Long, val segmentDrivingMs: Long) : LedgerEvent

    data class TripEnded(override val tMillis: Long, val drivingMs: Long) : LedgerEvent

    /** 약속 상태 변화(PromiseEngine) — 정차 화면 타일·요약·순응 계산용. ★ 약속엔진 v0.1에서 추가(기본값 있는 추가) */
    data class StatusChanged(override val tMillis: Long, val promiseId: String, val from: String, val to: String, val note: String = "") : LedgerEvent
}

fun interface LedgerSink {
    fun accept(e: LedgerEvent)
}

/** 메모리 기록(시험·리플레이용). 앱은 이 목록을 파일로 저장 */
class MemoryLedger : LedgerSink {
    private val list = mutableListOf<LedgerEvent>()
    val events: List<LedgerEvent> get() = list.toList()
    override fun accept(e: LedgerEvent) {
        list += e
    }
}

/**
 * 쉬기 약속 지표 — 운전 구간(쉼과 쉼 사이) 단위.
 *  - 노출(exposed): 본 알림 시각(2:00)에 닿은 구간
 *  - 전달(delivered): 그중 본 알림을 주행 중 끝까지 들려준 구간 → [deliveryRate] (지표 사다리 L0)
 *  - 순응(compliedAfterMain): 본 알림을 들려준 뒤 windowMin 안에 쉼을 시작한 구간 → [complianceRate] (L4, 주 지표)
 *  - 약속 지킴(keptWithinWindow): 본 알림 시각 뒤 windowMin 안에 쉼을 시작(들었는지와 무관)
 *  - 예고로 지킴(keptOnHeadsUp): 예고 시각(1:50)에 닿았지만 본 알림 전에 쉼
 * 한계: ‘쉼’은 P 15분 이상으로만 판단 — 주유·볼일·휴식을 구분하지 못합니다(문서 2장 타당성 위협).
 */
data class RestMetrics(
    val segments: Int,
    val headsUpExposed: Int,
    val keptOnHeadsUp: Int,
    val exposed: Int,
    val delivered: Int,
    val compliedAfterMain: Int,
    val keptWithinWindow: Int,
    val medianDeliveryLateMs: Long?,
) {
    val deliveryRate: Double? get() = if (exposed == 0) null else delivered.toDouble() / exposed
    val complianceRate: Double? get() = if (delivered == 0) null else compliedAfterMain.toDouble() / delivered
    val keptRate: Double? get() = if (exposed == 0) null else keptWithinWindow.toDouble() / exposed
}

object RestCompliance {
    const val MAIN_KEY = "rest.main"
    const val HEADS_UP_KEY = "rest.headsup"

    private class Seg {
        var headsUpDue: Long? = null
        var mainDue: Long? = null
        var mainSpoken: Long? = null
        var restStart: Long? = null
    }

    fun compute(events: List<LedgerEvent>, windowMin: Int = 20): RestMetrics {
        val window = windowMin * 60_000L
        val segs = mutableListOf<Seg>()
        var cur = Seg()
        for (e in events.sortedBy { it.tMillis }) {
            when (e) {
                is LedgerEvent.Due -> when (e.key) {
                    HEADS_UP_KEY -> if (cur.headsUpDue == null) cur.headsUpDue = e.tMillis
                    MAIN_KEY -> if (cur.mainDue == null) cur.mainDue = e.tMillis
                }
                is LedgerEvent.Spoken -> if (e.key == MAIN_KEY && cur.mainSpoken == null) cur.mainSpoken = e.tMillis
                is LedgerEvent.RestTaken -> {
                    cur.restStart = e.restStartMs
                    segs += cur
                    cur = Seg()
                }
                else -> Unit
            }
        }
        if (cur.headsUpDue != null || cur.mainDue != null) segs += cur
        var hx = 0; var kh = 0; var ex = 0; var dl = 0; var cp = 0; var kw = 0
        val late = mutableListOf<Long>()
        for (s in segs) {
            if (s.headsUpDue != null) hx++
            val r = s.restStart
            val due = s.mainDue
            if (due == null) {
                if (s.headsUpDue != null && r != null && r >= s.headsUpDue!!) kh++
                continue
            }
            ex++
            if (r != null && r >= due && r - due <= window) kw++
            val sp = s.mainSpoken ?: continue
            dl++
            late += sp - due
            if (r != null && r >= sp && r - sp <= window) cp++
        }
        val med = if (late.isEmpty()) null else late.sorted()[late.size / 2]
        return RestMetrics(segs.size, hx, kh, ex, dl, cp, kw, med)
    }
}
