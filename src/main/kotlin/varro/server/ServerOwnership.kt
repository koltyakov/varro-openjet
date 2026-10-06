package varro.server

import com.google.gson.JsonObject
import varro.protocol.Json
import varro.protocol.asObjectOrNull
import varro.protocol.str
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Wire-compatible with the Varro family's version 1 leases and claim files. */
internal class ServerOwnership(
    configuredPort: Int,
    private val directory: Path = sharedDirectory(),
    private val temporaryDirectory: Path = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")),
    private val inspect: ProcessIdentity = ProcessIdentity(),
    private val verifyCredentials: (Connection) -> Boolean = RegisteredConnectionVerifier()::verify,
) {
    internal data class Connection(val port: Int, val password: String?, val username: String = "opencode")
    private val host = UUID.randomUUID().toString()
    private val hostPid = ProcessHandle.current().pid()
    private val name = "varro-opencode-server-$configuredPort.json"
    internal var path: Path = directory.resolve(name).let { shared ->
        val legacy = temporaryDirectory.resolve(name)
        if (System.getenv("VARRO_TEST_STATE_ROOT").isNullOrBlank() && !Files.exists(shared) &&
            (record(legacy) != null || record(Path.of("$legacy.managed"), false) != null || connectionRecord(Path.of("$legacy.credentials")) != null)) legacy else shared
    }
        private set
    private val marker get() = Path.of("$path.managed")
    private val claim get() = Path.of("$path.claim")
    private var launchClaim: JsonObject? = null
    private data class Attachment(val source: Path, val record: JsonObject, val connection: Connection)
    @Volatile private var attachment: Attachment? = null
    @Volatile private var admitted: JsonObject? = null
    private var verifiedAt = 0L
    val credentialOnly: Boolean get() = attachment != null
    val registeredPid: Long? get() = admitted?.get("pid")?.asLong

    /** Discovery keeps the original coordination key, even when another editor configured it. */
    @Synchronized fun connection(port: Int? = null, password: String? = null): Connection? {
        val candidates = linkedSetOf(path)
        if (port != null) {
            val directories = if (System.getenv("VARRO_TEST_STATE_ROOT").isNullOrBlank()) listOf(directory, temporaryDirectory) else listOf(directory)
            directories.distinct().filter { Files.exists(it) }.forEach { root ->
                Files.list(root).use { files -> files.filter {
                    it.fileName.toString().matches(Regex("varro-opencode-server-\\d+\\.json(?:\\.managed|\\.credentials)?"))
                }.sorted().forEach { candidates.add(Path.of(it.toString().removeSuffix(".managed").removeSuffix(".credentials"))) } }
            }
        }
        val records = candidates.mapNotNull { candidate ->
            val lease = record(candidate)
            val value = lease ?: if (!Files.exists(candidate)) record(Path.of("$candidate.managed"), false) else null
            value?.takeIf { port == null || it.get("port").asInt == port }?.let { candidate to it }
        }
        val matching = records.filter { (_, value) -> runCatching { matches(value) }.getOrDefault(false) }
        check(matching.size <= 1) { "Conflicting Varro server registrations; ownership records were left untouched" }
        matching.singleOrNull()?.let { (candidate, value) ->
            check(launchClaim == null || path == candidate) { "Cannot change the Varro coordination key during a launch" }
            path = candidate
            attachment = null
            admitted = value.deepCopy()
            val companion = connectionRecord(Path.of("$candidate.credentials"))
                ?.takeIf { it.str("owner") == value.str("owner") && it.get("port") == value.get("port") }
            return if (value.str("password") == null && companion != null) credentials(companion) else credentials(value)
        }
        // A private credential proves attachment only, never authority over a replacement PID.
        for (candidate in candidates) {
            val values = listOfNotNull(connectionRecord(Path.of("$candidate.credentials")), record(candidate))
            for (value in values) {
                val connection = credentials(value)
                if (port != null && connection.port != port && (password == null || password != connection.password)) continue
                val target = if (port == null) connection else connection.copy(port = port)
                if (target.password == null || !verifyCredentials(target)) continue
                val source = if (value.has("pid")) candidate else Path.of("$candidate.credentials")
                if (read(source) != value) continue
                check(launchClaim == null || path == candidate) { "Cannot change the Varro coordination key during a launch" }
                path = candidate
                attachment = Attachment(source, value.deepCopy(), target)
                admitted = null
                verifiedAt = System.currentTimeMillis()
                return target
            }
        }
        attachment = null
        admitted = null
        return null
    }

    @Synchronized fun verifyConnection(port: Int, reconnect: Boolean = false) {
        val saved = attachment ?: return
        check(port == saved.connection.port && read(saved.source) == saved.record) { "Registered OpenCode endpoint or credentials changed; reconnect to verify them" }
        if (!reconnect && System.currentTimeMillis() - verifiedAt < 1000) return
        check(verifyCredentials(saved.connection) && read(saved.source) == saved.record) {
            "Registered OpenCode connection changed; reconnect to verify it"
        }
        verifiedAt = System.currentTimeMillis()
    }

    /** Host handoff is harmless; a changed listener or launch registration needs fresh startup. */
    @Synchronized fun registrationChanged(): Boolean {
        val expected = admitted ?: return false
        val recorded = record(path) ?: if (!Files.exists(path)) record(marker, false) else return false
        if (recorded != null && listOf("owner", "pid", "port", "executable", "birthIdentity").any { recorded.get(it) != expected.get(it) }) return true
        return !matches(expected)
    }

    @Synchronized fun refresh(port: Int, takeover: Boolean = false, password: String? = null, username: String = "opencode"): Boolean {
        if (credentialOnly) return false
        return coordinated {
            val lease = record(path) ?: if (!Files.exists(path)) record(marker, false)?.apply {
                addProperty("version", 1)
                addProperty("state", "relinquished")
                password?.let { addProperty("password", it) }
                if (password != null && username != "opencode") addProperty("username", username)
            } else null
            if (lease == null || lease.get("port").asInt != port || !matches(lease)) return@coordinated false
            if (lease.str("host") != host && lease.str("state") == "active" && !takeover && hostAlive(lease)) return@coordinated false
            identifyHost(lease)
            write(path, lease)
            admitted = lease.deepCopy()
            true
        } ?: false
    }

    @Synchronized fun register(port: Int, launchedPid: Long, password: String? = null, username: String = "opencode"): Boolean = coordinated {
        val pid = inspect.listeners(port).singleOrNull() ?: return@coordinated false
        var ancestor = ProcessHandle.of(pid).orElse(null)
        while (ancestor != null && ancestor.pid() != launchedPid) ancestor = ancestor.parent().orElse(null)
        if (ancestor == null) return@coordinated false
        val existing = record(path)
        if (existing != null && !retired(existing)) return@coordinated false
        val lease = Json.obj("version" to 1, "pid" to pid, "port" to port,
            "executable" to inspect.executable(pid), "birthIdentity" to inspect.birth(pid),
            "owner" to UUID.randomUUID().toString(), "createdAt" to System.currentTimeMillis())
        identifyHost(lease)
        password?.let { lease.addProperty("password", it) }
        if (username != "opencode") lease.addProperty("username", username)
        write(marker, lease.deepCopy().apply {
            listOf("version", "host", "hostPid", "hostBirthIdentity", "state", "password", "username").forEach(::remove)
        })
        write(path, lease)
        attachment = null
        admitted = lease.deepCopy()
        password?.let {
            runCatching { writeConnection(port, it, username, lease.str("owner")!!) }
                .onFailure { failure -> com.intellij.openapi.diagnostic.logger<ServerOwnership>().warn("Could not retain independent OpenCode connection credentials", failure) }
        }
        true
    } ?: false

    @Synchronized fun retainConnection(port: Int, password: String, username: String): Boolean {
        val connection = Connection(port, password, username)
        if (!verifyCredentials(connection)) return false
        return coordinated {
            val value = writeConnection(port, password, username, UUID.randomUUID().toString())
            attachment = Attachment(Path.of("$path.credentials"), value, connection)
            admitted = null
            verifiedAt = System.currentTimeMillis()
            true
        } ?: false
    }

    private fun writeConnection(port: Int, password: String, username: String, owner: String): JsonObject =
        Json.obj("version" to 1, "port" to port, "password" to password, "owner" to owner,
            "createdAt" to System.currentTimeMillis()).apply {
            if (username != "opencode") addProperty("username", username)
            write(Path.of("$path.credentials"), this)
        }

    /** Hold the family claim across spawn, confirmation and publication. */
    @Synchronized fun beginLaunch(): Boolean {
        check(launchClaim == null) { "An OpenCode launch is already in progress" }
        val own = acquireClaim() ?: return false
        launchClaim = own
        return true
    }

    @Synchronized fun assertLaunchAllowed() {
        check(launchClaim != null && read(claim) == launchClaim) { "OpenCode launch requires the shared Varro claim" }
        val existing = record(path) ?: if (!Files.exists(path)) record(marker, false) else error("Invalid OpenCode ownership record; it was left untouched")
        check(existing != null || !Files.exists(marker)) { "Invalid OpenCode ownership marker; it was left untouched" }
        check(existing == null || retired(existing)) { "A registered OpenCode process is still alive or cannot be verified; it was left untouched" }
    }

    @Synchronized fun endLaunch() {
        launchClaim?.let { if (read(claim) == it) Files.deleteIfExists(claim) }
        launchClaim = null
    }

    @Synchronized fun relinquish() = coordinated {
        if (credentialOnly) return@coordinated
        record(path)?.takeIf { it.str("host") == host && matches(it) }?.let {
            it.addProperty("state", "relinquished")
            write(path, it)
        }
    }

    /** Keep the cross-product claim for the entire stop, not just its authorization. */
    @Synchronized fun stop(graceMs: Long): Boolean = coordinated {
        if (credentialOnly) return@coordinated false
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
        val companion = Path.of("$path.credentials")
        if (connectionRecord(companion)?.str("owner") == lease.str("owner")) Files.deleteIfExists(companion)
        admitted = null
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
        return inspect.listeners(lease.get("port").asInt) == setOf(pid) &&
            inspect.normalizeExecutable(lease.str("executable")!!) == inspect.normalizeExecutable(inspect.executable(pid)) &&
            inspect.matchesBirth(lease.str("birthIdentity")!!, inspect.birth(pid), lease.get("createdAt").asLong)
    }

    private fun retired(lease: JsonObject): Boolean {
        val pid = lease.get("pid").asLong
        return !inspect.alive(pid) || !inspect.matchesBirth(lease.str("birthIdentity")!!, inspect.birth(pid), lease.get("createdAt").asLong)
    }

    private fun acquireClaim(): JsonObject? {
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(path.parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else Files.createDirectories(path.parent)
        check(!Files.isSymbolicLink(path.parent)) { "OpenCode ownership directory must not be a symlink" }
        if (path.parent != temporaryDirectory && path.fileSystem.supportedFileAttributeViews().contains("unix")) {
            val uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid")
            val mode = Files.getAttribute(path.parent, "unix:mode", NOFOLLOW_LINKS) as Int
            check(Files.getAttribute(path.parent, "unix:uid", NOFOLLOW_LINKS) == uid && mode and 63 == 0) {
                "OpenCode ownership directory is not private to this OS user; it was left untouched"
            }
        }
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
        return own
    }

    private fun <T> coordinated(action: () -> T): T? {
        launchClaim?.let { check(read(claim) == it) { "OpenCode launch claim changed" }; return action() }
        val own = acquireClaim() ?: return null
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
            System.getenv("VARRO_TEST_STATE_ROOT")?.takeIf { it.isNotBlank() }?.let {
                val root = Path.of(it)
                require(root.isAbsolute) { "VARRO_TEST_STATE_ROOT must be absolute" }
                return root.resolve("servers")
            }
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
            validatePrivateRecord(path)
            check(Files.size(path) <= 65536) { "OpenCode ownership record is too large" }
            Json.parseOrNull(Files.readString(path)).asObjectOrNull()
        } catch (_: java.nio.file.NoSuchFileException) { null }

        internal fun record(path: Path, lease: Boolean = true): JsonObject? = runCatching {
            read(path)?.takeIf { value ->
                fun integer(name: String, maximum: Long = 9007199254740991): Boolean {
                    val number = value.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: return false
                    return number.isFinite() && number >= 1 && number <= maximum && number == kotlin.math.floor(number)
                }
                integer("pid") && integer("port", 65535) &&
                    listOf("executable", "birthIdentity", "owner").all { nonemptyString(value, it) } &&
                    value.get("createdAt").asJsonPrimitive.isNumber && value.get("createdAt").asDouble.isFinite() &&
                    validPassword(value) &&
                    validUsername(value) &&
                    (!value.has("portMode") || value.str("portMode") in listOf("auto", "fixed")) &&
                    (!value.has("configPath") || value.get("configPath").asJsonPrimitive.isString) &&
                    (!lease || (integer("version", 1) && nonemptyString(value, "host") &&
                        value.str("state") in listOf("active", "relinquished") &&
                        value.has("hostPid") == value.has("hostBirthIdentity") &&
                        (!value.has("hostPid") || (integer("hostPid") && nonemptyString(value, "hostBirthIdentity")))))
            }
        }.getOrNull()

        private fun credentials(value: JsonObject) = Connection(value.get("port").asInt, value.str("password"), value.str("username") ?: "opencode")

        private fun nonemptyString(value: JsonObject, name: String): Boolean = value.get(name)?.let {
            it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank()
        } == true

        private fun validPassword(value: JsonObject): Boolean = !value.has("password") || value.get("password").let {
            it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.length in 1..4096
        }

        private fun validUsername(value: JsonObject): Boolean = !value.has("username") ||
            (value.get("username").asJsonPrimitive.isString && !value.str("username").isNullOrBlank() && ':' !in value.str("username")!!)

        internal fun connectionRecord(path: Path): JsonObject? = runCatching {
            read(path)?.takeIf { value ->
                value.get("version")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble == 1.0 } == true && value.get("port")?.let {
                    it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble == it.asInt.toDouble() && it.asInt in 1..65535
                } == true && nonemptyString(value, "owner") && value.get("createdAt")?.let {
                    it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite()
                } == true && value.has("password") && validPassword(value) && validUsername(value)
            }
        }.getOrNull()

        private fun validatePrivateRecord(path: Path) {
            if (!Files.exists(path, NOFOLLOW_LINKS)) return
            check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "OpenCode ownership record is not a regular private file" }
            if (path.fileSystem.supportedFileAttributeViews().contains("unix")) {
                val uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid")
                val mode = Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Int
                check(Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) == uid && mode and 63 == 0) {
                    "OpenCode ownership record is not private to this OS user; it was left untouched"
                }
            }
        }
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
