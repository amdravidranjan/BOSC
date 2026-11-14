plugins {
    id("org.jetbrains.kotlin.jvm")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    // Same ONNX Runtime version as the Python parity export. The app supplies onnxruntime-android (same API).
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
}
