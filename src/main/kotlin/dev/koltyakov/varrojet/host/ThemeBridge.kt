package dev.koltyakov.varrojet.host

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.UIManager

/** Theme kinds the webview branches on, matching upstream's `WebviewThemeKind`. */
enum class WebviewThemeKind(val id: String, val bodyClass: String) {
    LIGHT("light", "vscode-light"),
    DARK("dark", "vscode-dark"),
    HIGH_CONTRAST("high-contrast", "vscode-high-contrast"),
    HIGH_CONTRAST_LIGHT("high-contrast-light", "vscode-high-contrast-light"),
}

/** A resolved theme: the kind plus the CSS custom properties to inject. */
data class WebviewTheme(
    val kind: WebviewThemeKind,
    val variables: Map<String, String>,
) {
    /** `--name: value;` declarations for a `:root` block. */
    fun toCssDeclarations(): String =
        variables.entries.joinToString("\n") { (name, value) -> "  $name: $value;" }
}

/**
 * Translates the active IDE theme into the `--vscode-*` custom properties the
 * Varro webview reads.
 *
 * The webview never asks the host for colors directly: its stylesheet is written
 * entirely against `var(--vscode-…, fallback)`, and `lib/theme.ts` then corrects
 * text contrast at runtime against whatever background it finds. That is what
 * makes this bridge viable - the mapping has to be *plausible*, not exhaustive,
 * because every variable already carries a fallback and the webview re-derives
 * readable foregrounds itself.
 *
 * Colors come from three sources, in order of preference:
 *  1. Named IntelliJ UI keys (`Panel.background`, `Button.startBackground`, …),
 *     which themes override and therefore track custom themes correctly.
 *  2. The active editor color scheme, for editor background/foreground and the
 *     ANSI palette used by terminal-flavoured chips.
 *  3. Derived tints, for the handful of VS Code concepts JetBrains has no direct
 *     equivalent of.
 */
object ThemeBridge {

    fun current(): WebviewTheme {
        val dark = isDark()
        val highContrast = isHighContrast()
        val kind = when {
            highContrast && dark -> WebviewThemeKind.HIGH_CONTRAST
            highContrast -> WebviewThemeKind.HIGH_CONTRAST_LIGHT
            dark -> WebviewThemeKind.DARK
            else -> WebviewThemeKind.LIGHT
        }
        return WebviewTheme(kind, buildVariables(dark, highContrast))
    }

    fun isDark(): Boolean = !JBColor.isBright()

    /**
     * JetBrains has no "high contrast" theme *kind*, only a bundled theme named
     * so. Matching on the theme name is the only signal available, and getting it
     * wrong only costs slightly different borders.
     */
    private fun isHighContrast(): Boolean {
        val name = runCatching { LafManager.getInstance().currentUIThemeLookAndFeel?.name }
            .getOrNull()
            ?: return false
        return name.contains("High contrast", ignoreCase = true)
    }

    private fun buildVariables(dark: Boolean, highContrast: Boolean): Map<String, String> {
        val scheme = runCatching { EditorColorsManager.getInstance().globalScheme }.getOrNull()

        val panelBackground = uiColor("Panel.background") ?: fallback(dark, 0x252526, 0xF3F3F3)
        val sideBarBackground = uiColor("ToolWindow.background")
            ?: uiColor("Tree.background")
            ?: panelBackground
        val foreground = uiColor("Panel.foreground")
            ?: uiColor("Label.foreground")
            ?: fallback(dark, 0xCCCCCC, 0x3B3B3B)
        val editorBackground = scheme?.defaultBackground ?: fallback(dark, 0x1E1E1E, 0xFFFFFF)
        val editorForeground = scheme?.defaultForeground ?: foreground

        val borderColor = uiColor("Component.borderColor")
            ?: uiColor("Borders.color")
            ?: fallback(dark, 0x474747, 0xD4D4D4)
        val focusBorder = uiColor("Component.focusedBorderColor")
            ?: uiColor("Component.focusColor")
            ?: fallback(dark, 0x3794FF, 0x0090F1)

        val inputBackground = uiColor("TextField.background")
            ?: uiColor("EditorPane.background")
            ?: fallback(dark, 0x3C3C3C, 0xFFFFFF)
        val inputForeground = uiColor("TextField.foreground") ?: foreground

        val buttonBackground = uiColor("Button.default.startBackground")
            ?: uiColor("Button.startBackground")
            ?: fallback(dark, 0x0E639C, 0x0078D4)
        val buttonForeground = uiColor("Button.default.foreground")
            ?: uiColor("Button.foreground")
            ?: fallback(dark, 0xFFFFFF, 0xFFFFFF)

        val listHover = uiColor("List.hoverBackground")
            ?: uiColor("Table.hoverBackground")
            ?: tint(sideBarBackground, dark, 0.06)

        val linkColor = uiColor("Link.activeForeground")
            ?: uiColor("Component.linkColor")
            ?: fallback(dark, 0x589DF6, 0x2470B3)

        val errorColor = uiColor("Label.errorForeground")
            ?: uiColor("Notification.errorForeground")
            ?: fallback(dark, 0xF48771, 0xE51400)
        val warningColor = uiColor("Label.warningForeground") ?: fallback(dark, 0xCCA700, 0xBF8803)
        val successColor = fallback(dark, 0x89D185, 0x388A34)
        val mutedForeground = uiColor("Label.infoForeground")
            ?: uiColor("Label.disabledForeground")
            ?: mix(foreground, sideBarBackground, 0.65)

        val widgetBackground = uiColor("PopupMenu.background")
            ?: uiColor("CompletionPopup.background")
            ?: tint(panelBackground, dark, 0.04)

        val variables = linkedMapOf<String, String>()

        fun put(name: String, color: Color) {
            variables[name] = css(color)
        }

        // --- Base surfaces ----------------------------------------------------
        put("--vscode-foreground", foreground)
        put("--vscode-editor-background", editorBackground)
        put("--vscode-editor-foreground", editorForeground)
        put("--vscode-sideBar-background", sideBarBackground)
        put("--vscode-sideBar-foreground", foreground)
        put("--vscode-interactive-session-foreground", foreground)
        put("--vscode-panel-border", borderColor)
        put("--vscode-widget-border", borderColor)
        put("--vscode-focusBorder", focusBorder)
        put("--vscode-icon-foreground", mutedForeground)
        put("--vscode-descriptionForeground", mutedForeground)
        put("--vscode-textSeparator-foreground", withAlpha(borderColor, 0.6))
        put("--vscode-editorWidget-background", widgetBackground)
        put("--vscode-interactive-result-editor-background-color", editorBackground)
        put("--vscode-chat-list-background", sideBarBackground)
        variables["--vscode-widget-shadow"] = "rgba(0, 0, 0, ${if (dark) "0.36" else "0.16"})"

        // --- Text and links ---------------------------------------------------
        put("--vscode-textLink-foreground", linkColor)
        put("--vscode-textLink-activeForeground", brighten(linkColor, dark))
        put("--vscode-errorForeground", errorColor)
        put("--vscode-editorWarning-foreground", warningColor)
        put("--vscode-testing-iconPassed", successColor)
        put("--vscode-testing-iconFailed", errorColor)
        put("--vscode-gitDecoration-modifiedResourceForeground", fallback(dark, 0xE2C08D, 0x895503))

        // --- Inputs and buttons ----------------------------------------------
        put("--vscode-input-background", inputBackground)
        put("--vscode-input-foreground", inputForeground)
        put("--vscode-input-border", borderColor)
        put("--vscode-input-placeholderForeground", mix(inputForeground, inputBackground, 0.6))
        put("--vscode-button-background", buttonBackground)
        put("--vscode-button-foreground", buttonForeground)
        put("--vscode-button-hoverBackground", brighten(buttonBackground, dark))
        put("--vscode-button-border", withAlpha(borderColor, 0.0))
        variables["--vscode-button-separator"] = "rgba(255, 255, 255, 0.2)"
        put("--vscode-button-secondaryBackground", tint(panelBackground, dark, 0.10))
        put("--vscode-button-secondaryForeground", foreground)
        put("--vscode-button-secondaryHoverBackground", tint(panelBackground, dark, 0.16))

        // --- Lists, toolbars, scrollbars -------------------------------------
        put("--vscode-list-hoverBackground", listHover)
        put("--vscode-toolbar-hoverBackground", listHover)
        put("--vscode-scrollbarSlider-background", withAlpha(mutedForeground, 0.28))
        put("--vscode-scrollbarSlider-hoverBackground", withAlpha(mutedForeground, 0.44))

        // --- Validation -------------------------------------------------------
        put("--vscode-inputValidation-errorBackground", withAlpha(errorColor, if (dark) 0.18 else 0.10))
        put("--vscode-inputValidation-errorBorder", errorColor)
        put("--vscode-inputValidation-errorForeground", foreground)
        put("--vscode-inputValidation-warningBackground", withAlpha(warningColor, if (dark) 0.18 else 0.10))
        put("--vscode-inputValidation-warningBorder", warningColor)

        // --- Markdown and code ------------------------------------------------
        put("--vscode-textPreformat-foreground", fallback(dark, 0xCE9178, 0xA31515))
        put("--vscode-textPreformat-background", withAlpha(mutedForeground, if (dark) 0.16 else 0.10))
        put("--vscode-textPreformat-border", withAlpha(borderColor, 0.8))
        put("--vscode-textBlockQuote-background", withAlpha(mutedForeground, if (dark) 0.10 else 0.06))
        put("--vscode-textBlockQuote-border", withAlpha(focusBorder, 0.7))
        put("--vscode-editor-selectionBackground", scheme?.getColor(com.intellij.openapi.editor.colors.EditorColors.SELECTION_BACKGROUND_COLOR) ?: withAlpha(focusBorder, 0.3))

        // --- Diffs ------------------------------------------------------------
        val added = fallback(dark, 0x487E02, 0x4B825D)
        val removed = fallback(dark, 0x8B1E1E, 0xC74E39)
        put("--vscode-diffEditor-insertedTextBackground", withAlpha(added, 0.25))
        put("--vscode-diffEditor-insertedLineBackground", withAlpha(added, 0.14))
        put("--vscode-diffEditor-removedTextBackground", withAlpha(removed, 0.25))
        put("--vscode-diffEditor-removedLineBackground", withAlpha(removed, 0.14))
        put("--vscode-chat-linesAddedForeground", fallback(dark, 0x89D185, 0x388A34))
        put("--vscode-chat-linesRemovedForeground", fallback(dark, 0xF48771, 0xE51400))

        // --- Chat bubbles -----------------------------------------------------
        put("--vscode-chat-requestBubbleBackground", tint(sideBarBackground, dark, 0.05))
        put("--vscode-chat-requestBubbleHoverBackground", tint(sideBarBackground, dark, 0.09))
        put("--vscode-chat-requestBorder", withAlpha(borderColor, 0.8))
        put("--vscode-chat-avatarBackground", tint(sideBarBackground, dark, 0.12))
        put("--vscode-chat-avatarForeground", foreground)
        put("--vscode-chat-thinkingShimmer", withAlpha(focusBorder, 0.55))

        // --- ANSI palette, taken from the editor scheme's console colors ------
        putAnsi(variables, scheme, dark)

        // --- Chart colors -----------------------------------------------------
        put("--vscode-charts-blue", fallback(dark, 0x3794FF, 0x0F6CBD))
        put("--vscode-charts-green", fallback(dark, 0x89D185, 0x388A34))
        put("--vscode-charts-yellow", fallback(dark, 0xCCA700, 0xBF8803))
        put("--vscode-charts-orange", fallback(dark, 0xD18616, 0xC26A10))
        put("--vscode-charts-purple", fallback(dark, 0xB180D7, 0x652D90))

        // --- High contrast ----------------------------------------------------
        if (highContrast) {
            put("--vscode-contrastBorder", foreground)
            put("--vscode-contrastActiveBorder", focusBorder)
        } else {
            variables["--vscode-contrastBorder"] = "transparent"
            variables["--vscode-contrastActiveBorder"] = "transparent"
        }

        // --- Typography -------------------------------------------------------
        // The vendored layout is tuned against VS Code's browser UI stack. The
        // JetBrains UI font is often wider (Inter in the new UI), which changes
        // wrapping and makes fixed-size controls look horizontally stretched.
        variables["--vscode-font-family"] =
            "-apple-system, BlinkMacSystemFont, \"Segoe WPC\", \"Segoe UI\", system-ui, sans-serif"
        variables["--vscode-font-size"] = "${uiFontSize()}px"
        variables["--vscode-editor-font-family"] = cssFontStack(editorFontFamily(scheme))

        return variables
    }

    /**
     * ANSI colors back the terminal-flavoured chips and tool output. The console
     * color keys are the IDE's own ANSI palette, so themes that restyle the
     * terminal restyle these too.
     */
    private fun putAnsi(target: MutableMap<String, String>, scheme: EditorColorsScheme?, dark: Boolean) {
        fun console(key: String, fallbackDark: Int, fallbackLight: Int): Color {
            val attributes = scheme?.getAttributes(
                com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey(key),
            )
            return attributes?.foregroundColor ?: fallback(dark, fallbackDark, fallbackLight)
        }

        target["--vscode-terminal-ansiRed"] = css(console("CONSOLE_RED_OUTPUT", 0xCD3131, 0xCD3131))
        target["--vscode-terminal-ansiGreen"] = css(console("CONSOLE_GREEN_OUTPUT", 0x0DBC79, 0x00BC00))
        target["--vscode-terminal-ansiYellow"] = css(console("CONSOLE_YELLOW_OUTPUT", 0xE5E510, 0x949800))
        target["--vscode-terminal-ansiBlue"] = css(console("CONSOLE_BLUE_OUTPUT", 0x2472C8, 0x0451A5))
        target["--vscode-terminal-ansiMagenta"] = css(console("CONSOLE_MAGENTA_OUTPUT", 0xBC3FBC, 0xBC05BC))
        target["--vscode-terminal-ansiCyan"] = css(console("CONSOLE_CYAN_OUTPUT", 0x11A8CD, 0x0598BC))
        target["--vscode-terminal-ansiBrightBlue"] =
            css(console("CONSOLE_DARKBLUE_OUTPUT", 0x3B8EEA, 0x0451A5))
    }

    // --- Font helpers ---------------------------------------------------------

    fun uiFontFamily(): String = JBUI.Fonts.label().family

    fun uiFontSize(): Int = JBUI.Fonts.label().size

    fun editorFontFamily(scheme: EditorColorsScheme? = runCatching {
        EditorColorsManager.getInstance().globalScheme
    }.getOrNull()): String = scheme?.editorFontName ?: "monospace"

    fun editorFontSize(scheme: EditorColorsScheme? = runCatching {
        EditorColorsManager.getInstance().globalScheme
    }.getOrNull()): Int = scheme?.editorFontSize ?: 12

    /** Quotes a family name and appends a generic fallback, like VS Code does. */
    private fun cssFontStack(family: String): String {
        val generic = if (family.contains("mono", ignoreCase = true)) "monospace" else "sans-serif"
        return "\"${family.replace("\"", "")}\", $generic"
    }

    // --- Color helpers --------------------------------------------------------

    private fun uiColor(key: String): Color? = UIManager.getColor(key)

    private fun fallback(dark: Boolean, darkRgb: Int, lightRgb: Int): Color =
        Color(if (dark) darkRgb else lightRgb)

    private fun css(color: Color): String =
        if (color.alpha == 255) {
            "#%02x%02x%02x".format(color.red, color.green, color.blue)
        } else {
            "rgba(${color.red}, ${color.green}, ${color.blue}, ${"%.3f".format(color.alpha / 255.0)})"
        }

    private fun withAlpha(color: Color, alpha: Double): Color =
        Color(color.red, color.green, color.blue, (alpha.coerceIn(0.0, 1.0) * 255).toInt())

    private fun mix(foreground: Color, background: Color, ratio: Double): Color =
        ColorUtil.mix(background, foreground, ratio.coerceIn(0.0, 1.0))

    /** Lightens on dark themes and darkens on light ones, so "hover" reads as raised. */
    private fun tint(color: Color, dark: Boolean, amount: Double): Color =
        if (dark) ColorUtil.mix(color, Color.WHITE, amount) else ColorUtil.mix(color, Color.BLACK, amount)

    private fun brighten(color: Color, dark: Boolean): Color =
        if (dark) ColorUtil.mix(color, Color.WHITE, 0.12) else ColorUtil.mix(color, Color.BLACK, 0.10)
}
