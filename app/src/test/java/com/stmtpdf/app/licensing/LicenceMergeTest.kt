package com.stmtpdf.app.licensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM 单测：授权合并规则（重放不清零、续费顺延）。 */
class LicenceMergeTest {

    private fun state(
        plan: String,
        periods: List<Period>,
        order: String,
        issued: String
    ) = LicenceState(plan, periods.toMutableList(), order, issued)

    @Test
    fun sameOrderReplayedKeepsUsed() {
        val local = state(
            "t2_monthly",
            listOf(Period("2026-10-01", "2026-11-01", 1000, 300)),
            "order-A",
            "2026-10-01"
        )
        val incoming = state(
            "t2_monthly",
            listOf(Period("2026-10-01", "2026-11-01", 1000, 0)),
            "order-A",
            "2026-10-01"
        )

        val once = LicenceMerge.merge(local, incoming)
        val twice = LicenceMerge.merge(once, incoming)

        assertEquals(300, once.periods.single().used)
        assertEquals(300, twice.periods.single().used)
        assertEquals(700, twice.periods.single().remaining())
        assertEquals(1, twice.periods.size)
    }

    @Test
    fun sameOrderReplayRefreshesPagesAndExpiryButNotUsed() {
        val local = state(
            "t2_monthly",
            listOf(Period("2026-10-01", "2026-11-01", 1000, 420)),
            "order-A",
            "2026-10-01"
        )
        val incoming = state(
            "t2_monthly",
            listOf(Period("2026-10-01", "2026-11-08", 1200, 0)),
            "order-A",
            "2026-10-01"
        )

        val merged = LicenceMerge.merge(local, incoming)

        assertEquals(1, merged.periods.size)
        assertEquals(1200, merged.periods[0].pages)
        assertEquals("2026-11-08", merged.periods[0].end)
        assertEquals(420, merged.periods[0].used)
        assertEquals("order-A", merged.order)
    }

    @Test
    fun renewalKeepsOldPeriodAndStartsAtOldExpiry() {
        val local = state(
            "t2_monthly",
            listOf(Period("2026-10-01", "2026-11-01", 1000, 500)),
            "order-A",
            "2026-10-01"
        )
        val incoming = state(
            "t2_monthly",
            listOf(Period("2026-10-20", "2026-12-01", 1000, 0)),
            "order-B",
            "2026-10-20"
        )

        val merged = LicenceMerge.merge(local, incoming)

        assertEquals(2, merged.periods.size)
        // 旧周期完整保留，已用额度不丢
        assertEquals("2026-10-01", merged.periods[0].start)
        assertEquals("2026-11-01", merged.periods[0].end)
        assertEquals(500, merged.periods[0].used)
        // 新周期从旧到期日顺延
        assertEquals("2026-11-01", merged.periods[1].start)
        assertEquals("2026-12-01", merged.periods[1].end)
        assertEquals(1000, merged.periods[1].pages)
        assertEquals(0, merged.periods[1].used)
        assertEquals("2026-12-01", merged.expiry())
        assertEquals("order-B", merged.order)
    }

    @Test
    fun renewalAfterExpiryStartsAtIssuedDate() {
        val local = state(
            "t1_monthly",
            listOf(Period("2026-08-01", "2026-09-01", 400, 400)),
            "order-A",
            "2026-08-01"
        )
        val incoming = state(
            "t1_monthly",
            listOf(Period("2026-10-01", "2026-11-01", 400, 0)),
            "order-C",
            "2026-10-01"
        )

        val merged = LicenceMerge.merge(local, incoming)

        assertEquals(2, merged.periods.size)
        assertEquals("2026-10-01", merged.periods[1].start)
        assertEquals("2026-11-01", merged.periods[1].end)
    }

    @Test
    fun renewalUsesServerStackedExpiryWithoutDoubleAdding() {
        val local = state(
            "t1_monthly",
            listOf(Period("2026-10-01", "2026-11-01", 400, 120)),
            "order-A",
            "2026-10-01"
        )
        // 服务端已经给出叠加后的到期日
        val incoming = state(
            "t1_monthly",
            listOf(Period("2026-10-25", "2026-12-01", 400, 0)),
            "order-B",
            "2026-10-25"
        )

        val merged = LicenceMerge.merge(local, incoming)

        assertEquals("2026-11-01", merged.periods[1].start)
        assertEquals("2026-12-01", merged.periods[1].end)
        assertEquals("2026-12-01", merged.expiry())
    }

    @Test
    fun firstActivationAdoptsIncoming() {
        val incoming = state(
            "t3_annual",
            listOf(Period("2026-10-01", "2027-10-01", 48000, 0)),
            "order-X",
            "2026-10-01"
        )

        val merged = LicenceMerge.merge(null, incoming)

        assertEquals(1, merged.periods.size)
        assertEquals(48000, merged.periods[0].pages)
        assertEquals("order-X", merged.order)
        assertEquals("2026-10-01", merged.issued)
    }

    @Test
    fun historyCappedAtSixPeriods() {
        val periods = (0 until 6).map {
            val month = 1 + it
            Period("2026-0$month-01", "2026-0$month-28", 400, 100)
        }
        val local = state("t1_monthly", periods, "order-5", "2026-02-01")
        val incoming = state(
            "t1_monthly",
            listOf(Period("2026-11-01", "2026-12-01", 400, 0)),
            "order-6",
            "2026-11-01"
        )

        val merged = LicenceMerge.merge(local, incoming)

        assertEquals(LicenceMerge.MAX_PERIODS, merged.periods.size)
        // 丢掉最旧的一段，最新一段在末尾
        assertEquals("2026-02-01", merged.periods.first().start)
        assertEquals("2026-12-01", merged.periods.last().end)
        assertTrue(merged.periods.any { it.start == "2026-11-01" })
    }
}
