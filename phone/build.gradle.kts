plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
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
        ndk { abiFilters += "arm64-v8a" } // the S23U; also keeps OpenCV from bloating the APK
    }

    packaging {
        jniLibs {
            // Stored uncompressed + page-aligned, and OpenCV's libc++_shared.so (4 KB-aligned) swapped for the
            // NDK r29 one in src/main/jniLibs (16 KB-aligned): clears Android's 16 KB page-size warning.
            useLegacyPackaging = false
            pickFirsts += "lib/arm64-v8a/libc++_shared.so"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.play.services.wearable)
    implementation(libs.wear.remote.interactions) // open Nock on the other device
    implementation(libs.opencv) // hit finding: same library the offline proof (tools/hits.py) used
}
