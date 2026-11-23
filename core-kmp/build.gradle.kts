plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.android.kotlin.multiplatform.library") version "9.5.2"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
}

// Built with the current Kotlin, but published for older consumers: with
// language and API version 2.3, JVM/Android apps compile against it with
// Kotlin 2.3 or newer (Kotlin reads one language version ahead on the JVM) and
// iOS (Kotlin/Native) consumers with Kotlin 2.3 or newer. Raise this only when
// the code needs a newer language feature or stdlib API, and update the README.
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
    }

    android {
        namespace = "com.txlnk.sdk.core"
        compileSdk = 37
        // Consumer-facing minimums: Android 7.0 (API 24) and Java 11 bytecode.
        minSdk = 24
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
        withHostTest {}
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("io.ktor:ktor-client-core:3.6.1")
            implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
            implementation("com.russhwolf:multiplatform-settings:1.3.0")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("com.android.installreferrer:installreferrer:2.2")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.6.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.6.1")
            implementation("com.russhwolf:multiplatform-settings-test:1.3.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
