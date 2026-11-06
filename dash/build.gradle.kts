plugins {
    alias(libs.plugins.android.application)
}

// Virtual dash ("จอจำลอง"): runs on a spare Android 8+ device and speaks the DASH side of the
// Carbit EasyConn / PXC protocol (docs/01-REVERSE-ENGINEERING.md), so the RideScreen AA phone app
// streams Android Auto to it exactly as it does to a bike. Separate app and applicationId: it is
// installed on a different device from the phone app and shares no data with it.
android {
    namespace = "io.github.karaboon.ridescreendash"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.karaboon.ridescreendash"
        minSdk = 26          // Android 8.0: LocalOnlyHotspot
        targetSdk = 36
        versionCode = 1
        versionName = ((project.findProperty("appVersion") as String?) ?: "1") +
            ((project.findProperty("versionSuffix") as String?) ?: "-dev")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Same key as the CI debug builds, so a release APK installs over a debug one.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // QR code for the dash screen (pure Java, no Play Services).
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(libs.junit)
}
