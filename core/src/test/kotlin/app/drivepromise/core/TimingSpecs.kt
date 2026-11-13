package app.drivepromise.core

import app.drivepromise.core.gate.GatePolicy
import app.drivepromise.core.gate.QuietGate
import app.drivepromise.core.model.Gear
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.TurnType
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.speech.Priority
import app.drivepromise.core.speech.QuietPolicy
import app.drivepromise.core.speech.SpeechScheduler
import app.drivepromise.core.speech.Step
import app.drivepromise.core.speech.Utterance
import app.drivepromise.core.speech.Validity
import app.drivepromise.core.speech.World

/**
 * 1.3.6 — 발화 타이밍(회전 뒤 침묵의 기준점) 스펙. 11/13 구현 코어 시뮬레이션(docs/TIMING_EVAL.md)에서
 * 회전 20 m 앞을 ‘통과’로 보던 1.3.5는 느린 회전에서 회전 뒤 침묵이 설계 8초보다 일찍 끝남을 확인 → 회전점 기준으로.
 */
object TimingSpecs {
    val all: List<Pair<String, () -> Unit>> = listOf(
        "S128 회전 뒤 침묵은 회전점 기준 — 감속해 15 km/h로 도는 회전에서도 실제 회전 뒤 8초가 지나야 말함(1.3.5 기준 20 m 앞이면 약 3초 일찍 나옴)" to ::s128,
    )
}

/** 200 m 앞 오른쪽 회전: 12 m/s → 회전점까지 1.8 m/s²로 4.2 m/s까지 감속 → 회전 → 회전 뒤 1.5 m/s²로 가속. 말은 회전 60 m 앞(회전 앞 침묵 안)에서 들어옴 */
private fun startAfterTurn(gp: GatePolicy): Pair<Long, Long> {
    val g = QuietGate(gp)
    val q = SpeechScheduler(QuietPolicy(), g)
    var t = 0L; var d = 200.0; var v = 12.0; var turnAt = -1L; var spokeAt = -1L; var submitted = false
    g.onNavi(NaviEvent.Tbt(0, 200, TurnType.RIGHT))
    while (t <= 60_000 && spokeAt < 0) {
        val snap = VehicleSnapshot(t, gear = Gear.D, speedMps = v.toFloat())
        g.update(t, snap)
        if (!submitted && d <= 60) {   // 회전 앞 침묵 안에서 생긴 말(예: 구역 밖) — 회전이 끝난 뒤에야 말할 수 있음
            q.submit(Utterance("p", Priority.PROMISE, 3_000, render = { "약속 말" }, deadlineAtMs = t + 600_000, validity = Validity.WHILE_STATE), t)
            submitted = true
        }
        val s = q.tick(World(t, snap), voiceFree = true)
        if (s is Step.Speak) spokeAt = t
        val target = if (turnAt < 0) minOf(12.0, kotlin.math.sqrt(4.2 * 4.2 + 2 * 1.8 * maxOf(0.0, d))) else 12.0
        v = if (v < target) minOf(target, v + 0.15) else maxOf(target, v - 0.25)
        d -= v * 0.1
        t += 100
        if (turnAt < 0 && d <= 0) turnAt = t
    }
    return turnAt to spokeAt
}

private fun s128() {
    val (turnAt, spokeAt) = startAfterTurn(GatePolicy())
    ok(turnAt > 0 && spokeAt > 0, "회전·말 모두 있음: turn=$turnAt spoke=$spokeAt")
    ok(spokeAt - turnAt in 7_500..8_600, "실제 회전 뒤 약 8초(±0.5초 — 10 Hz 틱·속도 적분 오차)에 말함: ${spokeAt - turnAt} ms")
    val (oldTurn, oldSpoke) = startAfterTurn(GatePolicy(turnReachedM = 20))
    ok(oldSpoke - oldTurn < 6_500, "1.3.5 기준(20 m 앞 = 통과)은 회전 뒤 8초보다 일찍 나옴(바뀐 까닭): ${oldSpoke - oldTurn} ms")
}
