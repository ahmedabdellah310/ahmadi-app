dependencies {
    api(project(":ratl-core"))
    // Provided by the consumer: `onnxruntime` on desktop, `onnxruntime-android`
    // on Android. Same Java API, which is what lets one core serve both.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")
}
