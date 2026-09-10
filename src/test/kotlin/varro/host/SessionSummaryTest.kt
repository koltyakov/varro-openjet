package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json
import varro.protocol.long
import varro.protocol.obj
import varro.protocol.str

class SessionSummaryTest {
    private fun messages(json: String) = Json.parse(json).asJsonArray.map { it.asJsonObject }

    @Test
    fun `empty diff endpoint falls back to message edits and actual token usage`() {
        val history = SessionSummary.History(messages("""[
          {"info":{"role":"user","time":{"created":1000}},"parts":[]},
          {"info":{"role":"assistant","providerID":"openai","modelID":"gpt-6-astra","variant":"high",
            "time":{"created":1100,"completed":4000},
            "tokens":{"input":100,"output":20,"reasoning":5,"cache":{"read":1000,"write":10}}},
           "parts":[
             {"type":"tool","tool":"functions.apply_patch","state":{"metadata":{"files":[
               {"relativePath":"src/a.kt","additions":12,"deletions":3},
               {"relativePath":"node_modules/pkg/index.js","additions":900,"deletions":400}]}}},
             {"type":"patch","files":["/workspace/src/a.kt","src/b.kt"]},
             {"type":"tool","tool":"functions.edit","state":{"input":{"filePath":"src/b.kt"},"metadata":{"linesAdded":2,"linesRemoved":1}}}
           ]}
        ]"""))
        val result = SessionSummary.summarize(history, Json.array(emptyList<Any>()))
        assertEquals(2L, result.long("files"))
        assertEquals(14L, result.long("additions"))
        assertEquals(4L, result.long("deletions"))
        assertEquals(135L, result.long("tokens"))
        assertEquals(3000L, result.long("durationMs"))
        assertTrue(result.get("activeStartedAt").isJsonNull)
        assertEquals("high", result.obj("model").str("variant"))
        assertEquals(1135L, result.obj("tokenBreakdown").obj("session").long("total"))
    }

    @Test
    fun `subagent snapshots take precedence with message fallback and no double counting`() {
        val result = SessionSummary.summarize(SessionSummary.History(
            messages("""[{"info":{"role":"assistant","tokens":{"total":200,"cache":{"read":150}}}}]"""),
            listOf(
                SessionSummary.Descendant(Json.parse("""{"input":30,"output":5,"cache":{"read":100}}""").asJsonObject,
                    messages("""[{"info":{"role":"assistant","tokens":{"input":999}}}]""")),
                SessionSummary.Descendant(Json.obj("total" to 0),
                    messages("""[{"info":{"role":"assistant","tokens":{"total":0,"input":20,"output":4}}}]""")),
            ),
        ))
        assertEquals(109L, result.long("tokens"))
        assertEquals(2L, result.obj("tokenBreakdown").long("subagentCount"))
        assertEquals(159L, result.obj("tokenBreakdown").obj("subagents").long("total"))
    }

    @Test
    fun `duration excludes idle gaps and tracks an unfinished turn`() {
        val result = SessionSummary.summarize(SessionSummary.History(messages("""[
          {"info":{"role":"user","time":{"created":1000}}},
          {"info":{"role":"assistant","time":{"created":1100,"completed":2000}}},
          {"info":{"role":"assistant","time":{"created":2000,"completed":3000}}},
          {"info":{"role":"user","time":{"created":9000}}},
          {"info":{"role":"assistant","time":{"created":9100}}},
          {"info":{"role":"assistant","mode":"subagent","time":{"created":9200,"completed":9300}}}
        ]""")))
        assertEquals(2000L, result.long("durationMs"))
        assertEquals(9000L, result.long("activeStartedAt"))
    }

    @Test
    fun `remote diffs take precedence and accept aliases while ignoring invalid counts`() {
        val result = SessionSummary.summarize(SessionSummary.History(messages("""[
          {"info":{"summary":{"diffs":[{"file":"ignored.kt","additions":999}]}}}
        ]""")), Json.parse("""{
          "a":{"file":"src/a.kt","added":3,"removed":2},
          "b":{"file":"/workspace/src/a.kt","additions":4,"deletions":-1},
          "c":{"file":".venv/lib/a.py","additions":1000},
          "d":{"file":"src/b.kt","additions":"7","deletions":1.5}
        }"""))
        assertEquals(2L, result.long("files"))
        assertEquals(7L, result.long("additions"))
        assertEquals(2L, result.long("deletions"))
    }

    @Test
    fun `remote fallback recursively includes descendants and preserves directory`() {
        val paths = mutableListOf<String>()
        val service = SessionSummaryService({ null }) { path, directory ->
            assertEquals("/workspace", directory)
            paths.add(path)
            when (path) {
                "/session?limit=1000000" -> Json.parse("""[
                    {"id":"grandchild","parentID":"child"}, {"id":"child","parentID":"root"},
                    {"id":"unrelated"}, {"id":"root","parentID":"grandchild"}
                ]""")
                "/session/root/diff" -> Json.array(emptyList<Any>())
                else -> Json.parse("""[{"info":{"role":"assistant","tokens":{"input":10}}}]""")
            }
        }
        val result = service.read("root", "/workspace")
        assertEquals(30L, result.long("tokens"))
        assertEquals(2L, result.obj("tokenBreakdown").long("subagentCount"))
        assertEquals(5, paths.size)
        assertFalse(paths.any { it.contains("unrelated") })
    }

    @Test
    fun `local history avoids remote requests`() {
        val service = SessionSummaryService({ SessionSummary.History(emptyList()) }) { _, _ ->
            error("Local summaries must not request remote history")
        }
        assertEquals(0L, service.read("root", null).long("tokens"))
    }
}
