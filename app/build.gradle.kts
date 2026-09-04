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
        versionName = "1.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Chaquopy embeds a native Python runtime.
            // Only arm64-v8a for smallest APK size (most modern Android devices)
            abiFilters += listOf("arm64-v8a")
        }

        // Optimize native library packaging: extract at install time
        // Reduces APK size (compressed in APK) but increases on-disk size slightly
        // For minSdk >= 23, we can use extractNativeLibs=false to save space on device
        // but APK size will be larger (uncompressed). We want smaller APK.
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
            // Enable R8 full mode for more aggressive code removal
            isProfileable = false
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
        // Disable buildConfig to save a tiny bit of space
        buildConfig = false
    }

    packaging {
        jniLibs {
            // Keep compressed JNI libs in APK for smaller download size
            useLegacyPackaging = true
        }
        resources {
            // Remove unnecessary files from dependencies
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/proguard/*",
                "META-INF/*.pro",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
                "META-INF/services/*",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**.kotlin_builtins",
            )
        }
    }

    // Optimize APK splits - we only have one ABI so no need for splits
    // but we can optimize language resources
    bundle {
        language {
            // We only support Chinese, but keep all for now (small impact)
            enableSplit = false
        }
        density {
            enableSplit = false
        }
        abi {
            enableSplit = false
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

// ============================================================================
// APK size optimization: remove unused Python packages after pip install.
// These packages are only used for CLI/terminal UI, testing, or desktop
// environments and are completely unnecessary on Android.
// ============================================================================
afterEvaluate {
    listOf("debug", "release").forEach { buildName ->
        val capitalized = buildName.replaceFirstChar { it.uppercase() }
        val trimTaskName = "trim${capitalized}PythonPackages"

        tasks.register(trimTaskName, Delete::class.java) {
            description = "Remove unused Python packages to reduce APK size"

            val pipCommonDir = layout.buildDirectory.dir("python/pip/$buildName/common").get().asFile

            delete(fileTree(pipCommonDir) {
                // --- CLI / terminal UI libraries (~11.3 MB uncompressed) ---
                // jmcomic has try/except ImportError guards for these
                include("rich/**")
                include("rich-*/**")
                include("pygments/**")
                include("pygments-*/**")
                include("markdown_it/**")
                include("markdown_it_py-*/**")
                include("mdurl/**")
                include("mdurl-*/**")

                // --- curl_cffi CLI module ---
                include("curl_cffi/cli/**")

                // --- pycryptodome test files (~2.9 MB) ---
                // SelfTest is only for library testing, never used at runtime
                include("Crypto/SelfTest/**")

                // --- PIL Tkinter support (desktop only, ~20 KB) ---
                // Android has no Tkinter, these are safe to remove
                include("PIL/ImageTk.py")
                include("PIL/ImageTk.pyc")
                include("PIL/_tkinter_finder.py")
                include("PIL/_tkinter_finder.pyc")

                // --- certifi test files ---
                include("certifi/tests/**")
            })

            onlyIf("pip common directory exists") {
                pipCommonDir.exists()
            }
        }

        tasks.named("generate${capitalized}PythonRequirementsAssets").configure {
            dependsOn(trimTaskName)
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
    // @Preview annotation library (small, contains only annotations and interfaces)
    // Full ui-tooling (renderer) is debug-only via debugImplementation below
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Material Icons Extended (we use many icons from extended set)
    // TODO: consider replacing with custom vector drawables to reduce size
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    // Coil 2.7 自带 OkHttp（用于图片网络请求）；更新检查/下载也复用它，仅显式声明编译依赖
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
