package varro.host

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Color
import java.util.Locale

class ThemeBridgeTest {
    @Test
    fun `window themes reverse relative to the current IDE kind and preserve contrast mode`() {
        val pairs = mapOf(
            WebviewThemeKind.DARK to WebviewThemeKind.LIGHT,
            WebviewThemeKind.LIGHT to WebviewThemeKind.DARK,
            WebviewThemeKind.HIGH_CONTRAST to WebviewThemeKind.HIGH_CONTRAST_LIGHT,
            WebviewThemeKind.HIGH_CONTRAST_LIGHT to WebviewThemeKind.HIGH_CONTRAST,
        )
        for ((source, counterpart) in pairs) {
            for (reversed in listOf(false, true)) {
                val payload = ThemeBridge.windowChatTheme(source, reversed)
                assertEquals(reversed, payload.get("reversed").asBoolean)
                assertEquals(counterpart.id, payload.getAsJsonObject("counterpart").get("kind").asString)
                assertEquals(0, payload.getAsJsonObject("counterpart").getAsJsonObject("colors").size())
            }
        }
    }

    @Test
    fun `dark focus borders do not hide selected text on dark surfaces`() {
        val accent = Color(0x589DF6)
        assertEquals(accent, ThemeBridge.readableAccent(
            listOf(Color(0x181818), accent),
            listOf(Color(0x181818), Color(0x303134)),
        ))
    }

    @Test
    fun `readable theme accents are preserved on light surfaces`() {
        val accent = Color(0x2470B3)
        assertEquals(accent, ThemeBridge.readableAccent(
            listOf(accent, Color.BLACK),
            listOf(Color.WHITE, Color(0xF3F3F3)),
        ))
    }

    @Test
    fun `transparent focus rings are evaluated over the actual background`() {
        assertEquals(Color.WHITE, ThemeBridge.readableAccent(
            listOf(Color(255, 255, 255, 16), Color.WHITE),
            listOf(Color(0x181818)),
        ))
    }

    @Test
    fun `unreadable custom accents fall back to a contrasting color`() {
        assertEquals(Color.WHITE, ThemeBridge.readableAccent(
            listOf(Color.BLACK), listOf(Color.BLACK),
        ))
    }

    @Test
    fun `alpha colors remain valid CSS in decimal comma locales`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("rgba(10, 20, 30, 0.502)", ThemeBridge.css(Color(10, 20, 30, 128)))
            assertEquals("#0a141e", ThemeBridge.css(Color(10, 20, 30)))
        } finally {
            Locale.setDefault(original)
        }
    }
}
