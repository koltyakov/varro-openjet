package varro.host

import com.google.gson.JsonObject
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFrame
import com.intellij.ui.SystemNotifications
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.util.Alarm
import varro.protocol.*
import varro.server.OpenCodeServer
import varro.server.ParsedServerEvent
import varro.server.RequestOptions
import varro.server.ServerStatus
import varro.server.WorkspacePaths
import varro.settings.VarroSettings
import varro.store.VarroStore
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/** Owns delivery for a project, independently of the number or visibility of its webviews. */
internal class ProjectNotifications(
    private val project: Project,
    private val server: OpenCodeServer,
    private val settings: VarroSettings,
    private val store: VarroStore,
    private val openChat: (String) -> Unit,
) : Disposable {
    private val log = logger<ProjectNotifications>()
    private val app = ApplicationManager.getApplication()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    @Volatile private var disposed = false
    private var generation = 0
    private var reconciliation = 0
    private val loadingSessions = mutableSetOf<String>()
    private val scopedSessions = linkedSetOf<String>()
    private var lastNotification: Notification? = null
    private val soundLock = Any()
    private var clip: Clip? = null
    private val state = AttentionNotifications(::preferences, { app.isActive }, ::inScope)

    init {
        app.messageBus.connect(this).subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
            override fun applicationActivated(ideFrame: IdeFrame) = onEdt { state.update() }
        })
    }

    private fun preferences() = AttentionNotifications.Settings(settings.notificationsNative, buildSet {
        if (settings.notificationsSoundPermission) add(AttentionNotifications.Kind.PERMISSION)
        if (settings.notificationsSoundQuestion) add(AttentionNotifications.Kind.QUESTION)
        if (settings.notificationsSoundCompleted) add(AttentionNotifications.Kind.COMPLETED)
        if (settings.notificationsSoundPlanReady) add(AttentionNotifications.Kind.PLAN_READY)
    })

    private fun inScope(id: String): Boolean? {
        if (id in store.hiddenSessionIds) return false
        val directory = server.transport.observedSessionDirectories()[id] ?: return if (id in scopedSessions) true else null
        return WorkspacePaths.isSame(directory, project.basePath)
    }

    private fun rememberScope(id: String) {
        scopedSessions.add(id)
        if (scopedSessions.size > 4096) scopedSessions.remove(scopedSessions.first())
    }

    private fun onEdt(action: () -> Unit) {
        app.invokeLater {
            if (!disposed && !project.isDisposed) {
                action()
                schedule()
            }
        }
    }

    fun observeSession(info: JsonObject) {
        val copy = info.deepCopy()
        onEdt {
            val id = copy.text("id") ?: return@onEdt
            val directory = copy.text("directory")
            if (directory != null && !WorkspacePaths.isSame(directory, project.basePath)) return@onEdt
            if (directory != null) rememberScope(id)
            state.observeSession(copy)
        }
    }

    fun event(event: ParsedServerEvent) {
        if (event.type !in EVENT_TYPES || event.sequenceOnly) return
        onEdt {
            if (event.type == "server.connected") {
                generation++
                loadingSessions.clear()
            }
            val info = event.properties.obj("info") ?: event.properties
            val sessionId = event.properties.text("sessionID") ?: info.text("sessionID")
                ?: if (event.type in setOf("session.created", "session.updated")) info.text("id") else null
            if (sessionId != null && event.workspaceDirectory != null && WorkspacePaths.isSame(event.workspaceDirectory, project.basePath)) rememberScope(sessionId)
            if (sessionId != null && inScope(sessionId) == false) return@onEdt
            state.handle(event)
            sessionId?.let(::loadSession)
            if (event.type in setOf("session.created", "session.updated")) info.text("id")?.let(::loadSession)
            if (event.type == "server.connected" && enabled()) reconcilePending()
        }
    }

    fun revealPermission(id: String) = onEdt { state.revealPermission(id) }
    fun seen(id: String) = onEdt { if (app.isActive) state.seen(id) }
    fun settingsChanged() = onEdt {
        state.update()
        if (enabled() && server.currentStatus() is ServerStatus.Running) reconcilePending()
    }

    /** Only successful replies clear attention. A failed POST leaves its pending alert intact. */
    fun requestSucceeded(method: String, path: String) {
        if (method.uppercase() != "POST") return
        val parts = path.substringBefore('?').trim('/').split('/')
        if (parts.size != 3) return
        if (parts[0] == "session" && parts[2] == "abort") {
            onEdt { state.aborted(parts[1]) }
            return
        }
        val kind = when {
            parts[0] == "permission" && parts[2] == "reply" -> AttentionNotifications.Kind.PERMISSION
            parts[0] == "question" && parts[2] in setOf("reply", "reject") -> AttentionNotifications.Kind.QUESTION
            else -> return
        }
        val id = java.net.URLDecoder.decode(parts[1], Charsets.UTF_8)
        onEdt { state.resolve(kind, id) }
    }

    private fun loadSession(id: String) {
        if (!enabled() || inScope(id) == false || state.knowsSession(id) || !id.matches(Regex("[A-Za-z0-9_-]+")) || !loadingSessions.add(id)) return
        val startedGeneration = generation
        app.executeOnPooledThread {
            val session = runCatching {
                server.transport.request("GET", "/session/$id", options = RequestOptions(isCancelled = { disposed })).data.asObjectOrNull()
            }.getOrElse { log.debug("Could not load notification session metadata", it); null }
            onEdt {
                if (startedGeneration != generation) return@onEdt
                loadingSessions.remove(id)
                if (session != null && inScope(id) == true) {
                    state.observeSession(session)
                    session.text("parentID")?.let(::loadSession)
                }
            }
        }
    }

    private fun enabled() = preferences().let { it.native || it.sounds.isNotEmpty() }

    private fun reconcilePending() {
        val startedAt = state.snapshotRevision()
        val startedGeneration = generation
        val requestGeneration = ++reconciliation
        for ((path, kind) in listOf("/permission" to AttentionNotifications.Kind.PERMISSION, "/question" to AttentionNotifications.Kind.QUESTION)) {
            app.executeOnPooledThread {
                val entries = runCatching {
                    server.transport.request("GET", path, options = RequestOptions(isCancelled = { disposed })).data.asArrayOrNull()
                }.getOrElse { log.debug("Could not reconcile notification requests", it); null }
                if (entries != null) onEdt {
                    if (startedGeneration != generation || requestGeneration != reconciliation) return@onEdt
                    state.reconcile(kind, entries, startedAt)
                    entries.forEach { it.asObjectOrNull().text("sessionID")?.let(::loadSession) }
                }
            }
        }
    }

    private fun schedule() {
        alarm.cancelAllRequests()
        val deadline = state.nextDeadline() ?: return
        alarm.addRequest({
            if (!disposed && !project.isDisposed) {
                state.update()
                val delivery = state.deliver(editorVisible())
                if (delivery != null) deliver(delivery)
                schedule()
            }
        }, (deadline - System.currentTimeMillis()).coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
    }

    private fun deliver(delivery: AttentionNotifications.Delivery) {
        if (delivery.native) runCatching {
            val projectName = AttentionNotifications.clean(project.name)
            SystemNotifications.getInstance().notify("Varro attention", "Project: $projectName", "${delivery.title}\n${delivery.message}")
            // Keep an actionable entry in the IDE's notification list as well as the desktop banner.
            lastNotification?.expire()
            lastNotification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                .createNotification(StringUtil.escapeXmlEntities(delivery.title), StringUtil.escapeXmlEntities(delivery.message), NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimpleExpiring("Open chat") { if (!disposed && !project.isDisposed) openChat(delivery.sessionId) })
                .also { it.notify(project) }
        }.onFailure { log.warn("Could not show Varro desktop notification", it) }
        if (delivery.sound) app.executeOnPooledThread {
            runCatching { playSound() }.onFailure { if (!disposed) log.warn("Could not play Varro notification sound", it) }
        }
    }

    private fun playSound() = synchronized(soundLock) {
        if (disposed || app.isActive) return@synchronized
        val resource = requireNotNull(javaClass.getResource("/notifications/water-bubble.wav")) { "Notification sound is missing" }
        AudioSystem.getAudioInputStream(resource).use { audio ->
            clip?.close()
            val next = AudioSystem.getClip()
            try {
                next.open(audio)
                next.addLineListener { event -> if (event.type == LineEvent.Type.STOP) next.close() }
                clip = next
                next.start()
            } catch (error: Exception) {
                next.close()
                throw error
            }
        }
    }

    private fun editorVisible(): Boolean {
        if (app.isActive) return true
        if (!SystemInfo.isMac) return false
        return try {
            var visible: Boolean? = null
            // Cocoa owns NSWindow occlusion. Query on its main thread, including detached IDE windows.
            Foundation.executeOnMainThread(true, true) {
                val application = Foundation.invoke("NSApplication", "sharedApplication")
                val windows = Foundation.invoke(application, "windows")
                val count = Foundation.invoke(windows, "count").toInt()
                var anyVisible = false
                for (index in 0 until count) {
                    val window = Foundation.invoke(windows, "objectAtIndex:", index.toLong())
                    if (Foundation.invoke(window, "isVisible").booleanValue() &&
                        Foundation.invoke(window, "occlusionState").toLong() and 2L != 0L) {
                        anyVisible = true
                        break
                    }
                }
                visible = anyVisible
            }
            visible ?: true
        } catch (error: LinkageError) {
            log.debug("Native IDE visibility API is unavailable; suppressing notification", error)
            true
        } catch (error: Exception) {
            log.debug("Could not determine IDE window visibility; suppressing notification", error)
            true
        }
    }

    override fun dispose() {
        disposed = true
        alarm.cancelAllRequests()
        lastNotification?.expire()
        synchronized(soundLock) { clip?.close(); clip = null }
    }

    companion object {
        const val GROUP = "Varro Chat Activity"
        private val EVENT_TYPES = setOf(
            "server.connected", "server.instance.disposed", "global.disposed",
            "session.created", "session.updated", "session.deleted", "session.status", "session.idle", "session.error",
            "session.next.prompt.admitted", "session.next.prompted", "session.next.step.started", "session.next.step.ended",
            "session.next.step.failed", "session.next.agent.switched", "message.updated",
            "permission.asked", "permission.updated", "permission.v2.asked", "permission.replied", "permission.v2.replied",
            "question.asked", "question.v2.asked", "question.replied", "question.rejected", "question.v2.replied", "question.v2.rejected",
        )
    }
}
