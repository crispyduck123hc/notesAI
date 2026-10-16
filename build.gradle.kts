plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    id("app.cash.sqldelight") version "2.3.2" apply false

    // Code quality. Declared here so the versions live with the rest, then applied to every module
    // below. `apply false` because this script configures the modules rather than being code itself.
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// ---- Code quality -------------------------------------------------------------
// ktlint answers "is this formatted the way the project formats things", and can fix it:
// `./gradlew ktlintFormat`. detekt answers "is anything here a bad idea" — complexity, swallowed
// errors, suspicious constructs — and reports rather than rewrites.
//
// Applied from one place so the two tools cannot drift apart per module.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.5.0")
        // The lambda form, not string patterns: the plugin matches patterns against paths *relative to
        // each source directory*, and SQLDelight's generated
        // `com/example/notesai/db/NoteEntity.kt` is therefore indistinguishable from the handwritten
        // `com/example/notesai/db/DatabaseDriverFactory.kt` sitting beside it. The lambda gets the
        // real file, so the build directory can be excluded properly.
        filter {
            exclude { element -> element.file.path.contains("/build/generated/") }
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        // detekt's default source directories are the Maven layout, which is not where a
        // multiplatform module keeps its code — and the app modules use `src/main/kotlin`, so both
        // layouts have to be listed or a module is silently skipped.
        source.setFrom(
            files(
                "src/main/kotlin",
                "src/test/kotlin",
                "src/commonMain/kotlin",
                "src/jvmMain/kotlin",
                "src/androidMain/kotlin",
                "src/iosMain/kotlin",
                "src/commonTest/kotlin",
                "src/jvmTest/kotlin",
            ),
        )
        // Generated code is not ours to fix: SQLDelight's interfaces, Compose's resource
        // collectors, and the secrets and version constants this build writes. Listing the source
        // directories explicitly above is what keeps them out — detekt never sees `build/`.

        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        // Everything detekt finds today is recorded per module, so the check can gate new code from
        // now on rather than being permanently red. Per module, not one shared file: each module
        // writes its own baseline and would otherwise overwrite the others.
        // Shrink it as the listed code is cleaned up: fix something, then `./gradlew detektBaseline`.
        baseline = file("$projectDir/detekt-baseline.xml")
    }
}
