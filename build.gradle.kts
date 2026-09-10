import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/** Reads a comma-separated gradle.properties value into a list. */
fun commaSeparated(property: String): List<String> =
    providers.gradleProperty(property).orElse("").get()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)

dependencies {
    // Gson drives the whole webview protocol: Varro's transport is JSON
    // pass-through, so a mutable tree model beats generated data classes here.
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")

    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        bundledPlugins(commaSeparated("platformBundledPlugins"))
        bundledModules(commaSeparated("platformBundledModules"))
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// ---------------------------------------------------------------------------
// Webview pipeline
//
// The Solid/Tailwind webview is reused verbatim from upstream Varro. `npm run
// sync` vendors `src/webview` + `src/shared` into `webview/vendor`, and
// `npm run build` emits the bundle straight into the plugin's resources so
// `processResources` picks it up like any other static asset.
// ---------------------------------------------------------------------------

val webviewDir = layout.projectDirectory.dir("webview")
val webviewOutputDir = layout.projectDirectory.dir("src/main/resources/webview")
val skipWebview = providers.gradleProperty("skipWebview").orElse("false").get().toBoolean()

fun npmCommand(vararg args: String): List<String> =
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        listOf("cmd", "/c", "npm") + args
    } else {
        listOf("npm") + args
    }

val syncWebviewSources = tasks.register<Exec>("syncWebviewSources") {
    group = "varro"
    description = "Vendor the upstream Varro webview and shared sources into webview/vendor."
    workingDir = webviewDir.asFile
    commandLine(npmCommand("run", "sync"))
    onlyIf { !skipWebview }
}

val installWebviewDeps = tasks.register<Exec>("installWebviewDeps") {
    group = "varro"
    description = "Install webview npm dependencies from the committed lockfile."
    workingDir = webviewDir.asFile
    // `npm ci` installs the lockfile exactly and fails when it has drifted from
    // package.json. That strictness is the point: node and npm are pinned via
    // Volta (and matched in the Dockerfile), so any lockfile change should be a
    // deliberate dependency edit rather than a side effect of the local toolchain.
    commandLine(npmCommand("ci", "--include=optional", "--no-audit", "--no-fund"))
    inputs.files(webviewDir.file("package.json"), webviewDir.file("package-lock.json"))
    outputs.dir(webviewDir.dir("node_modules"))
    onlyIf { !skipWebview }
}

val buildWebview = tasks.register<Exec>("buildWebview") {
    group = "varro"
    description = "Build the webview bundle into src/main/resources/webview."
    dependsOn(installWebviewDeps)
    workingDir = webviewDir.asFile
    commandLine(npmCommand("run", "build"))

    inputs.dir(webviewDir.dir("src"))
    inputs.dir(webviewDir.dir("vendor"))
    inputs.files(
        webviewDir.file("package.json"),
        webviewDir.file("vite.config.mts"),
        webviewDir.file("tsconfig.json"),
    )
    outputs.dir(webviewOutputDir)

    onlyIf {
        if (skipWebview) {
            logger.lifecycle("Skipping webview build (-PskipWebview=true)")
            false
        } else {
            val vendored = webviewDir.dir("vendor").asFile
            if (!vendored.isDirectory) {
                throw GradleException(
                    "webview/vendor is missing. Run `./gradlew syncWebviewSources` " +
                        "(or `cd webview && npm run sync`) to vendor the upstream Varro webview.",
                )
            }
            true
        }
    }
}

tasks.named("processResources") {
    dependsOn(buildWebview)
}

val testWebviewHost = tasks.register<Exec>("testWebviewHost") {
    group = "verification"
    description = "Test the JetBrains webview persistence bridge."
    dependsOn(installWebviewDeps)
    workingDir = webviewDir.asFile
    commandLine(npmCommand("run", "test:host"))
    onlyIf { !skipWebview }
}

tasks.named("check") {
    dependsOn(testWebviewHost)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnit()
    dependsOn(testWebviewHost)
}

tasks.clean {
    delete(webviewOutputDir)
}
