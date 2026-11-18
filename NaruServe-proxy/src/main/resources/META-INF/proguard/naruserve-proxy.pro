# Consumer R8/ProGuard rules of NaruServe-proxy.
# The core's default error page recognizes ProxyException by name and prints its causes by class only, so their
# messages, which may name the target, stay out of the response body (FR-PROXY-015 AC-4): keep the name.
-keepnames class com.snoworca.naruserve.proxy.ProxyException
