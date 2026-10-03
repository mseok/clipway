import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// keystore.properties and the keystore are created by scripts/build-android.sh and
// stay out of git. Keep them: an update must be signed with the same key.
val keystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.mseok.clipway"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mseok.clipway"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("local") {
            storeFile = rootProject.file(keystore.getProperty("storeFile", "clipway.jks"))
            storePassword = keystore.getProperty("password", "")
            keyAlias = keystore.getProperty("keyAlias", "clipway")
            keyPassword = keystore.getProperty("password", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("local")
        }
        debug {
            signingConfig = signingConfigs.getByName("local")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.systemProperty("clipway.root", rootProject.projectDir.parent) }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("play-services pulls in Fragment 1.0, which the ActivityResult APIs reject")
        }
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
