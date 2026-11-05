package app.drivepromise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
        s.attach(locSrc, vehicleSrc, routeSrc)
        session = s
        L.log("session", "mode" to "real", "promises" to s.promises.joinToString("+") { it.id })
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

    fun replayNames(): List<String> = ReplayAssets.list(this)

    fun logStatus() {
        val s = session
        L.log("status_now", "mode" to mode, "phase" to s?.phase, "drivingMin" to (s?.drivingMs ?: 0) / 60_000,
            "s" to s?.statuses?.entries?.joinToString(",") { "${it.key}:${it.value}" }, "tts" to voice?.ready, "replay" to replayName)
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
