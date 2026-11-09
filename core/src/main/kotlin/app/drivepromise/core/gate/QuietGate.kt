package app.drivepromise.core.gate

import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.Turn
import app.drivepromise.core.model.TurnType
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.speech.QuietWindows

/**
 * 침묵 구간 값 — 침묵구간검증 v0.1(11/4)의 선택값 P5. 안전 효과가 검증된 값이 아니라 ‘설계 기본값’이며 G2 비교 시험으로 조정합니다.
 * 시간 값은 ms, 거리는 m.
 */
data class GatePolicy(
    /** 회전 앞 침묵: 회전점까지 남은 시간이 이보다 짧으면 말하지 않음 */
    val preMs: Long = 8_000,
    /** 좌회전·유턴은 더 일찍부터(고령 운전자 좌회전 사고 과다, Mayhew 2006) */
    val preLeftMs: Long = 10_000,
    /** 회전 뒤 침묵(보류된 말이 몰려 나오는 효과를 감안해 길게) */
    val postMs: Long = 8_000,
    /** 남은 시간 = 거리 ÷ max(속도, 이 값) — 정지·저속에서도 회전이 ‘곧’이라고 보지 않게 */
    val minSpeedMps: Double = 5.0,
    /** TBT 거리가 이 값 이하가 되면 회전점을 지난 것으로 봄 */
    val turnReachedM: Int = 20,
    /** 새 TBT의 거리가 직전 추정보다 이만큼 이상 크면 ‘다음 회전으로 넘어감’(= 회전 통과) */
    val tbtAdvanceM: Int = 60,
    /** 순정 내비가 안내를 시작하는 거리(가정 — G1-B에서 측정해 교체) */
    val navPromptAtM: List<Int> = listOf(300, 100),
    /** 순정 내비 안내 한 번의 길이 */
    val navPromptMs: Long = 2_500,
    /** 내비 안내 앞뒤 여유 */
    val navGuardPadMs: Long = 500,
    /** 신호 대기 창: 정지선에 선 뒤 이 시간 안(발화 정책 P6·침묵구간검증 ‘대기 시작 2초’) */
    val stopWindowMs: Long = 2_000,
    /** 급제동 판정: 이 감속(m/s²) 이상 */
    val hardBrakeMps2: Double = 3.0,
    /** 급제동 뒤 이만큼 침묵 */
    val hardBrakeHoldMs: Long = 2_000,
    /** TBT 소식이 이만큼 없으면 회전 예측을 접음(경로 안내가 끝났다고 봄) */
    val tbtStaleMs: Long = 60_000,
)

/**
 * 발화 게이트 — [QuietWindows] 구현. 앱 루프가 10 Hz로 [update]를 부르고, 내비 이벤트는 [onNavi]로 넣습니다.
 *
 *  - 회전 앞: 남은 시간(거리 ÷ max(속도, 5 m/s)) < 8초(좌회전·유턴 10초) → 침묵
 *  - 회전 뒤: 회전점 통과 뒤 8초 → 침묵
 *  - 방향지시등이 켜진 동안·급제동 뒤 2초 → 침묵
 *  - 끝낼 시간 확보(runway): 말이 회전 앞 침묵이 시작되기 전에 끝나야만 시작
 *  - 내비 안내 피하기(nav guard): TBT 거리로 예측한 내비 안내(300·100 m 앞, 2.5초)와 겹치지 않게
 *  - 신호 대기 창: 주행 중(기어 D) 속도 0이 된 뒤 2초 안 — 스케줄러가 마감 임박·두 번 끊긴 말에만 씀(방향지시등·급제동 침묵 중엔 닫힘, 1.3.0)
 * 경로 안내가 없으면(TBT 없음) 회전 예측은 하지 않고 차량 신호만 봅니다. 기어 P(세워 둠)에서도 회전 예측은 하지 않습니다(1.3.0b).
 */
class QuietGate(private val p: GatePolicy = GatePolicy()) : QuietWindows {
    private var nowMs = 0L
    private var speedMps = 0.0
    private var turnSignal = Turn.OFF
    private var driving = false
    /** 1.3.0b: 기어 P(또는 시동 끔) — 세워 둔 차에는 ‘곧 회전’이 없음 */
    private var parked = false

    // TBT 추정
    private var tbtAt: Long? = null
    private var tbtDistM = 0.0
    private var tbtTurn = TurnType.OTHER
    private var turnCounted = false   // 지금 TBT의 회전점 통과를 이미 셌는가
    private var lastTurnAt: Long? = null
    /** 1.3.3(P2, B17): TBT 뒤 실제로 달린 거리(속도 적분) — ‘현재 속도 × 지난 시간’은 신호 대기 뒤 출발하면 회전을 지난 것으로 봄 */
    private var movedM = 0.0
    private var movedAt: Long? = null

    // 정지·급제동
    private var stoppedSince: Long? = null
    private var hardBrakeAt: Long? = null
    /** 최근 1.5초의 (시각, 속도) 표본 — 0.3~1.5초 전 표본과 비교해 감속을 셈(표본 주기에 흔들리지 않게) */
    private val speedHist = ArrayDeque<Pair<Long, Double>>()

    /** 다가오는 회전점까지 남은 시간(ms). 경로가 없거나 그 회전을 이미 지났으면(다음 TBT를 기다리는 중) null */
    val timeToTurnMs: Long? get() = if (tbtAt == null || turnCounted || parked) null else (distNow() / maxOf(speedMps, p.minSpeedMps) * 1000).toLong()
    val nextTurn: TurnType? get() = if (tbtAt == null || turnCounted) null else tbtTurn
    val lastTurnPassedAt: Long? get() = lastTurnAt

    /** 10 Hz — 차량 상태 반영(속도·방향지시등·정차·급제동) */
    fun update(now: Long, v: VehicleSnapshot?) {
        nowMs = now
        if (v == null) return
        val s = (v.speedMps ?: 0f).toDouble()
        turnSignal = v.turnSignal
        driving = v.driving
        // 1.3.0b(11/8 실행 run_1108_183038): 세워 둔 차(기어 P)에는 회전 예측을 쓰지 않음 — 출발 전 계획 문장이 내비 안내에 끊긴 뒤,
        // 경로의 첫 회전(80 m)을 ‘16초 뒤 회전’으로 보고 글자 수로 잰 긴 문장(1.3.0 C05)을 끝내 다시 말하지 못했음
        parked = v.parked
        while (speedHist.isNotEmpty() && now - speedHist.first().first > HIST_MS) speedHist.removeFirst()
        for ((t, v0) in speedHist) {
            val dt = (now - t) / 1000.0
            if (dt >= MIN_DT_S && (v0 - s) / dt >= p.hardBrakeMps2) {
                hardBrakeAt = now
                speedHist.clear()   // 같은 감속을 다음 tick에 다시 세지 않게(침묵 2초는 ‘급제동 시점’부터)
                break
            }
        }
        if (speedHist.isEmpty() || speedHist.last().first < now) speedHist.addLast(now to s)
        // 1.3.3(B17): 앞 속도로 지난 구간을 적분(한 번에 최대 MAX_INTEGRATE_MS — 시계 점프 방지)
        val ma = movedAt
        if (tbtAt != null && ma != null && now > ma) movedM += speedMps * (minOf(now - ma, MAX_INTEGRATE_MS) / 1000.0)
        movedAt = now
        speedMps = s
        if (v.driving && v.stopped) {
            if (stoppedSince == null) stoppedSince = now
        } else {
            stoppedSince = null
        }
        // 회전점 통과 판단(거리 기준)
        val ta = tbtAt
        if (ta != null) {
            if (now - ta > p.tbtStaleMs || ta - now > FUTURE_TOL_MS) {   // 1.3.3(B23): 시계가 뒤로 가 TBT 시각이 미래면 접음
                tbtAt = null
            } else if (!turnCounted && distNow() <= p.turnReachedM) {
                markTurnPassed(now)
            }
        }
    }

    fun onNavi(e: NaviEvent) {
        when (e) {
            is NaviEvent.Tbt -> onTbt(e)
            is NaviEvent.Arrived, is NaviEvent.RouteError -> tbtAt = null
            else -> Unit
        }
    }

    private fun onTbt(e: NaviEvent.Tbt) {
        if (tbtAt != null && !turnCounted) {
            val est = distNow(e.tMillis)   // 직전 회전까지의 남은 거리 추정
            val advanced = e.distM >= est + p.tbtAdvanceM || (e.turn != tbtTurn && e.distM > est)
            if (advanced) markTurnPassed(e.tMillis)   // 다음 회전으로 넘어감 = 직전 회전 통과
        }
        tbtAt = e.tMillis
        tbtDistM = e.distM.toDouble()
        movedM = 0.0   // 1.3.3(B17): 새 TBT 거리부터 다시 적분
        tbtTurn = e.turn
        turnCounted = false
        if (e.turn == TurnType.DESTINATION && e.distM <= p.turnReachedM) tbtAt = null
    }

    private fun markTurnPassed(at: Long) {
        lastTurnAt = at
        turnCounted = true
    }

    private fun distNow(at: Long = nowMs): Double {
        val ta = tbtAt ?: return Double.MAX_VALUE
        // 1.3.3(B17): 적분한 거리 + 마지막 갱신(또는 TBT) 뒤 지금 속도로 간 거리
        val from = maxOf(movedAt ?: ta, ta)
        val extra = speedMps * ((at - from).coerceIn(0, MAX_INTEGRATE_MS) / 1000.0)
        return (tbtDistM - movedM - extra).coerceAtLeast(0.0)
    }

    private fun preMsFor(turn: TurnType) = if (turn == TurnType.LEFT || turn == TurnType.U_TURN) p.preLeftMs else p.preMs

    /** 지금 이 순간 침묵인가(회전 앞·뒤, 방향지시등, 급제동) */
    fun quiet(now: Long): Boolean {
        if (turnSignal != Turn.OFF && !parked) return true   // 1.3.1(P0-4): 세워 둔 채 비상등을 켜도 요약·계획은 말함
        val hb = hardBrakeAt
        if (hb != null && now - hb in 0 until p.hardBrakeHoldMs) return true   // 1.3.3(B23): 시계가 뒤로 가 미래가 된 기록은 무시
        val lt = lastTurnAt
        if (lt != null && now - lt in 0 until p.postMs) return true
        val tt = timeToTurnMs
        if (tt != null && tt < preMsFor(tbtTurn)) return true
        return false
    }

    override fun normal(nowMs: Long, needMs: Long): Boolean {
        if (quiet(nowMs)) return false
        val tt = timeToTurnMs ?: return true
        if (tt - preMsFor(tbtTurn) < needMs) return false   // 끝낼 시간 확보
        val v = maxOf(speedMps, p.minSpeedMps)
        val d = distNow(nowMs)
        for (at in p.navPromptAtM) {   // 내비 안내 피하기
            val t = ((d - at) / v * 1000).toLong()
            if (t in 0 until needMs + p.navGuardPadMs) return false
            if (t < 0 && -t < p.navPromptMs + p.navGuardPadMs) return false
        }
        return true
    }

    override fun stopWindow(nowMs: Long): Boolean {
        val s = stoppedSince ?: return false
        // 1.3.0(C04): 방향지시등이 켜진 동안(좌회전 대기 등)·급제동 직후에는 신호 대기 창도 열지 않음 — 11/7 codex R12
        if (turnSignal != Turn.OFF) return false
        val hb = hardBrakeAt
        if (hb != null && nowMs - hb < p.hardBrakeHoldMs) return false
        return driving && nowMs - s in 0..p.stopWindowMs
    }

    private companion object {
        const val HIST_MS = 1_500L
        const val MIN_DT_S = 0.3
        /** 1.3.3: 적분 한 구간 상한 · 미래 시각 허용 오차 */
        const val MAX_INTEGRATE_MS = 10_000L   // 60배속 리플레이 한 틱(6초)보다 크게
        const val FUTURE_TOL_MS = 5_000L
    }
}
