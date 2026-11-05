plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One codebase, two builds - the ONLY difference is the SETUP_MODE flag:
//   normal: ./gradlew assembleRelease                   -> updates only (sealed)
//   setup:  ./gradlew assembleRelease -PsetupMode=true  -> may also install the
//           first copy of each allowlisted app (setup day only)
val setupMode = (project.findProperty("setupMode") as String?)?.toBoolean() ?: false

android {
    namespace = "com.heavysighs.sealedupdater"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.heavysighs.sealedupdater"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
        if (setupMode) versionNameSuffix = "-setup"
        buildConfigField("boolean", "SETUP_MODE", setupMode.toString())
        manifestPlaceholders["appLabel"] =
            if (setupMode) "Sealed Updater (Setup)" else "Sealed Updater"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "sealedupdater"
            keyAlias = "sealedupdater"
            keyPassword = "sealedupdater"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
