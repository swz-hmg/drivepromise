package app.drivepromise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.drivepromise.core.ledger.LedgerEvent
import app.drivepromise.core.ledger.LedgerSink
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
import app.drivepromise.platform.PleosCarUx
import app.drivepromise.platform.PleosLocationSource
import app.drivepromise.platform.PleosRouteSource
import app.drivepromise.platform.PleosVehicleSource
import app.drivepromise.platform.PleosVoice
import app.drivepromise.replay.ReplayAssets
import app.drivepromise.store.LedgerFile
import app.drivepromise.store.Prefs

/**
 * 운전약속 포그라운드 서비스 — 화면과 떨어져 동작하는 ‘귀와 입’. 10 Hz로 [TripSession]을 돌리고, 어댑터(차량·내비·위치)와
 * Pleos TTS를 세션에 잇습니다. 주행 중 화면이 가려져도(DO 미인정, G1-B B1) 이 서비스는 계속 말합니다.
 *
 * 모드: REAL(실차·에뮬레이터 신호) ↔ REPLAY(assets/trips 재생, 가상 시계). 리플레이가 끝나면 15초 뒤 REAL로 돌아옴.
 * 명령(adb·화면): am start-foreground-service -n app.drivepromise/.DrivePromiseService --es cmd replay --es name hospital_home
 *   cmd=replay name=<트립> · cmd=stop_replay · cmd=plan name=<이름> --ef lat --ef lon · cmd=end · cmd=lang value=ko|en · cmd=status
 *   cmd=screen value=on|voice(주행 중 화면 설정) · cmd=assume_do value=1|0 · cmd=repeat(다시 들려줘) · cmd=mode value=parked|drive|voice|auto(디버그 빌드만, 시연 강제)
 *
 * 화면 모드(11/5 Q036, 화면모드전환설계 v0.1): 세션 단계 + 플랫폼 DO 판정(+가정) + 사용자 설정 → PARKED / DRIVE_DO / DRIVE_VOICE.
 * 정차 화면(MainActivity)이 DRIVE_DO 로 바뀌는 순간 주행 화면(DriveActivity, DO 선언)을 띄우고, 주행 화면은 모드가 바뀌면 스스로 닫힘.
 * 주행 화면이 UX 제한 중에 가려지면(onStop) 차단으로 보고 즉시 DRIVE_VOICE — 음성은 서비스에서 계속.
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
    private var locSrc: PleosLocationSource? = null
    var session: TripSession? = null
        private set
    var replay: ReplayPlayer? = null
        private set
    var replayName: String? = null
        private set
    var mode = Mode.REAL
        private set
    private var backToReal: Runnable? = null
    private var lastSave = 0L
    private var carUx: PleosCarUx? = null
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
        val v = PleosVoice(this, main,
            onNavPlayers = { n -> session?.onNavPlayers(n) },
            onFocus = { f -> session?.onFocus(f) },
            onDone = { id -> session?.onSpeechDone(id) },
            onError = { id, c -> session?.onSpeechError(id, c) })
        v.start()
        voice = v
        vehicleSrc = PleosVehicleSource(this, main)
        routeSrc = PleosRouteSource(this, main)
        locSrc = PleosLocationSource(this, main)
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
        session?.close()
        session = null
        vehicleSrc?.shutdown()
        routeSrc?.shutdown()
        carUx?.stop()
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
        replayName = name
        val s = newSession(p.clock, forReplay = true)
        s.attachReplay(p)
        session = s
        L.log("replay_start", "name" to name, "trip" to trip.meta.name, "speedup" to p.speedup, "startMs" to trip.meta.baseEpochMs, "endSec" to (trip.endMs - trip.meta.baseEpochMs) / 1000)
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun newSession(clock: Clock, forReplay: Boolean): TripSession {
        val ext: LedgerSink = if (forReplay) LedgerSink { } else ledgerFile   // 리플레이 기록은 세션 안(메모리)에만 — 실주행 지표를 더럽히지 않게
        return TripSession(prefs.promises(), voice!!, clock, prefs.lang, SessionPolicy(), ext, object : TripSession.Listener {
            override fun onPhase(phase: TripSession.Phase, nowMs: Long) {
                L.log("phase", "phase" to phase, "now" to nowMs, "mode" to mode)
                if (phase == TripSession.Phase.ENDED && mode == Mode.REPLAY) {
                    val r = Runnable { if (mode == Mode.REPLAY) startReal() }
                    backToReal = r
                    main.postDelayed(r, 15_000)
                }
                if (phase != TripSession.Phase.DRIVING) saveRest()
            }

            override fun onSpeak(step: Step.Speak, nowMs: Long) {
                L.log("speak", "key" to step.key, "id" to step.id, "short" to step.short, "repeat" to step.repeat, "attempt" to step.attempt, "now" to nowMs, "text" to step.text)
            }

            override fun onStatuses(statuses: Map<String, PromiseStatus>, nowMs: Long) {
                L.log("status", "now" to nowMs, "s" to statuses.entries.joinToString(",") { "${it.key}:${it.value}" })
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

    private fun step() {
        val s = session ?: return
        val p = replay
        if (mode == Mode.REPLAY && p != null) s.pumpReplay(p) else s.tick()
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
            "screen" to screen.mode, "cap" to screenCapabilityText(), "ux" to uxText())
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

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DrivePromiseService::class.java))
        }
    }
}
