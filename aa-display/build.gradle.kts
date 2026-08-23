plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine") version "4.4.0"
}

android {
    val buildTime = System.currentTimeMillis()
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nitsuya.aa.display"
        minSdk = 33
        targetSdk = 36
        versionCode = 3072
        versionName = "0.24#17.4-r12"
        buildConfigField("long", "BUILD_TIME", buildTime.toString())
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        resources.excludes.addAll(
            arrayOf(
                "META-INF/**",
                "kotlin/**",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        )
        jniLibs {
            // minSdk 33: keep libdexkit.so uncompressed so 16 KB devices can map it.
            useLegacyPackaging = false
        }
    }
    signingConfigs {
        create("release") {
            storeFile = file("../key.jks")
            storePassword = System.getenv("KEY_ANDROID")
            keyAlias = "key0"
            keyPassword = System.getenv("KEY_ANDROID")
            enableV1Signing = false
            enableV2Signing = false
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("KEY_ANDROID") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        getByName("debug") {
            // Keep debug artifacts unminified to avoid AGP warnings and speed up test builds.
            isMinifyEnabled = false
            isShrinkResources = false
            if (System.getenv("KEY_ANDROID") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

    }
    
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val versionName = variant.versionName.replace("#", "-")
            output.outputFileName = "aa-display-${versionName}.apk"
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        languageVersion = "2.0"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    androidResources.additionalParameters += mutableListOf("--allow-reserved-package-id", "--package-id", "0x64")

    namespace = "io.github.nitsuya.aa.display"
    buildToolsVersion = "35.0.0"
}

configurations.all {
    // Keep transitive androidx.appcompat out if any lib pulls it — we use platform themes.
    exclude("androidx.appcompat", "appcompat")
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // Cluster lyric invisible MediaBrowserService shell (Title egress to AA Now Playing).
    implementation("androidx.media:media:1.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    compileOnly(project(":lib-stub"))
    implementation("dev.rikka.tools.refine:runtime:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
    compileOnly(files("./libs/de.robv.android.xposed_api_82.jar"))
    implementation("com.github.kyuubiran:EzXHelper:1.0.3")
    implementation("org.luckypray:dexkit:2.0.7")
    implementation(files("./libs/aauto.aar"))

    testImplementation("junit:junit:4.13.2")
}
