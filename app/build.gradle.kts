plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.apexplow.hanterm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.apexplow.hanterm"
        // Issue #40 (P3 / decision): minSdk dropped from 36 to 34 in v1 listing
        // planning. The only real blocker for going below 36 was FGS
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE (API 34+); at 34 the existing
        // specialUse code path is still valid. targetSdk / compileSdk remain
        // at 36 (independent from minSdk).
        minSdk = 34
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        // Sprint 2.5 / BC-EN-01: enable BuildConfig generation so `BuildConfig.DEBUG`
        // resolves in Modules 13 + 14 (debug-log gating, auth-diagnostic gating).
        buildConfig = true
    }

    // Sprint Store / Issue #33 — release signing is driven entirely from
    // environment variables so the keystore (and its passwords) never
    // enter the repo. To produce a signed build locally:
    //
    //     export KEYSTORE_PATH=/secure/path/release.jks
    //     export KEYSTORE_PASSWORD=...
    //     export KEY_ALIAS=...
    //     export KEY_PASSWORD=...
    //     ./gradlew :app:assembleRelease
    //
    // Missing env vars leave the signing config incomplete; AGP then
    // surfaces a clear "signing config not specified" error at packaging
    // time rather than silently falling back to the debug keystore.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")
            if (keystorePath != null &&
                keystorePassword != null &&
                keyAlias != null &&
                keyPassword != null
            ) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        // Sprint 2.5 / BC-EN-02: debug build gets a `.debug` applicationIdSuffix so
        // debug + release can coexist on the same device.
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        // Sprint Store / Issue #33 — first official release build type.
        // isMinifyEnabled + isShrinkResources turn on R8 / resource shrinking;
        // proguard-rules.pro holds the SSHJ + BouncyCastle + Termux keep set
        // (those libraries use reflection for cipher / provider / JNI lookup
        // and would break under default R8).
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
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
            // Sprint 2.5 / S4 (PKP-LG-02): allow PublicKeyAuthProvider.loadKeyProvider
            // to call android.util.Log.d without crashing pre-existing JUnit tests.
            isReturnDefaultValues = true
        }
    }

    packaging {
        // Sprint 3.5 / SSHJ-0.40-UPGRADE: sshj 0.40 transitively pulls
        // bcprov-jdk18on / bcpkix-jdk18on / bcutil-jdk18on 1.80+. All three
        // JARs ship an OSGi multi-release manifest at the same path, so
        // mergeDebugJavaResource fails with "3 files found with path
        // META-INF/versions/9/OSGI-INF/MANIFEST.MF". Android's runtime
        // doesn't care about OSGi metadata — drop every copy. Excluding by
        // exact filename (not `**/OSGI-INF/*`) so a future, real collision
        // in that tree still surfaces as a build error instead of being
        // silently swallowed.
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    // Issue #41 — Lifecycle ViewModel + FontSizeController hoist.
    //
    // `lifecycle-viewmodel` provides the `ViewModel` base class and the
    // `viewModelScope` CoroutineScope that `HanTermAppViewModel` now extends.
    // `lifecycle-viewmodel-compose` provides the `viewModel(factory = ...)`
    // Compose helper used at the `HanTermApp` call site. `lifecycle-viewmodel-savedstate`
    // brings `SavedStateHandle` so `showTerminal` can survive process death.
    //
    // Aligned with `activity-compose:1.10.1` (Lifecycle 2.8.x). All three
    // modules are pinned to the same version to avoid mixed-version resolution.
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")

    // Sprint 2: real SSH transport via SSHJ + a modern BouncyCastle JCE provider.
    //
    // Historical note (pre-#19 minSdk was 29): Android's system "BC" on API 29
    // was ~BouncyCastle 1.62 — too old for sshj's PKCS#8 / Ed25519 PEM helpers.
    // We still bundle bcprov-jdk18on and register it explicitly inside
    // SshClient; do NOT rely on the system provider. minSdk is now 36
    // (Issue #19) but the explicit register remains load-bearing.
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Robolectric 4.16+ is required for SDK 36 (Baklava); the sandbox needs
    // JDK 21 (gradlew ships Temurin 21 as of Issue #19).
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    // bcpkix brings the org.bouncycastle.openssl.jcajce.* PEM helpers used
    // by PublicKeyAuthProviderTest to write Ed25519 keys in OpenSSH v1 format.
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // mockk-inline lets us mock the final TerminalRenderer class from the
    // com.termux:terminal-view AAR for the PTY-resize race regression test.
    // We deliberately avoid polluting the main classpath — testImplementation only.
    testImplementation("io.mockk:mockk:1.13.13")
    // Compose UI test harness for the ScrollbackBanner overlay.
    // Versions resolved by the Compose BOM above.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}
