plugins {
    autowire(libs.plugins.android.application)
    autowire(libs.plugins.kotlin.android)
}

android {
    namespace = property.project.app.packageName
    compileSdk = property.project.android.compileSdk

    defaultConfig {
        applicationId = property.project.app.packageName
        minSdk = property.project.android.minSdk
        targetSdk = property.project.android.targetSdk
        versionName = property.project.app.versionName
        versionCode = property.project.app.versionCode
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.clear();
            abiFilters.add("arm64-v8a");
        }
    }
    signingConfigs {
        // A checked-in throwaway key: GitHub runners generate a fresh debug keystore per job, so
        // every CI build used to get a different signature and installs silently failed to replace
        // the previous version.
        create("ci") {
            storeFile = rootProject.file("keystore/tgsp.jks")
            storePassword = "tgspdebug"
            keyAlias = "tgsp"
            keyPassword = "tgspdebug"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ci")
            // LSPosed loads every dex of the module into the client process at injection. Keep the
            // payload as small as possible: the app under test is sensitive to the extra class
            // linker work during its own startup.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            signingConfig = signingConfigs.getByName("ci")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    lint { checkReleaseBuilds = false }

    // ffmpeg-kit ships uncompressed .so files. Extract them on install instead of mmap'ing them
    // straight from the APK: that path is what Android's 16 KB page-size check verifies, and this
    // AGP version still zipaligns to 4 KB.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    // TODO Please visit https://highcapable.github.io/YukiHookAPI/en/api/special-features/host-inject
    // TODO 请参考 https://highcapable.github.io/YukiHookAPI/zh-cn/api/special-features/host-inject
    // androidResources.additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x64")
}

dependencies {
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview")
    implementation(com.github.duanhong169.drawabletoolbox)
    implementation(androidx.core.core.ktx)
    implementation(androidx.appcompat.appcompat)
    implementation(com.google.android.material.material)
    implementation(androidx.constraintlayout.constraintlayout)
    testImplementation(junit.junit)
    androidTestImplementation(androidx.test.ext.junit)
    androidTestImplementation(androidx.test.espresso.espresso.core)
    // ffmpeg-kit is retired upstream; this fork is the drop-in build whose .so have 16 KB
    // (p_align 0x4000) ELF alignment, which the old 6.0.1 artefacts do not have.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.9")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}