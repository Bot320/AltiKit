plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.altikit"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.altikit"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // Official releases are signed with the AltiKit release key, which the release
    // workflow passes in through the environment (see docs/RELEASING.md). Without it,
    // local release builds fall back to the debug key so they can still be sideloaded.
    val releaseKeystore = System.getenv("ALTIKIT_KEYSTORE")?.let { path ->
        rootProject.file(path).also { require(it.isFile) { "ALTIKIT_KEYSTORE points to a missing file: $path" } }
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                val password = requireNotNull(System.getenv("ALTIKIT_KEYSTORE_PASSWORD")) {
                    "ALTIKIT_KEYSTORE is set but ALTIKIT_KEYSTORE_PASSWORD is not"
                }
                storeFile = releaseKeystore
                storePassword = password
                keyAlias = System.getenv("ALTIKIT_KEY_ALIAS") ?: "altikit"
                keyPassword = System.getenv("ALTIKIT_KEY_PASSWORD") ?: password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material:1.5.6")
    implementation("androidx.wear.compose:compose-foundation:1.5.6")
    implementation("androidx.wear.compose:compose-navigation:1.5.6")
    implementation("androidx.wear.compose:compose-material3:1.5.6")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    // The Android stub of org.json throws in JVM tests; the forecast model is parsed with it.
    testImplementation("org.json:json:20240303")
}
