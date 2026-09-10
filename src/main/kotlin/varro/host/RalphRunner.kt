package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Project-owned orchestration. Every entered phase and prompt id is persisted before dispatch. */
class RalphRunner(
    initial: JsonObject,
    private val workspace: String,
    private val persist: (JsonObject) -> Unit,
    private val publish: (JsonObject) -> Unit,
    private val request: (String, String, JsonElement?, String) -> JsonElement?,
    private val readPlan: (String, String) -> String?,
    private val onChildCreated: (String, String) -> Unit = { _, _ -> },
    private val pollMs: Long = 1000,
    private val timeoutMs: Long = 30 * 60 * 1000,
) : AutoCloseable {
    private val runs = initial.deepCopy()
    private val active = ConcurrentHashMap<String, AtomicBoolean>()
    private val executor = Executors.newCachedThreadPool { Thread(it, "varro-ralph").apply { isDaemon = true } }
    @Volatile private var closed = false

    @Synchronized fun snapshot(): JsonObject = Json.obj("runs" to runs.deepCopy(), "activeIds" to active.keys.toList())
    fun isActive() = active.isNotEmpty()

    @Synchronized fun permissionModel(sessionId: String): JsonObject? {
        val matching = runs.entrySet().firstOrNull { (id, value) ->
            val run = value.asJsonObject
            active.containsKey(id) && run.str("status") == "running" && run.obj("config").str("permissionMode") == "auto" &&
                run.arr("iterations")?.any {
                    val iteration = it.asJsonObject
                    iteration.str("childSessionId") == sessionId || sessionId in iteration.arr("repairSessionIds")?.strings().orEmpty()
                } == true
        } ?: return null
        return matching.value.asJsonObject.obj("config").obj("model")?.deepCopy() ?: JsonObject()
    }

    @Synchronized fun handle(type: String, payload: JsonObject?) {
        if (closed) return
        val id = payload.text("managerSessionId")
        when (type) {
            "ralph/start" -> {
                val config = payload.obj("config")?.deepCopy() ?: error("Missing Ralph configuration")
                val managerId = config.text("managerSessionId") ?: error("Missing manager session id")
                require(managerId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid manager session id" }
                require(config.int("iterations") in 1..1000) { "Iterations must be between 1 and 1000" }
                require(config.text("planDocPath") != null && config.text("promptTemplate") != null) { "Missing plan or prompt" }
                require(config.str("permissionMode") in setOf("auto", "default", "full")) { "Invalid permission mode" }
                require(config.str("workspaceDirectory")?.let { varro.server.WorkspacePaths.isSame(it, workspace) } == true) {
                    "Ralph run belongs to a different workspace"
                }
                if (runs.has(managerId)) return
                runs.add(managerId, Json.obj("config" to config, "status" to "running", "currentIteration" to 0,
                    "iterations" to JsonArray(), "updatedAt" to System.currentTimeMillis()))
                save()
                launch(managerId)
            }
            "ralph/pause", "ralph/stop" -> id?.let { cancel(it, type == "ralph/pause") }
            "ralph/resume" -> id?.let {
                val run = runs.obj(it) ?: return
                if (active.containsKey(it) || run.str("status") == "done") return
                val config = run.obj("config")!!
                require(config.str("workspaceDirectory")?.let { directory -> varro.server.WorkspacePaths.isSame(directory, workspace) } == true) {
                    "This Ralph run has no matching workspace binding. Start a new run in this workspace."
                }
                if (run.str("status") == "incomplete") config.addProperty("iterations", ((config.int("iterations") ?: 0) + 5).coerceAtMost(1000))
                else if (run.arr("iterations")?.lastOrNull().asObjectOrNull().str("status") == "aborted") {
                    config.addProperty("iterations", ((config.int("iterations") ?: 0) + 1).coerceAtMost(1000))
                }
                run.remove("stopReason")
                run.remove("note")
                run.addProperty("status", "running")
                save()
                launch(it)
            }
            "ralph/update-model" -> id?.let {
                val model = payload?.get("model")
                if (model != null && !model.isJsonNull) require(model.asObjectOrNull().text("providerID") != null && model.asObjectOrNull().text("modelID") != null)
                runs.obj(it)?.obj("config")?.add("model", model)
                save()
            }
            "ralph/sync" -> {
                payload.obj("legacyRuns")?.entrySet()?.forEach { (key, value) ->
                    if (!runs.has(key) && value.isJsonObject) runs.add(key, value.deepCopy().apply {
                        asJsonObject.addProperty("status", "paused")
                        asJsonObject.addProperty("legacyMigrationAcknowledged", true)
                    })
                }
                save()
            }
        }
        publish(snapshot())
    }

    @Synchronized fun reattach() {
        runs.entrySet().filter { it.value.asJsonObject.str("status") == "running" }.forEach { (id, value) ->
            if (value.asJsonObject.obj("config").str("workspaceDirectory")?.let { varro.server.WorkspacePaths.isSame(it, workspace) } == true) launch(id)
            else finish(id, "failed", "iteration_error", "Run has no matching workspace binding.")
        }
    }

    private fun launch(id: String) {
        if (closed) return
        val cancelled = AtomicBoolean(false)
        if (active.putIfAbsent(id, cancelled) != null) return
        executor.execute {
            try { loop(id, cancelled) }
            catch (failure: Exception) {
                if (!cancelled.get() && !closed) synchronized(this) {
                    finish(id, "failed", "iteration_error", failure.message ?: "Ralph iteration failed")
                }
            } finally {
                active.remove(id, cancelled)
                publish(snapshot())
            }
        }
    }

    private fun check(cancelled: AtomicBoolean) { check(!cancelled.get() && !closed) { "Ralph run cancelled" } }
    private fun run(id: String): JsonObject = synchronized(this) { runs.obj(id)!!.deepCopy() }
    private fun config(id: String): JsonObject = run(id).obj("config")!!
    private fun directory(id: String) = config(id).str("workspaceDirectory")!!
    private fun call(id: String, method: String, path: String, body: JsonElement? = null) = request(method, path, body, directory(id))

    private fun loop(id: String, cancelled: AtomicBoolean) {
        while (true) {
            check(cancelled)
            val run = run(id)
            val config = config(id)
            val iterations = run.arr("iterations")!!
            val last = iterations.lastOrNull().asObjectOrNull()
            val plan = readPlan(config.str("planDocPath")!!, directory(id))?.takeIf { it.isNotBlank() }
                ?: error("Plan document is empty or unavailable")
            val unsettled = last != null && last.str("status") == "running"
            val gap = last.str("status") in setOf("failed", "unverified", "aborted")
            if (!unsettled && DONE.containsMatchIn(plan) && !gap) {
                synchronized(this) { finish(id, "done", "done_marker") }; return
            }
            val index = if (unsettled) last.int("index")!! else (last.int("index") ?: 0) + 1
            if (index > config.int("iterations")!!) {
                val incomplete = gap || (!DONE.containsMatchIn(plan) && Regex("(?m)^\\s*[-*+] \\[ \\]").containsMatchIn(plan))
                synchronized(this) { finish(id, if (incomplete) "incomplete" else "done", if (incomplete) "iteration_limit_with_gap" else "iteration_limit") }
                return
            }
            val iteration = if (unsettled) last else Json.obj("index" to index, "childSessionId" to null,
                "status" to "running", "phase" to "primary", "startedAt" to System.currentTimeMillis(),
                "endedAt" to null, "filesChanged" to JsonArray(), "verification" to JsonObject())
            putIteration(id, iteration)
            if (iteration.text("childSessionId") == null) {
                val child = createChild(id, "Ralph iteration $index", cancelled)
                iteration.addProperty("childSessionId", child)
                putIteration(id, iteration)
            }
            val child = iteration.str("childSessionId")!!
            if (iteration.str("phase") == "primary") {
                val replacements = mapOf("iteration" to "$index", "totalIterations" to "${config.int("iterations")}",
                    "planPath" to config.str("planDocPath")!!, "planContent" to plan,
                    "previousSummary" to (last.str("note") ?: "This is the first iteration."),
                    "planPathWarning" to "Read and update the exact plan path provided.", "verificationCommands" to VERIFY)
                val prompt = Regex("\\{\\{(\\w+)}}").replace(config.str("promptTemplate")!!) { replacements[it.groupValues[1]] ?: it.value }
                collectUsage(iteration, turn(id, child, iteration, prompt, cancelled))
                iteration.addProperty("phase", "verification")
                iteration.remove("promptId")
                putIteration(id, iteration)
            }
            var report: JsonArray
            if (iteration.str("phase") == "verification") {
                report = turn(id, child, iteration, VERIFY, cancelled)
                applyReport(iteration, child, report)
                putIteration(id, iteration)
            }
            var attempts = iteration.arr("repairSessionIds")?.size() ?: 0
            while ((iteration.obj("verification")?.entrySet()?.any { it.value.asString == "fail" } == true ||
                    iteration.str("phase") == "repair") && attempts <= 2) {
                check(cancelled)
                val repairIds = iteration.arr("repairSessionIds") ?: JsonArray().also { iteration.add("repairSessionIds", it) }
                val continuing = iteration.str("phase") == "repair" && iteration.text("promptId") != null
                if (!continuing && attempts >= 2) break
                val repair = if (continuing) repairIds.last().asString else {
                    val repairId = createChild(id, "Ralph repair $index/${attempts + 1}", cancelled)
                    repairIds.add(repairId)
                    attempts++
                    iteration.addProperty("phase", "repair")
                    iteration.remove("promptId")
                    putIteration(id, iteration)
                    repairId
                }
                report = turn(id, repair, iteration,
                    "Repair only the failing checks from iteration $index. Do not start new plan work. Plan: ${config.str("planDocPath")}\n" +
                        "Failures and summary: ${Json.stringify(iteration.obj("verification"))}\n${iteration.str("note")}\n$VERIFY", cancelled)
                applyReport(iteration, repair, report)
                iteration.addProperty("phase", "verification")
                iteration.remove("promptId")
                putIteration(id, iteration)
            }
            check(cancelled)
            iteration.addProperty("status", verificationStatus(iteration.obj("verification")!!))
            iteration.addProperty("endedAt", System.currentTimeMillis())
            iteration.remove("promptId")
            putIteration(id, iteration)
        }
    }

    private fun createChild(id: String, title: String, cancelled: AtomicBoolean): String {
        check(cancelled)
        val mode = config(id).str("permissionMode")
        val rules = when (mode) {
            "full" -> Json.array(listOf(Json.obj("permission" to "*", "pattern" to "*", "action" to "allow")))
            "auto" -> Json.array(listOf(Json.obj("permission" to "*", "pattern" to "*", "action" to "ask")) +
                listOf("read", "glob", "grep", "list", "codesearch", "lsp", "task", "todowrite", "question").map {
                    Json.obj("permission" to it, "pattern" to "*", "action" to "allow") })
            else -> JsonArray()
        }
        val child = call(id, "POST", "/session", Json.obj("title" to title, "parentID" to id, "permission" to rules))
            .asObjectOrNull().text("id") ?: error("Could not create Ralph child session")
        onChildCreated(child, mode ?: "default")
        if (cancelled.get() || closed) { runCatching { call(id, "POST", "/session/$child/abort") }; check(cancelled) }
        return child
    }

    private fun turn(id: String, child: String, iteration: JsonObject, prompt: String, cancelled: AtomicBoolean): JsonArray {
        check(cancelled)
        val existing = iteration.text("promptId")
        val promptId = existing ?: "msg_${java.util.UUID.randomUUID().toString().replace("-", "")}".also {
            iteration.addProperty("promptId", it)
            iteration.addProperty("promptStartedAt", System.currentTimeMillis())
            putIteration(id, iteration)
        }
        if (existing == null) {
            check(cancelled)
            val config = config(id)
            val model = config.obj("model")
            call(id, "POST", "/session/$child/prompt_async", Json.obj("messageID" to promptId,
                "model" to model, "variant" to model.str("variant"), "agent" to config.str("agent"),
                "parts" to Json.array(listOf(Json.obj("type" to "text", "text" to prompt)))))
        }
        val deadline = (iteration.long("promptStartedAt") ?: System.currentTimeMillis()) + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            check(cancelled)
            val messages = call(id, "GET", "/session/$child/message").asArrayOrNull() ?: error("Missing Ralph history")
            val promptIndex = messages.indexOfFirst { it.asObjectOrNull().obj("info").str("id") == promptId }
            val turn = if (promptIndex >= 0) Json.array(messages.drop(promptIndex)) else JsonArray()
            val response = turn.mapNotNull { it.asObjectOrNull() }.lastOrNull { it.obj("info").str("role") == "assistant" && it.obj("info").str("parentID") == promptId }
            val info = response.obj("info")
            if (info?.get("error")?.isJsonNull == false) error("Ralph assistant failed: ${Json.stringify(info.get("error"))}")
            val statuses = call(id, "GET", "/session/status").asObjectOrNull() ?: error("Missing session status")
            val idle = statuses.obj(child).str("type").let { it == null || it == "idle" }
            if (idle && info.obj("time").long("completed") != null && info.str("finish") !in setOf("tool-calls", "unknown")) {
                check(text(response).isNotBlank()) { "Ralph assistant returned no report" }
                return turn
            }
            Thread.sleep(pollMs)
        }
        error("Ralph prompt did not complete within ${timeoutMs / 60000} minutes. Inspect child $child before resuming.")
    }

    @Synchronized private fun putIteration(id: String, iteration: JsonObject) {
        if (closed || runs.obj(id).str("status") != "running") return
        val run = runs.obj(id)!!
        val list = run.arr("iterations")!!
        val index = list.indexOfFirst { it.asObjectOrNull().int("index") == iteration.int("index") }
        if (index < 0) list.add(iteration.deepCopy()) else list.set(index, iteration.deepCopy())
        run.addProperty("currentIteration", iteration.int("index"))
        run.addProperty("updatedAt", System.currentTimeMillis())
        save()
    }

    @Synchronized private fun cancel(id: String, pause: Boolean) {
        active[id]?.set(true)
        val run = runs.obj(id) ?: return
        val iteration = run.arr("iterations")?.lastOrNull().asObjectOrNull()
        if (iteration.str("status") == "running") {
            iteration?.addProperty("status", "aborted")
            iteration?.addProperty("endedAt", System.currentTimeMillis())
        }
        finish(id, if (pause) "paused" else "stopped", if (pause) null else "manual_stop")
        val roots = listOfNotNull(iteration.str("childSessionId")) + iteration?.arr("repairSessionIds")?.strings().orEmpty()
        executor.execute {
            val visited = mutableSetOf<String>()
            fun abortTree(child: String) {
                if (!visited.add(child)) return
                runCatching { call(id, "GET", "/session/$child/children").asArrayOrNull()?.forEach { it.asObjectOrNull().str("id")?.let(::abortTree) } }
                runCatching { call(id, "POST", "/session/$child/abort") }
            }
            roots.forEach(::abortTree)
        }
    }

    private fun finish(id: String, status: String, reason: String?, note: String? = null) {
        runs.obj(id)?.apply {
            addProperty("status", status); addProperty("updatedAt", System.currentTimeMillis())
            if (reason != null) addProperty("stopReason", reason) else remove("stopReason")
            if (note != null) addProperty("note", note)
        }
        save()
    }
    private fun save() { persist(runs.deepCopy()); publish(snapshot()) }
    override fun close() {
        closed = true
        active.values.forEach { it.set(true) }
        executor.shutdownNow()
    }

    companion object {
        private val DONE = Regex("(?m)^\\s*DONE\\s*$")
        private const val VERIFY = "Verify only the work just completed using checks this project configures and that match the changed files. Skip unrelated checks. Do not repeat checks already run unless matching files changed. Report each check on its own line as <name>: PASS, <name>: FAIL - cause, or <name>: SKIPPED - reason. Do not start new plan work."
        private fun text(message: JsonObject?): String = message.arr("parts")?.mapNotNull {
            val part = it.asObjectOrNull(); if (part.str("type") == "text") part.str("text") else null
        }?.joinToString("\n").orEmpty()

        fun parseVerification(text: String): JsonObject = JsonObject().apply {
            Regex("^[ \\t]*(?:[-*+]\\s+|\\d+[.)]\\s+)?[`*_]*([a-z][a-z0-9 _./+-]{0,30}?)[`*_]*\\s*[:\\-]\\s*(pass|fail|skipped)\\b",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).findAll(text).forEach {
                val name = it.groupValues[1].trim().lowercase().replace(Regex("\\s+"), " ")
                if (name.split(' ').size <= 3) addProperty(name, it.groupValues[2].lowercase())
            }
        }
        fun verificationStatus(verdicts: JsonObject): String {
            val values = verdicts.entrySet().map { it.value.asString }
            return if (values.isEmpty() || "fail" in values) "failed" else if ("pass" !in values) "unverified" else "passed"
        }

        fun applyReport(iteration: JsonObject, sessionId: String, messages: JsonArray) {
            collectUsage(iteration, messages)
            val report = messages.mapNotNull { it.asObjectOrNull() }.lastOrNull { it.obj("info").str("role") == "assistant" }
            val verification = parseVerification(text(report))
            val evidence = JsonObject()
            val candidates = mutableMapOf<String, MutableList<JsonObject>>()
            val files = iteration.arr("filesChanged")?.strings()?.toMutableSet() ?: mutableSetOf()
            messages.forEach { value ->
                val message = value.asJsonObject
                message.arr("parts")?.forEach partLoop@ { partValue ->
                    val part = partValue.asJsonObject
                    if (part.str("type") == "patch") files.addAll(part.arr("files")?.strings().orEmpty())
                    val state = part.obj("state")
                    if (part.str("tool") != "bash" || state.str("status") != "completed") return@partLoop
                    val command = state.obj("input").str("command")?.trim()?.replace(Regex(" +"), " ") ?: return@partLoop
                    val exit = state.obj("metadata").int("exit") ?: state.obj("metadata").int("exitCode") ?: return@partLoop
                    verification.entrySet().forEach { (name, verdict) ->
                        val commands = listOf(name, "npm run $name", "pnpm run $name", "yarn $name", "bun run $name") +
                            if (name == "test") listOf("npm test", "pnpm test", "bun test") else emptyList()
                        if (command in commands) candidates.getOrPut(name) { mutableListOf() }.add(Json.obj(
                            "sessionId" to sessionId, "messageId" to message.obj("info").str("id"), "partId" to part.str("id"),
                            "command" to command, "exitCode" to exit, "reportedVerdict" to verdict))
                    }
                }
            }
            candidates.filterValues { it.size == 1 }.forEach { (name, matches) ->
                evidence.add(name, matches.single())
                if (matches.single().int("exitCode") != 0 && verification.str(name) == "pass") verification.addProperty(name, "fail")
            }
            iteration.add("verification", verification)
            iteration.add("verificationEvidence", evidence)
            iteration.add("filesChanged", Json.array(files))
            iteration.addProperty("note", text(report).take(280))
        }

        private fun collectUsage(iteration: JsonObject, messages: JsonArray) {
            val seen = iteration.arr("accountedMessageIds")?.strings()?.toMutableSet() ?: mutableSetOf()
            val totals = iteration.obj("tokens") ?: JsonObject()
            var cost = iteration.num("cost") ?: 0.0
            val files = iteration.arr("filesChanged")?.strings()?.toMutableSet() ?: mutableSetOf()
            messages.forEach { value ->
                val message = value.asJsonObject
                message.arr("parts")?.filter { it.asObjectOrNull().str("type") == "patch" }?.forEach {
                    files.addAll(it.asObjectOrNull().arr("files")?.strings().orEmpty())
                }
                val info = message.obj("info")
                val messageId = info.str("id") ?: return@forEach
                if (info.str("role") != "assistant" || !seen.add(messageId)) return@forEach
                val tokens = info.obj("tokens")
                val values = mapOf("input" to tokens.long("input"), "output" to tokens.long("output"),
                    "reasoning" to tokens.long("reasoning"), "cacheRead" to tokens.obj("cache").long("read"),
                    "cacheWrite" to tokens.obj("cache").long("write"))
                values.forEach { (key, amount) -> totals.addProperty(key, (totals.long(key) ?: 0) + (amount ?: 0).coerceAtLeast(0)) }
                cost += info.num("cost") ?: 0.0
            }
            totals.addProperty("total", listOf("input", "output", "reasoning", "cacheWrite").sumOf { totals.long(it) ?: 0 })
            iteration.add("tokens", totals)
            iteration.addProperty("cost", cost)
            iteration.add("accountedMessageIds", Json.array(seen))
            iteration.add("filesChanged", Json.array(files))
        }
    }
}
