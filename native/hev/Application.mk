# Zuzu VPN build settings for hev-socks5-tunnel (replaces the upstream Application.mk).
# APP_ABI and APP_PLATFORM are passed on the ndk-build command line by native/build-native.sh.
#
# PKGNAME/CLSNAME select the Java class whose native methods hev's JNI_OnLoad registers:
# com.zuzuvpn.shadowsocks.HevTunnel (see shadowsocks/src/main/kotlin/.../HevTunnel.kt).
APP_OPTIM := release
APP_CFLAGS := -O3 -DPKGNAME=com/zuzuvpn/shadowsocks -DCLSNAME=HevTunnel
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
APP_STRIP_MODE := --strip-unneeded
NDK_TOOLCHAIN_VERSION := clang
