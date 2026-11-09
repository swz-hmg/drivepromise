package app.drivepromise.core.scene

/**
 * 1.3.1(P0-5·P0-6): 숫자·이름 뒤 서술격 조사와 쉬기 간격 이름.
 * - copula: 끝소리에 받침이 있으면 ‘이에요’, 없으면 ‘예요’ — 60(육십)이에요 · 95(구십오)예요 · 방면이에요 · IC예요
 * - koEvery/enEvery: 쉬기 간격 이름 — 120 → 2시간 · 90 → 1시간 30분 · 45 → 45분 (정수 나눗셈으로 90분이 ‘1시간’이 되던 문제)
 */
object Josa {
    /** 끝소리 받침 여부로 ‘이에요/예요’ */
    fun copula(word: String): String = if (endsWithBatchim(word)) "이에요" else "예요"

    fun endsWithBatchim(word: String): Boolean {
        val c = word.trim().lastOrNull() ?: return false
        return when {
            c in '가'..'힣' -> (c - '가') % 28 != 0
            c.isDigit() -> digitBatchim(word.trim())
            c.isLetter() -> c.uppercaseChar() in "LMNR"          // 엘·엠·엔·알 — 영문 글자 이름의 끝소리
            else -> false
        }
    }

    /** 숫자 읽기의 끝소리: 끝자리 0은 십·백·천(받침) · 1 일 · 3 삼 · 6 육 · 7 칠 · 8 팔(받침) · 2 이 · 4 사 · 5 오 · 9 구(받침 없음) */
    private fun digitBatchim(s: String): Boolean {
        val d = s.last() - '0'
        if (d != 0) return d in setOf(1, 3, 6, 7, 8)
        val digits = s.takeLastWhile { it.isDigit() }.trimStart('0')
        return digits.isNotEmpty()   // 10 십 · 100 백 · 1000 천 · 10000 만 — 모두 받침
    }

    /** 120 → "2시간" · 90 → "1시간 30분" · 45 → "45분" (요약·타일·계획 공용) */
    fun koEvery(min: Int): String {
        val h = min / 60
        val m = min % 60
        return when {
            h == 0 -> "${m}분"
            m == 0 -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }

    /** 120 → "2 hours" · 60 → "hour" · 90 → "90 minutes" */
    fun enEvery(min: Int): String = when {
        min == 60 -> "hour"
        min % 60 == 0 -> "${min / 60} hours"
        else -> "$min minutes"
    }

    /** 계획 문장용: 60 → "1 hour" · 120 → "2 hours" · 90 → "90 minutes" */
    fun enSpan(min: Int): String = if (min == 60) "1 hour" else enEvery(min)
}
