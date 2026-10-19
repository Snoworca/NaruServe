# Consumer R8/ProGuard rules of the NaruServe bootstrap module.
# RestApp.create() finds DefaultAppFactory through ServiceLoader, which instantiates it by name.
-keep class com.snoworca.naruserve.bootstrap.DefaultAppFactory { public <init>(); }
-keepnames class com.snoworca.naruserve.spi.AppFactory
