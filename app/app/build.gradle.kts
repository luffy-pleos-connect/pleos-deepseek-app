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

    signingConfigs {
        create("release") {
            // 키는 저장소 밖(~/moca-keys)에 있고 값은 로컬 파일에서 읽는다. 저장소·APK에 넣지 않는다.
            val keyDir = File(System.getProperty("user.home"), "moca-keys")
            val store = File(keyDir, "moca-release.jks")
            if (store.exists()) {
                val passFile = File(keyDir, "moca-release.pass")
                val pass = if (passFile.exists()) passFile.readText().trim() else ""
                storeFile = store
                storePassword = pass
                keyAlias = "moca"
                keyPassword = pass
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "SERVER_BASE_URL", "\"http://127.0.0.1:8787\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "SERVER_BASE_URL", "\"https://moca.vercel.app\"")
        }
    }

    // JVM 단위 테스트에서도 org.json 이 동작하도록 실제 구현을 쓴다.
    testOptions {
        unitTests.isReturnDefaultValues = false
        unitTests.all { it.systemProperty("org.json.simple", "true") }
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
    // Android 의 org.json 스텁 대신 실제 JSON 구현을 JVM 테스트에 쓴다.
    testImplementation("org.json:json:20240303")
}
