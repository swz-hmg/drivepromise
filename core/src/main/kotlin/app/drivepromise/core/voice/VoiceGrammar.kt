package app.drivepromise.core.voice

import app.drivepromise.core.speech.Lang

/**
 * 1.3.4 — G2 사전 등록(11/9 15:50 고정)의 C18 후보를 Kotlin으로 미리 옮겨 둔 것.
 * 기본은 V130 = `VoiceCommands.match` 그대로(행동 바뀜 없음 · VoiceCommands.kt는 바이트까지 그대로 → 사전 등록의 sha256 유지).
 * G2 판정(`rescore.py`의 decide)이 후보를 채택하면 [ACTIVE] 한 줄만 바꿉니다. 판정이 없거나 ‘유지’면 그대로 둡니다.
 * 후보는 ‘모르면 다시 묻기’ 쪽으로만 바꿉니다(새 명령을 만들지 않음) — rescore.py와 같은 결과인지 C18Specs(S125·S126)가 대조합니다.
 */
enum class Grammar { V130, C18A, C18B }

object VoiceGrammar {
    /** 앱이 쓰는 문법 — G2 판정 뒤 채택한 후보로 이 한 줄만 바꿈(사전 등록 §5) */
    val ACTIVE: Grammar = Grammar.V130

    fun match(text: String, lang: Lang, g: Grammar = ACTIVE): VoiceIntent {
        val r = VoiceCommands.match(text, lang)
        if (g == Grammar.V130 || lang != Lang.KO) return r
        val n = VoiceCommands.normalize(text)
        val tokens = n.split(' ')
        val joined = n.replace(" ", "")
        val yesPhrase = KO_YES.any { joined.contains(it) }
        val noPhrase = KO_NO.any { joined.contains(it) }
        return when (g) {
            // c18a: ‘약속’이 든 말의 단답(응·그래·좋아·아니…)은 대답으로 보지 않음 — ‘약속대로 쉴게’처럼 분명한 말은 그대로
            Grammar.C18A -> when {
                r == VoiceIntent.YES && "약속" in joined && !yesPhrase -> VoiceIntent.UNKNOWN
                r == VoiceIntent.NO && "약속" in joined && !noPhrase -> VoiceIntent.UNKNOWN
                else -> r
            }
            // c18b: 단답 YES는 짧은 말(낱말 2개 이하)일 때만 — ‘네 야속 어때’(R15)처럼 뒤에 다른 말이 붙으면 다시 묻기
            Grammar.C18B -> if (r == VoiceIntent.YES && !yesPhrase && tokens.size > 2) VoiceIntent.UNKNOWN else r
            Grammar.V130 -> r
        }
    }

    /** VoiceCommands의 KO_YES·KO_NO와 같은 목록(그쪽은 private이고 파일을 바꾸지 않으려고 여기 복사) — 같은지 S126이 확인 */
    internal val KO_YES = listOf("쉴게", "쉴께", "쉬어갈게", "쉬어갈께", "쉬자", "쉬어가자", "쉬어갈래", "알았어", "알겠어", "알겠습니다", "그럴게", "그렇게할게")
    internal val KO_NO = listOf("괜찮", "안쉬", "쉬지않", "안쉴", "나중에", "조금더", "좀더갈", "그냥갈", "있다가", "이따가")
}
