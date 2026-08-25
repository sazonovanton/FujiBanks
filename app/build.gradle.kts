import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The signing key lives outside the repository and its passwords are not in
// version control. Absent, the release variant simply builds unsigned rather
// than failing: a clone has to be able to build without the author's key.
val keystoreProps = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "org.nemo.fujibanks"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.nemo.fujibanks"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        keystoreProps?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
                // v3 carries a proof-of-rotation record, so the signing key can
                // be replaced later without orphaning installed copies. Without
                // it the first key is the only key this app can ever have.
                //
                // v1 stays off: it is JAR signing, needed only below API 24,
                // and minSdk here is 26. v4 is for incremental `adb install`
                // and writes a side-car .idsig that a release APK has no use
                // for.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Deliberately off, and not an oversight. R8 over Compose plus
            // kotlinx.serialization needs keep rules that nothing here has ever
            // exercised, and the release APK exists to be installed and used —
            // an untested shrink pass risks shipping a build that fails where
            // the debug one worked. Turn it on only with a run on the camera
            // behind it.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

// The APK gets carried around by hand, attached to a release and installed over
// adb, so it is named after the app and its version rather than "app-debug.apk",
// which says nothing once it is sitting in a downloads folder next to other
// builds. The variant is part of the name because without it a release build
// silently overwrote the debug APK of the same version, and the two are signed
// with different keys.
//
// `VariantOutputImpl` is an internal AGP class; if this stops compiling after a
// plugin upgrade, that is why, and losing the custom name costs nothing.
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set(
                    "FujiBanks-v${android.defaultConfig.versionName}-${variant.name}.apk"
                )
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
