package varro.server

/** Read-only diagnostics. A local CLI does not imply ownership of the server. */
data class OpenCodeVersionInfo(
    val url: String,
    val cliVersion: String?,
    val serverVersion: String?,
    val startedAt: Long?,
    val attachOnly: Boolean,
)
