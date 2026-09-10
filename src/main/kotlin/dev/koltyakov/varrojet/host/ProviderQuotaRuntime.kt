package dev.koltyakov.varrojet.host

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Node discovery and extraction of the bundled helper, independent of JCEF. */
object ProviderQuotaRuntime {
    fun launch(configuredNode: String, environment: Map<String, String>, searchPaths: List<String>): Process {
        val node = findNode(configuredNode, environment, searchPaths)
        val directory = Files.createTempDirectory("varro-provider-quota-")
        val script = directory.resolve("provider-quota.mjs")
        try {
            ProviderQuotaRuntime::class.java.getResourceAsStream("/quota/provider-quota.mjs")
                ?.use { Files.copy(it, script) }
                ?: error("Provider quota helper is missing from the plugin. Rebuild or reinstall Varro OpenJet.")
            val process = ProcessBuilder(node, script.toString())
                .directory(directory.toFile())
                // stdout carries JSON; upstream diagnostics must not enter that channel.
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment().putAll(environment) }
                .start()
            process.onExit().thenRun {
                runCatching { Files.deleteIfExists(script) }
                runCatching { Files.deleteIfExists(directory) }
            }
            return process
        } catch (failure: Exception) {
            Files.deleteIfExists(script)
            Files.deleteIfExists(directory)
            throw failure
        }
    }

    internal fun findNode(configuredNode: String, environment: Map<String, String>, searchPaths: List<String>): String {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val name = if (windows) "node.exe" else "node"
        val extra = listOfNotNull(
            environment["ProgramFiles"]?.let { Path.of(it, "nodejs").toString() },
            environment["LOCALAPPDATA"]?.let { Path.of(it, "Programs", "nodejs").toString() },
        )
        val candidates = if (configuredNode.isNotBlank()) listOf(configuredNode.trim())
        else (searchPaths + extra).distinct().map { File(it, name).absolutePath }
        for (candidate in candidates) {
            if (!Files.isRegularFile(Path.of(candidate))) continue
            val supported = runCatching {
                val process = ProcessBuilder(candidate, "--version")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .also { it.environment().putAll(environment) }
                    .start()
                try {
                    process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                        (process.inputStream.bufferedReader().readText().trim()
                            .removePrefix("v").substringBefore('.').toIntOrNull() ?: 0) >= 22
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }.getOrDefault(false)
            if (supported) return candidate
        }
        error("Provider quotas require Node.js 22 or newer. Install Node.js or set its path in Settings | Tools | Varro.")
    }
}
