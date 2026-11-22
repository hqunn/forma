plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.forma.benchmark"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.forma.benchmark"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // One target device (C2), which is arm64. Shipping the other ABIs
        // would triple the APK for architectures this project never measures.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // The benchmark is measured in a debuggable build for convenience.
            // Record that fact: it is a caveat on every number, see README.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    // MediaPipe memory-maps the model out of the APK. A compressed asset
    // cannot be mapped, so these extensions must be stored uncompressed.
    androidResources {
        noCompress += listOf("task", "tflite", "bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // MoveNet is a bare TFLite graph with no task wrapper, so the harness
    // drives the interpreter itself. MediaPipe bundles its own copy of the
    // runtime privately; these are the public API for the second engine.
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.17.0")
}
