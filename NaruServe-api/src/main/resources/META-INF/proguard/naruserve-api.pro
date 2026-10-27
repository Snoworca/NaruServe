# Consumer R8/ProGuard rules of NaruServe-api.
# TypeRef reads the generic superclass of its anonymous subclass: keep the Signature attribute and the subclasses.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep,allowobfuscation,allowshrinking class com.snoworca.naruserve.TypeRef
-keep,allowobfuscation,allowshrinking class * extends com.snoworca.naruserve.TypeRef
# The cookie parser (FR-COOKIE-002) reaches the package-private Cookie factory by reflection.
-keepclassmembers class com.snoworca.naruserve.Cookie { static com.snoworca.naruserve.Cookie unvalidated(java.lang.String, java.lang.String); }
# SseWire (FR-SSE-001) reaches the package-private SseEvent.wire by reflection.
-keepclassmembers class com.snoworca.naruserve.SseEvent { java.lang.String wire(); }
# The jspecify nullness annotations are compileOnly: the class files reference them, but no app carries them.
-dontwarn org.jspecify.annotations.**
