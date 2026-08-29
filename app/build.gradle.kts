plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

// Use a fresh build directory to avoid corrupted NTFS entries from previous builds.
// Use layout.buildDirectory (Gradle 8.x API) instead of deprecated buildDir assignment.
layout.buildDirectory.set(file("c:/Users/Yuan/AndroidStudioProjects/JM/app/build_v2"))

android {
    namespace = "com.carya.jm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.carya.jm"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Chaquopy embeds a native Python runtime.
            // TODO: re-enable x86_64 once its wheels are built.
            abiFilters += listOf("arm64-v8a")
        }
    }
    signingConfigs {
        create("release") {
            storeFile = file("../jm-release.jks")
            storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
            keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
            keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("C:/Users/Yuan/AppData/Local/Programs/Python/Python313/python.exe")
        pip {
            // Native wheels built locally for android_24_arm64_v8a.
            // Rebuild for x86_64 and add them here to support emulators.
            install("wheels/chaquopy_libffi-3.3-3-py3-none-android_24_arm64_v8a.whl")
            install("wheels/cffi-2.0.0-1-cp313-cp313-android_24_arm64_v8a.whl")
            install("wheels/curl_cffi-0.15.0-cp313-abi3-android_24_arm64_v8a.whl")
            install("wheels/chaquopy_libjpeg-1.5.3-2-py3-none-android_24_arm64_v8a.whl")
            install("wheels/chaquopy_freetype-2.9.1-2-py3-none-android_24_arm64_v8a.whl")
            install("wheels/chaquopy_libyaml-0.2.5-0-py3-none-android_24_arm64_v8a.whl")
            install("wheels/pillow-11.0.0-0-cp313-cp313-android_24_arm64_v8a.whl")
            install("wheels/pycryptodome-3.21.0-0-cp313-cp313-android_24_arm64_v8a.whl")
            install("wheels/pyyaml-6.0.3-0-cp313-cp313-android_24_arm64_v8a.whl")
            // Pure-Python packages from PyPI (jmcomic pulls in commonx, certifi, etc.)
            install("jmcomic==2.7.4")
        }
    }
}



dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation("androidx.webkit:webkit:1.11.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
