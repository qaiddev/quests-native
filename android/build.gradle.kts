import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension

// Publishing lives here, not in qaidquests/build.gradle.kts: apps build that module from
// source with their own plugin classpath, which has no publishing plugin on it. Only this
// standalone build loads the plugin and applies it to :qaidquests.
//
//   ./gradlew --no-daemon :qaidquests:publishToMavenLocal      no keys needed
//   ./gradlew --no-daemon :qaidquests:publishToMavenCentral    Central Portal upload
//
// Central needs mavenCentralUsername / mavenCentralPassword (a Portal user token) and a
// signing key: signingInMemoryKey (+ signingInMemoryKeyPassword, optional
// signingInMemoryKeyId), or signing.keyId / signing.password / signing.secretKeyRingFile.
// Set them in ~/.gradle/gradle.properties or as ORG_GRADLE_PROJECT_<name> env vars.
plugins {
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
}

/** Sign only when a key is present, so a local publish works on a machine without one. */
val hasSigningKey = listOf("signingInMemoryKey", "signing.keyId", "signing.gnupg.keyName")
    .any { providers.gradleProperty(it).isPresent }

project(":qaidquests") {
    // After AGP, which the plugin configures (publishing variant, sources, javadoc).
    pluginManager.withPlugin("com.android.library") {
        pluginManager.apply("com.vanniktech.maven.publish")
        extensions.configure<MavenPublishBaseExtension> {
            configure(
                AndroidSingleVariantLibrary(
                    javadocJar = JavadocJar.Javadoc(),
                    sourcesJar = SourcesJar.Sources(),
                    variant = "release",
                ),
            )
            coordinates("dev.qaid", "quests", "0.1.0")
            publishToMavenCentral()
            if (hasSigningKey) signAllPublications()
            pom {
                name.set("qaid quests for Android")
                description.set(
                    "Native Jetpack Compose quests for qaid.dev: step-by-step questionnaires drawn " +
                        "with real Android controls, answered into your qaid project.",
                )
                url.set("https://github.com/qaiddev/quests-native")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("qaiddev")
                        name.set("qaiddev")
                        url.set("https://qaid.dev")
                    }
                }
                scm {
                    url.set("https://github.com/qaiddev/quests-native")
                    connection.set("scm:git:https://github.com/qaiddev/quests-native.git")
                    developerConnection.set("scm:git:ssh://git@github.com/qaiddev/quests-native.git")
                }
            }
        }
    }
}

// Coverage, also from here and not the module's own build, for the same reason as publishing:
// an app that builds :qaidquests from source has no Kover on its classpath.
//
//   ./gradlew --no-daemon :qaidquests:koverVerifyCore          the gate (dev.qaid.quests.core)
//   ./gradlew --no-daemon :qaidquests:koverXmlReportCore       the gated scope, as XML
//   ./gradlew --no-daemon :qaidquests:koverHtmlReportDebug     everything, ui/ and internal/ included
//
// `core` is the pure, JVM-tested package. ui/, internal/ and QaidQuests.kt are exercised by the
// Robolectric + compose-ui-test flows and reported separately, but not gated.
// ../scripts/coverage-android.sh runs all three.
//
// The gate is one point under the measured numbers, never under 95; raise it with them.
// -PcoverageLineGate / -PcoverageBranchGate override it, to check the gate fails.
val coreLineGate = providers.gradleProperty("coverageLineGate").map(String::toInt).getOrElse(99)
val coreBranchGate = providers.gradleProperty("coverageBranchGate").map(String::toInt).getOrElse(98)

project(":qaidquests") {
    pluginManager.withPlugin("com.android.library") {
        pluginManager.apply("org.jetbrains.kotlinx.kover")
        extensions.configure<KoverProjectExtension> {
            currentProject {
                createVariant("core") { add("debug") }
            }
            reports {
                variant("core") {
                    filters { includes { packages("dev.qaid.quests.core") } }
                    verify {
                        rule("core lines") { bound { minValue = coreLineGate; coverageUnits = CoverageUnit.LINE } }
                        rule("core branches") { bound { minValue = coreBranchGate; coverageUnits = CoverageUnit.BRANCH } }
                    }
                }
            }
        }
    }
}
