package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json

class PermissionAutomationLeaseTest {
    private fun request(path: String = "/permission/child-request/reply") = Json.obj("method" to "POST", "path" to path)

    @Test fun `manual replies remain actionable without an automation lease`() {
        PermissionAutomationLease.assertCurrent(request()) { error("Manual replies must not consult ownership") }
        assertEquals("Permission automation lease is required", assertThrows(IllegalStateException::class.java) {
            PermissionAutomationLease.assertCurrent(request(ApiRoutes.Endpoints.PERMISSION_JUDGE)) { true }
        }.message)
    }

    @Test fun `judge and child replies recheck the current owner on every request`() {
        for (path in listOf(ApiRoutes.Endpoints.PERMISSION_JUDGE, "/permission/child-request/reply?directory=%2Frepo")) {
            val payload = request(path).apply { addProperty("permissionAutomationLease", 7) }
            var currentLease = 7L
            var owner = true
            PermissionAutomationLease.assertCurrent(payload) { owner && it == currentLease }
            currentLease = 8L
            assertEquals("Permission automation ownership changed", assertThrows(IllegalStateException::class.java) {
                PermissionAutomationLease.assertCurrent(payload) { owner && it == currentLease }
            }.message)
            payload.addProperty("permissionAutomationLease", currentLease)
            owner = false
            assertThrows(IllegalStateException::class.java) {
                PermissionAutomationLease.assertCurrent(payload) { owner && it == currentLease }
            }
        }
    }

    @Test fun `malformed leases fail closed instead of being rounded or treated as manual`() {
        for (lease in listOf(null, "7", -1, 7.5, 9_007_199_254_740_992L)) {
            val payload = request().apply { add("permissionAutomationLease", Json.toElement(lease)) }
            assertThrows(IllegalStateException::class.java) {
                PermissionAutomationLease.assertCurrent(payload) { true }
            }
        }
    }

    @Test fun `non automation requests do not require a lease`() {
        for (payload in listOf(request("/session/child/abort"), request("/varro/permission/session-allow"),
            request(ApiRoutes.Endpoints.PERMISSION_JUDGE).apply { addProperty("method", "GET") })) {
            PermissionAutomationLease.assertCurrent(payload) { error("Unrelated request checked ownership") }
        }
    }
}
