plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is injected only by the tag-triggered release workflow.
 // Pull-request CI deliberately builds an unsigned release variant.
val releaseKeystorePath = System.getenv("HAMSEDA_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("HAMSEDA_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("HAMSEDA_KEY_ALIAS")
val releaseKeyPassword = System.getenv("HAMSEDA_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.hamseda.walkie"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.hamseda.walkie"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DOPUS_BUILD_PROGRAMS=OFF",
                    "-DOPUS_BUILD_TESTING=OFF",
                    "-DOPUS_DRED=OFF",
                    "-DOPUS_OSCE=OFF",
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // Retain legacy Concentus source for provenance, but exclude it from Java compilation.
    // SourceSet.java.exclude is not exposed consistently by AGP's Kotlin DSL; excluding
    // at the JavaCompile task level works with the Android Gradle Plugin used here.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9's built-in Kotlin compiler defaults to the Java targetCompatibility.
    // Keep Java/Kotlin bytecode aligned at JVM 17 without the legacy kotlinOptions DSL.
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // android.util.Log (used in session/transport code paths under test)
        // is a stub on the JVM; return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Unit tests (JVM): :app:testDebugUnitTest
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests: compiled in CI via :app:assembleAndroidTest
    // (execution requires an emulator/device; see docs/TEST_PLAN.md)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The old Concentus port is kept in Git for provenance, not compiled into the APK.
tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    exclude("org/concentus/**")
}


// Stable metadata output for the tag-triggered release workflow's version gate.
tasks.register("printReleaseMetadata") {
    group = "help"
    description = "Print versionName|versionCode for release tag validation."
    doLast {
        println("${android.defaultConfig.versionName}|${android.defaultConfig.versionCode}")
    }
}
