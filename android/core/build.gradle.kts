// Everything the keyboard does that isn't Android UI: prompts, audio
// encoding, speech-to-text and cleanup. Plain Kotlin, so it's testable on
// any JVM.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The same prompt file the web app uses.
sourceSets.main {
    resources.srcDir(rootDir.resolve("../shared"))
}

dependencies {
    api("com.anthropic:anthropic-java:3.2.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.4")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
}
