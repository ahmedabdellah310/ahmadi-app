pluginManagement {
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
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
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

rootProject.name = "ratl"

// RATL Core is pure Kotlin/JVM (SPEC 6.1). No Android Gradle plugin is applied
// to ratl-text, ratl-core or ratl-eval, so an `android.*` import is a compile
// error rather than a review comment. ratl-android arrives in M3.
include("ratl-text")
include("ratl-core")
include("ratl-acoustic-ort")
include("ratl-eval")

// M3. The only Android-specific module: a thin wrapper plus onnxruntime-android.
// No NDK, no CMake, no JNI - the core is pure Kotlin and ORT ships prebuilt
// native libraries per ABI.
include("ratl-android")
