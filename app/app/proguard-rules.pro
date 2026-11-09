# MOCA release ProGuard 규칙
# 프레임워크 위젯·Pleos SDK 는 자체 consumer 규칙을 가진다.
# 여기에는 우리 코드에서 리플렉션으로 접근하는 것만 둔다.

# Vehicle SDK (AAR consumer 규칙이 있지만 이름 유지가 필요한 공개 API 를 명시)
-keep class ai.pleos.playground.vehicle.** { *; }
-keep interface ai.pleos.playground.vehicle.** { *; }
-keep class ai.pleos.ds.** { *; }

# Android Car (provided by the vehicle at runtime)
-dontwarn android.car.**
-keep class android.car.** { *; }

# org.json 은 플랫폼에 있다
-dontwarn org.json.**

# Kotlin 메타데이터·코루틴
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# 스택 트레이스에서 줄 번호 유지 (크래시 분석용)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
