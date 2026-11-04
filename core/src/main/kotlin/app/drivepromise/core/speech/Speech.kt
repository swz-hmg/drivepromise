package app.drivepromise.core.speech

import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.VehicleSnapshot

// 발화 정책 v0.4 계약 — 늦은알림정책 v0.1(Q027)·G1-B v0.6/v0.7 실측을 반영. 바뀐 점은 docs/contracts.md ‘변경 기록’.

enum class Priority { SAFETY, PROMISE, INFO }

/** 말의 유효성 종류 — LATE_OK(마감을 넘긴 말을 얼마나 늦게까지 해도 되는지)를 정합니다 */
enum class Validity {
    /** 상태가 이어지는 동안 맞는 말(2시간 쉬기·빗길·해 진 뒤): 주행 중 버리지 않음, 마감 뒤 신호 대기 창 허용 */
    WHILE_STATE,

    /** 지점·시각을 지나면 의미가 사라지는 말(고속도로 대신·구역 경계·해 지기 전 출발): 마감 뒤 0초 — 지난 말은 하지 않음 */
    UNTIL_DEADLINE,
}

enum class Lang { KO, EN }

/** 판단 시점의 세계 — 앱 루프가 10 Hz로 만듭니다. drivingMs는 RestTracker 값 */
data class World(
    val tMillis: Long,
    val vehicle: VehicleSnapshot? = null,
    val fix: GeoFix? = null,
    /** 마지막 쉼(P 15분 이상) 이후 운전 시간 */
    val drivingMs: Long = 0,
    /** 도착까지 남은 시간(모르면 null) */
    val etaMs: Long? = null,
    val lang: Lang = Lang.KO,
)

/** 문장을 만들 때의 맥락 — 말하기 직전에 지금 값으로 만듭니다. short = 물러선 뒤 다시 말할 때의 짧은 문장 */
data class SayCtx(val world: World, val repeat: Int, val short: Boolean)

/**
 * 말 하나. 문장은 미리 만들지 않고 [render]로 말하기 직전에 만듭니다.
 * PROMISE는 마감([deadlineAtMs])과 유효성([validity])이 꼭 있어야 합니다. 상태 지속형에서 마감 = ‘목표 시각’(에스컬레이션 시작).
 */
class Utterance(
    val key: String,
    val priority: Priority,
    /** 예상 길이(시니어용 느린 발화 기준). 4초 이하 */
    val estMs: Long,
    val render: (SayCtx) -> String,
    val deadlineAtMs: Long? = null,
    val validity: Validity? = null,
    /** 말하기 직전 재판정 — false면 말하지 않음(마감형은 요약 기록 + followup, 상태 지속형은 ‘상태 끝남’) */
    val stillValid: (World) -> Boolean = { true },
    /** 마감형이 지났을 때 앞을 보는 새 말(대개 상태 지속형) */
    val followup: (World) -> Utterance? = { null },
    val repeatAfterMs: Long? = null,
    val maxRepeats: Int = 0,
    /** 짧은 문장의 예상 길이(물러선 뒤) */
    val estShortMs: Long = estMs,
    /** 말해도 되는 상태인가 — false면 버리지 않고 미룸(예: 쉬기 말은 주행 중에만, 세워 둔 동안엔 미룸) */
    val speakWhen: (World) -> Boolean = { true },
) {
    init {
        if (priority == Priority.PROMISE) {
            require(deadlineAtMs != null) { "PROMISE 말에는 deadlineAtMs가 필요합니다: $key" }
            require(validity != null) { "PROMISE 말에는 validity가 필요합니다: $key" }
        }
        require(estMs in 1..MAX_UTTERANCE_MS) { "말 길이는 1~$MAX_UTTERANCE_MS ms(4초 이하): $key=$estMs" }
        require(estShortMs in 1..estMs) { "짧은 문장 길이는 1 ms~원래 길이: $key=$estShortMs" }
        require(maxRepeats >= 0) { "maxRepeats는 0 이상: $key" }
        require(maxRepeats == 0 || (repeatAfterMs != null && repeatAfterMs > 0)) { "반복하려면 repeatAfterMs(> 0)가 필요합니다: $key" }
    }

    override fun toString(): String = "Utterance($key, $priority, deadline=$deadlineAtMs, $validity)"

    companion object {
        const val MAX_UTTERANCE_MS = 4_000L
    }
}

enum class InfoOverflow { REST_DIGEST, DROP }

/** 발화 정책 v0.4 — 시간 값은 ms */
data class QuietPolicy(
    /** 정보: 이 시간 안에 못 하면 정차 후 요약 */
    val infoMaxWaitMs: Long = 60_000,
    val infoOverflow: InfoOverflow = InfoOverflow.REST_DIGEST,
    /** 약속: 마감 이 시간 전부터 신호 대기 창도 씀 */
    val promiseEscalateWithinMs: Long = 60_000,
    /** 지점·시각 마감형: 마감 뒤 허용(0 = 지난 말은 하지 않음) */
    val lateOkUntilDeadlineMs: Long = 0,
    /** 상태 지속형: 주행 중 버리지 않음(false면 정보처럼 infoMaxWaitMs 뒤 요약 — G2 대안) */
    val keepWhileStateWhileDriving: Boolean = true,
    /** 이만큼 끊기면(물러서면) 신호 대기 창이나 확실한 틈에서만 */
    val maxYieldsBeforeStopWindow: Int = 2,
    /** 확실한 틈 = 말 길이 + 이 값 */
    val longGapExtraMs: Long = 4_000,
    /** TTS 오류가 이만큼이면 요약으로 */
    val maxFailures: Int = 3,
)

/** 2시간마다 쉬기 — 늦은알림정책 v0.1. ★ headsUpCutoffMin은 코어 계약 v0.1에서 추가(예고와 본 알림이 붙지 않게) */
data class RestPolicy(
    val targetMin: Int = 120,
    val leadMin: Int = 10,
    val headsUpCutoffMin: Int = 2,
    val repeatMin: Int = 15,
    val maxRepeats: Int = 2,
    val restResetMin: Int = 15,
    /** 본 알림 때 도착이 이 시간 안이면 ‘도착하면 쉬어요’(설계 후보 — G2에서 수용성 확인). 0이면 끔 */
    val arrivalSoonMin: Int = 15,
    val estMs: Long = 3_500,
    val estShortMs: Long = 2_000,
) {
    init {
        require(targetMin > leadMin && leadMin > headsUpCutoffMin && headsUpCutoffMin >= 0) { "targetMin > leadMin > headsUpCutoffMin ≥ 0" }
        require(restResetMin > 0 && repeatMin > 0 && maxRepeats >= 0)
    }
}

/**
 * 말해도 되는 때 — gate의 침묵 구간 구현(11/7, 침묵구간검증 v0.1 값: 회전 8초 전(좌회전 10초)~회전 뒤 8초,
 * 예측 가능한 내비 안내(회전 300·100 m 앞, 2.5초), 방향지시등 중 침묵, 신호 대기 시작 2초 창)이 TBT·속도로 계산합니다.
 */
interface QuietWindows {
    /** now부터 needMs 동안 말해도 되는가 */
    fun normal(nowMs: Long, needMs: Long): Boolean

    /** 정지선 대기 시작 2초 안(신호 대기 창, 발화 정책 P6) */
    fun stopWindow(nowMs: Long): Boolean

    /** 확실한 틈: needMs 동안 내비 예측 안내·회전 침묵이 없음 */
    fun longGap(nowMs: Long, needMs: Long): Boolean = normal(nowMs, needMs)
}

/** 스케줄러 결정 */
sealed interface Step {
    data object Idle : Step

    /** estMs = 예상 길이(플랫폼의 시간 초과 감시용), attempt = 이 말의 몇 번째 시도(물러섬·오류 포함) */
    data class Speak(
        val id: Long,
        val key: String,
        val text: String,
        val priority: Priority,
        val short: Boolean,
        val repeat: Int,
        val attempt: Int,
        val estMs: Long,
    ) : Step
}

enum class DigestReason { INFO_TIMEOUT, EXPIRED, STATE_ENDED, TTS_FAILED, TRIP_END }

/** 정차 후 요약 후보(기록). 무엇을 말할지는 요약 문장 담당(scene)이 정함 */
data class DigestItem(val tMillis: Long, val key: String, val reason: DigestReason, val utterance: Utterance)
