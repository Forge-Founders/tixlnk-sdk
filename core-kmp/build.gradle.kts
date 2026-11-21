plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.android.kotlin.multiplatform.library") version "9.5.0"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.txlnk.sdk.core"
        compileSdk = 36
        minSdk = 24
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
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
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
