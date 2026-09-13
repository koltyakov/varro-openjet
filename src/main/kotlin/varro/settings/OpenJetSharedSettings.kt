package varro.settings

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import varro.store.VarroModelStore
import java.util.concurrent.TimeUnit

/** Shares core preferences between JetBrains products under the same OS account. */
@Service(Service.Level.APP)
class OpenJetSharedSettings : Disposable {
    private val file = SharedSettingsFile(SharedSettingsFile.location())
    private val settings = VarroSettings.getInstance()
    private val models = VarroModelStore.getInstance()
    private var baseline = snapshot()
    private var joined = false
    private var lastError: String? = null
    private val polling = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
        { synchronizeSafely() }, 1, 1, TimeUnit.SECONDS,
    )

    val path: String get() = file.path.toString()

    init {
        synchronizeSafely()
    }

    /** If another IDE initialized first, adopt its settings instead of replacing them. */
    @Synchronized
    fun initializeFromThisIde() {
        val current = snapshot()
        adopt(requireNotNull(file.update(current, current, initialize = true)))
        joined = true
    }

    @Synchronized
    private fun synchronizeSafely() {
        try {
            val latest = if (joined) file.update(baseline, snapshot()) else file.read()
            if (latest != null) {
                adopt(latest)
                joined = true
            } else {
                joined = false
                baseline = snapshot()
            }
            lastError = null
        } catch (error: Exception) {
            // Keep the last usable values and leave malformed or newer files untouched.
            if (lastError != error.message) logger<OpenJetSharedSettings>().warn("Cannot synchronize OpenJet settings", error)
            lastError = error.message
        }
    }

    private fun snapshot(): JsonObject {
        val core = SharedCoreSettings.snapshot(settings)
        return JsonObject().apply {
            addProperty("version", 1)
            add("core", core)
            add("models", models.modelPreferences)
        }
    }

    private fun adopt(document: JsonObject) {
        val current = snapshot()
        val core = document.getAsJsonObject("core")
        SharedCoreSettings.apply(core, settings)
        val nextModels = document.getAsJsonObject("models")
        val modelsChanged = models.modelPreferences != nextModels
        if (modelsChanged) models.modelPreferences = nextModels
        baseline = snapshot()
        val coreChanged = current.get("core") != baseline.get("core")
        if (modelsChanged || coreChanged) {
            val app = ApplicationManager.getApplication()
            app.invokeLater {
                if (!app.isDisposed) {
                    if (modelsChanged) app.messageBus.syncPublisher(VarroModelStore.TOPIC).preferencesChanged()
                    if (coreChanged) app.messageBus.syncPublisher(VarroSettings.TOPIC).settingsChanged()
                }
            }
        }
    }

    override fun dispose() {
        polling.cancel(false)
        synchronizeSafely()
    }

    companion object {
        fun getInstance(): OpenJetSharedSettings = service()
    }
}
