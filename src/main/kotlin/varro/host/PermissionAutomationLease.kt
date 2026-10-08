package varro.host

import com.google.gson.JsonObject
import varro.protocol.num
import varro.protocol.str

/** Manual replies stay available; automatic work must come from the current ready owner. */
internal object PermissionAutomationLease {
    private val replyPath = Regex("^/permission/[^/]+/reply$")

    fun assertCurrent(payload: JsonObject, isCurrent: (Long) -> Boolean) {
        if (payload.str("method")?.uppercase() != "POST") return
        val path = payload.str("path")?.substringBefore('?') ?: return
        val judge = path == ApiRoutes.Endpoints.PERMISSION_JUDGE
        if (!judge && !replyPath.matches(path)) return
        if (!payload.has("permissionAutomationLease")) {
            check(!judge) { "Permission automation lease is required" }
            return
        }
        val lease = payload.num("permissionAutomationLease")
        check(lease != null && lease.isFinite() && lease >= 0 && lease <= 9_007_199_254_740_991L &&
            lease % 1.0 == 0.0 && isCurrent(lease.toLong())) { "Permission automation ownership changed" }
    }
}
