package app.drivepromise

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.drivepromise.store.Prefs

/**
 * 1.3.0(PT 과제): 시동(차량 인포테인먼트 부팅) 때 서비스를 자동으로 시작 — 정차 화면을 열지 않아도 약속 판정·음성이 켜짐.
 * 조건: 설치 뒤 한 번은 앱을 열었어야 함(안드로이드 규칙) · 정차 화면 설정 ‘시동 때 자동 시작’(기본 켬).
 * AAOS는 운전자 사용자(10)가 켜질 때 그 사용자에게 BOOT_COMPLETED 를 보냄. 포그라운드 서비스 종류 specialUse 는 부팅 때 시작이 허용됨.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 1.3.3(P2): 헤드리스 시스템 사용자(0)에서는 시작하지 않음 — 화면이 없는 사용자라 말·위치 권한이 없고, 운전자 사용자(10)가 따로 받음
        try {
            val um = ctx.getSystemService(android.os.UserManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 31 && android.os.UserManager.isHeadlessSystemUserMode() && um?.isSystemUser == true) {
                L.log("boot", "skip" to "headless_system_user"); return
            }
        } catch (_: Throwable) {}
        val on = Prefs(ctx).autoStart
        L.log("boot", "autoStart" to on)
        if (!on) return
        try {
            DrivePromiseService.start(ctx, src = "boot")
        } catch (e: Throwable) {
            L.log("boot_error", "err" to e)
        }
    }
}
