package varro.server

import com.google.gson.JsonObject
import varro.protocol.Json

/** Why a startup failure happened, in a form the webview can branch on. */
enum class ServerErrorKind(val id: String) {
    CLI_MISSING("cli-missing"),
    CLI_PATH_INVALID("cli-path-invalid"),
    UPDATE_REQUIRED("update-required"),
    UPDATE_BLOCKED("update-blocked"),
    UPDATE_FAILED("update-failed"),
    GENERIC("generic"),
}

enum class ServerErrorBlockedBy(val id: String) {
    ACTIVE_SESSIONS("active-sessions"),
    AUTO_UPDATE_DISABLED("auto-update-disabled"),
    AUTO_START_DISABLED("auto-start-disabled"),
    FOREIGN_OWNER("foreign-owner"),
    VERIFY_FAILED("verify-failed"),
}

/**
 * Structured cause behind an `error` status. `message` stays the only required
 * part so the webview's generic states keep rendering when a failure carries no
 * detail.
 */
data class ServerErrorDetail(
    val kind: ServerErrorKind,
    val installMethod: OpenCodeInstallMethod? = null,
    /** Command that repairs this specific install; never a bare `opencode upgrade` after a failure. */
    val suggestedCommand: String? = null,
    val blockedBy: ServerErrorBlockedBy? = null,
    /** Settings id to deep-link, e.g. `varro.server.autoUpdate`. */
    val settingId: String? = null,
    val configuredCommand: String? = null,
    val searchedPaths: List<String>? = null,
    val observed: String? = null,
    val required: String? = null,
    val cause: String? = null,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("kind", kind.id)
        installMethod?.let { addProperty("installMethod", it.id) }
        suggestedCommand?.let { addProperty("suggestedCommand", it) }
        blockedBy?.let { addProperty("blockedBy", it.id) }
        settingId?.let { addProperty("settingId", it) }
        configuredCommand?.let { addProperty("configuredCommand", it) }
        searchedPaths?.let { add("searchedPaths", Json.array(it)) }
        observed?.let { addProperty("observed", it) }
        required?.let { addProperty("required", it) }
        cause?.let { addProperty("cause", it) }
    }
}

/** Health of the SSE connection while REST stays usable. */
enum class EventStreamState(val id: String) { HEALTHY("healthy"), DEGRADED("degraded") }

/**
 * Mirror of upstream's `ServerStatus` union. The webview switches its entire
 * top-level view on this, so the serialized shape has to match exactly.
 */
sealed interface ServerStatus {
    fun toJson(): JsonObject

    data object Starting : ServerStatus {
        override fun toJson(): JsonObject = Json.obj("state" to "starting")
    }

    data object Stopped : ServerStatus {
        override fun toJson(): JsonObject = Json.obj("state" to "stopped")
    }

    data class Running(
        val url: String,
        val eventStream: EventStreamState = EventStreamState.HEALTHY,
    ) : ServerStatus {
        override fun toJson(): JsonObject = Json.obj(
            "state" to "running",
            "url" to url,
            "eventStream" to eventStream.id,
        )
    }

    data class Error(
        val message: String,
        val detail: ServerErrorDetail? = null,
    ) : ServerStatus {
        override fun toJson(): JsonObject = JsonObject().apply {
            addProperty("state", "error")
            addProperty("message", message)
            detail?.let { add("detail", it.toJson()) }
        }
    }
}
