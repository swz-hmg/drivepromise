package app.drivepromise.probe

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.TurnSignal
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.content.pm.CarPackageManager
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.lang.reflect.Proxy
import java.util.Locale
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions

/**
 * G1-B 포그라운드 서비스 — 화면과 무관하게 돌며 결과를 logcat(G1B)에 남김.
 * 명령: adb shell am start-foreground-service -n app.drivepromise.probe/.ProbeService --es cmd <명령>
 *   do_check · speak_asst · speak_nav(B3) · route_far(B6) · b7_df_asst · b7_df_nav · b7_nf_asst · b7_nf_nav · b7_yield · b7_end(B7) · stop
 * 음성: Pleos TextToSpeech SDK 3.3.1(ON_DEVICE, 앱 등록 불필요). 안드로이드 기본 TTS는 이 기기에서 초기화 실패(v0.5, status=-1).
 * 용도: USAGE_ANNOUNCEMENT는 시스템 전용(@SystemApi) → 일반 앱이 쓸 수 있는 ASSISTANT·NAVIGATION_GUIDANCE를 비교.
 * B7: 운전약속 발화와 기본 내비 안내가 겹칠 때 시스템(오디오 포커스·음성 서비스)이 어떻게 처리하는지, 앱이 스스로 물러설 수 있는지 확인.
 */
class ProbeService : Service() {
    companion object {
        const val NAV = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        const val ASST = AudioAttributes.USAGE_ASSISTANT
        const val SHORT_TEXT = "운전약속 시험 발화입니다. 주행 중에도 들리는지 확인합니다."
        const val LONG_TEXT = "운전약속 겹침 시험입니다. 지금부터 십오 초쯤 길게 말하겠습니다. " +
            "이 사이에 내비게이션이 회전 안내를 하면, 두 목소리가 겹치는지, 어느 한쪽이 멈추는지, " +
            "아니면 한쪽이 기다렸다가 이어서 말하는지를 기록합니다. 시험이 끝날 때까지 이 문장은 계속됩니다."
        const val RETRY_TEXT = "내비 안내가 끝나서 이어서 말합니다. 운전약속 시험입니다."
        val PIID = Regex("piid:(-?\\d+)")
    }

    private data class Pl(val key: String, val usage: Int, val content: Int)

    private var car: Car? = null
    private var uxr: CarUxRestrictionsManager? = null
    private var props: CarPropertyManager? = null
    private var pkgMgr: CarPackageManager? = null
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private val b7h = Handler(Looper.getMainLooper())
    private val am: AudioManager by lazy { getSystemService(AudioManager::class.java) }

    // ---- 발화 상태(메인 스레드에서 바꿈)
    private var tts: PleosTts? = null
    @Volatile private var ttsReady = false
    private var focusReq: AudioFocusRequest? = null
    private var focusTag = ""
    private var speaking = false
    @Volatile private var curId = "-"
    @Volatile private var rmsN = 0
    private var speakUsage = 0
    private var speakT = 0L
    private var selfWait = false
    private var selfKey = ""

    // ---- 재생 감시(AudioPlaybackCallback — 일반 앱은 익명화된 목록: 용도·내용 종류만 보임)
    private var playCb: AudioManager.AudioPlaybackCallback? = null
    private var lastKeys: Set<String> = emptySet()
    private var navKey = ""
    private var navT = 0L

    // ---- B7 겹침 시험
    private var b7Mode = "none"
    private var pendingNavFirst: Pair<Int, String>? = null
    private var yieldWaiting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        P.log("svc", "s" to "create", "sdk" to Build.VERSION.SDK_INT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (!started) {
            started = true
            initCar()
            initVehicleSdk()
            initNavi()
            initAudioWatch()
            initTts()
        }
        val cmd = intent?.getStringExtra("cmd") ?: "none"
        P.log("cmd", "cmd" to cmd)
        when (cmd) {
            "do_check" -> doCheck()
            "speak_asst", "speak_ann" -> speak(ASST, "asst", SHORT_TEXT, "b3")
            "speak_nav" -> speak(NAV, "nav", SHORT_TEXT, "b3")
            "route_far" -> routeFar()
            "b7_df_asst" -> b7DpFirst(ASST, "asst")
            "b7_df_nav" -> b7DpFirst(NAV, "nav")
            "b7_nf_asst" -> b7NavFirst(ASST, "asst")
            "b7_nf_nav" -> b7NavFirst(NAV, "nav")
            "b7_yield" -> b7Yield()
            "b7_end" -> b7End("cmd")
            "stop" -> stopSelf()
        }
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("g1b") == null) {
            nm.createNotificationChannel(NotificationChannel("g1b", "G1-B probe", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, "g1b")
            .setContentTitle("G1-B probe")
            .setContentText("voice · vehicle · route probe")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    /** 인터페이스를 실행 중에 만들어 모든 콜백을 한곳에서 기록(공식 샘플과 같은 방식) */
    @Suppress("UNCHECKED_CAST")
    private fun <T> proxyOf(cls: Class<T>, name: String, h: (String, Array<out Any?>?) -> Unit): T =
        Proxy.newProxyInstance(cls.classLoader, arrayOf<Class<*>>(cls)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> name
                else -> {
                    try {
                        h(method.name, args)
                    } catch (e: Throwable) {
                        P.log("proxy_error", "name" to name, "m" to method.name, "err" to e)
                    }
                    null
                }
            }
        } as T

    // ---------------------------------------------------------------- AAOS: UX 제한·DO 판정·차량 속성
    private fun initCar() {
        try {
            val c = Car.createCar(this)
            car = c
            uxr = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            props = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            pkgMgr = c.getCarManager(Car.PACKAGE_SERVICE) as CarPackageManager
            val cur = uxr?.currentCarUxRestrictions
            P.log("uxr_init", "requiresDO" to cur?.isRequiresDistractionOptimization, "active" to cur?.activeRestrictions)
            uxr?.registerListener(object : CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
                override fun onUxRestrictionsChanged(r: CarUxRestrictions) {
                    P.log("uxr", "requiresDO" to r.isRequiresDistractionOptimization, "active" to r.activeRestrictions)
                }
            })
            registerProp(VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE, "gear")
            registerProp(VehiclePropertyIds.PERF_VEHICLE_SPEED, 1f, "speed")
            registerProp(VehiclePropertyIds.TURN_SIGNAL_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE, "turn_aaos")
            doCheck()
        } catch (e: Throwable) {
            P.log("car_error", "err" to e)
        }
    }

    private fun registerProp(id: Int, rate: Float, name: String) {
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                P.log("prop", "name" to name, "value" to value.value, "status" to value.status)
            }

            override fun onErrorEvent(propId: Int, zone: Int) {
                P.log("prop_error", "name" to name, "zone" to zone)
            }
        }
        try {
            val ok = props?.registerCallback(cb, id, rate)
            P.log("prop_reg", "name" to name, "ok" to ok)
        } catch (e: Throwable) {
            P.log("prop_reg_error", "name" to name, "err" to e)
        }
    }

    private fun doCheck() {
        val cur = try { uxr?.currentCarUxRestrictions } catch (e: Throwable) { null }
        for (cls in listOf(GlanceStatusActivity::class.java.name, MainActivity::class.java.name)) {
            try {
                val ok = pkgMgr?.isActivityDistractionOptimized(packageName, cls)
                P.log("do_check", "activity" to cls.substringAfterLast('.'), "optimized" to ok,
                    "requiresDO" to cur?.isRequiresDistractionOptimization, "active" to cur?.activeRestrictions)
            } catch (e: Throwable) {
                P.log("do_check_error", "activity" to cls.substringAfterLast('.'), "err" to e)
            }
        }
    }

    // ---------------------------------------------------------------- Pleos Vehicle SDK: 도어·방향지시등
    private fun initVehicleSdk() {
        try {
            val v = Vehicle(applicationContext)
            v.initialize()
            vehicle = v
            P.log("vehicle_init", "ok" to true)
            try {
                v.getDoor().registerDoorPosition(DoorArea(DoorValue.valueOf("ROW_1_LEFT")), object : DoorPositionListener {
                    override fun onPositionUpdated(pos: Int?) {
                        P.log("door", "pos" to pos)
                    }

                    override fun onFailed(e: Exception) {
                        P.log("door_error", "err" to e)
                    }
                })
                P.log("door_reg", "ok" to true)
            } catch (e: Throwable) {
                P.log("door_reg_error", "err" to e)
            }
            registerByReflection("TurnSignal", v.getTurnSignal(), TurnSignal::class)
        } catch (e: Throwable) {
            P.log("vehicle_error", "err" to e)
        }
    }

    /** 공식 샘플(VehicleApiExplorer)과 같은 방식: register* 함수에 동적 리스너를 붙여 모든 콜백을 기록 */
    private fun registerByReflection(domain: String, target: Any, api: KClass<*>) {
        for (f in api.declaredMemberFunctions.filter { it.name.startsWith("register") }) {
            val params = f.parameters.filter { it.kind == KParameter.Kind.VALUE }
            val lp = params.firstOrNull {
                (it.type.classifier as? KClass<*>)?.qualifiedName?.startsWith("ai.pleos.playground.vehicle.listener.") == true
            }
            if (lp == null || params.size != 1) {
                P.log("reflect_skip", "domain" to domain, "fn" to f.name, "params" to params.size)
                continue
            }
            val lc = (lp.type.classifier as KClass<*>).java
            val listener = Proxy.newProxyInstance(lc.classLoader, arrayOf(lc)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "G1BListener(${lc.simpleName})"
                    else -> {
                        P.log("vsdk", "domain" to domain, "cb" to "${lc.simpleName}.${method.name}", "args" to args?.joinToString())
                        null
                    }
                }
            }
            try {
                f.call(target, listener)
                P.log("reflect_reg", "domain" to domain, "fn" to f.name, "ok" to true)
            } catch (e: Throwable) {
                P.log("reflect_reg_error", "domain" to domain, "fn" to f.name, "err" to (e.cause ?: e))
            }
        }
    }

    // ---------------------------------------------------------------- NaviHelper: poiId 없는 먼 경로
    private fun initNavi() {
        try {
            val h = NaviHelper(applicationContext)
            h.initialize()
            val l = Proxy.newProxyInstance(
                NaviHelperEventListener::class.java.classLoader,
                arrayOf(NaviHelperEventListener::class.java)
            ) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "G1BNaviListener"
                    else -> {
                        P.log("navi", "cb" to method.name, "args" to args?.joinToString())
                        if (method.name == "onRouteStarted") main.post { onRouteStartedHook() }
                        null
                    }
                }
            } as NaviHelperEventListener
            h.addListener(l)
            navi = h
            P.log("navi_init", "ok" to true)
        } catch (e: Throwable) {
            P.log("navi_error", "err" to e)
        }
    }

    private fun routeFar() {
        try {
            val h = navi ?: run { P.log("route_error", "err" to "navi_not_ready"); return }
            h.requestRoute(
                RouteInfo(
                    latitude = 37.3948,
                    longitude = 127.1112,
                    poiId = "",
                    poiName = "판교역",
                    poiSubId = "0",
                    address = "경기도 성남시 분당구 판교역로 160"
                )
            )
            P.log("route_req", "ok" to true, "poiId" to "empty", "mode" to b7Mode)
        } catch (e: Throwable) {
            P.log("route_error", "err" to e)
        }
    }

    // ---------------------------------------------------------------- 재생 감시: 누가 지금 소리를 내는가
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
            P.log("audio_watch", "ok" to true)
        } catch (e: Throwable) {
            P.log("audio_watch_error", "err" to e)
        }
    }

    /** 일반 앱이 받는 목록은 익명화돼 있어 앱 이름은 없고 용도(usage)·내용 종류만 보임 → piid(재생기 번호)로 추적 */
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
        val removed = lastKeys - cur
        lastKeys = cur
        P.log("play", "n" to items.size,
            "list" to items.joinToString(",") { "${it.key}:${it.usage}:${it.content}" }.ifEmpty { "-" },
            "add" to added.joinToString(",") { "${it.key}:${it.usage}" }.ifEmpty { "-" },
            "rm" to removed.joinToString(",").ifEmpty { "-" })
        for (x in added) {
            if (speaking && selfWait && x.usage == speakUsage && now - speakT < 6000) {
                selfWait = false
                selfKey = x.key
                P.log("self_player", "key" to x.key, "usage" to x.usage, "content" to x.content, "ms" to now - speakT, "id" to curId)
            } else if (x.usage == NAV) {
                onNavAudioStart(x.key, now)
            }
        }
        if (navKey.isNotEmpty() && navKey !in cur) onNavAudioEnd(now)
        if (selfKey.isNotEmpty() && selfKey !in cur) {
            P.log("self_player_end", "key" to selfKey, "id" to curId)
            selfKey = ""
        }
    }

    private fun onNavAudioStart(key: String, now: Long) {
        if (navKey.isNotEmpty()) return
        navKey = key
        navT = now
        P.log("nav_audio", "s" to "start", "key" to key, "mode" to b7Mode, "speaking" to speaking)
        if (b7Mode.startsWith("nav_first")) {
            pendingNavFirst?.let { p ->
                pendingNavFirst = null
                speak(p.first, p.second, LONG_TEXT, "b7nf")
            }
        } else if (b7Mode == "yield_nav" && speaking && !yieldWaiting) {
            yieldWaiting = true
            P.log("yield", "s" to "stop", "via" to "play", "id" to curId)
            try { tts?.stop() } catch (e: Throwable) { P.log("tts_stop_error", "err" to e) }
        }
    }

    private fun onNavAudioEnd(now: Long) {
        P.log("nav_audio", "s" to "end", "key" to navKey, "dur" to now - navT, "mode" to b7Mode)
        navKey = ""
        if (b7Mode == "yield_nav" && yieldWaiting) b7h.postDelayed({ yieldRetry() }, 1500)
    }

    /** 물러선 뒤: 포커스를 돌려받거나(GAIN) 내비 소리가 끝나고 1.5초 조용하면 짧게 다시 말함(그 사이 내비가 또 말하면 다음 끝을 기다림) */
    private fun yieldRetry() {
        if (b7Mode != "yield_nav" || !yieldWaiting) return
        if (navKey.isNotEmpty()) {
            P.log("yield", "s" to "wait_more", "nav" to navKey)
            return
        }
        if (speaking) {
            P.log("yield", "s" to "no_onStop_after_stop", "id" to curId)
            speaking = false
            abandonFocus()
        }
        yieldWaiting = false
        P.log("yield", "s" to "resume")
        speak(NAV, "nav", RETRY_TEXT, "b7yr", pauseWhenDucked = true)
    }

    // ---------------------------------------------------------------- 음성: Pleos TextToSpeech(ON_DEVICE) + 오디오 포커스
    private val ttsEv: TtsEventListener by lazy {
        proxyOf(TtsEventListener::class.java, "G1BTtsEvents") { m, a ->
            if (m == "onUpdatedRms") {
                rmsN++
                if (rmsN == 1) P.log("tts_ev", "m" to m, "id" to curId)
            } else {
                P.log("tts_ev", "m" to m, "id" to curId, "arg" to a?.firstOrNull(), "rmsN" to rmsN)
                main.post { onTtsEvent(m) }
            }
        }
    }

    private fun initTts() {
        try {
            P.log("tts_perm", "granted" to (checkSelfPermission("pleos.car.permission.TTS_SERVICE") == android.content.pm.PackageManager.PERMISSION_GRANTED))
            val t = PleosTts(applicationContext, TtsMode.ON_DEVICE)
            tts = t
            val l = proxyOf(TtsInitListener::class.java, "G1BTtsInit") { m, a ->
                if (m == "onSuccess") {
                    try { t.addEventListener(ttsEv) } catch (e: Throwable) { P.log("tts_listener_error", "err" to e) }
                    try { t.setLocale(Locale.KOREA) } catch (e: Throwable) { P.log("tts_locale_error", "err" to e) }
                    ttsReady = true
                    P.log("tts_init", "ok" to true, "mode" to "ON_DEVICE", "sdk" to "pleos-tts-3.3.1")
                } else {
                    P.log("tts_init", "ok" to false, "cb" to m, "err" to a?.firstOrNull())
                }
            }
            t.initialize(l)
            P.log("tts_init_call", "mode" to "ON_DEVICE")
            main.postDelayed({ if (!ttsReady) P.log("tts_init_timeout", "ms" to 10000) }, 10000)
        } catch (e: Throwable) {
            P.log("tts_init_error", "err" to e)
        }
    }

    private fun onTtsEvent(m: String) {
        when (m) {
            "onDone", "onStop", "onError" -> {
                speaking = false
                selfWait = false
                if (!(b7Mode == "yield_nav" && yieldWaiting)) abandonFocus()  // 물러서 기다리는 중엔 포커스 요청을 유지 → 내비가 끝나면 GAIN
            }
        }
    }

    private fun speak(usage: Int, tag: String, text: String, purpose: String, pauseWhenDucked: Boolean = false) {
        val t = tts
        if (t == null || !ttsReady) {
            P.log("tts_not_ready", "usage" to tag, "purpose" to purpose)
            return
        }
        if (speaking) {
            P.log("tts_busy", "usage" to tag, "purpose" to purpose, "cur" to curId)
            return
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        requestFocus(attrs, tag, pauseWhenDucked)
        try { t.setAudioAttributes(attrs) } catch (e: Throwable) { P.log("tts_attr_error", "err" to e) }
        curId = "$tag-$purpose-${System.currentTimeMillis()}"
        rmsN = 0
        speakUsage = usage
        speakT = System.currentTimeMillis()
        speaking = true
        selfWait = true
        selfKey = ""
        try {
            t.speak(text, true)
            P.log("tts_speak", "id" to curId, "usage" to tag, "purpose" to purpose, "chars" to text.length)
        } catch (e: Throwable) {
            P.log("tts_speak_error", "id" to curId, "err" to e)
            speaking = false
            selfWait = false
            abandonFocus()
        }
    }

    /**
     * pauseWhenDucked=true면 차량 포커스 규칙상 같은 시간에 들어온 MAY_DUCK 요청(예: 내비 안내)에 대해
     * 우리가 ‘포커스를 잃는 쪽’이 됨 → 내비가 소리를 내기 전에(포커스 요청 순간) 알림을 받음 = 물러서기 1차 신호
     */
    private fun requestFocus(attrs: AudioAttributes, tag: String, pauseWhenDucked: Boolean = false) {
        abandonFocus()
        try {
            val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(pauseWhenDucked)
                .setOnAudioFocusChangeListener({ ch -> onFocusChange(ch) }, main)
                .build()
            focusReq = fr
            focusTag = tag
            P.log("focus", "usage" to tag, "result" to am.requestAudioFocus(fr), "pauseWhenDucked" to pauseWhenDucked)
        } catch (e: Throwable) {
            P.log("focus_error", "usage" to tag, "err" to e)
        }
    }

    private fun onFocusChange(ch: Int) {
        P.log("focus_change", "usage" to focusTag, "change" to ch, "mode" to b7Mode, "speaking" to speaking)
        if (b7Mode != "yield_nav") return
        if (ch < 0 && speaking && !yieldWaiting) {  // LOSS(-1)·LOSS_TRANSIENT(-2)·LOSS_TRANSIENT_CAN_DUCK(-3)
            yieldWaiting = true
            P.log("yield", "s" to "stop", "via" to "focus", "id" to curId)
            try { tts?.stop() } catch (e: Throwable) { P.log("tts_stop_error", "err" to e) }
        } else if (ch == AudioManager.AUDIOFOCUS_GAIN && yieldWaiting) {
            P.log("yield", "s" to "gain")
            b7h.postDelayed({ yieldRetry() }, 1500)
        }
    }

    private fun abandonFocus() {
        val fr = focusReq ?: return
        try {
            am.abandonAudioFocusRequest(fr)
        } catch (e: Throwable) {
            P.log("focus_abandon_error", "err" to e)
        }
        focusReq = null
    }

    // ---------------------------------------------------------------- B7: 내비 안내와 겹칠 때
    private fun b7Reset(mode: String) {
        b7h.removeCallbacksAndMessages(null)
        pendingNavFirst = null
        yieldWaiting = false
        b7Mode = mode
        P.log("b7_case", "mode" to mode, "speaking" to speaking, "navAudio" to navKey.isNotEmpty())
    }

    /** 운전약속이 먼저 길게 말하는 중에 경로를 시작 → 내비가 회전 안내를 끼워 넣음 */
    private fun b7DpFirst(usage: Int, tag: String) {
        b7Reset("dp_first_$tag")
        speak(usage, tag, LONG_TEXT, "b7df")
        b7h.postDelayed({ routeFar() }, 1200)
    }

    /** 경로를 시작해 내비가 먼저 말하게 하고, 내비 소리가 보이는 순간 운전약속이 말함 */
    private fun b7NavFirst(usage: Int, tag: String) {
        b7Reset("nav_first_$tag")
        pendingNavFirst = usage to tag
        routeFar()
        b7h.postDelayed({
            pendingNavFirst?.let { p ->
                pendingNavFirst = null
                P.log("b7_fallback", "why" to "no_nav_audio_15s")
                speak(p.first, p.second, LONG_TEXT, "b7nf")
            }
        }, 15000)
    }

    private fun onRouteStartedHook() {
        P.log("route_started_hook", "mode" to b7Mode)
        if (b7Mode.startsWith("nav_first") && pendingNavFirst != null) {
            b7h.postDelayed({
                pendingNavFirst?.let { p ->
                    pendingNavFirst = null
                    P.log("b7_fallback", "why" to "no_nav_audio_4s_after_route")
                    speak(p.first, p.second, LONG_TEXT, "b7nf")
                }
            }, 4000)
        }
    }

    /** 정책 후보 시연: 내비 안내 용도 + pauseWhenDucked로 말하다가 내비가 포커스를 요청하면(1차) 또는 내비 소리가 보이면(2차) 즉시 멈추고, 끝나면 1.5초 뒤 짧게 다시 */
    private fun b7Yield() {
        b7Reset("yield_nav")
        speak(NAV, "nav", LONG_TEXT, "b7y", pauseWhenDucked = true)
        b7h.postDelayed({ routeFar() }, 1200)
    }

    private fun b7End(why: String) {
        b7h.removeCallbacksAndMessages(null)
        P.log("b7_end", "mode" to b7Mode, "why" to why, "speaking" to speaking)
        b7Mode = "none"
        pendingNavFirst = null
        yieldWaiting = false
        if (speaking) {
            try { tts?.stop() } catch (e: Throwable) { P.log("tts_stop_error", "err" to e) }
        }
        abandonFocus()
    }

    override fun onDestroy() {
        P.log("svc", "s" to "destroy")
        b7h.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(null)
        try { playCb?.let { am.unregisterAudioPlaybackCallback(it) } } catch (_: Throwable) {}
        try { tts?.removeEventListener(ttsEv) } catch (_: Throwable) {}
        try { tts?.release() } catch (_: Throwable) {}
        try { navi?.release() } catch (_: Throwable) {}
        try { vehicle?.release() } catch (_: Throwable) {}
        try { car?.disconnect() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
