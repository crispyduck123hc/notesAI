import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("NotesDatabase") {
            packageName.set("com.example.notesai.db")
        }
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
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation("app.cash.sqldelight:android-driver:2.3.2")
        }
        commonMain.dependencies {
            implementation("app.cash.sqldelight:coroutines-extensions:2.3.2")
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
            implementation("app.cash.sqldelight:native-driver:2.3.2")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
//        jsMain.dependencies {
//            implementation("app.cash.sqldelight:web-worker-driver:2.3.2")
//            implementation(libs.wrappers.browser)
//        }
        jvmMain.dependencies {
            implementation("app.cash.sqldelight:sqlite-driver:2.3.2")
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}