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

    implementation(libs.compose.uiToolingPreview)
}

// Read at configuration time; empty unless the release credentials are present.
val buildEnv = providers

compose.desktop {
    application {
        mainClass = "com.example.notesai.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            packageName = "notesAI"
            packageVersion = "1.0.0"
            description = "Notes you own, kept in your own Google Drive"
            vendor = "notesAI"

            // The Compose plugin uses jlink and does *not* infer required JDK modules. Leaving
            // one out produces a ClassNotFoundException only at runtime, in the packaged app —
            // `run` uses the full JDK and hides it. The SQLite driver goes through JDBC.
            modules("java.sql")

            macOS {
                bundleID = "io.github.crispyduck123hc.notesai"

                // Signing is switched on only when the certificate is available, so a plain
                // `packageDmg` still works on a machine without it.
                buildEnv.environmentVariable("MACOS_SIGNING_IDENTITY").orNull?.let { identityName ->
                    signing {
                        sign.set(true)
                        identity.set(identityName)
                    }
                }

                // Only consulted by the notarize* tasks.
                notarization {
                    appleID.set(buildEnv.environmentVariable("NOTARIZATION_APPLE_ID"))
                    password.set(buildEnv.environmentVariable("NOTARIZATION_PASSWORD"))
                    teamID.set(buildEnv.environmentVariable("NOTARIZATION_TEAM_ID"))
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
