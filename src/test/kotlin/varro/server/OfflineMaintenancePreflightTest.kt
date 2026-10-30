package varro.server

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*

class OfflineMaintenancePreflightTest {
    @Test fun `maintenance checks loaded locations only and protects idle services and global forms`() {
        for (blocker in listOf("none", "active", "service", "global-form", "permission")) {
            val calls = mutableListOf<String>()
            fun run() = OfflineMaintenancePreflight.requireIdle { path, options ->
                assertTrue(options.unscoped); assertTrue(options.cancellableBody)
                calls.add(path)
                OpenCodeResponse(when {
                    path == "/api/session/active" -> Json.obj("data" to if (blocker == "active") Json.obj("session" to Json.obj("type" to "running")) else Json.obj())
                    path == "/api/debug/location" -> Json.array(listOf(Json.obj("directory" to "/fixture/loaded")))
                    path.startsWith("/session/status?") -> Json.obj("service" to Json.obj("type" to "idle", "backgroundServices" to if (blocker == "service") 1 else 0))
                    path.startsWith("/api/form?") -> Json.obj("data" to if (blocker == "global-form") listOf(Json.obj("sessionID" to "global")) else emptyList<Any>())
                    path.startsWith("/api/permission/request?") -> Json.obj("data" to if (blocker == "permission") listOf(Json.obj("sessionID" to "session")) else emptyList<Any>())
                    else -> error("Maintenance must not open historical directories: $path")
                })
            }
            if (blocker == "none") run() else assertThrows(IllegalArgumentException::class.java) { run() }
            assertFalse(calls.any { it.startsWith("/experimental/session") })
        }
    }
}
