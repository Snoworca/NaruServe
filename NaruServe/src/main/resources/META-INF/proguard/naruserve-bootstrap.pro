# Consumer R8/ProGuard rules of the NaruServe bootstrap module.
# RestApp.create() finds DefaultAppFactory through ServiceLoader, which instantiates it by name; R8 sees no
# instantiation, so create() must be kept too or the instantiated provider has no create() at run time.
-keep class com.snoworca.naruserve.bootstrap.DefaultAppFactory { public <init>(); public com.snoworca.naruserve.RestApp create(); }
-keepnames class com.snoworca.naruserve.spi.AppFactory
# The Logcat logger of NaruServe-logcat (brought by NaruServe-android) is created by name on Android.
-keep class com.snoworca.naruserve.logcat.LogcatLogger { public <init>(); public <methods>; }
