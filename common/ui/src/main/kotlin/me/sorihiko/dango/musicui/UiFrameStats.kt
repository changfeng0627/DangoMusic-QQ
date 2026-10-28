package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoLogger
import java.util.Locale

/** Bounded diagnostic for the preview: no timers, worker threads, account data, or input contents. */
internal class UiFrameStats {
    private val periods = LongArray(2048)
    private val renders = LongArray(2048)
    private val captures = LongArray(2048)
    private var key = ""
    private var previous = 0L
    private var windowStart = 0L
    private var count = 0
    private var reportsLeft = 6
    private var longest = 0L
    private var over50 = 0
    private var over200 = 0

    fun reset() { key = ""; previous = 0L; count = 0 }

    fun record(context: String, start: Long, captureEnd: Long, finish: Long) {
        if (reportsLeft == 0) return
        if (context != key || previous == 0L) {
            key = context; previous = start; windowStart = start; count = 0
            longest = 0L; over50 = 0; over200 = 0
            return
        }
        val period = start - previous
        previous = start
        val index = count++ % periods.size
        periods[index] = period
        renders[index] = finish - start
        captures[index] = captureEnd - start
        longest = maxOf(longest, period)
        if (period > 50_000_000L) over50++
        if (period > 200_000_000L) over200++
        if (finish - windowStart < 10_000_000_000L) return
        fun p95(values: LongArray): String {
            val sorted = values.copyOf(minOf(count, values.size)).apply { sort() }
            return String.format(Locale.ROOT, "%.2f", sorted[(sorted.size - 1) * 95 / 100] / 1e6)
        }
        DangoLogger.info("UI timing {}: frames={}, interval p95={}ms max={}ms >50ms={} >200ms={}, render p95={}ms, backdrop p95={}ms",
            key, count, p95(periods), String.format(Locale.ROOT, "%.2f", longest / 1e6), over50, over200,
            p95(renders), p95(captures))
        reportsLeft--
        windowStart = finish; count = 0; longest = 0L; over50 = 0; over200 = 0
    }
}
