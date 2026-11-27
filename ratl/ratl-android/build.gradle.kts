// AGP 9 ships built-in Kotlin support and registers the `kotlin` extension
// itself, so applying org.jetbrains.kotlin.android as well is a hard conflict.
plugins {
    id("com.android.library")
}

android {
    namespace = "ai.ratl.android"
    // The installed platforms are android-36.1 (API 36 minor 1) and
    // android-37.0. AGP needs the minor stated explicitly, otherwise it looks
    // for a plain "android-36" that is not installed.
    compileSdk = 36
    compileSdkMinor = 1

    defaultConfig {
        // Android 7.0. ONNX Runtime supports 21+, so RATL takes the lower
        // bound rather than forcing an integrating app to raise its own minSdk.
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ORT ships prebuilt native libraries; RATL authors none. arm64-v8a is
        // the benchmark device and effectively all modern hardware. The other
        // ABIs are excluded so the AAR does not carry code the project cannot
        // test on real hardware.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    androidResources {
        // The ONNX graph and raw PCM do not compress usefully; leaving them
        // uncompressed keeps install and first load fast.
        noCompress += listOf("onnx", "s16")
    }

    packaging {
        // The instrumentation APK carries the model and fixtures; leave them
        // uncompressed so ORT can memory-map rather than inflate them.
        resources.excludes += "META-INF/*"
    }
}

dependencies {
    api(project(":ratl-core"))
    api(project(":ratl-acoustic-ort"))
    // Same ONNX Runtime Java API as the desktop artifact, with prebuilt native
    // libraries per ABI. This is what lets one Kotlin core serve both targets
    // with no NDK and no JNI of our own.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
