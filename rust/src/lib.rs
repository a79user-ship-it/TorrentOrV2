use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jlong, jstring};
use jni::EnvUnowned;
use librqbit::api::TorrentIdOrHash;
use librqbit::dht::DhtPersistenceConfig;
use librqbit::{
    AddTorrent, AddTorrentOptions, AddTorrentResponse, Api, DhtSessionConfig, ListenerOptions,
    ManagedTorrent, Session, SessionOptions, SessionPersistenceConfig, generate_azereus_style,
};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::num::NonZeroU32;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::OnceLock;
use std::time::Duration;
use tokio::runtime::Runtime;
use tracing_subscriber::EnvFilter;

struct Engine {
    runtime: Runtime,
    session: Arc<Session>,
    api: Api,
    torrents: Mutex<Vec<Arc<ManagedTorrent>>>,
    pending_adds: Mutex<HashMap<String, Vec<u8>>>,
    next_token: Mutex<u64>,
    // All-time (cumulative, persists across deletes and app restarts) download/upload
    // totals. librqbit only tracks live per-torrent counters that vanish when a torrent
    // is deleted or the app restarts, so we maintain our own running total: each poll we
    // add the delta since the last poll for every currently-managed torrent, and persist
    // the running total to a small file on disk.
    all_time_stats: Mutex<(u64, u64)>,
    all_time_snapshot: Mutex<HashMap<String, (u64, u64)>>,
    all_time_path: PathBuf,
    // What DHT/LSD were actually configured as for this running session (set once, at
    // init, same as what was passed to SessionOptions) — reported back to the UI's
    // Network Features screen so it shows the real state, not just the saved preference.
    dht_enabled: bool,
    lsd_enabled: bool,
}

static ENGINE: OnceLock<Engine> = OnceLock::new();
static LOGGING_INIT: OnceLock<()> = OnceLock::new();
static TLS_INIT: OnceLock<()> = OnceLock::new();

fn init_logging() {
    LOGGING_INIT.get_or_init(|| {
        let writer = paranoid_android::AndroidLogMakeWriter::new("TorrentorEngine".to_string());

        tracing_subscriber::fmt()
            .with_writer(writer)
            .with_env_filter(EnvFilter::new(
                "info,librqbit=debug,librqbit_dht=debug,librqbit_tracker_comms=debug,librqbit_utp=debug,librqbit_lsd=debug,reqwest=debug",
            ))
            .with_ansi(false)
            .init();
    });
}

fn format_bytes(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut size = bytes as f64;
    let mut unit_index = 0;

    while size >= 1024.0 && unit_index < UNITS.len() - 1 {
        size /= 1024.0;
        unit_index += 1;
    }

    if unit_index == 0 {
        format!("{} {}", bytes, UNITS[unit_index])
    } else {
        format!("{:.2} {}", size, UNITS[unit_index])
    }
}

fn sanitize_field(s: String) -> String {
    s.replace('|', " ").replace('\n', " ").replace('\r', " ")
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_initEngine<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    download_dir: JString<'local>,
    context: JObject<'local>,
    dht_enabled: jboolean,
    lsd_enabled: jboolean,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            init_logging();

            let mut tls_result = Ok(());
            TLS_INIT.get_or_init(|| {
                tls_result = rustls_platform_verifier::android::init_with_env(env, context);
            });
            if let Err(e) = tls_result {
                let output = env.new_string(format!("ERROR (TLS init): {e}"))?;
                return Ok(output.into_raw());
            }

            let dir_str: String = download_dir.mutf8_chars(env)?.into();

            let message = if ENGINE.get().is_some() {
                "ALREADY INITIALIZED".to_string()
            } else {
                match init_engine_blocking(dir_str, dht_enabled, lsd_enabled) {
                    Ok(restored_count) => {
                        let dht_text = if dht_enabled { "DHT on" } else { "DHT off" };
                        let lsd_text = if lsd_enabled { "LSD on" } else { "LSD off" };
                        if restored_count > 0 {
                            format!(
                                "CONNECTED (session started, {dht_text}, {lsd_text}, seeding enabled, {restored_count} torrent(s) restored from persistence)"
                            )
                        } else {
                            format!("CONNECTED (session started, {dht_text}, {lsd_text}, seeding enabled)")
                        }
                    }
                    Err(e) => format!("ERROR: {e}"),
                }
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn init_engine_blocking(dir: String, dht_enabled: bool, lsd_enabled: bool) -> anyhow::Result<usize> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;

    let all_time_path = PathBuf::from(&dir).join("all_time_stats.txt");
    let all_time_stats = load_all_time_stats(&all_time_path);

    let session = runtime.block_on(async {
        let download_dir = PathBuf::from(dir);
        std::fs::create_dir_all(&download_dir)?;

        let dht_json_path = download_dir.join("dht.json");
        let persistence_folder = download_dir.join("session");

        let dht_bootstrap_nodes = vec![
            "router.bittorrent.com:6881".to_string(),
            "router.utorrent.com:6881".to_string(),
            "dht.transmissionbt.com:6881".to_string(),
            "dht.aelitis.com:6881".to_string(),
            "dht.libtorrent.org:25401".to_string(),
        ];

        // DHT and LSD (local service discovery, multicast-based LAN peer discovery) are
        // both real librqbit-supported toggles — but both are read only once, here, at
        // Session::new_with_opts time. librqbit exposes no runtime enable/disable method,
        // so flipping the switch in the app takes effect the next time the engine starts
        // (i.e. after fully restarting the app), not instantly.
        let opts = SessionOptions {
            // Identify this client to trackers and peers as "v2TorrentOr" rather than
            // inheriting librqbit's own "-rQ...-" identity:
            //
            // 1. client_name_and_version becomes the User-Agent header on every HTTP(S)
            //    tracker announce/scrape request, and is also sent in the BEP 10 extended
            //    handshake's "v" field, which many peers (and most swarm-stats tools) show
            //    verbatim as the client's display name — this is the one trackers/peers
            //    actually render as free text, so it's the only place the full name
            //    "v2TorrentOr" can appear unabbreviated.
            // 2. peer_id is the 20-byte BEP 20 peer id sent in the handshake and to
            //    trackers. Its format is fixed (Azureus-style: "-" + 2-letter client code +
            //    4-digit version + "-" + 12 random bytes), so there's no room to spell out
            //    the full name here — but using our own "TO" code (instead of librqbit's
            //    "rQ") means peer-list UIs that fall back to showing the raw id for an
            //    unrecognized code will surface "-TO0100-...", not "-rQ...-", and it stops
            //    us from being counted as/confused with vanilla rqbit in swarm stats.
            client_name_and_version: Some("v2TorrentOr/0.1.0".to_string()),
            peer_id: Some(generate_azereus_style(*b"TO", (0, 1, 0, 0))),
            dht: if dht_enabled {
                Some(DhtSessionConfig {
                    bootstrap_addrs: Some(dht_bootstrap_nodes),
                    persistence: Some(DhtPersistenceConfig {
                        config_filename: Some(dht_json_path),
                        ..Default::default()
                    }),
                    ..Default::default()
                })
            } else {
                None
            },
            disable_local_service_discovery: !lsd_enabled,
            persistence: Some(SessionPersistenceConfig::Json {
                folder: Some(persistence_folder),
            }),
            // Two things needed for seeding (peers connecting IN to us), neither of
            // which librqbit does by default:
            //
            // 1. enable_upnp_port_forwarding defaults to false, so the listening port
            //    is never opened on the router automatically. Most home WiFi routers
            //    support UPnP, so this asks the router to forward it for us. This is
            //    "best effort" — UPnP discovery can be flaky/slow/unsupported on some
            //    routers, and it never helps on cellular/carrier data (no router to ask).
            // 2. listen_addr defaults to a RANDOM port each time the engine starts
            //    (port 0 = "pick any free port"), which makes it impossible to forward
            //    manually in the router's admin panel since the port keeps changing.
            //    Pinning it to a fixed port lets you forward it once, permanently, as
            //    a reliable fallback for whenever UPnP doesn't cooperate.
            listen: Some(ListenerOptions {
                listen_addr: SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 51413),
                enable_upnp_port_forwarding: true,
                ..Default::default()
            }),
            ..Default::default()
        };

        Session::new_with_opts(download_dir, opts).await
    })?;

    let restored: Vec<Arc<ManagedTorrent>> =
        session.with_torrents(|torrents| torrents.map(|(_, t)| t.clone()).collect());
    let restored_count = restored.len();

    let api = Api::new(session.clone(), None);

    ENGINE
        .set(Engine {
            runtime,
            session,
            api,
            torrents: Mutex::new(restored),
            pending_adds: Mutex::new(HashMap::new()),
            next_token: Mutex::new(0),
            all_time_stats: Mutex::new(all_time_stats),
            all_time_snapshot: Mutex::new(HashMap::new()),
            all_time_path,
            dht_enabled,
            lsd_enabled,
        })
        .map_err(|_| anyhow::anyhow!("engine already initialized"))?;

    Ok(restored_count)
}

/// Reads "downloaded|uploaded" from disk. Missing/unreadable/malformed file just means
/// no history yet (fresh install, or first run before this feature existed) — start at 0.
fn load_all_time_stats(path: &std::path::Path) -> (u64, u64) {
    let Ok(contents) = std::fs::read_to_string(path) else {
        return (0, 0);
    };

    let mut parts = contents.trim().split('|');
    let downloaded = parts.next().and_then(|s| s.parse::<u64>().ok()).unwrap_or(0);
    let uploaded = parts.next().and_then(|s| s.parse::<u64>().ok()).unwrap_or(0);

    (downloaded, uploaded)
}

/// Adds each currently-managed torrent's progress since the last time this ran to the
/// all-time running total, then persists the total to disk. Best-effort: a write failure
/// here shouldn't take down anything else, so errors are swallowed.
fn accumulate_all_time_stats(engine: &Engine) {
    let torrents = match engine.torrents.lock() {
        Ok(g) => g,
        Err(_) => return,
    };

    let mut snapshot = match engine.all_time_snapshot.lock() {
        Ok(g) => g,
        Err(_) => return,
    };

    let mut total_delta_down: u64 = 0;
    let mut total_delta_up: u64 = 0;

    for handle in torrents.iter() {
        let hash = handle.shared().info_hash.as_string();
        let stats = handle.stats();
        let current = (stats.progress_bytes, stats.uploaded_bytes);

        let prev = snapshot.get(&hash).copied().unwrap_or((0, 0));
        let delta_down = current.0.saturating_sub(prev.0);
        let delta_up = current.1.saturating_sub(prev.1);

        total_delta_down += delta_down;
        total_delta_up += delta_up;

        snapshot.insert(hash, current);
    }

    drop(torrents);
    drop(snapshot);

    if total_delta_down == 0 && total_delta_up == 0 {
        return;
    }

    if let Ok(mut all_time) = engine.all_time_stats.lock() {
        all_time.0 += total_delta_down;
        all_time.1 += total_delta_up;

        let contents = format!("{}|{}", all_time.0, all_time.1);
        let _ = std::fs::write(&engine.all_time_path, contents);
    }
}

fn parse_output_folder(s: String) -> Option<String> {
    let trimmed = s.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_string())
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_addMagnet<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    magnet_uri: JString<'local>,
    output_folder: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let magnet: String = magnet_uri.mutf8_chars(env)?.into();
            let folder: String = output_folder.mutf8_chars(env)?.into();

            let message = match add_magnet_blocking(magnet, parse_output_folder(folder)) {
                Ok((name, hash)) => format!("METADATA RESOLVED: {name} (hash: {hash})"),
                Err(e) => format!("MAGNET ERROR: {e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn add_magnet_blocking(magnet: String, output_folder: Option<String>) -> anyhow::Result<(String, String)> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    engine.runtime.block_on(async {
        let opts = AddTorrentOptions {
            overwrite: true,
            output_folder,
            ..Default::default()
        };

        let handle = engine
            .session
            .add_torrent(AddTorrent::from_url(magnet), Some(opts))
            .await?
            .into_handle()
            .ok_or_else(|| anyhow::anyhow!("torrent add returned no handle"))?;

        tokio::time::timeout(Duration::from_secs(30), handle.wait_until_initialized())
            .await
            .map_err(|_| anyhow::anyhow!("timed out waiting for magnet metadata (30s)"))??;

        let name = handle.name().unwrap_or_else(|| "<unknown name>".to_string());
        let hash = handle.shared().info_hash.as_string();

        add_to_torrent_list(engine, handle);

        Ok((name, hash))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_addTorrentFile<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    torrent_bytes: JByteArray<'local>,
    output_folder: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let bytes: Vec<u8> = env.convert_byte_array(&torrent_bytes)?;
            let folder: String = output_folder.mutf8_chars(env)?.into();

            let message = match add_torrent_file_blocking(bytes, parse_output_folder(folder)) {
                Ok((name, hash)) => format!("METADATA RESOLVED: {name} (hash: {hash})"),
                Err(e) => format!("TORRENT FILE ERROR: {e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn add_torrent_file_blocking(bytes: Vec<u8>, output_folder: Option<String>) -> anyhow::Result<(String, String)> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    engine.runtime.block_on(async {
        let opts = AddTorrentOptions {
            overwrite: true,
            output_folder,
            ..Default::default()
        };

        let handle = engine
            .session
            .add_torrent(AddTorrent::TorrentFileBytes(bytes.into()), Some(opts))
            .await?
            .into_handle()
            .ok_or_else(|| anyhow::anyhow!("torrent add returned no handle"))?;

        tokio::time::timeout(Duration::from_secs(30), handle.wait_until_initialized())
            .await
            .map_err(|_| anyhow::anyhow!("timed out waiting for torrent metadata (30s)"))??;

        let name = handle.name().unwrap_or_else(|| "<unknown name>".to_string());
        let hash = handle.shared().info_hash.as_string();

        add_to_torrent_list(engine, handle);

        Ok((name, hash))
    })
}

fn add_to_torrent_list(engine: &Engine, handle: Arc<ManagedTorrent>) {
    if let Ok(mut guard) = engine.torrents.lock() {
        let already_present = guard
            .iter()
            .any(|t| t.shared().info_hash == handle.shared().info_hash);
        if !already_present {
            guard.push(handle);
        }
    }
}

fn find_torrent_by_hash(engine: &Engine, hash: &str) -> anyhow::Result<Arc<ManagedTorrent>> {
    let guard = engine
        .torrents
        .lock()
        .map_err(|_| anyhow::anyhow!("torrent lock poisoned"))?;

    guard
        .iter()
        .find(|t| t.shared().info_hash.as_string().eq_ignore_ascii_case(hash))
        .cloned()
        .ok_or_else(|| anyhow::anyhow!("no torrent found with that hash"))
}

// ---- File-selection flow: fetch metadata/file list without committing to a download ----

fn list_only_add(
    add: AddTorrent<'static>,
) -> anyhow::Result<(String, Option<String>, Vec<(usize, String, u64)>)> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    engine.runtime.block_on(async {
        let opts = AddTorrentOptions {
            list_only: true,
            ..Default::default()
        };

        let response = tokio::time::timeout(
            Duration::from_secs(30),
            engine.session.add_torrent(add, Some(opts)),
        )
        .await
        .map_err(|_| anyhow::anyhow!("timed out fetching file list (30s)"))??;

        let list_only = match response {
            AddTorrentResponse::ListOnly(l) => l,
            AddTorrentResponse::AlreadyManaged(_, _) => {
                anyhow::bail!("this torrent is already added")
            }
            AddTorrentResponse::Added(_, _) => {
                anyhow::bail!("bug: torrent was added when only listing was requested")
            }
        };

        let files: Vec<(usize, String, u64)> = list_only
            .info
            .iter_file_details()
            .enumerate()
            .map(|(i, fd)| (i, fd.filename.to_string(), fd.len))
            .collect();

        let bytes = list_only.torrent_bytes.to_vec();

        // The "comment" field (like "created by"/"creation date") lives in the *top-level*
        // .torrent dict, a sibling of "info" — not inside list_only.info (which is only the
        // "info" sub-dict: files/pieces/etc). librqbit's runtime APIs never surface it for an
        // already-added torrent, so this is the only point where it's available at all: right
        // here, freshly parsed from the raw .torrent bytes we already have. Re-parsing with
        // librqbit::torrent_from_bytes (re-exported from librqbit_core) is cheap (no I/O, no
        // hashing beyond what already happened above) and gives us the full top-level dict.
        // A magnet link never carries a comment (peers only exchange the "info" dict over
        // BEP9), so this is almost always None for magnet adds — that's expected, not a bug.
        let comment = librqbit::torrent_from_bytes(&bytes)
            .ok()
            .and_then(|meta| meta.comment)
            .map(|c| sanitize_field(String::from_utf8_lossy(c.as_ref()).to_string()))
            .filter(|c| !c.trim().is_empty());

        let token = {
            let mut counter = engine
                .next_token
                .lock()
                .map_err(|_| anyhow::anyhow!("token lock poisoned"))?;
            *counter += 1;
            format!("t{}", *counter)
        };

        engine
            .pending_adds
            .lock()
            .map_err(|_| anyhow::anyhow!("pending lock poisoned"))?
            .insert(token.clone(), bytes);

        Ok((token, comment, files))
    })
}

fn format_file_list_response(
    token: &str,
    comment: &Option<String>,
    files: &[(usize, String, u64)],
) -> String {
    let mut out = format!("OK|{token}");
    out.push_str(&format!("\nCOMMENT|{}", comment.as_deref().unwrap_or("")));
    for (i, path, size) in files {
        out.push_str(&format!("\n{}|{}|{}", i, sanitize_field(path.clone()), size));
    }
    out
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_listOnlyAddMagnet<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    magnet_uri: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let magnet: String = magnet_uri.mutf8_chars(env)?.into();

            let message = match list_only_add(AddTorrent::from_url(magnet)) {
                Ok((token, comment, files)) => format_file_list_response(&token, &comment, &files),
                Err(e) => format!("ERROR|{e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_listOnlyAddTorrentFile<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    torrent_bytes: JByteArray<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let bytes: Vec<u8> = env.convert_byte_array(&torrent_bytes)?;

            let message = match list_only_add(AddTorrent::TorrentFileBytes(bytes.into())) {
                Ok((token, comment, files)) => format_file_list_response(&token, &comment, &files),
                Err(e) => format!("ERROR|{e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_confirmAdd<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    token: JString<'local>,
    only_files_csv: JString<'local>,
    output_folder: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let token_str: String = token.mutf8_chars(env)?.into();
            let csv: String = only_files_csv.mutf8_chars(env)?.into();
            let folder: String = output_folder.mutf8_chars(env)?.into();

            let only_files = if csv.trim().is_empty() {
                None
            } else {
                Some(
                    csv.split(',')
                        .filter_map(|s| s.trim().parse::<usize>().ok())
                        .collect::<Vec<usize>>(),
                )
            };

            let message = match confirm_add_blocking(&token_str, only_files, parse_output_folder(folder)) {
                Ok((name, hash)) => format!("METADATA RESOLVED: {name} (hash: {hash})"),
                Err(e) => format!("ADD ERROR: {e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn confirm_add_blocking(
    token: &str,
    only_files: Option<Vec<usize>>,
    output_folder: Option<String>,
) -> anyhow::Result<(String, String)> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    let bytes = engine
        .pending_adds
        .lock()
        .map_err(|_| anyhow::anyhow!("pending lock poisoned"))?
        .remove(token)
        .ok_or_else(|| anyhow::anyhow!("no pending add found for this selection (it may have expired)"))?;

    engine.runtime.block_on(async {
        let opts = AddTorrentOptions {
            overwrite: true,
            only_files,
            output_folder,
            ..Default::default()
        };

        let handle = engine
            .session
            .add_torrent(AddTorrent::TorrentFileBytes(bytes.into()), Some(opts))
            .await?
            .into_handle()
            .ok_or_else(|| anyhow::anyhow!("torrent add returned no handle"))?;

        tokio::time::timeout(Duration::from_secs(30), handle.wait_until_initialized())
            .await
            .map_err(|_| anyhow::anyhow!("timed out initializing torrent (30s)"))??;

        let name = handle.name().unwrap_or_else(|| "<unknown name>".to_string());
        let hash = handle.shared().info_hash.as_string();

        add_to_torrent_list(engine, handle);

        Ok((name, hash))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_cancelPendingAdd<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    token: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let token_str: String = token.mutf8_chars(env)?.into();

            if let Some(engine) = ENGINE.get() {
                if let Ok(mut pending) = engine.pending_adds.lock() {
                    pending.remove(&token_str);
                }
            }

            let output = env.new_string("OK".to_string())?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_updateOnlyFiles<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    hash: JString<'local>,
    only_files_csv: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let hash_str: String = hash.mutf8_chars(env)?.into();
            let csv: String = only_files_csv.mutf8_chars(env)?.into();

            let message = match update_only_files_blocking(&hash_str, &csv) {
                Ok(()) => "OK".to_string(),
                Err(e) => format!("ERROR: {e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn update_only_files_blocking(hash: &str, csv: &str) -> anyhow::Result<()> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    let idx = TorrentIdOrHash::try_from(hash)?;

    let only_files: std::collections::HashSet<usize> = csv
        .split(',')
        .filter_map(|s| s.trim().parse::<usize>().ok())
        .collect();

    engine.runtime.block_on(async {
        engine
            .api
            .api_torrent_action_update_only_files(idx, &only_files)
            .await
            .map_err(|e| anyhow::anyhow!("{e}"))?;

        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_getTorrentInfo<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let message = get_all_torrents_info();
            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_getGlobalStats<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let message = get_global_stats();
            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// "downloadedBytes|uploadedBytes" — the all-time cumulative totals, persisted across
/// torrent deletes and app restarts. "0|0" if the engine hasn't initialized yet.
fn get_global_stats() -> String {
    let Some(engine) = ENGINE.get() else {
        return "0|0".to_string();
    };

    accumulate_all_time_stats(engine);

    match engine.all_time_stats.lock() {
        Ok(g) => format!("{}|{}", g.0, g.1),
        Err(_) => "0|0".to_string(),
    }
}

// ---- Global speed limits ----
//
// librqbit exposes this as Session.ratelimits (a public `Limits` field backed by the
// `governor` crate's token-bucket rate limiter), adjustable at any time with no engine
// restart needed — unlike DHT/LSD, this is a genuine runtime toggle, not just an
// at-init-time SessionOptions setting. 0 (or negative) means "no limit" for that direction,
// matching how the Kotlin side treats an empty/zero field.

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_setSpeedLimits<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    download_bps: jlong,
    upload_bps: jlong,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let message = set_speed_limits(download_bps, upload_bps);
            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn set_speed_limits(download_bps: jlong, upload_bps: jlong) -> String {
    let Some(engine) = ENGINE.get() else {
        return "ERROR: engine not initialized".to_string();
    };

    let down = if download_bps > 0 {
        NonZeroU32::new(download_bps.min(u32::MAX as jlong) as u32)
    } else {
        None
    };
    let up = if upload_bps > 0 {
        NonZeroU32::new(upload_bps.min(u32::MAX as jlong) as u32)
    } else {
        None
    };

    engine.session.ratelimits.set_download_bps(down);
    engine.session.ratelimits.set_upload_bps(up);

    "OK".to_string()
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_getSpeedLimits<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let message = get_speed_limits();
            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// "downloadBps|uploadBps", 0 meaning unlimited for that direction. "0|0" (engine not
/// initialized) is indistinguishable from "both unlimited" by design — both mean the UI
/// should show blank/unlimited fields either way.
fn get_speed_limits() -> String {
    let Some(engine) = ENGINE.get() else {
        return "0|0".to_string();
    };

    let down = engine
        .session
        .ratelimits
        .get_download_bps()
        .map(|v| v.get())
        .unwrap_or(0);
    let up = engine
        .session
        .ratelimits
        .get_upload_bps()
        .map(|v| v.get())
        .unwrap_or(0);

    format!("{down}|{up}")
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_getNetworkStatus<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let message = get_network_status();
            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// "OK|dhtEnabled(0/1)|dhtNodes|lsdEnabled(0/1)". dhtNodes is the real, live DHT routing
/// table size (IPv4 + IPv6 combined) from librqbit's own Session::get_dht()/Dht::stats() —
/// not estimated. There's no equivalent live counter for LSD (librqbit doesn't expose one)
/// and no PEX field at all anywhere in librqbit's public API, so PEX is handled entirely
/// client-side in the UI as "not supported by this engine" rather than faked here.
fn get_network_status() -> String {
    let Some(engine) = ENGINE.get() else {
        return "ERROR|engine not initialized".to_string();
    };

    let dht_nodes = engine
        .session
        .get_dht()
        .map(|dht| {
            let stats = dht.stats();
            stats.routing_table_size + stats.routing_table_size_v6
        })
        .unwrap_or(0);

    format!(
        "OK|{}|{}|{}",
        if engine.dht_enabled { 1 } else { 0 },
        dht_nodes,
        if engine.lsd_enabled { 1 } else { 0 }
    )
}

/// One line per torrent, pipe-delimited, fixed field order:
/// hash|name|status|percent|downloadedBytes|totalBytes|uploadedBytes|downSpeedBytes|upSpeedBytes|eta|connPeers|knownPeers
fn get_all_torrents_info() -> String {
    let Some(engine) = ENGINE.get() else {
        return String::new();
    };

    accumulate_all_time_stats(engine);

    let guard = match engine.torrents.lock() {
        Ok(g) => g,
        Err(_) => return String::new(),
    };

    guard
        .iter()
        .map(|handle| format_single_torrent_line(handle))
        .collect::<Vec<_>>()
        .join("\n")
}

fn format_single_torrent_line(handle: &Arc<ManagedTorrent>) -> String {
    let name = handle.name().unwrap_or_else(|| "<unknown name>".to_string());
    let hash = handle.shared().info_hash.as_string();
    let stats = handle.stats();

    let percent = if stats.total_bytes > 0 {
        (stats.progress_bytes as f64 / stats.total_bytes as f64) * 100.0
    } else {
        0.0
    };

    let (down_speed, up_speed, eta, conn_peers, known_peers) = if let Some(live) = &stats.live {
        let peers = &live.snapshot.peer_stats;
        let connected = (peers.live_tcp + peers.live_utp + peers.live_socks) as u64;
        let eta_text = live
            .time_remaining
            .as_ref()
            .map(|t| t.to_string())
            .unwrap_or_default();

        (
            live.download_speed.as_bytes() as u64,
            live.upload_speed.as_bytes() as u64,
            eta_text,
            connected,
            peers.seen as u64,
        )
    } else {
        (0u64, 0u64, String::new(), 0u64, 0u64)
    };

    format!(
        "{}|{}|{}|{:.1}|{}|{}|{}|{}|{}|{}|{}|{}",
        hash,
        sanitize_field(name),
        stats.state,
        percent,
        stats.progress_bytes,
        stats.total_bytes,
        stats.uploaded_bytes,
        down_speed,
        up_speed,
        sanitize_field(eta),
        conn_peers,
        known_peers
    )
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_getTorrentPieces<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    hash: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let hash_str: String = hash.mutf8_chars(env)?.into();

            let message = match get_torrent_pieces_blocking(&hash_str) {
                Ok(s) => s,
                Err(e) => format!("ERROR: {e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn get_torrent_pieces_blocking(hash: &str) -> anyhow::Result<String> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    let idx = TorrentIdOrHash::try_from(hash)?;
    let (bf, len) = engine
        .api
        .api_dump_haves(idx)
        .map_err(|e| anyhow::anyhow!("{e}"))?;

    let len = len as usize;

    let bitstring: String = bf
        .iter()
        .by_vals()
        .take(len)
        .map(|b| if b { '1' } else { '0' })
        .collect();

    let have_count = bitstring.chars().filter(|c| *c == '1').count();

    Ok(format!("{have_count}|{len}|{bitstring}"))
}

// ---- Extra metadata: piece size, private flag, tracker list ----
//
// Response protocol:
//   "OK|<pieceLengthBytes>|<private 0/1>\n<trackerUrl>\n<trackerUrl>\n..."
//   "ERROR|<message>"
//
// Note: librqbit's runtime API for an *already-added* torrent (this function included)
// still doesn't expose comment/created-by/creation-date — TorrentDetailsResponse/TorrentStats
// never carried those top-level .torrent fields. "comment" is now captured separately, but
// only at add time (see list_only_add/format_file_list_response below), by re-parsing the raw
// .torrent bytes with librqbit::torrent_from_bytes before they're handed off to the engine —
// that's the only point they're ever available. created-by/creation-date could be added the
// same way if wanted later. There's still no "force reannounce" action method.

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_getTorrentExtra<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    hash: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let hash_str: String = hash.mutf8_chars(env)?.into();

            let message = match get_torrent_extra_blocking(&hash_str) {
                Ok(s) => s,
                Err(e) => format!("ERROR|{e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn get_torrent_extra_blocking(hash: &str) -> anyhow::Result<String> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    let handle = find_torrent_by_hash(engine, hash)?;

    let (piece_length, is_private) = handle
        .with_metadata(|meta| (meta.info.info().piece_length, meta.info.info().private))
        .map_err(|e| anyhow::anyhow!("{e}"))?;

    let mut trackers: Vec<String> = handle
        .shared()
        .trackers
        .iter()
        .map(|u| u.to_string())
        .collect();
    trackers.sort();

    let mut out = format!("OK|{piece_length}|{}", if is_private { 1 } else { 0 });
    for tracker in trackers {
        out.push('\n');
        out.push_str(&sanitize_field(tracker));
    }

    Ok(out)
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_getTorrentPeers<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    hash: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let hash_str: String = hash.mutf8_chars(env)?.into();

            let message = match get_torrent_peers_blocking(&hash_str) {
                Ok(s) => s,
                Err(e) => format!("ERROR: {e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn get_torrent_peers_blocking(hash: &str) -> anyhow::Result<String> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    let idx = TorrentIdOrHash::try_from(hash)?;

    let snapshot = engine
        .api
        .api_peer_stats(idx, Default::default())
        .map_err(|e| anyhow::anyhow!("{e}"))?;

    let mut lines: Vec<String> = snapshot
        .peers
        .iter()
        .map(|(addr, peer)| {
            let client_name = peer.client_name.clone().unwrap_or_default();
            let conn_kind = peer
                .conn_kind
                .as_ref()
                .map(|k| format!("{k:?}"))
                .unwrap_or_default();

            format!(
                "{}|{}|{}|{}|{}|{}",
                sanitize_field(addr.clone()),
                sanitize_field(client_name),
                peer.state,
                sanitize_field(conn_kind),
                peer.counters.fetched_bytes,
                peer.counters.uploaded_bytes
            )
        })
        .collect();

    lines.sort();

    Ok(lines.join("\n"))
}

// ---- Files tab: real on-disk paths for in-app file opening, plus per-file progress ----
//
// Response protocol:
//   "OK|<output_folder>\n<idx>|<relativePath>|<sizeBytes>|<included 0/1>|<havePieces>|<totalPiecesInRange>\n..."
//   "ERROR|<message>"
//
// relativePath uses '/' as separator; Kotlin joins it onto output_folder
// (via java.io.File(outputFolder, relativePath)) to get the real absolute path.
//
// havePieces/totalPiecesInRange are an APPROXIMATION: librqbit's public API doesn't
// expose a per-file "bytes downloaded" figure, only a whole-torrent piece bitfield
// (api_dump_haves) and total_pieces. Files are laid out back-to-back in piece order,
// so we estimate piece_length = ceil(total_bytes / total_pieces), turn each file's
// exact byte-offset range into an estimated piece-index range, and count how many of
// those pieces are marked "have" in the bitfield. A file's edge pieces are often
// shared with a neighboring file, so this can slightly overcount/undercount near file
// boundaries, but it's close enough for a progress bar.

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_getTorrentFiles<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    hash: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let hash_str: String = hash.mutf8_chars(env)?.into();

            let message = match get_torrent_files_blocking(&hash_str) {
                Ok(s) => s,
                Err(e) => format!("ERROR|{e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn get_torrent_files_blocking(hash: &str) -> anyhow::Result<String> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    let idx = TorrentIdOrHash::try_from(hash)?;

    let details = engine
        .api
        .api_torrent_details(idx)
        .map_err(|e| anyhow::anyhow!("{e}"))?;

    let output_folder = sanitize_field(details.output_folder.clone());

    let mut out = format!("OK|{output_folder}");

    // Try to get the have-bitfield so we can estimate per-file progress. If this
    // fails (e.g. torrent metadata not fully resolved yet), fall back to 0|0 for
    // every file rather than failing the whole files listing.
    let haves = engine.api.api_dump_haves(idx).ok();
    let total_pieces = details.total_pieces as usize;

    if let Some(files) = details.files {
        let total_bytes: u64 = files.iter().map(|f| f.length).sum();
        let piece_length: u64 = if total_pieces > 0 && total_bytes > 0 {
            (total_bytes + total_pieces as u64 - 1) / total_pieces as u64
        } else {
            0
        };

        let mut offset: u64 = 0;
        for (idx, f) in files.iter().enumerate() {
            let rel_path = f.components.join("/");
            let file_start = offset;
            let file_end = offset + f.length; // exclusive
            offset = file_end;

            let (have_pieces, total_pieces_in_range) =
                if piece_length > 0 && f.length > 0 {
                    let start_piece = (file_start / piece_length) as usize;
                    let end_piece = if file_end == 0 {
                        0
                    } else {
                        ((file_end - 1) / piece_length) as usize
                    };
                    let range_total = end_piece.saturating_sub(start_piece) + 1;

                    let have = if let Some((bf, len)) = &haves {
                        let len = *len as usize;
                        bf.iter()
                            .by_vals()
                            .take(len)
                            .enumerate()
                            .skip(start_piece)
                            .take(range_total)
                            .filter(|(_, b)| *b)
                            .count()
                    } else {
                        0
                    };

                    (have, range_total)
                } else {
                    (0, 0)
                };

            out.push_str(&format!(
                "\n{}|{}|{}|{}|{}|{}",
                idx,
                sanitize_field(rel_path),
                f.length,
                if f.included { 1 } else { 0 },
                have_pieces,
                total_pieces_in_range
            ));
        }
    }

    Ok(out)
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_pauseTorrent<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    hash: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let hash_str: String = hash.mutf8_chars(env)?.into();

            let message = match pause_torrent_blocking(&hash_str) {
                Ok(()) => "PAUSED".to_string(),
                Err(e) => format!("PAUSE ERROR: {e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn pause_torrent_blocking(hash: &str) -> anyhow::Result<()> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    let handle = find_torrent_by_hash(engine, hash)?;

    engine.runtime.block_on(engine.session.pause(&handle))
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_resumeTorrent<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    hash: JString<'local>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let hash_str: String = hash.mutf8_chars(env)?.into();

            let message = match resume_torrent_blocking(&hash_str) {
                Ok(()) => "RESUMED".to_string(),
                Err(e) => format!("RESUME ERROR: {e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn resume_torrent_blocking(hash: &str) -> anyhow::Result<()> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    let handle = find_torrent_by_hash(engine, hash)?;

    engine.runtime.block_on(engine.session.unpause(&handle))
}

#[no_mangle]
pub extern "system" fn Java_com_example_torrentorv2_RustBridge_deleteTorrent<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    hash: JString<'local>,
    delete_files: jboolean,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let hash_str: String = hash.mutf8_chars(env)?.into();

            let message = match delete_torrent_blocking(&hash_str, delete_files) {
                Ok(()) => {
                    if delete_files {
                        "DELETED (torrent and files removed)".to_string()
                    } else {
                        "REMOVED (torrent removed, files kept)".to_string()
                    }
                }
                Err(e) => format!("DELETE ERROR: {e}"),
            };

            let output = env.new_string(message)?;
            Ok(output.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn delete_torrent_blocking(hash: &str, delete_files: bool) -> anyhow::Result<()> {
    let engine = ENGINE
        .get()
        .ok_or_else(|| anyhow::anyhow!("engine not initialized"))?;

    let handle = find_torrent_by_hash(engine, hash)?;
    let info_hash = handle.shared().info_hash;

    // Capture the delta since the last poll before this torrent's handle disappears from
    // engine.torrents — otherwise that final slice of progress/upload would be lost forever
    // instead of folded into the all-time total.
    accumulate_all_time_stats(engine);

    engine
        .runtime
        .block_on(engine.session.delete(info_hash.into(), delete_files))?;

    if let Ok(mut guard) = engine.torrents.lock() {
        guard.retain(|t| t.shared().info_hash != info_hash);
    }

    Ok(())
}