package app.drivepromise.core.scene

import app.drivepromise.core.speech.Lang
import java.time.Instant
import java.time.ZoneId

/**
 * 약속 문장(PromiseComposer) — 회전 문장은 없음(순정 내비 몫). 4초 안쪽, 스스로 정한 약속을 같이 지키는 말투.
 * 시각은 ‘오후 5시 22분’처럼 읽고, 분이 0이면 ‘5시’.
 */
object PromisePhrases {
    fun koClock(epochMs: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
        val h = t.hour
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = when (val x = h % 12) { 0 -> 12; else -> x }
        return if (t.minute == 0) "$ampm ${h12}시" else "$ampm ${h12}시 ${t.minute}분"
    }

    fun enClock(epochMs: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
        val h = t.hour
        val h12 = when (val x = h % 12) { 0 -> 12; else -> x }
        return "%d:%02d %s".format(h12, t.minute, if (h < 12) "a.m." else "p.m.")
    }

    // ---- 해 지기 전 귀가
    fun darkRisk(lang: Lang, sunsetMs: Long, arrivalMs: Long, zone: ZoneId, short: Boolean): String = when (lang) {
        Lang.KO -> if (short) "해 진 뒤 도착이에요. 쉬지 말고 가요." else "이대로면 ${koClock(arrivalMs, zone)} 도착, 해는 ${koClock(sunsetMs, zone)}에 져요. 쉬지 말고 바로 가요."
        Lang.EN -> if (short) "Arriving after dark. Keep going." else "At this pace you arrive ${enClock(arrivalMs, zone)} and the sun sets ${enClock(sunsetMs, zone)} — best not to stop."
    }

    fun darkNow(lang: Lang, short: Boolean): String = when (lang) {
        Lang.KO -> if (short) "해가 졌어요. 천천히 가요." else "해가 졌어요. 약속보다 늦었지만, 전조등 켜고 천천히 가요."
        Lang.EN -> if (short) "It's dark now. Take it slow." else "The sun has set. Later than promised — lights on, take it slow."
    }

    fun darkKept(lang: Lang): String = when (lang) {
        Lang.KO -> "해 지기 전에 도착했어요. 약속 지키셨습니다."
        Lang.EN -> "Home before dark — promise kept."
    }

    // ---- 고속도로 대신
    fun highwayEntered(lang: Lang, short: Boolean): String = when (lang) {
        Lang.KO -> if (short) "고속도로예요. 약속과 달라요." else "고속도로에 들어섰어요. 약속과 달라요. 다음 나들목에서 내려갈지 정해요."
        Lang.EN -> if (short) "This is a highway — not as promised." else "You've entered a highway, unlike your promise. Decide at the next exit."
    }

    // ---- 비 오는 날
    fun rainStart(lang: Lang, short: Boolean): String = when (lang) {
        Lang.KO -> if (short) "비가 와요. 천천히 가요." else "비가 와요. 약속대로 평소보다 천천히, 앞차와 거리를 두세요."
        Lang.EN -> if (short) "It's raining. Slow down." else "It's raining. As promised, slower than usual and keep your distance."
    }

    fun rainFast(lang: Lang, speedKph: Int, targetKph: Int, short: Boolean): String = when (lang) {
        Lang.KO -> if (short) "빗길이에요. ${targetKph}까지 줄여요." else "빗길에 ${speedKph}예요. ${targetKph} 아래로 줄여요."
        Lang.EN -> if (short) "Wet road — slow to $targetKph." else "$speedKph on a wet road. Ease down below $targetKph."
    }

    // ---- 약속 구역
    fun zoneEdge(lang: Lang, short: Boolean): String = when (lang) {
        Lang.KO -> if (short) "약속 구역 끝이에요." else "약속 구역 끝이 가까워요. 이 길로 가면 구역을 벗어나요."
        Lang.EN -> if (short) "Edge of your zone." else "You're near the edge of your zone. This road leads out of it."
    }

    fun zoneOut(lang: Lang, short: Boolean): String = when (lang) {
        Lang.KO -> if (short) "구역 밖이에요." else "약속 구역 밖이에요. 안전한 곳에서 돌아가요."
        Lang.EN -> if (short) "Outside your zone." else "You're outside your zone. Turn back when it's safe."
    }

    fun zoneBack(lang: Lang): String = when (lang) {
        Lang.KO -> "약속 구역으로 돌아왔어요."
        Lang.EN -> "Back inside your zone."
    }
}
