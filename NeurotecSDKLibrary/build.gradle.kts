plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.neurotecsdklibrary"
    compileSdk = 35

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
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
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    packaging {
        resources {
            excludes += listOf(
                "member-search-index.zip",
                "jquery-ui.overrides.css",
                "jquery/jquery-ui.min.css",
                "index-all.html",
                "**", "META-INF/**", "element-list", "legal/ASSEMBLY_EXCEPTION", "**/*.html", "**/*.png", "**/*.js", "**/*.css", "**/*.md"
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
}

dependencies {

    api(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // JNA dependency
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("androidx.camera:camera-view:1.5.0")
    implementation(libs.androidx.camera.lifecycle)
}