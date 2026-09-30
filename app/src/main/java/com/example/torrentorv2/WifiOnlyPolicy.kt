package com.example.torrentorv2

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import kotlin.concurrent.thread

/**
 * Wi-Fi-only download policy: when enabled, every currently-active ("live", not already
 * paused) torrent is paused the instant the device is on a metered connection (mobile data,
 * or a WiFi hotspot itself marked metered), and only the torrents THIS policy paused are
 * resumed once back on an unmetered connection.
 *
 * A torrent the user paused manually is never touched by this — pauseTorrent/resumeTorrent
 * are the same real engine calls used everywhere else in the app (they persist paused state
 * in librqbit's own session JSON), and resumeTorrent is only ever called here for a hash
 * this object itself recorded pausing in autoPausedPrefs. That's what keeps this consistent
 * with the app's core "a paused torrent must never auto-resume" requirement: this never
 * resumes a torrent it didn't pause itself, on this device or any other.
 *
 * reevaluate() is called from two places: TorrentService's live
 * ConnectivityManager.NetworkCallback (so it reacts the instant the network actually
 * changes), and MainActivity's Wi-Fi Only checkbox (so toggling the setting takes effect
 * immediately, not only on the next network change — unlike DHT/LSD, this is a genuine
 * runtime setting, not an engine-init-only one).
 */
object WifiOnlyPolicy {

    private const val PREFS_NAME = "torrentor_prefs"
    private const val AUTO_PAUSED_PREFS_NAME = "torrentor_wifi_autopaused"
    private const val KEY_ENABLED = "wifi_only_enabled"
    private const val KEY_HASHES = "hashes"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isCurrentConnectionMetered(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.isActiveNetworkMetered ?: false
        } catch (e: Throwable) {
            false
        }
    }

    /** Re-applies the policy for the device's CURRENT connection state right now. Safe to
     *  call from any thread (e.g. directly from a UI click listener) — does its own
     *  background work on a new thread and returns immediately. */
    fun reevaluate(context: Context) {
        val appContext = context.applicationContext
        thread {
            try {
                reevaluateBlocking(appContext)
            } catch (e: Throwable) {
                AppLog.warning("Wi-Fi Only policy check failed: ${e.message}")
            }
        }
    }

    private fun reevaluateBlocking(context: Context) {
        val autoPausedPrefs = context.getSharedPreferences(AUTO_PAUSED_PREFS_NAME, Context.MODE_PRIVATE)
        val autoPaused = (autoPausedPrefs.getStringSet(KEY_HASHES, emptySet()) ?: emptySet()).toMutableSet()

        if (!isEnabled(context)) {
            if (autoPaused.isNotEmpty()) resumeAll(autoPaused, autoPausedPrefs)
            return
        }

        if (isCurrentConnectionMetered(context)) {
            pauseLiveTorrents(autoPaused, autoPausedPrefs)
        } else if (autoPaused.isNotEmpty()) {
            resumeAll(autoPaused, autoPausedPrefs)
        }
    }

    private fun pauseLiveTorrents(autoPaused: MutableSet<String>, autoPausedPrefs: SharedPreferences) {
        val info: String = try {
            RustBridge.getTorrentInfo()
        } catch (e: Throwable) {
            return
        }

        var changed = false
        val stillPresent = mutableSetOf<String>()

        for (line in info.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split("|")
            if (parts.size < 3) continue
            val hash = parts[0]
            val status = parts[2]
            stillPresent.add(hash)

            if (status != "paused" && !autoPaused.contains(hash)) {
                try {
                    RustBridge.pauseTorrent(hash)
                    autoPaused.add(hash)
                    changed = true
                } catch (e: Throwable) {
                }
            }
        }

        // Drop bookkeeping for torrents that were deleted while we had them auto-paused, so
        // this set doesn't grow forever.
        if (autoPaused.retainAll(stillPresent)) changed = true

        if (changed) {
            autoPausedPrefs.edit().putStringSet(KEY_HASHES, autoPaused.toSet()).apply()
            AppLog.info("Wi-Fi Only: paused ${autoPaused.size} torrent(s) — on a metered connection")
        }
    }

    private fun resumeAll(autoPaused: MutableSet<String>, autoPausedPrefs: SharedPreferences) {
        val hashes = autoPaused.toList()
        for (hash in hashes) {
            try {
                RustBridge.resumeTorrent(hash)
            } catch (e: Throwable) {
            }
        }
        autoPaused.clear()
        autoPausedPrefs.edit().putStringSet(KEY_HASHES, emptySet()).apply()
        if (hashes.isNotEmpty()) {
            AppLog.info("Wi-Fi Only: resumed ${hashes.size} previously auto-paused torrent(s)")
        }
    }
}