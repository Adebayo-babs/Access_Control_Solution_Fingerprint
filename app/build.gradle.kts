plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.example.access_control_solution"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.access_control_solution"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11

        isCoreLibraryDesugaringEnabled = true

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
            pickFirsts += listOf(
                "lib/armeabi-v7a/libNCore.so",
                "lib/arm64-v8a/libNCore.so",
                "lib/x86/libNCore.so",
                "lib/x86_64/libNCore.so",
                "**/libjnidispatch.so"
            )
        }

        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "member-search-index.zip",
                "jquery-ui.overrides.css",
                "jquery/jquery-ui.min.css",
                "index-all.html",
                "**", "META-INF/**", "element-list", "legal/ASSEMBLY_EXCEPTION", "**/*.html", "**/*.png", "**/*.js", "**/*.css", "**/*.md"
            )
            pickFirsts += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )

            jniLibs {
                useLegacyPackaging = true
                pickFirsts += listOf(
                    "**/libjnidispatch.so",
                    "lib/**/libNCore.so"
                )
            }
        }
    }

    packagingOptions {
        resources {
            excludes += "member-search-index.zip"
        }
    }
}

dependencies {

    implementation(project(":NeurotecSDKLibrary"))

    implementation("androidx.core:core:1.12.0")
    implementation("androidx.core:core-ktx:1.12.0")

    implementation(libs.androidx.lifecycle.runtime.ktx.v270)
    implementation(libs.androidx.activity.compose.v193)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    //Lottie
    implementation(libs.lottie.compose)

    // JNA dependency
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    implementation(libs.androidx.foundation.layout.android)
    implementation(libs.androidx.foundation.layout.android)
    implementation(libs.play.services.nearby)
    implementation(libs.play.services.recaptcha)
    implementation(libs.camera.view)
    implementation(libs.camera.lifecycle)
    implementation("androidx.camera:camera-core:1.5.0")
    implementation("androidx.camera:camera-camera2:1.5.0")
    coreLibraryDesugaring (libs.desugar.jdk.libs)

    // Coroutines
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Room Database
    implementation(libs.androidx.room.runtime.v250)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler.v250)

    // Lifecycle components
    implementation ("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation ("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Activity KTX for lifecycleScope
    implementation ("androidx.activity:activity-ktx:1.9.3")
    implementation ("androidx.fragment:fragment-ktx:1.6.1")

    // Hilt Dependency
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("com.google.dagger:hilt-android:2.49")
    ksp("com.google.dagger:hilt-compiler:2.49")

    // Pull-to-refresh functionality
    implementation ("com.google.accompanist:accompanist-swiperefresh:0.32.0")


}
