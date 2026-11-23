import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    // The entry point draws one small window of its own ("already running"), and `:shared` does not
    // expose its Compose dependencies transitively.
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    implementation(libs.compose.uiToolingPreview)
}

// Read at configuration time; empty unless the release credentials are present.
val buildEnv = providers

// The version the installer is named after. Comes from the same gradle.properties value the app
// displays and Android reports, so the artifact and the tag can't disagree by accident.
val appVersion = providers.gradleProperty("notesai.version").getOrElse("1.0.0")

compose.desktop {
    application {
        mainClass = "com.example.notesai.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            packageName = "notesAI"
            packageVersion = appVersion
            description = "Notes you own, kept in your own Google Drive"
            vendor = "notesAI"

            // The Compose plugin uses jlink and does *not* infer required JDK modules. Leaving
            // one out produces a ClassNotFoundException only at runtime, in the packaged app —
            // `run` uses the full JDK and hides it.
            //
            // java.sql       — the SQLite driver, which goes through JDBC.
            // jdk.httpserver — the sign-in loopback server (com.sun.net.httpserver.HttpServer) that
            //                  receives the OAuth redirect. Signing in from the packaged build
            //                  failed with NoClassDefFoundError: com/sun/net/httpserver/HttpServer
            //                  until this was listed.
            modules("java.sql", "jdk.httpserver")

            macOS {
                bundleID = "io.github.crispyduck123hc.notesai"

                // Signing is switched on only when a certificate is actually named, so a plain
                // `packageDmg` still works on a machine without it.
                //
                // Blank counts as absent. That distinction matters: a workflow that passes an
                // unset secret through `env:` produces an *empty string*, not a missing variable,
                // and an empty identity makes jpackage try to sign with "" and fail with
                // "Could not find certificate for ''". Tolerating that here rather than asking
                // every caller to be careful keeps an unsigned build buildable from anywhere.
                buildEnv
                    .environmentVariable("MACOS_SIGNING_IDENTITY")
                    .orNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { identityName ->
                        signing {
                            sign.set(true)
                            identity.set(identityName)
                        }
                    }

                // Only consulted by the notarize* tasks, and blank is treated as absent for the
                // same reason: an unconfigured credential should look unconfigured.
                fun credential(name: String) = buildEnv.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

                notarization {
                    appleID.set(credential("NOTARIZATION_APPLE_ID"))
                    password.set(credential("NOTARIZATION_PASSWORD"))
                    teamID.set(credential("NOTARIZATION_TEAM_ID"))
                }
            }

            windows {
                // Must never change: this is how the installer recognises an existing install
                // and offers an upgrade, rather than leaving two copies behind.
                upgradeUuid = "8a479580-649e-4a8e-9aa9-53486e2896cb"
            }
        }
    }
}

// ---- Package trimming -------------------------------------------------------
// Two jars on the packaged classpath carry bytes no packaged app ever executes:
//
// skiko-awt-runtime-*.jar  — the packaging plugin already extracts the host's Skia
//   native (libskiko-*.dylib/.so/.dll) next to the jars and loads it from disk, but
//   the jar it leaves behind still contains Skia natives inside (skiko ships a fat
//   runtime jar with every architecture). Those entries are dead weight: the loader
//   reads the file beside the jar, never the resource.
//
// sqlite-jdbc-*.jar — org.xerial bundles the SQLite native for *every* platform it
//   supports (macOS x86_64 + aarch64, Windows x86/x64/arm, Linux in six flavours,
//   FreeBSD, musl, Android). A packaged app runs exactly one OS, so every other
//   directory under org/sqlite/native/ is never touched.
//
// The task rewrites those jars in the app image (idempotently — a jar with nothing
// left to remove is left untouched) after createDistributable and before any package
// task, so the DMG/MSI/DEB are built from the trimmed image.
val trimPackagedJars by tasks.registering(TrimPackagedJars::class) {
    appImageRoot.set(layout.buildDirectory.dir("compose/binaries/main/app"))
}

// Ordering only: mustRunAfter does not create a dependency, it just guarantees the
// image exists (and is fresh) before the rewrite walks it. The dependency comes from
// the package tasks below, which is also what keeps packageRelease* wired correctly.
trimPackagedJars.configure {
    mustRunAfter(tasks.matching { it.name.startsWith("create") && it.name.endsWith("Distributable") })
}

tasks.matching { it.name.startsWith("package") }.configureEach {
    dependsOn(trimPackagedJars)
}

abstract class TrimPackagedJars : DefaultTask() {
    @get:Internal
    abstract val appImageRoot: DirectoryProperty

    @TaskAction
    fun trim() {
        val root = appImageRoot.get().asFile
        if (!root.isDirectory) {
            logger.warn("Trim: no app image at $root — nothing to do (run createDistributable first)")
            return
        }

        val host = OperatingSystem.current()
        val nativesToKeep =
            when {
                host.isMacOsX -> setOf("Mac")
                host.isWindows -> setOf("Windows")
                host.isLinux -> setOf("Linux", "Linux-Musl")
                else -> null // unknown host: trim nothing, rather than guess wrong
            }

        var savedBytes = 0L
        val jars =
            root
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(".jar") }
                .toList()
        for (jar in jars) {
            val removed =
                when {
                    jar.name.startsWith("skiko-awt-runtime-") ->
                        rewrite(jar) { entry -> entry.name.startsWith("libskiko-") }

                    jar.name.startsWith("sqlite-jdbc-") && nativesToKeep != null ->
                        rewrite(jar) { entry -> !sqliteNativeWanted(entry.name, nativesToKeep) }

                    else -> null
                }
            if (removed != null) {
                savedBytes += removed
                logger.lifecycle("Trim: removed ${removed / 1024} KB from ${jar.relativeTo(root)}")
            }
        }
        logger.lifecycle("Trim: saved ${savedBytes / 1024 / 1024} MB")
    }

    /**
     * True when the SQLite native under `org/sqlite/native/<dir>/` is one the host could
     * ever load. `Linux-Android` and `FreeBSD` are always dropped — no desktop target
     * uses them; everything else is decided by the packaging host's OS.
     */
    private fun sqliteNativeWanted(
        entryName: String,
        kept: Set<String>,
    ): Boolean {
        val prefix = "org/sqlite/native/"
        if (!entryName.startsWith(prefix)) return true // classes, metadata, everything else
        val dir = entryName.removePrefix(prefix).substringBefore('/')
        return dir in kept
    }

    /** Copies every entry except those matching [isRemoved]. Returns bytes saved, or null when there was nothing to remove. */
    private fun rewrite(
        jar: File,
        isRemoved: (ZipEntry) -> Boolean,
    ): Long? {
        ZipFile(jar).use { zip ->
            if (zip.entries().asSequence().none(isRemoved)) return null
            val tmp = File(jar.parentFile, jar.name + ".trim")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                for (entry in zip.entries()) {
                    if (isRemoved(entry)) continue
                    out.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            val saved = jar.length() - tmp.length()
            if (!tmp.renameTo(jar)) {
                tmp.delete()
                throw GradleException("Trim: could not replace ${jar.name}")
            }
            return saved
        }
    }
}
