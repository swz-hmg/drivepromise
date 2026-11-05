package app.drivepromise.core.scene

import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.speech.Lang

/** 정차 후 요약·정차 화면 문장 — 약속 이름과 한 줄 요약 */
object SummaryPhrases {
    fun name(lang: Lang, p: Promise): String = when (p) {
        is Promise.HomeBeforeDark -> if (lang == Lang.KO) "해 지기 전 귀가" else "home before dark"
        is Promise.AvoidHighway -> if (lang == Lang.KO) "고속도로 대신" else "no highways"
        is Promise.RainCare -> if (lang == Lang.KO) "비 오는 날 조심" else "care in the rain"
        is Promise.RestEvery -> if (lang == Lang.KO) "${p.everyMin / 60}시간마다 쉬기" else "rest every ${p.everyMin / 60} hours"
        is Promise.StayInZone -> if (lang == Lang.KO) "약속 구역" else "stay in zone"
    }

    fun status(lang: Lang, s: PromiseStatus): String = when (s) {
        PromiseStatus.ON_TRACK -> if (lang == Lang.KO) "지키는 중" else "on track"
        PromiseStatus.AT_RISK -> if (lang == Lang.KO) "위험" else "at risk"
        PromiseStatus.KEPT -> if (lang == Lang.KO) "지킴" else "kept"
        PromiseStatus.MISSED -> if (lang == Lang.KO) "못 지킴" else "missed"
        PromiseStatus.NOT_APPLICABLE -> if (lang == Lang.KO) "해당 없음" else "n/a"
        PromiseStatus.UNKNOWN -> if (lang == Lang.KO) "모름" else "unknown"
    }

    /** 주행 끝 한 줄: “약속 4개 중 3개를 지켰어요.” — 판정이 KEPT/MISSED인 것만 셈 */
    fun kept(lang: Lang, promises: List<Promise>, statuses: Map<String, PromiseStatus>): String? {
        val judged = promises.filter { statuses[it.id] == PromiseStatus.KEPT || statuses[it.id] == PromiseStatus.MISSED }
        if (judged.isEmpty()) return null
        val kept = judged.count { statuses[it.id] == PromiseStatus.KEPT }
        val missed = judged.filter { statuses[it.id] == PromiseStatus.MISSED }
        return when (lang) {
            Lang.KO -> if (missed.isEmpty()) "오늘 약속 ${judged.size}개를 모두 지켰어요. 수고하셨어요."
            else "약속 ${judged.size}개 중 ${kept}개를 지켰어요. 다음엔 ${missed.joinToString("·") { name(lang, it) }}도 같이 지켜요."
            Lang.EN -> if (missed.isEmpty()) "All ${judged.size} promises kept today. Well done."
            else "$kept of ${judged.size} promises kept. Next time let's also keep: ${missed.joinToString(", ") { name(lang, it) }}."
        }
    }
}
