package varro.server

import com.intellij.credentialStore.Credentials
import org.junit.Assert.*
import org.junit.Test

class OpenCodeServerAuthenticationTest {
    private val url = "http://127.0.0.1:4096"

    @Test fun `verified saved credentials authenticate without prompting and stay endpoint scoped`() {
        val auth = OpenCodeServerAuthentication(read = { Credentials("user", "saved") },
            save = { _, _ -> error("Already saved") }, prompt = { _, _ -> error("Must not prompt") })
        val result = auth.recover(url, {}, {
            assertEquals(OpenCodeConnection.authorization("saved", "user"), auth.authorization(url))
            HealthInfo(true)
        }, { false })
        assertTrue(result.healthy)
        assertNull(auth.authorization("http://127.0.0.1:4097"))
    }

    @Test fun `rejected saved password prompts once and only saves a verified replacement`() {
        var saved: Credentials? = null
        var attempts = 0
        val auth = OpenCodeServerAuthentication(read = { Credentials("user", "expired") },
            save = { endpoint, value -> assertEquals(url, endpoint); saved = value },
            prompt = { endpoint, username -> assertEquals(url, endpoint); assertEquals("user", username); Credentials("user", "new") })
        val result = auth.recover(url, {}, { HealthInfo(++attempts == 2) }, { attempts == 1 })
        assertTrue(result.healthy)
        assertEquals("new", saved?.getPasswordAsString())
        assertEquals(OpenCodeConnection.authorization("new", "user"), auth.authorization(url))
    }

    @Test fun `cancelled rejected and superseded credentials are never retained or saved`() {
        for (outcome in listOf("cancel", "reject", "supersede", "failure")) {
            var changed = false
            val auth = OpenCodeServerAuthentication(read = { null }, save = { _, _ -> error("Must not save") },
                prompt = { _, _ -> if (outcome == "cancel") null else Credentials("user", "password") })
            val run = {
                auth.recover(url, { check(!changed) }, {
                    if (outcome == "failure") error("Network failure")
                    if (outcome == "supersede") changed = true
                    HealthInfo(outcome == "supersede")
                }, { true })
            }
            if (outcome in listOf("supersede", "failure")) assertThrows(IllegalStateException::class.java) { run() }
            else assertFalse(run().healthy)
            assertNull(auth.authorization(url))
        }
    }
}
