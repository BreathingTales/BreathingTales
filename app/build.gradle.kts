import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.breathingtales"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.breathingtales"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // 릴리스 서명: 로컬 keystore.properties가 있을 때만 (git에 없음). 없으면 서명 안 된 APK를 만든다
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 차량 어댑터 교체 (src/debug의 di/VehicleBindingModule): -PfakeVehicle=true 면 Fake
            buildConfigField("boolean", "FAKE_VEHICLE", (providers.gradleProperty("fakeVehicle").orNull == "true").toString())
        }
        release {
            // Fake 코드는 debug 소스에만 있다
            buildConfigField("boolean", "FAKE_VEHICLE", "false")
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    useLibrary("android.car")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// 집계 태스크(hiltCollectClasses)가 Android Studio 의존성 수집(ijCollectDependencies)과 충돌한다.
// Hilt 모듈은 :app에만 있으므로 끄더라도 영향이 없다.
hilt {
    enableAggregatingTask = false
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 02 기술 확인 화면(src/debug/probe)에서 SDK를 직접 호출한다. 본 구현은 :data를 거친다.
    debugImplementation(libs.pleos.vehicle)
    debugImplementation(libs.pleos.tts)
    debugImplementation(libs.pleos.stt)

    testImplementation(libs.junit)
}
