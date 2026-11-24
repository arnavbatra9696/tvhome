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
