package varro.host

import com.google.gson.JsonElement
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import varro.server.WorkspacePaths
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Optional attribution, never authorization. Disabled calls perform no reads and preserve every byte. */
internal class PromptTrace(
    private val request: (String, String, JsonElement?, RequestOptions) -> OpenCodeResponse,
    private val enabled: () -> Boolean = ::isEnabled,
) {
    fun prompt(purpose: String, prompt: String, options: RequestOptions, sessionID: String? = null,
               requestID: String? = null, helperSessionID: String? = null): String {
        if (!enabled()) return prompt
        options.checkCancelled()
        val session = validID(sessionID)
        val root = session?.let { root(it, options) }
        options.checkCancelled()
        val marker = Json.obj("purpose" to purpose)
        session?.let { marker.addProperty("sessionID", it) }
        root?.let { marker.addProperty("rootSessionID", it) }
        if (session == null && WorkspacePaths.isAbsolute(options.directory)) {
            val workspace = WorkspacePaths.normalizeIdentity(options.directory)!!
            marker.addProperty("workspaceID", MessageDigest.getInstance("SHA-256").digest(workspace.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
        }
        validID(requestID)?.let { marker.addProperty("requestID", it) }
        validID(helperSessionID)?.let { marker.addProperty("helperSessionID", it) }
        return "VARRO_TRACE_V1 $marker\n$prompt"
    }

    private fun root(source: String, options: RequestOptions): String? {
        val bounded = options.copy(maxResponseBytes = 64 * 1024,
            deadlineNanos = minOf(options.deadlineNanos ?: Long.MAX_VALUE, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)))
        val seen = mutableSetOf<String>()
        var current = source
        try {
            repeat(16) {
                bounded.checkCancelled()
                if (validID(current) == null || !seen.add(current)) return null
                val session = request("GET", "/session/$current", null, bounded).data.asObjectOrNull()
                bounded.checkCancelled()
                if (session.str("id") != current) return null
                if (session?.has("parentID") != true) return current
                current = session.str("parentID") ?: return null
            }
        } catch (_: Exception) { options.checkCancelled() }
        return null
    }

    companion object {
        fun isEnabled(): Boolean = com.intellij.openapi.application.ApplicationManager.getApplication()
            ?.getService(varro.settings.VarroSettings::class.java)?.chatPromptTrace == true
        private fun validID(value: String?): String? = value?.takeIf { Regex("[A-Za-z0-9_-]{1,200}").matches(it) }
    }
}
