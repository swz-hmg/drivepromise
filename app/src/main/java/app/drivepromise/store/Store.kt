package app.drivepromise.store

import android.content.Context
import app.drivepromise.L
import app.drivepromise.core.ledger.LedgerEvent
import app.drivepromise.core.ledger.LedgerJson
import app.drivepromise.core.ledger.LedgerSink
import app.drivepromise.core.model.Promise
import app.drivepromise.core.rest.RestTracker
import app.drivepromise.core.speech.Lang
import app.drivepromise.core.ui.ScreenWhileDriving
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 기기 안 기록 파일 — files/ledger/YYYY-MM.jsonl 에 한 줄씩 덧붙임(서버 전송 없음, 계약 §7) */
class LedgerFile(ctx: Context) : LedgerSink {
    private val dir = File(ctx.filesDir, "ledger").apply { mkdirs() }
    private val fmt = SimpleDateFormat("yyyy-MM", Locale.US)

    override fun accept(e: LedgerEvent) {
        try {
            File(dir, fmt.format(Date(e.tMillis)) + ".jsonl").appendText(LedgerJson.encode(e) + "\n")
        } catch (t: Throwable) {
            L.log("ledger_write_error", "err" to t)
        }
    }

    /** 모든 기록(시간 순) — 지표 계산용 */
    fun readAll(): List<LedgerEvent> =
        (dir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()).sortedBy { it.name }
            .flatMap { f -> runCatching { LedgerJson.decodeAll(f.readText()) }.getOrDefault(emptyList()) }
            .sortedBy { it.tMillis }

    fun sizeBytes(): Long = (dir.listFiles() ?: emptyArray()).sumOf { it.length() }
}

/** 설정·이어 가기 값 — SharedPreferences. 약속 설정은 사용자가 정차 화면에서 바꿈(v0.1: 기본 5개 모두 켬) */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("drivepromise", Context.MODE_PRIVATE)

    var lang: Lang
        get() = if (sp.getString("lang", "ko") == "en") Lang.EN else Lang.KO
        set(v) { sp.edit().putString("lang", if (v == Lang.EN) "en" else "ko").apply() }

    /** 주행 중 화면 — 기본 ON(가능하면 화면). 화면모드전환설계 v0.1: 설정은 화면을 줄이는 쪽으로만 작동 */
    var screenWhileDriving: ScreenWhileDriving
        get() = if (sp.getString("screen.driving", "on") == "voice") ScreenWhileDriving.VOICE_ONLY else ScreenWhileDriving.ON
        set(v) { sp.edit().putString("screen.driving", if (v == ScreenWhileDriving.VOICE_ONLY) "voice" else "on").apply() }

    /** DO(주행 중 허용 화면) 인정 가정 — 11/5 Q036 기본 켬. 끄면 플랫폼 답(isActivityDistractionOptimized)만 믿음 */
    var assumeDo: Boolean
        get() = sp.getBoolean("screen.assumeDo", true)
        set(v) { sp.edit().putBoolean("screen.assumeDo", v).apply() }

    /**
     * 질문 뒤 듣기(STT C안의 B) — 기본 켬. 끄면 앱이 먼저 마이크를 열지 않고(쉬기 알림도 질문 없는 문장), ‘말하기’ 버튼(A)만 씀.
     * 버튼은 운전자가 누를 때만 들으므로 이 설정과 상관없이 동작합니다.
     */
    var listenAfterQuestion: Boolean
        get() = sp.getBoolean("voice.afterQuestion", true)
        set(v) { sp.edit().putBoolean("voice.afterQuestion", v).apply() }

    /** 1.3.0(PT 과제): 시동(부팅) 때 자동 시작 — 기본 켬 */
    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", true)
        set(v) { sp.edit().putBoolean("autoStart", v).apply() }

    /** 1.3.0 약속 값(정차 화면 ‘약속 정하기’) */
    var restEveryMin: Int
        get() = sp.getInt("rest.everyMin", 120)
        set(v) { sp.edit().putInt("rest.everyMin", v).apply() }
    var darkMarginMin: Int
        get() = sp.getInt("dark.marginMin", 30)
        set(v) { sp.edit().putInt("dark.marginMin", v).apply() }
    var zoneRadiusM: Int
        get() = sp.getInt("zone.radiusM", 10_000)
        set(v) { sp.edit().putInt("zone.radiusM", v).apply() }
    fun promiseOn(id: String): Boolean = id in (sp.getStringSet("promises.on", null) ?: PROMISE_IDS.toSet())

    fun restState(): RestTracker.State? {
        if (!sp.contains("rest.drivingMs")) return null
        return RestTracker.State(
            drivingMs = sp.getLong("rest.drivingMs", 0),
            lastDrivingAtMs = sp.getLong("rest.lastDrivingAtMs", -1).takeIf { it >= 0 },
            headsUpDone = sp.getBoolean("rest.headsUpDone", false),
            mainDone = sp.getBoolean("rest.mainDone", false),
            parkedAtSave = sp.getBoolean("rest.parkedAtSave", false),   // 1.3.0(R08): 저장 때 세워져 있었는가
        )
    }

    fun saveRest(s: RestTracker.State) {
        sp.edit()
            .putLong("rest.drivingMs", s.drivingMs)
            .putLong("rest.lastDrivingAtMs", s.lastDrivingAtMs ?: -1)
            .putBoolean("rest.headsUpDone", s.headsUpDone)
            .putBoolean("rest.mainDone", s.mainDone)
            .putBoolean("rest.parkedAtSave", s.parkedAtSave)
            .apply()
    }

    /** 약속 설정 — 켠 약속 id 집합 + 구역 중심·반경 + 어두워지기 전 여유 */
    fun promises(): List<Promise> {
        val on = sp.getStringSet("promises.on", null) ?: PROMISE_IDS.toSet()
        val list = mutableListOf<Promise>()
        if ("dark" in on) list += Promise.HomeBeforeDark(sp.getInt("dark.marginMin", 30))
        if ("highway" in on) list += Promise.AvoidHighway()
        if ("rain" in on) list += Promise.RainCare()
        if ("rest" in on) list += Promise.RestEvery(sp.getInt("rest.everyMin", 120))
        if ("zone" in on) list += Promise.StayInZone(
            lat = java.lang.Double.longBitsToDouble(sp.getLong("zone.lat", java.lang.Double.doubleToLongBits(37.5665))),
            lon = java.lang.Double.longBitsToDouble(sp.getLong("zone.lon", java.lang.Double.doubleToLongBits(126.9780))),
            radiusM = sp.getInt("zone.radiusM", 10_000),
        )
        return list
    }

    /** 1.3.1(P0-11): 약속·화면·음성 설정을 처음 값으로(쉬기 기록은 그대로) — 심사·시연 전 한 번 */
    fun resetSettings() {
        sp.edit().remove("promises.on").remove("rest.everyMin").remove("dark.marginMin")
            .remove("zone.lat").remove("zone.lon").remove("zone.radiusM")
            .remove("voice.afterQuestion").remove("autoStart").remove("screen.driving").remove("screen.assumeDo").remove("lang")
            .apply()
    }

    fun setPromiseOn(id: String, on: Boolean) {
        val cur = (sp.getStringSet("promises.on", null) ?: PROMISE_IDS.toSet()).toMutableSet()
        if (on) cur += id else cur -= id
        sp.edit().putStringSet("promises.on", cur).apply()
    }

    fun setZone(lat: Double, lon: Double, radiusM: Int) {
        sp.edit().putLong("zone.lat", java.lang.Double.doubleToLongBits(lat)).putLong("zone.lon", java.lang.Double.doubleToLongBits(lon)).putInt("zone.radiusM", radiusM).apply()
    }

    companion object {
        val PROMISE_IDS = listOf("dark", "highway", "rain", "rest", "zone")
    }
}
