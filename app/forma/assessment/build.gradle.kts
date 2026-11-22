plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

sourceSets {
    test {
        // The tests load the REAL specification files from docs/specs rather
        // than copies. A specification edit that breaks a validation rule
        // therefore fails this build, which is the only way the rules in
        // SCHEMA.md stay true of the files on disk.
        resources.srcDir(rootProject.file("../../docs/specs"))
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
