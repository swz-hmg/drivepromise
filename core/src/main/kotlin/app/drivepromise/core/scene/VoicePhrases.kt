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

    /** 지금 약속 상태 한두 마디 — 위험·못 지킴인 것만 이름을 말하고 나머지는 묶음 */
    fun status(lang: Lang, promises: List<Promise>, statuses: Map<String, PromiseStatus>): String {
        val known = promises.filter { (statuses[it.id] ?: PromiseStatus.UNKNOWN) !in setOf(PromiseStatus.UNKNOWN, PromiseStatus.NOT_APPLICABLE) }
        val risky = known.filter { statuses[it.id] == PromiseStatus.AT_RISK || statuses[it.id] == PromiseStatus.MISSED }
        val ko = lang == Lang.KO
        return when {
            known.isEmpty() -> if (ko) "아직 판단할 신호가 없어요." else "No signals to judge yet."
            risky.isEmpty() -> if (ko) "약속 ${known.size}개 모두 지키는 중이에요." else "All ${known.size} promises are on track."
            risky.size > 2 -> if (ko) "약속 ${risky.size}개가 위험해요. 하나씩 조심해요." else "${risky.size} promises are at risk. Take care."
            else -> {
                val names = risky.map { SummaryPhrases.name(lang, it) }
                if (ko) {
                    val joined = names.joinToString("·")
                    val rest = known.size - risky.size
                    "$joined${josa(joined, "이", "가")} 위험해요." + if (rest > 0) " 나머지는 지키는 중이에요." else ""
                } else {
                    names.joinToString(" and ").replaceFirstChar { it.uppercase() } + (if (risky.size == 1) " is" else " are") + " at risk."
                }
            }
        }
    }

    /** 받침이 있으면 a(이), 없으면 b(가) — 한글이 아니면 b */
    fun josa(word: String, a: String, b: String): String {
        val c = word.trimEnd().lastOrNull() ?: return b
        if (c !in '가'..'힣') return b
        return if ((c - '가') % 28 != 0) a else b
    }
}
