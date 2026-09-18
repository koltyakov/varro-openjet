package varro.server

import com.google.gson.JsonObject
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.str
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Wire-compatible with the Varro family's version 1 leases and claim files. */
internal class ServerOwnership(
    configuredPort: Int,
    directory: Path = sharedDirectory(),
    temporaryDirectory: Path = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")),
    private val inspect: ProcessIdentity = ProcessIdentity(),
) {
    private val host = UUID.randomUUID().toString()
    private val hostPid = ProcessHandle.current().pid()
    private val name = "varro-opencode-server-$configuredPort.json"
    internal val path: Path = directory.resolve(name).let { shared ->
        val legacy = temporaryDirectory.resolve(name)
        if (!Files.exists(shared) && (record(legacy) != null || record(Path.of("$legacy.managed"), false) != null)) legacy else shared
    }
    private val marker = Path.of("$path.managed")
    private val claim = Path.of("$path.claim")

    fun connection(): Pair<Int, String?>? = record(path)?.takeIf(::matches)?.let {
        it.get("port").asInt to it.str("password")
    }

    fun refresh(port: Int, takeover: Boolean = false): Boolean = coordinated {
        val lease = record(path) ?: if (!Files.exists(path)) record(marker, false)?.apply {
            addProperty("version", 1)
            addProperty("state", "relinquished")
        } else null
        if (lease == null || lease.get("port").asInt != port || !matches(lease)) return@coordinated false
        if (lease.str("host") != host && lease.str("state") == "active" && !takeover && hostAlive(lease)) return@coordinated false
        identifyHost(lease)
        write(path, lease)
        true
    } ?: false

    fun register(port: Int, launchedPid: Long, password: String? = null): Boolean = coordinated {
        val pid = inspect.listeners(port).singleOrNull() ?: return@coordinated false
        var ancestor = ProcessHandle.of(pid).orElse(null)
        while (ancestor != null && ancestor.pid() != launchedPid) ancestor = ancestor.parent().orElse(null)
        if (ancestor == null) return@coordinated false
        val existing = record(path)
        if (existing != null && matches(existing)) return@coordinated false
        val lease = Json.obj("version" to 1, "pid" to pid, "port" to port,
            "executable" to inspect.executable(pid), "birthIdentity" to inspect.birth(pid),
            "owner" to UUID.randomUUID().toString(), "createdAt" to System.currentTimeMillis())
        identifyHost(lease)
        password?.let { lease.addProperty("password", it) }
        write(marker, lease.deepCopy().apply {
            listOf("version", "host", "hostPid", "hostBirthIdentity", "state", "password").forEach(::remove)
        })
        write(path, lease)
        true
    } ?: false

    fun relinquish() = coordinated {
        record(path)?.takeIf { it.str("host") == host && matches(it) }?.let {
            it.addProperty("state", "relinquished")
            write(path, it)
        }
    }

    /** Keep the cross-product claim for the entire stop, not just its authorization. */
    fun stop(graceMs: Long): Boolean = coordinated {
        val lease = record(path) ?: return@coordinated false
        if (lease.str("host") != host || lease.str("state") != "active" || !matches(lease)) return@coordinated false
        val process = ProcessHandle.of(lease.get("pid").asLong).orElse(null) ?: return@coordinated false
        process.destroy()
        try { process.onExit().get(graceMs, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.TimeoutException) {
            if (!matches(lease)) return@coordinated false
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.onExit().get(2, TimeUnit.SECONDS)
        }
        Files.deleteIfExists(path)
        if (record(marker, false)?.str("owner") == lease.str("owner")) Files.deleteIfExists(marker)
        true
    } ?: false

    private fun identifyHost(lease: JsonObject) {
        lease.addProperty("host", host)
        lease.addProperty("hostPid", hostPid)
        lease.addProperty("hostBirthIdentity", inspect.birth(hostPid))
        lease.addProperty("state", "active")
    }

    private fun hostAlive(lease: JsonObject): Boolean {
        val pid = lease.get("hostPid")?.asLong ?: return true
        if (!inspect.alive(pid)) return false
        val birth = lease.str("hostBirthIdentity") ?: return true
        return inspect.matchesBirth(birth, inspect.birth(pid), lease.get("createdAt").asLong)
    }

    private fun matches(lease: JsonObject): Boolean {
        val pid = lease.get("pid").asLong
        return pid in inspect.listeners(lease.get("port").asInt) &&
            inspect.normalizeExecutable(lease.str("executable")!!) == inspect.normalizeExecutable(inspect.executable(pid)) &&
            inspect.matchesBirth(lease.str("birthIdentity")!!, inspect.birth(pid), lease.get("createdAt").asLong)
    }

    private fun <T> coordinated(action: () -> T): T? {
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(path.parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else Files.createDirectories(path.parent)
        val own = Json.obj("version" to 1, "host" to host, "hostPid" to hostPid,
            "hostBirthIdentity" to inspect.birth(hostPid), "createdAt" to System.currentTimeMillis())
        try {
            privateWrite(claim, Json.stringify(own))
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            val old = read(claim) ?: return null
            val pid = old.get("hostPid")?.asLong ?: return null
            if (inspect.alive(pid) && (old.str("hostBirthIdentity") == null || hostAlive(old))) return null
            // Only retire the exact claim inspected above.
            if (read(claim) != old) return null
            Files.deleteIfExists(claim)
            try { privateWrite(claim, Json.stringify(own)) }
            catch (_: java.nio.file.FileAlreadyExistsException) { return null }
        }
        try { return action() } finally {
            if (read(claim) == own) Files.deleteIfExists(claim)
        }
    }

    private fun write(target: Path, value: JsonObject) {
        val temporary = Path.of("$target.$host.${UUID.randomUUID()}.tmp")
        try {
            privateWrite(temporary, Json.stringify(value) + "\n")
            for (attempt in 0..3) {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    return
                } catch (failure: java.nio.file.FileSystemException) {
                    if (attempt == 3) throw failure
                    Thread.sleep(25L * (attempt + 1))
                }
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        internal fun sharedDirectory(): Path {
            val home = Path.of(System.getProperty("user.home"))
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") -> home.resolve("Library/Application Support/Varro/servers")
                os.contains("windows") -> (System.getenv("LOCALAPPDATA")?.let(Path::of) ?: home.resolve("AppData/Local")).resolve("Varro/servers")
                else -> (System.getenv("XDG_STATE_HOME")?.let(Path::of) ?: home.resolve(".local/state")).resolve("varro/servers")
            }
        }

        private fun privateWrite(path: Path, text: String) {
            val attributes = if (path.fileSystem.supportedFileAttributeViews().contains("posix"))
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
            Files.newByteChannel(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), *attributes).use {
                val bytes = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) it.write(bytes)
            }
        }

        private fun read(path: Path): JsonObject? = try {
            Json.parseOrNull(Files.readString(path)).asObjectOrNull()
        } catch (_: java.nio.file.NoSuchFileException) { null }

        internal fun record(path: Path, lease: Boolean = true): JsonObject? = runCatching {
            read(path)?.takeIf { value ->
                fun integer(name: String, maximum: Long = 9007199254740991): Boolean {
                    val number = value.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: return false
                    return number.isFinite() && number >= 1 && number <= maximum && number == kotlin.math.floor(number)
                }
                integer("pid") && integer("port", 65535) &&
                    listOf("executable", "birthIdentity", "owner").all { !value.str(it).isNullOrBlank() } &&
                    value.get("createdAt").asJsonPrimitive.isNumber && value.get("createdAt").asDouble.isFinite() &&
                    (!value.has("password") || value.str("password")?.length in 1..4096) &&
                    (!value.has("configPath") || value.get("configPath").asJsonPrimitive.isString) &&
                    (!lease || (integer("version", 1) && !value.str("host").isNullOrBlank() &&
                        value.str("state") in listOf("active", "relinquished") &&
                        value.has("hostPid") == value.has("hostBirthIdentity") &&
                        (!value.has("hostPid") || (integer("hostPid") && !value.str("hostBirthIdentity").isNullOrBlank()))))
            }
        }.getOrNull()
    }
}

internal open class ProcessIdentity {
    private val os = System.getProperty("os.name").lowercase()
    private val windows = os.contains("windows")
    private val mac = os.contains("mac")
    open fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    protected open fun command(vararg arguments: String): String {
        val process = ProcessBuilder(*arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Process inspection timed out")
        }
        check(process.exitValue() in 0..1) { "Process inspection failed" }
        return output.get(2, TimeUnit.SECONDS).trim()
    }
    open fun listeners(port: Int): Set<Long> {
        if (!windows && !mac) {
            val inodes = listOf("tcp", "tcp6").flatMap { protocol ->
                val file = Path.of("/proc/net/$protocol")
                if (!Files.exists(file)) emptyList() else Files.readAllLines(file).drop(1).mapNotNull { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    fields.getOrNull(9)?.takeIf { fields[3] == "0A" && fields[1].substringAfter(':').toInt(16) == port }
                }
            }.toSet()
            if (inodes.isEmpty()) return emptySet()
            return Files.list(Path.of("/proc")).use { processes ->
                processes.toList().mapNotNull { directory ->
                    val pid = directory.fileName.toString().toLongOrNull() ?: return@mapNotNull null
                    val found = runCatching { Files.list(directory.resolve("fd")).use { descriptors ->
                        descriptors.anyMatch { fd -> runCatching { Files.readSymbolicLink(fd).toString() }
                            .getOrNull()?.let { it.startsWith("socket:[") && it.removePrefix("socket:[").removeSuffix("]") in inodes } == true }
                    } }.getOrDefault(false)
                    pid.takeIf { found }
                }.toSet()
            }
        }
        val result = if (windows) command("powershell.exe", "-NoProfile", "-Command",
            "Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess")
        else command("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN", "-t")
        return result.lines().mapNotNull { it.trim().toLongOrNull() }.toSet()
    }
    open fun executable(pid: Long): String {
        val value = when {
            windows -> command("powershell.exe", "-NoProfile", "-Command", "(Get-CimInstance Win32_Process -Filter \"ProcessId = $pid\").ExecutablePath")
            !mac -> Files.readSymbolicLink(Path.of("/proc/$pid/exe")).toString()
            else -> {
                val executable = command("lsof", "-nP", "-a", "-p", "$pid", "-d", "txt", "-Fn")
                    .lines().firstOrNull { it.startsWith("n") }?.drop(1)
                if (executable != null && Files.exists(Path.of(executable))) executable else {
                    val launch = command("ps", "-p", "$pid", "-o", "comm=")
                    runCatching { Path.of(launch).toRealPath().toString() }.getOrDefault(launch)
                }
            }
        }
        return value.also { check(it.isNotBlank()) { "Missing executable for PID $pid" } }
    }
    open fun birth(pid: Long): String = when {
        windows -> "win32:" + command("powershell.exe", "-NoProfile", "-Command",
            "\$p = Get-CimInstance Win32_Process -Filter \"ProcessId = $pid\"; if (\$p) { \$p.CreationDate.ToUniversalTime().Ticks }")
        mac -> "darwin:" + command("ps", "-p", "$pid", "-o", "lstart=").replace(Regex("\\s+"), " ")
        else -> {
            val ticks = Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(") ").split(Regex("\\s+"))[19]
            "linux:${Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim()}:$ticks"
        }
    }.also { check(!it.endsWith(":")) { "Missing start identity for PID $pid" } }
    fun normalizeExecutable(value: String): String = when {
        windows -> value.trim().lowercase()
        mac -> value.trim()
        else -> value.trim().removeSuffix(" (deleted)")
    }
    fun matchesBirth(expected: String, actual: String, createdAt: Long): Boolean {
        if (expected == actual) return true
        if (!expected.matches(Regex("linux:\\d+")) || !actual.startsWith("linux:")) return false
        val uptime = Files.readString(Path.of("/proc/uptime")).substringBefore(' ').toDouble() * 1000
        return expected == "linux:${actual.substringAfterLast(':')}" && createdAt >= System.currentTimeMillis() - uptime
    }
}
