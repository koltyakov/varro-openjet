package varro.server

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import varro.protocol.str
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ServerOwnershipTest {
    private class Identity : ProcessIdentity() {
        var serverBirth = "darwin:original-start"
        var fail = false
        var listening = setOf(123L)
        var dead = setOf(999L)
        var accountValue: Account? = null
        var onListeners: (() -> Unit)? = null
        var onExecutable: (() -> Unit)? = null
        var onBirth: (() -> Unit)? = null
        override fun listeners(port: Int): Set<Long> {
            check(!fail) { "inspection failed" }
            onListeners?.invoke()
            return listening
        }
        override fun executable(pid: Long): String { onExecutable?.invoke(); return "/usr/bin/opencode" }
        override fun birth(pid: Long): String {
            if (pid == 123L) onBirth?.invoke()
            return if (pid == 123L) serverBirth else "darwin:host-start"
        }
        override fun alive(pid: Long) = pid !in dead
        override fun account(pid: Long) = accountValue ?: Account(true, "$pid:${birth(pid)}:fixture-user")
    }

    private fun fixture(action: (Path, Path, Identity) -> Unit) {
        val root = Files.createTempDirectory("ownership-test-")
        try {
            val shared = Files.createDirectory(root.resolve("shared"))
            if (shared.fileSystem.supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwx------"))
            action(shared, Files.createDirectory(root.resolve("legacy")), Identity())
        }
        finally { root.toFile().deleteRecursively() }
    }

    private fun marker() = Json.obj("pid" to 123, "port" to 4096, "executable" to "/usr/bin/opencode",
        "birthIdentity" to "darwin:original-start", "owner" to "varro-server-nonce", "createdAt" to System.currentTimeMillis())

    private fun privateFile(path: Path, text: String): Path = Files.writeString(path, text).also {
        if (it.fileSystem.supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-------"))
    }

    @Test fun `legacy marker recovers missing lease and rejects PID reuse`() = fixture { shared, legacy, identity ->
        val path = legacy.resolve("varro-opencode-server-4096.json")
        privateFile(Path.of("$path.managed"), Json.stringify(marker()))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertEquals(path, manager.path)
        assertTrue(manager.refresh(4096))
        assertEquals("varro-server-nonce", ServerOwnership.record(path).str("owner"))
        identity.serverBirth = "darwin:reused-pid"
        assertFalse(manager.refresh(4096))
    }

    @Test fun `products share ownership and handoff without trusting cached host`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(Path.of("$path.managed"), Json.stringify(marker()))
        val first = ServerOwnership(4096, shared, legacy, identity)
        val second = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(first.refresh(4096))
        assertFalse(second.refresh(4096))
        first.relinquish()
        assertTrue(second.refresh(4096))
        assertFalse(first.stop(1))
        first.relinquish()
        assertEquals("active", ServerOwnership.record(path).str("state"))
        assertTrue(first.refresh(4096, takeover = true))
        assertFalse(second.refresh(4096))
    }

    @Test fun `crashed host recovery preserves password and foreign config`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(path, Json.stringify(marker().apply {
            addProperty("version", 1); addProperty("host", "vscode-host"); addProperty("hostPid", 999)
            addProperty("hostBirthIdentity", "darwin:dead-host"); addProperty("state", "active")
            addProperty("password", "secret"); addProperty("configPath", "/foreign/opencode.json")
        }))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(manager.refresh(4096))
        assertEquals(ServerOwnership.Connection(4096, "secret"), manager.connection())
        assertEquals("/foreign/opencode.json", ServerOwnership.record(path).str("configPath"))
    }

    @Test fun `competing products elect one owner`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(Path.of("$path.managed"), Json.stringify(marker()))
        val managers = List(8) { ServerOwnership(4096, shared, legacy, identity) }
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(managers.size)
        try {
            val results = managers.map { manager -> pool.submit<Boolean> { start.await(); manager.refresh(4096) } }
            start.countDown()
            assertEquals(1, results.count { it.get(5, java.util.concurrent.TimeUnit.SECONDS) })
            assertNotNull(ServerOwnership.record(path))
            assertFalse(Files.exists(Path.of("$path.claim")))
        } finally { pool.shutdownNow() }
    }

    @Test fun `removed runtime config does not block handoff or relax process verification`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        val config = shared.resolve("removed-runtime-config/opencode.json")
        privateFile(path, Json.stringify(lease().apply { addProperty("configPath", config.toString()) }))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertFalse(Files.exists(config))
        assertTrue(manager.refresh(4096))
        assertEquals(ServerOwnership.Connection(4096, "secret", "varro-user"), manager.connection())
        identity.serverBirth = "darwin:reused-pid"
        assertFalse(manager.refresh(4096, takeover = true))
        assertEquals(config.toString(), ServerOwnership.record(path).str("configPath"))
    }

    @Test fun `inspection failures and malformed records preserve evidence`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(Path.of("$path.managed"), Json.stringify(marker()))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(manager.refresh(4096))
        val before = Files.readString(path)
        identity.fail = true
        assertThrows(IllegalStateException::class.java) { manager.refresh(4096) }
        assertEquals(before, Files.readString(path))
        privateFile(path, "{broken")
        identity.fail = false
        assertFalse(manager.refresh(4096))
        assertEquals("{broken", Files.readString(path))
    }

    private fun lease() = marker().apply {
        addProperty("version", 1); addProperty("host", "vscode-host"); addProperty("hostPid", 999)
        addProperty("hostBirthIdentity", "darwin:dead-host"); addProperty("state", "active")
        addProperty("password", "secret"); addProperty("username", "varro-user")
    }

    private class StoppingProcess : ProcessHandle {
        var alive = true
        var signals = 0
        var forced = 0
        override fun pid() = 123L
        override fun isAlive() = alive
        override fun destroy(): Boolean { signals++; return true }
        override fun destroyForcibly(): Boolean { forced++; alive = false; return true }
        override fun onExit() = if (alive) java.util.concurrent.CompletableFuture<ProcessHandle>()
            else java.util.concurrent.CompletableFuture.completedFuture<ProcessHandle>(this)
        override fun descendants(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()
        override fun children(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()
        override fun parent(): java.util.Optional<ProcessHandle> = java.util.Optional.empty()
        override fun info(): ProcessHandle.Info = error("Unexpected process info read")
        override fun supportsNormalTermination() = true
        override fun compareTo(other: ProcessHandle) = pid().compareTo(other.pid())
    }

    @Test fun `post-signal identity failures accept confirmed exit but retain live unverifiable leases`() {
        for (missing in listOf("executable", "birth")) for (exited in listOf(false, true)) fixture { shared, legacy, identity ->
            val process = StoppingProcess()
            val manager = ServerOwnership(4096, shared, legacy, identity, processHandle = { assertEquals(123L, it); process })
            privateFile(Path.of("${manager.path}.managed"), Json.stringify(marker()))
            assertTrue(manager.refresh(4096))
            val before = Files.readString(manager.path)
            val failRead = {
                if (process.signals > 0) {
                    if (exited) process.alive = false
                    error("Cannot verify $missing identity")
                }
            }
            if (missing == "executable") identity.onExecutable = failRead else identity.onBirth = failRead
            if (exited) {
                assertTrue(manager.stop(0))
                assertFalse(Files.exists(manager.path))
                assertFalse(Files.exists(Path.of("${manager.path}.managed")))
            } else {
                assertThrows(IllegalStateException::class.java) { manager.stop(0) }
                assertEquals(before, Files.readString(manager.path))
                assertTrue(Files.exists(Path.of("${manager.path}.managed")))
            }
            assertEquals(1, process.signals)
            assertEquals(0, process.forced)
            assertFalse(Files.exists(Path.of("${manager.path}.claim")))
        }
    }

    @Test fun `pre-signal identity failures never authorize termination`() = fixture { shared, legacy, identity ->
        val process = StoppingProcess()
        val manager = ServerOwnership(4096, shared, legacy, identity, processHandle = { process })
        privateFile(Path.of("${manager.path}.managed"), Json.stringify(marker()))
        assertTrue(manager.refresh(4096))
        val before = Files.readString(manager.path)
        identity.onExecutable = { error("Cannot verify executable identity") }
        assertThrows(IllegalStateException::class.java) { manager.stop(0) }
        assertEquals(0, process.signals)
        assertEquals(0, process.forced)
        assertEquals(before, Files.readString(manager.path))
    }

    @Test fun `credential verification and unchanged registration refresh serialize without false invalidation`() {
        for (companion in listOf(false, true)) fixture { shared, legacy, identity ->
            val path = shared.resolve("varro-opencode-server-4096.json")
            privateFile(if (companion) Path.of("$path.credentials") else path, Json.stringify(if (companion)
                Json.obj("version" to 1, "port" to 4096, "owner" to "launch", "createdAt" to 1, "password" to "secret") else lease()))
            identity.serverBirth = "darwin:replacement"
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            var block = false
            val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = {
                if (block) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                true
            })
            assertNotNull(manager.connection())
            block = true
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val verification = pool.submit { manager.verifyConnection(4096, reconnect = true) }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val refresh = pool.submit<ServerOwnership.Connection?> { manager.connection() }
                release.countDown()
                verification.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("secret", refresh.get(5, java.util.concurrent.TimeUnit.SECONDS)!!.password)
                assertTrue(manager.credentialOnly)
                assertNull(manager.registeredPid)
                assertFalse(manager.refresh(4096, takeover = true))
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun `credential evidence changed during verification is rejected`() {
        for (field in listOf("password", "username", "port")) fixture { shared, legacy, identity ->
            val path = shared.resolve("varro-opencode-server-4096.json")
            val value = lease()
            privateFile(path, Json.stringify(value))
            identity.serverBirth = "darwin:replacement"
            var mutate = false
            val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = {
                if (mutate) privateFile(path, Json.stringify(value.deepCopy().apply {
                    if (field == "port") addProperty(field, 5001) else addProperty(field, "changed")
                }))
                true
            })
            assertNotNull(manager.connection())
            mutate = true
            assertThrows(IllegalStateException::class.java) { manager.verifyConnection(4096, reconnect = true) }
            assertNull(manager.registeredPid)
            assertFalse(manager.refresh(4096, takeover = true))
        }
    }

    @Test fun `discovery retains the VS Code coordination key and username`() = fixture { shared, legacy, identity ->
        val original = shared.resolve("varro-opencode-server-5000.json")
        privateFile(original, Json.stringify(lease()))
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertEquals(ServerOwnership.Connection(4096, "secret", "varro-user"), manager.connection(4096, "secret"))
        assertEquals(original, manager.path)
        assertTrue(manager.refresh(4096))
        assertFalse(Files.exists(shared.resolve("varro-opencode-server-4096.json")))
    }

    @Test fun `replacement credentials allow attachment without ownership or record changes`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        val original = Json.stringify(lease())
        privateFile(path, original)
        identity.serverBirth = "darwin:replacement"
        val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { it.password == "secret" })
        assertEquals(ServerOwnership.Connection(4096, "secret", "varro-user"), manager.connection())
        assertTrue(manager.credentialOnly)
        assertNull(manager.registeredPid)
        assertFalse(manager.refresh(4096, takeover = true))
        assertFalse(manager.stop(1))
        assertThrows(IllegalStateException::class.java) { manager.verifyConnection(5000) }
        manager.relinquish()
        assertEquals(original, Files.readString(path))
        privateFile(path, Json.stringify(lease().apply { addProperty("password", "changed") }))
        assertThrows(IllegalStateException::class.java) { manager.verifyConnection(4096) }
    }

    @Test fun `discovered V2 replacements recover the original key without obsolete config`() {
        for (mode in listOf("different-pid", "reused-pid", "unknown-account", "multiple-records")) fixture { shared, legacy, identity ->
            val original = shared.resolve("varro-opencode-server-5000.json")
            val oldLease = lease().apply { addProperty("configPath", "/foreign/retired.json"); addProperty("portMode", "auto") }
            privateFile(original, Json.stringify(oldLease))
            var selected = original
            if (mode == "multiple-records") {
                selected = shared.resolve("varro-opencode-server-5002.json")
                privateFile(selected, Json.stringify(oldLease.deepCopy().apply {
                    addProperty("owner", "newer-owner"); addProperty("createdAt", oldLease.get("createdAt").asLong + 1)
                }))
            }
            identity.listening = if (mode == "reused-pid") setOf(123) else setOf(456)
            identity.serverBirth = "darwin:replacement"
            if (mode != "reused-pid") identity.dead += 123L
            if (mode == "unknown-account") identity.accountValue = ProcessIdentity.Account(null)
            var probes = 0
            val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { fail("Must recover before credential-only fallback"); false },
                verifyReplacement = { connection, pid, _ ->
                    probes++
                    assertEquals(ServerOwnership.Connection(5001, "secret", "varro-user"), connection)
                    assertEquals(identity.listening.single(), pid)
                    true
                })
            assertEquals(ServerOwnership.Connection(5001, "secret", "varro-user"), manager.connection(5001, "secret", recoverReplacement = true))
            assertEquals(1, probes)
            assertEquals(selected, manager.path)
            assertFalse(manager.credentialOnly)
            assertEquals(identity.listening.single(), manager.registeredPid)
            val recovered = ServerOwnership.record(selected)!!
            assertEquals(5001, recovered.get("port").asInt)
            assertEquals("active", recovered.str("state"))
            assertEquals("auto", recovered.str("portMode"))
            assertNotEquals(oldLease.str("owner"), recovered.str("owner"))
            assertFalse(recovered.has("configPath"))
            assertFalse(ServerOwnership.record(Path.of("$selected.managed"), false)!!.has("password"))
            assertFalse(Files.exists(Path.of("$selected.claim")))
            assertTrue(manager.refresh(5001))
            assertFalse(Files.exists(shared.resolve("varro-opencode-server-4096.json")))
            if (mode == "multiple-records") assertEquals(oldLease, ServerOwnership.record(original))
            val follower = ServerOwnership(5000, shared, legacy, identity)
            assertEquals(ServerOwnership.Connection(5001, "secret", "varro-user"), follower.connection(5001, "secret"))
            assertFalse(follower.refresh(5001))
            manager.relinquish()
            assertTrue(follower.refresh(5001))
        }
    }

    @Test fun `replacement recovery requires private retired provenance and exact listener account credentials`() {
        for (mode in listOf("live-original", "unknown-original", "multiple-listeners", "foreign-user", "missing-account-identity",
            "wrong-account-identity", "wrong-password", "wrong-executable", "public-record", "failed-HTTP", "missing-record")) fixture { shared, legacy, identity ->
            val path = shared.resolve("varro-opencode-server-4096.json")
            val oldLease = lease()
            if (mode == "wrong-executable") oldLease.addProperty("executable", "/different/opencode")
            if (mode != "missing-record") privateFile(path, Json.stringify(oldLease))
            identity.listening = if (mode == "multiple-listeners") setOf(456, 789) else setOf(456)
            if (mode != "live-original" && mode != "unknown-original") identity.dead += 123L
            if (mode == "unknown-original") identity.fail = true
            identity.accountValue = when (mode) {
                "foreign-user" -> ProcessIdentity.Account(false, "456:darwin:host-start:other-user")
                "missing-account-identity" -> ProcessIdentity.Account(true)
                "wrong-account-identity" -> ProcessIdentity.Account(true, "789:darwin:host-start:fixture-user")
                else -> null
            }
            if (mode == "public-record" && path.fileSystem.supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
            }
            val before = if (Files.exists(path)) Files.readString(path) else null
            var probes = 0
            val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { false },
                verifyReplacement = { _, _, _ -> probes++; false })
            assertNull(mode, manager.connection(5001, if (mode == "wrong-password") "different" else "secret", recoverReplacement = true))
            assertEquals(mode, if (mode == "failed-HTTP" || mode == "public-record" && !path.fileSystem.supportedFileAttributeViews().contains("posix")) 1 else 0, probes)
            assertNull(manager.registeredPid)
            assertEquals(before, if (Files.exists(path)) Files.readString(path) else null)
            assertFalse(Files.exists(Path.of("$path.managed")))
        }
    }

    @Test fun `replacement recovery does not publish through busy claims changed records or cancellation`() {
        for (mode in listOf("busy-claim", "changed-record", "changed-listener", "cancelled")) fixture { shared, legacy, identity ->
            val path = shared.resolve("varro-opencode-server-4096.json")
            privateFile(path, Json.stringify(lease()))
            identity.listening = setOf(456)
            identity.dead += 123L
            val owner = ServerOwnership(4096, shared, legacy, identity)
            if (mode == "busy-claim") assertTrue(owner.beginLaunch())
            var cancelled = false
            var expected = Files.readString(path)
            val manager = ServerOwnership(4096, shared, legacy, identity, verifyReplacement = { _, _, _ ->
                when (mode) {
                    "changed-record" -> { privateFile(path, Json.stringify(lease().apply { addProperty("owner", "changed-owner") })); expected = Files.readString(path) }
                    "changed-listener" -> identity.listening = setOf(789)
                    "cancelled" -> cancelled = true
                }
                true
            })
            try {
                if (mode == "cancelled") assertThrows(java.util.concurrent.CancellationException::class.java) {
                    manager.connection(5001, "secret", recoverReplacement = true, isCancelled = { cancelled })
                } else assertThrows(IllegalStateException::class.java) { manager.connection(5001, "secret", recoverReplacement = true) }
                assertEquals(expected, Files.readString(path))
                assertFalse(Files.exists(Path.of("$path.managed")))
                assertNull(manager.registeredPid)
            } finally { if (mode == "busy-claim") owner.endLaunch() }
        }
    }

    @Test fun `failed inspection can attach using a private credential companion`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json.credentials")
        val value = Json.obj("version" to 1, "port" to 4096, "owner" to "launch", "createdAt" to 1,
            "password" to "secret", "username" to "varro-user")
        privateFile(path, Json.stringify(value))
        identity.fail = true
        val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { true })
        assertEquals(ServerOwnership.Connection(4096, "secret", "varro-user"), manager.connection())
        assertTrue(manager.credentialOnly)
        assertFalse(Files.exists(manager.path))
    }

    @Test fun `changed discovery port requires exact saved credentials`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(path, Json.stringify(lease()))
        identity.serverBirth = "darwin:replacement"
        val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { it.port == 5001 && it.password == "secret" })
        assertNull(manager.connection(5001, "unrelated"))
        assertEquals(ServerOwnership.Connection(5001, "secret", "varro-user"), manager.connection(5001, "secret"))
        assertTrue(manager.credentialOnly)
        assertFalse(manager.refresh(5001, takeover = true))
        assertEquals(4096, ServerOwnership.record(path)?.get("port")?.asInt)
    }

    @Test fun `changed-port credential recheck preserves the selected endpoint during fresh stream admission`() {
        for (changed in listOf(false, true)) fixture { shared, legacy, identity ->
            val path = shared.resolve("varro-opencode-server-4096.json.credentials")
            val value = Json.obj("version" to 1, "port" to 4096, "owner" to "launch", "createdAt" to 1, "password" to "secret")
            privateFile(path, Json.stringify(value))
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val probes = java.util.concurrent.CopyOnWriteArrayList<Int>()
            var block = false
            val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = {
                probes.add(it.port)
                if (block) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                it.port == 5001 && it.password == "secret"
            })
            assertEquals(ServerOwnership.Connection(5001, "secret"), manager.connection(5001, "secret"))
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                block = true
                val admission = pool.submit { manager.verifyConnection(5001, reconnect = true) }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val preparation = pool.submit<Boolean> { manager.recheckAttachment(5001) }
                if (changed) privateFile(path, Json.stringify(value.apply { addProperty("password", "changed") }))
                val expectedRecord = Files.readString(path)
                release.countDown()
                if (changed) {
                    val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                        admission.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    assertTrue(failure.cause is IllegalStateException)
                } else admission.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertFalse(preparation.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(probes.isNotEmpty() && probes.all { it == 5001 })
                assertTrue(manager.credentialOnly)
                assertNull(manager.registeredPid)
                assertFalse(manager.refresh(5001, takeover = true))
                assertFalse(manager.stop(0))
                assertEquals(expectedRecord, Files.readString(path))
                assertFalse(Files.exists(manager.path))
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun `ambiguous listeners and nonprivate credentials cannot grant ownership`() = fixture { shared, legacy, identity ->
        val path = shared.resolve("varro-opencode-server-4096.json")
        privateFile(path, Json.stringify(lease()))
        identity.listening = setOf(123, 456)
        val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { false })
        assertNull(manager.connection())
        assertFalse(manager.refresh(4096, takeover = true))
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
            assertNull(ServerOwnership.record(path))
            assertNull(manager.connection())
        }
    }

    @Test fun `the family launch claim stays held and uncertain processes block replacement`() = fixture { shared, legacy, identity ->
        val first = ServerOwnership(4096, shared, legacy, identity)
        val second = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(first.beginLaunch())
        assertFalse(second.beginLaunch())
        first.assertLaunchAllowed()
        privateFile(first.path, Json.stringify(lease()))
        assertThrows(IllegalStateException::class.java) { first.assertLaunchAllowed() }
        first.endLaunch()
        assertTrue(second.beginLaunch())
        identity.dead += 123L
        second.assertLaunchAllowed()
        second.endLaunch()
        assertFalse(Files.exists(Path.of("${first.path}.claim")))
    }

    @Test fun `corrupt records are retained and cannot authorize a launch`() = fixture { shared, legacy, identity ->
        val manager = ServerOwnership(4096, shared, legacy, identity)
        privateFile(manager.path, "{broken")
        assertTrue(manager.beginLaunch())
        try { assertThrows(IllegalStateException::class.java) { manager.assertLaunchAllowed() } }
        finally { manager.endLaunch() }
        assertEquals("{broken", Files.readString(manager.path))
    }

    @Test fun `credential-only launch persists a private companion shared with VS Code`() = fixture { shared, legacy, identity ->
        val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { true })
        assertTrue(manager.beginLaunch())
        try { assertTrue(manager.retainConnection(5001, "secret", "varro-user")) }
        finally { manager.endLaunch() }
        assertFalse(Files.exists(manager.path))
        val companion = Path.of("${manager.path}.credentials")
        assertEquals("varro-user", ServerOwnership.connectionRecord(companion).str("username"))
        val reloaded = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { true })
        assertEquals(ServerOwnership.Connection(5001, "secret", "varro-user"), reloaded.connection())
        assertTrue(reloaded.credentialOnly)
        assertFalse(reloaded.refresh(5001, takeover = true))
    }

    @Test fun `confirmed launch publishes the shared wire format and keeps its claim`() = fixture { shared, legacy, identity ->
        val pid = ProcessHandle.current().pid()
        identity.listening = setOf(pid)
        val manager = ServerOwnership(4096, shared, legacy, identity)
        assertTrue(manager.beginLaunch())
        try {
            assertTrue(manager.register(5001, pid, "secret", "varro-user"))
            val lease = ServerOwnership.record(manager.path)!!
            assertEquals(1, lease.get("version").asInt)
            assertEquals(5001, lease.get("port").asInt)
            assertEquals("varro-user", lease.str("username"))
            assertEquals(lease.str("owner"), ServerOwnership.connectionRecord(Path.of("${manager.path}.credentials")).str("owner"))
            assertFalse(ServerOwnership.record(Path.of("${manager.path}.managed"), false)!!.has("password"))
            val follower = ServerOwnership(4096, shared, legacy, identity)
            assertEquals(ServerOwnership.Connection(5001, "secret", "varro-user"), follower.connection())
            assertFalse(follower.refresh(5001))
            assertTrue(Files.exists(Path.of("${manager.path}.claim")))
        } finally { manager.endLaunch() }
        manager.relinquish()
        assertEquals("relinquished", ServerOwnership.record(manager.path).str("state"))
    }

    @Test fun `marker recovery preserves endpoint credentials without claiming a foreign config`() = fixture { shared, legacy, identity ->
        val manager = ServerOwnership(4096, shared, legacy, identity)
        privateFile(Path.of("${manager.path}.managed"), Json.stringify(marker().apply { addProperty("configPath", "/vscode/injected.json") }))
        assertNotNull(manager.connection())
        assertTrue(manager.refresh(4096, password = "secret", username = "varro-user"))
        assertEquals(ServerOwnership.Connection(4096, "secret", "varro-user"), manager.connection())
        assertEquals("/vscode/injected.json", ServerOwnership.record(manager.path).str("configPath"))
    }

    @Test fun `followers detect replacement but host handoff and inconclusive inspection are not replacement`() = fixture { shared, legacy, identity ->
        val manager = ServerOwnership(4096, shared, legacy, identity)
        privateFile(manager.path, Json.stringify(lease()))
        assertNotNull(manager.connection())
        privateFile(manager.path, Json.stringify(lease().apply { addProperty("host", "openjet-host") }))
        assertFalse(manager.registrationChanged())
        identity.fail = true
        assertThrows(IllegalStateException::class.java) { manager.registrationChanged() }
        identity.fail = false
        assertFalse(manager.registrationChanged())
        identity.serverBirth = "darwin:replacement"
        assertTrue(manager.registrationChanged())
    }

    @Test fun `an empty listener observation cannot replace a live managed registration`() = fixture { shared, legacy, identity ->
        val manager = ServerOwnership(4096, shared, legacy, identity)
        privateFile(manager.path, Json.stringify(lease()))
        assertNotNull(manager.connection())
        val before = Files.readString(manager.path)
        identity.listening = emptySet()
        val failure = assertThrows(IllegalStateException::class.java) { manager.registrationChanged() }
        assertTrue(failure.message!!.contains("observed listening PIDs: none"))
        assertFalse(manager.refresh(4096, takeover = true)) // startup/lifecycle remain strict
        assertEquals(before, Files.readString(manager.path))
        identity.listening = setOf(123)
        assertFalse(manager.registrationChanged())
        assertFalse(manager.credentialOnly)
        identity.listening = setOf(456)
        assertTrue(manager.registrationChanged())
    }

    @Test fun `credential-only startup recheck recovers the original active or relinquished lease only`() {
        for (state in listOf("active", "relinquished")) fixture { shared, legacy, identity ->
            val original = lease().apply {
                addProperty("state", state); addProperty("hostPid", ProcessHandle.current().pid())
                addProperty("hostBirthIdentity", "darwin:host-start")
            }
            val path = shared.resolve("varro-opencode-server-4096.json")
            privateFile(path, Json.stringify(original))
            identity.listening = emptySet()
            val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { true })
            assertNotNull(manager.connection()); assertTrue(manager.credentialOnly)
            assertFalse(manager.recheckAttachment(4096)); assertTrue(manager.credentialOnly)
            assertEquals(original, ServerOwnership.record(path))
            identity.listening = setOf(123)
            assertTrue(manager.recheckAttachment(4096)); assertFalse(manager.credentialOnly)
            assertEquals(123L, manager.registeredPid)
            assertEquals(state == "relinquished", manager.refresh(4096))
            assertEquals("secret", manager.connection()!!.password)
        }
    }

    @Test fun `credential rechecks reject replacement ambiguity modified evidence and cancellation`() {
        for (mode in listOf("reused-pid", "executable", "multiple-listeners", "changed-record", "cancelled", "cancelled-during-inspection", "companion-only")) fixture { shared, legacy, identity ->
            val path = shared.resolve("varro-opencode-server-4096.json")
            if (mode == "companion-only") privateFile(Path.of("$path.credentials"), Json.stringify(Json.obj("version" to 1, "port" to 4096,
                "owner" to "companion", "createdAt" to 1, "password" to "secret")))
            else privateFile(path, Json.stringify(lease().apply { if (mode == "executable") addProperty("executable", "/different/opencode") }))
            identity.listening = emptySet()
            val manager = ServerOwnership(4096, shared, legacy, identity, verifyCredentials = { true })
            assertNotNull(manager.connection()); assertTrue(manager.credentialOnly)
            identity.listening = if (mode == "multiple-listeners") setOf(123, 456) else setOf(123)
            if (mode == "reused-pid") identity.serverBirth = "darwin:replacement"
            if (mode == "changed-record") privateFile(path, Json.stringify(lease().apply { addProperty("owner", "changed") }))
            val before = if (Files.exists(path)) Files.readString(path) else null
            var cancelled = mode == "cancelled"
            if (mode == "cancelled-during-inspection") identity.onListeners = { cancelled = true }
            if (mode.startsWith("cancelled")) assertThrows(java.util.concurrent.CancellationException::class.java) { manager.recheckAttachment(4096) { cancelled } }
            else assertFalse(mode, manager.recheckAttachment(4096))
            assertTrue(manager.credentialOnly); assertNull(manager.registeredPid)
            assertFalse(manager.refresh(4096, takeover = true)); assertFalse(manager.stop(1))
            assertEquals(before, if (Files.exists(path)) Files.readString(path) else null)
        }
    }

    @Test fun `credential companions reject coerced types and symlinks`() = fixture { shared, _, _ ->
        val companion = shared.resolve("credentials")
        val valid = Json.obj("version" to 1, "port" to 4096, "owner" to "launch", "createdAt" to 1, "password" to "secret")
        for (field in listOf("version", "port", "password")) {
            val invalid = valid.deepCopy().apply { if (field == "password") addProperty(field, 123) else addProperty(field, get(field).asString) }
            privateFile(companion, Json.stringify(invalid))
            assertNull(ServerOwnership.connectionRecord(companion))
        }
        privateFile(companion, Json.stringify(valid))
        assertNotNull(ServerOwnership.connectionRecord(companion))
        if (companion.fileSystem.supportedFileAttributeViews().contains("posix")) {
            val link = Files.createSymbolicLink(shared.resolve("link"), companion)
            assertNull(ServerOwnership.connectionRecord(link))
        }
    }

    @Test fun `macOS resolves launch symlink when lsof executable was replaced`() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        val root = Files.createTempDirectory("macos-ownership-").toRealPath()
        try {
            val binary = Files.writeString(root.resolve("opencode"), "binary")
            val launch = Files.createSymbolicLink(root.resolve("opencode2"), binary)
            val identity = object : ProcessIdentity() {
                override fun command(vararg arguments: String) = if (arguments[0] == "lsof") "p123\nftxt\nn/nonexistent-opencode" else launch.toString()
            }
            assertEquals(binary.toString(), identity.executable(123))
        } finally { root.toFile().deleteRecursively() }
    }
}
