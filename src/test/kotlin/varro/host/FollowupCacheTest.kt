package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FollowupCacheTest {
    @JvmField @Rule val temporary = TemporaryFolder()
    private val key = "a".repeat(64)
    private val digest = "b".repeat(64)
    private val session = "c".repeat(64)
    private val suggestions = Json.obj("summary" to "One issue remains.", "actions" to listOf(Json.obj("title" to "Add a test", "prompt" to "Add a focused regression test.")))

    @Test fun `pending admission is shared until completion without permitting another claim`() {
        val path = temporary.newFolder().toPath().resolve("cache.json")
        val writer = FollowupCache(path)
        val reader = FollowupCache(path)
        assertNull(reader.getState(key, digest))
        assertTrue(writer.claim(key, digest, session))
        assertEquals(FollowupCache.State.Pending, reader.getState(key, digest))
        assertEquals(Followups.empty(), reader.get(key, digest))
        assertEquals(FollowupCache.State.Complete(Followups.empty()), reader.getState(key, "d".repeat(64)))
        writer.complete(key, digest, Followups.empty())
        assertEquals(FollowupCache.State.Complete(Followups.empty()), reader.getState(key, digest))
        assertFalse(Files.readString(path).contains("pendingSince"))
        assertFalse(reader.claim(key, digest, session))
    }

    @Test fun `pending grace expiry and legacy entries retain permanent admission`() {
        val path = temporary.newFolder().toPath().resolve("cache.json")
        var now = 100_000L
        val cache = FollowupCache(path, now = { now })
        cache.claim(key, digest, session)
        now += 34_999
        assertEquals(FollowupCache.State.Pending, cache.getState(key, digest))
        now++
        assertEquals(FollowupCache.State.Complete(Followups.empty()), cache.getState(key, digest))
        assertFalse(cache.claim(key, digest, session))
        val stored = Json.parse(Files.readString(path)).asJsonObject
        stored.obj("entries").obj(key)!!.remove("pendingSince")
        Files.writeString(path, stored.toString())
        assertEquals(FollowupCache.State.Complete(Followups.empty()), cache.getState(key, digest))
        assertFalse(cache.claim(key, digest, session))
    }

    @Test fun `dismissal and new-turn retirement settle pending entries and reject late results`() {
        for (dismiss in listOf(true, false)) {
            val path = temporary.newFolder().toPath().resolve("cache.json")
            val cache = FollowupCache(path)
            cache.claim(key, digest, session)
            if (dismiss) cache.dismiss(key, digest) else cache.clearSession(session, null)
            cache.complete(key, digest, suggestions)
            assertEquals(FollowupCache.State.Complete(Followups.empty()), FollowupCache(path).getState(key, digest))
            assertFalse(Files.readString(path).contains("pendingSince"))
            assertFalse(cache.claim(key, digest, session))
        }
    }

    @Test fun `native cache roots match Varro and isolated roots never discover production admission`() {
        val home = temporary.root.toPath()
        assertEquals(home.resolve("Library/Application Support/Varro/followups/cache.json"), FollowupCache.defaultPath(emptyMap(), home, "Mac OS X"))
        assertEquals(home.resolve("AppData/Local/Varro/followups/cache.json"), FollowupCache.defaultPath(mapOf("LOCALAPPDATA" to "relative"), home, "Windows 11"))
        assertEquals(home.resolve(".local/state/varro/followups/cache.json"), FollowupCache.defaultPath(mapOf("XDG_STATE_HOME" to "relative"), home, "Linux"))
        assertEquals(home.resolve("custom/varro/followups/cache.json"), FollowupCache.defaultPath(mapOf("XDG_STATE_HOME" to home.resolve("custom").toString()), home, "Linux"))
        assertEquals(home.resolve("isolated/followups/cache.json"), FollowupCache.defaultPath(mapOf("VARRO_TEST_STATE_ROOT" to home.resolve("isolated").toString()), home, "Mac OS X"))
        assertThrows(IllegalArgumentException::class.java) { FollowupCache.defaultPath(mapOf("VARRO_TEST_STATE_ROOT" to "relative"), home, "Linux") }
    }

    @Test fun `corrupt presentation preserves admission and conflicting digests cannot revive a turn`() {
        val path = temporary.newFolder().toPath().resolve("cache.json")
        val cache = FollowupCache(path)
        assertTrue(cache.claim(key, digest, session))
        cache.complete(key, digest, suggestions)
        val stored = Json.parse(Files.readString(path)).asJsonObject
        stored.obj("entries").obj(key)!!.add("suggestions", Json.toElement("corrupt"))
        Files.writeString(path, stored.toString())
        assertEquals(Followups.empty(), cache.get(key, digest))
        assertFalse(cache.claim(key, digest, session))
        assertEquals(Followups.empty(), cache.get(key, "d".repeat(64)))
        cache.complete(key, "d".repeat(64), suggestions)
        assertEquals(Followups.empty(), cache.get(key, digest))
    }

    @Test fun `transient Windows replacement failures retry without deleting the committed file`() {
        for (reason in listOf("access", "sharing violation", "busy")) {
            val path = temporary.newFolder().toPath().resolve("cache.json")
            val original = FollowupCache(path)
            original.claim(key, digest, session)
            original.complete(key, digest, suggestions)
            var attempts = 0
            val cache = FollowupCache(path, windows = true) { from, to ->
                if (++attempts < 3) {
                    assertEquals(suggestions, original.get(key, digest))
                    if (reason == "access") throw AccessDeniedException(to.toString())
                    throw java.nio.file.FileSystemException(to.toString(), null, reason)
                }
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            cache.dismiss(key, digest)
            assertEquals(3, attempts)
            assertEquals(Followups.empty(), FollowupCache(path).get(key, digest))
            assertFalse(Files.readString(path).contains("One issue remains"))
            assertEquals(listOf("cache.json"), Files.list(path.parent).use { it.map { file -> file.fileName.toString() }.toList() })
        }
    }

    @Test fun `persistent replacement failures are bounded and preserve committed state`() {
        val path = temporary.newFolder().toPath().resolve("cache.json")
        val original = FollowupCache(path)
        original.claim(key, digest, session)
        original.complete(key, digest, suggestions)
        val before = Files.readString(path)
        var attempts = 0
        val cache = FollowupCache(path, windows = true) { _, to -> attempts++; throw AccessDeniedException(to.toString()) }
        assertThrows(AccessDeniedException::class.java) { cache.dismiss(key, digest) }
        assertEquals(8, attempts)
        assertEquals(before, Files.readString(path))
        assertFalse(FollowupCache(path).claim(key, digest, session))
        assertEquals(listOf("cache.json"), Files.list(path.parent).use { it.map { file -> file.fileName.toString() }.toList() })
    }

    @Test(timeout = 10_000) fun `concurrent cache instances preserve all admissions and only one claims each turn`() {
        val path = temporary.newFolder().toPath().resolve("followups/cache.json")
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val claims = (0 until 24).map { executor.submit<Boolean> { FollowupCache(path).claim(key, digest, session) } }
            assertEquals(1, claims.count { it.get(5, TimeUnit.SECONDS) })
            val distinct = (0 until 24).map { index -> executor.submit<Boolean> {
                FollowupCache(path).claim("%064x".format(index), digest, session)
            } }
            assertTrue(distinct.all { it.get(5, TimeUnit.SECONDS) })
        }
        assertEquals(25, Json.parse(Files.readString(path)).asJsonObject.obj("entries")!!.size())
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
        }
    }
}
