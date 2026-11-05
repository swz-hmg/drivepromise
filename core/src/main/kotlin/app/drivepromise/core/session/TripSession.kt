package app.drivepromise.core.session

import app.drivepromise.core.engine.EnginePolicy
import app.drivepromise.core.engine.PromiseEngine
import app.drivepromise.core.gate.GatePolicy
import app.drivepromise.core.gate.QuietGate
import app.drivepromise.core.ledger.LedgerEvent
import app.drivepromise.core.ledger.LedgerSink
import app.drivepromise.core.ledger.MemoryLedger
import app.drivepromise.core.ledger.RestCompliance
import app.drivepromise.core.ledger.RestMetrics
import app.drivepromise.core.model.GeoFix
import app.drivepromise.core.model.NaviEvent
import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.model.VehicleSnapshot
import app.drivepromise.core.plan.PreTripPlanner
import app.drivepromise.core.replay.ReplayPlayer
import app.drivepromise.core.rest.RestTracker
import app.drivepromise.core.scene.SummaryPhrases
import app.drivepromise.core.source.Clock
import app.drivepromise.core.source.LocationSource
import app.drivepromise.core.source.Place
import app.drivepromise.core.source.RouteOption
import app.drivepromise.core.source.RouteSource
import app.drivepromise.core.source.Sink
import app.drivepromise.core.source.VehicleSource
import app.drivepromise.core.speech.AudioUsage
import app.drivepromise.core.speech.DigestItem
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.speech.Priority
import app.drivepromise.core.speech.QuietPolicy
import app.drivepromise.core.speech.RestPolicy
import app.drivepromise.core.speech.SayCtx
import app.drivepromise.core.speech.Speaker
import app.drivepromise.core.speech.SpeechScheduler
import app.drivepromise.core.speech.Step
import app.drivepromise.core.speech.Utterance
import app.drivepromise.core.speech.VoiceArbiter
import app.drivepromise.core.speech.VoiceLoop
import app.drivepromise.core.speech.World

/** 세션 정책 — 하위 정책 묶음 + 주행 시작·끝 판단 값 */
data class SessionPolicy(
    val quiet: QuietPolicy = QuietPolicy(),
    val rest: RestPolicy = RestPolicy(),
    val engine: EnginePolicy = EnginePolicy(),
    val gate: GatePolicy = GatePolicy(),
    /** 1순위 내비 안내 용도(G1-B 확정). 플랫폼이 거절하면 NOTIFICATION으로 한 줄 전환 */
    val usage: AudioUsage = AudioUsage.NAVIGATION_GUIDANCE,
    /** 도착 소식 없이 이만큼 세워 두면(P 또는 시동 끔) 주행 끝 — 쉼(15분)보다 길게 */
    val endAfterParkedMs: Long = 30 * 60_000,
    /** 도착 소식 뒤 세우면 바로 끝, 세우지 않아도 이 시간이 지나면 끝 */
    val endAfterArrivedMs: Long = 5 * 60_000,
    /** 위치를 아직 모를 때 출발 전 계획(일몰)에 쓰는 기본 위치 — 서울시청. 주행 중 판정에는 쓰지 않음 */
    val fallbackLat: Double = 37.5665,
    val fallbackLon: Double = 126.9780,
    /** 출발 전 계획·주행 끝 요약을 (세워 둔 동안) 말로도 들려줌 */
    val speakPlan: Boolean = true,
    val speakSummary: Boolean = true,
)

/**
 * 주행 세션 — 코어 부품을 하나로 잇는 상태 기계. 앱 서비스·리플레이·JVM 시험이 같은 배선을 씁니다.
 *
 *  PARKED ──(차가 움직임: 기어 R·N·D 또는 속도 > 0.5 m/s)──▶ DRIVING ──(도착 뒤 정차 · 시동 끔 · 30분 정차 · 수동)──▶ ENDED
 *
 * 10 Hz [tick]마다: RestTracker.update → QuietGate.update → World → PromiseEngine.tick → 상태 전이 → VoiceLoop.tick(말하기 결정).
 * 입력은 [attach]로 원천(위치·차량·내비)을 붙이거나 [onFix]·[onVehicle]·[onNavi]로 직접 넣습니다(같은 스레드).
 * 기록은 안(MemoryLedger)과 밖(externalLedger — 앱이 파일에 저장) 양쪽으로.
 */
class TripSession(
    val promises: List<Promise>,
    speaker: Speaker,
    private val clock: Clock,
    var lang: Lang = Lang.KO,
    val policy: SessionPolicy = SessionPolicy(),
    private val externalLedger: LedgerSink = LedgerSink { },
    private val listener: Listener = object : Listener {},
) {
    enum class Phase { PARKED, DRIVING, ENDED }

    /** 앱(화면·알림)이 받는 소식 — 모두 선택 구현 */
    interface Listener {
        fun onPhase(phase: Phase, nowMs: Long) {}
        fun onSpeak(step: Step.Speak, nowMs: Long) {}
        fun onStatuses(statuses: Map<String, PromiseStatus>, nowMs: Long) {}
        fun onPlan(plan: PreTripPlanner.Plan, nowMs: Long) {}
        fun onSummary(summary: Summary) {}
        fun onEvent(e: LedgerEvent) {}
    }

    /** 주행 끝 요약 — 정차 화면·말·기록 */
    data class Summary(
        val startMs: Long?,
        val endMs: Long,
        val drivingMs: Long,
        val statuses: Map<String, PromiseStatus>,
        /** 들려줄 한두 문장(쉬기 요약·약속 요약) */
        val lines: List<String>,
        /** 못 전한 말(정차 후 요약 후보)의 짧은 문장 */
        val digestLines: List<String>,
        val digest: List<DigestItem>,
        val rest: RestMetrics,
        val reason: String,
    )

    val memory = MemoryLedger()
    private val ledger = LedgerSink { e ->
        memory.accept(e)
        externalLedger.accept(e)
        listener.onEvent(e)
    }
    val gate = QuietGate(policy.gate)
    val scheduler = SpeechScheduler(policy.quiet, gate, ledger)
    val arbiter = VoiceArbiter()
    val voice = VoiceLoop(scheduler, arbiter, speaker, policy.usage)
    val rest = RestTracker(policy.rest, scheduler, ledger)
    val engine = PromiseEngine(promises, scheduler, ledger, policy.engine)

    var phase = Phase.PARKED
        private set
    var tripStartMs: Long? = null
        private set
    var lastVehicle: VehicleSnapshot? = null
        private set
    var lastFix: GeoFix? = null
        private set
    var plan: PreTripPlanner.Plan? = null
        private set
    var summary: Summary? = null
        private set
    var routeError: String? = null
        private set
    var etaEpochMs: Long? = null
        private set
    var remainM: Int? = null
        private set

    private var arrivedAt: Long? = null
    private var parkedSince: Long? = null
    private var lastStatuses: Map<String, PromiseStatus> = emptyMap()
    private val closers = mutableListOf<AutoCloseable>()

    val now: Long get() = clock.nowMs()
    val statuses: Map<String, PromiseStatus> get() = engine.statuses
    val drivingMs: Long get() = rest.drivingMs

    // ---------------------------------------------------------------- 입력
    /** 원천을 붙임(없는 것은 null). 끝낼 때 [close] */
    fun attach(location: LocationSource? = null, vehicle: VehicleSource? = null, route: RouteSource? = null) {
        location?.let { s -> closers += s.start(Sink { onFix(it) }) }
        vehicle?.let { s -> closers += s.start(Sink { onVehicle(it) }) }
        route?.let { s -> closers += s.start(Sink { onNavi(it) }) }
    }

    fun attachReplay(p: ReplayPlayer) = attach(p.location, p.vehicle, p.route)

    fun close() {
        closers.forEach { runCatching { it.close() } }
        closers.clear()
    }

    fun onFix(f: GeoFix) {
        lastFix = f
    }

    fun onVehicle(v: VehicleSnapshot) {
        lastVehicle = v
    }

    fun onNavi(e: NaviEvent) {
        engine.onNavi(e)
        gate.onNavi(e)
        when (e) {
            is NaviEvent.Trip -> { etaEpochMs = e.etaEpochMs; remainM = e.remainM; routeError = null }
            is NaviEvent.Arrived -> { arrivedAt = e.tMillis; etaEpochMs = null; remainM = 0 }
            is NaviEvent.RouteError -> { etaEpochMs = null; remainM = null; routeError = e.cause }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 음성 쪽 콜백(플랫폼 → 세션)
    fun onNavPlayers(active: Int): VoiceArbiter.Action = voice.onNavPlayers(clock.nowMs(), active)
    fun onFocus(change: VoiceArbiter.Focus): VoiceArbiter.Action = voice.onFocus(clock.nowMs(), change)
    fun onSpeechDone(id: Long) = voice.onDone(id, clock.nowMs())
    fun onSpeechError(id: Long, cause: String) = voice.onError(id, clock.nowMs(), cause)

    // ---------------------------------------------------------------- 루프
    fun world(now: Long = clock.nowMs()): World = World(
        tMillis = now,
        vehicle = lastVehicle,
        fix = lastFix,
        drivingMs = rest.drivingMs,
        etaMs = etaEpochMs?.let { (it - now).coerceAtLeast(0) },
        lang = lang,
    )

    /** 10 Hz — 판정·상태 전이·말하기 결정 */
    fun tick(): Step {
        val now = clock.nowMs()
        val v = lastVehicle
        rest.update(now, v)
        gate.update(now, v)
        val w = world(now)
        if (phase != Phase.ENDED) {
            engine.tick(w)
            checkPhase(now, v)
        }
        val step = voice.tick(w)
        if (step is Step.Speak) listener.onSpeak(step, now)
        val st = engine.statuses
        if (st != lastStatuses) {
            lastStatuses = st
            listener.onStatuses(st, now)
        }
        return step
    }

    /** 리플레이 한 걸음: 가상 시각까지의 이벤트를 넣고 tick, 끝나면 주행 끝 */
    fun pumpReplay(p: ReplayPlayer): Step {
        p.pump(clock.nowMs())
        val s = tick()
        if (p.finished && phase != Phase.ENDED) end("replay_end")
        return s
    }

    private fun checkPhase(now: Long, v: VehicleSnapshot?) {
        when (phase) {
            Phase.PARKED -> if (v?.driving == true) {
                phase = Phase.DRIVING
                tripStartMs = now
                listener.onPhase(phase, now)
            }
            Phase.DRIVING -> {
                val parked = v?.parked == true
                if (parked) { if (parkedSince == null) parkedSince = now } else parkedSince = null
                val arr = arrivedAt
                val ps = parkedSince
                when {
                    v?.ignitionOn == false -> end("ignition_off")
                    arr != null && parked -> end("arrived")
                    arr != null && now - arr >= policy.endAfterArrivedMs -> end("arrived_timeout")
                    ps != null && now - ps >= policy.endAfterParkedMs -> end("parked_long")
                }
            }
            Phase.ENDED -> Unit
        }
    }

    /** 주행 끝 — 약속 판정을 닫고 요약을 만듦(한 번만). 요약 문장은 세워 둔 동안 말함 */
    fun end(reason: String = "manual"): Summary {
        summary?.let { return it }
        val now = clock.nowMs()
        val w = world(now)
        val st = engine.endTrip(w)
        val restLine = rest.endTrip(now, lang)
        scheduler.endTrip(now)
        val lines = mutableListOf<String>()
        restLine?.let { lines += it }
        SummaryPhrases.kept(lang, promises, st)?.let { lines += it }
        val digest = scheduler.digest
        val digestLines = digest.map { d -> runCatching { d.utterance.render(SayCtx(w, 0, true)) }.getOrDefault(d.key) }
        val s = Summary(tripStartMs, now, rest.drivingMs, st, lines, digestLines, digest, RestCompliance.compute(memory.events), reason)
        summary = s
        phase = Phase.ENDED
        listener.onPhase(phase, now)
        listener.onSummary(s)
        if (policy.speakSummary) lines.forEachIndexed { i, line ->
            scheduler.submit(Utterance("summary.$i", Priority.INFO, 4_000, render = { line }, speakWhen = PARKED_OR_UNKNOWN), now)
        }
        return s
    }

    // ---------------------------------------------------------------- 출발 전 계획
    /** 목적지의 경로 후보를 받아 계획을 세움(결과는 listener.onPlan·[plan]). 세워 둔 동안이면 첫 문장을 말함 */
    fun plan(
        dest: Place,
        route: RouteSource,
        departAtMs: Long? = null,
        options: List<RouteOption> = listOf(RouteOption.RECOMMENDED, RouteOption.FREE, RouteOption.COMFORTABLE, RouteOption.SHORTEST),
    ): AutoCloseable {
        val now = clock.nowMs()
        val fix = lastFix
        val lat = fix?.lat ?: policy.fallbackLat
        val lon = fix?.lon ?: policy.fallbackLon
        return route.planRoutes(dest, options, Sink { plans ->
            val p = PreTripPlanner.plan(PreTripPlanner.Input(now, lat, lon, plans, promises, policy.engine.zone, lang, departAtMs ?: now))
            plan = p
            val t = clock.nowMs()
            listener.onPlan(p, t)
            if (policy.speakPlan && p.lines.isNotEmpty()) {
                scheduler.cancel("plan", t, "replanned")
                val text = p.lines.take(2).joinToString(" ")
                scheduler.submit(Utterance("plan", Priority.INFO, 4_000, render = { text }, speakWhen = PARKED_OR_UNKNOWN), t)
            }
        })
    }

    // ---------------------------------------------------------------- 이어 가기
    fun restState(): RestTracker.State = rest.state()
    fun restoreRest(s: RestTracker.State) = rest.restore(s, clock.nowMs())

    companion object {
        private val PARKED_OR_UNKNOWN: (World) -> Boolean = { w -> w.vehicle?.driving != true }
    }
}
