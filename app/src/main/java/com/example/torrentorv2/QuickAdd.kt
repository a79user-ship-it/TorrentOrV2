package com.example.torrentorv2

import android.app.Activity
import android.content.Context
import kotlin.concurrent.thread

/**
 * Adds a torrent straight from a magnet URI or a .torrent download URL — used by RssActivity
 * and SearchActivity, where the flow is "download everything" (no per-file selection), unlike
 * MainActivity's manual "Add Magnet"/"Choose .torrent File" buttons, which offer a file-picking
 * step first. Downloading every file is the reasonable default for an RSS/search hit — the
 * user already picked which torrent they want by choosing this result.
 */
object QuickAdd {
    fun addSource(activity: Activity, source: String, label: String, onDone: (String) -> Unit) {
        val outputFolder = activity.getSharedPreferences(RssStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("download_folder", "") ?: ""

        if (source.startsWith("magnet:", ignoreCase = true)) {
            thread {
                val result: String = try {
                    RustBridge.addMagnet(source, outputFolder)
                } catch (e: Throwable) {
                    "ERROR: ${e.message}"
                }
                AppLog.info("Add \"$label\" (magnet): $result")
                activity.runOnUiThread { onDone(result) }
            }
            return
        }

        thread {
            val bytes: ByteArray? = try {
                RssManager.fetchUrlBytes(source)
            } catch (e: Throwable) {
                null
            }

            if (bytes == null) {
                AppLog.warning("Add \"$label\": couldn't download .torrent from $source")
                activity.runOnUiThread { onDone("ERROR: couldn't download the .torrent file") }
                return@thread
            }

            val result: String = try {
                RustBridge.addTorrentFile(bytes, outputFolder)
            } catch (e: Throwable) {
                "ERROR: ${e.message}"
            }
            AppLog.info("Add \"$label\": $result")
            activity.runOnUiThread { onDone(result) }
        }
    }
}