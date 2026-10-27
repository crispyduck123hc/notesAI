import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.example.notesai"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    // Derived from `notesai.version`, so the code can never lag the name. Play requires every release
    // to carry a higher code than the last, and MAJOR.MINOR.PATCH fits into 10_000 / 100 / 1 — room for
    // 99 minors and 99 patches before the ceiling matters.
    val appVersion = providers.gradleProperty("notesai.version").getOrElse("1.0.0")
    val versionParts = appVersion.split(".", "-").map { it.toIntOrNull() ?: 0 }
    val computedVersionCode =
        versionParts.getOrElse(0) { 0 } * 10_000 +
            versionParts.getOrElse(1) { 0 } * 100 +
            versionParts.getOrElse(2) { 0 }

    defaultConfig {
        // Store identity. Deliberately not `com.example.*`: that prefix is unowned (Play
        // rejects it) and it gets baked into the Android OAuth client and the store listing.
        applicationId = "io.github.crispyduck123hc.notesai"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = computedVersionCode
        // Same source of truth as the desktop installer name and the version the app displays.
        versionName = appVersion
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
