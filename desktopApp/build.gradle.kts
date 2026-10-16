import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
            // `run` uses the full JDK and hides it. The SQLite driver goes through JDBC.
            modules("java.sql")

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
