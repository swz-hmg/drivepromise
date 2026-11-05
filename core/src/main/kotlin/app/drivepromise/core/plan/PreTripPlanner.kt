package app.drivepromise.core.plan

import app.drivepromise.core.model.Promise
import app.drivepromise.core.model.PromiseStatus
import app.drivepromise.core.scene.PromisePhrases
import app.drivepromise.core.source.RouteOption
import app.drivepromise.core.source.RoutePlan
import app.drivepromise.core.source.RoutePlans
import app.drivepromise.core.speech.Lang
import java.time.ZoneId

/**
 * 출발 전 계획 — 경로 후보(RouteSource.planRoutes 결과)를 약속에 비추어 고르고, 출발 전 화면의 카드 문장을 만듭니다.
 * 순수 함수: 같은 입력 → 같은 계획. 일몰은 SolarCalc(출발지 좌표·출발 시각 기준).
 */
object PreTripPlanner {
    data class Input(
        /** 계획을 세우는 지금 */
        val nowMs: Long,
        val originLat: Double,
        val originLon: Double,
        val plans: RoutePlans,
        val promises: List<Promise>,
        val zone: ZoneId = ZoneId.of("Asia/Seoul"),
        val lang: Lang = Lang.KO,
        /** 출발 예정 시각(기본: 지금) — 아침에 오후 출발을 계획할 때 */
        val departAtMs: Long = nowMs,
    )

    /** 경로 후보 하나의 판정 */
    data class Candidate(
        val option: RouteOption,
        val plan: RoutePlan,
        val arrivalMs: Long,
        val darkStatus: PromiseStatus,
        val highwayStatus: PromiseStatus,
        val needsRest: Boolean,
        val violations: Int,
    )

    data class Plan(
        val choice: RouteOption?,
        val candidates: List<Candidate>,
        val sunsetMs: Long?,
        /** 이 시각까지 출발해야 해 지기 전(여유 포함) 도착 — 가장 빠른 경로 기준. 이미 지났으면 과거 시각 */
        val departByMs: Long?,
        val lines: List<String>,
        val error: String? = null,
    )

    fun plan(i: Input): Plan {
        val sun = SolarCalc.at(i.departAtMs, i.originLat, i.originLon, i.zone)
        val sunset = sun.sunsetMs
        val dark = i.promises.filterIsInstance<Promise.HomeBeforeDark>().firstOrNull()
        val avoidHw = i.promises.any { it is Promise.AvoidHighway }
        val rest = i.promises.filterIsInstance<Promise.RestEvery>().firstOrNull()
        val deadline = if (dark != null && sunset != null) sunset - dark.marginMin * 60_000L else null
        if (i.plans.plans.isEmpty()) {
            return Plan(null, emptyList(), sunset, null, listOf(noRoute(i.lang, i.plans.error)), i.plans.error ?: "NO_ROUTE")
        }
        val cands = i.plans.plans.values.sortedBy { it.durationSec }.map { p ->
            val arr = i.departAtMs + p.durationSec * 1_000L
            val ds = when {
                dark == null -> PromiseStatus.NOT_APPLICABLE
                deadline == null -> PromiseStatus.UNKNOWN
                arr <= deadline -> PromiseStatus.ON_TRACK
                sunset != null && arr <= sunset -> PromiseStatus.AT_RISK
                else -> PromiseStatus.MISSED
            }
            val hs = when {
                !avoidHw -> PromiseStatus.NOT_APPLICABLE
                p.highwayM == null -> PromiseStatus.UNKNOWN
                p.highwayM > 0 -> PromiseStatus.MISSED
                else -> PromiseStatus.ON_TRACK
            }
            val nr = rest != null && p.durationSec >= rest.everyMin * 60L
            val v = (if (ds == PromiseStatus.MISSED || ds == PromiseStatus.AT_RISK) 1 else 0) + (if (hs == PromiseStatus.MISSED) 1 else 0)
            Candidate(p.option, p, arr, ds, hs, nr, v)
        }
        // 선택: 위반 0 중 가장 빠름 → 없으면 ‘해 지기 전’을 먼저 지키는 것 → 그래도 없으면 가장 빠른 것
        val choice = cands.filter { it.violations == 0 }.minByOrNull { it.plan.durationSec }
            ?: cands.filter { it.darkStatus != PromiseStatus.MISSED }.minByOrNull { it.plan.durationSec }
            ?: cands.first()
        val fastest = cands.minByOrNull { it.plan.durationSec }!!
        val departBy = deadline?.let { it - fastest.plan.durationSec * 1_000L }
        val lines = mutableListOf<String>()
        lines += chooseLine(i.lang, choice)
        if (dark != null) {
            if (sunset == null) lines += if (i.lang == Lang.KO) "오늘은 해가 지지 않는 곳이에요." else "The sun does not set here today."
            else when (choice.darkStatus) {
                PromiseStatus.ON_TRACK -> lines += darkOk(i.lang, choice.arrivalMs, sunset, i.zone)
                PromiseStatus.AT_RISK -> lines += darkTight(i.lang, choice.arrivalMs, sunset, i.zone)
                else -> lines += darkLate(i.lang, i.departAtMs, choice.arrivalMs, sunset, departBy!!, i.nowMs, i.zone)
            }
        }
        if (avoidHw) when (choice.highwayStatus) {
            PromiseStatus.MISSED -> lines += if (i.lang == Lang.KO) "이 경로엔 고속도로 ${fmtKm(choice.plan.highwayM ?: 0)}가 있어요." else "This route includes ${fmtKm(choice.plan.highwayM ?: 0)} of highway."
            PromiseStatus.UNKNOWN -> lines += if (i.lang == Lang.KO) "고속도로가 있는지는 알 수 없어요." else "Highway use is unknown for this route."
            else -> lines += if (i.lang == Lang.KO) "고속도로 없이 가요." else "No highway on this route."
        }
        if (choice.needsRest) lines += if (i.lang == Lang.KO) "${rest!!.everyMin / 60}시간 넘게 걸려요. 중간에 한 번 쉬어 가요." else "Over ${rest!!.everyMin / 60} hours — plan one rest stop."
        return Plan(choice.option, cands, sunset, departBy, lines)
    }

    private fun fmtKm(m: Int) = if (m >= 1000) "%.1f km".format(m / 1000.0) else "$m m"

    private fun optName(lang: Lang, o: RouteOption) = when (o) {
        RouteOption.RECOMMENDED -> if (lang == Lang.KO) "추천 경로" else "recommended route"
        RouteOption.FREE -> if (lang == Lang.KO) "무료 도로" else "toll-free route"
        RouteOption.COMFORTABLE -> if (lang == Lang.KO) "편한 길" else "comfortable route"
        RouteOption.SHORTEST -> if (lang == Lang.KO) "가장 짧은 길" else "shortest route"
        RouteOption.OTHER -> if (lang == Lang.KO) "다른 경로" else "other route"
    }

    private fun chooseLine(lang: Lang, c: Candidate): String {
        val min = c.plan.durationSec / 60
        return if (lang == Lang.KO) "${optName(lang, c.option)}로 가요. ${min}분 걸려요." else "Take the ${optName(lang, c.option)} — about $min minutes."
    }

    private fun darkOk(lang: Lang, arr: Long, ss: Long, z: ZoneId) =
        if (lang == Lang.KO) "${PromisePhrases.koClock(arr, z)} 도착, 해는 ${PromisePhrases.koClock(ss, z)}에 져요. 약속대로 해 지기 전이에요."
        else "Arrive ${PromisePhrases.enClock(arr, z)}; sunset ${PromisePhrases.enClock(ss, z)}. Before dark, as promised."

    private fun darkTight(lang: Lang, arr: Long, ss: Long, z: ZoneId) =
        if (lang == Lang.KO) "${PromisePhrases.koClock(arr, z)} 도착이면 해 지기 직전이에요(일몰 ${PromisePhrases.koClock(ss, z)}). 바로 출발해요."
        else "Arriving ${PromisePhrases.enClock(arr, z)} is just before sunset (${PromisePhrases.enClock(ss, z)}). Leave now."

    private fun darkLate(lang: Lang, departAt: Long, arr: Long, ss: Long, departBy: Long, now: Long, z: ZoneId) =
        if (departBy > now) {
            if (lang == Lang.KO) "${PromisePhrases.koClock(departAt, z)} 출발이면 ${PromisePhrases.koClock(arr, z)} 도착, 해 진 뒤예요. 약속을 지키려면 ${PromisePhrases.koClock(departBy, z)}까지 출발해야 해요."
            else "Leaving at ${PromisePhrases.enClock(departAt, z)} arrives ${PromisePhrases.enClock(arr, z)}, after dark. To keep the promise, leave by ${PromisePhrases.enClock(departBy, z)} at the latest."
        } else {
            if (lang == Lang.KO) "지금 가도 ${PromisePhrases.koClock(arr, z)} 도착, 해 진 뒤예요(일몰 ${PromisePhrases.koClock(ss, z)}). 해 지기 전 도착은 어려워요 — 다른 날이나 동승을 생각해요."
            else "Even now you arrive ${PromisePhrases.enClock(arr, z)}, after sunset (${PromisePhrases.enClock(ss, z)}). Consider another day or a companion."
        }

    private fun noRoute(lang: Lang, err: String?) =
        if (lang == Lang.KO) "경로를 받지 못했어요${err?.let { "($it)" } ?: ""}. 인터넷을 확인하고 다시 시도해요."
        else "No route received${err?.let { " ($it)" } ?: ""}. Check the connection and try again."
}
