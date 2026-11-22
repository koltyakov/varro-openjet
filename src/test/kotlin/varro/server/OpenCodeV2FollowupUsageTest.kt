package varro.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import varro.host.ApiRoutes
import varro.protocol.*
import java.util.concurrent.CancellationException

class OpenCodeV2FollowupUsageTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun user(id: String) = Json.obj("id" to id, "type" to "user", "text" to "Private prompt")
    private fun step(id: String, input: Int? = 1, completed: Boolean = true) = Json.obj("id" to id, "type" to "assistant",
        "time" to if (completed) Json.obj("completed" to 1) else Json.obj(), "content" to listOf(Json.obj("type" to "text", "text" to "Private answer"))).apply {
        if (input != null) add("tokens", Json.obj("input" to input, "output" to 2, "reasoning" to 3, "cache" to Json.obj("read" to 4, "write" to 5)))
    }
    private fun intervals(records: List<JsonObject>, complete: Boolean = true) =
        OpenCodeV2FollowupUsage.summarize(records, complete, emptyList()).arr("intervals")!!.map { it.asJsonObject }

    @Test fun `persisted delivery order groups prompts and keeps completed intervals stable without returning bodies`() {
        val records = listOf(user("z"), step("y", 10), user("x"), user("w"), step("v", 20), user("u"), step("t", 40))
        val before = intervals(records)
        assertEquals(listOf(listOf("z"), listOf("x", "w"), listOf("u")), before.map { it.arr("promptIDs")!!.map { it.asString } })
        assertEquals(listOf(10.0, 20.0, 40.0), before.map { it.obj("usage").num("input") })
        assertEquals(listOf(true, true, false), before.map { it.bool("final") })
        val after = intervals(records + Json.obj("id" to "notice", "type" to "synthetic") + step("later", 5))
        assertEquals(before.take(2), after.take(2))
        assertEquals(45.0, after.last().obj("usage").num("input"))
        assertFalse(after.toString().contains("Private"))
    }

    @Test fun `usage distinguishes zero missing active retries and compaction and settles unfinished idle steps`() {
        val retry = step("retry", 3).apply { addProperty("cost", 0.25); add("retry", Json.obj("attempt" to 1)) }
        val records = listOf(user("a"), step("zero", 0), step("failed", null).apply { add("error", Json.obj("message" to "failed")) },
            retry, retry, step("active", null, false), Json.obj("type" to "compaction", "tokens" to Json.obj("input" to 900)))
        val usage = intervals(records).single().obj("usage")!!
        assertEquals(Json.obj("input" to 3, "output" to 4, "reasoning" to 6, "cacheRead" to 8, "cacheWrite" to 10,
            "cost" to 0.25, "steps" to 2, "missing" to 1, "active" to 1), usage)
        val settled = intervals(records + Json.obj("type" to "idle")).single()
        assertEquals(2, settled.obj("usage").int("missing"))
        assertEquals(0, settled.obj("usage").int("active"))
        assertEquals(false, settled.bool("final"))
    }

    @Test fun `goal kickoffs create intervals and only the unread oldest boundary is uncertain`() {
        val kickoff = Json.obj("id" to "goal", "type" to "synthetic", "description" to "Goal", "metadata" to Json.obj("source" to "goal"))
        val continuation = kickoff.deepCopy().apply { remove("description"); addProperty("id", "continue") }
        val result = intervals(listOf(user("a"), step("one"), kickoff, step("two", 2), continuation, step("three", 4)), false)
        assertEquals(2, result.size)
        assertEquals(true, result.first().bool("boundaryUnknown"))
        assertFalse(result.last().has("boundaryUnknown"))
        assertEquals(6.0, result.last().obj("usage").num("input"))
        assertFalse(intervals(listOf(step("leading"), user("a"), step("next")), false).single().has("boundaryUnknown"))
    }

    @Test fun `bounded native pages follow cursors newest first and report pending user inputs without synthetic inputs`() {
        val calls = mutableListOf<String?>()
        val pages = listOf(listOf(step("s2", 2), user("c"), user("b")), listOf(step("s1"), user("a")))
        val result = OpenCodeV2FollowupUsage.read({
            calls += "inbox"
            Json.array(listOf(Json.obj("id" to "queued", "type" to "user"), Json.obj("id" to "notice", "type" to "synthetic")))
        }, { cursor ->
            calls += cursor
            val index = cursor?.toInt() ?: 0
            Json.obj("data" to pages.getOrElse(index) { emptyList() }, "cursor" to Json.obj("next" to (index + 1).toString()))
        })
        assertEquals(listOf("inbox", null, "1", "2"), calls)
        assertEquals(true, result.bool("complete"))
        assertEquals(Json.array(listOf("queued")), result.get("pendingPromptIDs"))
        assertEquals(listOf(listOf("a"), listOf("b", "c")), result.arr("intervals")!!.map { it.asJsonObject.arr("promptIDs")!!.map { it.asString } })
    }

    @Test fun `page and byte bounds preserve newer intervals and only oversize errors truncate`() {
        val page = Json.obj("data" to listOf(step("s2", 2), user("b")), "cursor" to Json.obj("next" to "older"))
        for (maxPages in listOf(1, 10)) {
            val result = OpenCodeV2FollowupUsage.read({ JsonArray() }, { cursor ->
                if (cursor != null) throw OpenCodeResponseTooLargeException(1) else page
            }, maxPages = maxPages)
            assertEquals(false, result.bool("complete"))
            assertEquals(2.0, result.arr("intervals")!![0].asJsonObject.obj("usage").num("input"))
            assertEquals(true, result.arr("intervals")!![0].asJsonObject.bool("boundaryUnknown"))
        }
        var pages = 0
        val result = OpenCodeV2FollowupUsage.read({ JsonArray() }, {
            pages++
            if (pages == 1) page else Json.obj("data" to listOf(user("large").apply { addProperty("text", "x".repeat(4096)) }))
        }, maxBytes = page.arr("data").toString().toByteArray().size.toLong())
        assertEquals(1, result.arr("intervals")!!.size())
        assertThrows(CancellationException::class.java) {
            OpenCodeV2FollowupUsage.read({ JsonArray() }, { throw CancellationException() })
        }
        assertThrows(IllegalStateException::class.java) {
            OpenCodeV2FollowupUsage.read({ JsonArray() }, { page })
        }
        assertThrows(IllegalStateException::class.java) {
            OpenCodeV2FollowupUsage.read({ JsonArray() }, { Json.obj("data" to "malformed") })
        }
    }

    @Test fun `adapter exposes only bounded read-only native operations and rejects request bodies`() {
        val calls = mutableListOf<String>()
        val adapter = OpenCodeV2Adapter({ method, path, _, options ->
            assertEquals("GET", method); assertEquals("/repo", options.directory)
            calls += path
            OpenCodeResponse(if (path.endsWith("inbox")) Json.obj("data" to emptyList<Any>())
                else Json.obj("data" to emptyList<Any>(), "cursor" to Json.obj("next" to null)))
        }, OpenCodeV2SessionState(temporary.root.toPath()))
        val result = adapter.request("GET", "/session/ses_one/followup-usage", null, RequestOptions(directory = "/repo")).data.asObjectOrNull()
        assertEquals(true, result.bool("supported"))
        assertEquals(listOf("/api/session/ses_one/inbox", "/api/session/ses_one/message?limit=200&order=desc"), calls)
        assertThrows(IllegalArgumentException::class.java) {
            adapter.request("GET", "/session/ses_one/followup-usage", Json.obj(), RequestOptions())
        }
        assertTrue(ApiRoutes.isAllowed("GET", "/varro/session/ses_one/followup-usage?directory=%2Frepo"))
        assertFalse(ApiRoutes.isAllowed("POST", "/varro/session/ses_one/followup-usage"))
        assertFalse(ApiRoutes.isAllowed("GET", "/varro/session/ses_one/followup-usage?limit=10000"))
        assertFalse(ApiRoutes.isAllowed("GET", "/session/ses_one/followup-usage"))
    }
}
