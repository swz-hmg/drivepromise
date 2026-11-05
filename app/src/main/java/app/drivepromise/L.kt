package app.drivepromise

import android.util.Log

/**
 * 앱 기록 — logcat 태그 DP 한 줄("event=… t=<ms> k=v …"). 실행 스크립트(SMOKE 단계)가 이 줄을 읽어 판정하고,
 * 정차 화면의 ‘기록’ 칸에도 마지막 줄들을 보여 줍니다. 개인 정보(이름·주소)는 넣지 않습니다.
 */
object L {
    const val TAG = "DP"
    private const val KEEP = 200
    private val ring = ArrayDeque<String>()
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
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
        ring.addLast(line)
        while (ring.size > KEEP) ring.removeFirst()
        listeners.toList().forEach { runCatching { it(line) } }
    }

    @Synchronized
    fun recent(n: Int = 30): List<String> = ring.toList().takeLast(n)

    @Synchronized
    fun listen(l: (String) -> Unit): () -> Unit {
        listeners += l
        return { synchronized(this) { listeners -= l } }
    }
}
