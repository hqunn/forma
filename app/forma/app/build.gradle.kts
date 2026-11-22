plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.forma.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.forma.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // One target device (4.1, constraint C2), which is arm64. The
        // other ABIs would triple the APK for architectures this project
        // never measures and therefore cannot claim anything about.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
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

    // MediaPipe memory-maps the model straight out of the APK. A compressed
    // asset cannot be mapped, so these extensions ship uncompressed. The
    // cue bank is already PCM and gains nothing from deflate either.
    androidResources {
        noCompress += listOf("task", "tflite", "bin", "wav")

        // docs/specs holds its own documentation beside the specifications,
        // and app/forma/cues holds the generator that produced the bank.
        // Pointing the asset directories at where those files are authored
        // is right; shipping SCHEMA.md and a PowerShell script to a handset
        // is not.
        ignoreAssetsPatterns += listOf("*.md", "*.ps1", ".gitkeep")
    }

    // Sources live under src/main/kotlin rather than src/main/java, which is
    // the convention the :assessment module already uses.
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")

    sourceSets["main"].assets.srcDirs(
        // The specifications and the cue bank are taken from where they are
        // authored, not copied here. This is the same arrangement the
        // :assessment tests use for docs/specs, and for the same reason: a
        // copy is a second source of truth that drifts silently. An edit to
        // a specification or a regenerated cue reaches the handset on the
        // next build with no step in between that anyone has to remember.
        rootProject.file("../../docs/specs"),
        rootProject.file("cues"),
        // The model bundle, which is gitignored at 5.8 MB of third-party
        // weights. scripts/fetch-model.ps1 puts it here.
        layout.projectDirectory.dir("src/main/assets-model"),
    )
}

dependencies {
    // The whole of the assessment layer, which has no Android dependency of
    // its own. Chapter 6.4 requires that direction of dependency and nothing
    // in this module may reverse it.
    implementation(project(":assessment"))

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

    // MoveNet is a bare TFLite graph with no task wrapper, so the engine
    // drives the interpreter itself. MediaPipe's tasks-vision was here until
    // 2026-11-04 and went with the engine it wrapped; nothing in this module
    // references it, and leaving it would ship twenty megabytes of runtime
    // for a model that is no longer loaded.
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.17.0")

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
