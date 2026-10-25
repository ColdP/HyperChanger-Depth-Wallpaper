// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m

import java.time.LocalDate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = providers.gradleProperty("releaseStoreFile").orNull
val releaseStorePassword = providers.gradleProperty("releaseStorePassword").orNull
val releaseKeyAlias = providers.gradleProperty("releaseKeyAlias").orNull
val releaseKeyPassword = providers.gradleProperty("releaseKeyPassword").orNull
val buildDate = LocalDate.now().toString().replace('-', '.')

android {
    namespace = "io.github.btm_m.depthwallpaper"
    compileSdk = 37


    defaultConfig {
        applicationId = "io.github.btm_m.depthwallpaper"
        minSdk = 35
        targetSdk = 37
        versionCode = 1020101
        versionName = "1.2.1 RC1"
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
        ndk { abiFilters += "arm64-v8a" }

    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Prebuilt native dependencies are packaged without invoking the NDK.
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += "**"
        }
    }
}

dependencies {
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("io.github.kyant0:backdrop:2.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.github.kyant0:shapes:1.2.0")
    implementation("org.jetbrains.compose.ui:ui:1.11.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.11.0")
    implementation("org.jetbrains.compose.animation:animation:1.11.0")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.graphics:graphics-shapes:1.1.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")

    // Retained for the unchanged Settings page's connection status and stored preferences.
    implementation("io.github.libxposed:service:102.0.0")
}
