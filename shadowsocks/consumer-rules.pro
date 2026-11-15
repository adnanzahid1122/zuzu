# The native libraries bind to these exact class and method names:
#  - libzuzuss.so exports Java_com_zuzuvpn_shadowsocks_SsLocal_* symbols
#  - libhev-socks5-tunnel.so registers natives on com/zuzuvpn/shadowsocks/HevTunnel in JNI_OnLoad
-keep class com.zuzuvpn.shadowsocks.SsLocal { native <methods>; }
-keep class com.zuzuvpn.shadowsocks.HevTunnel { native <methods>; }
