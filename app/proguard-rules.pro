# keep defaults
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The Rust core finds these classes and methods by name through JNI.
-keep class app.rclonemgr.ApiException { <init>(int, java.lang.String); }
-keep class app.rclonemgr.AdapterException { <init>(java.lang.String); }
-keep class app.rclonemgr.CoreListener { *; }
-keepclassmembers class * extends app.rclonemgr.CoreListener { *; }
