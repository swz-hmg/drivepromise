package app.drivepromise.core

import app.drivepromise.core.speech.Lang
import app.drivepromise.core.voice.Grammar
import app.drivepromise.core.voice.VoiceCommands
import app.drivepromise.core.voice.VoiceGrammar
import app.drivepromise.core.voice.VoiceIntent
import app.drivepromise.core.voice.VoiceIntent.NO
import app.drivepromise.core.voice.VoiceIntent.QUIET
import app.drivepromise.core.voice.VoiceIntent.REPEAT
import app.drivepromise.core.voice.VoiceIntent.STATUS
import app.drivepromise.core.voice.VoiceIntent.UNKNOWN
import app.drivepromise.core.voice.VoiceIntent.YES

/**
 * 1.3.4 — G2 사전 등록 C18 후보의 Kotlin 이식이 사전 등록 채점 도구(rescore.py, sha256 fb94ca63…)와 같은지.
 * 기대값은 rescore.py의 match(text, v130/c18a/c18b)로 만든 163줄입니다(11/9, G2 전).
 * 문장은 카드·프로브 문장과 합성음 리허설 인식 글자뿐입니다 — 사람 녹음·인식 원문은 넣지 않습니다.
 */
object C18Specs {
    val all: List<Pair<String, () -> Unit>> = listOf(
        "S125 문법 기본값은 V130 — VoiceGrammar.match = VoiceCommands.match(대조 163줄 · 영어 설정 포함)(1.3.4, G2 전)" to ::s125,
        "S126 C18 후보(c18a·c18b)가 rescore.py와 같은 뜻 — 163줄 모두 · 복사한 YES·NO 낱말 목록이 VoiceCommands와 같음 · 영어 설정은 바뀌지 않음(1.3.4, G2 전)" to ::s126,
    )
}

private class P(val text: String, val v130: VoiceIntent, val c18a: VoiceIntent, val c18b: VoiceIntent)

private val C18_PARITY = listOf(
        P("아니 그냥 갈래", NO, NO, NO),
        P("아니 벤츠 한나", NO, NO, NO),
        P("아니 왠지 않나", NO, NO, NO),
        P("나중에 길게", NO, NO, NO),
        P("나중에 지역에", NO, NO, NO),
        P("아니요", NO, NO, NO),
        P("오늘 날씨 좋네", UNKNOWN, UNKNOWN, UNKNOWN),
        P("응급실은 어디야?", UNKNOWN, UNKNOWN, UNKNOWN),
        P("응급실은 어디야", UNKNOWN, UNKNOWN, UNKNOWN),
        P("약속시간에 늦겠다", UNKNOWN, UNKNOWN, UNKNOWN),
        P("약속 시간에 늦겠다", UNKNOWN, UNKNOWN, UNKNOWN),
        P("라디오 좀 틀어줘", UNKNOWN, UNKNOWN, UNKNOWN),
        P("저희 언니에", UNKNOWN, UNKNOWN, UNKNOWN),
        P("저희 요일에", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그만 말해", QUIET, QUIET, QUIET),
        P("잠을 위해서", UNKNOWN, UNKNOWN, UNKNOWN),
        P("잠깐 조용히 우리 뭐", QUIET, QUIET, QUIET),
        P("저 저희 어머니에", UNKNOWN, UNKNOWN, UNKNOWN),
        P("좀 저희 언니의 조례", UNKNOWN, UNKNOWN, UNKNOWN),
        P("다시 들려주어", REPEAT, REPEAT, REPEAT),
        P("뭐라도", UNKNOWN, UNKNOWN, UNKNOWN),
        P("뭐라고", REPEAT, REPEAT, REPEAT),
        P("한번 더 말해줘", REPEAT, REPEAT, REPEAT),
        P("한번 더 말해주고", REPEAT, REPEAT, REPEAT),
        P("뭐라고 해서", REPEAT, REPEAT, REPEAT),
        P("약속 알려줘", STATUS, STATUS, STATUS),
        P("약속 알려 줘", STATUS, STATUS, STATUS),
        P("내 약속을 어때", STATUS, STATUS, STATUS),
        P("내 약속 어때", STATUS, STATUS, STATUS),
        P("약속을 확인해 줘", STATUS, STATUS, STATUS),
        P("약속인데 좋아", YES, UNKNOWN, YES),
        P("내 약속 잘 지키고 있어", STATUS, STATUS, STATUS),
        P("그래 쉬자", YES, YES, YES),
        P("응 이렇게", YES, YES, YES),
        P("응 그러게요", YES, YES, YES),
        P("그래 쉬어가자", YES, YES, YES),
        P("그래 기억하자", YES, YES, YES),
        P("네", YES, YES, YES),
        P("아니 그냥 아니에", NO, NO, NO),
        P("아니 그냥 뼈에", NO, NO, NO),
        P("아니 프렌치 하나", NO, NO, NO),
        P("아니 코멘트 하나", NO, NO, NO),
        P("나중에 수익에", NO, NO, NO),
        P("오늘 날씨 전에", UNKNOWN, UNKNOWN, UNKNOWN),
        P("오늘 날씨 좀", UNKNOWN, UNKNOWN, UNKNOWN),
        P("응급실은 서비아", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그냥 수업 시간에 늦게나", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그냥 수업 시간에 늦게 자", UNKNOWN, UNKNOWN, UNKNOWN),
        P("싸이어 좀 슬로우스와", UNKNOWN, UNKNOWN, UNKNOWN),
        P("서영이에", UNKNOWN, UNKNOWN, UNKNOWN),
        P("손미래", UNKNOWN, UNKNOWN, UNKNOWN),
        P("서울 안 조용히 해서", QUIET, QUIET, QUIET),
        P("첨단 소년기에서", UNKNOWN, UNKNOWN, UNKNOWN),
        P("저 저 여기에 소액", UNKNOWN, UNKNOWN, UNKNOWN),
        P("저 저는 미래 소재", UNKNOWN, UNKNOWN, UNKNOWN),
        P("다시 이야기가", REPEAT, REPEAT, REPEAT),
        P("다시 또 해야지만", REPEAT, REPEAT, REPEAT),
        P("아무래도", UNKNOWN, UNKNOWN, UNKNOWN),
        P("한 번 더 말해줘", REPEAT, REPEAT, REPEAT),
        P("선물하고 했어", UNKNOWN, UNKNOWN, UNKNOWN),
        P("친구라도 했어", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그냥 기억이 아니었어", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그냥 기억이 안녕하세요", UNKNOWN, UNKNOWN, UNKNOWN),
        P("내 약속에", UNKNOWN, UNKNOWN, UNKNOWN),
        P("내 약속 안에", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그냥 숏 할인해 줘", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그냥 속말인데 뭐", UNKNOWN, UNKNOWN, UNKNOWN),
        P("내 약속이 아니고 있어", UNKNOWN, UNKNOWN, UNKNOWN),
        P("내 약속하기 위기도 있어", UNKNOWN, UNKNOWN, UNKNOWN),
        P("근데 심지어", UNKNOWN, UNKNOWN, UNKNOWN),
        P("좀 힘들게", UNKNOWN, UNKNOWN, UNKNOWN),
        P("길게 여", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그래 수요일까지", YES, YES, YES),
        P("근데 수요일까지 않", UNKNOWN, UNKNOWN, UNKNOWN),
        P("아니 그냥 가게", NO, NO, NO),
        P("아니 그러니까 나", NO, NO, NO),
        P("아니 포인트 하나", NO, NO, NO),
        P("나중에 수능에", NO, NO, NO),
        P("조용히", QUIET, QUIET, QUIET),
        P("그러면 저희 언니 계속", UNKNOWN, UNKNOWN, UNKNOWN),
        P("저는 조용히 해서", QUIET, QUIET, QUIET),
        P("저 저희 언니는 소리를", UNKNOWN, UNKNOWN, UNKNOWN),
        P("좀 조용히 이제 조용히", QUIET, QUIET, QUIET),
        P("사실 이거", UNKNOWN, UNKNOWN, UNKNOWN),
        P("한 번도 말해줘", UNKNOWN, UNKNOWN, UNKNOWN),
        P("뭐라고 했어?", REPEAT, REPEAT, REPEAT),
        P("뭐라도 해서", UNKNOWN, UNKNOWN, UNKNOWN),
        P("약속이 안녕하세요", UNKNOWN, UNKNOWN, UNKNOWN),
        P("약속 다녀서", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그래 약속에", YES, UNKNOWN, YES),
        P("내가 기존에", UNKNOWN, UNKNOWN, UNKNOWN),
        P("약속을 구하기 위해서", UNKNOWN, UNKNOWN, UNKNOWN),
        P("약속 구하기 위해서", UNKNOWN, UNKNOWN, UNKNOWN),
        P("내 약속시간이 필요 있어", UNKNOWN, UNKNOWN, UNKNOWN),
        P("내 기억하기 귀도 있어?", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그래 시작", YES, YES, YES),
        P("실제로", UNKNOWN, UNKNOWN, UNKNOWN),
        P("응 실제로", YES, YES, YES),
        P("그래 수요일 아니야", NO, NO, NO),
        P("그래 10월까지", YES, YES, YES),
        P("근데", UNKNOWN, UNKNOWN, UNKNOWN),
        P("약속을 알려 줘", STATUS, STATUS, STATUS),
        P("약속 좀 알려 줘", STATUS, STATUS, STATUS),
        P("약속 잘 지키고 있어", STATUS, STATUS, STATUS),
        P("오늘 약속 다 지켰어", STATUS, STATUS, STATUS),
        P("약속 확인해 줘", STATUS, STATUS, STATUS),
        P("네 야속 어때", YES, YES, UNKNOWN),
        P("응", YES, YES, YES),
        P("응 쉴게", YES, YES, YES),
        P("그래 쉬어 가자", YES, YES, YES),
        P("약속대로 쉴게", YES, YES, YES),
        P("아니", NO, NO, NO),
        P("괜찮아", NO, NO, NO),
        P("지금 괜찮아요", NO, NO, NO),
        P("나중에 쉴게", NO, NO, NO),
        P("안 쉴래", NO, NO, NO),
        P("다시 말해 줘", REPEAT, REPEAT, REPEAT),
        P("조용히 해", QUIET, QUIET, QUIET),
        P("그만", UNKNOWN, UNKNOWN, UNKNOWN),
        P("what time is it", UNKNOWN, UNKNOWN, UNKNOWN),
        P("오늘 날씨 좋다", UNKNOWN, UNKNOWN, UNKNOWN),
        P("어 저기 차 온다", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그래 그만 좀 말해", YES, YES, UNKNOWN),
        P("지금 뭐 하는 거야", UNKNOWN, UNKNOWN, UNKNOWN),
        P("좋아 다시 말해 봐", REPEAT, REPEAT, REPEAT),
        P("What time is it?", UNKNOWN, UNKNOWN, UNKNOWN),
        P("OK, let's rest", UNKNOWN, UNKNOWN, UNKNOWN),
        P("약속!!! 알려줘~", STATUS, STATUS, STATUS),
        P("  약속 은 어때 ", STATUS, STATUS, STATUS),
        P("약속", STATUS, STATUS, STATUS),
        P("상태 알려", STATUS, STATUS, STATUS),
        P("쉬지 않을래", NO, NO, NO),
        P("no thanks", UNKNOWN, UNKNOWN, UNKNOWN),
        P("Promise?", UNKNOWN, UNKNOWN, UNKNOWN),
        P("응응", YES, YES, YES),
        P("네비 켜 줘", UNKNOWN, UNKNOWN, UNKNOWN),
        P("약속 들 알려", STATUS, STATUS, STATUS),
        P("약속을을 알려줘", STATUS, STATUS, STATUS),
        P("I'm fine", UNKNOWN, UNKNOWN, UNKNOWN),
        P("그래, 약속대로 할게", YES, UNKNOWN, UNKNOWN),
        P("아니, 약속 시간 늦겠다", NO, UNKNOWN, NO),
        P("좋아 약속 지키자", STATUS, STATUS, STATUS),
        P("그래 약속", YES, UNKNOWN, YES),
        P("좋아 약속 지킬게", YES, UNKNOWN, UNKNOWN),
        P("네 네 알겠어", YES, YES, YES),
        P("그래요 쉬어 갈래요", YES, YES, YES),
        P("아니 약속 말고", NO, UNKNOWN, NO),
        P("응 그래 좋아", YES, YES, UNKNOWN),
        P("네 그럼 그렇게 해요", YES, YES, UNKNOWN),
        P("다시 들려줘", REPEAT, REPEAT, REPEAT),
        P("쉬어 가요", UNKNOWN, UNKNOWN, UNKNOWN),
        P("약속은 지켜야지 쉬어 갈게", STATUS, STATUS, STATUS),
        P("오케이 고마워 진짜", YES, YES, UNKNOWN),
        P("아니요 괜찮아요", NO, NO, NO),
        P("그냥 갈래", NO, NO, NO),
        P("약속 좋아", YES, UNKNOWN, YES),
        P("네 약속", YES, UNKNOWN, YES),
        P("약속 시간 늦어도 그냥 갈래", NO, NO, NO),
        P("약속 있어서 나중에 쉴게", NO, NO, NO),
        P("응 약속대로 쉬어 갈게", YES, YES, YES),
        P("아니요 괜찮아요 약속 지키는 중", STATUS, STATUS, STATUS),
        P("네 알겠어 약속", YES, YES, YES),
        P("응 그래 그래", YES, YES, UNKNOWN),
)

private fun s125() {
    eq(VoiceGrammar.ACTIVE, Grammar.V130, "기본 문법(G2 판정 전)")
    var bad = 0
    for (p in C18_PARITY) for (l in Lang.values()) {
        if (VoiceGrammar.match(p.text, l) != VoiceCommands.match(p.text, l)) bad++
    }
    eq(bad, 0, "V130 = VoiceCommands")
    for (p in C18_PARITY) eq(VoiceCommands.match(p.text, Lang.KO), p.v130, "rescore v130: ${p.text}")
}

private fun s126() {
    for (p in C18_PARITY) {
        eq(VoiceGrammar.match(p.text, Lang.KO, Grammar.C18A), p.c18a, "c18a: ${p.text}")
        eq(VoiceGrammar.match(p.text, Lang.KO, Grammar.C18B), p.c18b, "c18b: ${p.text}")
    }
    ok(C18_PARITY.count { it.c18a != it.v130 } > 0 && C18_PARITY.count { it.c18b != it.v130 } > 0, "대조표에 후보가 바꾸는 줄이 있음")
    for (name in listOf("KO_YES", "KO_NO")) {
        val f = VoiceCommands::class.java.getDeclaredField(name).apply { isAccessible = true }
        val g = VoiceGrammar::class.java.getDeclaredField(name).apply { isAccessible = true }
        eq(g.get(VoiceGrammar), f.get(VoiceCommands), "낱말 목록 $name")
    }
    for (p in C18_PARITY) for (g in Grammar.values())
        eq(VoiceGrammar.match(p.text, Lang.EN, g), VoiceCommands.match(p.text, Lang.EN), "영어 설정 ${g}: ${p.text}")
}
