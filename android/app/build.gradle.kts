import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.murmur"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.murmur"
        minSdk = 26
        targetSdk = 34
        // CI numbers each build so a new APK installs over the old one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.$versionCode"
    }

    signingConfigs {
        create("murmur") {
            // Every build must use the same key, or Android refuses to update the app.
            // Set these environment variables (repository secrets) to use your own key.
            storeFile = rootProject.file(System.getenv("MURMUR_KEYSTORE") ?: "keystore/murmur.jks")
            storePassword = System.getenv("MURMUR_KEYSTORE_PASSWORD") ?: "murmur-keyboard"
            keyAlias = System.getenv("MURMUR_KEY_ALIAS") ?: "murmur"
            keyPassword = System.getenv("MURMUR_KEY_PASSWORD") ?: "murmur-keyboard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("murmur")
        }
        debug {
            signingConfig = signingConfigs.getByName("murmur")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/*/module-info.class",
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
