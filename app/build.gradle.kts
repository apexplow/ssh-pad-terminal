plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.sshterminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.sshterminal"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")

    // Sprint 2: real SSH transport via SSHJ + a modern BouncyCastle JCE provider.
    //
    // SSHJ 0.38.0 uses PKCS#8 / Ed25519 key-loading code paths that need a BC
    // provider newer than what Android ships on API 29 (the system "BC" there
    // is ~BouncyCastle 1.62 — too old for the PEM helpers sshj pulls in).
    // We bundle bcprov-jdk18on 1.78.1 and register it explicitly inside
    // SshClient; do NOT rely on the system provider being recent enough.
    implementation("com.hierynomus:sshj:0.38.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
