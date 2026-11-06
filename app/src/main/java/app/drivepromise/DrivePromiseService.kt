package app.drivepromise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.drivepromise.core.ledger.LedgerEvent
import app.drivepromise.core.ledger.LedgerSink
import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.plan.PreTripPlanner
import app.drivepromise.core.replay.ReplayPlayer
import app.drivepromise.core.session.SessionPolicy
import app.drivepromise.core.session.TripSession
import app.drivepromise.core.source.Clock
import app.drivepromise.core.source.Place
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.speech.Step
import app.drivepromise.core.ui.PresentationMode
import app.drivepromise.core.ui.ScreenWhileDriving
import app.drivepromise.core.voice.ListenTrigger
import app.drivepromise.core.voice.VoiceIntent
import app.drivepromise.platform.PleosCarUx
import app.drivepromise.platform.PleosFusedLocationSource
import app.drivepromise.platform.PleosLocationSource
import app.drivepromise.platform.PleosRouteSource
import app.drivepromise.platform.PleosStt
import app.drivepromise.platform.PleosVehicleSource
import app.drivepromise.platform.PleosVoice
import app.drivepromise.replay.ReplayAssets
import app.drivepromise.store.LedgerFile
import app.drivepromise.store.Prefs
import kotlin.math.abs

/**
 * 운전약속 포그라운드 서비스 — 화면과 떨어져 동작하는 ‘귀와 입’. 10 Hz로 [TripSession]을 돌리고, 어댑터(차량·내비·위치)와
 * Pleos TTS를 세션에 잇습니다. 주행 중 화면이 가려져도(DO 미인정, G1-B B1) 이 서비스는 계속 말합니다.
 *
 * 모드: REAL(실차·에뮬레이터 신호) ↔ REPLAY(assets/trips 재생, 가상 시계). 리플레이가 끝나면 요약·대답을 다 말한 뒤(4~45초) REAL로 돌아옴.
 * 리플레이 중 운전자의 대답을 듣는 동안은 가상 시계가 멈춤(1.2.1 — 60배속에서 5초 듣기가 가상 5분이 되지 않게).
 * 명령(adb·화면): am start-foreground-service -n app.drivepromise/.DrivePromiseService --es cmd replay --es name hospital_home
 *   cmd=replay name=<트립> · cmd=stop_replay · cmd=plan name=<이름> --ef lat --ef lon · cmd=end · cmd=lang value=ko|en · cmd=status
 *   cmd=screen value=on|voice(주행 중 화면 설정) · cmd=assume_do value=1|0 · cmd=repeat(다시 들려줘) · cmd=mode value=parked|drive|voice|auto(디버그 빌드만, 시연 강제)
 *   음성(11/6 STT C안): cmd=listen(‘말하기’ 버튼과 같음) · cmd=voice value=on|off(질문 뒤 듣기) · cmd=stt_retry
 *   시험: cmd=stt_audio_begin label=<이름> → cmd=stt_audio_chunk pcm=<base64> … → cmd=stt_audio_end [trigger=question|button] (Pleos 인식기에 PCM 주입)
 *         cmd=stt_text text=<글자> [trigger=…] (인식기를 건너뛰고 글자를 바로 — 뜻 풀이·대답만 시험)
 *
 * 화면 모드(11/5 Q036, 화면모드전환설계 v0.1): 세션 단계 + 플랫폼 DO 판정(+가정) + 사용자 설정 → PARKED / DRIVE_DO / DRIVE_VOICE.
 * 정차 화면(MainActivity)이 DRIVE_DO 로 바뀌는 순간 주행 화면(DriveActivity, DO 선언)을 띄우고, 주행 화면은 모드가 바뀌면 스스로 닫힘.
 * 주행 화면이 UX 제한 중에 가려지면(onStop) 차단으로 보고 즉시 DRIVE_VOICE — 음성은 서비스에서 계속.
 *
 * 듣기(11/6 Q040, STT C안): A ‘말하기’ 버튼(최대 6초) + B 앱이 물은 말(2시간 쉬기 질문)이 끝난 뒤 5초. 늘 듣지 않음.
 * 우리가 말하는 중·내비가 말하는 중에는 마이크를 열지 않고 끝나기를 기다리며, 듣는 동안은 세션이 새 말을 시작하지 않습니다.
 * 들은 글자 → 코어(TripSession.onVoice)가 뜻을 풀고 대답(INFO ‘voice.*’). 질문 뒤 아무 말이 없으면 아무것도 하지 않음.
 */
class DrivePromiseService : Service() {
    enum class Mode { REAL, REPLAY }

    inner class LocalBinder : Binder() {
        val service: DrivePromiseService get() = this@DrivePromiseService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    lateinit var prefs: Prefs
        private set
    lateinit var ledgerFile: LedgerFile
        private set
    private var voice: PleosVoice? = null
    private var vehicleSrc: PleosVehicleSource? = null
    private var routeSrc: PleosRouteSource? = null
    private var locSrc: PleosFusedLocationSource? = null   // 주: Pleos FusedLocation · 대체: LocationManager
    private var zoneWatch: AutoCloseable? = null           // 약속 구역 Pleos 근접 알림(이중 확인)
    var session: TripSession? = null
        private set
    var replay: ReplayPlayer? = null
        private set
    var replayName: String? = null
        private set
    var mode = Mode.REAL
        private set
    private var backToReal: Runnable? = null
    private var replayHeld = false
    private var lastSave = 0L
    private var carUx: PleosCarUx? = null
    private var stt: PleosStt? = null
    private var pendingListen: ListenTrigger? = null   // 말이 끝나면 들을 차례(버튼·질문)
    private var pendingSince = 0L
    private var cueing = false                         // ‘띵’ 뒤 마이크를 열기까지(그동안도 새 말을 시작하지 않음)
    private var cueGen = 0
    private var navPlayers = 0
    private var afterQuestion = true
    private var tone: ToneGenerator? = null
    /** 화면 표시용 — 마지막으로 들은 말과 뜻 */
    var lastVoice: String? = null
        private set
    var lastVoiceAt = 0L
        private set
    // 약속 구역 이중 확인(코어 상태가 바뀐 시각 — Pleos 근접 알림과의 시간 차를 셈)
    private var zoneId: String? = null
    private var zoneCoreInside: Boolean? = null
    private var zoneCoreAt = 0L
    val screen = ScreenModeController { prev, next, why ->
        L.log("mode", "from" to prev, "to" to next, "why" to why, "cap" to screenCapabilityText())
    }
    val isDebuggable: Boolean get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val loop = object : Runnable {
        override fun run() {
            step()
            main.postDelayed(this, 100)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        prefs = Prefs(this)
        ledgerFile = LedgerFile(this)
        afterQuestion = prefs.listenAfterQuestion
        val v = PleosVoice(this, main,
            onNavPlayers = { n -> navPlayers = n; if (n > 0) stt?.cancel("nav"); session?.onNavPlayers(n) },
            onFocus = { f -> session?.onFocus(f) },
            onDone = { id -> session?.onSpeechDone(id) },
            onError = { id, c -> session?.onSpeechError(id, c) })
        v.start()
        voice = v
        stt = PleosStt(this, main, isDebuggable) { text, trig, src -> onHeard(text, trig, src) }.also { it.start(prefs.lang) }
        vehicleSrc = PleosVehicleSource(this, main)
        routeSrc = PleosRouteSource(this, main)
        locSrc = PleosFusedLocationSource(this, main, fallback = PleosLocationSource(this, main))
        screen.user = prefs.screenWhileDriving
        screen.assumeDo = prefs.assumeDo
        val ux = PleosCarUx(this, main) { screen.update(session?.phase == TripSession.Phase.DRIVING, "uxr") }
        ux.start()
        carUx = ux
        screen.platformOptimized = ux.isOptimized(DriveActivity::class.java.name)
        L.log("screen_init", "cap" to screenCapabilityText(), "user" to screen.user, "assumeDo" to screen.assumeDo, "debug" to isDebuggable)
        startReal()
        main.post(loop)
        L.log("svc", "s" to "create", "promises" to prefs.promises().joinToString("+") { it.id }, "lang" to prefs.lang)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent?.getStringExtra("cmd")
        if (cmd != null) {
            L.log("cmd", "cmd" to cmd, "name" to intent.getStringExtra("name"))
            try {
                when (cmd) {
                    "replay" -> startReplay(intent.getStringExtra("name") ?: "hospital_home")
                    "stop_replay" -> startReal()
                    "plan" -> plan(intent.getStringExtra("name") ?: "판교역", intent.getDoubleExtra("lat", intent.getFloatExtra("lat", 37.3948f).toDouble()), intent.getDoubleExtra("lon", intent.getFloatExtra("lon", 127.1112f).toDouble()))
                    "end" -> endTrip()
                    "lang" -> setLang(if (intent.getStringExtra("value") == "en") Lang.EN else Lang.KO)
                    "status" -> logStatus()
                    "screen" -> setScreenWhileDriving(if (intent.getStringExtra("value") == "voice") ScreenWhileDriving.VOICE_ONLY else ScreenWhileDriving.ON)
                    "assume_do" -> setAssumeDo(intent.getStringExtra("value") != "0")
                    "repeat" -> repeatLast()
                    "mode" -> forceMode(intent.getStringExtra("value"))
                    "listen" -> pushToTalk()
                    "voice" -> setListenAfterQuestion(intent.getStringExtra("value") != "off")
                    "stt_retry" -> stt?.retry()
                    "stt_text" -> injectText(intent.getStringExtra("text") ?: "", triggerOf(intent.getStringExtra("trigger")))
                    "stt_audio_begin" -> stt?.feedBegin(intent.getStringExtra("label") ?: "clip")
                    "stt_audio_chunk" -> stt?.feedChunk(intent.getStringExtra("pcm") ?: "")?.let { n -> L.log("stt_feed", "s" to "chunk", "total" to n) }
                    "stt_audio_end" -> stt?.feedEnd(triggerOf(intent.getStringExtra("trigger")) ?: ListenTrigger.BUTTON)
                }
            } catch (e: Throwable) {
                L.log("cmd_error", "cmd" to cmd, "err" to e)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        L.log("svc", "s" to "destroy")
        main.removeCallbacksAndMessages(null)
        saveRest()
        zoneWatch?.close()
        zoneWatch = null
        session?.close()
        session = null
        locSrc?.shutdown()
        vehicleSrc?.shutdown()
        routeSrc?.shutdown()
        carUx?.stop()
        stt?.release()
        stt = null
        try { tone?.release() } catch (_: Throwable) {}
        voice?.release()
        voice = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 모드
    fun startReal() {
        backToReal?.let { main.removeCallbacks(it) }
        backToReal = null
        closeSession()
        mode = Mode.REAL
        replay = null
        replayName = null
        val s = newSession(Clock { System.currentTimeMillis() }, forReplay = false)
        prefs.restState()?.let { st -> s.restoreRest(st); L.log("rest_restore", "drivingMin" to st.drivingMs / 60_000, "main" to st.mainDone) }
        s.attach(locSrc, vehicleSrc, routeSrc)   // 차량·내비 어댑터는 한 번만 초기화되고 세션마다 받는 쪽만 바뀜
        session = s
        watchZone(s)
        L.log("session", "mode" to "real", "promises" to s.promises.joinToString("+") { it.id }, "vehicle" to vehicleSrc?.sources?.joinToString("+"))
    }

    fun startReplay(name: String) {
        val trip = ReplayAssets.load(this, name)
        backToReal?.let { main.removeCallbacks(it) }
        backToReal = null
        closeSession()
        mode = Mode.REPLAY
        val p = ReplayPlayer(trip, Clock { System.currentTimeMillis() })
        replay = p
        replayHeld = false
        replayName = name
        val s = newSession(p.clock, forReplay = true)
        s.attachReplay(p)
        session = s
        L.log("replay_start", "name" to name, "trip" to trip.meta.name, "speedup" to p.speedup, "startMs" to trip.meta.baseEpochMs, "endSec" to (trip.endMs - trip.meta.baseEpochMs) / 1000)
    }

    private fun closeSession() {
        stopListening()
        stt?.cancel("session")
        zoneWatch?.close()
        zoneWatch = null
        session?.close()
        session = null
    }

    /**
     * 약속 구역에 Pleos 근접 알림을 걺 — ENTER/EXIT를 코어 판정(거리)과 나란히 기록(이중 확인). 코어 결정은 바꾸지 않음.
     * 11/6: 근접 알림이 코어보다 47~88 ms 먼저 와서(1.1.0 실측) 그 자리에서 비교하면 늘 ‘불일치’ → [XCHECK_DELAY_MS] 뒤에 비교하고,
     * 코어 상태가 같은 쪽으로 바뀐 시각과의 차(lagMs, 양수 = 코어가 늦음)를 함께 남김.
     */
    private fun watchZone(s: TripSession) {
        val z = s.promises.filterIsInstance<Promise.StayInZone>().firstOrNull() ?: return
        zoneId = z.id
        zoneCoreInside = null
        zoneWatch = locSrc?.watchZone(z.lat, z.lon, z.radiusM) { inside ->
            val tP = System.currentTimeMillis()
            main.postDelayed({
                val core = session?.statuses?.get(z.id)
                val known = core != null && core != PromiseStatus.UNKNOWN && core != PromiseStatus.NOT_APPLICABLE
                val lag = if (zoneCoreInside == inside && abs(zoneCoreAt - tP) <= 10_000) zoneCoreAt - tP else null
                L.log("zone_xcheck", "pleos" to (if (inside) "inside" else "outside"), "core" to core,
                    "agree" to (known && inside == (core != PromiseStatus.MISSED)), "lagMs" to lag, "afterMs" to XCHECK_DELAY_MS)
            }, XCHECK_DELAY_MS)
        }
    }

    /** 코어의 약속 구역 상태(안/밖)가 바뀐 시각을 적어 둠 — [watchZone]의 시간 차 계산용 */
    private fun noteZoneCore(statuses: Map<String, PromiseStatus>) {
        val id = zoneId ?: return
        val st = statuses[id] ?: return
        if (st == PromiseStatus.UNKNOWN || st == PromiseStatus.NOT_APPLICABLE) return
        val ins = st != PromiseStatus.MISSED
        if (ins != zoneCoreInside) { zoneCoreInside = ins; zoneCoreAt = System.currentTimeMillis() }
    }

    /** 정차 화면 부제용 — 지금 좌표를 넣는 원천과 Pleos 근접 알림이 알려 준 구역 안/밖 */
    fun locationActive(): PleosFusedLocationSource.Active = if (mode == Mode.REAL) locSrc?.active ?: PleosFusedLocationSource.Active.NONE else PleosFusedLocationSource.Active.NONE
    fun zoneInsideByPleos(): Boolean? = if (mode == Mode.REAL) locSrc?.zoneInside else null

    private fun newSession(clock: Clock, forReplay: Boolean): TripSession {
        val ext: LedgerSink = if (forReplay) LedgerSink { } else ledgerFile   // 리플레이 기록은 세션 안(메모리)에만 — 실주행 지표를 더럽히지 않게
        return TripSession(prefs.promises(), voice!!, clock, prefs.lang, SessionPolicy(), ext, object : TripSession.Listener {
            override fun onPhase(phase: TripSession.Phase, nowMs: Long) {
                L.log("phase", "phase" to phase, "now" to nowMs, "mode" to mode)
                if (phase == TripSession.Phase.ENDED && mode == Mode.REPLAY) scheduleBackToReal()
                if (phase != TripSession.Phase.DRIVING) saveRest()
            }

            override fun onSpeak(step: Step.Speak, nowMs: Long) {
                L.log("speak", "key" to step.key, "id" to step.id, "short" to step.short, "repeat" to step.repeat, "attempt" to step.attempt, "now" to nowMs, "text" to step.text)
            }

            override fun onStatuses(statuses: Map<String, PromiseStatus>, nowMs: Long) {
                L.log("status", "now" to nowMs, "s" to statuses.entries.joinToString(",") { "${it.key}:${it.value}" })
                if (mode == Mode.REAL) noteZoneCore(statuses)
            }

            override fun onPlan(plan: PreTripPlanner.Plan, nowMs: Long) {
                L.log("plan", "choice" to plan.choice, "error" to plan.error, "lines" to plan.lines.joinToString("|"))
            }

            override fun onSummary(summary: TripSession.Summary) {
                L.log("summary", "reason" to summary.reason, "drivingMin" to summary.drivingMs / 60_000,
                    "s" to summary.statuses.entries.joinToString(",") { "${it.key}:${it.value}" },
                    "lines" to summary.lines.joinToString("|"), "digest" to summary.digestLines.joinToString("|"),
                    "delivery" to summary.rest.deliveryRate, "compliance" to summary.rest.complianceRate)
            }

            override fun onEvent(e: LedgerEvent) {
                if (e is LedgerEvent.RestTaken || e is LedgerEvent.TripEnded) main.post { saveRest() }
            }
        })
    }

    /**
     * 리플레이가 끝난 뒤 실차 세션으로 돌아가기 — 1.2.1: 고정 15초가 아니라 요약·대답을 다 말하고 듣기도 끝난 뒤(최소 [BACK_MIN_MS], 최대 [BACK_MAX_MS]).
     * 11/6 실행: 질문이 아닌 문장 뒤 듣기(12초) 때문에 요약이 밀린 채 15초가 되어 요약이 통째로 사라짐.
     */
    private fun scheduleBackToReal() {
        backToReal?.let { main.removeCallbacks(it) }
        val endedAt = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (mode != Mode.REPLAY || backToReal !== this) return
                val s = session
                val waited = System.currentTimeMillis() - endedAt
                val busy = s != null && (s.pendingAfterEnd || voice?.isSpeaking == true || pendingListen != null || cueing || stt?.listening == true)
                if (busy && waited < BACK_MAX_MS) { main.postDelayed(this, 500); return }
                L.log("replay_back", "why" to (if (busy) "timeout" else "idle"), "waitedMs" to waited, "pending" to s?.scheduler?.pendingKeys?.joinToString("+"))
                startReal()
            }
        }
        backToReal = r
        main.postDelayed(r, BACK_MIN_MS)
    }

    private fun step() {
        val s = session ?: return
        voiceStep(s)
        val p = replay
        if (mode == Mode.REPLAY && p != null) {
            s.pumpReplay(p)
            if (p.held != replayHeld) { replayHeld = p.held; L.log("replay_hold", "on" to p.held, "heldMs" to p.heldMs, "phase" to s.phase) }   // 듣는 동안 가상 시계 멈춤(1.2.1)
        } else s.tick()
        screen.update(s.phase == TripSession.Phase.DRIVING)
        val now = System.currentTimeMillis()
        if (mode == Mode.REAL && now - lastSave > 30_000) saveRest()
    }

    private fun saveRest() {
        if (mode != Mode.REAL) return
        val s = session ?: return
        prefs.saveRest(s.restState())
        lastSave = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------- 화면·명령
    fun plan(name: String, lat: Double, lon: Double) {
        val s = session ?: return
        val r = (if (mode == Mode.REPLAY) replay?.route else routeSrc) ?: return
        s.plan(Place(name, lat, lon), r)
    }

    fun endTrip() {
        session?.end("manual")
    }

    fun setLang(l: Lang) {
        prefs.lang = l
        session?.lang = l
        voice?.setLang(l)
        stt?.setLang(l)
        L.log("lang", "lang" to l)
    }

    fun setPromiseOn(id: String, on: Boolean) {
        prefs.setPromiseOn(id, on)
        if (mode == Mode.REAL && session?.phase == TripSession.Phase.PARKED) startReal()   // 세워 둔 동안만 바로 반영
    }

    // ---------------------------------------------------------------- 화면 모드
    /** 주행 화면이 UX 제한 중에 가려짐(DriveActivity.onStop) → 차단 감지 → 즉시 음성만. 시연(세워 둔 채 리플레이)에서는 제한이 없어 해당 없음 */
    fun onDriveScreenStopped(finishing: Boolean) {
        val requires = carUx?.requiresDo == true
        L.log("screen", "s" to "drive_stop", "finishing" to finishing, "requiresDO" to requires, "mode" to screen.mode)
        if (!finishing && requires && screen.mode == PresentationMode.DRIVE_DO) {
            screen.markBlocked("uxr_hidden")
            screen.update(session?.phase == TripSession.Phase.DRIVING, "blocked")
        }
    }

    fun setScreenWhileDriving(v: ScreenWhileDriving) {
        prefs.screenWhileDriving = v
        screen.user = v
        screen.update(session?.phase == TripSession.Phase.DRIVING, "setting")
        L.log("screen", "s" to "setting", "user" to v)
    }

    fun setAssumeDo(v: Boolean) {
        prefs.assumeDo = v
        screen.assumeDo = v
        screen.update(session?.phase == TripSession.Phase.DRIVING, "assume")
        L.log("screen", "s" to "assume_do", "value" to v)
    }

    /** 시연·실측용 강제 모드(디버그 빌드만): parked · drive · voice · auto */
    fun forceMode(value: String?) {
        if (!isDebuggable) { L.log("screen", "s" to "force_denied", "value" to value); return }
        screen.force = when (value) { "parked" -> PresentationMode.PARKED; "drive" -> PresentationMode.DRIVE_DO; "voice" -> PresentationMode.DRIVE_VOICE; else -> null }
        if (value == "auto") screen.clearBlocked()
        screen.update(session?.phase == TripSession.Phase.DRIVING, "force:$value")
    }

    /** ‘다시 들려줘’ — 마지막 문장을 코어가 다시 예약(게이트·물러서기 적용) */
    fun repeatLast(): Boolean {
        val ok = session?.repeatLast() ?: false
        L.log("repeat", "ok" to ok, "text" to session?.lastSpoken?.text)
        return ok
    }

    // ---------------------------------------------------------------- 듣기(STT C안: A 버튼 + B 물을 때만)
    /** 10 Hz — 세션에 ‘들을 수 있음’을 알리고, 질문이 끝났으면 듣기를 예약하고, 말이 끝나기를 기다리던 듣기를 시작 */
    private fun voiceStep(s: TripSession) {
        val st = stt
        val usable = st?.ready == true
        s.voiceInput = usable && afterQuestion
        s.takeListenRequest()?.let { t ->
            if (usable && afterQuestion) requestListen(t) else L.log("stt_listen", "ok" to false, "why" to (if (!usable) "stt_${st?.state}" else "setting_off"), "trigger" to t)
        }
        pumpListen()
        s.listening = pendingListen != null || cueing || st?.listening == true
    }

    /** ‘말하기’(A) — 누르면 최대 [BUTTON_LISTEN_MS] 들음. 우리 말·내비 안내가 끝난 뒤에 열고, 듣는 중에 다시 누르면 그만 */
    fun pushToTalk(): Boolean {
        val st = stt
        if (st == null || !st.ready) {
            L.log("ptt", "ok" to false, "why" to (st?.state ?: "none"), "err" to st?.lastError)
            if (st?.state == PleosStt.State.ERROR) st.retry()   // 오류 상태에서 누르면 다시 초기화(권한을 나중에 준 경우 등)
            return false
        }
        if (st.listening || pendingListen != null || cueing) {
            stopListening()
            st.cancel("ptt_again")
            L.log("ptt", "ok" to true, "s" to "stop")
            return true
        }
        L.log("ptt", "ok" to true, "s" to "start")
        requestListen(ListenTrigger.BUTTON)
        return true
    }

    /** 기다리던 듣기·‘띵’ 뒤 열 마이크를 모두 접음(듣는 중인 인식은 호출한 쪽이 cancel) */
    private fun stopListening() {
        pendingListen = null
        cueing = false
        cueGen++
    }

    private fun requestListen(t: ListenTrigger) {
        pendingListen = t
        pendingSince = System.currentTimeMillis()
        L.log("stt_wait", "trigger" to t, "speaking" to (voice?.isSpeaking == true), "nav" to navPlayers)
        pumpListen()
    }

    private fun pumpListen() {
        val t = pendingListen ?: return
        val st = stt ?: run { pendingListen = null; return }
        val waited = System.currentTimeMillis() - pendingSince
        if (voice?.isSpeaking == true || navPlayers > 0) {   // 마이크가 우리 목소리·내비 안내를 듣지 않게 — 끝나기를 기다림
            if (waited > (if (t == ListenTrigger.BUTTON) BUTTON_WAIT_MS else QUESTION_WAIT_MS)) {
                pendingListen = null
                L.log("stt_listen", "ok" to false, "why" to "busy", "trigger" to t, "waitedMs" to waited, "nav" to navPlayers)
            }
            return
        }
        pendingListen = null
        cueing = true
        val g = ++cueGen
        cue()
        main.postDelayed({   // ‘띵’ 소리가 끝난 뒤 마이크를 엶(소리를 듣지 않게)
            if (g != cueGen) return@postDelayed
            cueing = false
            if (!st.listening && session != null) st.listen(t, if (t == ListenTrigger.BUTTON) BUTTON_LISTEN_MS else QUESTION_LISTEN_MS)
        }, CUE_MS)
    }

    /** 듣기 시작 신호 — 짧은 ‘띵’(화면을 보지 않아도 지금 말하면 된다는 것을 알게). 실패해도 듣기는 함 */
    private fun cue() {
        try {
            val tg = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).also { tone = it }
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Throwable) {
            L.log("cue_error", "err" to e)
        }
    }

    /** 들은 글자 → 코어. 버튼을 눌렀는데 말이 없으면 ‘잘 못 들었어요’, 질문 뒤 침묵은 대답 없음(아무것도 하지 않음) */
    private fun onHeard(text: String, trig: ListenTrigger, src: String) {
        val s = session ?: return
        if (text.isBlank() && trig == ListenTrigger.QUESTION) {
            L.log("voice_cmd", "intent" to "NONE", "trigger" to trig, "src" to src)
            return
        }
        val i = s.onVoice(text, trig)
        lastVoice = (if (text.isBlank()) "…" else "‘${text.take(24)}’") + " → " + intentText(i)
        lastVoiceAt = System.currentTimeMillis()
        L.log("voice_cmd", "intent" to i, "trigger" to trig, "src" to src, "len" to text.length)
    }

    /** 시험용 — 인식기를 건너뛰고 글자를 바로 넣음(듣는 중이면 그 차례의 까닭을 이어받음) */
    fun injectText(text: String, trig: ListenTrigger?) {
        val st = stt
        val t = trig ?: st?.trigger ?: pendingListen ?: ListenTrigger.BUTTON
        st?.cancel("inject")
        stopListening()
        L.log("stt_result", "trigger" to t, "src" to "inject", "why" to "inject", "len" to text.length, "text" to text)
        onHeard(text, t, "inject")
    }

    private fun triggerOf(v: String?): ListenTrigger? = when (v?.lowercase()) {
        "question", "q", "b" -> ListenTrigger.QUESTION
        "button", "ptt", "a" -> ListenTrigger.BUTTON
        else -> null
    }

    private fun intentText(i: VoiceIntent): String {
        val ko = prefs.lang == Lang.KO
        return when (i) {
            VoiceIntent.REPEAT -> if (ko) "다시 들려줌" else "repeat"
            VoiceIntent.STATUS -> if (ko) "약속 상태" else "status"
            VoiceIntent.QUIET -> if (ko) "10분 조용히" else "quiet 10 min"
            VoiceIntent.YES -> if (ko) "네(쉬기)" else "yes (rest)"
            VoiceIntent.NO -> if (ko) "아니요" else "no"
            VoiceIntent.UNKNOWN -> if (ko) "못 알아들음" else "not understood"
        }
    }

    fun setListenAfterQuestion(v: Boolean) {
        prefs.listenAfterQuestion = v
        afterQuestion = v
        if (!v && (pendingListen == ListenTrigger.QUESTION || stt?.trigger == ListenTrigger.QUESTION)) { stopListening(); stt?.cancel("setting_off") }
        L.log("voice_setting", "afterQuestion" to v)
    }

    /** 화면 표시용 — 말하기 버튼 상태 */
    fun sttState(): PleosStt.State = stt?.state ?: PleosStt.State.OFF
    fun sttListening(): Boolean = stt?.listening == true || pendingListen != null
    fun sttError(): String? = stt?.lastError
    fun listenAfterQuestion(): Boolean = afterQuestion

    /** 권한을 받은 뒤 — STT가 실패 상태면 다시 초기화 */
    fun onPermissionsChanged() {
        stt?.retry()
    }

    fun uxText(): String = "requiresDO=${carUx?.requiresDo} active=${carUx?.activeRestrictions} maxLen=${carUx?.maxStringLength}"
    fun maxStringLength(): Int = carUx?.maxStringLength ?: 120
    private fun screenCapabilityText(): String = "${screen.capability}(platform=${screen.platformOptimized},assume=${screen.assumeDo},blocked=${screen.blockedSeen})"

    fun replayNames(): List<String> = ReplayAssets.list(this)

    /** 차량 신호 원천 이름(화면 표시용) — aaos:gear, sdk:Wiper.registerWiperState … */
    fun vehicleSources(): List<String> = vehicleSrc?.sources ?: emptyList()

    fun logStatus() {
        val s = session
        L.log("status_now", "mode" to mode, "phase" to s?.phase, "drivingMin" to (s?.drivingMs ?: 0) / 60_000,
            "s" to s?.statuses?.entries?.joinToString(",") { "${it.key}:${it.value}" }, "tts" to voice?.ready, "replay" to replayName,
            "screen" to screen.mode, "cap" to screenCapabilityText(), "ux" to uxText(),
            "loc" to locSrc?.active, "fixes" to "${locSrc?.pleosFixes ?: 0}+${locSrc?.fallbackFixes ?: 0}", "zoneP" to locSrc?.zoneInside,
            "stt" to stt?.state, "sttErr" to stt?.lastError, "afterQ" to afterQuestion, "heard" to stt?.lastHeard?.length,
            "hold" to replay?.heldMs, "busyAfterEnd" to s?.pendingAfterEnd)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "운전약속", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH)
            .setContentTitle("운전약속")
            .setContentText("약속을 지켜 드릴 준비가 됐어요")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTI_ID, n)
    }

    companion object {
        const val CH = "drivepromise"
        const val NOTI_ID = 1
        /** 근접 알림 뒤 코어와 비교하기까지(코어는 다음 위치 갱신·10 Hz 판정 뒤에 바뀜 — 실측 1.1~1.3초) */
        const val XCHECK_DELAY_MS = 3_000L
        /** 듣는 시간 — 버튼(A)은 조금 길게, 질문 뒤(B)는 짧게(‘응’·‘아니’) */
        const val BUTTON_LISTEN_MS = 6_000L
        const val QUESTION_LISTEN_MS = 5_000L
        /** 우리 말·내비 안내가 끝나기를 기다리는 최대 시간 — 넘으면 이번 듣기는 포기 */
        const val BUTTON_WAIT_MS = 6_000L
        const val QUESTION_WAIT_MS = 2_000L
        const val CUE_MS = 250L
        /** 리플레이가 끝난 뒤 실차 세션으로 돌아가기까지 — 최소(화면에 요약이 보이게)·최대(말·듣기가 끝나지 않아도) */
        const val BACK_MIN_MS = 4_000L
        const val BACK_MAX_MS = 45_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DrivePromiseService::class.java))
        }
    }
}
