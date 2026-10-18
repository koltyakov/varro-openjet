package varro.host

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND

class HandyRecordingStateTest {
    @JvmField @Rule val temporary = TemporaryFolder()
    private fun line(source: String, message: String) = "[2026-10-18][10:00:00][handy_app_lib::$source][INFO] $message\n"

    @Test fun `bounded tail reader tracks partial lines rotation and missing logs without guessing idle`() {
        val path = temporary.root.toPath().resolve("handy.log")
        HandyRecordingState { path }.use { reader ->
            assertEquals("unknown", reader.read())
            Files.writeString(path, line("managers::audio", "Recording started for binding default"))
            assertEquals("recording", reader.read())
            val revision = reader.revision
            assertEquals("recording", reader.read()); assertEquals(revision, reader.revision)
            Files.writeString(path, line("actions", "TranscribeAction::stop called for binding: default").trimEnd(), APPEND)
            assertEquals("recording", reader.read())
            Files.writeString(path, "\n", APPEND); assertEquals("processing", reader.read())
            Files.writeString(path, line("actions", "Text pasted successfully"), APPEND)
            assertEquals("idle", reader.read())
            reader.assume("recording"); assertEquals("recording", reader.read()) // old lines cannot undo a command
            Files.delete(path); Files.writeString(path, line("tray", "tray apply: icon=resources/tray_idle_dark.png busy=false "))
            assertEquals("idle", reader.read())
            Files.delete(path); assertEquals("unknown", reader.read())
        }
    }

    @Test fun `lifecycle parser ignores transcripts and arbitrary lookalike text`() {
        assertNull(HandyRecordingState.lifecycle("Text pasted successfully"))
        assertNull(HandyRecordingState.lifecycle(line("actions", "Transcript says Recording started for binding default").trimEnd()))
        assertEquals("idle", HandyRecordingState.lifecycle(line("utils", "Operation cancellation completed - returned to idle state").trimEnd()))
        assertEquals("processing", HandyRecordingState.lifecycle(line("tray", "tray apply: icon=resources/transcribing.png busy=true ").trimEnd()))
    }
}
