# MapLibre
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# GraphHopper
-keep class com.graphhopper.** { *; }
-dontwarn com.graphhopper.**
-keep class com.fasterxml.** { *; }
-dontwarn com.fasterxml.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# WorkManager — class name is stored as a String by WorkManager; must not be renamed
-keep class * extends androidx.work.ListenableWorker { *; }
# Keep Hilt-generated worker factories in the work package
-keep class com.streeter.work.** { *; }

# Timber
-dontwarn org.jetbrains.annotations.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.streeter.**$$serializer { *; }
-keepclassmembers class com.streeter.** { *** Companion; }
-keepclasseswithmembers class com.streeter.** { kotlinx.serialization.KSerializer serializer(...); }
