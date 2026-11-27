plugins { application }

dependencies {
    implementation(project(":ratl-core"))
    implementation(project(":ratl-acoustic-ort"))
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
}

application {
    mainClass.set("ai.ratl.eval.BenchG2")
}

// G5 is specified single-threaded (SPEC 7.2); keep the harness honest about it.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    jvmArgs("-Xmx2g")
}
