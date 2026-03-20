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
