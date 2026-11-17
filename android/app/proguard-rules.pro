# ---- Meridian Attendance R8 rules ----
# Keep line numbers for readable crash reports (mapping.txt is uploaded with each release).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: keep serializers for our DTOs (generated $serializer classes and @Serializable companions)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class za.co.meridiangroup.attendance.**$$serializer { *; }
-keepclassmembers class za.co.meridiangroup.attendance.** { *** Companion; }
-keepclasseswithmembers class za.co.meridiangroup.attendance.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit (uses reflection on method signatures and generic types)
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room entities are accessed via generated code; keep names of entity fields used in queries
-keep class za.co.meridiangroup.attendance.data.local.db.** { *; }

# WorkManager workers are instantiated by class name
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Firebase messaging service / receivers are referenced from the manifest
-keep class za.co.meridiangroup.attendance.notifications.AttendanceFirebaseMessagingService { *; }
-keep class za.co.meridiangroup.attendance.detection.** extends android.content.BroadcastReceiver { *; }

# Room-generated database implementation is looked up by name (AppDatabase_Impl)
-keep class * extends androidx.room.RoomDatabase { <init>(); }
# Play services geofencing / Firebase ship their own consumer rules; nothing extra needed.
