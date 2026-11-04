package app.drivepromise.core.replay

/**
 * 작은 JSON 읽개 — 리플레이 트립 파일만 읽으면 되므로 라이브러리 없이(코어는 의존성 0).
 * 결과: Map<String, Any?> · List<Any?> · String · Long(정수) · Double(소수·지수) · Boolean · null.
 * 오류는 위치(문자 번호)와 함께 IllegalArgumentException.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = P(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) p.fail("JSON 뒤에 남은 글자")
        return v
    }

    private class P(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON $msg (위치 $i)")

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            if (i >= s.length) fail("값이 없음")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("알 수 없는 글자 '$c'")
            }
        }

        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("‘$w’ 기대")
            i += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("키(문자열) 기대")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("‘:’ 기대")
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) fail("‘}’ 기대")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> fail("‘,’ 또는 ‘}’ 기대")
                }
            }
        }

        fun arr(): List<Any?> {
            val a = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                ws()
                a += value()
                ws()
                if (i >= s.length) fail("‘]’ 기대")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return a }
                    else -> fail("‘,’ 또는 ‘]’ 기대")
                }
            }
        }

        fun str(): String {
            val b = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) fail("문자열이 끝나지 않음")
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("이스케이프가 끝나지 않음")
                        when (val e = s[i++]) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n')
                            'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u 뒤 4자리 기대")
                                val h = s.substring(i, i + 4)
                                b.append(Char(h.toIntOrNull(16) ?: fail("\\u$h 잘못됨")))
                                i += 4
                            }
                            else -> fail("알 수 없는 이스케이프 \\$e")
                        }
                    }
                    c < ' ' -> fail("문자열 안 제어 문자")
                    else -> b.append(c)
                }
            }
        }

        fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var frac = false
            if (i < s.length && s[i] == '.') { frac = true; i++; while (i < s.length && s[i] in '0'..'9') i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                frac = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(st, i)
            if (t == "-" || t.isEmpty()) fail("숫자 잘못됨")
            return if (frac) t.toDoubleOrNull() ?: fail("숫자 잘못됨 $t") else t.toLongOrNull() ?: t.toDouble()
        }
    }
}
