// RATL - root build. SPEC 6.1: V1 core is pure Kotlin/JVM; Rust + UniFFI is v1.1.
//
// The Kotlin plugin is pulled in via a buildscript classpath rather than the
// plugins {} DSL so it resolves the plugin JAR directly. The plugins {} DSL
// needs a separate marker artifact, which forces a network round trip even when
// the plugin itself is already present locally - unhelpful on a metered link.
buildscript {
    repositories {
        // Scoped to Android artifacts only. Left unscoped, Gradle probes
        // dl.google.com for Kotlin artifacts too, which on a full-tunnel VPN
        // turns every build into a series of timeouts.
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
        // M3: AGP 9.1.1 is already present locally and pairs with Gradle 9.
        classpath("com.android.tools.build:gradle:9.1.1")
    }
}

// The JVM modules. ratl-android is deliberately NOT here: it is the only module
// that may touch the Android SDK, and keeping the split explicit is what makes
// the checkNoAndroidImports guard meaningful.
val jvmModules = setOf("ratl-text", "ratl-core", "ratl-acoustic-ort", "ratl-eval")

allprojects {
    group = "ai.ratl"
    version = "1.0.0-SNAPSHOT"
    repositories {
        // Scoped to Android artifacts only. Left unscoped, Gradle probes
        // dl.google.com for Kotlin artifacts too, which on a full-tunnel VPN
        // turns every build into a series of timeouts.
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
    }
}

subprojects {
    if (name !in jvmModules) return@subprojects
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // Target Java 17 bytecode (what AGP accepts) while compiling with whatever
    // JDK is installed. Requesting a 17 TOOLCHAIN instead would force Gradle to
    // download a second JDK for no benefit.
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

    dependencies {
        add("testImplementation", kotlin("test"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "failed", "skipped") }
    }

    // SPEC 6.1 is enforced, not merely stated: RATL Core must run unchanged on
    // desktop JVM and on Android, so the portable modules may not reference the
    // Android SDK. Checked in the build because a comment does not fail CI.
    if (name in listOf("ratl-text", "ratl-core", "ratl-eval")) {
        tasks.register("checkNoAndroidImports") {
            group = "verification"
            description = "Fails if a platform-portable module imports android.*"
            val srcDir = layout.projectDirectory.dir("src")
            doLast {
                val offenders = srcDir.asFile.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { f ->
                        f.readLines().any {
                            it.trimStart().startsWith("import android.") ||
                                it.trimStart().startsWith("import androidx.")
                        }
                    }
                    .map { it.relativeTo(projectDir).path }
                    .toList()
                if (offenders.isNotEmpty()) {
                    throw GradleException(
                        "SPEC 6.1 violation - android.* imported in a portable module:\n" +
                            offenders.joinToString("\n") { "  $it" }
                    )
                }
            }
        }
        tasks.named("check") { dependsOn("checkNoAndroidImports") }
    }
}
