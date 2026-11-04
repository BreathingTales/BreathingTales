import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 순수 Kotlin 모듈: Android, Pleos SDK, Media3 의존성을 두지 않는다.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hilt.core) // javax.inject 어노테이션용 (Android 의존성 없음)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
