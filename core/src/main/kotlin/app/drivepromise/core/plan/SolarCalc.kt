package app.drivepromise.core.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * 일출·일몰 계산 — NOAA Solar Calculator 식(Meeus 기반). 외부 데이터 없음.
 * 정확도: 한국천문연구원 표와 1분 안(PyEphem·바다타임 표로 확인, 약속엔진 v0.1 문서 2장). 극지방(해가 안 뜨거나 안 지는 날)은 null.
 * zenith 90.833° = 일몰(태양 윗가장자리 + 대기 굴절), 96° = 시민 박명 끝(‘어두워짐’).
 */
object SolarCalc {
    const val ZENITH_SUNSET = 90.833
    const val ZENITH_CIVIL = 96.0

    data class SunTimes(val sunriseMs: Long?, val sunsetMs: Long?, val civilDawnMs: Long?, val civilDuskMs: Long?)

    /** 그 시각이 속한 ‘그 지역 날짜’의 일출·일몰 */
    fun at(epochMs: Long, lat: Double, lon: Double, zone: ZoneId): SunTimes {
        val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        return forDate(date, lat, lon, zone)
    }

    fun forDate(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): SunTimes {
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val noonUt = date.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
        val noonJd = julianDay(noonUt)
        fun event(zenith: Double, rising: Boolean): Long? {
            // 1차: 정오 기준, 2차: 그 결과 시각 기준으로 다시(분 단위 오차 → 초 단위)
            var ms = solve(noonJd, lat, lon, zenith, rising, midnight, zone) ?: return null
            ms = solve(julianDay(ms), lat, lon, zenith, rising, midnight, zone) ?: return null
            return ms
        }
        return SunTimes(event(ZENITH_SUNSET, true), event(ZENITH_SUNSET, false), event(ZENITH_CIVIL, true), event(ZENITH_CIVIL, false))
    }

    /** 일몰까지 남은 ms(지났으면 음수). 해가 안 지는 날은 null */
    fun untilSunsetMs(epochMs: Long, lat: Double, lon: Double, zone: ZoneId): Long? = at(epochMs, lat, lon, zone).sunsetMs?.let { it - epochMs }

    private fun julianDay(epochMs: Long): Double = epochMs / 86_400_000.0 + 2_440_587.5

    private fun solve(jd: Double, lat: Double, lon: Double, zenith: Double, rising: Boolean, localMidnightMs: Long, zone: ZoneId): Long? {
        val jc = (jd - 2_451_545.0) / 36_525.0
        val l0 = norm360(280.46646 + jc * (36_000.76983 + jc * 0.0003032))
        val m = 357.52911 + jc * (35_999.05029 - 0.0001537 * jc)
        val e = 0.016708634 - jc * (0.000042037 + 0.0000001267 * jc)
        val c = sin(rad(m)) * (1.914602 - jc * (0.004817 + 0.000014 * jc)) + sin(rad(2 * m)) * (0.019993 - 0.000101 * jc) + sin(rad(3 * m)) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1_934.136 * jc
        val appLong = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val obliq0 = 23.0 + (26.0 + (21.448 - jc * (46.815 + jc * (0.00059 - jc * 0.001813))) / 60.0) / 60.0
        val obliq = obliq0 + 0.00256 * cos(rad(omega))
        val decl = deg(asin(sin(rad(obliq)) * sin(rad(appLong))))
        val y = tan(rad(obliq / 2)).let { it * it }
        val eqTime = 4 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m)),
        )
        val cosHa = (cos(rad(zenith)) - sin(rad(lat)) * sin(rad(decl))) / (cos(rad(lat)) * cos(rad(decl)))
        if (cosHa < -1 || cosHa > 1) return null
        val ha = deg(acos(cosHa))
        // 지역 시각(분, 자정 기준): 태양 정오 = 720 − 4·경도 − 균시차 + 시간대 보정
        val tzMin = zone.rules.getOffset(Instant.ofEpochMilli(localMidnightMs + 12 * 3_600_000L)).totalSeconds / 60.0
        val noonMin = 720.0 - 4.0 * lon - eqTime + tzMin
        val minutes = if (rising) noonMin - ha * 4 else noonMin + ha * 4
        return localMidnightMs + Math.round(minutes * 60_000.0)
    }

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI
    private fun norm360(d: Double) = d - 360.0 * floor(d / 360.0)
}
