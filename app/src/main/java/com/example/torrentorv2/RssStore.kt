package com.example.torrentorv2

import android.content.Context
import kotlin.concurrent.thread

/** Mutable UI-side state for one subscribed RSS/Atom feed. `url`, `name`, `autoDownload` and
 *  `lastRefreshMs` are persisted; `items`/`loading`/`error`/`seenLinks` are session-only and
 *  reset on app restart — worst case that costs one extra refresh cycle before auto-download
 *  resumes (see refreshFeed's `hadBaseline` comment), never a flood of re-downloads. */
data class RssFeedEntry(
    val url: String,
    var name: String,
    var items: List<RssItem> = emptyList(),
    var loading: Boolean = false,
    var error: String? = null,
    var autoDownload: Boolean = false,
    var lastRefreshMs: Long = 0L,
    val seenLinks: MutableSet<String> = mutableSetOf()
)

/** The refresh intervals offered in the picker, in milliseconds, paired with a display label. */
val RSS_REFRESH_INTERVAL_OPTIONS: List<Pair<String, Long>> = listOf(
    "5 minutes" to 5 * 60 * 1000L,
    "15 minutes" to 15 * 60 * 1000L,
    "30 minutes" to 30 * 60 * 1000L,
    "1 hour" to 60 * 60 * 1000L,
    "3 hours" to 3 * 60 * 60 * 1000L,
    "6 hours" to 6 * 60 * 60 * 1000L,
    "12 hours" to 12 * 60 * 60 * 1000L,
    "1 day" to 24 * 60 * 60 * 1000L
)

/**
 * Holds RSS subscriptions and their fetched items, independent of any Activity — MainActivity
 * runs the background auto-refresh timer against this store, RssActivity/RssFeedItemsActivity
 * are just windows onto it, so feeds keep refreshing on schedule whether or not those windows
 * happen to be open. Not a background service: this only runs while the app process itself is
 * alive (the timer lives in MainActivity, same as every other auto-refresh loop in this app).
 *
 * Also owns per-feed "auto-download": when a feed's `autoDownload` flag is on, any item that
 * appears in a refresh but wasn't present in the previous refresh gets added as a torrent
 * automatically, no tap needed. The very first refresh after a feed is added (or after the app
 * restarts, since `seenLinks` isn't persisted) never auto-downloads — there's no "previous
 * refresh" to diff against yet, so every item would look "new" and flood-download the whole
 * feed. Auto-download only fires once a baseline of already-seen items exists.
 */
object RssStore {
    const val PREFS_NAME = "torrentor_prefs"
    private const val KEY_FEEDS = "rss_feeds"
    private const val KEY_INTERVAL_MS = "rss_refresh_interval_ms"
    private const val KEY_LAST_REFRESH_MS = "rss_last_refresh_ms"
    private val DEFAULT_INTERVAL_MS = RSS_REFRESH_INTERVAL_OPTIONS[1].second // 15 minutes

    val feeds = mutableListOf<RssFeedEntry>()
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        feeds.clear()
        feeds.addAll(loadFromPrefs(context))
    }

    fun getRefreshIntervalMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)
    }

    fun setRefreshIntervalMs(context: Context, intervalMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_INTERVAL_MS, intervalMs)
            .apply()
    }

    /** 0L means "never refreshed yet". */
    fun getLastRefreshMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_REFRESH_MS, 0L)
    }

    private fun setLastRefreshMs(context: Context, whenMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_REFRESH_MS, whenMs)
            .apply()
    }

    /** 0L means "unknown" (nothing has refreshed yet to project from). */
    fun getNextRefreshMs(context: Context): Long {
        val last = getLastRefreshMs(context)
        return if (last == 0L) 0L else last + getRefreshIntervalMs(context)
    }

    private fun loadFromPrefs(context: Context): List<RssFeedEntry> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FEEDS, "") ?: ""

        if (raw.isBlank()) return emptyList()

        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|||")
                val url = parts.getOrNull(0)?.trim()
                val name = parts.getOrNull(1)?.trim()
                val autoDownload = parts.getOrNull(2)?.trim() == "1"
                val lastRefreshMs = parts.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L
                if (url.isNullOrBlank()) return@mapNotNull null
                RssFeedEntry(
                    url = url,
                    name = if (name.isNullOrBlank()) url else name,
                    autoDownload = autoDownload,
                    lastRefreshMs = lastRefreshMs
                )
            }
            .toList()
    }

    private fun saveToPrefs(context: Context) {
        val serialized = feeds.joinToString("\n") {
            "${it.url}|||${it.name}|||${if (it.autoDownload) "1" else "0"}|||${it.lastRefreshMs}"
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FEEDS, serialized)
            .apply()
    }

    fun addFeed(context: Context, url: String): Boolean {
        ensureLoaded(context)
        if (feeds.any { it.url.equals(url, ignoreCase = true) }) return false

        feeds.add(RssFeedEntry(url = url, name = url))
        saveToPrefs(context)
        AppLog.info("RSS: subscribed to $url")
        return true
    }

    fun removeFeed(context: Context, entry: RssFeedEntry) {
        feeds.remove(entry)
        saveToPrefs(context)
        AppLog.info("RSS: unsubscribed from ${entry.name}")
    }

    fun setAutoDownload(context: Context, entry: RssFeedEntry, enabled: Boolean) {
        entry.autoDownload = enabled
        saveToPrefs(context)
        AppLog.info("RSS: auto-download ${if (enabled) "enabled" else "disabled"} for \"${entry.name}\"")
    }

    /** Fetches one feed in the background. Callers that touch views must hop back to the UI
     *  thread themselves via onDone — RssActivity/RssFeedItemsActivity's polling redraw loop
     *  mostly makes this unnecessary since they just re-read `feeds` on a timer. */
    fun refreshFeed(context: Context, entry: RssFeedEntry, onDone: (() -> Unit)? = null) {
        entry.loading = true
        entry.error = null

        thread {
            try {
                val result = RssManager.fetchFeed(entry.url)

                // A non-empty seenLinks means this feed has completed at least one refresh
                // since the app process started, so there's a real "previous state" to diff
                // against. Empty means either a brand-new feed or a just-restarted app —
                // either way, skip auto-download this one time rather than treating every
                // item as "new".
                val previousSeen = entry.seenLinks.toSet()
                val hadBaseline = previousSeen.isNotEmpty()

                entry.items = result.items
                if (!result.feedTitle.isNullOrBlank()) {
                    entry.name = result.feedTitle
                }
                entry.error = null

                val currentKeys = result.items.mapNotNull { keyFor(it) }.toSet()
                val newKeys = if (hadBaseline) currentKeys - previousSeen else emptySet()
                entry.seenLinks.clear()
                entry.seenLinks.addAll(currentKeys)

                if (entry.autoDownload && newKeys.isNotEmpty()) {
                    val outputFolder = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString("download_folder", "") ?: ""
                    for (item in result.items) {
                        val key = keyFor(item) ?: continue
                        if (key in newKeys) autoDownloadItem(item, outputFolder, entry.name)
                    }
                }

                entry.lastRefreshMs = System.currentTimeMillis()
                val newNote = if (newKeys.isNotEmpty()) ", ${newKeys.size} new" else ""
                AppLog.info("RSS: refreshed \"${entry.name}\" — ${result.items.size} item(s)$newNote")
            } catch (e: Throwable) {
                entry.error = e.message ?: "Couldn't load feed"
                AppLog.warning("RSS: failed to refresh ${entry.url} — ${entry.error}")
            } finally {
                entry.loading = false
                setLastRefreshMs(context, System.currentTimeMillis())
                saveToPrefs(context)
                onDone?.invoke()
            }
        }
    }

    private fun keyFor(item: RssItem): String? {
        val key = item.downloadUrl ?: item.pageLink
        return key.ifBlank { null }
    }

    private fun autoDownloadItem(item: RssItem, outputFolder: String, feedName: String) {
        val source = item.downloadUrl ?: return
        try {
            val result = if (source.startsWith("magnet:", ignoreCase = true)) {
                RustBridge.addMagnet(source, outputFolder)
            } else {
                RustBridge.addTorrentFile(RssManager.fetchUrlBytes(source), outputFolder)
            }
            AppLog.info("RSS auto-download [$feedName]: \"${item.title}\" — $result")
        } catch (e: Throwable) {
            AppLog.warning("RSS auto-download [$feedName]: failed for \"${item.title}\" — ${e.message}")
        }
    }

    fun refreshAll(context: Context, onEachDone: (() -> Unit)? = null) {
        ensureLoaded(context)
        for (entry in feeds.toList()) {
            refreshFeed(context, entry, onEachDone)
        }
    }
}