package varro.host

import com.google.gson.JsonArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.protocol.*
import java.nio.file.Files

class NativePermissionRulesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun rule(resource: String, effect: String = "allow", action: String = "shell") =
        Json.obj("action" to action, "resource" to resource, "effect" to effect)
    private fun rules() = Json.array(listOf(rule("git *"), rule("git push*", "deny"), rule("git reset --hard*", "ask")))

    @Test fun `last match uses concrete resources native wildcard normalization and malformed rule safety`() {
        for ((resource, expected) in listOf("git fetch origin" to "allow", "git" to "allow",
            "git push origin main" to "deny", "git reset --hard HEAD" to "ask", "npm test" to null)) {
            assertEquals(expected, NativePermissionRules.resolve(rules(), "shell", resource))
        }
        val wildcard = Json.array(listOf(rule("echo [a].?*", action = "sh?ll")))
        assertEquals("allow", NativePermissionRules.resolve(wildcard, "shell", "echo [a].x"))
        assertNull(NativePermissionRules.resolve(wildcard, "shell", "echo a.x"))
        assertNull(NativePermissionRules.resolve(wildcard, "shell", "prefix echo [a].x"))
        assertEquals("deny", NativePermissionRules.resolve(Json.array(listOf(rule("git *"), rule("git show private\\*", "deny"))),
            "shell", "git show private/secret"))
        assertNull(NativePermissionRules.resolve(rules().apply { add(Json.obj("action" to "shell")) }, "shell", "git fetch"))
    }

    @Test fun `redundant grants are skipped without crossing exceptions or broadening syntax`() {
        val existing = rules()
        assertEquals(existing, NativePermissionRules.appendAllows(existing, "shell", listOf("git fetch *", "git fetch origin")))
        assertEquals(4, NativePermissionRules.appendAllows(existing, "shell", listOf("git *", "git push*", "git reset --hard*")).size())
        assertEquals(5, NativePermissionRules.appendAllows(existing, "shell", listOf("git push*", "git reset --hard*")).size())
        val fresh = NativePermissionRules.appendAllows(JsonArray(), "shell", listOf("git fetch *", "git fetch *", "git fetch origin", "head *"))
        assertEquals(2, fresh.size())
        assertEquals(fresh, NativePermissionRules.appendAllows(fresh, "shell", listOf("git fetch")))
        assertEquals(3, NativePermissionRules.appendAllows(fresh, "shell", listOf("git fetch*")).size())
        assertEquals(3, NativePermissionRules.appendAllows(fresh, "shell", listOf("git ?etch *")).size())
        val overlap = Json.array(listOf(rule("*", action = "*"), rule("git * --force", "ask")))
        assertEquals(3, NativePermissionRules.appendAllows(overlap, "shell", listOf("git fetch *")).size())
        assertEquals(overlap, NativePermissionRules.appendAllows(overlap, "shell", listOf("head *")))
        val malformed = existing.deepCopy().apply { add(Json.obj("action" to "shell", "effect" to "unknown")) }
        assertEquals(5, NativePermissionRules.appendAllows(malformed, "shell", listOf("git fetch *")).size())
        assertEquals(3, existing.size())
    }

    @Test fun `project and global grants preserve exact documents for redundant scopes and append past exceptions`() {
        for (global in listOf(false, true)) {
            val root = temporary.newFolder().toPath()
            val path = root.resolve("opencode.jsonc")
            val original = "{ /* keep */ \"permissions\": ${rules()}, \"model\":\"p/m\" }\n"
            Files.writeString(path, original)
            fun allow(patterns: List<String>) = if (global) GlobalPermissionConfig(root).allow("bash", patterns)
                else ProjectPermissionConfig(root, { true }).allow("bash", patterns)
            assertEquals(3, allow(listOf("git fetch *")).size())
            assertEquals(original, Files.readString(path))
            assertEquals(4, allow(listOf("git push*")).size())
            assertTrue(Files.readString(path).contains("/* keep */"))
            assertEquals("allow", NativePermissionRules.resolve(Json.parse(ProjectPermissionConfig.stripJsonComments(Files.readString(path)))
                .asJsonObject.arr("permissions")!!, "shell", "git push origin main"))
        }
    }

    private fun permission(resources: Any? = listOf("git fetch origin")) = Json.obj("permission" to Json.obj(
        "id" to "perm", "sessionID" to "child", "type" to "bash", "patterns" to resources, "always" to listOf("*"),
        "metadata" to Json.obj("command" to "git fetch origin")))

    @Test fun `configured shell decisions precede inference recheck every request and preserve deny and ask exceptions`() {
        var config = rules()
        var reads = 0
        val judge = PermissionJudge({ error("No model lookup") }, { method, path, _, timeout ->
            assertEquals("GET", method); assertEquals("/config", path); assertEquals(2000L, timeout)
            reads++
            Json.obj("permissions" to config)
        }, { error("No helper") }, apiVersion = { 2 })
        for ((resources, expected) in listOf(
            listOf("git fetch origin") to "allow", listOf("git fetch origin", "git push origin main") to "reject",
            listOf("git reset --hard HEAD") to "ask")) {
            assertEquals(expected, judge.judge(permission(resources)).str("decision"))
        }
        config = config.deepCopy().apply { add(rule("git *", "ask")) }
        assertEquals("ask", judge.judge(permission()).str("decision"))
        assertEquals(4, reads)
    }

    @Test fun `unverified config asks and missing malformed or partially configured resources never auto allow`() {
        val failed = PermissionJudge({ error("No model") }, { _, _, _, _ -> error("timeout") }, {}, apiVersion = { 2 })
        assertEquals("Cannot verify configured shell permissions.", failed.judge(permission()).str("reason"))
        for (resources in listOf(null, listOf(3), listOf(""), listOf("git fetch", "cargo build"))) {
            val judge = PermissionJudge({ "p/m" }, { _, path, _, _ ->
                if (path == "/config") Json.obj("permissions" to rules()) else error("No available reviewer")
            }, {}, apiVersion = { 2 })
            assertEquals("ask", judge.judge(permission(resources)).str("decision"))
        }
        var configReads = 0
        val defaultAsk = PermissionJudge({ "p/m" }, { _, path, _, _ ->
            if (path == "/config") { configReads++; Json.obj("permissions" to listOf(rule("*", "ask"))) } else error("Reviewer unavailable")
        }, {}, apiVersion = { 2 })
        assertTrue(defaultAsk.judge(permission(listOf("cargo build"))).str("reason")!!.startsWith("Permission review failed"))
        assertEquals(1, configReads)
    }
}
