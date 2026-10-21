plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nock"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Phone and watch must share the application id (and signing key) to talk over the Wearable Data Layer.
        applicationId = "com.nock"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.play.services.wearable)
    implementation(libs.wear.remote.interactions) // open Nock on the other device
    implementation(libs.wear.ongoing) // wrist raise returns to Nock during a session
    implementation(libs.androidx.core)
}
