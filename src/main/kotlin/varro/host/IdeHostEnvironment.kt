package varro.host

import com.intellij.openapi.diagnostic.logger

/** Uses the public product-mode API when present, without linking older IDEs to a missing class. */
internal class IdeHostEnvironment(
    private val productMode: () -> Boolean? = ::readProductMode,
    private val launchArguments: () -> List<String>? = { ProcessHandle.current().info().arguments().orElse(null)?.toList() },
) {
    private val remote by lazy {
        try {
            productMode() ?: launchArguments()?.any { it in LEGACY_REMOTE_COMMANDS } ?: true
        } catch (failure: Exception) {
            logger<IdeHostEnvironment>().warn("Could not determine the IDE host mode; local dictation is disabled", failure)
            true
        }
    }

    fun isRemoteHost(): Boolean = remote

    companion object {
        val current = IdeHostEnvironment()

        // IDE 252 predates IdeProductMode. These are its remote/split-mode launcher commands.
        private val LEGACY_REMOTE_COMMANDS = setOf("cwmHost", "cwmHostNoLobby", "remoteDevHost", "serverMode", "splitMode")

        private fun readProductMode(): Boolean? {
            val type = try {
                Class.forName("com.intellij.platform.ide.productMode.IdeProductMode")
            } catch (_: ClassNotFoundException) {
                return null
            }
            return type.getMethod("isBackend").invoke(null) as? Boolean
                ?: error("The IDE product-mode API returned an invalid backend flag")
        }
    }
}
