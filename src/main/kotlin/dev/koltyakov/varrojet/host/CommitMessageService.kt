package dev.koltyakov.varrojet.host

import com.google.gson.JsonArray
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.koltyakov.varrojet.protocol.Json
import dev.koltyakov.varrojet.protocol.asObjectOrNull
import dev.koltyakov.varrojet.protocol.arr
import dev.koltyakov.varrojet.protocol.obj
import dev.koltyakov.varrojet.protocol.str
import dev.koltyakov.varrojet.server.OpenCodeServer
import dev.koltyakov.varrojet.server.RequestOptions
import dev.koltyakov.varrojet.settings.VarroSettings

/**
 * Generates a commit message from the current changes.
 *
 * Port of `src/extension/commit-message-service.ts`. The rules that matter for
 * trust are preserved exactly:
 *
 *  - Staged changes are used when the index has any; otherwise the unstaged
 *    working tree is used. Staged and unstaged changes are **never** mixed,
 *    because a message describing both would not match the commit that follows.
 *  - Nothing is staged and nothing is committed. The message is written to the
 *    commit input for the user to review.
 *  - The helper session is hidden from the chat history so generating a message
 *    does not litter the session list.
 */
class CommitMessageService(
    private val project: Project,
    private val server: OpenCodeServer,
    private val settings: VarroSettings,
) {
    private val log = logger<CommitMessageService>()

    sealed interface Result {
        data class Generated(val message: String) : Result
        data class Failed(val reason: String) : Result
        data object NoChanges : Result
    }

    /**
     * @param diff the change set to describe, produced by the caller from Git.
     * @param recentMessages recent commit subjects, used to follow the
     *   repository's existing style rather than imposing one.
     */
    fun generate(diff: String, recentMessages: List<String>): Result {
        if (diff.isBlank()) return Result.NoChanges

        val model = resolveModel() ?: return Result.Failed(
            "No model is configured. Set one in Settings | Tools | Varro, or connect a provider.",
        )

        val bounded = if (diff.length > MAX_DIFF_CHARS) {
            // A very large diff is truncated rather than refused: the subject line
            // is usually derivable from the first files, and refusing outright is
            // worse than a message the user edits.
            diff.take(MAX_DIFF_CHARS) + "\n\n[diff truncated]"
        } else {
            diff
        }

        return runCatching {
            val sessionId = createHelperSession() ?: return Result.Failed("Could not start a helper session.")
            try {
                val reply = prompt(sessionId, buildPrompt(bounded, recentMessages), model)
                val message = extractMessage(reply)
                if (message.isNullOrBlank()) {
                    Result.Failed("The model did not return a commit message.")
                } else {
                    Result.Generated(message)
                }
            } finally {
                // The helper session exists only for this request.
                runCatching {
                    server.transport.request("DELETE", "/session/${encode(sessionId)}")
                }
            }
        }.getOrElse { failure ->
            log.warn("Commit message generation failed", failure)
            Result.Failed(failure.message ?: "Commit message generation failed.")
        }
    }

    private fun resolveModel(): Pair<String, String>? {
        val configured = settings.commitMessageModel.trim()
        if (configured.isNotEmpty()) {
            val provider = configured.substringBefore('/', "")
            val model = configured.substringAfter('/', "")
            if (provider.isNotEmpty() && model.isNotEmpty()) return provider to model
        }

        // Fall back to whatever OpenCode considers the default, so the feature
        // works without any Varro-specific configuration.
        return runCatching {
            val record = server.transport.request(
                "GET",
                "/model/default",
                options = RequestOptions(unscoped = true),
            ).data.asObjectOrNull()
            val provider = record.str("providerID") ?: return@runCatching null
            val model = record.str("modelID") ?: return@runCatching null
            provider to model
        }.getOrNull()
    }

    private fun createHelperSession(): String? = runCatching {
        server.transport.request(
            "POST",
            "/session",
            body = Json.obj("title" to HELPER_SESSION_TITLE),
        ).data.asObjectOrNull()?.str("id")
    }.getOrNull()

    private fun prompt(sessionId: String, text: String, model: Pair<String, String>): String {
        val response = server.transport.request(
            "POST",
            "/session/${encode(sessionId)}/prompt_async",
            body = Json.obj(
                "model" to Json.obj("providerID" to model.first, "modelID" to model.second),
                "parts" to JsonArray().apply { add(Json.obj("type" to "text", "text" to text)) },
            ),
        )
        return collectAssistantText(sessionId) ?: Json.stringify(response.data)
    }

    /**
     * Reads the assistant's reply back off the session.
     *
     * `prompt_async` returns as soon as the run is accepted, so the text has to be
     * polled. The window is short because a commit message is a single small turn.
     */
    private fun collectAssistantText(sessionId: String): String? {
        val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            val messages = runCatching {
                server.transport.request("GET", "/session/${encode(sessionId)}/message").data
            }.getOrNull() ?: continue

            val entries = if (messages.isJsonArray) messages.asJsonArray else continue

            val assistant = entries.reversed().firstOrNull { entry ->
                entry.asObjectOrNull().obj("info").str("role") == "assistant"
            }?.asObjectOrNull() ?: continue

            // A reply is only complete once the turn has finished; reading a partial
            // stream would yield a half-written subject line.
            if (assistant.obj("info").obj("time")?.has("completed") != true) continue

            val text = assistant.arr("parts")
                ?.mapNotNull { part ->
                    val record = part.asObjectOrNull()
                    if (record.str("type") == "text") record.str("text") else null
                }
                ?.joinToString("\n")
                ?.trim()

            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun buildPrompt(diff: String, recentMessages: List<String>): String = buildString {
        appendLine("Write a commit message for the following changes.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Reply with the commit message only. No preamble, no code fences, no commentary.")
        appendLine("- First line: a concise subject under 72 characters, in the imperative mood.")
        appendLine("- Add a body only when the change needs explanation, separated by a blank line.")
        appendLine("- Describe what the change does and why, not which files moved.")
        if (recentMessages.isNotEmpty()) {
            appendLine()
            appendLine("Follow the style of recent commits in this repository:")
            recentMessages.take(MAX_RECENT_MESSAGES).forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Changes:")
        appendLine("```diff")
        appendLine(diff)
        appendLine("```")
    }

    /**
     * Strips the wrappers models add despite being asked not to: a fenced block,
     * or a leading label such as "Commit message:".
     */
    private fun extractMessage(reply: String): String? {
        var text = reply.trim()
        if (text.isEmpty()) return null

        FENCE.find(text)?.let { match -> text = match.groupValues[1].trim() }
        text = LEADING_LABEL.replace(text, "").trim()

        return text.ifBlank { null }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    companion object {
        /** Marks the session as Varro's own, so it can be filtered from the list. */
        const val HELPER_SESSION_TITLE = "varro:commit-message"

        private const val MAX_DIFF_CHARS = 60_000
        private const val MAX_RECENT_MESSAGES = 10
        private const val REPLY_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 400L

        private val FENCE = Regex("""```(?:\w+)?\s*\n(.*?)\n?```""", RegexOption.DOT_MATCHES_ALL)
        private val LEADING_LABEL = Regex("""^(?:commit\s+message|subject)\s*:\s*""", RegexOption.IGNORE_CASE)
    }
}
