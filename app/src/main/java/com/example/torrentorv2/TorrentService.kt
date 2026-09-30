package com.example.torrentorv2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class TorrentService : Service() {

    companion object {
        const val CHANNEL_ID = "torrentor_channel"
        const val NOTIFICATION_ID = 1

        // Separate channel/IDs for one-shot "download finished" pings, distinct from the
        // single ongoing/LOW-importance progress notification above: DEFAULT importance so
        // it actually alerts (sound/heads-up) instead of sitting silently in the shade.
        const val CHANNEL_ID_COMPLETE = "torrentor_complete_channel"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var updateLoopRunning = true
    private var multicastLock: WifiManager.MulticastLock? = null

    // Hashes we've already fired a "finished" notification for, persisted so a service
    // restart (or the notification loop just re-polling) doesn't re-fire for a torrent that
    // was already complete before this process started. Not the same thing as "torrent is
    // currently complete" — it's specifically "already notified", so completing again after
    // dropping below 100% (e.g. a recheck finds missing pieces, then finishes again) is
    // allowed to notify a second time.
    private lateinit var completePrefs: android.content.SharedPreferences
    private val notifiedHashes = mutableSetOf<String>()

    // Wi-Fi Only: reacts live to actual network changes via ConnectivityManager's callback,
    // rather than polling — WifiOnlyPolicy.reevaluate() does the actual pause/resume work
    // and is also called directly by MainActivity's checkbox, so toggling the setting takes
    // effect immediately without waiting for a network transition.
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(applicationContext)
        createNotificationChannel()
        acquireMulticastLock()

        completePrefs = getSharedPreferences("torrentor_notified_complete", MODE_PRIVATE)
        notifiedHashes.addAll(completePrefs.getStringSet("hashes", emptySet()) ?: emptySet())

        registerNetworkCallback()

        val downloadDir = filesDir.absolutePath + "/torrentor_test"
        val appContext = applicationContext

        // DHT and LSD are librqbit session options read only once, at engine-init time
        // below — there's no live toggle, so a switch flipped in MainActivity takes
        // effect starting from the next time this service (re)starts the engine, i.e.
        // after the app is fully restarted. See MainActivity's DHT/LSD switches.
        val prefs = getSharedPreferences("torrentor_prefs", MODE_PRIVATE)
        val dhtEnabled = prefs.getBoolean("dht_enabled", true)
        val lsdEnabled = prefs.getBoolean("lsd_enabled", true)

        thread {
            try {
                val result = RustBridge.initEngine(downloadDir, appContext, dhtEnabled, lsdEnabled)
                AppLog.info("Engine started (DHT=$dhtEnabled, LSD=$lsdEnabled): $result")

                // Unlike DHT/LSD, speed limits are a genuine runtime setting on librqbit's
                // side (Session.ratelimits, backed by the governor crate) rather than an
                // at-init-only option — but the engine still always STARTS unlimited, so the
                // saved limit has to be re-applied here every time this service (re)starts
                // the engine, same as MainActivity re-applies it instantly when the user
                // changes it from the Speed Limits dialog.
                val downloadBps = prefs.getLong("download_limit_bps", 0L)
                val uploadBps = prefs.getLong("upload_limit_bps", 0L)
                if (downloadBps > 0L || uploadBps > 0L) {
                    val limitResult = RustBridge.setSpeedLimits(downloadBps, uploadBps)
                    AppLog.info("Applied saved speed limits (down=$downloadBps B/s, up=$uploadBps B/s): $limitResult")
                }

                // Apply the Wi-Fi Only policy for whatever the connection already is right
                // now — the NetworkCallback registered in onCreate only fires on a CHANGE,
                // so without this, a torrent added while already on mobile data (with the
                // setting on) wouldn't get paused until the network actually changed.
                WifiOnlyPolicy.reevaluate(this@TorrentService)
            } catch (e: Throwable) {
                // Engine init failures surface via getTorrentInfo()'s error text on next poll.
                AppLog.error("Engine failed to start: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        startNotificationUpdateLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        updateLoopRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        releaseMulticastLock()
        unregisterNetworkCallback()
    }

    /** Registers a live callback that fires the instant the device's default network's
     *  metered-ness changes (WiFi <-> mobile data, or a WiFi network itself getting marked
     *  metered) — used to drive WifiOnlyPolicy without polling. */
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivityManager = cm

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    WifiOnlyPolicy.reevaluate(this@TorrentService)
                }

                override fun onAvailable(network: Network) {
                    WifiOnlyPolicy.reevaluate(this@TorrentService)
                }
            }
            networkCallback = callback
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Throwable) {
            AppLog.warning("Couldn't register network callback for Wi-Fi Only: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = connectivityManager
            val callback = networkCallback
            if (cm != null && callback != null) {
                cm.unregisterNetworkCallback(callback)
            }
        } catch (e: Throwable) {
        }
        networkCallback = null
    }

    /**
     * Android silently drops incoming WiFi multicast packets by default (to save battery),
     * which breaks UPnP port-forwarding discovery (SSDP uses multicast to find the router's
     * IGD service) — without this lock, librqbit's UPnP attempts always report "discovered 0
     * endpoints" even on a router that fully supports UPnP. Holding this lock for the engine's
     * whole lifetime (tied to this foreground service, not just MainActivity) is what lets
     * automatic port forwarding actually work, which in turn is what lets peers connect in to
     * us for seeding instead of us only ever connecting out to leech.
     */
    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock("torrentor_multicast_lock")
            lock?.setReferenceCounted(true)
            lock?.acquire()
            multicastLock = lock
        } catch (e: Throwable) {
            // No WiFi service available, or permission missing — UPnP discovery just won't
            // find anything, which is the same degraded state as before this fix.
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Throwable) {
        }
        multicastLock = null
    }

    private fun startNotificationUpdateLoop() {
        val interval = 2000L

        val updateTask = object : Runnable {
            override fun run() {
                if (!updateLoopRunning) return

                thread {
                    val info: String = try {
                        RustBridge.getTorrentInfo()
                    } catch (e: Throwable) {
                        ""
                    }

                    val summary = summarize(info)
                    val notification = buildNotification(summary)
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, notification)

                    checkForNewlyCompleted(info)
                }

                mainHandler.postDelayed(this, interval)
            }
        }

        mainHandler.postDelayed(updateTask, interval)
    }

    /** Parses the full pipe-delimited torrent list (same protocol as summarize()'s first
     *  line) and fires a one-shot "Finished" notification for any hash that just crossed
     *  into ~100% complete and hasn't already been notified. 99.95% (not exactly 100) guards
     *  against the same floating-point display rounding the UI already tolerates elsewhere. */
    private fun checkForNewlyCompleted(info: String) {
        val lines = info.lineSequence().filter { it.isNotBlank() }.toList()
        val seenHashes = mutableSetOf<String>()
        var changed = false

        for (line in lines) {
            val parts = line.split("|")
            if (parts.size < 4) continue

            val hash = parts[0]
            val name = parts[1]
            val percent = parts[3].toDoubleOrNull() ?: 0.0
            seenHashes.add(hash)

            if (percent >= 99.95) {
                if (!notifiedHashes.contains(hash)) {
                    notifiedHashes.add(hash)
                    changed = true
                    postCompleteNotification(hash, name)
                }
            } else {
                // Dropped back below "complete" (e.g. a recheck found missing pieces) —
                // clearing this lets a later re-completion notify again.
                if (notifiedHashes.remove(hash)) changed = true
            }
        }

        // Forget bookkeeping for torrents that no longer exist (deleted), so this set
        // doesn't grow forever across the life of the install.
        if (notifiedHashes.retainAll(seenHashes)) changed = true

        if (changed) {
            completePrefs.edit().putStringSet("hashes", notifiedHashes.toSet()).apply()
        }
    }

    private fun postCompleteNotification(hash: String, name: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COMPLETE)
            .setContentTitle("Download finished")
            .setContentText("✓ $name")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        // A stable per-torrent ID (distinct from NOTIFICATION_ID = 1, the ongoing progress
        // notification) so multiple finished torrents each get their own notification
        // instead of overwriting one another.
        manager.notify(("complete_" + hash).hashCode(), notification)
        AppLog.info("Finished: $name")
    }

    private fun summarize(info: String): String {
        val lines = info.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return "No torrents"

        val parts = lines[0].split("|")
        if (parts.size < 4) return "No torrents"

        val name = parts[1]
        val status = parts[2]
        val percent = parts[3]

        return if (lines.size > 1) {
            "$name: $status $percent%  (+${lines.size - 1} more)"
        } else {
            "$name: $status $percent%"
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TorrentOrV2")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                CHANNEL_ID,
                "TorrentOrV2 Service",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)

            // DEFAULT importance (not LOW like the ongoing channel above) so a "download
            // finished" notification actually alerts — sound + heads-up — instead of sitting
            // silently in the shade like the ever-present progress notification does.
            val completeChannel = NotificationChannel(
                CHANNEL_ID_COMPLETE,
                "TorrentOrV2 Download Finished",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(completeChannel)
        }
    }
}