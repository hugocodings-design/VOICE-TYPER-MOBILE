# kotlinx.serialization (settings stored as JSON)
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.hugocodings.voicetyper.core.**$$serializer { *; }
-keepclassmembers class com.hugocodings.voicetyper.core.** { *** Companion; }
-keepclasseswithmembers class com.hugocodings.voicetyper.core.** { kotlinx.serialization.KSerializer serializer(...); }
