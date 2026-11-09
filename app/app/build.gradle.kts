plugins {
    id("com.android.application")
}

android {
    namespace = "ai.moca.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.moca.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            buildConfigField("String", "SERVER_BASE_URL", "\"http://10.0.2.2:8787\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "SERVER_BASE_URL", "\"https://moca.vercel.app\"")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AAOS Car API (CarUxRestrictionsManager) — 차량에서 런타임에 제공된다.
    useLibrary("android.car")

    // 점수 엔진은 순수 Kotlin/JVM 모듈(:core)에 있고, JDK 17 만으로 테스트한다.
    // AGP 9 내장 Kotlin 이 이 소스 디렉터리를 그대로 컴파일한다(별도 Gradle 모듈 불필요).
    sourceSets["main"].resources.srcDir("../../core/src/main/resources")
    sourceSets["main"].kotlin.srcDir("../../core/src/main/kotlin")
}

dependencies {
    implementation(project(":pleos-ds"))
    implementation("ai.pleos.playground:Vehicle:2.3.2")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
