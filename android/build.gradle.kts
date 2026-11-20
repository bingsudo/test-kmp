plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 1.2.3 -> versionName "1.2.3", versionCode 10203 (override with -Papp.versionCode)
val appVersion = providers.gradleProperty("app.version").get()
val appVersionCode = providers.gradleProperty("app.versionCode").orNull?.toInt()
    ?: appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// Release signing comes from the environment (a CI secret or your own shell), never from the repository.
val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")

android {
    namespace = "com.example.kmptest"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.kmptest"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    // One APK per CPU type instead of one with both: phones are ARM (arm64-v8a); x86_64 is for emulators and
    // the few x86 devices. Both APKs share the version code, so either can replace the other.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false  // the size is native libraries; shrinking would only risk the JNI-loaded classes
            // without a keystore the release APK stays unsigned (not installable) instead of silently using the debug key
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources { generateLocaleConfig = false }
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            // compressed in the APK (unpacked at install): a much smaller download for these large native libraries
            useLegacyPackaging = true
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared:ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.koin.android)

    // instrumented tests (./gradlew :android:connectedDebugAndroidTest, needs a device)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlin.test)
}
