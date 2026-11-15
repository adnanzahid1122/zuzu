# Zuzu VPN release shrinking rules.

# The WireGuard JNI glue (tunnel/tools/libwg-go/jni.c) binds by fully-qualified class and method name:
#   Java_com_wireguard_android_backend_GoBackend_wgTurnOn / wgTurnOff / wgGetSocketV4 /
#   wgGetSocketV6 / wgGetConfig / wgVersion
# The class name and its native methods must never be renamed or removed.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.wireguard.android.backend.GoBackend {
    native <methods>;
}

# GoBackend$VpnService is referenced from the merged manifest, so AAPT keeps it automatically.
