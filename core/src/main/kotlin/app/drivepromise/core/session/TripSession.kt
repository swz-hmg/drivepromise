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
import app.drivepromise.core.rest.Phrases
import app.drivepromise.core.rest.RestTracker
import app.drivepromise.core.scene.SummaryPhrases
import app.drivepromise.core.scene.VoicePhrases
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
import app.drivepromise.core.voice.ListenTrigger
import app.drivepromise.core.voice.VoiceCommands
import app.drivepromise.core.voice.VoiceIntent
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
    /** 질문(2시간 쉬기)이 끝난 뒤 이 시간 안에 ‘말하기’로 한 ‘응/아니’도 그 질문의 대답으로 봄(11/6 STT) */
    val answerWindowMs: Long = 30_000,
    /** ‘조용히’ — INFO 말을 미루는 시간 */
    val quietMs: Long = 10 * 60_000,
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
    /** 1.3.0: 실제 시계(감시 타이머) — 리플레이는 [clock]이 가상 시계라 따로 줌. 기본은 같은 시계 */
    private val realClock: Clock = clock,
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
        /** 1.3.0(C14): 감시 타이머가 완료 소식 없는 말을 멈춤 */
        fun onWatchdog(key: String, nowMs: Long) {}
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
    val voice = VoiceLoop(scheduler, arbiter, speaker, policy.usage).also { v -> v.onWatchdog = { _, key -> listener.onWatchdog(key, clock.nowMs()) } }
    /** 1.3.0(C12): 쉬기 간격은 켜 둔 ‘쉬기’ 약속(everyMin)에서 — 약속이 없으면 쉬기 말은 하지 않음(codex R05·R06) */
    private val restPromise = promises.filterIsInstance<Promise.RestEvery>().firstOrNull()
    val rest = RestTracker(restPolicyFor(policy.rest, restPromise), scheduler, ledger, enabled = restPromise != null)
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

    /** 마지막으로 말한 문장(주행 화면 표시·‘다시 들려줘’) */
    var lastSpoken: Step.Speak? = null
        private set
    var lastSpokenAtMs: Long? = null
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
    fun onSpeechDone(id: Long) {
        val now = clock.nowMs()
        voice.onDone(id, now)
        if (id == questionSpeakId) {   // 질문을 끝까지 들려줌 → 앱이 짧게 들음(B)
            questionSpeakId = null
            questionEndMs = now
            listenRequest = ListenTrigger.QUESTION
        }
    }
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
        val real = realClock.nowMs()
        val step = if (listening) voice.scheduler.tick(w, false, real) else voice.tick(w, real)   // 듣는 동안은 새 말을 시작하지 않음(정리만)
        if (step is Step.Speak) {
            lastSpoken = step; lastSpokenAtMs = now
            // 질문으로 말한 경우에만 듣기 차례를 엶 — ‘곧 도착이에요. 도착하면 꼭 쉬어요.’처럼 질문이 아닌 본 알림 뒤에는 듣지 않음(11/6 실행에서 발견, 1.2.1)
            if (voiceInput && step.key == RestTracker.KEY_MAIN && step.repeat == 0 && Phrases.isQuestion(step.text)) { questionSpeakId = step.id; questionKey = step.key; questionEndMs = null; questionRepeats = 0 }
            else if (step.key == "repeat" && repeatIsQuestion) { questionSpeakId = step.id; repeatIsQuestion = false }
            listener.onSpeak(step, now)
        }
        val st = engine.statuses
        if (st != lastStatuses) {
            lastStatuses = st
            listener.onStatuses(st, now)
        }
        return step
    }

    /**
     * 리플레이 한 걸음: 가상 시각까지의 이벤트를 넣고 tick, 끝나면 주행 끝.
     * 1.2.1: 듣는 동안([listening])은 가상 시계를 세우고, 주행이 끝나면 실시간(1배)으로 — 요약·대답이 배속에 밀려 사라지지 않게.
     */
    fun pumpReplay(p: ReplayPlayer): Step {
        p.hold(listening)
        p.pump(clock.nowMs())
        val s = tick()
        if (p.finished && phase != Phase.ENDED) end("replay_end")
        if (phase == Phase.ENDED) p.realTimeAfterEnd()
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
        if (tripStartMs != null) {   // 1.3.0d: 주행이 없었던 세션(세워 둔 채 시동 끔·수동 끝)은 요약하지 않음 — 판정할 주행이 없음
            restLine?.let { lines += it }
            SummaryPhrases.kept(lang, promises, st)?.let { lines += it }
            SummaryPhrases.unknownLine(lang, promises, st)?.let { lines += it }   // 1.3.0(C03): 판단 못 한 약속은 따로
        }
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
    /**
     * ‘다시 들려줘’ — 마지막 문장을 INFO 로 다시 예약합니다(게이트·물러서기는 그대로 적용). 말한 것이 없으면 false.
     * 같은 요청이 겹치면 앞의 것을 취소합니다.
     */
    fun repeatLast(): Boolean {
        val last = lastSpoken ?: return false
        val now = clock.nowMs()
        val text = last.text
        scheduler.cancel("repeat", now, "replaced")
        scheduler.submit(Utterance("repeat", Priority.INFO, estMs = (350L * text.length).coerceIn(1_000L, 4_000L), render = { text }), now)
        return true
    }

    // ---------------------------------------------------------------- 음성 명령(STT C안 — A 버튼 · B 물을 때만, 11/6)
    /**
     * 앱이 STT를 쓸 수 있으면 true — 2시간 쉬기 본 알림 첫 말을 질문으로 바꾸고, 그 말이 끝나면 [takeListenRequest]가
     * QUESTION을 돌려줌(앱은 그때만 짧게 들음). STT가 없으면 false: 문장·동작이 1.1.0과 같음.
     */
    var voiceInput: Boolean = false
        set(v) { field = v; rest.ask = v }
    /**
     * 앱이 마이크로 듣는 중(또는 듣기를 기다리는 중)이면 true — 그동안 새 말을 시작하지 않습니다(마이크가 우리 목소리를 듣지 않게).
     * 큐의 유효성 정리는 그대로 하고, 듣기가 끝나면(최대 6초) 밀린 말을 이어서 합니다. 이미 말하는 중인 말은 끊지 않습니다.
     */
    var listening: Boolean = false
    private var questionSpeakId: Long? = null
    private var questionKey: String? = null
    private var questionEndMs: Long? = null
    private var listenRequest: ListenTrigger? = null
    private var repeatIsQuestion = false
    private var questionRepeats = 0
    /** 마지막 음성 명령의 뜻(화면 표시·시험용) */
    var lastIntent: VoiceIntent? = null
        private set

    /** 듣기 요청을 한 번 꺼냄 — 질문이 끝났을 때만 QUESTION, 아니면 null */
    fun takeListenRequest(): ListenTrigger? = listenRequest.also { listenRequest = null }

    /**
     * 알아들은 글자 → 뜻 → 행동(대답은 INFO ‘voice.*’, 끼어든 이전 대답은 지움). 원문은 기록하지 않고 뜻만 Ledger에.
     * 질문 맥락: trigger가 QUESTION이거나, 질문이 끝난 뒤 [SessionPolicy.answerWindowMs] 안의 ‘말하기’.
     */
    fun onVoice(text: String, trigger: ListenTrigger): VoiceIntent {
        val now = clock.nowMs()
        val intent = VoiceCommands.match(text, lang)
        val qEnd = questionEndMs
        val inQuestion = questionKey != null && (trigger == ListenTrigger.QUESTION || (qEnd != null && now - qEnd <= policy.answerWindowMs))
        val q = if (inQuestion) questionKey else null
        lastIntent = intent
        ledger.accept(LedgerEvent.VoiceCommand(now, trigger.name, intent.name, q ?: ""))
        when (intent) {
            VoiceIntent.REPEAT -> {
                if (!repeatLast()) reply("nothing", VoicePhrases.nothing(lang), now)
                else if (q != null && questionRepeats < 1) { questionRepeats++; repeatIsQuestion = true }   // 질문을 다시 들려준 뒤 한 번 더 들음
            }
            VoiceIntent.STATUS -> reply("status", VoicePhrases.status(lang, promises, engine.statuses), now)
            VoiceIntent.QUIET -> {
                scheduler.muteInfoUntilMs = now + policy.quietMs
                reply("quiet", VoicePhrases.quiet(lang, policy.quietMs / 60_000), now)
            }
            VoiceIntent.YES -> if (q != null) { closeQuestion(); reply("yes", VoicePhrases.yes(lang), now) } else reply("help", VoicePhrases.help(lang), now)
            VoiceIntent.NO -> if (q != null) { closeQuestion(); reply("no", VoicePhrases.no(lang), now) } else reply("help", VoicePhrases.help(lang), now)
            VoiceIntent.UNKNOWN -> reply("unknown", VoicePhrases.unknown(lang), now)
        }
        return intent
    }

    private fun closeQuestion() {
        questionKey = null; questionEndMs = null; questionSpeakId = null; repeatIsQuestion = false
    }

    private fun reply(k: String, text: String, now: Long) {
        scheduler.cancel("voice.", now, "replaced")
        scheduler.submit(Utterance("voice.$k", Priority.INFO, estMs = (300L * text.length).coerceIn(1_200L, 4_000L), render = { text }), now)
    }

    /**
     * 주행이 끝났는데 아직 들려줄 말이 남았는가 — 말하는 중이거나, 요약(summary.*)·음성 대답(voice.*)·다시 들려줘(repeat)·계획(plan)이 대기 중.
     * 앱은 리플레이가 끝난 뒤 이것이 false가 될 때까지 실차 세션으로 돌아가지 않음(11/6: 고정 15초 뒤 돌아가며 요약을 끊던 문제, 1.2.1).
     * 주행 중에만 말하는 약속 문장(speakWhen=주행 중)은 세운 뒤 나갈 수 없으므로 세지 않음.
     */
    val pendingAfterEnd: Boolean
        get() = phase == Phase.ENDED && (scheduler.speakingKey != null || listening ||
            scheduler.pendingKeys.any { it.startsWith("summary.") || it.startsWith("voice.") || it == "repeat" || it == "plan" })

    /**
     * 1.3.0(C06): 주행이 끝났고 할 말(요약·대답)도 다 했는가 — 앱은 이때 실차 세션을 새로 만들어 다음 주행을 받음
     * (첫 주행이 끝나면 다시 D로 가도 판정이 멈춰 있던 문제, codex R01)
     */
    val readyForNext: Boolean get() = phase == Phase.ENDED && !pendingAfterEnd

    fun restState(): RestTracker.State = rest.state()
    fun restoreRest(s: RestTracker.State) = rest.restore(s, clock.nowMs())

    companion object {
        private val PARKED_OR_UNKNOWN: (World) -> Boolean = { w -> w.vehicle?.driving != true }

        /** 쉬기 약속의 간격으로 쉬기 정책을 만듦(예고는 기본 10분 전, 간격이 짧으면 간격의 ⅓) */
        fun restPolicyFor(base: RestPolicy, p: Promise.RestEvery?): RestPolicy {
            if (p == null || p.everyMin == base.targetMin) return base
            val lead = minOf(base.leadMin, p.everyMin / 3).coerceAtLeast(base.headsUpCutoffMin + 1)
            return base.copy(targetMin = p.everyMin, leadMin = lead)
        }
    }
}
