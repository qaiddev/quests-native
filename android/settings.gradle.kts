// Standalone build for the qaid quests Android library — its unit and Robolectric tests, and
// the `dev.qaid:quests` release to Maven Central (see build.gradle.kts).
//
// An app consumes the module by SOURCE, not through this build: it includes `:qaidquests`
// with `projectDir` pointing here, so the module compiles with the app's own AGP, Kotlin and
// Compose compiler plugin (the apps pin AGP 9.4.0 / Kotlin 2.4.10, the same as below). That
// sidesteps the plugin-classloader clashes an `includeBuild` of an Android build invites.
pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
    plugins {
        id("com.android.library") version "9.4.0"
        // AGP 9 builds Kotlin itself but bundles an older Kotlin Gradle plugin. Resolving this
        // one onto the root classpath pins Kotlin to 2.4.10, the version the Compose compiler
        // plugin below must match.
        id("org.jetbrains.kotlin.jvm") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "qaid-quests-android"
include(":qaidquests")
