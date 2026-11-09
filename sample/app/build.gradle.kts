plugins {
    id("com.android.application")
}

android {
    namespace = "ai.moca.g0"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.moca.g0"
        // Pleos: 차량 보안(서명 키 rotation / lineage) 때문에 minSdk 28 이상 필수
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // AAOS Car API (CarUxRestrictionsManager). 차량에서 런타임에 제공됩니다.
    useLibrary("android.car")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // config/versions.env 의 PLEOS_VEHICLE_SDK_VERSION 과 맞춰 주세요.
    implementation("ai.pleos.playground:Vehicle:2.3.2")
}
