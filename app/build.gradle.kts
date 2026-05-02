plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.burnout"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.burnout"
        // minSdk = 35 (not 36) so Robolectric 4.14.1 can parse the merged manifest in
        // MaskCodecTest. App still runs on Android 16+. Established gotcha — see CLAUDE.md.
        minSdk = 35
        targetSdk = 36
        // Dev-install builds need versionCode > the baked-in /system/app/Burnout APK's
        // versionCode (currently 36 from the Soong build) so `adb install -r` can supersede it.
        versionCode = 101
        versionName = "0.4-per-channel-zones"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // ProcessLifecycleOwner — observe app foreground/background to set sys.burnout.suspend.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    // setprop sys.burnout.suspend uses direct Runtime.exec("su", "-c", ...) in dev builds
    // (BurnoutApp.kt). Magisk prompts the user once; subsequent calls are silent. Phase 6
    // platform signing replaces this with direct SystemProperties.set in the burnout_app
    // sepolicy domain.

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
