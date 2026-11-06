package app.drivepromise.core.ledger

import app.drivepromise.core.replay.MiniJson

/**
 * 기록 한 줄 ↔ JSON(한 줄, JSON Lines). 앱은 파일(files/ledger/ 아래 .jsonl)에 덧붙이고, 다시 읽어 지표(RestCompliance)를 계산합니다.
 * 기기 안에만 두며 서버로 보내지 않습니다(계약 §7).
 */
object LedgerJson {
    fun encode(e: LedgerEvent): String {
        val sb = StringBuilder("{\"t\":").append(e.tMillis)
        when (e) {
            is LedgerEvent.Due -> sb.kv("e", "Due").kv("key", e.key).kv("drivingMs", e.drivingMs)
            is LedgerEvent.Spoken -> sb.kv("e", "Spoken").kv("key", e.key).kv("repeat", e.repeat).kv("yields", e.yields).kv("endMs", e.endMs).kv("lateMs", e.lateMs)
            is LedgerEvent.Yielded -> sb.kv("e", "Yielded").kv("key", e.key).kv("count", e.count)
            is LedgerEvent.Dropped -> sb.kv("e", "Dropped").kv("key", e.key).kv("reason", e.reason)
            is LedgerEvent.Digested -> sb.kv("e", "Digested").kv("key", e.key).kv("reason", e.reason)
            is LedgerEvent.RestTaken -> sb.kv("e", "RestTaken").kv("restStartMs", e.restStartMs).kv("segmentDrivingMs", e.segmentDrivingMs)
            is LedgerEvent.TripEnded -> sb.kv("e", "TripEnded").kv("drivingMs", e.drivingMs)
            is LedgerEvent.StatusChanged -> sb.kv("e", "StatusChanged").kv("promiseId", e.promiseId).kv("from", e.from).kv("to", e.to).kv("note", e.note)
            is LedgerEvent.VoiceCommand -> sb.kv("e", "VoiceCommand").kv("trigger", e.trigger).kv("intent", e.intent).kv("question", e.question)
        }
        return sb.append('}').toString()
    }

    /** 한 줄 → 이벤트. 모르는 종류·깨진 줄은 null(기록 파일은 버전이 섞일 수 있음) */
    fun decode(line: String): LedgerEvent? {
        val m = runCatching { MiniJson.parse(line) as? Map<*, *> }.getOrNull() ?: return null
        val t = m.long("t") ?: return null
        return when (m["e"]) {
            "Due" -> LedgerEvent.Due(t, m.str("key"), m.long("drivingMs") ?: 0)
            "Spoken" -> LedgerEvent.Spoken(t, m.str("key"), m.int("repeat"), m.int("yields"), m.long("endMs") ?: t, m.long("lateMs"))
            "Yielded" -> LedgerEvent.Yielded(t, m.str("key"), m.int("count"))
            "Dropped" -> LedgerEvent.Dropped(t, m.str("key"), m.str("reason"))
            "Digested" -> LedgerEvent.Digested(t, m.str("key"), m.str("reason"))
            "RestTaken" -> LedgerEvent.RestTaken(t, m.long("restStartMs") ?: t, m.long("segmentDrivingMs") ?: 0)
            "TripEnded" -> LedgerEvent.TripEnded(t, m.long("drivingMs") ?: 0)
            "StatusChanged" -> LedgerEvent.StatusChanged(t, m.str("promiseId"), m.str("from"), m.str("to"), m.str("note"))
            "VoiceCommand" -> LedgerEvent.VoiceCommand(t, m.str("trigger"), m.str("intent"), m.str("question"))
            else -> null
        }
    }

    fun decodeAll(text: String): List<LedgerEvent> = text.lineSequence().mapNotNull { l -> l.trim().takeIf { it.isNotEmpty() }?.let { decode(it) } }.toList()

    private fun StringBuilder.kv(k: String, v: Any?): StringBuilder {
        append(",\"").append(k).append("\":")
        when (v) {
            null -> append("null")
            is Number, is Boolean -> append(v.toString())
            else -> append('"').append(esc(v.toString())).append('"')
        }
        return this
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    private fun Map<*, *>.str(k: String): String = this[k]?.toString() ?: ""
    private fun Map<*, *>.long(k: String): Long? = (this[k] as? Number)?.toLong()
    private fun Map<*, *>.int(k: String): Int = (this[k] as? Number)?.toInt() ?: 0
}
