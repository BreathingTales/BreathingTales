plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Hilt Gradle 플러그인은 :app에만 둔다. 라이브러리 모듈은 hilt-android + compiler로 충분하고,
    // 플러그인의 hiltCollectClasses 태스크가 Android Studio 의존성 수집(ijCollectDependencies)과 충돌한다.
}

android {
    namespace = "com.breathingtales.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        // 에뮬레이터에서 Pleos 어댑터 확인 (src/androidTest)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // AAOS CarUxRestrictionsManager / CarPropertyManager
    useLibrary("android.car")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.exoplayer)

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
