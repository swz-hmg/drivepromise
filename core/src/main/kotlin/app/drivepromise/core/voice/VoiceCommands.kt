package app.drivepromise.core.voice

import app.drivepromise.core.speech.Lang

/**
 * 음성 명령의 뜻 — 11/6 STT C안(A: ‘말하기’ 버튼을 누를 때만 듣기 + B: 앱이 물은 뒤에만 짧게 듣기).
 * Pleos SpeechToText SDK는 글자만 돌려주고 명령을 실행하지 않으므로, 뜻 풀이는 여기서 닫힌 문법으로 합니다.
 * 원칙: 모르면 UNKNOWN(아무것도 실행하지 않음) — 잘못 알아듣고 행동하는 것보다 한 번 더 묻는 쪽이 안전합니다.
 */
enum class VoiceIntent { REPEAT, STATUS, QUIET, YES, NO, UNKNOWN }

/** 듣기를 연 까닭 — BUTTON: 운전자가 ‘말하기’를 누름(A) · QUESTION: 앱이 물은 말이 끝난 뒤 짧게 들음(B) */
enum class ListenTrigger { BUTTON, QUESTION }

object VoiceCommands {
    /** 소문자 · 문장부호 없앰 · 공백 하나로 */
    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s']"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * 우선순위: QUIET > REPEAT > STATUS > NO > YES. 한 음절 대답(응·네·예)은 낱말이 그것일 때만 — ‘네비’ 같은 말에 걸리지 않게.
     * ‘괜찮아’는 쉬기 질문에 대한 대답으로 ‘아니(안 쉬어도 돼)’로 봅니다.
     */
    fun match(text: String, lang: Lang): VoiceIntent {
        val n = normalize(text)
        if (n.isEmpty()) return VoiceIntent.UNKNOWN
        val tokens = n.split(' ')
        val joined = n.replace(" ", "")
        // 1.3.0(C08): 한국어 설정에서는 한국어 문법만(‘what time is it’→REPEAT 같은 오수락 방지, 인식기도 ko-KR이라 영어 낱말은 거의 안 옴).
        // 영어 설정에서는 영어 먼저, 한국어도 봄(섞어 말해도)
        val order = if (lang == Lang.EN) listOf(::en, ::ko) else listOf(::ko)
        for (f in order) {
            val r = f(tokens, joined)
            if (r != VoiceIntent.UNKNOWN) return r
        }
        return VoiceIntent.UNKNOWN
    }

    private fun anyIn(joined: String, words: List<String>) = words.any { joined.contains(it) }

    private val KO_QUIET = listOf("조용", "그만해", "그만말", "말하지마", "말좀그만", "시끄러", "닥쳐")
    private val KO_REPEAT = listOf("다시", "뭐라고", "뭐라구", "한번더", "못들었", "안들려", "잘못들")
    private val KO_STATUS = listOf("약속알려", "약속말해", "약속어때", "약속어떻", "약속확인", "약속뭐", "약속은", "상태")   // ‘약속대로 쉴게’는 대답(YES)이므로 ‘약속’만으로는 안 봄
    /** 1.3.0(C08, 11/9 사전 목표 고정 전 반영): ‘약속’과 서술어 사이의 조사·부사 허용 — ‘약속을 알려 줘’·‘약속 좀 알려 줘’·‘약속 잘 지키고 있어’. ‘약속대로’·‘약속 시간’은 걸리지 않음 */
    private val KO_STATUS_RE = Regex("약속(을|를|은|는|이|가|좀|다|잘|들)*(알려|말해|어때|어떻|확인|뭐|지키|지켰)")
    private val KO_NO_TOKENS = setOf("아니", "아니요", "아니오", "아뇨", "아니야", "싫어", "됐어", "괜찮아", "괜찮아요")
    private val KO_NO = listOf("괜찮", "안쉬", "쉬지않", "안쉴", "나중에", "조금더", "좀더갈", "그냥갈", "있다가", "이따가")
    private val KO_YES_TOKENS = setOf("응", "네", "예", "그래", "그래요", "좋아", "좋아요", "알았어", "알겠어", "오케이", "넹", "웅", "네네", "응응")
    private val KO_YES = listOf("쉴게", "쉴께", "쉬어갈게", "쉬어갈께", "쉬자", "쉬어가자", "쉬어갈래", "알았어", "알겠어", "알겠습니다", "그럴게", "그렇게할게")

    private fun ko(tokens: List<String>, joined: String): VoiceIntent = when {
        anyIn(joined, KO_QUIET) -> VoiceIntent.QUIET
        anyIn(joined, KO_REPEAT) -> VoiceIntent.REPEAT
        anyIn(joined, KO_STATUS) || KO_STATUS_RE.containsMatchIn(joined) || tokens == listOf("약속") -> VoiceIntent.STATUS
        tokens.any { it in KO_NO_TOKENS } || anyIn(joined, KO_NO) -> VoiceIntent.NO
        tokens.any { it in KO_YES_TOKENS } || anyIn(joined, KO_YES) -> VoiceIntent.YES
        else -> VoiceIntent.UNKNOWN
    }

    private val EN_QUIET = listOf("quiet", "stoptalking", "shutup", "silence", "bequiet")
    private val EN_REPEAT = listOf("again", "repeat", "pardon", "saythat", "what")
    private val EN_STATUS = listOf("promise", "status", "howamidoing")
    private val EN_NO_TOKENS = setOf("no", "nope", "nah")
    private val EN_NO = listOf("notnow", "later", "imfine", "i'mfine", "noneed")
    private val EN_YES_TOKENS = setOf("yes", "yeah", "yep", "sure", "ok", "okay", "alright")
    private val EN_YES = listOf("let'srest", "letsrest", "iwill", "takeabreak")

    private fun en(tokens: List<String>, joined: String): VoiceIntent = when {
        anyIn(joined, EN_QUIET) -> VoiceIntent.QUIET
        anyIn(joined, EN_REPEAT) -> VoiceIntent.REPEAT
        anyIn(joined, EN_STATUS) -> VoiceIntent.STATUS
        tokens.any { it in EN_NO_TOKENS } || anyIn(joined, EN_NO) -> VoiceIntent.NO
        tokens.any { it in EN_YES_TOKENS } || anyIn(joined, EN_YES) -> VoiceIntent.YES
        else -> VoiceIntent.UNKNOWN
    }
}
