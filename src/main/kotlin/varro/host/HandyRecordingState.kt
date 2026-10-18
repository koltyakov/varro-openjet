package varro.host

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Optional, read-only tailing. No log reads or watcher while merely checking installation. */
internal class HandyRecordingState(private val getPath: () -> Path?) : AutoCloseable {
    @Volatile var state = "unknown"; private set
    @Volatile var revision = 0L; private set
    @Volatile var observation = 0L; private set
    private data class Cursor(val path: Path, val key: Any?, val position: Long, val modified: Long, val partial: String)
    private var cursor: Cursor? = null
    private var watcher: WatchService? = null
    private var generation = 0L
    private var disposed = false

    @Synchronized fun assume(value: String) {
        if (state != value) { state = value; revision++ }
    }

    @Synchronized fun read(): String {
        if (disposed) return "unknown"
        try {
            val path = getPath() ?: error("No log")
            FileChannel.open(path, StandardOpenOption.READ).use { file ->
                val metadata = Files.readAttributes(path, BasicFileAttributes::class.java)
                val previous = cursor
                val size = file.size()
                val continuous = previous != null && previous.path == path && previous.key == metadata.fileKey() && size >= previous.position &&
                    !(size == previous.position && metadata.lastModifiedTime().toMillis() != previous.modified)
                val start = if (continuous) previous.position else 0
                if (continuous && size == start) return state
                val offset = maxOf(start, size - MAX_BYTES)
                val buffer = ByteBuffer.allocate((size - offset).toInt())
                file.position(offset)
                while (buffer.hasRemaining() && file.read(buffer) > 0) { /* bounded snapshot */ }
                val complete = continuous && offset == start
                val text = (if (complete) previous.partial else "") + String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
                val lines = text.split('\n').toMutableList()
                val partial = lines.removeAt(lines.lastIndex).takeLast(MAX_BYTES)
                if (!complete && offset > 0 && lines.isNotEmpty()) lines.removeAt(0)
                cursor = Cursor(path, metadata.fileKey(), offset + buffer.position(), metadata.lastModifiedTime().toMillis(), partial)
                var next = if (complete) state else "unknown"
                lines.forEach { line -> lifecycle(line.trimEnd())?.let { next = it; observation++ } }
                assume(next)
            }
        } catch (_: Exception) { cursor = null; assume("unknown") }
        return state
    }

    @Synchronized fun watch(listener: (String) -> Unit) {
        stopWatching()
        if (disposed || cursor == null) return
        try {
            val path = getPath() ?: return
            val service = FileSystems.getDefault().newWatchService()
            try { path.parent.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE) }
            catch (failure: Exception) { service.close(); throw failure }
            watcher = service
            val token = generation
            Thread({
                var published = synchronized(this) { state }
                fun publish() {
                    synchronized(this) {
                        if (token != generation || disposed) return
                        val next = read()
                        if (next == "idle" || next == "unknown") stopWatching()
                        if (next != published) { published = next; listener(next) }
                    }
                }
                try {
                    publish()
                    while (true) {
                        val key = service.take()
                        val changed = key.pollEvents().any { it.kind() == StandardWatchEventKinds.OVERFLOW || it.context() == path.fileName }
                        if (!key.reset()) error("Handy log directory unavailable")
                        if (changed) { Thread.sleep(100); publish() }
                    }
                } catch (_: ClosedWatchServiceException) { /* stopped normally */ }
                catch (_: Exception) {
                    synchronized(this) {
                        if (token == generation && !disposed) { reset("unknown"); listener("unknown") }
                    }
                }
            }, "varro-handy-lifecycle").apply { isDaemon = true; start() }
        } catch (_: Exception) { reset("unknown") }
    }

    @Synchronized fun stopWatching() { generation++; watcher?.close(); watcher = null }
    @Synchronized fun reset(value: String) { stopWatching(); cursor = null; assume(value) }
    @Synchronized override fun close() { disposed = true; reset("unknown") }

    companion object {
        private const val MAX_BYTES = 64 * 1024
        private val LINE = Regex("^\\[\\d{4}-\\d{2}-\\d{2}\\]\\[\\d{2}:\\d{2}:\\d{2}\\]\\[handy_app_lib::(tray|utils|actions|managers::audio)\\]\\[(?:DEBUG|INFO|WARN|ERROR)\\] (.*)$")
        private val ICON = Regex("^tray apply: icon=resources/(?:tray_)?(idle|handy|recording|transcribing)(?:_dark)?\\.png ")
        internal fun lifecycle(line: String): String? {
            val match = LINE.matchEntire(line) ?: return null
            val source = match.groupValues[1]; val message = match.groupValues[2]
            return when (source) {
                "tray" -> when (ICON.find(message)?.groupValues?.get(1)) {
                    "recording" -> "recording"
                    "transcribing" -> "processing"
                    "idle", "handy" -> "idle"
                    else -> if (Regex("^tray apply: .* busy=false ").containsMatchIn(message)) "idle" else null
                }
                "utils" -> if (message == "Operation cancellation completed - returned to idle state") "idle" else null
                "actions" -> when {
                    message.startsWith("TranscribeAction::stop called for binding: ") -> "processing"
                    message.startsWith("Text pasted successfully") -> "idle"
                    else -> null
                }
                "managers::audio" -> if (message.startsWith("Recording started for binding ")) "recording" else null
                else -> null
            }
        }
    }
}
