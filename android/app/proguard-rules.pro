# Retrofit interfaces are reflective: keep their generic signatures and annotations.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx.serialization generates a companion .serializer() per @Serializable class and
# looks it up reflectively at the boundary, so those members must survive shrinking.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tzvi.kickoff.**$$serializer { *; }
-keepclassmembers class com.tzvi.kickoff.** {
    *** Companion;
}
-keepclasseswithmembers class com.tzvi.kickoff.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room and Hilt generate code that references these by name.
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# The overlay island builds a ComposeView from a Service and sets the view-tree owners
# reflectively through androidx; keep the owner interfaces intact.
-keep class androidx.lifecycle.** { *; }
