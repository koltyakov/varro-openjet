package varro.server

import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.bool
import varro.protocol.str
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Private credentials admit a connection only if the listener actually enforces them. */
internal class RegisteredConnectionVerifier {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build()

    fun verify(connection: ServerOwnership.Connection): Boolean {
        val password = connection.password ?: return false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        fun remaining(): Long = (deadline - System.nanoTime()).also { check(it > 0) { "Credential verification timed out" } }
        fun probe(route: String, authorization: String?, inspect: (HttpResponse<java.io.InputStream>) -> Boolean): Boolean {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${connection.port}$route"))
                .timeout(Duration.ofNanos(remaining())).GET()
            authorization?.let { builder.header("Authorization", it) }
            val pending = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            try {
                val response = pending.get(remaining(), TimeUnit.NANOSECONDS)
                return response.body().use { inspect(response) }
            } finally { pending.cancel(true) }
        }
        try {
            for (route in listOf("/api/info", "/api/status", "/global/health")) {
                var missing = false
                val anonymousRejected = probe(route, null) { response ->
                    missing = response.statusCode() == 404 || response.headers().firstValue("content-type").orElse("").contains("text/html")
                    response.statusCode() == 401
                }
                if (missing) continue
                if (!anonymousRejected) return false
                val wrong = "varro-check-${UUID.randomUUID()}".let { if (it == password) "$it.invalid" else it }
                if (!probe(route, OpenCodeConnection.authorization(wrong, connection.username)) { it.statusCode() == 401 }) return false
                val authenticated = probe(route, OpenCodeConnection.authorization(password, connection.username)) { response ->
                    missing = response.statusCode() == 404 || response.headers().firstValue("content-type").orElse("").contains("text/html")
                    if (missing || response.statusCode() !in 200..299) return@probe false
                    val body = CompletableFuture.supplyAsync { response.body().readNBytes(65537) }
                    val bytes = try { body.get(remaining(), TimeUnit.NANOSECONDS) } finally { body.cancel(true) }
                    if (bytes.size > 65536) return@probe false
                    val value = Json.parseOrNull(bytes.toString(Charsets.UTF_8)).asObjectOrNull() ?: return@probe false
                    val version = value.str("version") ?: return@probe false
                    if (route == "/global/health") version.startsWith("1.") && value.bool("healthy") == true
                    else version.startsWith("2.") && value.get("pid")?.let {
                        it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble > 0 && it.asDouble == it.asLong.toDouble()
                    } == true
                }
                if (authenticated) return true
                if (!missing) return false
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.util.concurrent.CancellationException("OpenCode credential verification cancelled")
        } catch (_: Exception) {
            // Read-only fallback never retires the original ownership evidence.
        }
        return false
    }
}
