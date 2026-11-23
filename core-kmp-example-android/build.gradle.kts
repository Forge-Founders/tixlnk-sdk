buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // Same Gradle build as :core-kmp, so it must use the same Kotlin Gradle Plugin.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.2"
}

android {
    namespace = "com.txlnk.sdk.example"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.txlnk.sdk.example"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(project(":core-kmp"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    implementation("com.russhwolf:multiplatform-settings:1.3.0")
}
