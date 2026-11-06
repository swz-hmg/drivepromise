package app.drivepromise.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import app.drivepromise.L
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.voice.ListenTrigger
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.Locale

/**
 * 듣기 — Pleos SpeechToText 3.3.1(ON_DEVICE). 11/6 STT C안: A ‘말하기’ 버튼을 누를 때 + B 앱이 물은 말이 끝난 뒤에만 짧게 듣습니다.
 * 늘 듣지 않고(웨이크워드 없음) 들은 글자만 돌려줍니다 — 뜻 풀이와 행동은 코어(VoiceCommands · TripSession.onVoice).
 *
 * SDK 판마다 리스너 모양이 달라서(3.3.1: onError(ErrorType)·onUpdatedRms(Double), 예전 판: onError()) 모든 호출을 이름으로(리플렉션) 합니다.
 * SDK가 없거나 이름이 달라도 컴파일은 깨지지 않고 듣기만 꺼집니다 — 앱은 1.1.0처럼 동작(쉬기 알림도 질문 없는 문장).
 *
 * 입력 두 가지:
 *  - 마이크: request() → onUpdated(글자, completed) → 시간이 다 되면 stop()
 *  - 음성 파일 주입(시험용, ON_DEVICE 전용): PCM 16 kHz·16 bit·mono를 100 ms(3,200 B)씩 실시간 속도로 sendAudio() → completeAudioSend().
 *    이때 request()는 부르지 않음(마이크를 하나 더 열게 됨). 같은 인식기를 거치므로 ‘글자 → 뜻 → 행동’ 전체를 에뮬레이터 마이크 없이 시험합니다.
 * 콜백은 모두 main 스레드에서 처리합니다. 들은 글자 원문은 디버그 빌드·시험 입력에서만 기록(logcat)하고, 기기 기록(Ledger)에는 뜻만 남깁니다(코어).
 */
class PleosStt(
    private val ctx: Context,
    private val main: Handler,
    private val debug: Boolean,
    private val onText: (text: String, trigger: ListenTrigger, src: String) -> Unit,
) {
    enum class State { OFF, INIT, READY, LISTENING, ERROR }

    var state = State.OFF
        private set
    var lastError: String? = null
        private set
    /** 마지막으로 들은 글자(화면 표시용) */
    var lastHeard: String? = null
        private set
    /** 지금 듣는 까닭(BUTTON·QUESTION) — 듣는 중이 아니면 null */
    var trigger: ListenTrigger? = null
        private set
    val ready: Boolean get() = state == State.READY || state == State.LISTENING
    val listening: Boolean get() = state == State.LISTENING

    private var stt: Any? = null
    private var resultListener: Any? = null
    private var lang = Lang.KO
    @Volatile private var gen = 0
    private var src = "mic"
    private var partial = ""
    private var maxRms = 0.0
    private var rmsCount = 0
    private var epdLogged = false
    private var stopped = false
    private var timeout: Runnable? = null
    private var feed: ByteArrayOutputStream? = null
    private var feedLabel = ""
    @Volatile private var feeding = false

    // ---------------------------------------------------------------- 시작·끝
    fun start(lang: Lang) {
        this.lang = lang
        L.log("stt_perm", "stt" to granted(PERMISSION), "mic" to granted(Manifest.permission.RECORD_AUDIO))
        try {
            val c = Class.forName(CLS)
            val modeCls = Class.forName(MODE)
            val onDevice = modeCls.enumConstants?.firstOrNull { (it as Enum<*>).name == "ON_DEVICE" } ?: error("Mode.ON_DEVICE 없음")
            stt = c.getConstructor(Context::class.java, modeCls).newInstance(ctx.applicationContext, onDevice)
            state = State.INIT
            val initCls = Class.forName(INIT_LISTENER)
            val initM = c.methods.firstOrNull { it.name == "initialize" && it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(initCls) }
            if (initM != null) {
                initM.invoke(stt, proxy(initCls, "DPSttInit") { m, a -> main.post { onInit(m, a?.firstOrNull()?.toString()) } })
            } else {   // 예전 판: 인자 없는 initialize() — 부르고 나면 바로 준비
                if (call("initialize")) main.post { onInit("onSuccess", null) } else { state = State.ERROR; lastError = "initialize" }
            }
            main.postDelayed({ if (state == State.INIT) L.log("stt_init_timeout", "ms" to INIT_TIMEOUT_MS) }, INIT_TIMEOUT_MS)
        } catch (e: ClassNotFoundException) {
            state = State.OFF
            lastError = "no_sdk"
            L.log("stt_init", "ok" to false, "err" to "no_sdk", "cls" to e.message)
        } catch (e: Throwable) {
            state = State.ERROR
            lastError = (e.cause ?: e).javaClass.simpleName
            L.log("stt_init", "ok" to false, "err" to (e.cause ?: e))
        }
    }

    /** 권한을 나중에 받았을 때 등 — 실패 상태면 처음부터 다시(SDK가 없으면 하지 않음) */
    fun retry() {
        if (state != State.ERROR) return
        L.log("stt_retry", "err" to lastError)
        release()
        start(lang)
    }

    fun release() {
        cancel("release")
        val o = stt
        val l = resultListener
        if (o != null && l != null) runCatching { o.javaClass.methods.firstOrNull { it.name == "removeListener" && it.parameterTypes.size == 1 }?.invoke(o, l) }
        if (o != null) call("release")
        stt = null
        resultListener = null
        state = State.OFF
    }

    fun setLang(l: Lang) {
        lang = l
        if (ready) setLocale(l)
    }

    private fun onInit(m: String, arg: String?) {
        when {
            m == "onSuccess" -> {
                setLocale(lang)
                if (addResultListener()) {
                    state = State.READY
                    lastError = null
                    L.log("stt_init", "ok" to true, "mode" to "ON_DEVICE", "sdk" to SDK)
                } else {
                    state = State.ERROR
                    lastError = "listener"
                }
            }
            m.startsWith("onFail") || m.startsWith("onError") -> {
                state = State.ERROR
                lastError = arg ?: m
                L.log("stt_init", "ok" to false, "cb" to m, "err" to arg)
            }
            else -> L.log("stt_init_ev", "cb" to m, "arg" to arg)
        }
    }

    /** setLocale(java.util.Locale) — 판에 따라 문자열·열거형이면 그것으로 */
    private fun setLocale(l: Lang) {
        val o = stt ?: return
        val loc = if (l == Lang.KO) Locale.KOREA else Locale.US
        val m = o.javaClass.methods.filter { it.name == "setLocale" && it.parameterTypes.size == 1 }
        val ok = runCatching {
            val byLocale = m.firstOrNull { it.parameterTypes[0] == Locale::class.java }
            val byString = m.firstOrNull { it.parameterTypes[0] == String::class.java }
            val byEnum = m.firstOrNull { it.parameterTypes[0].isEnum }
            when {
                byLocale != null -> byLocale.invoke(o, loc)
                byString != null -> byString.invoke(o, loc.toLanguageTag())
                byEnum != null -> {
                    val want = if (l == Lang.KO) listOf("KO", "KR") else listOf("EN", "US")
                    val c = byEnum.parameterTypes[0].enumConstants?.firstOrNull { e -> want.any { (e as Enum<*>).name.uppercase().contains(it) } } ?: error("언어 상수 없음")
                    byEnum.invoke(o, c)
                }
                else -> error("setLocale 없음: " + signatures("setLocale"))
            }
            true
        }.getOrElse { e -> L.log("stt_locale_error", "err" to (e.cause ?: e)); false }
        if (ok) L.log("stt_locale", "lang" to l, "tag" to loc.toLanguageTag())
    }

    private fun addResultListener(): Boolean {
        val o = stt ?: return false
        return try {
            val rl = Class.forName(RESULT_LISTENER)
            val add = o.javaClass.methods.firstOrNull { it.name == "addListener" && it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(rl) }
                ?: error("addListener 없음: " + signatures("addListener"))
            val l = proxy(rl, "DPSttResult") { m, a -> main.post { onResult(m, a) } }
            add.invoke(o, l)
            resultListener = l
            true
        } catch (e: Throwable) {
            L.log("stt_listener_error", "err" to (e.cause ?: e))
            false
        }
    }

    // ---------------------------------------------------------------- 마이크로 듣기
    /** 마이크로 최대 maxMs 동안 들음. 준비가 안 됐거나 이미 듣는 중이면 false */
    fun listen(t: ListenTrigger, maxMs: Long): Boolean {
        if (state != State.READY) {
            L.log("stt_listen", "ok" to false, "why" to (if (state == State.ERROR) "error:$lastError" else state.name.lowercase()), "trigger" to t)
            return false
        }
        begin(t, "mic")
        if (!call("request")) {
            state = State.READY
            trigger = null
            L.log("stt_listen", "ok" to false, "why" to "request_failed", "trigger" to t)
            return false
        }
        L.log("stt_listen", "ok" to true, "trigger" to t, "src" to "mic", "maxMs" to maxMs)
        arm(maxMs) {   // 시간이 다 됨 → 멈추고 마지막 결과를 잠깐 기다림
            stopMic()
            arm(STOP_GRACE_MS) { finish(partial, if (partial.isEmpty()) "timeout_empty" else "timeout_partial") }
        }
        return true
    }

    /** 듣기를 그만둠(내비가 말하기 시작함·버튼을 다시 누름·주행 끝 등) — 결과는 버림 */
    fun cancel(why: String) {
        if (state != State.LISTENING) return
        disarm()
        stopMic()   // 파일 주입 중이어도 stop() — 인식기가 남은 소리를 기다리지 않게
        feeding = false
        gen++
        state = State.READY
        val t = trigger
        trigger = null
        L.log("stt_cancel", "why" to why, "trigger" to t, "src" to src)
    }

    // ---------------------------------------------------------------- 음성 파일 주입(시험용)
    fun feedBegin(label: String) {
        feed = ByteArrayOutputStream()
        feedLabel = label.take(40).ifEmpty { "clip" }
        L.log("stt_feed", "s" to "begin", "label" to feedLabel)
    }

    /** base64 PCM 조각을 모음 — 모은 바이트 수(실패하면 -1) */
    fun feedChunk(b64: String): Int {
        val f = feed ?: run { L.log("stt_feed", "s" to "no_begin"); return -1 }
        val bytes = try { Base64.getDecoder().decode(b64.trim()) } catch (e: IllegalArgumentException) { L.log("stt_feed", "s" to "bad_b64", "err" to e); return -1 }
        f.write(bytes)
        return f.size()
    }

    /** 모은 PCM을 실시간 속도로 인식기에 보냄. 마이크로 듣던 중이면 그 차례를 이 음성이 이어받음(같은 까닭) */
    fun feedEnd(t: ListenTrigger): Boolean {
        val pcm = feed?.toByteArray()
        feed = null
        if (pcm == null || pcm.isEmpty()) { L.log("stt_feed", "s" to "empty"); return false }
        val take = trigger ?: t
        if (state == State.LISTENING) cancel("feed")
        if (state != State.READY) { L.log("stt_feed", "s" to "not_ready", "state" to state, "err" to lastError); return false }
        begin(take, "feed:$feedLabel")
        feeding = true
        val g = gen
        L.log("stt_feed", "s" to "send", "label" to feedLabel, "bytes" to pcm.size, "ms" to pcm.size / BYTES_PER_MS, "trigger" to take)
        Thread({
            val all = pcm + ByteArray(TAIL_SILENCE_BYTES)   // 끝에 0.3초 묵음 — 끝점 검출이 마지막 말을 자르지 않게
            var off = 0
            var ok = true
            while (off < all.size && g == gen) {
                val end = minOf(off + CHUNK_BYTES, all.size)
                if (!call("sendAudio", all.copyOfRange(off, end))) { ok = false; break }
                off = end
                try { Thread.sleep(CHUNK_MS) } catch (_: InterruptedException) { break }
            }
            val done = g == gen && ok && call("completeAudioSend")
            main.post {
                if (g != gen) return@post
                feeding = false
                L.log("stt_feed", "s" to "sent", "ok" to done, "bytes" to off)
                if (!done) finish("", "feed_error")
                else if (state == State.LISTENING) arm(FEED_RESULT_MS) { finish(partial, if (partial.isEmpty()) "feed_timeout_empty" else "feed_timeout_partial") }
            }
        }, "dp-stt-feed").start()
        return true
    }

    // ---------------------------------------------------------------- 결과
    private fun onResult(m: String, a: Array<out Any?>?) {
        when (m) {
            "onUpdatedRms" -> { (a?.firstOrNull() as? Number)?.toDouble()?.let { if (it > maxRms) maxRms = it }; rmsCount++ }
            "onUpdated" -> {
                val text = a?.getOrNull(0)?.toString()?.trim().orEmpty()
                val completed = a?.getOrNull(1) as? Boolean ?: false
                if (state != State.LISTENING) { L.log("stt_late", "completed" to completed, "len" to text.length); return }
                if (!completed) {
                    if (text.isNotEmpty() && text != partial) { partial = text; L.log("stt_partial", "len" to text.length) }
                } else if (text.isNotEmpty()) {
                    finish(text, "final")
                }
            }
            "onEndedRecognition" -> {
                L.log("stt_ev", "m" to m, "state" to state)
                if (state == State.LISTENING && !feeding) {   // 마지막 결과가 이미 왔으면 finish 가 끝냄 — 아니면 잠깐 기다렸다 부분 결과로
                    arm(END_GRACE_MS) { finish(partial, if (partial.isEmpty()) "ended_empty" else "ended_partial") }
                }
            }
            "onError" -> {
                val err = a?.firstOrNull()?.toString() ?: "error"
                lastError = err
                L.log("stt_error", "err" to err, "trigger" to trigger, "src" to src, "state" to state)
                if (state == State.LISTENING) finish(partial, "error:$err")
                if (err in FATAL) state = State.ERROR
            }
            "onUpdatedEpdData" -> if (!epdLogged) { epdLogged = true; L.log("stt_ev", "m" to m, "on" to a?.getOrNull(0), "off" to a?.getOrNull(1)) }
            else -> L.log("stt_ev", "m" to m)   // onReady · onStartedRecognition
        }
    }

    private fun finish(text: String, why: String) {
        if (state != State.LISTENING) return
        disarm()
        val t = trigger ?: ListenTrigger.BUTTON
        val s = src
        if (s == "mic") stopMic()
        state = State.READY
        trigger = null
        feeding = false
        if (text.isNotEmpty()) lastHeard = text
        val show = debug || s != "mic"
        L.log("stt_result", "trigger" to t, "src" to s, "why" to why, "len" to text.length,
            "rms" to String.format(Locale.US, "%.1f/%d", maxRms, rmsCount), "text" to (if (show) text else "(${text.length}자)"))
        onText(text, t, s)
    }

    private fun begin(t: ListenTrigger, s: String) {
        gen++
        disarm()
        trigger = t
        src = s
        partial = ""
        maxRms = 0.0
        rmsCount = 0
        epdLogged = false
        stopped = false
        state = State.LISTENING
    }

    private fun stopMic() {
        if (stopped) return
        stopped = true
        call("stop")
    }

    private fun arm(ms: Long, r: () -> Unit) {
        disarm()
        val g = gen
        val run = Runnable { if (g == gen && state == State.LISTENING) r() }
        timeout = run
        main.postDelayed(run, ms)
    }

    private fun disarm() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    // ---------------------------------------------------------------- 리플렉션 도우미
    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** 이름·인자 수가 맞는 공개 함수를 부름 — 없거나 실패하면 기록하고 false */
    private fun call(name: String, vararg args: Any?): Boolean {
        val o = stt ?: return false
        val m: Method = o.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            ?: run { L.log("stt_api_missing", "fn" to name, "argc" to args.size, "have" to signatures(name)); return false }
        return try {
            m.invoke(o, *args)
            true
        } catch (e: Throwable) {
            L.log("stt_call_error", "fn" to name, "err" to (e.cause ?: e))
            false
        }
    }

    private fun signatures(name: String): String =
        (stt?.javaClass?.methods ?: emptyArray()).filter { it.name == name }.joinToString("+") { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" }.ifEmpty { "없음" }

    private fun proxy(iface: Class<*>, name: String, h: (String, Array<out Any?>?) -> Unit): Any =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { p, method, args ->
            when (method.name) {
                "equals" -> p === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(p)
                "toString" -> name
                else -> {
                    try { h(method.name, args) } catch (e: Throwable) { L.log("proxy_error", "name" to name, "m" to method.name, "err" to e) }
                    null
                }
            }
        }

    companion object {
        const val PERMISSION = "pleos.car.permission.STT_SERVICE"
        const val SDK = "SpeechToText-3.3.1"
        private const val CLS = "ai.pleos.playground.stt.SpeechToText"
        private const val MODE = "ai.pleos.playground.stt.constant.Mode"
        private const val INIT_LISTENER = "ai.pleos.playground.stt.listener.OnInitializeListener"
        private const val RESULT_LISTENER = "ai.pleos.playground.stt.listener.ResultListener"
        const val INIT_TIMEOUT_MS = 10_000L
        /** 시간이 다 돼 stop()을 부른 뒤 마지막 결과를 기다리는 시간 */
        const val STOP_GRACE_MS = 1_500L
        /** 인식이 끝났다(onEndedRecognition)는 알림 뒤 마지막 결과를 기다리는 시간 */
        const val END_GRACE_MS = 800L
        /** 음성 파일을 다 보낸 뒤 결과를 기다리는 시간(공개 시험 사례: 12초 안) */
        const val FEED_RESULT_MS = 10_000L
        /** 16 kHz · 16 bit · mono = 32 B/ms → 100 ms = 3,200 B(공식 문서 예시) */
        const val BYTES_PER_MS = 32
        const val CHUNK_BYTES = 3_200
        const val CHUNK_MS = 100L
        const val TAIL_SILENCE_BYTES = 9_600
        /** 다시 시도해도 소용없는 오류 — 상태를 ERROR로(권한을 받으면 retry) */
        private val FATAL = setOf("PERMISSION_DENIED", "UNAUTHENTICATED", "NOT_SUPPORTED_API")
    }
}
