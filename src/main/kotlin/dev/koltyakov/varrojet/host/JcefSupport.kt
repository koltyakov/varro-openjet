package dev.koltyakov.varrojet.host

import com.intellij.openapi.diagnostic.logger

/**
 * Whether this IDE can host the webview.
 *
 * This is deliberately a separate object with no `org.cef` references anywhere
 * in its signatures or bodies. JCEF is an *optional* dependency - it became a
 * bundled plugin in 2026.2 and a user can run a JBR without it - so the check
 * must be callable on an IDE where those classes are absent. Putting it on
 * [WebviewHost] would mean loading a class whose bodies reference `org.cef`
 * types just to ask the question.
 *
 * [runCatching] catches `Throwable`, which is what makes this safe: a missing
 * JCEF surfaces as `NoClassDefFoundError`, an `Error` rather than an `Exception`.
 */
object JcefSupport {

    private val log = logger<JcefSupport>()

    fun isAvailable(): Boolean = runCatching { com.intellij.ui.jcef.JBCefApp.isSupported() }
        .onFailure { log.info("JCEF is unavailable in this IDE: ${it.message}") }
        .getOrDefault(false)
}
