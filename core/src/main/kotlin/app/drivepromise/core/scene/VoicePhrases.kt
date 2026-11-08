package app.drivepromise.core.scene

import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.speech.Lang

/** 음성 명령에 대한 짧은 대답(ko/en) — 4초 안팎, 한 문장 위주. 시니어가 다음에 무엇을 말하면 되는지 알게 */
object VoicePhrases {
    fun yes(lang: Lang) = if (lang == Lang.KO) "좋아요. 다음 쉴 곳에서 쉬어요." else "Good. Let's rest at the next stop."
    fun no(lang: Lang) = if (lang == Lang.KO) "알겠어요. 조심히 가요." else "Okay. Drive safely."
    fun quiet(lang: Lang, min: Long) = if (lang == Lang.KO) "네, ${min}분 동안 조용히 할게요." else "Okay, quiet for $min minutes."
    fun unknown(lang: Lang) = if (lang == Lang.KO) "잘 못 들었어요. ‘다시’나 ‘약속 알려 줘’라고 해 주세요." else "Sorry, I missed that. Say 'again' or 'my promises'."
    fun help(lang: Lang) = if (lang == Lang.KO) "‘다시’나 ‘약속 알려 줘’라고 해 주세요." else "Say 'again' or 'my promises'."
    fun nothing(lang: Lang) = if (lang == Lang.KO) "아직 들려드린 말이 없어요." else "Nothing to repeat yet."

    /**
     * 지금 약속 상태 한두 마디 — 위험·못 지킴인 것만 이름을 말하고 나머지는 묶음.
     * 1.2.2(11/6 실행): 아직 판단할 수 없는 약속(UNKNOWN — 예: 목적지가 없어 도착 시각을 모르는 ‘해 지기 전 귀가’)이 있으면
     * ‘모두’라고 하지 않고 그 약속을 따로 말함 — 화면엔 약속 5개가 있는데 ‘약속 4개 모두 지키는 중’이라 들리던 어긋남(S83).
     */
    fun status(lang: Lang, promises: List<Promise>, statuses: Map<String, PromiseStatus>): String {
        fun st(p: Promise) = statuses[p.id] ?: PromiseStatus.UNKNOWN
        val known = promises.filter { st(it) !in setOf(PromiseStatus.UNKNOWN, PromiseStatus.NOT_APPLICABLE) }
        val unknown = promises.filter { st(it) == PromiseStatus.UNKNOWN }
        val risky = known.filter { st(it) == PromiseStatus.AT_RISK || st(it) == PromiseStatus.MISSED }
        val ko = lang == Lang.KO
        val rest = known.size - risky.size
        return when {
            known.isEmpty() -> if (ko) "아직 판단할 신호가 없어요." else "No signals to judge yet."
            risky.isEmpty() && unknown.isEmpty() -> if (ko) "약속 ${known.size}개 모두 지키는 중이에요." else "All ${known.size} promises are on track."
            risky.isEmpty() -> if (ko) "약속 ${known.size}개는 지키는 중이에요. " + unknownTail(lang, unknown) else "${known.size} promises are on track; " + unknownTail(lang, unknown)
            risky.size > 2 -> if (ko) "약속 ${risky.size}개가 위험해요. 하나씩 조심해요." else "${risky.size} promises are at risk. Take care."
            else -> {
                val names = risky.map { SummaryPhrases.name(lang, it) }
                // 1.3.0(C10): 위험 약속이 있어도 판단 전 약속을 빠뜨리지 않음(antigravity BUG-02) — 영어도 개수를 말함
                if (ko) {
                    val joined = names.joinToString("·")
                    "$joined${josa(joined, "이", "가")} 위험해요." + when {
                        rest <= 0 -> ""
                        unknown.isEmpty() -> " 나머지는 지키는 중이에요."
                        else -> " ${rest}개는 지키는 중이에요."
                    } + (if (unknown.isEmpty()) "" else " " + unknownTail(lang, unknown))
                } else {
                    names.joinToString(" and ").replaceFirstChar { it.uppercase() } + (if (risky.size == 1) " is" else " are") + " at risk." +
                        (if (rest > 0) " $rest on track." else "") + (if (unknown.isEmpty()) "" else " " + unknownTail(lang, unknown).replaceFirstChar { it.uppercase() })
                }
            }
        }
    }

    /** 판단 전인 약속 — 하나면 이름, 둘 이상이면 개수 */
    private fun unknownTail(lang: Lang, unknown: List<Promise>): String {
        val ko = lang == Lang.KO
        return if (unknown.size == 1) {
            val n = SummaryPhrases.name(lang, unknown[0])
            if (ko) "$n${josa(n, "은", "는")} 아직 몰라요." else "$n is not known yet."
        } else {
            if (ko) "${unknown.size}개는 아직 몰라요." else "${unknown.size} are not known yet."
        }
    }

    /** 받침이 있으면 a(이), 없으면 b(가) — 한글이 아니면 b */
    fun josa(word: String, a: String, b: String): String {
        val c = word.trimEnd().lastOrNull() ?: return b
        if (c !in '가'..'힣') return b
        return if ((c - '가') % 28 != 0) a else b
    }
}
