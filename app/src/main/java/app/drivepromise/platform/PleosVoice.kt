package app.drivepromise.platform

import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.SystemClock
import app.drivepromise.L
import app.drivepromise.core.speech.AudioUsage
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.speech.Speaker
import app.drivepromise.core.speech.VoiceArbiter
import java.util.Locale

/**
 * 말하기 — Pleos TextToSpeech(ON_DEVICE) + 오디오 포커스 + 재생 목록 감시. G1-B v0.6/v0.7에서 확정한 물러서기 규칙의 ‘플랫폼 쪽 절반’:
 *  - 용도는 내비 안내(NAVIGATION_GUIDANCE) + pauseWhenDucked — 내비가 포커스를 요청하는 순간 LOSS 알림을 받음(1차 신호 뒤 68 ms)
 *  - 재생 목록(AudioPlaybackCallback)에서 우리 재생기가 아닌 내비 용도 재생기 수를 세어 세션에 알림(가장 빠른 신호, 효과음이 말보다 62 ms 앞)
 *  - 멈춤([stop])은 tts.stop() — 실측 168~191 ms 안에 소리가 꺼짐. 다시 말할지는 코어(VoiceArbiter·SpeechScheduler)가 정함
 *  - 물러선 뒤에도 포커스 요청은 10초까지 쥐고 있어 내비가 끝나면 GAIN을 받음
 * 세션 콜백은 모두 main 스레드에서 부릅니다.
 */
class PleosVoice(
    private val ctx: Context,
    private val main: Handler,
    private val onNavPlayers: (Int) -> Unit,
    private val onFocus: (VoiceArbiter.Focus) -> Unit,
    private val onDone: (Long) -> Unit,
    private val onError: (Long, String) -> Unit,
) : Speaker {
    private val am: AudioManager by lazy { ctx.getSystemService(AudioManager::class.java) }
    private var tts: PleosTts? = null
    var ready = false
        private set
    /** 1.3.5(11/10 A5): 마지막 초기화가 실패(onFail·예외)로 끝났는가 — 서비스의 30초 재시도가 다시 초기화 */
    private var initFailed = false
    /** 1.3.5: 10초 안에 소식이 없었는가 — 늦게라도 끝날 수 있으므로 시작 뒤 [RETRY_AFTER_TIMEOUT_MS]가 지나야 다시(진행 중인 초기화를 30초마다 끊지 않게) */
    private var initTimedOut = false
    private var initStartAt = 0L
    private var curId: Long? = null
    private var speaking = false
    /** 지금 우리 말을 재생 중인가 — 듣기(STT)는 말이 끝난 뒤에 시작(마이크가 우리 목소리를 듣지 않게) */
    val isSpeaking: Boolean get() = speaking
    private var stopRequested = false
    /** 1.3.2(P1-7): 앞 말을 끊은 뒤 그 말의 끝 소식(onDone·onStop·onError)이 늦게 와서 새 말에 붙지 않게 — 이 시각까지 1건 무시 */
    private var staleTerminal = 0
    private var staleUntil = 0L
    private var focusReq: AudioFocusRequest? = null
    private var holdFocus: Runnable? = null
    private var playCb: AudioManager.AudioPlaybackCallback? = null
    private var ttsEv: TtsEventListener? = null

    // 재생 목록 추적(프로브와 같은 방식)
    private data class Pl(val key: String, val usage: Int, val content: Int)
    private var lastKeys = emptySet<String>()
    private val navKeys = HashSet<String>()
    private var selfKey = ""
    private var selfWait = false
    private var speakT = 0L
    private var speakUsage = NAV
    private var lastNavCount = 0

    fun start() {
        initTts()
        initAudioWatch()
    }

    fun release() {
        try { playCb?.let { am.unregisterAudioPlaybackCallback(it) } } catch (_: Throwable) {}
        try { ttsEv?.let { tts?.removeEventListener(it) } } catch (_: Throwable) {}
        try { tts?.release() } catch (_: Throwable) {}
        abandonFocus()
        tts = null
        ready = false
    }

    /** 1.3.5(11/10 A5): 초기화가 실패했거나 소식 없이 60초가 지났을 때만 다시(진행 중인 초기화는 두고 봄) — 다시 했으면 true */
    fun retryIfFailed(): Boolean {
        if (ready) return false
        val stale = initTimedOut && SystemClock.elapsedRealtime() - initStartAt >= RETRY_AFTER_TIMEOUT_MS
        if (!initFailed && !stale) return false
        reinitIfNotReady()
        return true
    }

    /** 1.3.1(P0-7): 초기화가 실패했던 TTS(권한 없음 등)를 다시 — 이미 준비됐으면 아무것도 안 함 */
    fun reinitIfNotReady() {
        if (ready) return
        try { ttsEv?.let { tts?.removeEventListener(it) } } catch (_: Throwable) {}
        try { tts?.release() } catch (_: Throwable) {}
        tts = null
        L.log("tts_reinit")
        initTts()
    }

    fun setLang(lang: Lang) {
        try { tts?.setLocale(if (lang == Lang.KO) Locale.KOREA else Locale.US) } catch (e: Throwable) { L.log("tts_locale_error", "err" to e) }
    }

    // ---------------------------------------------------------------- Speaker
    override fun speak(id: Long, text: String, usage: AudioUsage) {
        val t = tts
        if (t == null || !ready) {
            L.log("tts_not_ready", "id" to id)
            main.post { onError(id, "tts_not_ready") }
            return
        }
        if (speaking) {   // 코어는 한 번에 하나만 말하므로 오지 않아야 함 — 오면 앞 말을 끊고 새 말
            markStale("replaced")
            try { t.stop() } catch (_: Throwable) {}
        }
        val u = if (usage == AudioUsage.NAVIGATION_GUIDANCE) NAV else NTF
        val attrs = AudioAttributes.Builder().setUsage(u).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        requestFocus(attrs)
        try { t.setAudioAttributes(attrs) } catch (e: Throwable) { L.log("tts_attr_error", "err" to e) }
        curId = id
        speaking = true
        stopRequested = false
        selfWait = true
        selfKey = ""
        speakT = System.currentTimeMillis()
        speakUsage = u
        try {
            t.speak(text, true)
            L.log("tts_speak", "id" to id, "usage" to (if (u == NAV) "nav" else "ntf"), "chars" to text.length, "text" to text)
        } catch (e: Throwable) {
            L.log("tts_speak_error", "id" to id, "err" to e)
            speaking = false
            abandonFocus()
            main.post { onError(id, "speak_failed") }
        }
    }

    /** 1.3.2(P1-7): 세션을 바꿀 때 — 지금 말을 멈추고, 그 말의 끝 소식은 무시(새 세션의 같은 번호 말에 붙지 않게) */
    fun cancelAll(why: String) {
        if (!speaking) return
        markStale(why)
        L.log("tts_cancel", "id" to curId, "why" to why)
        speaking = false
        curId = null
        stopRequested = true
        try { tts?.stop() } catch (e: Throwable) { L.log("tts_stop_error", "err" to e) }
        abandonFocus()
    }

    private fun markStale(why: String) {
        staleTerminal = 1
        staleUntil = System.currentTimeMillis() + STALE_WINDOW_MS
        L.log("tts_stale_mark", "id" to curId, "why" to why)
    }

    override fun stop(id: Long) {
        if (curId != id) return
        stopRequested = true
        L.log("tts_stop_req", "id" to id)
        try { tts?.stop() } catch (e: Throwable) { L.log("tts_stop_error", "err" to e) }
        // 포커스 요청은 쥔 채 기다림 → 내비가 끝나면 GAIN. 10초가 지나도 새 말이 없으면 놓음
        holdFocus?.let { main.removeCallbacks(it) }
        val r = Runnable { if (!speaking) abandonFocus() }
        holdFocus = r
        main.postDelayed(r, 10_000)
    }

    // ---------------------------------------------------------------- Pleos TTS
    private fun initTts() {
        try {
            L.log("tts_perm", "granted" to (ctx.checkSelfPermission("pleos.car.permission.TTS_SERVICE") == PackageManager.PERMISSION_GRANTED))
            initFailed = false
            initTimedOut = false
            initStartAt = SystemClock.elapsedRealtime()
            val t = PleosTts(ctx.applicationContext, TtsMode.ON_DEVICE)
            tts = t
            val ev = Reflect.proxyOf(TtsEventListener::class.java, "DPTtsEvents") { m, a ->
                if (m != "onUpdatedRms") {
                    L.log("tts_ev", "m" to m, "id" to curId, "arg" to a?.firstOrNull())
                    main.post { onTtsEvent(m, a?.firstOrNull()?.toString()) }
                }
            }
            ttsEv = ev
            val init = Reflect.proxyOf(TtsInitListener::class.java, "DPTtsInit") { m, a ->
                if (tts !== t) {   // 1.3.5(11/10 B2): 다시 초기화한 뒤 해제된 옛 인스턴스의 늦은 소식은 무시
                    L.log("tts_init_stale", "cb" to m)
                } else if (m == "onSuccess") {
                    try { t.addEventListener(ev) } catch (e: Throwable) { L.log("tts_listener_error", "err" to e) }
                    try { t.setLocale(Locale.KOREA) } catch (e: Throwable) { L.log("tts_locale_error", "err" to e) }
                    ready = true
                    L.log("tts_init", "ok" to true, "mode" to "ON_DEVICE", "sdk" to "pleos-tts-3.3.1")
                } else {
                    initFailed = true
                    L.log("tts_init", "ok" to false, "cb" to m, "err" to a?.firstOrNull())
                }
            }
            t.initialize(init)
            main.postDelayed({ if (tts === t && !ready) { initTimedOut = true; L.log("tts_init_timeout", "ms" to 10_000) } }, 10_000)
        } catch (e: Throwable) {
            initFailed = true
            L.log("tts_init_error", "err" to e)
        }
    }

    private fun onTtsEvent(m: String, arg: String?) {
        if ((m == "onDone" || m == "onStop" || m == "onError") && staleTerminal > 0) {
            staleTerminal = 0
            if (System.currentTimeMillis() <= staleUntil) { L.log("tts_stale", "m" to m, "cur" to curId); return }   // 1.3.2(P1-7): 끊은 앞 말의 소식
        }
        val id = curId ?: return
        when (m) {
            "onDone" -> {
                speaking = false
                curId = null
                abandonFocus()
                onDone(id)
            }
            "onStop" -> {
                speaking = false
                curId = null
                if (!stopRequested) { abandonFocus(); onError(id, "stopped") }   // 우리가 멈춘 게 아니면 오류로(코어가 다시 시도)
            }
            "onError" -> {
                speaking = false
                curId = null
                abandonFocus()
                onError(id, arg ?: "tts_error")
            }
        }
    }

    // ---------------------------------------------------------------- 포커스
    private fun requestFocus(attrs: AudioAttributes) {
        holdFocus?.let { main.removeCallbacks(it) }
        abandonFocus()
        try {
            val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener({ ch -> onFocusChange(ch) }, main)
                .build()
            focusReq = fr
            L.log("focus", "result" to am.requestAudioFocus(fr))
        } catch (e: Throwable) {
            L.log("focus_error", "err" to e)
        }
    }

    private fun onFocusChange(ch: Int) {
        L.log("focus_change", "change" to ch, "speaking" to speaking)
        val f = when (ch) {
            AudioManager.AUDIOFOCUS_GAIN -> VoiceArbiter.Focus.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> VoiceArbiter.Focus.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> VoiceArbiter.Focus.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> VoiceArbiter.Focus.LOSS_TRANSIENT_CAN_DUCK
            else -> return
        }
        onFocus(f)
    }

    private fun abandonFocus() {
        val fr = focusReq ?: return
        try { am.abandonAudioFocusRequest(fr) } catch (e: Throwable) { L.log("focus_abandon_error", "err" to e) }
        focusReq = null
    }

    // ---------------------------------------------------------------- 재생 목록 감시: 누가 지금 소리를 내는가
    private fun initAudioWatch() {
        try {
            val cb = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    onPlayback(configs ?: emptyList())
                }
            }
            am.registerAudioPlaybackCallback(cb, main)
            playCb = cb
            onPlayback(am.activePlaybackConfigurations)
            L.log("audio_watch", "ok" to true)
        } catch (e: Throwable) {
            L.log("audio_watch_error", "err" to e)
        }
    }

    /** 일반 앱이 받는 목록은 익명화돼 앱 이름이 없고 용도·내용 종류만 보임 → piid(재생기 번호)로 추적 */
    private fun players(cfgs: List<AudioPlaybackConfiguration>): List<Pl> {
        val seen = HashMap<String, Int>()
        return cfgs.map { c ->
            val a = c.audioAttributes
            val p = PIID.find(c.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val key = if (p >= 0) "p$p" else {
                val base = "u${a.usage}c${a.contentType}"
                val k = seen[base] ?: 0
                seen[base] = k + 1
                "$base#$k"
            }
            Pl(key, a.usage, a.contentType)
        }
    }

    private fun onPlayback(cfgs: List<AudioPlaybackConfiguration>) {
        val now = System.currentTimeMillis()
        val items = players(cfgs)
        val cur = items.map { it.key }.toSet()
        val added = items.filter { it.key !in lastKeys }
        lastKeys = cur
        for (x in added) {
            if (speaking && selfWait && x.usage == speakUsage && now - speakT < 6_000) {
                selfWait = false
                selfKey = x.key
                L.log("self_player", "key" to x.key, "ms" to now - speakT, "id" to curId)
            } else if (x.usage == NAV && x.key != selfKey) {
                navKeys.add(x.key)
            }
        }
        navKeys.retainAll(cur)
        if (selfKey.isNotEmpty() && selfKey !in cur) selfKey = ""
        if (navKeys.size != lastNavCount) {
            lastNavCount = navKeys.size
            L.log("nav_players", "n" to navKeys.size)
            onNavPlayers(navKeys.size)
        }
    }

    companion object {
        /** 1.3.2(P1-7): 끊은 말의 끝 소식을 기다리는 시간(실측 onStop 27 ms) */
        const val STALE_WINDOW_MS = 1_500L
        /** 1.3.5(11/10 A5): 시간 초과(소식 없음)였던 초기화를 다시 하기까지 — 시작 뒤 60초(부팅 직후 음성 서비스가 늦게 떠도 진행 중인 연결을 끊지 않게) */
        const val RETRY_AFTER_TIMEOUT_MS = 60_000L
        const val NAV = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        const val NTF = AudioAttributes.USAGE_NOTIFICATION
        private val PIID = Regex("piid:(-?\\d+)")
    }
}
