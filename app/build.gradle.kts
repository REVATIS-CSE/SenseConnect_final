plugins {
    alias(libs.plugins.android.application)
}

// Production SenseConnect backend (Render). Release builds always use this, over HTTPS.
val productionApiBaseUrl = "https://senseconnect-api.onrender.com/"

// Debug builds use production too, unless a developer points them at a local server:
//   ./gradlew assembleDebug -Psenseconnect.devApiBaseUrl=http://10.0.2.2:3000/
// (cleartext to 10.0.2.2/localhost is permitted only by src/debug/res/xml/network_security_config.xml)
val debugApiBaseUrl: String = providers.gradleProperty("senseconnect.devApiBaseUrl")
    .getOrElse(productionApiBaseUrl)

android {
    namespace = "com.example.senseconnect"

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.senseconnect"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"


        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"$productionApiBaseUrl\"")
            optimization {
                enable = false
            }
        }
    }

    // Also produce small per-CPU APKs (e.g. app-arm64-v8a-debug.apk for almost all phones);
    // the universal APK is still built for emulators/older devices.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    // AndroidX
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)

    // Lifecycle / ViewModel / Coroutines
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit Text Recognition - bundled model, so OCR works offline from first launch
    implementation(libs.mlkit.text.recognition)

    // GPS / Location
    implementation(libs.play.services.location)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
