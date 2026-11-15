//! JNI entry points for `com.zuzuvpn.shadowsocks.SsLocal`.
//!
//! Runs the shadowsocks-rust local client (SOCKS5 inbound on loopback) on a dedicated tokio
//! runtime, blocking the calling Java thread until [`nativeStop`] is called or the client fails.
//!
//! Security notes:
//! * No logger is installed, so shadowsocks-rust's internal `log` output (which can include the
//!   parsed configuration at trace level) is discarded and never reaches logcat.
//! * Error strings returned to Java never contain the configuration text or the password.
//! * Outbound sockets are passed to `VpnService.protect()` through `outbound_vpn_protect_path`,
//!   a Unix socket served by the app (see ProtectServer.kt).

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Duration;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use shadowsocks_service::config::{Config, ConfigType};
use shadowsocks_service::local::socks::config::Socks5AuthConfig;
use tokio::sync::oneshot;

const ENGINE_VERSION: &str = "shadowsocks-service 1.25.0";

/// Stop signal for the instance that is currently running (at most one per process).
static STOP: Mutex<Option<oneshot::Sender<()>>> = Mutex::new(None);

/// Clears [`STOP`] when `run` returns, on every path.
struct RunningGuard;

impl Drop for RunningGuard {
    fn drop(&mut self) {
        if let Ok(mut stop) = STOP.lock() {
            stop.take();
        }
    }
}

fn run(config_json: &str, protect_path: &str, socks_user: &str, socks_password: &str) -> Result<(), String> {
    let mut config = Config::load_from_str(config_json, ConfigType::Local)
        .map_err(|e| format!("invalid configuration ({:?})", e.kind))?;

    config.outbound_vpn_protect_path = Some(PathBuf::from(protect_path));

    // Require RFC 1929 username/password on the loopback SOCKS5 listener, so other apps on the
    // device cannot use it as an open proxy. The credentials are random per session.
    for local in config.local.iter_mut() {
        let mut auth = Socks5AuthConfig::new();
        auth.passwd.add_user(socks_user, socks_password);
        local.config.socks5_auth = auth;
    }

    let (stop_tx, stop_rx) = oneshot::channel::<()>();
    {
        let mut stop = STOP.lock().map_err(|_| "internal lock poisoned".to_string())?;
        if stop.is_some() {
            return Err("already running".into());
        }
        *stop = Some(stop_tx);
    }
    let _guard = RunningGuard;

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("zuzuss-worker")
        .enable_all()
        .build()
        .map_err(|e| format!("runtime: {e}"))?;

    let result = runtime.block_on(async move {
        tokio::select! {
            r = shadowsocks_service::local::run(config) => match r {
                Ok(()) => Err("client exited unexpectedly".to_string()),
                Err(e) => Err(format!("client error: {e}")),
            },
            _ = stop_rx => Ok(()),
        }
    });

    runtime.shutdown_timeout(Duration::from_secs(2));
    result
}

fn java_string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value).map(Into::into).map_err(|_| "invalid string argument".to_string())
}

fn to_jstring(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// `static native String nativeRun(String configJson, String protectPath, String socksUser, String socksPassword)`
///
/// Blocks until stopped. Returns null after a requested stop, otherwise an error description.
#[no_mangle]
pub extern "system" fn Java_com_zuzuvpn_shadowsocks_SsLocal_nativeRun<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    config_json: JString<'local>,
    protect_path: JString<'local>,
    socks_user: JString<'local>,
    socks_password: JString<'local>,
) -> jstring {
    let args = (|| {
        Ok::<_, String>((
            java_string(&mut env, &config_json)?,
            java_string(&mut env, &protect_path)?,
            java_string(&mut env, &socks_user)?,
            java_string(&mut env, &socks_password)?,
        ))
    })();
    let (config, protect, user, password) = match args {
        Ok(a) => a,
        Err(e) => return to_jstring(&mut env, &e),
    };

    let outcome = catch_unwind(AssertUnwindSafe(|| run(&config, &protect, &user, &password)));
    drop(config);
    drop(password);

    match outcome {
        Ok(Ok(())) => std::ptr::null_mut(),
        Ok(Err(e)) => to_jstring(&mut env, &e),
        Err(_) => to_jstring(&mut env, "internal panic"),
    }
}

/// `static native void nativeStop()`: asks a running [`Java_com_zuzuvpn_shadowsocks_SsLocal_nativeRun`] to return.
#[no_mangle]
pub extern "system" fn Java_com_zuzuvpn_shadowsocks_SsLocal_nativeStop<'local>(_env: JNIEnv<'local>, _class: JClass<'local>) {
    if let Ok(mut stop) = STOP.lock() {
        if let Some(tx) = stop.take() {
            let _ = tx.send(());
        }
    }
}

/// `static native String nativeVersion()`
#[no_mangle]
pub extern "system" fn Java_com_zuzuvpn_shadowsocks_SsLocal_nativeVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    to_jstring(&mut env, ENGINE_VERSION)
}
