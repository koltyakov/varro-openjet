package varro.toolwindow

import org.junit.Assert.*
import org.junit.Test
import varro.server.OpenCodeVersionInfo

class OpenCodeStatusPresentationTest {
    private val now = 1_800_000_000_000L
    private fun info(cli: String? = "2.0.25", server: String? = "2.0.25", startedAt: Long? = null, attached: Boolean = false) =
        OpenCodeVersionInfo("http://127.0.0.1:4096", cli, server, startedAt, attached)

    private fun render(info: OpenCodeVersionInfo = info(), updates: Boolean = true) =
        OpenCodeStatusPresentation.from(info, "0.6.0", updates, now)

    @Test fun `shows CLI server port and plugin versions`() {
        assertEquals("OpenCode 2.0.25", render().text)
        assertEquals("OpenCode CLI: 2.0.25\nOpenCode Server: 2.0.25\nServer port: 4096\n\nVarro OpenJet: 0.6.0", render().tooltip)
    }

    @Test fun `marks older CLIs and reflects the auto update setting`() {
        val status = render(info(cli = "2.0.23", server = "2.0.23"), updates = false)
        assertEquals("OpenCode 2.0.23*", status.text)
        assertTrue(status.tooltip.contains("New CLI version: OpenCode 2.0.25 is not installed yet.\nAuto-updates are off."))
        assertTrue(render(info(cli = "2.0.23")).tooltip.contains("Auto-updates are on."))
        assertFalse(status.tooltip.contains("Verified w/"))
    }

    @Test fun `shows the running version and stale server warning after CLI upgrade`() {
        val status = render(info(cli = "2.0.26"))
        assertEquals("OpenCode 2.0.25*", status.text)
        assertTrue(status.tooltip.contains("CLI updated to OpenCode 2.0.26; server 2.0.25 is stale."))
        assertFalse(status.tooltip.contains("New CLI version:"))
    }

    @Test fun `uses the corresponding verified version for each API family`() {
        assertEquals("OpenCode 1.18.35", render(info(cli = "1.18.35", server = "1.18.35")).text)
        assertEquals("OpenCode 1.18.34*", render(info(cli = "1.18.34", server = "1.18.34")).text)
        assertTrue(render(info(cli = "1.16.0", server = "1.16.0")).tooltip.endsWith("Verified w/ OpenCode 1.18.35"))
        assertTrue(render(info(cli = "2.1.0", server = "2.1.0")).tooltip.endsWith("Verified w/ OpenCode 2.0.25"))
    }

    @Test fun `external connections hide local CLI versions and update markers`() {
        val status = render(info(cli = "1.0.0", attached = true))
        assertEquals("OpenCode 2.0.25", status.text)
        assertEquals("Server address: http://127.0.0.1:4096\nServer IP: 127.0.0.1\nOpenCode Server: 2.0.25\nServer port: 4096\n\nVarro OpenJet: 0.6.0", status.tooltip)
        assertFalse(status.tooltip.contains("CLI"))
        assertFalse(status.tooltip.contains("Auto-updates"))
    }

    @Test fun `handles unknown versions and default ports`() {
        assertEquals("OpenCode", render(info(cli = null, server = null)).text)
        assertTrue(render(info(cli = null, server = null)).tooltip.contains("OpenCode CLI: unknown\nOpenCode Server: unknown"))
        assertEquals("OpenCode 2.0.25", render(info(server = null)).text)
        assertTrue(render(info().copy(url = "https://example.com")).tooltip.contains("Server port: 443"))
        assertTrue(render(info().copy(url = "http://example.com")).tooltip.contains("Server port: 80"))
    }

    @Test fun `formats actual uptime using the two largest adjacent units`() {
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        val week = 7 * day
        val cases = listOf(
            0L to "less than a min", 59_999L to "less than a min", minute to "1 min",
            42 * minute to "42 min", hour to "1 hr", hour + minute to "1 hr 1 min",
            2 * hour + 35 * minute to "2 hrs 35 min", day to "1 day",
            day + hour + 45 * minute to "1 day 1 hr", 2 * day + 10 * hour to "2 days 10 hrs",
            week to "1 week", week + day + hour to "1 week 1 day", 2 * week + 3 * day to "2 weeks 3 days",
        )
        for ((duration, expected) in cases) {
            assertTrue(expected, render(info(startedAt = now - duration)).tooltip.contains("Server uptime: $expected\n"))
        }
        assertTrue(render(info(startedAt = now + minute)).tooltip.contains("Server uptime: less than a min"))
    }

    @Test fun `omits unavailable or invalid uptime`() {
        for (startedAt in listOf(null, 0L, -1L)) assertFalse(render(info(startedAt = startedAt)).tooltip.contains("Server uptime:"))
    }
}
