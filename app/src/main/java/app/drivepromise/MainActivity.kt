package app.drivepromise

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.scene.SummaryPhrases
import app.drivepromise.core.session.TripSession
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.ui.PresentationMode
import app.drivepromise.core.ui.ScreenWhileDriving

/**
 * 정차 화면(DO 선언 없음) — 세워 둔 동안 보는 화면: 약속 타일 5개, 출발 전 계획 카드, 주행 끝 요약, 설정(주행 중 화면·DO 가정·한/영),
 * 리플레이 버튼, 기록. 주행이 시작되고 모드가 DRIVE_DO 가 되면 주행 화면(DriveActivity, DO 선언)을 한 번 띄웁니다(11/5 Q036).
 * 모드가 DRIVE_VOICE 면 아무것도 띄우지 않고(플랫폼이 이 화면을 가림) 서비스가 음성으로만 동작합니다. 글자는 크게(시니어), 바탕은 어둡게.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var svc: DrivePromiseService? = null
    private lateinit var subtitle: TextView
    private lateinit var tiles: GridLayout
    private lateinit var planCard: TextView
    private lateinit var summaryCard: TextView
    private lateinit var logView: TextView
    private lateinit var replayRow: LinearLayout
    private var replayButtonsBuilt = false
    private var launchedSeq = 0
    private var resumed = false
    private lateinit var screenBtn: Button
    private lateinit var assumeBtn: Button
    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 500)
        }
    }
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            svc = (service as? DrivePromiseService.LocalBinder)?.service
            L.log("act", "s" to "bound")
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS), 1)
        DrivePromiseService.start(this)
        L.log("act", "s" to "create")
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DrivePromiseService::class.java), conn, Context.BIND_AUTO_CREATE)
        main.post(refresh)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(refresh)
        try { unbindService(conn) } catch (_: Throwable) {}
        svc = null
    }

    // ---------------------------------------------------------------- 화면 만들기(레이아웃 XML 없이)
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        root.addView(text("운전약속", 34f, bold = true, color = Color.WHITE))
        subtitle = text("준비 중…", 20f, color = SUB)
        root.addView(subtitle)
        tiles = GridLayout(this).apply { columnCount = 2; setPadding(0, dp(16), 0, dp(8)) }
        root.addView(tiles)
        root.addView(text("출발 전 계획", 18f, color = SUB))
        planCard = card("목적지를 고르면 약속에 비추어 경로와 출발 시한을 알려 드려요.")
        root.addView(planCard)
        root.addView(text("주행 끝 요약", 18f, color = SUB))
        summaryCard = card("아직 끝난 주행이 없어요.")
        root.addView(summaryCard)
        root.addView(row(
            button("경로 계획: 판교역") { svc?.plan("판교역", 37.3948, 127.1112) },
            button("주행 끝") { svc?.endTrip() },
            button("한/영") { svc?.let { it.setLang(if (it.prefs.lang == Lang.KO) Lang.EN else Lang.KO) } },
            button("실차 모드로") { svc?.startReal() },
        ))
        screenBtn = button("주행 중 화면: 켜기") { svc?.let { it.setScreenWhileDriving(if (it.prefs.screenWhileDriving == ScreenWhileDriving.ON) ScreenWhileDriving.VOICE_ONLY else ScreenWhileDriving.ON) } }
        assumeBtn = button("DO 인정 가정: 켬") { svc?.let { it.setAssumeDo(!it.prefs.assumeDo) } }
        root.addView(row(
            screenBtn,
            assumeBtn,
            button("주행 화면 미리보기") { startActivity(DriveActivity.intent(this, preview = true)) },
        ))
        replayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(replayRow); isHorizontalScrollBarEnabled = false })
        root.addView(text("기록", 18f, color = SUB))
        logView = text("", 13f, color = SUB).apply { typeface = Typeface.MONOSPACE }
        root.addView(logView)
        return ScrollView(this).apply { addView(root); setBackgroundColor(BG) }
    }

    private fun render() {
        val s = svc
        val ses = s?.session
        if (s == null || ses == null) {
            subtitle.text = "서비스에 연결하는 중…"
            return
        }
        val lang = s.prefs.lang
        val mode = if (s.mode == DrivePromiseService.Mode.REPLAY) (if (lang == Lang.KO) "리플레이 ${s.replayName}" else "replay ${s.replayName}") else (if (lang == Lang.KO) "실차" else "vehicle")
        val phase = when (ses.phase) {
            TripSession.Phase.PARKED -> if (lang == Lang.KO) "세워 둠" else "parked"
            TripSession.Phase.DRIVING -> if (lang == Lang.KO) "주행 중(화면은 가려지고 음성만)" else "driving (voice only)"
            TripSession.Phase.ENDED -> if (lang == Lang.KO) "주행 끝" else "trip ended"
        }
        val min = ses.drivingMs / 60_000
        val vs = s.vehicleSources()
        val vsText = if (lang == Lang.KO) "차량 신호 ${vs.size}개" else "${vs.size} vehicle signals"
        val sm = s.screen.mode
        val smText = when (sm) {
            PresentationMode.PARKED -> if (lang == Lang.KO) "정차 화면" else "parked screen"
            PresentationMode.DRIVE_DO -> if (lang == Lang.KO) "주행 화면+음성" else "drive screen + voice"
            PresentationMode.DRIVE_VOICE -> if (lang == Lang.KO) "주행 음성만" else "drive voice only"
        }
        subtitle.text = "$phase · ${if (lang == Lang.KO) "연속 운전 ${min}분" else "$min min driving"} · $mode · $vsText · $smText"
        screenBtn.text = if (s.prefs.screenWhileDriving == ScreenWhileDriving.ON) (if (lang == Lang.KO) "주행 중 화면: 켜기" else "Screen while driving: on") else (if (lang == Lang.KO) "주행 중 화면: 음성만" else "Screen while driving: voice only")
        assumeBtn.text = (if (lang == Lang.KO) "DO 인정 가정: " else "Assume DO: ") + (if (s.prefs.assumeDo) (if (lang == Lang.KO) "켬" else "on") else (if (lang == Lang.KO) "끔" else "off"))
        // 주행 시작 + 화면 허용 → 주행 화면을 한 번만 띄움(이 화면이 앞에 있을 때만 — 뒤에 있으면 플랫폼 규칙상 띄우지 않음)
        val seq = s.screen.launchSeq
        if (seq != launchedSeq && sm == PresentationMode.DRIVE_DO) {
            launchedSeq = seq
            if (resumed) { L.log("screen", "s" to "launch_drive", "seq" to seq); startActivity(DriveActivity.intent(this)) }
            else L.log("screen", "s" to "launch_skipped_background", "seq" to seq)
        }
        if (!replayButtonsBuilt) {
            replayButtonsBuilt = true
            for (n in s.replayNames()) replayRow.addView(button("▶ $n") { s.startReplay(n) })
            replayRow.addView(button(if (lang == Lang.KO) "■ 리플레이 멈춤" else "■ stop replay") { s.startReal() })
        }
        // 타일
        val promises = ses.promises
        val st = ses.statuses
        if (tiles.childCount != promises.size) {
            tiles.removeAllViews()
            for (p in promises) tiles.addView(tile(p.id))
        }
        promises.forEachIndexed { i, p ->
            val tv = tiles.getChildAt(i) as TextView
            val status = st[p.id] ?: PromiseStatus.UNKNOWN
            tv.text = "${SummaryPhrases.name(lang, p)}\n${SummaryPhrases.status(lang, status)}"
            tv.setBackgroundColor(colorOf(status))
        }
        // 계획·요약
        ses.plan?.let { p -> planCard.text = p.lines.joinToString("\n") }
        ses.summary?.let { sm ->
            val extra = if (sm.digestLines.isEmpty()) "" else "\n" + (if (lang == Lang.KO) "못 전한 말: " else "Not delivered: ") + sm.digestLines.joinToString(" / ")
            summaryCard.text = (sm.lines.ifEmpty { listOf(if (lang == Lang.KO) "주행이 끝났어요." else "Trip ended.") }).joinToString("\n") + extra
        }
        logView.text = L.recent(60).filter { l -> SHOW.any { l.startsWith("event=$it ") } }.takeLast(10)
            .joinToString("\n") { it.replace(Regex(" t=\\d+"), "").take(160) }
    }

    // ---------------------------------------------------------------- 작은 도우미
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, sp: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun card(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 20f
        setTextColor(Color.WHITE)
        setBackgroundColor(CARD)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun tile(id: String): TextView = TextView(this).apply {
        textSize = 24f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(20), dp(12), dp(20))
        layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
            width = 0
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        tag = id
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; views.forEach { addView(it) } })
    }

    private fun colorOf(s: PromiseStatus): Int = when (s) {
        PromiseStatus.ON_TRACK -> Color.parseColor("#2E7D32")
        PromiseStatus.AT_RISK -> Color.parseColor("#EF6C00")
        PromiseStatus.KEPT -> Color.parseColor("#1565C0")
        PromiseStatus.MISSED -> Color.parseColor("#C62828")
        PromiseStatus.NOT_APPLICABLE -> Color.parseColor("#37474F")
        PromiseStatus.UNKNOWN -> Color.parseColor("#546E7A")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = permissions.indices.filter { grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED }.map { permissions[it].substringAfterLast('.') }
        L.log("perm", "granted" to granted.joinToString("+"))
        if (granted.contains("ACCESS_FINE_LOCATION")) svc?.startReal()   // 위치 원천을 다시 붙임
    }

    companion object {
        /** 기록 칸에 보여 줄 이벤트(어댑터의 매초 콜백은 뺌) */
        private val SHOW = listOf("speak", "phase", "status", "plan", "summary", "replay_start", "session", "cmd", "tts_init", "tts_init_error", "vsdk", "prop_reg", "location", "perm", "mode", "screen", "do_check", "uxr", "repeat")
        private val BG = Color.parseColor("#0F1418")
        private val CARD = Color.parseColor("#1C262E")
        private val SUB = Color.parseColor("#B0BEC5")
    }
}
