plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.josvpn.india"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.josvpn.india"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            // Workaround: AGP's per-library dexing step cannot desugar the Java records
            // inside the WireGuard tunnel library when minSdk < 35. Running the debug
            // build through R8 avoids that step. The rules in debug-rules.pro keep every
            // class and turn off renaming, so the code is not shrunk or obfuscated.
            isMinifyEnabled = true
            proguardFiles("debug-rules.pro")
        }
    }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20250531")
}
