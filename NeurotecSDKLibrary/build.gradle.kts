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

    api(fileTree("libs") {
        include("*.aar", "*.jar")
        exclude(
            "*-javadoc.jar", // documentation only, never needed for build or runtime

            // Unused fingerprint scanner vendor SDKs (confirmed: only Suprema BioMini + Telpo are used)
            "neurotec-devices-fscanners-bitel-android.jar",
            "neurotec-devices-fscanners-bluefin-android.jar",
            "neurotec-devices-fscanners-digitalpersona-uareu-android.jar",
            "neurotec-devices-fscanners-ekemp-android.jar",
            "neurotec-devices-fscanners-futronic-android.jar",
            "neurotec-devices-fscanners-futronic-bluetooth-android.jar",
            "neurotec-devices-fscanners-greenbit-android.jar",
            "neurotec-devices-fscanners-hfsecurity-android.jar",
            "neurotec-devices-fscanners-identos-tactivo-android.jar",
            "neurotec-devices-fscanners-integratedbiometrics-android.jar",
            "neurotec-devices-fscanners-mantra-android.jar",
            "neurotec-devices-fscanners-miaxis-android.jar",
            "neurotec-devices-fscanners-morpho-android.jar",
            "neurotec-devices-fscanners-nextbiometrics-android.jar",
            "neurotec-devices-fscanners-nitgen-android.jar",
            "neurotec-devices-fscanners-secugen-android.jar",
            "neurotec-devices-fscanners-smufsbio-android.jar",
            "neurotec-devices-fscanners-startek-android.jar",
            "neurotec-devices-fscanners-suprema-realscan-android.jar", // different Suprema product than BioMini — keep suprema-biomini jar
            "neurotec-devices-fscanners-zkteco-android.jar", // already disabled via neurotec.plugin.fingers.zkteco.disable

            // Unused iris scanner vendor SDKs
            "neurotec-devices-irisscanners-iritech-irishield-android.jar",
            "neurotec-devices-irisscanners-mantra-android.jar",

            // Unused multimodal vendor SDK
            "neurotec-devices-multimodal-credenceid-android.jar"
        )
    })

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