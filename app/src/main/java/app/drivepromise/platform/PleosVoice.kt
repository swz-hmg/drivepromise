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
    private var curId: Long? = null
    private var speaking = false
    /** 지금 우리 말을 재생 중인가 — 듣기(STT)는 말이 끝난 뒤에 시작(마이크가 우리 목소리를 듣지 않게) */
    val isSpeaking: Boolean get() = speaking
    private var stopRequested = false
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
                if (m == "onSuccess") {
                    try { t.addEventListener(ev) } catch (e: Throwable) { L.log("tts_listener_error", "err" to e) }
                    try { t.setLocale(Locale.KOREA) } catch (e: Throwable) { L.log("tts_locale_error", "err" to e) }
                    ready = true
                    L.log("tts_init", "ok" to true, "mode" to "ON_DEVICE", "sdk" to "pleos-tts-3.3.1")
                } else {
                    L.log("tts_init", "ok" to false, "cb" to m, "err" to a?.firstOrNull())
                }
            }
            t.initialize(init)
            main.postDelayed({ if (!ready) L.log("tts_init_timeout", "ms" to 10_000) }, 10_000)
        } catch (e: Throwable) {
            L.log("tts_init_error", "err" to e)
        }
    }

    private fun onTtsEvent(m: String, arg: String?) {
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
        const val NAV = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        const val NTF = AudioAttributes.USAGE_NOTIFICATION
        private val PIID = Regex("piid:(-?\\d+)")
    }
}
