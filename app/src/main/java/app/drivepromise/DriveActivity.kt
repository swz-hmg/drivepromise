package app.drivepromise

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.scene.SummaryPhrases
import app.drivepromise.core.session.TripSession
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.ui.PresentationMode
import app.drivepromise.platform.PleosStt

/**
 * 주행 화면(DO 선언, 11/5 Q036) — 주행 중 ‘화면 + 음성’ 모드(DRIVE_DO)에서만 보이는 한 장짜리 상태 화면.
 * 주행 중 제한 규칙(AAOS 운전자 주의 분산 지침)에 맞춰: 스크롤·목록·키보드·설정 없음, 글자 크게(24 sp 이상), 큰 버튼 둘(‘다시 들려줘’·‘🎤 말하기’, 76 dp),
 * 문장은 플랫폼 허용 길이(maxRestrictedStringLength, 기본 120자) 안에서만. ‘말하기’는 누를 때만 듣고(11/6 STT C안 A), 들은 말과 뜻을 아래 한 줄에 잠깐 보여 줌.
 * 내용: 약속 5개의 상태(색), 마지막으로 말한 문장(큰 글씨 — 청력이 약한 시니어·심사 영상의 ‘자막’ 역할), 연속 운전 시간.
 * 모드가 DRIVE_DO 가 아니게 되면(세움·차단·설정) 스스로 닫힘. 세워 둔 채 ‘미리보기’(preview)로도 열 수 있음(G2 시연·캡처).
 */
class DriveActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var svc: DrivePromiseService? = null
    private var preview = false
    private lateinit var timeView: TextView
    private lateinit var badge: TextView
    private lateinit var pills: LinearLayout
    private lateinit var nowCard: TextView
    private lateinit var repeatBtn: Button
    private lateinit var micBtn: Button
    private lateinit var hint: TextView
    private var repeatFlashUntil = 0L
    private var lastModeLogged: PresentationMode? = null
    private var lastShown: String? = null
    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 500)
        }
    }
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            svc = (service as? DrivePromiseService.LocalBinder)?.service
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = intent?.getBooleanExtra("preview", false) == true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        L.log("screen", "s" to "drive_create", "preview" to preview)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DrivePromiseService::class.java), conn, Context.BIND_AUTO_CREATE)
        main.post(refresh)
        L.log("screen", "s" to "drive_show", "preview" to preview)
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(refresh)
        // 1.3.5(11/10 A2): 낮/밤(uiMode) 전환 등 구성 변경으로 다시 만들어지는 것은 플랫폼 차단이 아님 — 차단으로 보면 그 주행 동안 음성만 남음
        if (!isChangingConfigurations) svc?.onDriveScreenStopped(isFinishing)
        try { unbindService(conn) } catch (_: Throwable) {}
        svc = null
    }

    // ---------------------------------------------------------------- 화면(레이아웃 XML 없음) — 스크롤 없이 한 장
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(36), dp(28), dp(36), dp(24))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("운전약속", 30f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeView = text("", 24f, color = SUB)
        header.addView(timeView)
        badge = text("", 16f, color = Color.WHITE).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = pill(ACCENT)
        }
        header.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(16) })
        root.addView(header)

        pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(22), 0, dp(10)) }
        root.addView(pills)

        nowCard = TextView(this).apply {
            textSize = 36f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 3
            setLineSpacing(0f, 1.15f)
            setPadding(dp(28), dp(26), dp(28), dp(26))
            background = pill(CARD, radius = 22)
        }
        root.addView(nowCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12); bottomMargin = dp(18) })

        repeatBtn = Button(this).apply {
            text = "다시 들려줘"
            textSize = 26f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = pill(ACCENT, radius = 20)
            setOnClickListener {
                val ok = svc?.repeatLast() ?: false
                repeatFlashUntil = System.currentTimeMillis() + 2_500
                text = if (ok) "다시 말할게요" else "아직 말한 게 없어요"
            }
        }
        micBtn = Button(this).apply {
            text = "🎤 말하기"
            textSize = 26f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = pill(MIC, radius = 20)
            setOnClickListener { svc?.pushToTalk() }
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(repeatBtn, LinearLayout.LayoutParams(0, dp(76), 1f).apply { rightMargin = dp(8) })
        buttons.addView(micBtn, LinearLayout.LayoutParams(0, dp(76), 1f).apply { leftMargin = dp(8) })
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)))

        hint = text("주행 중엔 음성으로 알려 드려요 · 세우면 자세히 볼 수 있어요", 16f, color = SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        root.addView(hint)
        return root
    }

    private fun render() {
        val s = svc ?: return
        val ses = s.session ?: return
        val lang = s.prefs.lang
        val screenMode = s.screen.mode
        if (screenMode != lastModeLogged) { lastModeLogged = screenMode; L.log("screen", "s" to "drive_mode", "mode" to screenMode, "preview" to preview) }
        if (!preview && screenMode != PresentationMode.DRIVE_DO) {   // 세움·차단·설정 → 스스로 닫힘(정차 화면이 아래에 있음)
            L.log("screen", "s" to "drive_close", "mode" to screenMode)
            finish()
            return
        }
        val min = ses.drivingMs / 60_000
        timeView.text = if (lang == Lang.KO) "연속 운전 ${min}분" else "$min min driving"
        badge.text = when {
            preview && screenMode != PresentationMode.DRIVE_DO -> if (lang == Lang.KO) "미리보기" else "preview"
            s.mode == DrivePromiseService.Mode.REPLAY -> if (lang == Lang.KO) "리플레이 · 화면+음성" else "replay · screen+voice"
            else -> if (lang == Lang.KO) "화면+음성" else "screen+voice"
        }
        // 약속 알약 5개(상태 색)
        val promises = ses.promises
        val st = ses.statuses
        if (pills.childCount != promises.size) {
            pills.removeAllViews()
            for (p in promises) pills.addView(TextView(this).apply {
                textSize = 21f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dp(10), dp(14), dp(10), dp(14))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            })
        }
        promises.forEachIndexed { i, p ->
            val tv = pills.getChildAt(i) as TextView
            val status = st[p.id] ?: PromiseStatus.UNKNOWN
            tv.text = "${SummaryPhrases.name(lang, p)}\n${SummaryPhrases.status(lang, status)}"
            tv.background = pill(colorOf(status), radius = 18)
        }
        // 마지막으로 말한 문장(60초 동안) — 없으면 상태 한 줄. 플랫폼 허용 길이 안에서
        val maxLen = s.maxStringLength()
        val last = ses.lastSpoken
        val at = ses.lastSpokenAtMs
        val fresh = last != null && at != null && ses.now - at <= 60_000
        val onTrack = st.values.count { it == PromiseStatus.ON_TRACK || it == PromiseStatus.KEPT }
        val risk = promises.firstOrNull { (st[it.id] ?: PromiseStatus.UNKNOWN) == PromiseStatus.AT_RISK || (st[it.id] ?: PromiseStatus.UNKNOWN) == PromiseStatus.MISSED }
        val fallback = when {
            risk != null -> if (lang == Lang.KO) "${SummaryPhrases.name(lang, risk)} — ${SummaryPhrases.status(lang, st[risk.id]!!)}" else "${SummaryPhrases.name(lang, risk)} — ${SummaryPhrases.status(lang, st[risk.id]!!)}"
            ses.phase == TripSession.Phase.DRIVING -> if (lang == Lang.KO) "약속 ${onTrack}개 지키는 중이에요" else "$onTrack promises on track"
            else -> if (lang == Lang.KO) "출발하면 약속을 지켜 드릴게요" else "Promises start with the trip"
        }
        val shown = (if (fresh) last!!.text else fallback).take(maxLen)
        if (shown != lastShown) {   // 1.2.1: 화면에 실제로 띄운 문장을 기록 — 판정이 화면 덤프(창이 여럿이면 내비 창을 잡음, 11/6) 대신 이 줄을 봄
            lastShown = shown
            L.log("screen", "s" to "drive_text", "fresh" to fresh, "mode" to screenMode, "text" to shown)
        }
        nowCard.text = shown
        nowCard.setTextColor(if (fresh) Color.WHITE else SUB)
        if (System.currentTimeMillis() > repeatFlashUntil) repeatBtn.text = if (lang == Lang.KO) "다시 들려줘" else "Say it again"
        repeatBtn.isEnabled = last != null
        val ko = lang == Lang.KO
        val sttSt = s.sttState()
        val sttOk = sttSt == PleosStt.State.READY || sttSt == PleosStt.State.LISTENING
        micBtn.text = when {
            s.sttListening() -> if (ko) "듣는 중…" else "Listening…"
            sttOk -> if (ko) "🎤 말하기" else "🎤 Speak"
            else -> if (ko) "말하기 준비 안 됨" else "Voice not ready"
        }
        micBtn.isEnabled = sttOk
        micBtn.background = pill(if (s.sttListening()) LISTEN else MIC, radius = 20)
        val heard = s.lastVoice?.takeIf { System.currentTimeMillis() - s.lastVoiceAt < 8_000 }
        hint.text = when {
            heard != null -> (if (ko) "들은 말: " else "Heard: ") + heard
            sttOk -> if (ko) "‘다시’ · ‘약속 알려 줘’ · ‘조용히 해’라고 말해 보세요" else "Say ‘again’ · ‘my promises’ · ‘be quiet’"
            else -> if (ko) "주행 중엔 음성으로 알려 드려요 · 세우면 자세히 볼 수 있어요" else "Voice while driving · details when parked"
        }
    }

    // ---------------------------------------------------------------- 작은 도우미
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, sp: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(color: Int, radius: Int = 999): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun colorOf(s: PromiseStatus): Int = when (s) {
        PromiseStatus.ON_TRACK -> Color.parseColor("#2E7D32")
        PromiseStatus.AT_RISK -> Color.parseColor("#EF6C00")
        PromiseStatus.KEPT -> Color.parseColor("#1565C0")
        PromiseStatus.MISSED -> Color.parseColor("#C62828")
        PromiseStatus.NOT_APPLICABLE -> Color.parseColor("#37474F")
        PromiseStatus.UNKNOWN -> Color.parseColor("#546E7A")
    }

    companion object {
        private val BG = Color.parseColor("#0F1418")
        private val CARD = Color.parseColor("#1C262E")
        private val SUB = Color.parseColor("#B0BEC5")
        private val ACCENT = Color.parseColor("#1E6FD9")
        private val MIC = Color.parseColor("#00897B")
        private val LISTEN = Color.parseColor("#C62828")

        fun intent(ctx: Context, preview: Boolean = false): Intent =
            Intent(ctx, DriveActivity::class.java).putExtra("preview", preview).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
