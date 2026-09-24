package varro.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Semantic v1-to-v2 adaptation, shared by the webview and host helper sessions. */
internal class OpenCodeV2Adapter(
    private val wire: (String, String, JsonElement?, RequestOptions) -> OpenCodeResponse,
    private val annotations: OpenCodeV2SessionState = OpenCodeV2SessionState(),
) {
    private val permissions = ConcurrentHashMap<String, String>()
    private val forms = ConcurrentHashMap<String, JsonObject>()
    private val contexts = ConcurrentHashMap<String, JsonObject>()
    private val inputTypes = ConcurrentHashMap<String, String>()
    private val failures = ConcurrentHashMap<String, JsonObject>()
    private val parents = ConcurrentHashMap<String, String>()
    private val submissions = Array(64) { Any() }
    private data class OAuthAttempt(val integration: String, val id: String, val provider: String, val directory: String?)
    private val oauth = ConcurrentHashMap<String, OAuthAttempt>()
    private val backgroundWork = OpenCodeV2BackgroundWork()

    fun reset() { permissions.clear(); forms.clear(); contexts.clear(); inputTypes.clear(); failures.clear(); parents.clear(); oauth.clear(); backgroundWork.reset() }

    fun events(event: JsonObject): List<JsonObject> {
        val type = event.str("type").orEmpty()
        val data = event.obj("data") ?: return emptyList()
        backgroundWork.observe(type, data, event.obj("location").str("directory"))
        val sessionID = data.str("sessionID")
        val context = sessionID?.let { id -> contexts.compute(id) { _, old -> (old?.deepCopy() ?: Json.obj()).apply {
            (event.obj("location") ?: data.obj("location")).str("directory")?.let { addProperty("directory", it) }
            if (type == "session.agent.selected") add("agent", data.get("agent"))
            if (type == "session.model.selected") add("model", data.get("model"))
            if (type == "session.inbox.enqueued") data.str("inboxID")?.let { inputTypes[it] = data.obj("item").str("type").orEmpty() }
            if (type == "session.inbox.delivered") data.str("inboxID")?.let {
                if (inputTypes.remove(it) == "user") { addProperty("parentID", it); addProperty("hasAssistant", false) }
            }
            if (type == "session.step.started") addProperty("hasAssistant", true)
            if (type == "session.execution.failed") event.str("id")?.let { failures[it.replaceFirst("evt_", "msg_")] = deepCopy().apply { add("error", data.get("error")) } }
        } } } ?: Json.obj()
        if (type == "permission.asked" && sessionID != null) data.str("id")?.let { permissions[it] = sessionID }
        if (type == "permission.replied") data.str("requestID")?.let(permissions::remove)
        if (type == "form.created") data.obj("form")?.let { form -> form.str("id")?.let { forms[it] = form } }
        if (type in setOf("form.replied", "form.cancelled")) data.str("id")?.let(forms::remove)
        for (map in listOf(contexts, failures)) while (map.size > 4096) map.keys.firstOrNull()?.let(map::remove)
        while (inputTypes.size > 4096) inputTypes.keys.firstOrNull()?.let(inputTypes::remove)
        return OpenCodeV2Events.project(event, context.deepCopy().apply {
            addProperty("backgroundPending", sessionID?.let(backgroundWork::isWaiting) == true)
            add("backgroundStartedAt", Json.toElement(sessionID?.let(backgroundWork::startedAt)))
        })
    }

    fun request(method: String, path: String, body: JsonElement?, options: RequestOptions): OpenCodeResponse {
        options.checkCancelled()
        val route = path.substringBefore('?')
        val query = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        // V2 instruction discovery expects the filesystem's uppercase Windows drive letter.
        val directory = (query["directory"] ?: options.directory)?.replace(Regex("^[a-z]:[\\\\/]")) { it.value.uppercase() }
        val input = body.asObjectOrNull() ?: Json.obj()
        fun scoped(target: String) = target + if (directory == null) "" else "${if ('?' in target) '&' else '?'}location%5Bdirectory%5D=${encode(directory)}"
        fun raw(verb: String, target: String, payload: JsonElement? = null): JsonElement? {
            options.checkCancelled()
            return wire(verb, target, payload, options.copy(unscoped = true, captureNextCursor = false)).data
        }
        fun data(verb: String, target: String, payload: JsonElement? = null): JsonElement {
            val result = raw(verb, target, payload).asObjectOrNull()
            return result?.get("data") ?: error("Invalid OpenCode v2 response for ${target.substringBefore('?')}")
        }
        fun result(value: Any?) = OpenCodeResponse(Json.toElement(value))
        if (method == "GET" && route == "/openapi.json") return result(raw("GET", "/openapi.json"))
        if (route.startsWith("/api/")) return result(raw(method, scoped(path), body))
        when (route) {
            "/global/health" -> return result(Json.obj("healthy" to true, "version" to raw("GET", "/api/info").asObjectOrNull().str("version")))
            "/global/dispose" -> { raw("POST", scoped("/api/location/reload"), Json.obj()); return result(true) }
            "/config", "/global/config" -> {
                require(method == "GET") { "OpenCode v2 configuration updates must target a configuration file" }
                val response = raw("GET", scoped("/api/config"))
                val entries = response.asArrayOrNull() ?: response.asObjectOrNull().arr("data") ?: error("Invalid OpenCode v2 configuration response")
                val config = Json.obj()
                entries.forEach { it.asObjectOrNull().obj("info")?.let { entry -> merge(config, entry) } }
                val agents = Json.obj()
                config.obj("agents")?.entrySet()?.forEach { (name, value) -> value.asObjectOrNull()?.let { agent -> agents.add(name, agent.deepCopy().apply {
                    addProperty("model", legacyModel(agent.get("model"))); add("prompt", agent.get("system")); add("permission", OpenCodeV2Projection.legacyRules(agent.get("permissions")))
                }) } }
                config.addProperty("model", legacyModel(config.get("model")))
                config.add("agent", agents); config.add("small_model", agents.obj("title")?.get("model"))
                config.add("provider", config.get("providers")); config.add("command", config.get("commands"))
                config.add("permission", OpenCodeV2Projection.legacyRules(config.get("permissions")))
                config.obj("compaction")?.let { it.add("reserved", it.get("buffer")) }
                return result(config)
            }
            "/agent" -> {
                val agents = objects(data("GET", scoped("/api/agent"))).map(OpenCodeV2Projection::agent).toMutableList()
                val config = request("GET", "/config", null, options).data.asObjectOrNull()
                config.obj("agent")?.entrySet()?.forEach { (name, value) -> value.asObjectOrNull()?.let { agent ->
                    val existing = agents.firstOrNull { it.str("name") == name }
                    agents.remove(existing)
                    if (agent.bool("disabled") != true) agents.add((existing ?: Json.obj("mode" to "primary", "hidden" to false)).apply {
                        agent.entrySet().forEach { add(it.key, it.value) }; addProperty("name", name)
                        OpenCodeV2Projection.modelRef(agent.get("model"))?.let { ref -> add("model", ref.apply { add("modelID", get("id")) }) }
                    })
                } }
                return result(agents)
            }
            "/provider", "/config/providers" -> return result(providers(route, directory, options))
            "/provider/auth" -> {
                val integrations = objects(data("GET", scoped("/api/integration")))
                val providers = objects(data("GET", scoped("/api/provider")))
                val methods = Json.obj()
                integrations.forEach { methods.add(it.str("id"), authMethods(it)) }
                providers.forEach { provider -> methods.add(provider.str("id"), authMethods(integrations.firstOrNull {
                    it.str("id") == (provider.str("integrationID") ?: provider.str("id")) })) }
                return result(methods)
            }
            "/model/default" -> {
                val config = request("GET", "/config", null, options).data.asObjectOrNull()
                val model = OpenCodeV2Projection.modelRef(config?.get("model")) ?: data("GET", scoped("/api/model/default")).asObjectOrNull()
                return result(model?.let { Json.obj("providerID" to it.get("providerID"), "modelID" to it.get("id"), "variant" to it.get("variant")) })
            }
            "/session/status" -> {
                val version = backgroundWork.snapshotVersion()
                val active = data("GET", "/api/session/active").asObjectOrNull() ?: error("Invalid OpenCode v2 active sessions")
                val shells = objects(data("GET", scoped("/api/shell")))
                val waiting = backgroundWork.reconcile(shells, active.keySet(), directory, version)
                return result(Json.obj().apply {
                active.entrySet().forEach { (id, status) ->
                    require(status.asObjectOrNull().str("type") == "running") { "Invalid OpenCode v2 active status" }
                    add(id, Json.obj("type" to "busy"))
                }
                waiting.forEach { add(it, Json.obj("type" to "busy", "background" to true, "backgroundStartedAt" to backgroundWork.startedAt(it))) }
                })
            }
            "/session", "/experimental/session" -> if (method == "GET") {
                val params = query.filterKeys { it in setOf("limit", "search", "parentID", "cursor", "order") }.toMutableMap()
                if (route == "/session" && directory != null) params["directory"] = directory
                if (query["roots"] == "true") params["parentID"] = "null"
                val response = raw("GET", withQuery("/api/session", params)).asObjectOrNull() ?: error("Invalid OpenCode v2 session list")
                return OpenCodeResponse(Json.array(objects(response.get("data")).map(::session)), if (options.captureNextCursor) response.obj("cursor").str("next") else null)
            } else if (method == "POST") {
                val payload = Json.obj("title" to input.get("title"), "id" to input.get("id"), "metadata" to input.get("metadata"),
                    "model" to OpenCodeV2Projection.modelRef(input.get("model")), "agent" to input.get("agent"),
                    "location" to directory?.let { Json.obj("directory" to it) })
                if (input.hasNonNull("permission")) payload.add("permissions", OpenCodeV2Projection.rules(input.get("permission")))
                val created = data("POST", "/api/session", payload).asJsonObject
                input.str("parentID")?.let { annotations.update(created.str("id")!!, Json.obj("parentID" to it), options::checkCancelled) }
                return result(session(created))
            }
            "/permission" -> {
                val pending = objects(data("GET", scoped("/api/permission/request")))
                pending.forEach { permissions[it.str("id")!!] = it.str("sessionID")!! }
                return result(pending.map(OpenCodeV2Projection::permission))
            }
            "/question" -> {
                val pending = objects(data("GET", scoped("/api/form")))
                pending.forEach { forms[it.str("id")!!] = it }
                return result(pending.map(OpenCodeV2Projection::form))
            }
            "/command" -> {
                val commands = objects(data("GET", scoped("/api/command"))).map { it.deepCopy().apply {
                    addProperty("description", it.str("description").orEmpty()); addProperty("template", ""); add("hints", JsonArray())
                } }.toMutableList()
                val config = request("GET", "/config", null, options).data.asObjectOrNull()
                config.obj("commands")?.entrySet()?.forEach { (name, value) -> value.asObjectOrNull()?.takeIf { it.str("template") != null }?.let {
                    commands.removeAll { it.str("name") == name }
                    commands.add(Json.obj("name" to name, "description" to it.str("description").orEmpty(), "template" to it.get("template"), "hints" to JsonArray()))
                } }
                return result(commands)
            }
            "/skill", "/project", "/vcs", "/vcs/status" -> return result(data("GET", scoped("/api$route")))
            "/project/current", "/path" -> {
                val location = raw("GET", scoped("/api/location")).asObjectOrNull() ?: error("Invalid OpenCode location")
                val project = location.obj("project") ?: Json.obj()
                return result(if (route == "/project/current") project.deepCopy().apply { add("worktree", get("directory")) }
                    else Json.obj("directory" to location.get("directory"), "worktree" to project.get("directory"), "config" to "", "state" to ""))
            }
            "/mcp" -> return result(Json.obj().apply { objects(data("GET", scoped("/api/mcp"))).forEach { add(it.str("name"), it.get("status")) } })
            "/lsp", "/experimental/workspace/status" -> return result(JsonArray())
        }
        Regex("^/(permission|question)/([^/]+)/(reply|reject)$").matchEntire(route)?.let { match ->
            val id = decode(match.groupValues[2])
            if (match.groupValues[1] == "permission") {
                if (!permissions.containsKey(id)) request("GET", "/permission", null, options)
                val owner = permissions[id] ?: error("OpenCode permission request is no longer pending")
                raw("POST", "/api/session/${encode(owner)}/permission/${encode(id)}/reply", Json.obj("decision" to input.get("reply"), "message" to input.get("message")))
                permissions.remove(id)
            } else {
                if (!forms.containsKey(id)) request("GET", "/question", null, options)
                val form = forms[id] ?: error("OpenCode question is no longer pending")
                val target = "/api/session/${encode(form.str("sessionID")!!)}/form/${encode(id)}"
                if (match.groupValues[3] == "reject") raw("DELETE", target)
                else {
                    val answers = input.arr("answers") ?: error("Invalid question answers")
                    val answer = Json.obj()
                    form.arr("fields").orEmpty().forEachIndexed { index, entry ->
                        val field = entry.asJsonObject
                        val selected = answers.getOrNull(index).asArrayOrNull().orEmpty().map { selected ->
                            field.arr("options").orEmpty().firstOrNull { it.asObjectOrNull().str("label") == selected.asString }.asObjectOrNull()?.get("value") ?: selected
                        }
                        answer.add(field.str("key"), when (field.str("type")) {
                            "multiselect" -> Json.array(selected)
                            "boolean" -> Json.toElement(selected.firstOrNull()?.asString == "Yes")
                            "number", "integer" -> Json.toElement(selected.firstOrNull()?.asString?.toDoubleOrNull() ?: error("Invalid numeric answer"))
                            else -> selected.firstOrNull() ?: Json.toElement("")
                        })
                    }
                    raw("POST", "$target/reply", Json.obj("answer" to answer))
                }
                forms.remove(id)
            }
            return result(true)
        }
        Regex("^/session/([^/]+)(?:/(.*))?$").matchEntire(route)?.let { match ->
            val id = decode(match.groupValues[1]); val endpoint = "/api/session/${encode(id)}"; val action = match.groupValues[2]
            if (action.isEmpty()) when (method) {
                "GET" -> return result(session(data("GET", endpoint).asJsonObject))
                "DELETE" -> { raw("DELETE", endpoint); annotations.remove(id); return result(true) }
                "PATCH" -> {
                    if (input.has("title") || input.has("permission")) raw("PATCH", endpoint, Json.obj("title" to input.get("title")).apply {
                        if (input.has("permission")) add("permissions", OpenCodeV2Projection.rules(input.get("permission")))
                    })
                    val patch = Json.obj()
                    for (key in listOf("metadata", "time")) if (input.has(key)) patch.add(key, input.get(key))
                    if (patch.size() > 0) annotations.update(id, patch, options::checkCancelled)
                    return result(session(data("GET", endpoint).asJsonObject))
                }
            }
            when (action) {
                "children" -> return result(objects(data("GET", "/api/session?parentID=${encode(id)}&limit=1000")).map(::session))
                "message" -> if (method == "GET") return messages(id, directory, query, options)
                "abort", "summarize" -> {
                    if (action == "abort" && backgroundWork.isWaiting(id)) {
                        backgroundWork.shellIDs(id).forEach { raw("DELETE", scoped("/api/shell/${encode(it)}")) }
                        backgroundWork.clearSession(id)
                    }
                    raw("POST", "$endpoint/${if (action == "abort") "interrupt?resume=false" else "compact"}", Json.obj()); return result(true)
                }
                "fork" -> return result(session(data("POST", "$endpoint/fork", Json.obj("before" to input.get("messageID"))).asJsonObject))
                "revert" -> { raw("POST", "$endpoint/revert/stage", Json.obj("messageID" to input.get("messageID"), "files" to true)); return result(session(data("GET", endpoint).asJsonObject)) }
                "unrevert" -> { raw("DELETE", "$endpoint/revert"); return result(session(data("GET", endpoint).asJsonObject)) }
                "diff" -> return result(data("GET", endpoint + "/diff" + (query["messageID"]?.let { "?from=${encode(it)}" } ?: "")))
                "todo" -> {
                    objects(data("GET", "$endpoint/message?limit=100&order=desc")).forEach { message ->
                        if (message.str("type") == "assistant") message.arr("content").orEmpty().reversed().forEach { content ->
                            val tool = content.asObjectOrNull()
                            if (tool.str("name") == "todowrite") tool.obj("state").obj("input").arr("todos")?.let { return result(it) }
                        }
                    }
                    return result(JsonArray())
                }
            }
            if (action.startsWith("message/")) {
                if (method == "GET") {
                    val message = data("GET", "$endpoint/$action").asJsonObject
                    return result(OpenCodeV2Projection.message(message, id, directory.orEmpty(), parents[message.str("id")].orEmpty(), contexts[id] ?: Json.obj()))
                }
                if (method == "DELETE") {
                    val messageID = decode(action.removePrefix("message/"))
                    val last = objects(data("GET", "$endpoint/message?limit=20&order=desc")).firstOrNull(OpenCodeV2Projection::transcript)
                    require(last.str("id") == messageID) { "OpenCode v2 can only delete messages from the end of the transcript" }
                    raw("POST", "$endpoint/revert/stage", Json.obj("messageID" to messageID, "files" to false)); raw("POST", "$endpoint/revert/commit", Json.obj())
                    return result(true)
                }
            }
            if (action in setOf("prompt_async", "prompt", "message", "command") && method == "POST") return synchronized(submissions[(id.hashCode() and Int.MAX_VALUE) % submissions.size]) {
                val model = OpenCodeV2Projection.modelRef(input.get("model"))?.apply { if (input.has("variant")) add("variant", input.get("variant")) }
                val parts = input.arr("parts").orEmpty().mapNotNull { it.asObjectOrNull() }
                val text = parts.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }
                if (action == "message" && (input.hasNonNull("system") || input.hasNonNull("format"))) {
                    if (model != null) raw("POST", "$endpoint/model", Json.obj("model" to model))
                    val prompt = listOfNotNull(input.str("system"), text, input.obj("format")?.takeIf { it.str("type") == "json_schema" }?.let { "Return only JSON matching this schema:\n${it.get("schema")}" }).filter { it.isNotBlank() }.joinToString("\n\n")
                    val generated = data("POST", "$endpoint/generate", Json.obj("prompt" to prompt)).asObjectOrNull()
                    return@synchronized result(Json.obj("info" to Json.obj("id" to "msg_${java.util.UUID.randomUUID().toString().replace("-", "")}", "sessionID" to id, "role" to "assistant"),
                        "parts" to listOf(Json.obj("type" to "text", "text" to generated.str("text").orEmpty()))))
                }
                input.str("agent")?.let { raw("POST", "$endpoint/agent", Json.obj("agent" to it)) }
                if (model != null) raw("POST", "$endpoint/model", Json.obj("model" to model))
                if (data("GET", endpoint).asObjectOrNull().hasNonNull("revert")) raw("POST", "$endpoint/revert/commit", Json.obj())
                input.str("system")?.let { raw("PUT", "/api/experimental/session/${encode(id)}/instructions/entries/varro.system", Json.obj("value" to it)) }
                val payload = Json.obj("id" to input.get("messageID"), "text" to if (action == "command") input.str("arguments") ?: text else text,
                    "files" to parts.filter { it.str("type") == "file" }.map { Json.obj("uri" to it.get("url"), "name" to it.get("filename")) },
                    "agents" to parts.filter { it.str("type") == "agent" }.map { Json.obj("name" to it.get("name")) },
                    "delivery" to if (input.str("delivery") == "queue") "queue" else "steer")
                if (input.bool("noReply") == true) payload.addProperty("resume", false)
                if (action == "command") payload.add("name", input.get("command"))
                val admitted = raw("POST", "$endpoint/${if (action == "command") "command" else "prompt"}", payload)
                if (action in setOf("prompt_async", "command") || input.bool("noReply") == true) return@synchronized result(admitted)
                raw("POST", "/api/experimental/session/${encode(id)}/wait", Json.obj())
                val message = objects(data("GET", "$endpoint/message?limit=50&order=desc")).firstOrNull { it.str("type") == "assistant" } ?: error("OpenCode finished without an assistant message")
                result(OpenCodeV2Projection.message(message, id, directory.orEmpty()))
            }
        }
        Regex("^/mcp/([^/]+)/(connect|disconnect)$").matchEntire(route)?.let {
            raw("POST", scoped("/api/experimental/mcp/${it.groupValues[1]}/${it.groupValues[2]}"), Json.obj()); return result(true)
        }
        Regex("^/(?:auth|provider)/([^/]+)(?:/oauth/(authorize|callback))?$").matchEntire(route)?.let {
            return result(authenticate(decode(it.groupValues[1]), it.groupValues[2], method, input, directory, options))
        }
        Regex("^/mcp/([^/]+)/auth(?:/(authenticate|callback))?$").matchEntire(route)?.let { match ->
            val name = decode(match.groupValues[1]); val action = match.groupValues[2]
            val integration = objects(data("GET", scoped("/api/mcp"))).firstOrNull { it.str("name") == name }.str("integrationID")
                ?: error("This MCP server does not expose an OpenCode authentication integration")
            val key = "mcp:$name"
            if (method == "DELETE") { authenticate(key, "", method, input, directory, options, integration); return result(Json.obj("success" to true)) }
            if (action == "callback") { authenticate(key, "callback", "POST", input, directory, options, integration); return result(request("GET", "/mcp", null, options).data.asObjectOrNull()?.get(name)) }
            val authorization = authenticate(key, "authorize", "POST", input, directory, options, integration).asJsonObject
            if (action != "authenticate") return result(Json.obj("authorizationUrl" to authorization.get("url"), "oauthState" to authorization.get("attemptID")))
            com.intellij.ide.BrowserUtil.browse(authorization.str("url") ?: error("Missing MCP authentication URL"))
            authenticate(key, "callback", "POST", Json.obj("attemptID" to authorization.get("attemptID")), directory, options, integration)
            return result(true)
        }
        error("OpenCode v2 does not support this Varro operation: $method $route")
    }

    private fun session(value: JsonObject): JsonObject {
        val result = OpenCodeV2Projection.session(value)
        val state = annotations.read(value.str("id") ?: error("Invalid OpenCode session"))
        val time = (result.obj("time") ?: Json.obj()).deepCopy()
        state.obj("time")?.entrySet()?.forEach { time.add(it.key, it.value) }
        state.entrySet().forEach { result.add(it.key, it.value) }
        result.add("time", time)
        return result
    }

    private fun messages(id: String, directory: String?, query: Map<String, String>, options: RequestOptions): OpenCodeResponse {
        var bytes = 0L
        fun raw(path: String): JsonElement? {
            val response = wire("GET", path, null, options.copy(unscoped = true, captureNextCursor = false)).data
            bytes += response.toString().toByteArray(Charsets.UTF_8).size
            if (bytes > options.maxResponseBytes) throw OpenCodeResponseTooLargeException(options.maxResponseBytes)
            return response
        }
        val endpoint = "/api/session/${encode(id)}"
        var cursor = query["before"]
        val limit = query["limit"]?.toIntOrNull() ?: Int.MAX_VALUE
        require(limit > 0) { "Invalid message page limit" }
        val inbox = if (cursor != null) emptyList() else objects(raw("$endpoint/inbox").asObjectOrNull()?.get("data"))
        val records = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        var count = 0
        do {
            val page = raw(withQuery("$endpoint/message", mapOf("limit" to minOf(200, limit - count).toString()) +
                if (cursor == null) mapOf("order" to "desc") else mapOf("cursor" to cursor))).asObjectOrNull() ?: error("Invalid message page")
            val entries = objects(page.get("data")); records.addAll(entries); count += entries.count(OpenCodeV2Projection::transcript)
            cursor = page.obj("cursor").str("next")
            require(cursor == null || seen.add(cursor)) { "OpenCode repeated a message pagination cursor" }
        } while (cursor != null && count < limit)
        val ordered = records.asReversed()
        val firstAssistant = ordered.indexOfFirst { it.str("type") in setOf("assistant", "skill", "shell") || (it.str("type") == "idle" && it.str("outcome") == "failed") }
        val firstUser = ordered.indexOfFirst { it.str("type") == "user" }
        var parent = ordered.getOrNull(firstAssistant)?.str("id")?.let { parents[it] }.orEmpty()
        var assistantFailed = false
        if (parent.isEmpty() && firstAssistant >= 0 && (firstUser < 0 || firstAssistant < firstUser)) {
            var contextCursor = cursor
            val contextSeen = mutableSetOf<String>()
            while (contextCursor != null) {
                require(contextSeen.add(contextCursor)) { "OpenCode repeated a context pagination cursor" }
                val page = raw("$endpoint/message?limit=200&cursor=${encode(contextCursor)}").asObjectOrNull() ?: error("Invalid context page")
                val entries = objects(page.get("data"))
                val preceding = entries.takeWhile { it.str("type") != "user" }
                assistantFailed = assistantFailed || preceding.any { it.str("type") == "assistant" && it.hasNonNull("error") }
                val user = entries.firstOrNull { it.str("type") == "user" }
                if (user != null) { parent = user.str("id").orEmpty(); break }
                if (entries.isEmpty()) break
                contextCursor = page.obj("cursor").str("next")
            }
        }
        val context = contexts[id]?.deepCopy() ?: Json.obj()
        val messages = mutableListOf<JsonObject>()
        ordered.forEach { message ->
            if (message.str("type") == "agent-switched") context.add("agent", message.get("agent"))
            if (message.str("type") == "model-switched") context.add("model", message.get("model"))
            if (!OpenCodeV2Projection.transcript(message) || (message.str("type") == "idle" && assistantFailed)) return@forEach
            val details = context.deepCopy().apply { failures[message.str("id")]?.entrySet()?.forEach { add(it.key, it.value) } }
            val projected = OpenCodeV2Projection.message(message, id, directory.orEmpty(), parent, details)
            if (message.str("type") == "user") { parent = message.str("id").orEmpty(); assistantFailed = false }
            if (message.str("type") == "assistant" && message.hasNonNull("error")) assistantFailed = true
            if (projected.obj("info").str("role") == "assistant" && parent.isNotEmpty()) parents[message.str("id")!!] = parent
            messages.add(projected)
        }
        while (parents.size > 4096) parents.keys.firstOrNull()?.let(parents::remove)
        val ids = messages.map { it.obj("info").str("id") }.toMutableSet()
        inbox.sortedBy { it.obj("time").long("created") ?: 0 }.forEach { item ->
            if (item.str("type") == "user" && ids.add(item.str("id"))) messages.add(OpenCodeV2Projection.message(
                (item.obj("payload") ?: Json.obj()).deepCopy().apply { add("id", item.get("id")); addProperty("type", "user"); add("time", item.get("time")) }, id, directory.orEmpty(), context = context).apply {
                    obj("info")!!.add("pendingDelivery", item.get("delivery"))
                })
        }
        return OpenCodeResponse(Json.array(messages), if (options.captureNextCursor) cursor else null)
    }

    private fun providers(route: String, directory: String?, options: RequestOptions): JsonObject {
        val suffix = directory?.let { "?location%5Bdirectory%5D=${encode(it)}" }.orEmpty()
        fun catalog(path: String) = objects(wire("GET", path + suffix, null, options.copy(unscoped = true)).data.asObjectOrNull()?.get("data"))
        val models = catalog("/api/model")
        val integrations = catalog("/api/integration")
        fun connectionInfo(target: JsonObject, integration: JsonObject?) {
            val connections = objects(integration?.get("connections") ?: JsonArray())
            target.add("env", Json.array(connections.filter { it.str("type") == "env" }.mapNotNull { it.str("name") }))
            target.addProperty("source", when {
                connections.any { it.str("type") == "credential" } -> "api"
                connections.any { it.str("type") == "env" } -> "env"
                else -> "custom"
            })
        }
        val all = catalog("/api/provider").map { provider -> provider.deepCopy().apply {
            if (provider.str("integrationID").isNullOrEmpty() && provider.str("id") in setOf("ollama", "lmstudio", "vllm")) addProperty("disconnectMode", "disable")
            connectionInfo(this, integrations.firstOrNull { it.str("id") == (provider.str("integrationID") ?: provider.str("id")) })
            add("options", provider.obj("settings") ?: Json.obj())
            add("models", Json.obj().apply { models.filter { it.str("providerID") == provider.str("id") }.forEach { add(it.str("id"), OpenCodeV2Projection.model(it)) } })
        } }.toMutableList()
        integrations.forEach { integration ->
            if (all.none { it.str("id") == integration.str("id") || it.str("integrationID") == integration.str("id") }) all.add(Json.obj(
                "id" to integration.get("id"), "integrationID" to integration.get("id"), "name" to integration.get("name"), "options" to Json.obj(), "models" to Json.obj()).apply { connectionInfo(this, integration) })
        }
        val config = request("GET", "/config", null, options.copy(directory = directory)).data.asObjectOrNull()
        config.obj("providers")?.entrySet()?.forEach { (id, entry) -> entry.asObjectOrNull()?.let { provider ->
            val policy = config.obj("experimental").arr("policies")?.lastOrNull {
                it.asObjectOrNull().str("action") == "provider.use" && it.asObjectOrNull().str("resource") == id
            }
            if (policy.asObjectOrNull().str("effect") == "deny") return@forEach
            val target = all.firstOrNull { it.str("id") == id } ?: Json.obj("id" to id, "name" to (provider.str("name") ?: id), "env" to JsonArray(),
                "source" to "config", "options" to Json.obj(), "models" to Json.obj()).also {
                    if (id in setOf("ollama", "lmstudio", "vllm")) it.addProperty("disconnectMode", "disable")
                    all.add(it)
                }
            if (target.str("source") == "custom") target.addProperty("source", "config")
            provider.obj("models")?.entrySet()?.forEach { (modelID, definition) -> definition.asObjectOrNull()?.let { model ->
                val normalized = Json.obj("id" to modelID, "providerID" to id, "name" to (model.str("name") ?: modelID),
                    "api" to Json.obj("id" to (model.str("modelID") ?: modelID), "npm" to provider.str("package").orEmpty(), "url" to ""),
                    "cost" to Json.obj("input" to 0, "output" to 0, "cache_read" to 0, "cache_write" to 0),
                    "capabilities" to Json.obj("tools" to true, "input" to listOf("text"), "output" to listOf("text")), "limit" to Json.obj("context" to 0, "output" to 0))
                target.obj("models").obj(modelID)?.entrySet()?.forEach { normalized.add(it.key, it.value) }
                model.entrySet().forEach { normalized.add(it.key, it.value) }
                val existing = target.obj("models").obj(modelID)
                normalized.add("cost", if (model.hasNonNull("cost")) OpenCodeV2Projection.modelCost(model.get("cost"))
                    else existing?.get("cost") ?: OpenCodeV2Projection.modelCost(null))
                normalized.add("limit", Json.obj("context" to 0, "output" to 0).apply {
                    existing.obj("limit")?.entrySet()?.forEach { add(it.key, it.value) }
                    model.obj("limit")?.entrySet()?.forEach { add(it.key, it.value) }
                })
                normalized.addProperty("enabled", model.bool("disabled") != true)
                if (model.arr("variants") != null) normalized.add("variants", Json.obj().apply { model.arr("variants")!!.forEach { add(it.asObjectOrNull().str("id"), it) } })
                if (!normalized.has("variants")) normalized.add("variants", Json.obj())
                target.obj("models")!!.add(modelID, normalized)
            } }
        } }
        val defaults = Json.obj().apply { all.forEach { provider -> addProperty(provider.str("id"), provider.obj("models")?.entrySet()?.firstOrNull { it.value.asObjectOrNull().bool("enabled") != false }?.key.orEmpty()) } }
        return if (route == "/config/providers") Json.obj("providers" to all, "default" to defaults)
        else Json.obj("all" to all, "default" to defaults, "connected" to all.filter { it.obj("models")?.entrySet()?.any { it.value.asObjectOrNull().bool("enabled") != false } == true }.map { it.str("id") })
    }

    private fun authenticate(provider: String, action: String, method: String, input: JsonObject, directory: String?, options: RequestOptions, override: String? = null): JsonElement {
        fun raw(verb: String, path: String, body: JsonElement? = null): JsonElement? {
            options.checkCancelled()
            return wire(verb, path + directory?.let { "?location%5Bdirectory%5D=${encode(it)}" }.orEmpty(), body, options.copy(unscoped = true)).data
        }
        fun data(verb: String, path: String, body: JsonElement? = null) = raw(verb, path, body).asObjectOrNull()?.get("data") ?: error("Invalid OpenCode authentication response")
        var integration = override ?: provider
        if (override == null) try { data("GET", "/api/provider/${encode(provider)}").asObjectOrNull().str("integrationID")?.let { integration = it } }
        catch (failure: Exception) { if (failure.message?.startsWith("404 ") != true) throw failure }
        val base = "/api/integration/${encode(integration)}"
        if (method == "PUT" && input.str("type") == "api") {
            val form = data("GET", base).asObjectOrNull().arr("methods").orEmpty().mapNotNull { it.asObjectOrNull() }.firstOrNull { it.str("type") == "key" }.arr("form")
            raw("POST", "$base/connect/key", Json.obj("key" to input.get("key"), "answer" to authAnswers(form, input.get("metadata"))))
            return Json.toElement(true)
        }
        if (method == "DELETE") {
            val connections = data("GET", base).asObjectOrNull().arr("connections").orEmpty().mapNotNull { it.asObjectOrNull() }
            val credentials = connections.filter { it.str("type") == "credential" }
            require(credentials.isNotEmpty() || connections.isEmpty()) { "Remove the provider environment variable to disconnect this OpenCode integration" }
            credentials.forEach { raw("DELETE", "/api/credential/${encode(it.str("id")!!)}") }
            return Json.toElement(true)
        }
        if (action == "authorize") {
            val methods = data("GET", base).asObjectOrNull().arr("methods").orEmpty().mapNotNull { it.asObjectOrNull() }.filter { it.str("type") in setOf("key", "oauth") }
            val selected = methods.getOrNull(input.int("method") ?: 0)
            require(selected.str("type") == "oauth") { "Unsupported OpenCode authentication method" }
            val attempt = data("POST", "$base/connect/oauth", Json.obj("methodID" to selected?.get("id"), "answer" to authAnswers(selected.arr("form"), input.get("inputs")))).asObjectOrNull()
            val id = attempt.str("attemptID") ?: error("Invalid OpenCode OAuth attempt")
            oauth[id] = OAuthAttempt(integration, id, provider, directory)
            return Json.obj("attemptID" to id, "url" to attempt?.get("url"), "method" to if (attempt.str("mode") == "code") "code" else "auto", "instructions" to attempt.str("instructions").orEmpty())
        }
        if (action == "callback") {
            val candidates = oauth.values.filter { it.provider == provider && it.directory == directory }
            val attempt = (input.str("attemptID")?.let { id -> candidates.firstOrNull { it.id == id } }
                ?: if (!input.hasNonNull("attemptID")) candidates.singleOrNull() else null) ?: error("OpenCode OAuth attempt was not started")
            val endpoint = "/api/integration/${encode(attempt.integration)}/connect/oauth/${encode(attempt.id)}"
            var completed = false
            try {
                if (input.hasNonNull("code")) raw("POST", "$endpoint/complete", Json.obj("code" to input.get("code")))
                val deadline = System.currentTimeMillis() + 300_000
                while (true) {
                    val status = data("GET", endpoint).asObjectOrNull()
                    options.checkCancelled()
                    if (status.str("status") == "complete") break
                    require(status.str("status") == "pending" && System.currentTimeMillis() < deadline) { status.str("message") ?: "OpenCode authentication did not complete" }
                    Thread.sleep(500)
                }
                completed = true
                return Json.toElement(true)
            } finally {
                oauth.remove(attempt.id, attempt)
                if (!completed) runCatching {
                    wire("DELETE", endpoint + directory?.let { "?location%5Bdirectory%5D=${encode(it)}" }.orEmpty(), null,
                        options.copy(unscoped = true, isCancelled = { false }, timeoutMs = 5_000))
                }
            }
        }
        error("This credential operation requires OpenCode v2 credential management")
    }

    private fun authMethods(integration: JsonObject?) = Json.array(integration.arr("methods").orEmpty().mapNotNull { it.asObjectOrNull() }
        .filter { it.str("type") in setOf("oauth", "key") }.map { method -> Json.obj("type" to if (method.str("type") == "key") "api" else "oauth", "label" to (method.str("label") ?: "API key"),
            "prompts" to method.arr("form").orEmpty().mapNotNull { it.asObjectOrNull() }.filter { it.str("type") != "external" }.map { field ->
                Json.obj("key" to field.get("key"), "message" to (field.str("title") ?: field.str("description") ?: field.str("key")),
                    "required" to (field.bool("required") == true), "hidden" to field.get("hidden"),
                    "default" to field.get("default")?.takeIf { it.isJsonPrimitive }?.asString,
                    "placeholder" to field.get("placeholder"), "when" to field.arr("when")?.map { condition -> condition.asJsonObject.deepCopy().apply { addProperty("value", get("value").asString) } },
                    "type" to if (field.str("type") == "boolean" || (field.str("type") == "string" && field.has("options"))) "select" else "text",
                    "options" to if (field.str("type") == "boolean") listOf(Json.obj("value" to "true", "label" to "Yes"), Json.obj("value" to "false", "label" to "No"))
                    else field.arr("options")?.map { option ->
                        Json.obj("label" to option.asObjectOrNull().str("label"), "value" to option.asObjectOrNull()?.get("value")?.asString, "hint" to option.asObjectOrNull().str("description")) })
            }) })

    private fun authAnswers(form: JsonArray?, value: JsonElement?): JsonElement? {
        val answer = value.asObjectOrNull()?.deepCopy() ?: Json.obj()
        val fields = form.orEmpty().mapNotNull { it.asObjectOrNull() }
        fields.forEach { field ->
            val key = field.str("key") ?: return@forEach
            val input = answer.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return@forEach
            when (field.str("type")) {
                "boolean" -> {
                    require(input in setOf("true", "false")) { "Invalid boolean answer for ${field.str("title") ?: key}" }
                    answer.addProperty(key, input == "true")
                }
                "number", "integer" -> {
                    val number = input.toDoubleOrNull()
                    require(number != null && number.isFinite() && (field.str("type") != "integer" || number % 1.0 == 0.0)) { "Invalid ${field.str("type")} answer for ${field.str("title") ?: key}" }
                    answer.addProperty(key, number)
                }
            }
        }
        fields.forEach { field ->
            val key = field.str("key") ?: return@forEach
            if (field.str("type") == "external" || field.bool("hidden") != true || answer.has(key)) return@forEach
            val excluded = field.arr("when").orEmpty().any { entry ->
                val condition = entry.asObjectOrNull()
                val equal = answer.get(condition.str("key")) == condition?.get("value")
                if (condition.str("op") == "eq") !equal else equal
            }
            if (!excluded && field.has("default")) answer.add(key, field.get("default"))
        }
        return if (answer.size() > 0) answer else value
    }

    companion object {
        private fun objects(value: JsonElement?): List<JsonObject> = value.asArrayOrNull()?.map { it.asObjectOrNull() ?: error("Invalid OpenCode v2 record") } ?: error("Invalid OpenCode v2 list")
        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
        private fun decode(value: String) = URLDecoder.decode(value, Charsets.UTF_8)
        private fun withQuery(path: String, query: Map<String, String>) = path + if (query.isEmpty()) "" else query.entries.joinToString("&", "?") { "${encode(it.key)}=${encode(it.value)}" }
        private fun legacyModel(value: JsonElement?): String? {
            if (value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) return value.asString
            val model = value.asObjectOrNull() ?: return null
            return "${model.str("providerID") ?: return null}/${model.str("model") ?: model.str("id") ?: return null}${model.str("variant")?.let { "#$it" }.orEmpty()}"
        }
        private fun merge(target: JsonObject, patch: JsonObject) {
            patch.entrySet().forEach { (key, value) ->
                if (key in setOf("__proto__", "constructor", "prototype")) return@forEach
                if (value.isJsonObject) { val nested = target.obj(key) ?: Json.obj(); merge(nested, value.asJsonObject); target.add(key, nested) }
                else target.add(key, value)
            }
        }
    }
}
