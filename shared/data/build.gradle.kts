plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

val javacv: String = libs.versions.javacv.get()
val hostPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val name = when {
        "win" in os -> "windows"
        "mac" in os -> "macosx"
        else -> "linux"
    }
    name + "-" + if ("aarch64" in arch || "arm64" in arch) "arm64" else "x86_64"
}

kotlin {
    jvmToolchain(17)
    androidLibrary {
        namespace = "com.example.kmptest.data"
        compileSdk = 36
        minSdk = 29
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.documentfile)
            implementation(libs.opencv)
            implementation(libs.ffmpeg.kit)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
                // JavaCV's FFmpeg bindings. Only the natives of the OS this is built on are bundled, so each
                // platform's package is built on that platform (see the CI workflow). JavaCV's other native
                // libraries (OpenCV, OpenBLAS, ...) are left out: no desktop engine uses them.
                implementation(libs.javacv)
                for ((artifact, version) in listOf("javacpp" to javacv, "ffmpeg" to "8.1.2-$javacv")) {
                    implementation("org.bytedeco:$artifact:$version")
                    // the "-gpl" FFmpeg build contains libx264
                    implementation("org.bytedeco:$artifact:$version:$hostPlatform" + if (artifact == "ffmpeg") "-gpl" else "")
                }
            }
        }
    }
}
