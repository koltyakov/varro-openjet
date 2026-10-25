package varro

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.*
import varro.settings.VarroSettings

class FollowupsTest {
    @Test fun `shared identities match JavaScript escaping without rewriting literal Unicode escapes`() {
        val source = Json.obj("userPrompt" to "Literal \\u2028; line \u2028; paragraph \u2029; 日本語 🚀; lone \ud800; control \u0001\b\n")
        val expected = "{\"userPrompt\":\"Literal \\\\u2028; line \u2028; paragraph \u2029; 日本語 🚀; lone \\ud800; control \\u0001\\b\\n\"}"
        assertEquals(expected, Followups.identityJson(source))
        assertEquals("[null,\"/repo\",\"s\"]", Followups.identityJson(Json.array(listOf(null, "/repo", "s"))))
    }

    @Test fun `session model validation retains reasoning but drops empty variants`() {
        val model = Json.obj("providerID" to "provider", "modelID" to "model", "variant" to "high")
        assertEquals(model, Followups.model(model))
        assertFalse(Followups.model(model.deepCopy().apply { addProperty("variant", "") })!!.has("variant"))
        for (invalid in listOf(null, Json.obj(), model.deepCopy().apply { addProperty("providerID", "") },
            model.deepCopy().apply { addProperty("variant", 42) }, model.deepCopy().apply { addProperty("modelID", "x".repeat(4097)) })) {
            assertNull(Followups.model(invalid))
        }
    }

    @Test fun `follow-ups default to enabled and settings persist opt-out and model`() {
        val settings = VarroSettings()
        assertTrue(settings.chatFollowupsEnabled)
        assertEquals("", settings.chatFollowupModel)
        settings.chatFollowupsEnabled = false
        settings.chatFollowupModel = "provider/model"
        val restored = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(settings.state), VarroSettings::class.java)
        assertFalse(restored.chatFollowupsEnabled)
        assertEquals("provider/model", restored.chatFollowupModel)
    }

    @Test fun `generated output rejects malformed unsafe duplicate oversized and extra fields`() {
        val action = Json.obj("title" to "Fix parser", "prompt" to "Fix the reported parser bug.")
        val valid = Json.obj("summary" to "One parser bug remains.", "actions" to listOf(action))
        assertEquals(valid, Followups.suggestions(valid.toString()))
        assertEquals(Followups.empty(), Followups.suggestions(Followups.empty().toString()))
        for (text in listOf("not json", "[]", "{summary:'x',actions:[]}", valid.toString() + " trailing",
            Json.obj("summary" to "", "actions" to listOf(action)).toString(),
            Json.obj("summary" to "x".repeat(401), "actions" to listOf(action)).toString(),
            Json.obj("summary" to "Unsafe\u202e", "actions" to listOf(action)).toString(),
            Json.obj("summary" to "Context", "actions" to listOf(action, action)).toString(),
            valid.deepCopy().apply { addProperty("execute", true) }.toString(),
            valid.deepCopy().apply { getAsJsonArray("actions")[0].asJsonObject.addProperty("prompt", "/publish") }.toString(),
            valid.deepCopy().apply { getAsJsonArray("actions")[0].asJsonObject.addProperty("title", "Fix\nall") }.toString(),
            valid.deepCopy().apply { getAsJsonArray("actions")[0].asJsonObject.addProperty("prompt", "x".repeat(601)) }.toString(),
            "x".repeat(4097))) {
            assertThrows(text.take(100), Exception::class.java) { Followups.suggestions(text) }
        }
        for (title in listOf(null, "", " ", "x".repeat(41), "Fix\tall", "Fix\u2066all")) assertNull(Followups.title(title))
        assertEquals("Inspect parser", Followups.title(" Inspect parser "))
    }

    @Test fun `source is bounded and hidden context never contributes visible text`() {
        val source = Json.obj("assistantMessageId" to "a", "userMessageId" to "u", "userPrompt" to "Fix it", "answer" to "One issue remains")
        assertEquals(source, Followups.source(source))
        for (invalid in listOf(source.deepCopy().apply { addProperty("answer", "x".repeat(12001)) },
            source.deepCopy().apply { addProperty("userPrompt", " ") }, source.deepCopy().apply { addProperty("userMessageId", "../other") })) {
            assertThrows(IllegalArgumentException::class.java) { Followups.source(invalid) }
        }
        assertEquals("Visible request", Followups.text(Json.array(listOf(Json.obj("type" to "text", "text" to "Visible request"),
            Json.obj("type" to "reasoning", "text" to "Hidden"), Json.obj("type" to "text", "text" to "Hidden", "synthetic" to true),
            Json.obj("type" to "text", "text" to "Hidden", "ignored" to true)))))
    }
}
