package app.drivepromise.replay

import android.content.Context
import app.drivepromise.core.replay.ReplayParser
import app.drivepromise.core.replay.ReplayTrip

/**
 * 리플레이 트립 파일(assets/trips/ 아래 .json, 스키마 0.2 — 원본은 demo/trips/) — 시연 장치의 자료. 이름(name)은 파일 이름(확장자 없이).
 * 트립의 날짜·시각(meta.start)이 세션 시계가 되므로 ‘해 지기 전 귀가’는 파일의 날짜 기준 일몰로 판정됩니다.
 */
object ReplayAssets {
    const val DIR = "trips"

    data class Entry(val name: String, val trip: ReplayTrip)

    fun list(ctx: Context): List<String> =
        (ctx.assets.list(DIR) ?: emptyArray()).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()

    fun load(ctx: Context, name: String): ReplayTrip {
        val json = ctx.assets.open("$DIR/$name.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return ReplayParser.parse(json)
    }

    fun loadAll(ctx: Context): List<Entry> = list(ctx).mapNotNull { n -> runCatching { Entry(n, load(ctx, n)) }.getOrNull() }
}
