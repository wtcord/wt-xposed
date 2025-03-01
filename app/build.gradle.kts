plugins {
    alias(libs.plugins.devtools.ksp)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.wintry.xposed"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wintry.xposed"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.libunbound)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlin.reflect)

    // compileOnly(libs.aliuhook)
    compileOnly(libs.xposed)

    implementation(libs.yuki.api)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

tasks.register<Exec>("forceStopDiscord") {
    executable = "adb"
    args("shell", "am", "force-stop", "com.discord")
}

tasks.register<Exec>("startDiscord") {
    dependsOn("forceStopDiscord")

    executable = "adb"
    args("shell", "am", "start", "-n", "com.discord/.main.MainActivity")
}

tasks.whenTaskAdded {
    finalizedBy("startDiscord")
}
