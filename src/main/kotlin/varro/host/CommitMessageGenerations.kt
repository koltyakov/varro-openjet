package varro.host

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** A cancelled attempt releases its repository immediately, without releasing a newer retry. */
@Service(Service.Level.PROJECT)
class CommitMessageGenerations : Disposable {
    class Attempt {
        val cancelled = AtomicBoolean()
        fun check() { if (cancelled.get()) throw CancellationException("Commit message generation cancelled") }
    }
    private val active = ConcurrentHashMap<String, Attempt>()
    fun begin(root: String): Attempt? = Attempt().takeIf { active.putIfAbsent(root, it) == null }
    fun isActive(root: String): Boolean = active.containsKey(root)
    fun cancel(root: String) { active.remove(root)?.cancelled?.set(true) }
    fun finish(root: String, attempt: Attempt) { active.remove(root, attempt) }
    override fun dispose() { active.keys.toList().forEach(::cancel) }
}
