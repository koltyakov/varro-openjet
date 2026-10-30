package varro.server

import varro.protocol.*
import java.net.URLEncoder
import java.nio.file.Path

/** Offline maintenance must protect idle background services and location-owned forms too. */
internal object OfflineMaintenancePreflight {
    fun requireIdle(request: (String, RequestOptions) -> OpenCodeResponse) {
        val options = RequestOptions(unscoped = true, timeoutMs = 30_000, maxResponseBytes = 16L * 1024 * 1024, cancellableBody = true)
        val active = request("/api/session/active", options).data.asObjectOrNull().obj("data") ?: error("Malformed global execution status")
        require(active.size() == 0) { "Active work prevents offline maintenance" }
        val locations = request("/api/debug/location", options).data.asArrayOrNull() ?: error("Malformed loaded locations")
        require(locations.size() <= 10_000) { "Too many loaded locations for safe maintenance" }
        locations.map { value -> value.asObjectOrNull().text("directory")?.takeIf { Path.of(it).isAbsolute } ?: error("Malformed loaded location") }
            .distinct().forEach { directory ->
                val encoded = URLEncoder.encode(directory, Charsets.UTF_8).replace("+", "%20")
                val statuses = request("/session/status?directory=$encoded", options).data.asObjectOrNull() ?: error("Malformed session status")
                require(statuses.entrySet().all { (_, value) -> value.asObjectOrNull().let {
                    it.str("type") == "idle" && (it.num("backgroundServices") ?: 0.0) == 0.0
                } }) { "Running shells or background services prevent offline maintenance" }
                for (route in listOf("/api/permission/request", "/api/form")) {
                    val pending = request("$route?location%5Bdirectory%5D=$encoded", options).data.asObjectOrNull().arr("data")
                        ?: error("Malformed pending decisions")
                    require(pending.isEmpty) { "Pending decisions prevent offline maintenance" }
                }
            }
    }
}
