package app.drivepromise.probe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/** 모든 결과를 logcat 태그 G1B 한 줄로 남김: "event=… t=<ms> k=v …" (러너 스크립트가 이 줄을 읽어 판정) */
object P {
    const val TAG = "G1B"

    fun log(event: String, vararg kv: Pair<String, Any?>) {
        val line = buildString {
            append("event=").append(event)
            append(" t=").append(System.currentTimeMillis())
            for ((k, v) in kv) {
                append(' ').append(k).append('=')
                append(v.toString().replace('\n', ' ').replace(' ', '_').take(800))
            }
        }
        Log.i(TAG, line)
    }
}

/** 화면 수명 주기를 기록하는 바탕 화면 — 주행 중 차단되면 onPause·onStop이 찍힘(B4) */
open class LogActivity : Activity() {
    open val label: String = "main"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "G1-B probe · $label"
        tv.textSize = 30f
        tv.setPadding(48, 48, 48, 48)
        setContentView(tv)
        P.log("act", "a" to label, "s" to "create")
    }

    override fun onResume() {
        super.onResume()
        P.log("act", "a" to label, "s" to "resume")
    }

    override fun onPause() {
        super.onPause()
        P.log("act", "a" to label, "s" to "pause")
    }

    override fun onStop() {
        super.onStop()
        P.log("act", "a" to label, "s" to "stop")
    }
}

/** 정차 화면(DO 선언 없음). 열리면 포그라운드 서비스를 시작 */
class MainActivity : LogActivity() {
    override val label: String = "main"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, ProbeService::class.java))
    }
}

/** 주행 중 상태 화면(DO 선언). B1에서 주행 중 가려지는지 확인 */
class GlanceStatusActivity : LogActivity() {
    override val label: String = "glance"
}
