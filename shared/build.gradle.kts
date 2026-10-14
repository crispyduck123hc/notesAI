import java.util.Properties
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinSerialization)
}

sqldelight {
    databases {
        create("NotesDatabase") {
            packageName.set("com.example.notesai.db")
            // Generated `.db` snapshots of every schema version live here and are
            // committed. They are what verifyMigrations checks `.sqm` migrations against.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
}

// ---- Application version ------------------------------------------------------
// Read from gradle.properties so there is one value to bump per release. It is compiled into the
// app (and shown in the sidebar) so a bug report can say which build it came from.
val appVersion = providers.gradleProperty("notesai.version").getOrElse("0.0.0")

val appVersionDir = layout.buildDirectory.dir("generated/appVersion/kotlin")

val generateAppVersion by tasks.registering {
    val outputDir = appVersionDir
    val version = appVersion
    inputs.property("version", version)
    outputs.dir(outputDir)

    doLast {
        val dir = outputDir.get().asFile.resolve("com/example/notesai")
        dir.mkdirs()
        dir.resolve("AppVersion.kt").writeText(
            buildString {
                appendLine("package com.example.notesai")
                appendLine()
                appendLine("// Generated from gradle.properties (notesai.version) at build time.")
                appendLine("// Shown in the app's sidebar so a bug report can name the build.")
                appendLine("const val APP_VERSION: String = \"$version\"")
            },
        )
    }
}

// ---- Local OAuth credentials -------------------------------------------------
// Client ids/secrets are per-machine and must not be committed, so they live in the
// git-ignored local.properties and are compiled into a generated Kotlin file. Missing
// values are fine: the project still builds and sign-in reports the missing config.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val localSecretsDir = layout.buildDirectory.dir("generated/localSecrets/kotlin")

val generateLocalSecrets by tasks.registering {
    val outputDir = localSecretsDir
    val values = mapOf(
        "DESKTOP_CLIENT_ID" to localProperties.getProperty("google.desktop.clientId").orEmpty(),
        "DESKTOP_CLIENT_SECRET" to localProperties.getProperty("google.desktop.clientSecret").orEmpty(),
        "IOS_CLIENT_ID" to localProperties.getProperty("google.ios.clientId").orEmpty(),
        "ANDROID_CLIENT_ID" to localProperties.getProperty("google.android.clientId").orEmpty(),
    )
    inputs.properties(values)
    outputs.dir(outputDir)

    doLast {
        fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val dir = outputDir.get().asFile.resolve("com/example/notesai/auth")
        dir.mkdirs()
        dir.resolve("LocalSecrets.kt").writeText(
            buildString {
                appendLine("package com.example.notesai.auth")
                appendLine()
                appendLine("// Generated from local.properties at build time. Do not edit or commit values.")
                appendLine("internal object LocalSecrets {")
                values.forEach { (name, value) ->
                    appendLine("    const val $name: String = ${literal(value)}")
                }
                appendLine("}")
            },
        )
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            binaryOption("bundleId", "com.example.notesai.shared")
            linkerOpts("-lsqlite3")
        }
    }

    jvm()

//    js {
//        browser()
//    }

//    @OptIn(ExperimentalWasmDsl::class)
//    wasmJs {
//        browser()
//    }

    android {
        namespace = "com.example.notesai.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateLocalSecrets)
            kotlin.srcDir(generateAppVersion)
        }

        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
        }
        commonMain.dependencies {
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.datetime)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
//        jsMain.dependencies {
//            implementation("app.cash.sqldelight:web-worker-driver:2.3.2")
//            implementation(libs.wrappers.browser)
//        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.ktor.client.okhttp)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}