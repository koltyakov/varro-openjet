package varro.host

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import varro.protocol.*
import varro.server.OpenCodeResponse
import varro.server.RequestOptions
import varro.server.WorkspacePaths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Saved inventories are read through open roots, never by loading an unapproved location. */
class SessionLocations(
    private val request: (String, String, JsonElement?, RequestOptions) -> OpenCodeResponse,
    private val apiVersion: () -> Int,
    private val connectionKey: () -> Any,
    private val roots: () -> List<String>,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Inventory(val expires: Long, val directories: Set<String>)
    private data class Grant(val connection: Any, val root: String, val directory: String, val projectID: String)
    private val inventories = ConcurrentHashMap<List<Any?>, Inventory>()
    private val grants = ConcurrentHashMap<String, Grant>()
    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private fun options(root: String?, cancelled: () -> Boolean = { false }): RequestOptions {
        val key = connectionKey()
        val openRoots = roots().map(WorkspacePaths::normalizeIdentity)
        val deadline = nanoTime() + TimeUnit.SECONDS.toNanos(5)
        return RequestOptions(directory = root, unscoped = root == null, timeoutMs = 5_000,
            maxResponseBytes = 2 * 1024 * 1024, cancellableBody = true, isCancelled = {
                check(key == connectionKey()) { "Connection changed while verifying conversation location" }
                check(openRoots == roots().map(WorkspacePaths::normalizeIdentity)) { "Workspace folders changed while verifying conversation location" }
                check(nanoTime() < deadline) { "Conversation location verification timed out" }
                cancelled()
            })
    }

    private fun read(path: String, options: RequestOptions): JsonElement? {
        options.checkCancelled()
        return request("GET", path, null, options).data.also { options.checkCancelled() }
    }

    /** Reference lookup does not grant access to the destination's catalog, events or writes. */
    fun reference(id: String, hidden: () -> Boolean, authorized: (String) -> Boolean = { false },
        cancelled: () -> Boolean = { false }): JsonObject {
        require(!hidden() && id.matches(Regex("[A-Za-z0-9_-]{1,512}"))) { "404 Session not found" }
        val root = roots().firstOrNull() ?: error("Workspace folder is not open")
        val options = options(root, cancelled)
        val session = read("/session/${encode(id)}", options).asObjectOrNull()
        val directory = session.text("directory")
        require(session.str("id") == id && session?.get("title")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true &&
            directory != null && directory.length <= 4096 && directory.none(Character::isISOControl) &&
            WorkspacePaths.isAbsolute(directory)) { "404 Session not found" }
        val external = roots().none { WorkspacePaths.isSame(it, directory) } && !authorized(directory)
        options.checkCancelled()
        require(!hidden()) { "404 Session not found" }
        return Json.obj("id" to id, "title" to session.str("title"), "directory" to directory, "external" to external)
    }

    /** This capability belongs only to a retained session, not every session in the destination. */
    fun authorizeTransferred(transfer: JsonObject): Boolean {
        val id = transfer.text("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,512}")) } ?: return false
        grants.remove(id)
        val directory = transfer.text("directory")?.takeIf {
            it.length <= 4096 && it.none(Character::isISOControl) && WorkspacePaths.isAbsolute(it)
        } ?: return false
        val projectID = transfer.text("projectID")?.takeUnless { it == "global" } ?: return false
        val key = connectionKey()
        val open = roots()
        return try {
            val options = options(open.firstOrNull())
            open.any { root ->
                val scoped = options.copy(directory = root, unscoped = false)
                val project = read("/project/current", scoped).asObjectOrNull()
                if (project.str("id") != projectID) return@any false
                val projects = read("/project", scoped).asArrayOrNull() ?: return@any false
                if (projects.none { it.asObjectOrNull().str("id") == projectID && it.asObjectOrNull().str("vcs") == "git" }) return@any false
                // Authorization always uses fresh inventory, not the presentation cache.
                val worktrees = read("/experimental/worktree?projectID=${encode(projectID)}", scoped).asArrayOrNull()
                    ?: return@any false
                if (worktrees.none {
                    val info = it.asObjectOrNull()
                    val path = if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else info.str("directory")
                    WorkspacePaths.isSame(path, directory) && (info?.has("strategy") != true || info.str("strategy") == "git")
                }) return@any false
                val session = read("/session/${encode(id)}", scoped).asObjectOrNull()
                options.checkCancelled()
                val verified = session.str("id") == id && session.str("projectID") == projectID &&
                    WorkspacePaths.isSame(session.str("directory"), directory) &&
                    key == connectionKey() && roots().any { WorkspacePaths.isSame(it, root) }
                if (verified) {
                    if (grants.size >= 512) grants.clear()
                    grants[id] = Grant(key, root, directory, projectID)
                }
                verified
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Catalog presentation may reuse a verified grant; every action still verifies it afresh. */
    fun knownTransferred(transfer: JsonObject): Boolean {
        val grant = grants[transfer.str("id")] ?: return false
        return grant.connection == connectionKey() && grant.projectID == transfer.str("projectID") &&
            WorkspacePaths.isSame(grant.directory, transfer.str("directory")) &&
            roots().any { WorkspacePaths.isSame(it, grant.root) }
    }

    private fun gitWorktrees(projectID: String, root: String, cancelled: () -> Boolean): Set<String> {
        val key = listOf(connectionKey(), projectID, WorkspacePaths.normalizeIdentity(root))
        val inventory = inventories.compute(key) { _, previous ->
            if (previous != null && previous.expires > nanoTime()) previous else {
                val value = read("/experimental/worktree?projectID=${encode(projectID)}", options(root, cancelled))
                    .asArrayOrNull() ?: error("Malformed project worktree inventory")
                Inventory(nanoTime() + TimeUnit.SECONDS.toNanos(5), value.mapNotNull {
                    val info = it.asObjectOrNull()
                    info.text("directory")?.takeIf { info.str("strategy") == "git" }?.let(WorkspacePaths::normalizeIdentity)
                }.toSet())
            }
        }!!
        if (inventories.size > 256) inventories.keys.filter { it != key }.forEach(inventories::remove)
        return inventory.directories
    }

    fun annotate(items: JsonArray, cancelled: () -> Boolean): JsonArray {
        if (apiVersion() != 2) return items
        val root = roots().firstOrNull() ?: return items
        val projects = items.mapNotNull { it.asObjectOrNull().text("projectID") }.filter { it != "global" }.distinct()
        val known = projects.mapNotNull { project ->
            try { project to gitWorktrees(project, root, cancelled) }
            catch (failure: Exception) {
                if (cancelled() || failure is java.util.concurrent.CancellationException) throw failure
                null
            }
        }.toMap()
        return Json.array(items.map { value ->
            val session = value.asObjectOrNull() ?: return@map value
            val inventory = known[session.str("projectID")] ?: return@map value
            session.deepCopy().apply { addProperty("isGitWorktree", WorkspacePaths.normalizeIdentity(str("directory")) in inventory) }
        })
    }

    fun location(id: String, directory: String?, retained: JsonObject?, historyScope: String,
        hidden: Boolean = false, cancelled: () -> Boolean = { false }): JsonObject {
        require(!hidden && id.matches(Regex("[A-Za-z0-9_-]{1,512}"))) { "404 Session not found" }
        val root = roots().firstOrNull() ?: error("Workspace folder is not open")
        val options = options(root, cancelled)
        val session = read("/session/${encode(id)}", options).asObjectOrNull()
        val current = session.text("directory")
        val projectID = session.text("projectID")
        require(session.str("id") == id && current != null && projectID != null && current.length <= 4096 &&
            current.none(Character::isISOControl) && WorkspacePaths.isAbsolute(current)) { "Malformed session location" }
        require(directory == null || WorkspacePaths.isSame(directory, current)) { "Conversation location changed. Refresh before opening its folder." }
        require(retained.str("id") == id && WorkspacePaths.isSame(retained.str("directory"), current) ||
            FileReferences.authorizeDirectory(id, current, roots(), historyScope, { session },
                { open -> read("/project/current", options.copy(directory = open)).asObjectOrNull() })) { "404 Session not found" }
        val worktree = try {
            if (apiVersion() == 2) WorkspacePaths.normalizeIdentity(current) in gitWorktrees(projectID, root, cancelled)
            else {
                val worktrees = read("/experimental/worktree?projectID=${encode(projectID)}", options).asArrayOrNull()
                worktrees?.any {
                    if (it.isJsonPrimitive && it.asJsonPrimitive.isString) {
                        val project = read("/project/current", options).asObjectOrNull()
                        WorkspacePaths.isSame(it.asString, current) && project.str("id") == projectID && project.str("vcs") == "git" &&
                            !WorkspacePaths.isSame(project.str("worktree"), current)
                    } else it.asObjectOrNull().let { info -> info.str("strategy") == "git" && WorkspacePaths.isSame(info.str("directory"), current) }
                } == true
            }
        } catch (failure: Exception) {
            options.checkCancelled()
            false
        }
        return Json.obj("directory" to current, "isGitWorktree" to worktree).apply {
            if (worktree) {
                for (open in roots()) {
                    val project = read("/project/current", options.copy(directory = open)).asObjectOrNull()
                    if (project.str("id") != projectID) continue
                    val target = if (WorkspacePaths.isSame(open, current)) project.text("canonical") ?: project.text("worktree") else open
                    if (target != null && !WorkspacePaths.isSame(target, current)) addProperty("projectDirectory", target)
                    break
                }
            }
            options.checkCancelled()
        }
    }
}
