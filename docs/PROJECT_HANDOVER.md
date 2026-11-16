# Zuzu VPN Android: project handover

Audience: a developer or AI coding agent taking over the Android repository with no access to earlier
conversations. Checked against the source on **16 Nov 2026**, updated **17 Nov 2026** after the Stage B/C security
review and the Shadowsocks cipher catalogue (section 16). The **code is the source of truth**; fix this file when
they disagree. Never put real server passwords, tokens or keys in tracked files.

Related: the Control Plane (backend + admin dashboard) is a separate repository,
`/home/hp/projects/zuzu-control-plane`, with its own `docs/PROJECT_HANDOVER.md`. Its contracts used by this app:
`docs/api/app-api-v1.md`, `docs/crypto/config-delivery-v1.md`, `docs/crypto/test-vectors.json`.

---

## 0. Situation at a glance

| | |
|---|---|
| Repository | `/home/hp/projects/wireguard-android`, a fork of the official WireGuard Android repo. The Zuzu app lives in **`:app`** |
| Last commit | `c44350d4 files added` (16 Nov 2026 23:03; the Control Plane directory integration and the review fixes) |
| Uncommitted | **Stage C** (customer sign-in, Keystore device keys, encrypted configuration delivery), see sections 13–15, plus the security-review fixes and the cipher catalogue (section 16). **Do not discard.** |
| Application ID | `com.example.zuzuvpn` (**temporary**, `gradle.properties` `appPackageName`; replace before Play publication). Debug adds `.debug` |
| Version | `appVersionName=0.1.0-dev`, `appVersionCode=1` |
| Min / target / compile SDK | 24 / 36 / 36 |
| ABI | **arm64-v8a only** (the Shadowsocks native build) |
| Working VPN | **Shadowsocks** (tested on a physical phone with a debug test server). WireGuard engine **not wired** |
| Control Plane | Public directory integrated (`https://api.gorabbit.net`). **Stage C (local, uncommitted):** customer sign-in, automatic device enrollment with Keystore keys, signed refresh, encrypted configuration delivery → in-memory Shadowsocks handoff. Interoperability verified against a **local** backend. Production has **not** deployed Stage B, so Zuzu servers cannot be connected through production yet |

---

## 1. What the app is

Zuzu VPN is a consumer VPN app: Home (connect button, status, selected location), Locations (country and server
list from the Control Plane), Settings. The VPN runs in a foreground `VpnService`.

* **Shadowsocks:** `shadowsocks-rust` (`sslocal`, as a JNI library) provides a local SOCKS5 proxy, and
  `hev-socks5-tunnel` turns the TUN interface into SOCKS. Ciphers: exactly the six compiled into the engine: `chacha20-ietf-poly1305`, `aes-256-gcm`, `aes-128-gcm`,
  `2022-blake3-aes-128-gcm`, `2022-blake3-aes-256-gcm`, `2022-blake3-chacha20-poly1305` (matrix: Control Plane
  `docs/shadowsocks-ciphers.md`).
* **WireGuard:** the upstream `:tunnel` module (GoBackend) is bundled but not connected to the Zuzu controller.

The Control Plane supplies the directory (public), and after Stage C the per-device encrypted configurations.

---

## 2. Modules and technology

| Module | Purpose | Notes |
|---|---|---|
| `:app` | **Zuzu VPN app** (Kotlin, Jetpack Compose, Material 3) | namespace `com.example.zuzuvpn`; AGP 9.1.0 built-in Kotlin 2.2.10; Compose BOM 2026.04.01; coroutines 1.10.2; no DI framework (manual `AppContainer`); no networking library (`HttpsURLConnection` + `org.json`) |
| `:shadowsocks` | Shadowsocks engine library `com.zuzuvpn.shadowsocks` | Kotlin: `ShadowsocksProfile`, `ShadowsocksUri`, `EngineConfig` (`SsConfigBuilder`, `HevConfigBuilder`), `SsLocal`, `HevTunnel`, `ProtectServer`. Native `libzuzuss.so` (Rust) + HEV, built by `native/build-native.sh` |
| `:tunnel` | Upstream WireGuard library (GoBackend, config parser, crypto) | unchanged Java packages (JNI names) |
| `:ui` | Upstream WireGuard GUI | kept untouched; **not** a dependency of `:app` |
| `native/` | Native build inputs | `versions.properties`: Rust 1.99.0, shadowsocks-rust 1.25.0 (crate SHA-256 pinned), hev-socks5-tunnel 2.19.3 (SHA-256 pinned), NDK 28.2.13676358, API 24, ABIS=arm64-v8a |
| `zuzu-dev/` | Debug-only test servers | `test-servers.json` (**git-ignored**, real test credentials), `test-servers.example.json`, `README.md`. Encrypted into the **debug** APK at build time (`GenerateDevTestServers` in `app/build.gradle.kts`); never in release |

Gradle: `settings.gradle.kts` includes `:tunnel :ui :app :shadowsocks`; versions in `gradle/libs.versions.toml`.

---

## 3. `:app` source map (`app/src/main/kotlin/com/example/zuzuvpn`)

| Path | Responsibility |
|---|---|
| `ZuzuApplication.kt` | `AppContainer`: `appScope`, `ShadowsocksProfileStore` (debug store), `VpnController`, `ServerSelection`, `DeveloperTools`, **`controlPlane` data source**, `servers` repository |
| `MainActivity.kt` | Compose host, splash, VPN/notification permission launchers |
| `ui/ZuzuApp.kt` | App shell: Home / Locations / Settings (+ Licenses, debug Developer screen) |
| `ui/home/*` | `HomeViewModel` (connect flow: selection, `servers.connectionConfig(id)`, VPN permission, `VpnController.connect`), `ConnectAction` (pure decision), `HomeUiState`, `HomeScreen` |
| `ui/locations/*` | `LocationsViewModel` (directory, search, selection, refresh), `LocationsScreen` (pull-to-refresh, failed/empty states) |
| `ui/settings/*` | Settings and licenses |
| `ui/components/*`, `ui/theme/*` | Design system (navy/electric blue), mascot, connect button, location card |
| `data/servers/ServerModel.kt` | `ServerInfo` (public), `ConnectionConfig` (`Shadowsocks(profile)` / `WireGuard(configText)`, redacting `toString`), `ServerEntry`, `CountryCodes` |
| `data/servers/ServerRepository.kt` | `ServerDirectory` (Loading, NotConfigured, Failed, Available), `DirectoryError`, **`ServerDataSource`** interface (`directory`, `refresh()`, `connectionConfig(serverId)`), `CompositeServerDataSource` (debug: Control Plane + local; config only from the source that lists the id), `ServerRepository`, `ServerCatalog` (grouping, search, ordering), `ServerSelection` (SharedPreferences, id only) |
| `data/controlplane/ControlPlaneApi.kt` | `ControlPlaneEndpoints` (appends `/api/v1` once, HTTPS only), `HttpGetter`, `UrlConnectionHttpGetter` (no redirects, size limit) |
| `data/controlplane/DirectoryParser.kt` | Parses `GET /countries` and `GET /servers` (tolerant per item) |
| `data/controlplane/ControlPlaneServerDataSource.kt` | Public directory source: all pages, countries for ordering and filtering; keeps the last list on failure. **`connectionConfig()` returns null** (until Stage C) |
| `data/ShadowsocksProfileStore.kt`, `ServerListCodec.kt`, `SecretBox.kt` | Debug local server store: whole list encrypted with `KeystoreSecretBox` (AES-256-GCM, non-exportable Keystore key, alias `zuzu.shadowsocks.profile.v1`), written atomically to `noBackupFilesDir/ss_servers.bin` |
| `vpn/ZuzuVpnService.kt` | Single owner of the TUN; one session at a time; foreground notification; `selectedRequest()` for always-on restarts |
| `vpn/VpnController.kt`, `VpnState.kt`, `ConnectionHandoff.kt` | UI-facing controller; state store; **in-memory handoff** of `ConnectionRequest` (Intent carries only a random one-time token) |
| `vpn/ShadowsocksEngine.kt` | Resolves `profile.host` (DNS or IP), starts `sslocal` on a random loopback port with per-session SOCKS credentials, protects sockets, starts HEV, verifies traffic before reporting Connected |
| `vpn/VpnEngine.kt` | `VpnEngine` interface; **`WireGuardEngine` stub** (throws `ProtocolNotAvailable`) |
| `dev/DeveloperTools.kt` | Build-type split: `localDataSource` (debug) / null (release), debug UI |
| `app/src/debug/...` | `LocalServerDataSource`, build-time seeding (`DevServerSeeding.kt`), Developer screen (add, edit, import `ss://`, delete test servers) |
| `app/src/release/...` | No local source, no developer UI |

---

## 4. Data flow today

```
Locations/Home ViewModels ─► ServerRepository ─► (release) ControlPlaneServerDataSource ─► GET /api/v1/servers, /countries
                                               └► (debug) CompositeServerDataSource(ControlPlane, LocalServerDataSource)
Connect ─► ConnectAction.decide ─► servers.connectionConfig(id)
            ├ Control Plane server: null (today) ─► "location not available for connection"
            └ local debug server: ConnectionConfig.Shadowsocks(profile) ─► VPN permission ─► VpnController.connect
                 ─► ConnectionHandoff (memory) ─► ZuzuVpnService ─► ShadowsocksEngine ─► tunnel
```

Selecting a server only stores its id. Connecting requires a non-null `ConnectionConfig`.

---

## 5. Build and test

* **SDK path:** `local.properties` points at the **Windows** SDK (Android Studio on Windows). When building from WSL,
  `sdk.dir` must temporarily point at `~/Android/Sdk` and then be restored. Do not change it permanently. A helper
  that does this and restores it on exit was used (`gw.sh` in the session scratchpad); recreate it if needed.
* **Commands:**
  * `./gradlew :app:assembleDebug :app:assembleRelease`
  * `./gradlew :app:testDebugUnitTest`: AGP 9 does not create `testReleaseUnitTest` by default.
  * `./gradlew :app:lintRelease`
* **Outputs:**
  * `app/build/outputs/apk/debug/app-debug.apk`
  * `app/build/outputs/apk/release/app-release-unsigned.apk`: **unsigned**; no signing config yet.
* **Release:** R8 minify + resource shrinking (`proguard-rules.pro` keeps the WireGuard JNI). `allowBackup=false`.
* **API base URL:** `BuildConfig.API_BASE_URL` from Gradle property `zuzuApiBaseUrl`, default
  `https://api.gorabbit.net`; HTTPS is enforced at build time and at runtime.
* **Debug test servers:** put real test credentials in `zuzu-dev/test-servers.json` (git-ignored). While it still
  contains the placeholder password, the debug APK has no preloaded server.
* **Native:** `:shadowsocks` builds `libzuzuss.so` via `native/build-native.sh` (needs Rust and the NDK).
  The outputs are cached under `build/`.

Tests before Stage C (JVM, `testDebugUnitTest`):
* `ShadowsocksProfileStoreTest` (13), `ConnectActionTest` (7), `ServerCatalogTest` (8),
  `ControlPlaneDirectoryTest` (21), `LiveDirectorySmokeTest` (1, opt-in `ZUZU_LIVE_API=1`),
  `LocalServerDataSourceTest` (7, debug), `ServerFormAndImportTest` (20, debug).
* `:shadowsocks`: `ShadowsocksProfileTest` (33).

---

## 6. Security model (app side)

* **No backend secret in the APK:** only the API URL. Debug test credentials are encrypted into **debug** APKs only.
* **Credentials at rest:** debug store only, AES-256-GCM with a non-exportable Keystore key, in the no-backup
  directory. Nothing secret goes in SharedPreferences (only the selected server id).
* **Logs** record only counts, error kinds and class names. `toString()` of profiles and configs redacts secrets.
* **HTTPS only;** redirects are not followed.
* **VPN:** the per-session SOCKS username and password are random; the loopback port is random; sockets are
  protected; Connected is reported only after a traffic check.

---

## 7. Current status (before Stage C)

| Feature | Status |
|---|---|
| Consumer UI (Home, Locations, Settings) | ✅ |
| Public directory from the Control Plane | ✅ (unit tests, live read-only smoke test) |
| Shadowsocks connection | ✅ with debug test servers (physical device tested earlier) |
| WireGuard connection | ❌ engine stub |
| Customer sign-in, device enrollment | ❌ (Stage C) |
| Encrypted configuration delivery | ❌ (Stage C) |
| Release signing, Play publication | ❌ |
| Multi-ABI | ❌ (arm64-v8a only) |

---

## 12. Work committed before Stage C (`c44350d4`)

* **Modified:**
  * Control Plane directory integration: `app/build.gradle.kts`, `ZuzuApplication.kt`, `ServerModel.kt`,
    `ServerRepository.kt`, `DeveloperTools.kt`, both `BuildDeveloperTools.kt`, Home and Locations UI files, `strings.xml`.
  * Ported legacy tests: `ShadowsocksProfileStoreTest.kt`, `ConnectActionTest.kt`.
* **Deleted (ported, not dropped):** `LocationsTest.kt` → `data/servers/ServerCatalogTest.kt`;
  `DevelopmentLocationRepositoryTest.kt` → `dev/LocalServerDataSourceTest.kt`.
* **New:** `data/controlplane/*` and its tests.
* The `tunnel/tools/*` submodules show local line-ending modifications dated 27 Oct 2026, which are **not** from this work.


---

## 13. Stage C: architecture (customer authentication and encrypted configuration delivery)

Contract sources (Control Plane repo, not modified by Stage C):
* `docs/api/app-api-v1.md`
* `docs/crypto/config-delivery-v1.md`
* `docs/crypto/test-vectors.json`, copied **byte-for-byte** to `app/src/test/resources/config-delivery-test-vectors.json`
  (same SHA-256).

New package `app/src/main/kotlin/com/example/zuzuvpn/delivery/`:

| File | Responsibility |
|---|---|
| `ProtocolV1.kt` | Constants and pure functions of protocol v1: suites (`ECDH_P256` / `RSA3072`, DB id and wire name), `keyId` (base64url SHA-256(SPKI), 22 chars), canonical `ZUZU-ENROLL-V1` / `ZUZU-ROTATE-V1` / `ZUZU-SIG-V1` messages, HKDF info/salt, AAD, HKDF-SHA256 (RFC 5869, JCA HMAC), strict canonical `Base64Url` (java.util.Base64 needs API 26) |
| `DeviceKeys.kt` | `DeviceKeys` (sign, ECDH `agree`, RSA-OAEP `unwrap`) via JCA so that private-key operations run in the Keystore; `JcaDeviceKeys` (shared by Keystore keys and test software keys); `DeviceKeyStore` interface |
| `AndroidKeystoreDeviceKeys.kt` | Generates and loads the two non-exportable Keystore keys. Aliases `zuzu.device.sign.v1` (EC P-256, SIGN, SHA-256) and `zuzu.device.encrypt.v1`: **API ≥ 31** EC P-256 `PURPOSE_AGREE_KEY` (suite A), else, or if generation fails, **RSA-3072 e=65537 OAEP** with digests SHA-1+SHA-256 (suite B). StrongBox tried for EC keys (API 28+), TEE fallback (StrongBox has no RSA-3072). Not bound to user auth (screen-lock changes don't invalidate). Incomplete or unusable key sets are deleted (the server then sees a new device) |
| `ConfigEnvelope.kt` | `ConfigEnvelope` (JSON shape), `EnvelopeOpener.decrypt/open` implementing the §3 verification order, `EnvelopeFailure`, `ConfigPayload.Shadowsocks` (redacted `toString`) → `ShadowsocksProfile` (validated with `ShadowsocksProfileValidator`) |
| `AppApi.kt` | `JsonHttp` + `HttpsJsonClient` (HTTPS only, no redirects, sends the exact signed bytes); `RequestSigner` (`X-Zuzu-Key-Id/-Timestamp/-Nonce/-Signature`, fresh 18-byte nonce); `AppApi` (register, login, google, enroll with proof, signed refresh, signed logout, me, requestConfig); `ApiFailure` (backend `code`), `NetworkFailure` |
| `SessionStorage.kt` | `FileSessionStorage`: deviceId, customerId, email, **refresh token**, sealed with `KeystoreSecretBox("zuzu.session.v1")` in `noBackupFilesDir/session.bin`. `FileEnvelopeCache`: the envelope **as received** (still device-encrypted) + its request nonce, sealed again with `KeystoreSecretBox("zuzu.configcache.v1")` in `noBackupFilesDir/config-cache/` |
| `AccountManager.kt` | Session state (`Loading`/`SignedOut(reason)`/`SignedIn`), sign-in/register/Google → automatic enrollment (re-attach when keys exist), device-limit replacement (credentials kept **in memory only** during the prompt), `authorized{}` (refresh before expiry and once after `UNAUTHORIZED`; refresh single-flight under a mutex), terminal handling, signed sign-out, `refreshNow()` |
| `SecureConfigProvider.kt` | Requests `POST /app/servers/{id}/config` with `protocols:["shadowsocks"]` and a fresh nonce, opens the envelope, caches it **encrypted**, returns `ConfigResolution.Ready(ConnectionConfig.Shadowsocks)`. On `NetworkFailure` only: falls back to a valid cached envelope (always-on restarts) |

Integration with the existing app (no VPN engine or `ZuzuVpnService` change):
* `ServerDataSource.resolveConnection()` (new, default derived from `connectionConfig()`) returns a typed
  `ConfigResolution` (Ready, SignInRequired, SubscriptionRequired, PremiumRequired, SignedOut, Unavailable,
  RateLimited, NetworkError, SecurityError). It **never throws**, because the service's always-on path calls
  `connectionConfig()` outside its try block.
* `ControlPlaneServerDataSource` gets an optional `SecureConfigProvider`. `CompositeServerDataSource` routes to the
  owning source, so debug local test servers keep their own configurations.
* `HomeViewModel` uses `resolveConnection` and maps reasons to new `HomeNotice` values. The decrypted config goes
  `ConnectionRequest` → `ConnectionHandoff` (memory) → `ZuzuVpnService` → `ShadowsocksEngine` (unchanged).
* UI: Settings → **Account** section (sign in / signed-in email / sign out). `ui/account/AccountScreen.kt` +
  `AccountViewModel.kt` (email/password sign-in and registration, device-limit replace dialog, ended-session messages).
* `AppContainer` (`ZuzuApplication.kt`) creates `AppApi`, `AccountManager` (restored at start), the cache and the provider.

Flow:

```
Settings → Account → sign in/register ─► POST /app/auth/login|register ─► enrollment token (jti)
   Keystore keys (load or create; suite by API level) ─► POST /app/devices {SPKIs, suite, ECDSA proof over ZUZU-ENROLL-V1}
   ─► session (refresh token sealed, access token in memory)
Home → Connect (Control Plane server) ─► resolveConnection ─► refresh if needed (signed ZUZU-SIG-V1)
   ─► POST /app/servers/{id}/config {v:1, kid, nonce} ─► EnvelopeOpener (ECDH in Keystore + HKDF, or RSA-OAEP in Keystore;
      AES-256-GCM with AAD from this request's nonce; nonce/exp/kid/device/server checks)
   ─► ShadowsocksProfile in memory ─► existing connect path
```

Terminal answers:
* `DEVICE_REVOKED` / `ACCOUNT_DISABLED`: delete keys, session and cache.
* `DEVICE_KEY_INVALID`: delete keys and session.
* `SESSION_EXPIRED` / `REFRESH_TOKEN_REUSED`: drop tokens, keep keys, so the next sign-in re-attaches.
* `SIGNATURE_REPLAYED` / `SIGNATURE_EXPIRED`: reported, not a sign-out (clock skew shows as a security notice).

Threading and startup (after the security review):
* `AccountManager.restore/signIn/signInWithGoogle/replaceDevice/signOut` and `SecureConfigProvider.resolve` run on
  `Dispatchers.IO` (Keystore key generation, RSA-OAEP/ECDH, file I/O never on the main thread).
* `resolve` waits up to 5 s for session restore to finish (`AccountState` leaves `Loading`), so a Connect tap right
  after launch or an always-on start does not report "sign in" while a valid session is being restored.
* A Keystore error at restore leaves keys and session in place (signed out for this run only); `load()` throws
  instead of deleting the keys.

---

## 14. Stage C: tests and verification (16 Nov 2026)

| Test | Count | Covers |
|---|---|---|
| `delivery/BackendVectorsTest` | 10 | **Mandatory backend vectors**: key ids; all 3 envelopes decrypt to the exact plaintext (ECDH Shadowsocks, ECDH WireGuard, RSA Shadowsocks); Shadowsocks payload to profile (host = `serverAddress`); all 9 negative vectors rejected; canonical messages byte-identical; backend signatures verify and ours verify (DER); base64url strictness; HKDF RFC 5869 vector |
| `delivery/EnvelopeOpenerTest` | 9 | Both suites: valid open; another device's key; tampered ciphertext; tampered metadata (exp, iat, serverId, deviceId); response for another nonce (replay); expiry with 120 s skew; incorrect kid; unsupported version/suite and suite mismatch (no downgrade); malformed fields |
| `delivery/AccountFlowTest` | 17 | Proof of possession at enrollment; untrusted key echo; error codes; device limit and replacement; refresh signed over the exact bytes and path; fresh nonce per signed request; `SIGNATURE_REPLAYED` handling; **revoked device** and **disabled account** wipe the identity; expired session keeps keys; revoked keys get a fresh identity (retry via login, never a second registration); signed sign-out; provider: decrypt → profile, replayed/foreign envelope, 403/404/429 mapping, offline cache until exp, signed-out never fetches |
| `delivery/LocalBackendInteropTest` | 1 (opt-in) | **Real Kotlin client ↔ real Control Plane API** (local, test DB): register + login on two installations (ECDH and RSA), 403 before subscription, encrypted delivery decrypted for both suites, server-accepted signed refresh, admin revocation, then `DEVICE_REVOKED` and keys deleted, account disable. Env: `ZUZU_INTEROP_API`, `ZUZU_INTEROP_ADMIN_EMAIL`, `ZUZU_INTEROP_ADMIN_PASSWORD`. **Never point it at production** |

Confirmed results:
* `:app:testDebugUnitTest`: **114 tests, 0 failures, 2 skipped** (the two opt-in live tests).
* `:shadowsocks:testDebugUnitTest`: **33/33**.
* `LocalBackendInteropTest` against the locally running backend: **passed**. The backend audit log showed
  CUSTOMER_REGISTERED, CUSTOMER_LOGIN, DEVICE_ENROLLED ×2, CONFIG_DENIED ×1, CONFIG_DELIVERED ×3, DEVICE_REVOKED ×2,
  CUSTOMER_UPDATED, and the backend log contained 0 secrets.
* `:app:assembleDebug`, `:app:assembleRelease`: OK. `:app:lintRelease`: only the 3 pre-existing warnings.
* The release APK contains no test vectors, test keys or backend secret names; it contains only the protocol labels and
  Keystore aliases, and the API URL `https://api.gorabbit.net`.

---

## 15. Stage C: limitations and remaining work

**Not verified on physical devices.** Required before real Shadowsocks testing through the Control Plane:

| Device | Expected suite | Check |
|---|---|---|
| ≥1 Android 7–11 (API 24–30) | RSA3072 | Keystore RSA-3072 generation (time), OAEP SHA-1 decrypt, enrollment accepted (SPKI encoding canonical for the backend), refresh, delivery, connect |
| ≥1 Android 12+ (API 31+) | ECDH_P256 | `PURPOSE_AGREE_KEY` generation (StrongBox and TEE), `KeyAgreement("ECDH","AndroidKeyStore")`, enrollment, delivery, connect |

Record device model, Android version and security patch, the suite used, StrongBox yes/no, and the results.

Other items:
1. **Backend Stage B is not deployed** (production `GET /api/v1/app/me` is 404). Until it is, Zuzu servers show "sign in"
   or "not available".
2. **SPKI canonicalization** (backend requires `export(spki) == input`): JCA/Keystore encodings match Node in JVM tests
   and the live interop test (software keys). Keystore-generated keys must be confirmed on devices (row above).
3. **Device clock:** signed requests need the clock within ±120 s (`SIGNATURE_EXPIRED` shows as a security notice).
   No clock-offset handling is specified; not added.
4. **Google Sign-In:** `AppApi.google()` and `AccountManager.signInWithGoogle()` are implemented and use the backend
   contract, but there is **no UI and no Credential Manager dependency** yet. It needs an OAuth client (and
   `GOOGLE_CLIENT_IDS` on the server).
5. **Key rotation** (`POST /app/devices/current/keys`) is not used by the app yet (message builder implemented and
   vector-tested).
6. **WireGuard:** deliveries are decrypted by the same code, but the app requests only `shadowsocks` and does not
   consume WireGuard payloads; the engine is still a stub.
7. The account UI is functional but minimal (no password reset, which needs the backend email feature; no device
   list screen beyond the limit prompt).
8. **Backend observation (not changed):** Swagger warns "Duplicate DTO detected" for `LoginDto` and `ConfigRequestDto`
   (admin vs customer and legacy vs new DTO names), so the generated OpenAPI schema merges them. Rename in the backend
   later.
9. **Offline at cold start:** the server directory is not stored on disk, so an always-on start with no network has no
   server list and fails before the cached (encrypted) configuration can be used.
10. **Revocation does not stop a running tunnel**; the app learns about it at the next refresh or config request.
    `AccountManager.refreshNow()` exists but is not called yet.

---

## 16. Security review and cipher catalogue (17 Nov 2026)

Full report (changes per file, operation, Postman tests, deployment checklist): Control Plane
`docs/security-review-2026-11.md`. Android changes:

| File | Change |
|---|---|
| `delivery/AccountManager.kt` | Sign-in, sign-out, restore on `Dispatchers.IO`; Keystore error at restore keeps the identity |
| `delivery/SecureConfigProvider.kt` | `resolve` on `Dispatchers.IO`; waits ≤5 s for session restore |
| `delivery/AndroidKeystoreDeviceKeys.kt`, `DeviceKeys.kt` | `load()` throws `KeyStoreException` on Keystore errors instead of deleting keys |
| `delivery/AppApi.kt` | `SignInResult` / `DeviceTokens` redact tokens in `toString` |
| `ui/account/AccountViewModel.kt` | Remembered replace-device credentials cleared in `onCleared` |
| `shadowsocks/.../ShadowsocksProfile.kt` | 6 supported methods; key length 16/32/32 for AEAD-2022; identity chains only for AES-2022 (`ProfileProblem.IdentityChainUnsupported`) |
| debug `strings.xml`, `ServerScreens.kt`, `ServerForm.kt` | Developer form texts for the new methods and problem |
| Tests | `AccountFlowTest` +3 (restore wait, Keystore error, redaction); `LocalBackendInteropTest` +1 (all 6 ciphers through the real backend, both suites); `ShadowsocksProfileTest` (38); `ServerFormAndImportTest` uses `aes-256-cfb` as the unsupported example |

Engine evidence: `native/zuzuss/Cargo.toml` enables `aead-cipher` + `aead-cipher-2022` only (shadowsocks-rust 1.25.0);
the built `libzuzuss.so` contains the 6 AEAD names and none of the 14 legacy stream ciphers.

Results (17 Nov 2026):
* `:app:testDebugUnitTest`: **118 tests, 0 failures, 3 skipped** (`LiveDirectorySmokeTest` and the two opt-in
  `LocalBackendInteropTest` tests; the interop tests passed in the previous run against the local backend:
  "INTEROP OK", "CIPHERS OK" for all 6 methods via ECDH and RSA, 0 secrets in the API log).
* `:shadowsocks:testDebugUnitTest`: **38/38**.
* `:app:assembleDebug`, `:app:assembleRelease` (unsigned): OK. `:app:lintRelease`: the 3 pre-existing warnings.
* **Not verified:** a real tunnel per cipher on a physical phone against real servers; ANR behaviour on devices.

`tunnel/tools/elf-cleaner` and `tunnel/tools/wireguard-tools` show as modified only because of CRLF line endings
(no content change); do not commit them.

