plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.idos.pos"
    // compileSdk/targetSdk pinned to 34, minSdk to 26 — task 0.2 assumption:
    // design.md left this unpinned; 26 covers CameraX/MLKit's practical minimum
    // and keeps broad device reach for a POS target device population.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.idos.pos"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Room schema export. Exported straight into `src/main/assets` (task 2.6,
    // moved from the original build-only `$projectDir/schemas`) so the exported
    // `<database-canonical-name>/<version>.json` files land directly at the
    // assets ROOT — `androidx.room.testing.MigrationTestHelper` resolves them by
    // that exact relative path (NOT under any extra subfolder). Only
    // `main`/`debug` (+ AAR) assets are packaged into the `apk-for-local-test.ap_`
    // bundle that Robolectric reads for JVM unit tests — a `test`-sourceSet-only
    // assets dir is NOT included in that bundle in this AGP version (confirmed
    // empirically: `packageDebugUnitTestForUnitTest`'s output only ever pulls
    // from `mergeDebugAssets`). Putting the schemas under `main/assets` adds a
    // few KB of JSON to the production APK, which is the accepted tradeoff for a
    // real (non-skipped) migration test — see `LicenseStateDaoTest.
    // migration1to2_appliesCleanly_toAV1SchemaFixture`.
    ksp {
        arg("room.schemaLocation", "$projectDir/src/main/assets")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // --- Compose (task 0.3) ---
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // --- Room (task 0.4) ---
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // --- CameraX + MLKit barcode scanning (task 0.5) ---
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // --- Jetpack Security — EncryptedSharedPreferences for PIN storage (task 0.6) ---
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // --- Coroutines (task 0.7) ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.core:core-ktx:1.13.1")

    // --- Test deps (task 0.8) ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("androidx.room:room-testing:$roomVersion")
    // JVM-side Compose UI tests via Robolectric (task 3.4; design.md Testing
    // Strategy: "createComposeRule UI tests (JVM where possible)").
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    // Nav-shell test-only seam (nav/PosNavHostTest.kt): TestNavHostController
    // lets a test drive navigation directly, bypassing UI paths that are
    // themselves untestable under Robolectric (e.g. the barcode-scan
    // "unknown barcode" hand-off, which normally goes through
    // scan/BarcodeScanScreen's real-camera-bound CameraPreviewWithAnalysis).
    testImplementation("androidx.navigation:navigation-testing:2.7.7")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Test-fixture-only: encodes a real EAN-13 bitmap at runtime for
    // BarcodeScanPipelineTest (task 6.4) instead of shipping a static image
    // asset. Pure-Java barcode encoder, no Android/main-source dependency.
    androidTestImplementation("com.google.zxing:core:3.5.3")
}

// Task 6.4 apply-progress note: one JVM per unit-test class. Some existing
// Compose UI tests (ProductFormScreenTest, InventoryMovementFormScreenTest)
// intentionally render an AlertDialog and, per their own class docs, already
// accept that Robolectric's Compose idle-detection never fully settles for
// that composition within the SAME test method. Left running in a shared
// forked JVM, that leftover Compose/Robolectric static runtime state was
// found to leak into later, unrelated Compose test classes (including the
// new scan/CameraPermissionGateTest, task 6.2/6.4) and make their
// `setContent`/`waitForIdle` calls hang indefinitely — reproducible only
// once enough prior Compose test classes had run in the same JVM, and NOT
// fixed by raising Espresso's idling timeout (see CameraPermissionGateTest's
// class doc for the full investigation). Forcing a fresh JVM per test class
// fully isolates that static state without touching any Phase 3/4/5 test file.
tasks.withType<Test> {
    forkEvery = 1
}
