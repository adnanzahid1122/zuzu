# Debug test servers (development only)

Debug builds of Zuzu VPN can ship with preloaded **test** servers so the app connects right after
installation, without anyone typing a server into the app. Release builds never contain this.

## Files

| File | Tracked in Git | Purpose |
|---|---|---|
| `test-servers.json` | **No** (ignored by `/.gitignore`) | Real test-server configuration, including the password. |
| `test-servers.example.json` | Yes | Format reference with documentation-only placeholder values. |

## Format

```json
{
  "servers": [
    {
      "id": "test-fr-1",               // stable id; also used to seed at most once
      "name": "France",                // display name
      "country": "France",
      "countryCode": "FR",             // ISO 3166-1 alpha-2; the flag is derived from it
      "city": null,                    // optional
      "protocol": "shadowsocks",
      "recommended": true,
      "premium": false,
      "shadowsocks": {
        "server": "…", "server_port": 443, "local_port": 1080,
        "password": "…", "method": "chacha20-ietf-poly1305", "udp": false
      }
      // instead of "shadowsocks" you may give the server's link: "uri": "ss://…"
    }
  ]
}
```

* `method`: `chacha20-ietf-poly1305` or `2022-blake3-aes-128-gcm` (validated with the engine's rules).
* `local_port` is accepted but ignored: the app always uses a random loopback port protected by
  per-session SOCKS credentials.
* `udp`: set `true` only if the server relays UDP. When `false`, UDP is blocked and DNS is resolved
  by the server over TCP.

## How it works

1. `./gradlew :app:assembleDebug` runs `:app:generateDebugDevTestServers`, which reads
   `test-servers.json`, encrypts it with a fresh random AES-256-GCM key and packages it as a debug
   asset; the key goes into a generated debug-only class. The password is therefore not stored in
   the APK as plaintext. This is obfuscation, not protection: anyone with the debug APK can recover
   the test credentials, so **treat debug APKs as containing them** and do not distribute them.
2. On first launch the debug app decrypts it, validates every server, stores them in the
   Android-Keystore-encrypted server store and selects the best one if no valid server is selected.
3. Each `id` is seeded only once. Servers you later edit or delete under Settings → Developer are
   never overwritten or brought back. To apply changed values, use a new `id` or clear the app's data.

If the file is missing, or the password is still `PASTE_TEST_SERVER_PASSWORD_HERE`, the debug build
has no preloaded server and Gradle prints a warning.
