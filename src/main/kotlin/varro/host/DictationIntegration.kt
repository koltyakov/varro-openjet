package varro.host

import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import varro.host.HandyIntegration.Action
import varro.host.HandyIntegration.Status
import varro.protocol.*
import java.util.concurrent.CompletableFuture

/** One app-wide command guard and capture owner, shared across every project/view. */
@Service(Service.Level.APP)
internal class DictationIntegration(
    private val handy: (Action, ((Status) -> Unit)?) -> CompletableFuture<Status>,
    private val hex: (Action, ((Status) -> Unit)?) -> CompletableFuture<Status>,
    private val closeIntegrations: () -> Unit = {},
    private val localHost: () -> Boolean = { true },
) : com.intellij.openapi.Disposable {
    constructor() : this(HandyIntegration(), HexIntegration())
    private constructor(handy: HandyIntegration, hex: HexIntegration) : this(handy::request, hex::request, { handy.dispose(); hex.close() }, { !IdeHostEnvironment.current.isRemoteHost() })
    private var pending: CompletableFuture<Status>? = null
    private var pendingAction: Action? = null
    private var owner: String? = null
    private var selected: String? = null
    private var generation = 0L
    private var revision = 0L
    private var disposed = false
    private val observations = mutableMapOf<String, Pair<Long, Status>>()

    fun handleRequest(payload: JsonObject?, post: (JsonObject) -> Unit) {
        val request = HandyIntegration.parseRequest(payload) ?: return
        if (payload?.has("provider") == true && payload.str("provider") !in setOf("handy", "hex")) return
        request(request.action, { post(Json.message("dictation/result", it.payload(request.requestId))) }, payload.str("provider"))
            .thenAccept { post(Json.message("dictation/result", it.payload(request.requestId))) }
    }

    @Synchronized fun request(action: Action, onState: ((Status) -> Unit)? = null, provider: String? = null): CompletableFuture<Status> {
        if (disposed || !localHost()) return CompletableFuture.completedFuture(Status(false, false))
        pending?.let { operation ->
            if (action != Action.STATUS && pendingAction == Action.STATUS) return operation.thenCompose { request(action, onState, provider) }
            return operation.thenApply { it.copy(error = if (action == Action.STATUS) it.error else "Another dictation command is still pending.") }
        }
        val result = CompletableFuture<Status>()
        pending = result
        pendingAction = action
        val chosen = owner ?: provider?.takeIf { action != Action.START }
        val discovery = try {
            if (chosen != null) CompletableFuture.completedFuture(chosen to null) else {
                handy(Action.STATUS, null).thenCombine(hex(Action.STATUS, null)) { h, x ->
                    val id = if (x.running || (!h.running && x.available)) "hex" else "handy"
                    id to if (id == "hex") x else h
                }
            }
        } catch (failure: Exception) {
            pending = null
            result.complete(Status(false, false, "Dictation failed: ${failure.message}"))
            return result
        }
        discovery.thenCompose { (id, observed) ->
            if (synchronized(this) { disposed }) return@thenCompose CompletableFuture.completedFuture(Status(false, false))
            val epoch = synchronized(this) {
                if (action != Action.STATUS || selected != id) generation++
                selected = id
                generation
            }
            val integration = if (id == "hex") hex else handy
            val operation = if (action == Action.STATUS && observed != null) CompletableFuture.completedFuture(observed)
            else integration(action) { update -> synchronized(this) {
                if (!disposed && epoch == generation) onState?.invoke(publish(id, update))
            } }
            operation.thenApply { synchronized(this) { if (disposed) Status(false, false) else publish(id, it) } }
        }.whenComplete { status, failure ->
            synchronized(this) { if (pending === result) pending = null }
            result.complete(status ?: Status(false, false, "Dictation failed: ${failure?.message}"))
        }
        return result
    }

    private fun publish(provider: String, status: Status): Status {
        observations[provider]?.let { (raw, previous) -> if (status.revision < raw) return previous }
        if (status.state in setOf("recording", "processing")) owner = provider
        else if (status.state == "idle" && status.error == null) owner = null
        return status.copy(provider = provider, revision = ++revision).also { observations[provider] = status.revision to it }
    }

    @Synchronized override fun dispose() {
        disposed = true
        generation++
        closeIntegrations()
    }
}
