package varro.host

import java.util.Properties

internal object VarroBuild {
    val version: String = Properties().apply {
        VarroBuild::class.java.getResourceAsStream("/varro-build.properties")?.use { load(it) }
    }.getProperty("version", "")
}
