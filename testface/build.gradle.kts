// Throwaway Watch Face Format face used only to exercise AltiKit's complications.
plugins {
    id("com.android.application")
}

android {
    namespace = "app.altikit.testface"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.altikit.testface"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
