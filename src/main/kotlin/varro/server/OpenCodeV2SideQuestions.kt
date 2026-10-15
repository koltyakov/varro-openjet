package varro.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*

/** Bounds mirror shared/side-question.ts. Side questions never enter the session inbox. */
internal object OpenCodeV2SideQuestions {
    const val MAX_QUESTION_LENGTH = 8_000
    const val MAX_ANSWER_LENGTH = 64_000
    const val MAX_TURNS = 12
    const val MAX_CONTEXT_LENGTH = 128_000
    const val TIMEOUT_MS = 35_000L

    fun prompt(input: JsonObject): String {
        val question = input.text("question")
        require(input.keySet().all { it in setOf("question", "history") } && question != null && question.length <= MAX_QUESTION_LENGTH) {
            "Invalid side question"
        }
        val history = if (input.has("history")) input.arr("history") else com.google.gson.JsonArray()
        require(history != null && history.size() < MAX_TURNS) { "Invalid side-question history" }
        var contextLength = question.length
        val turns = history.map { value ->
            val turn = value.asObjectOrNull()
            val previousQuestion = turn.text("question")
            val answer = turn.text("answer")
            require(turn?.size() == 2 && previousQuestion != null && previousQuestion.length <= MAX_QUESTION_LENGTH &&
                answer != null && answer.length <= MAX_ANSWER_LENGTH) { "Invalid side-question history" }
            contextLength += previousQuestion.length + answer.length
            Json.obj("question" to previousQuestion, "answer" to answer)
        }
        require(contextLength <= MAX_CONTEXT_LENGTH) { "This side conversation is full. Close it and start another." }
        return buildList {
            add("The user is asking a quick side question about the conversation so far.")
            add("Answer directly and concisely in Markdown from what you already know.")
            add("Do not call any tools and do not take any actions.")
            add("File and line references do not imply file contents. Use only included text or existing conversation context, and do not claim to read files.")
            if (turns.isNotEmpty()) {
                add("Previous side questions and answers, for context only:")
                add(Json.stringify(turns))
                add("Answer the following follow-up:")
            }
            add("")
            add(question.trim())
        }.joinToString("\n")
    }

    fun answer(value: JsonElement): JsonObject {
        val text = value.asObjectOrNull().text("text")
        check(text != null) { "OpenCode returned no answer. Side questions cannot run tools." }
        check(text.length <= MAX_ANSWER_LENGTH) { "The side-question answer was too long. Try a narrower question." }
        return Json.obj("text" to text.trim())
    }
}
