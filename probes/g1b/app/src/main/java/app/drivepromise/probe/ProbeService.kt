package app.drivepromise.probe

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
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
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.lang.reflect.Proxy
import java.util.Locale
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions

/**
 * G1-B 포그라운드 서비스 — 화면과 무관하게 돌며 결과를 logcat(G1B)에 남김.
 * 명령: adb shell am start-foreground-service -n app.drivepromise.probe/.ProbeService --es cmd <do_check|speak_asst|speak_nav|route_far|stop>
 * 발화 용도: USAGE_ANNOUNCEMENT는 시스템 전용(@SystemApi)이라 일반 앱은 ASSISTANT·NAVIGATION_GUIDANCE만 비교
 */
class ProbeService : Service() {
    private var car: Car? = null
    private var uxr: CarUxRestrictionsManager? = null
    private var props: CarPropertyManager? = null
    private var pkgMgr: CarPackageManager? = null
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focusReq: AudioFocusRequest? = null
    private var started = false

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
            initTts()
        }
        val cmd = intent?.getStringExtra("cmd") ?: "none"
        P.log("cmd", "cmd" to cmd)
        when (cmd) {
            "do_check" -> doCheck()
            "speak_asst", "speak_ann" -> speak(AudioAttributes.USAGE_ASSISTANT, "asst")
            "speak_nav" -> speak(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, "nav")
            "route_far" -> routeFar()
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
            P.log("route_req", "ok" to true, "poiId" to "empty")
        } catch (e: Throwable) {
            P.log("route_error", "err" to e)
        }
    }

    // ---------------------------------------------------------------- TTS: Pleos CaaS 엔진으로 발화 + 오디오 포커스
    private fun initTts() {
        try {
            tts = TextToSpeech(applicationContext, { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                val lang = try { tts?.setLanguage(Locale.KOREAN) } catch (e: Throwable) { -99 }
                P.log("tts_init", "status" to status, "engine" to tts?.defaultEngine, "lang" to lang)
            }, "ai.pleos.playground.caas")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    P.log("tts_start", "id" to utteranceId)
                }

                override fun onDone(utteranceId: String?) {
                    P.log("tts_done", "id" to utteranceId)
                    abandonFocus()
                }

                override fun onError(utteranceId: String?) {
                    P.log("tts_error", "id" to utteranceId)
                    abandonFocus()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    P.log("tts_error", "id" to utteranceId, "code" to errorCode)
                    abandonFocus()
                }
            })
        } catch (e: Throwable) {
            P.log("tts_init_error", "err" to e)
        }
    }

    private fun speak(usage: Int, tag: String) {
        if (!ttsReady) {
            P.log("tts_not_ready", "usage" to tag)
            return
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        try {
            val am = getSystemService(AudioManager::class.java)
            val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            focusReq = fr
            P.log("focus", "usage" to tag, "result" to am.requestAudioFocus(fr))
        } catch (e: Throwable) {
            P.log("focus_error", "usage" to tag, "err" to e)
        }
        tts?.setAudioAttributes(attrs)
        val id = "$tag-${System.currentTimeMillis()}"
        val r = tts?.speak("운전약속 시험 발화입니다. 주행 중에도 들리는지 확인합니다.", TextToSpeech.QUEUE_FLUSH, null, id)
        P.log("tts_speak", "id" to id, "usage" to tag, "result" to r)
    }

    private fun abandonFocus() {
        val fr = focusReq ?: return
        try {
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(fr)
        } catch (e: Throwable) {
            P.log("focus_abandon_error", "err" to e)
        }
        focusReq = null
    }

    override fun onDestroy() {
        P.log("svc", "s" to "destroy")
        try { tts?.shutdown() } catch (_: Throwable) {}
        try { navi?.release() } catch (_: Throwable) {}
        try { vehicle?.release() } catch (_: Throwable) {}
        try { car?.disconnect() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
