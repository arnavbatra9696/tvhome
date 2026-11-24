plugins {
    id("com.android.application")
}

android {
    namespace = "com.arnavbatra.tvhome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arnavbatra.tvhome"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Sign every debug build with the same committed key. Without this, each fresh CI runner made a new
    // ~/.android/debug.keystore, and Android refused to update the installed app ("signatures do not match").
    // Standard debug credentials; this key is only for sideloading onto your own TV.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // The whole UI lives in /web and is bundled into the app as-is.
        getByName("main") {
            assets.srcDirs("../../web")
        }
    }
}
