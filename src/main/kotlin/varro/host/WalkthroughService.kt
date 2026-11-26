package varro.host

import com.google.gson.JsonElement
import varro.protocol.asObjectOrNull
import varro.protocol.arr
import varro.protocol.num
import varro.protocol.obj
import varro.protocol.str
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import java.net.http.HttpTimeoutException
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Generates an AI-guided reading order through a diff as a Markdown report.
 *
 * Port of the request side of upstream `src/extension/walkthrough-service.ts`.
 * It only runs when asked, because every walkthrough spends tokens on a capable
 * model. Git capture and UI live in `WalkThroughChangesAction`.
 */
class WalkthroughService(
    private val request: (String, String, JsonElement?, RequestOptions) -> OpenCodeResponse,
    private val apiVersion: () -> Int,
    private val now: () -> Instant = Instant::now,
) {
    data class Report(val content: String, val title: String)

    /** The model's context window in tokens, when OpenCode reports it; best effort. */
    fun readContextLimit(model: WalkthroughModel?, directory: String?,
                         options: RequestOptions = RequestOptions(directory = directory)): Int? {
        if (model == null) return null
        return try {
            val config = request("GET", "/config/providers", null, options.copy(directory = directory)).data.asObjectOrNull()
            val provider = config.arr("providers")
                ?.mapNotNull { it.asObjectOrNull() }
                ?.firstOrNull { it.str("id") == model.providerID }
            val models = provider.obj("models")
            val entry = models.obj(model.modelID)
                ?: models?.entrySet()?.mapNotNull { it.value.asObjectOrNull() }?.firstOrNull { it.str("id") == model.modelID }
            entry.obj("limit").num("context")?.takeIf { it.isFinite() }?.toInt()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sends [digest] to [model] through OpenCode's one-shot generation API and
     * renders the validated answer. Throws with a user-facing message on failure
     * and [CancellationException] when [isCancelled] turns true.
     */
    fun generate(files: List<WalkthroughFile>, digest: WalkthroughDigest, scopeLabel: String, directory: String?,
                 model: WalkthroughModel?, isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }): Report {
        if (isCancelled()) throw CancellationException("Walkthrough generation was cancelled")
        if (apiVersion() != 2) error("Changes walkthroughs require OpenCode v2.")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GENERATION_TIMEOUT_MS)
        val options = RequestOptions(
            directory = directory,
            isCancelled = isCancelled,
            timeoutMs = GENERATION_TIMEOUT_MS,
            deadlineNanos = deadline,
            maxResponseBytes = MAX_RESPONSE_BYTES,
        )
        val generated = try {
            OneShotGeneration(request).generate(
                2, Walkthrough.buildPrompt(digest.digest, scopeLabel), model?.toJson(), options, purpose = "walkthrough",
            )
        } catch (failure: Exception) {
            if (isCancelled() || failure is CancellationException) throw CancellationException("Walkthrough generation was cancelled")
            val timedOut = failure is HttpTimeoutException || failure.cause is HttpTimeoutException || System.nanoTime() - deadline >= 0
            if (timedOut) throw IllegalStateException("The model took too long to answer.", failure)
            throw failure
        }
        if (generated == null) {
            error(
                if (model != null) "${model.providerID}/${model.modelID} is unavailable for one-shot generation. Pick another model, or set one with Use for reviews in the Models view."
                else "This OpenCode server has no one-shot generation API. Update OpenCode to use walkthroughs.",
            )
        }
        val walkthrough = Walkthrough.normalize(Walkthrough.parseJsonObject(generated), files, digest.hunkByAlias)
        val content = Walkthrough.renderMarkdown(walkthrough, files, WalkthroughReportContext(
            scopeLabel = scopeLabel,
            modelLabel = model?.label,
            generatedAt = now(),
        ))
        return Report(content, walkthrough.title)
    }

    companion object {
        const val GENERATION_TIMEOUT_MS = 5 * 60_000L
        const val MAX_RESPONSE_BYTES = 1024L * 1024
    }
}
