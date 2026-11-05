package app.drivepromise.platform

import android.car.Car
import android.car.content.pm.CarPackageManager
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import app.drivepromise.L

/**
 * 주행 중 UX 제한·DO 판정(AAOS) — 프로브 G1-B(uxr_init·do_check) 코드를 앱으로 옮김.
 *  - [requiresDo]: 지금 플랫폼이 ‘주행 중 허용 화면(DO)만 보이게’ 하는가(기어 D 등)
 *  - [activeRestrictions]: 활성 제한 비트(0x50 = 영상 금지 + 설정 금지 — G1 실측)
 *  - [maxStringLength]: 주행 중 허용 글자 수(플랫폼 값, 없으면 120)
 *  - [isOptimized]: 우리 화면(Activity)이 플랫폼에서 DO 로 인정되는가(사이드로드 앱은 false — B1 실측)
 * 값이 바뀌면 [onChange] 로 알립니다(main 스레드). 에뮬레이터·차종마다 없는 API 는 null/UNKNOWN 으로 둡니다.
 */
class PleosCarUx(private val ctx: Context, private val main: Handler, private val onChange: () -> Unit) {
    private var car: Car? = null
    private var uxr: CarUxRestrictionsManager? = null
    private var pkg: CarPackageManager? = null
    private var listener: CarUxRestrictionsManager.OnUxRestrictionsChangedListener? = null

    var requiresDo: Boolean? = null
        private set
    var activeRestrictions: Int? = null
        private set
    var maxStringLength: Int = 120
        private set

    fun start() {
        try {
            val c = Car.createCar(ctx)
            car = c
            uxr = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            pkg = c.getCarManager(Car.PACKAGE_SERVICE) as? CarPackageManager
            uxr?.currentCarUxRestrictions?.let { apply(it, "init") }
            val l = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r -> main.post { apply(r, "change") } }
            uxr?.registerListener(l)
            listener = l
            L.log("uxr_init", "ok" to true, "requiresDO" to requiresDo, "active" to activeRestrictions, "maxLen" to maxStringLength)
        } catch (e: Throwable) {
            L.log("uxr_error", "err" to e)
        }
    }

    fun stop() {
        try { listener?.let { uxr?.unregisterListener() } } catch (_: Throwable) {}
        listener = null
        try { car?.disconnect() } catch (_: Throwable) {}
        car = null; uxr = null; pkg = null
    }

    private fun apply(r: CarUxRestrictions, why: String) {
        val req = r.isRequiresDistractionOptimization
        val act = r.activeRestrictions
        val len = try { r.maxRestrictedStringLength } catch (_: Throwable) { 120 }
        val changed = req != requiresDo || act != activeRestrictions
        requiresDo = req; activeRestrictions = act; maxStringLength = if (len > 0) len else 120
        if (changed) {
            L.log("uxr", "requiresDO" to req, "active" to act, "maxLen" to maxStringLength, "why" to why)
            onChange()
        }
    }

    /** 플랫폼에 ‘이 화면을 주행 중 띄워도 되는가’ 묻기 — true/false, API 가 없거나 실패하면 null */
    fun isOptimized(activityClass: String): Boolean? = try {
        val ok = pkg?.isActivityDistractionOptimized(ctx.packageName, activityClass)
        L.log("do_check", "activity" to activityClass.substringAfterLast('.'), "optimized" to ok, "requiresDO" to requiresDo)
        ok
    } catch (e: Throwable) {
        L.log("do_check_error", "err" to e)
        null
    }
}
