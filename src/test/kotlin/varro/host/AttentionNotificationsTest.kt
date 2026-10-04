package varro.host

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import varro.host.AttentionNotifications.Kind
import varro.protocol.*
import varro.server.OpenCodeV2Events
import varro.server.ServerEvents
import varro.settings.VarroSettings
import java.security.MessageDigest
import javax.sound.sampled.AudioSystem

class AttentionNotificationsTest {
    private class Fixture {
        var time = 1_000L
        var focused = false
        var settings = AttentionNotifications.Settings(native = true, sounds = Kind.entries.toSet())
        val excluded = mutableSetOf<String>()
        val state = AttentionNotifications({ settings }, { focused }, { it !in excluded }, { time })

        fun session(id: String = "root", parent: String? = null, agent: String = "build") = state.observeSession(Json.obj(
            "id" to id, "title" to "Chat $id", "parentID" to parent,
            "metadata" to Json.obj("varro" to Json.obj("agent" to agent)),
        ))
        fun event(type: String, props: JsonObject = JsonObject(), eventId: String? = null) {
            state.handle(requireNotNull(ServerEvents.parse(Json.obj("type" to type, "properties" to props, "id" to eventId))))
        }
        fun status(type: String, id: String = "root") = event("session.status", Json.obj("sessionID" to id, "status" to Json.obj("type" to type)))
        fun ask(kind: Kind = Kind.QUESTION, id: String = "q", session: String = "root") = event(
            if (kind == Kind.QUESTION) "question.asked" else "permission.asked", Json.obj("id" to id, "sessionID" to session),
        )
        fun deliver(after: Long = 300, visible: Boolean = false): AttentionNotifications.Delivery? {
            time += after
            return state.deliver(visible)
        }
        fun finish(id: String = "root") { status("busy", id); status("idle", id) }
    }

    @Test fun `all notification channels default off`() {
        val settings = VarroSettings()
        assertFalse(settings.notificationsNative)
        assertFalse(settings.notificationsSoundPermission)
        assertFalse(settings.notificationsSoundQuestion)
        assertFalse(settings.notificationsSoundCompleted)
        assertFalse(settings.notificationsSoundPlanReady)
    }

    @Test fun `batches mixed requests and completions with one sound and prioritizes the first chat`() {
        val f = Fixture()
        f.session()
        f.session("other")
        f.ask()
        f.ask(Kind.PERMISSION, "p")
        f.state.revealPermission("p")
        f.finish("other")
        assertNull(f.deliver(299))
        val delivery = requireNotNull(f.deliver(1))
        assertEquals("root", delivery.sessionId)
        assertEquals("Chat root", delivery.title)
        assertEquals("Your answer is needed; Permission approval needed; Updates in 1 other chat", delivery.message)
        assertTrue(delivery.native)
        assertTrue(delivery.sound)
        assertNull(f.deliver(10_000))
    }

    @Test fun `cooldown is fixed and continuous asks cannot starve delivery`() {
        val f = Fixture()
        f.ask(id = "first")
        assertNotNull(f.deliver())
        f.ask(id = "second")
        assertNull(f.deliver(4_999))
        f.ask(id = "third")
        assertNotNull(f.deliver(1))
    }

    @Test fun `native and sound switches are independent for every event`() {
        for (kind in Kind.entries) for (native in listOf(false, true)) for (sound in listOf(false, true)) {
            val f = Fixture()
            f.settings = AttentionNotifications.Settings(native, if (sound) setOf(kind) else emptySet())
            f.session(agent = if (kind == Kind.PLAN_READY) "plan" else "build")
            when (kind) {
                Kind.PERMISSION -> { f.ask(kind); f.state.revealPermission("q") }
                Kind.QUESTION -> f.ask(kind)
                else -> f.finish()
            }
            val delivery = f.deliver()
            if (!native && !sound) assertNull(delivery)
            else {
                assertEquals(native, delivery?.native)
                assertEquals(sound, delivery?.sound)
                assertEquals(kind.label, delivery?.message)
            }
        }
    }

    @Test fun `muted event does not consume cooldown and settings are rechecked at delivery`() {
        val f = Fixture()
        f.settings = AttentionNotifications.Settings(sounds = setOf(Kind.QUESTION))
        f.session()
        f.finish()
        assertNull(f.deliver())
        f.ask()
        f.settings = AttentionNotifications.Settings()
        assertNull(f.deliver())
        f.settings = AttentionNotifications.Settings(sounds = setOf(Kind.QUESTION))
        f.ask(id = "new")
        assertNotNull(f.deliver())
    }

    @Test fun `focused or still visible editor consumes a burst without replay after losing focus`() {
        for (focusedAtAsk in listOf(false, true)) {
            val f = Fixture()
            f.focused = focusedAtAsk
            f.ask()
            if (!focusedAtAsk) f.focused = true
            assertNull(f.deliver())
            f.focused = false
            assertNull(f.deliver(10_000))
        }
        val f = Fixture()
        f.ask()
        assertNull(f.deliver(visible = true))
        assertNull(f.deliver(10_000))
    }

    @Test fun `resolved automatic permissions never alert but deferred requests have a bounded fallback`() {
        val f = Fixture()
        f.ask(Kind.PERMISSION)
        assertNull(f.deliver(19_999))
        f.state.resolve(Kind.PERMISSION, "q")
        assertNull(f.deliver(10_000))
        f.ask(Kind.PERMISSION, "fallback")
        assertNull(f.deliver(20_000)) // reveal starts the batching window
        assertEquals(Kind.PERMISSION.label, f.deliver()?.message)
    }

    @Test fun `explicit reveal alerts promptly and authoritative replies cancel a queued alert`() {
        for (reply in listOf("permission.replied", "permission.v2.replied")) {
            val f = Fixture()
            f.ask(Kind.PERMISSION)
            f.state.revealPermission("q")
            f.event(reply, Json.obj("info" to Json.obj("permissionID" to "q")))
            assertNull(f.deliver())
        }
        val f = Fixture()
        f.ask(Kind.PERMISSION)
        f.state.revealPermission("q")
        assertNotNull(f.deliver())
    }

    @Test fun `legacy and wrapped V2 requests accept every request ID spelling`() {
        for (type in listOf("permission.updated", "permission.asked", "permission.v2.asked")) {
            for (idKey in listOf("id", "permissionID", "requestID")) {
                val f = Fixture()
                f.event(type, Json.obj("info" to Json.obj(idKey to "p", "sessionID" to "root")))
                f.state.revealPermission("p")
                assertEquals(Kind.PERMISSION.label, f.deliver()?.message)
            }
        }
    }

    @Test fun `late snapshots cannot resurrect replied or deleted requests or remove a newer ask`() {
        val f = Fixture()
        val beforeAsk = f.state.snapshotRevision()
        f.ask()
        f.state.reconcile(Kind.QUESTION, Json.array(emptyList<String>()), beforeAsk)
        assertNotNull(f.deliver())
        val beforeReply = f.state.snapshotRevision()
        f.state.resolve(Kind.QUESTION, "q")
        val stale = Json.array(listOf(Json.obj("id" to "q", "sessionID" to "root")))
        f.state.reconcile(Kind.QUESTION, stale, beforeReply)
        assertNull(f.deliver(10_000))
        val beforeDeletion = f.state.snapshotRevision()
        f.event("session.deleted", Json.obj("info" to Json.obj("id" to "root")))
        f.state.reconcile(Kind.QUESTION, Json.array(listOf(Json.obj("id" to "new", "sessionID" to "root"))), beforeDeletion)
        assertNull(f.deliver(10_000))
    }

    @Test fun `snapshot omissions clear pending requests and duplicate snapshots do not alert twice`() {
        val f = Fixture()
        val requests = Json.array(listOf(Json.obj("id" to "q", "sessionID" to "root")))
        f.state.reconcile(Kind.QUESTION, requests, f.state.snapshotRevision())
        assertNotNull(f.deliver())
        f.state.reconcile(Kind.QUESTION, requests, f.state.snapshotRevision())
        assertNull(f.deliver(10_000))
        f.state.reconcile(Kind.QUESTION, Json.array(emptyList<String>()), f.state.snapshotRevision())
        f.state.reconcile(Kind.QUESTION, requests, f.state.snapshotRevision())
        assertNull(f.deliver(10_000))
    }

    @Test fun `only live root turns complete and duplicate idle or trailing busy events do not notify again`() {
        val f = Fixture()
        f.session()
        f.session("child", "root")
        f.status("idle")
        assertNull(f.deliver())
        f.finish("child")
        assertNull(f.deliver())
        f.finish()
        assertEquals(Kind.COMPLETED.label, f.deliver()?.message)
        f.status("idle")
        f.finish()
        assertNull(f.deliver(10_000))
        f.event("message.updated", Json.obj("info" to Json.obj("role" to "user", "id" to "new-user", "sessionID" to "root")))
        f.finish()
        assertNotNull(f.deliver())
    }

    @Test fun `child permissions notify and prevent premature completion of the root`() {
        val f = Fixture()
        f.session()
        f.session("child", "root")
        f.status("busy")
        f.ask(Kind.PERMISSION, "p", "child")
        f.state.revealPermission("p")
        f.status("idle")
        val notification = requireNotNull(f.deliver())
        assertEquals("child", notification.sessionId)
        assertEquals(Kind.PERMISSION.label, notification.message)
    }

    @Test fun `renaming a child preserves ancestry`() {
        val f = Fixture()
        f.session("child", "root")
        f.event("session.updated", Json.obj("info" to Json.obj("id" to "child", "title" to "Renamed")))
        f.finish("child")
        assertNull(f.deliver())
    }

    @Test fun `question resolution waits for continuation instead of announcing the idle gap`() {
        val f = Fixture()
        f.session()
        f.status("busy")
        f.ask()
        f.state.resolve(Kind.QUESTION, "q")
        f.status("idle")
        assertNull(f.deliver())
        f.finish()
        assertNotNull(f.deliver())
    }

    @Test fun `terminal tool steps wait for server idle and continuation steps do not finish the turn`() {
        val f = Fixture()
        f.session(agent = "plan")
        f.status("busy")
        f.event("session.next.step.ended", Json.obj("sessionID" to "root", "finish" to "tool-calls"))
        assertNull(f.deliver())
        f.event("session.next.step.ended", Json.obj("sessionID" to "root", "finish" to "stop", "executionContinues" to true))
        assertNull(f.deliver())
        f.event("message.updated", Json.obj("info" to Json.obj("sessionID" to "root", "role" to "assistant", "finish" to "stop", "time" to Json.obj("completed" to 2000))))
        assertNull(f.deliver())
        f.status("idle")
        assertEquals(Kind.PLAN_READY.label, f.deliver()?.message)
    }

    @Test fun `failures cancellation and superseded completions never announce success`() {
        for (type in listOf("session.error", "session.next.step.failed")) {
            val f = Fixture()
            f.session()
            f.status("busy")
            f.event(type, Json.obj("sessionID" to "root"))
            f.status("idle")
            assertNull(f.deliver())
        }
        val f = Fixture()
        f.session()
        f.finish()
        f.state.aborted("root")
        assertNull(f.deliver())
        f.event("session.next.prompt.admitted", Json.obj("sessionID" to "root"))
        f.finish()
        f.event("session.next.prompt.admitted", Json.obj("sessionID" to "root"))
        assertNull(f.deliver())
    }

    @Test fun `V2 execution interruption stays distinct from a successful idle`() {
        for (outcome in listOf("interrupted", "succeeded")) {
            val f = Fixture()
            f.session()
            f.status("busy")
            val events = OpenCodeV2Events.project(Json.obj("type" to "session.execution.$outcome", "id" to "evt_end", "created" to 2000,
                "data" to Json.obj("sessionID" to "root")), Json.obj("directory" to "/repo"))
            events.forEach { f.state.handle(requireNotNull(ServerEvents.parse(it))) }
            assertEquals(outcome == "succeeded", f.deliver() != null)
        }
    }

    @Test fun `fast completion waits for metadata rather than guessing root ancestry`() {
        val root = Fixture()
        root.finish()
        assertNull(root.deliver())
        root.session()
        assertNotNull(root.deliver())
        val child = Fixture()
        child.finish("child")
        child.session("child", "root")
        assertNull(child.deliver())
    }

    @Test fun `partial rename metadata cannot turn an unknown child into a root`() {
        val f = Fixture()
        f.event("session.updated", Json.obj("info" to Json.obj("id" to "child", "title" to "Renamed")))
        f.finish("child")
        assertNull(f.deliver())
        f.session("child", "root")
        assertNull(f.deliver())
    }

    @Test fun `automatically approved permission resumes completion even without another busy event`() {
        val f = Fixture()
        f.session()
        f.status("busy")
        f.ask(Kind.PERMISSION)
        f.state.resolve(Kind.PERMISSION, "q")
        f.event("message.updated", Json.obj("info" to Json.obj("id" to "reply", "sessionID" to "root", "role" to "assistant")))
        f.status("idle")
        assertEquals(Kind.COMPLETED.label, f.deliver()?.message)
    }

    @Test fun `unknown workspace requests wait for scope resolution and foreign requests stay silent`() {
        for (inProject in listOf(false, true)) {
            var time = 0L
            var scope: Boolean? = null
            val state = AttentionNotifications({ AttentionNotifications.Settings(native = true) }, { false }, { scope }, { time })
            state.reconcile(Kind.QUESTION, Json.array(listOf(Json.obj("id" to "q", "sessionID" to "unknown"))), state.snapshotRevision())
            time += 1_000
            assertNull(state.deliver(false))
            assertNull(state.nextDeadline())
            scope = inProject
            state.observeSession(Json.obj("id" to "unknown"))
            time += 300
            assertEquals(inProject, state.deliver(false) != null)
        }
    }

    @Test fun `late child ancestry cancels an already queued parent completion`() {
        val f = Fixture()
        f.session()
        f.finish()
        f.ask(Kind.PERMISSION, "p", "child")
        f.session("child", "root")
        assertNull(f.deliver())
        f.state.revealPermission("p")
        assertEquals(Kind.PERMISSION.label, f.deliver()?.message)
    }

    @Test fun `foreign snapshot requests cannot block completion in this project`() {
        val f = Fixture()
        f.session()
        f.excluded.add("foreign")
        f.state.reconcile(Kind.QUESTION, Json.array(listOf(Json.obj("id" to "q", "sessionID" to "foreign"))), f.state.snapshotRevision())
        f.finish()
        assertEquals(Kind.COMPLETED.label, f.deliver()?.message)
    }

    @Test fun `terminal evidence older than the current prompt cannot complete or fail it`() {
        val f = Fixture()
        f.session()
        f.event("message.updated", Json.obj("info" to Json.obj("id" to "new", "role" to "user", "sessionID" to "root", "time" to Json.obj("created" to 3_000))))
        f.event("message.updated", Json.obj("info" to Json.obj("role" to "assistant", "sessionID" to "root", "finish" to "stop", "time" to Json.obj("completed" to 2_000))))
        f.event("session.next.step.ended", Json.obj("sessionID" to "root", "finish" to "stop", "timestamp" to 2_000))
        assertNull(f.deliver())
        f.status("idle")
        assertNotNull(f.deliver())
    }

    @Test fun `scope and visibility are rechecked and seen sessions drop queued completions`() {
        val f = Fixture()
        f.session()
        f.finish()
        f.excluded.add("root")
        assertNull(f.deliver())
        f.excluded.clear()
        f.event("session.next.prompt.admitted", Json.obj("sessionID" to "root"))
        f.finish()
        f.state.seen("root")
        assertNull(f.deliver())
    }

    @Test fun `reconnect clears queued completions and replayed event IDs are ignored`() {
        val f = Fixture()
        f.session()
        f.finish()
        f.event("server.connected")
        assertNull(f.deliver())
        f.event("question.asked", Json.obj("id" to "q", "sessionID" to "root"), "evt_ask")
        assertNotNull(f.deliver())
        f.state.resolve(Kind.QUESTION, "q")
        f.event("question.asked", Json.obj("id" to "q", "sessionID" to "root"), "evt_ask")
        assertNull(f.deliver(10_000))
    }

    @Test fun `sound resource is the upstream recording and readable by JVM audio`() {
        val resource = requireNotNull(javaClass.getResource("/notifications/water-bubble.wav"))
        val hash = MessageDigest.getInstance("SHA-256").digest(resource.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("69b9f1a8c1f2c2fe5e73ca3dfcd9ed02d16266be03f37e42ff8ded717a4f9d92", hash)
        AudioSystem.getAudioInputStream(resource).use {
            assertEquals(48_000f, it.format.sampleRate)
            assertEquals(1, it.format.channels)
            assertEquals(19_680L, it.frameLength)
        }
    }
}
