package varro.server

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

/** Retry authenticated endpoints with URL-bound credentials kept in the IDE password safe. */
internal class OpenCodeServerAuthentication(
    private val read: (String) -> Credentials? = { PasswordSafe.instance.get(attributes(it)) },
    private val save: (String, Credentials) -> Unit = { url, credentials -> PasswordSafe.instance.set(attributes(url), credentials) },
    private val prompt: (String, String) -> Credentials? = ::promptCredentials,
) {
    @Volatile private var current: Pair<String, String>? = null

    fun authorization(url: String): String? = current?.takeIf { it.first == url }?.second

    fun recover(url: String, checkCurrent: () -> Unit, health: () -> HealthInfo, rejected: () -> Boolean): HealthInfo {
        checkCurrent()
        val stored = read(url)
        checkCurrent()
        var verified = false
        try {
            if (!stored?.getPasswordAsString().isNullOrEmpty()) {
                use(url, stored)
                val result = health()
                checkCurrent()
                verified = result.healthy
                if (result.healthy || !rejected()) return result
            }
            val entered = prompt(url, stored?.userName ?: "opencode")
            checkCurrent()
            if (entered?.getPasswordAsString().isNullOrEmpty()) return HealthInfo(false)
            use(url, entered)
            val result = health()
            checkCurrent()
            verified = result.healthy
            if (result.healthy) save(url, entered) else current = null
            return result
        } finally {
            // A cancelled or superseded startup must not retain an unverified credential.
            try { checkCurrent() } catch (failure: Exception) { current = null; throw failure }
            if (!verified) current = null
        }
    }

    private fun use(url: String, credentials: Credentials) {
        current = url to OpenCodeConnection.authorization(credentials.getPasswordAsString()!!, credentials.userName ?: "opencode")
    }

    companion object {
        private fun attributes(url: String) = CredentialAttributes(generateServiceName("Varro OpenJet", "opencode.server:$url"))

        private fun promptCredentials(url: String, username: String): Credentials? {
            val application = ApplicationManager.getApplication()
            if (application.isHeadlessEnvironment) return null
            var result: Credentials? = null
            application.invokeAndWait {
                val title = "Connect to OpenCode server"
                val user = Messages.showInputDialog("Authentication required for $url. Enter the server username.",
                    title, null, username, object : InputValidator {
                        override fun checkInput(inputString: String) = inputString.isNotBlank() && ':' !in inputString
                        override fun canClose(inputString: String) = checkInput(inputString)
                    }) ?: return@invokeAndWait
                val password = Messages.showPasswordDialog(
                    "Enter the password for $url. Verified credentials are saved in the IDE password safe.", title,
                ) ?: return@invokeAndWait
                if (password.isNotEmpty()) result = Credentials(user, password)
            }
            return result
        }
    }
}
