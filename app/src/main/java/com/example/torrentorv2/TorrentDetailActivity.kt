package com.example.torrentorv2

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

/**
 * Full-screen "Torrent details" page for a single torrent — header stats + a horizontally
 * scrollable GENERAL / FILES / TRACKERS / PEERS / PIECES tab strip, opened from a tap on a
 * torrent card in MainActivity's list instead of that card expanding in place.
 *
 * This screen owns its own single-hash copies of the same caches MainActivity keeps per-hash
 * (piece data, peer snapshots for speed deltas, file list + selection overrides, extra/tracker
 * info) rather than sharing MainActivity's maps — simpler than plumbing them across activities,
 * and each is scoped to just the one torrent this screen is showing.
 */
class TorrentDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HASH = "hash"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = true

    private lateinit var hash: String

    private var currentTorrent: TorrentInfo? = null
    private var torrentMissing = false

    private var selectedTab: String = "general"

    private var extraInfo: ExtraInfo? = null
    private var extraInfoErr: String? = null

    private var pieceDataRaw: String? = null

    private var peerSnapshot: PeerSnapshotCache? = null
    private var peerDisplayRows: List<PeerRow> = emptyList()

    private var fileListData: Pair<String, List<FileEntry>>? = null
    private var fileListErr: String? = null
    private val fileSelections = mutableMapOf<Int, Boolean>()

    private var cachedNetworkStatusRaw: String = ""

    /** Approximate "first seen by this app" timestamp for the General tab's Added On row —
     *  same SharedPreferences file MainActivity stamps on first poll of a hash (see its
     *  dateAddedPrefs field comment for why this is an approximation, not a real add time
     *  from the engine). */
    private val dateAddedPrefs by lazy { getSharedPreferences("torrentor_date_added", Context.MODE_PRIVATE) }

    /** hash -> comment, same SharedPreferences file MainActivity writes into right after a
     *  torrent finishes adding (see its commentPrefs field comment). librqbit has no runtime
     *  API that exposes a torrent's "comment" field once it's already added, so this is only
     *  ever populated for torrents added after that feature existed — an older torrent, or one
     *  whose .torrent file simply didn't set a comment (most don't), just won't have an entry
     *  here, and the Comment tab says so plainly rather than pretending. */
    private val commentPrefs by lazy { getSharedPreferences("torrentor_comments", Context.MODE_PRIVATE) }

    /** User-typed personal notes per torrent, keyed by info hash — purely local, never sent
     *  anywhere. This is NOT the .torrent file's own "comment" metadata field: librqbit parses
     *  that at the raw torrent-file level (see torrent_metainfo.rs) but never surfaces it
     *  through any runtime API for an already-added torrent (confirmed absent from both
     *  TorrentDetailsResponse and TorrentStats), so there's nothing to read back even if we
     *  wanted to show the uploader's original comment instead. */
    private val commentsPrefs by lazy { getSharedPreferences("torrentor_comments", Context.MODE_PRIVATE) }

    // ---- Views ----
    private lateinit var nameView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusPercentView: TextView
    private lateinit var peersView: TextView
    private lateinit var downSpeedView: TextView
    private lateinit var upSpeedView: TextView
    private lateinit var ratioView: TextView
    private lateinit var sizeView: TextView
    private lateinit var uploadedView: TextView
    private lateinit var pauseResumeBtn: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var contentContainer: LinearLayout

    private val textPrimary = Color.WHITE
    private val textSecondary = Color.parseColor("#CCCCCC")
    private val textMuted = Color.parseColor("#999999")

    // themeAccent is the user's chosen theme color (Settings > Theme) — used for anything
    // decorative (progress bars, section headers). statusGreenColor is a fixed, literal green
    // kept separate for anything that means "working/enabled" (the Trackers tab's DHT/LSD
    // status), so picking e.g. the Crimson Red theme doesn't make a "Working" line confusingly
    // read as an error.
    //
    // NOTE: this can't be read via AppTheme.accentColor(this) here at property-initializer time —
    // that runs inside the Activity's constructor, before Android calls attachBaseContext(), so
    // "this" isn't a usable Context yet (SharedPreferences access on it throws a
    // NullPointerException and crashes the app the instant the Activity is created). It's set for
    // real in onCreate() instead, once the Activity is actually attached.
    private var accentGreen = Color.parseColor("#4CAF50")
    private val statusGreenColor = Color.parseColor("#4CAF50")
    private val trackDark = Color.parseColor("#555555")
    private val barBg = Color.parseColor("#1F1F1F")
    private val cardBg = Color.parseColor("#2A2A2A")
    private val tabSelectedBg = Color.parseColor("#4A4A4A")
    private val tabUnselectedBg = Color.parseColor("#333333")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safe to read the theme now — the Activity is actually attached at this point.
        accentGreen = AppTheme.accentColor(this)

        val h = intent.getStringExtra(EXTRA_HASH)
        if (h.isNullOrBlank()) {
            Toast.makeText(this, "No torrent specified.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        hash = h

        setContentView(buildRootView())

        startPolling()
    }

    // ---- Layout ----

    private fun buildRootView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#151515"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        root.addView(buildTopBar())

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        scrollContent.addView(buildHeader())
        scrollContent.addView(buildTabStrip())

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scrollContent.addView(contentContainer)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(scrollContent)
        }
        root.addView(scrollView)

        return root
    }

    private fun iconButton(label: String, sizeSp: Float = 20f): TextView = TextView(this).apply {
        text = label
        textSize = sizeSp
        setTextColor(textPrimary)
        isClickable = true
        isFocusable = true
        setPadding(20, 12, 20, 12)
        gravity = Gravity.CENTER
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(barBg)
            setPadding(8, 16, 8, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val backBtn = iconButton("←", 24f)
        backBtn.setOnClickListener { finish() }

        val titleView = TextView(this).apply {
            text = "Torrent details"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(8, 0, 0, 0)
        }

        pauseResumeBtn = iconButton("⏸")
        val deleteBtn = iconButton("🗑")
        val moreBtn = iconButton("⋮")

        pauseResumeBtn.setOnClickListener { togglePauseResume() }
        deleteBtn.setOnClickListener { confirmDelete() }
        moreBtn.setOnClickListener { showOverflowMenu() }

        bar.addView(backBtn)
        bar.addView(titleView)
        bar.addView(pauseResumeBtn)
        bar.addView(deleteBtn)
        bar.addView(moreBtn)

        return bar
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        nameView = TextView(this).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accentGreen)
            progressBackgroundTintList = ColorStateList.valueOf(trackDark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }

        fun statRow(vararg views: TextView): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 14 }
            }
            for (v in views) {
                v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(v)
            }
            return row
        }

        fun statText(): TextView = TextView(this).apply {
            textSize = 13f
            setTextColor(textSecondary)
        }

        statusPercentView = statText().apply { setTypeface(null, Typeface.BOLD); setTextColor(textPrimary) }
        peersView = statText().apply { gravity = Gravity.END }
        downSpeedView = statText()
        upSpeedView = statText().apply { gravity = Gravity.END }
        ratioView = statText()
        sizeView = statText().apply { gravity = Gravity.CENTER }
        uploadedView = statText().apply { gravity = Gravity.END }

        header.addView(nameView)
        header.addView(progressBar)
        header.addView(statRow(statusPercentView, peersView))
        header.addView(statRow(downSpeedView, upSpeedView))
        header.addView(statRow(ratioView, sizeView, uploadedView))

        return header
    }

    private fun buildTabStrip(): View {
        val tabs = listOf(
            "general" to "GENERAL",
            "files" to "FILES",
            "trackers" to "TRACKERS",
            "peers" to "PEERS",
            "pieces" to "PIECES",
            "comment" to "COMMENT"
        )

        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        for ((key, label) in tabs) {
            val tabBtn = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
                setPadding(28, 24, 28, 24)
                isClickable = true
                isFocusable = true
                tag = key
                setBackgroundColor(if (key == selectedTab) tabSelectedBg else tabUnselectedBg)
            }
            tabBtn.setOnClickListener {
                selectedTab = key
                onTabSelected(key)
                renderTabStrip()
                renderContent()
            }
            tabRow.addView(tabBtn)
        }

        return HorizontalScrollView(this).apply {
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
            addView(tabRow)
        }
    }

    private fun renderTabStrip() {
        for (i in 0 until tabRow.childCount) {
            val child = tabRow.getChildAt(i)
            val key = child.tag as? String ?: continue
            child.setBackgroundColor(if (key == selectedTab) tabSelectedBg else tabUnselectedBg)
        }
    }

    // ---- Polling ----

    private fun onTabSelected(key: String) {
        when (key) {
            "pieces" -> fetchPieces()
            "peers" -> fetchPeers()
            "files" -> fetchFiles()
            "trackers" -> { fetchExtraOnce(); fetchNetworkStatus() }
            "general" -> {
                // General now shows piece count/size and the save path alongside the usual
                // transfer stats, so it needs one-off pieces + files fetches too (not the
                // continuous 3s polling those get while their own tab is open).
                fetchExtraOnce()
                if (pieceDataRaw == null) fetchPieces()
                if (fileListData == null && fileListErr == null) fetchFiles()
            }
        }
    }

    private fun startPolling() {
        fetchExtraOnce()
        onTabSelected(selectedTab)

        val headerTask = object : Runnable {
            override fun run() {
                if (!running) return
                pollHeader()
                mainHandler.postDelayed(this, 1000L)
            }
        }
        mainHandler.postDelayed(headerTask, 0L)

        val tabTask = object : Runnable {
            override fun run() {
                if (!running) return
                when (selectedTab) {
                    "pieces" -> fetchPieces()
                    "peers" -> fetchPeers()
                    "files" -> fetchFiles()
                    "trackers" -> fetchNetworkStatus()
                }
                mainHandler.postDelayed(this, 3000L)
            }
        }
        mainHandler.postDelayed(tabTask, 3000L)
    }

    private fun pollHeader() {
        thread {
            val raw: String = try {
                RustBridge.getTorrentInfo()
            } catch (e: Throwable) {
                ""
            }

            val match = raw.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size < 12 || parts[0] != hash) return@mapNotNull null
                    TorrentInfo(
                        hash = parts[0],
                        name = parts[1],
                        status = parts[2],
                        percent = parts[3].toDoubleOrNull() ?: 0.0,
                        downloadedBytes = parts[4].toLongOrNull() ?: 0L,
                        totalBytes = parts[5].toLongOrNull() ?: 0L,
                        uploadedBytes = parts[6].toLongOrNull() ?: 0L,
                        downSpeedBytes = parts[7].toLongOrNull() ?: 0L,
                        upSpeedBytes = parts[8].toLongOrNull() ?: 0L,
                        eta = parts[9],
                        connPeers = parts[10].toLongOrNull() ?: 0L,
                        knownPeers = parts[11].toLongOrNull() ?: 0L
                    )
                }
                .firstOrNull()

            runOnUiThread {
                if (!running) return@runOnUiThread
                if (match == null) {
                    torrentMissing = true
                } else {
                    torrentMissing = false
                    currentTorrent = match
                }
                renderHeader()
                if (selectedTab == "general") renderContent()
            }
        }
    }

    private fun fetchExtraOnce() {
        if (extraInfo != null || extraInfoErr != null) return
        thread {
            val raw: String = try {
                RustBridge.getTorrentExtra(hash)
            } catch (e: Throwable) {
                "ERROR|${e.message}"
            }

            if (raw.startsWith("ERROR|")) {
                extraInfoErr = raw.substringAfter("ERROR|")
            } else {
                extraInfo = parseExtraResponse(raw)
                if (extraInfo == null) extraInfoErr = "Unexpected response from engine."
            }

            runOnUiThread {
                if (!running) return@runOnUiThread
                if (selectedTab == "general" || selectedTab == "trackers") renderContent()
            }
        }
    }

    private fun fetchNetworkStatus() {
        thread {
            val raw: String = try {
                RustBridge.getNetworkStatus()
            } catch (e: Throwable) {
                "ERROR|${e.message}"
            }
            runOnUiThread {
                if (!running) return@runOnUiThread
                cachedNetworkStatusRaw = raw
                if (selectedTab == "trackers") renderContent()
            }
        }
    }

    private fun fetchPieces() {
        thread {
            val result: String = try {
                RustBridge.getTorrentPieces(hash)
            } catch (e: Throwable) {
                "ERROR: ${e.message}"
            }
            runOnUiThread {
                if (!running) return@runOnUiThread
                pieceDataRaw = result
                // General shows a "Pieces: total x size (have N)" summary line too, so it
                // needs a re-render here as well, not just the Pieces tab itself.
                if (selectedTab == "pieces" || selectedTab == "general") renderContent()
            }
        }
    }

    private fun fetchPeers() {
        thread {
            val raw: String = try {
                RustBridge.getTorrentPeers(hash)
            } catch (e: Throwable) {
                "ERROR: ${e.message}"
            }

            val newRows = parsePeerRows(raw)
            val now = System.currentTimeMillis()
            val previous = peerSnapshot

            val displayRows = newRows.values.map { row ->
                val prevRow = previous?.rows?.get(row.address)
                if (prevRow != null && previous.timestampMs > 0) {
                    val elapsedSec = (now - previous.timestampMs) / 1000.0
                    if (elapsedSec > 0) {
                        val downSpeed = ((row.downloaded - prevRow.downloaded).coerceAtLeast(0) / elapsedSec).toLong()
                        val upSpeed = ((row.uploaded - prevRow.uploaded).coerceAtLeast(0) / elapsedSec).toLong()
                        PeerRow(row.address, row.client, row.state, row.connKind, downSpeed, upSpeed)
                    } else {
                        PeerRow(row.address, row.client, row.state, row.connKind, 0L, 0L)
                    }
                } else {
                    PeerRow(row.address, row.client, row.state, row.connKind, 0L, 0L)
                }
            }.sortedBy { it.address }

            runOnUiThread {
                if (!running) return@runOnUiThread
                peerSnapshot = PeerSnapshotCache(now, newRows)
                peerDisplayRows = displayRows
                if (selectedTab == "peers") renderContent()
            }
        }
    }

    private fun fetchFiles() {
        thread {
            val raw: String = try {
                RustBridge.getTorrentFiles(hash)
            } catch (e: Throwable) {
                "ERROR|${e.message}"
            }

            runOnUiThread {
                if (!running) return@runOnUiThread
                if (raw.startsWith("ERROR|")) {
                    fileListErr = raw.substringAfter("ERROR|")
                    fileListData = null
                } else {
                    val parsed = parseFilesResponse(raw)
                    if (parsed != null) {
                        fileListData = parsed
                        fileListErr = null
                    }
                }
                // General shows the Save Path line too, so it needs a re-render here as well.
                if (selectedTab == "files" || selectedTab == "general") renderContent()
            }
        }
    }

    // ---- Parsing (mirrors MainActivity's protocol parsing, scoped to this one hash) ----

    private fun parseExtraResponse(raw: String): ExtraInfo? {
        if (raw.isBlank() || raw.startsWith("ERROR|")) return null
        val lines = raw.lines()
        if (lines.isEmpty() || !lines[0].startsWith("OK|")) return null
        val headerParts = lines[0].removePrefix("OK|").split("|")
        val pieceLength = headerParts.getOrNull(0)?.toLongOrNull() ?: 0L
        val isPrivate = headerParts.getOrNull(1) == "1"
        val trackers = lines.drop(1).filter { it.isNotBlank() }
        return ExtraInfo(pieceLength, isPrivate, trackers)
    }

    private fun parsePeerRows(raw: String): Map<String, PeerRow> {
        if (raw.isBlank() || raw.startsWith("ERROR")) return emptyMap()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 6) return@mapNotNull null
                PeerRow(
                    address = parts[0],
                    client = parts[1],
                    state = parts[2],
                    connKind = parts[3],
                    downloaded = parts[4].toLongOrNull() ?: 0L,
                    uploaded = parts[5].toLongOrNull() ?: 0L
                )
            }
            .associateBy { it.address }
    }

    private fun parseFilesResponse(raw: String): Pair<String, List<FileEntry>>? {
        if (raw.isBlank() || raw.startsWith("ERROR|")) return null
        val lines = raw.lines()
        if (lines.isEmpty() || !lines[0].startsWith("OK|")) return null
        val outputFolder = lines[0].substringAfter("OK|")
        val files = mutableListOf<FileEntry>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split("|")
            if (parts.size < 4) continue
            val index = parts[0].toIntOrNull() ?: continue
            val path = parts[1]
            val size = parts[2].toLongOrNull() ?: 0L
            val included = parts[3] == "1"
            val havePieces = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val totalPieces = parts.getOrNull(5)?.toIntOrNull() ?: 0
            files.add(FileEntry(index, path, size, included, havePieces, totalPieces))
        }
        return outputFolder to files
    }

    // ---- Formatting ----

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return String.format("%.2f %s", value, units[unitIndex])
    }

    private fun formatSpeed(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"

    // ---- Rendering ----

    private fun renderHeader() {
        val t = currentTorrent

        if (t == null) {
            nameView.text = if (torrentMissing) "Torrent no longer exists" else "Loading..."
            return
        }

        nameView.text = t.name
        progressBar.progress = (t.percent * 10).toInt().coerceIn(0, 1000)

        statusPercentView.text = "${t.status.replaceFirstChar { it.uppercase() }} - ${String.format("%.1f", t.percent)}%"
        peersView.text = "Peers: ${t.connPeers} (${t.knownPeers})"

        downSpeedView.text = "↓ ${formatSpeed(t.downSpeedBytes)}"
        upSpeedView.text = "↑ ${formatSpeed(t.upSpeedBytes)}"

        val ratio = if (t.downloadedBytes > 0) t.uploadedBytes.toDouble() / t.downloadedBytes.toDouble() else 0.0
        ratioView.text = "Ratio: ${String.format("%.2f", ratio)}"
        sizeView.text = "Size: ${formatBytes(t.totalBytes)}"
        uploadedView.text = "Uploaded: ${formatBytes(t.uploadedBytes)}"

        pauseResumeBtn.text = if (t.status == "paused") "▶" else "⏸"
    }

    private fun renderContent() {
        contentContainer.removeAllViews()

        if (torrentMissing) {
            contentContainer.addView(TextView(this).apply {
                text = "This torrent no longer exists — it may have been removed."
                textSize = 13f
                setTextColor(textSecondary)
            })
            return
        }

        when (selectedTab) {
            "general" -> contentContainer.addView(buildGeneralTabView())
            "files" -> contentContainer.addView(buildFilesTabView())
            "trackers" -> contentContainer.addView(buildTrackersTabView())
            "comment" -> contentContainer.addView(buildCommentTabView())
            else -> {
                val textView = TextView(this).apply {
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(textSecondary)
                }
                textView.text = when (selectedTab) {
                    "pieces" -> buildPiecesTabText()
                    "peers" -> buildPeersTabText()
                    else -> ""
                }
                contentContainer.addView(textView)
            }
        }
    }

    // ---- General tab: qBittorrent-style "Transfer" / "Information" stat grid ----

    private fun sectionHeader(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(accentGreen)
        setPadding(0, 24, 0, 10)
    }

    private fun statCell(label: String, value: String): TextView = TextView(this).apply {
        text = "$label: $value"
        textSize = 12f
        setTextColor(textSecondary)
        setPadding(0, 6, 12, 6)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun statRowOf(vararg cells: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        for (c in cells) addView(c)
    }

    private fun fullWidthStat(label: String, value: String): TextView = TextView(this).apply {
        text = "$label: $value"
        textSize = 12f
        setTextColor(textSecondary)
        setPadding(0, 6, 0, 6)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun buildGeneralTabView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val t = currentTorrent
        if (t == null) {
            container.addView(TextView(this).apply {
                text = "Loading..."
                textSize = 12f
                setTextColor(textSecondary)
            })
            return container
        }

        val ratio = if (t.downloadedBytes > 0) t.uploadedBytes.toDouble() / t.downloadedBytes.toDouble() else 0.0

        container.addView(sectionHeader("Transfer"))
        container.addView(statRowOf(
            statCell("Downloaded", formatBytes(t.downloadedBytes)),
            statCell("Uploaded", formatBytes(t.uploadedBytes)),
            statCell("Connections", "${t.connPeers}")
        ))
        container.addView(statRowOf(
            statCell("Download Speed", formatSpeed(t.downSpeedBytes)),
            statCell("Upload Speed", formatSpeed(t.upSpeedBytes)),
            statCell("Known Peers", "${t.knownPeers}")
        ))
        container.addView(statRowOf(
            statCell("Share Ratio", String.format("%.2f", ratio)),
            statCell("ETA", if (t.eta.isBlank()) "Unknown" else t.eta),
            statCell("Status", t.status.replaceFirstChar { it.uppercase() })
        ))

        container.addView(sectionHeader("Information"))

        val piecesText = pieceDataRaw?.let { raw ->
            if (raw.startsWith("ERROR")) "unavailable" else {
                val parts = raw.split("|")
                val have = parts.getOrNull(0) ?: "?"
                val total = parts.getOrNull(1) ?: "?"
                val extra = extraInfo
                if (extra != null) "$total x ${formatBytes(extra.pieceLength)} (have $have)" else "$have / $total"
            }
        } ?: "loading..."

        val privateText = when {
            extraInfo != null -> if (extraInfo!!.isPrivate) "Yes" else "No"
            extraInfoErr != null -> "unavailable"
            else -> "loading..."
        }

        container.addView(statRowOf(
            statCell("Total Size", formatBytes(t.totalBytes)),
            statCell("Pieces", piecesText),
            statCell("Private", privateText)
        ))

        val addedAt = dateAddedPrefs.getLong(t.hash, 0L)
        val addedText = if (addedAt > 0L) {
            java.text.SimpleDateFormat("M/d/yyyy h:mm a", java.util.Locale.getDefault()).format(java.util.Date(addedAt))
        } else {
            "Unknown"
        }
        container.addView(fullWidthStat("Added On (approx.)", addedText))

        val savePath = fileListData?.first ?: fileListErr?.let { "unavailable" } ?: "loading..."
        container.addView(fullWidthStat("Save Path", savePath))

        container.addView(fullWidthStat("Info Hash", t.hash))

        container.addView(sectionHeader("Notes"))

        val commentText = commentsPrefs.getString(t.hash, "") ?: ""
        val commentRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 4, 0, 12)
            isClickable = true
            setOnClickListener { showEditCommentDialog() }
        }
        commentRow.addView(TextView(this).apply {
            text = if (commentText.isBlank()) "No notes yet — tap to add one." else commentText
            textSize = 12f
            setTextColor(if (commentText.isBlank()) textMuted else textSecondary)
            setPadding(0, 2, 0, 2)
        })
        commentRow.addView(TextView(this).apply {
            text = "Edit"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentGreen)
            setPadding(0, 8, 0, 0)
        })
        container.addView(commentRow)

        return container
    }

    /** Purely local per-torrent note — see commentsPrefs field comment for why this can't be
     *  the .torrent file's own "comment" metadata (librqbit never surfaces that at runtime). */
    private fun showEditCommentDialog() {
        val t = currentTorrent ?: return

        val input = EditText(this).apply {
            setText(commentsPrefs.getString(t.hash, "") ?: "")
            setHint("Add a personal note about this torrent...")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
            gravity = android.view.Gravity.TOP
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Notes")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                commentsPrefs.edit().putString(t.hash, text).apply()
                if (selectedTab == "general") renderContent()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildPiecesTabText(): String {
        val raw = pieceDataRaw ?: return "Loading piece data..."
        if (raw.startsWith("ERROR")) return raw

        val parts = raw.split("|")
        if (parts.size < 3) return "No piece data available."

        val have = parts[0].toIntOrNull() ?: 0
        val total = parts[1].toIntOrNull() ?: 0
        val bits = parts[2]

        return "Pieces: $have / $total\n\n${buildPieceTriangle(bits)}"
    }

    /** Same centered-triangle (pyramid) layout as MainActivity's inline Pieces tab — row 1
     *  gets the first piece, row 2 the next 2, row 3 the next 3, and so on. */
    private fun buildPieceTriangle(bits: String): String {
        if (bits.isEmpty()) return ""

        var rowLength = 1
        var consumed = 0
        val rows = mutableListOf<String>()

        while (consumed < bits.length) {
            val end = minOf(consumed + rowLength, bits.length)
            val row = buildString {
                for (i in consumed until end) append(if (bits[i] == '1') '■' else '□')
            }
            rows.add(row)
            consumed = end
            rowLength++
        }

        val maxLen = rows.maxOf { it.length }
        return rows.joinToString("\n") { row ->
            val leftPad = (maxLen - row.length) / 2
            " ".repeat(leftPad) + row
        }
    }

    private fun buildPeersTabText(): String {
        if (peerDisplayRows.isEmpty() && peerSnapshot == null) return "Loading peer data..."
        if (peerDisplayRows.isEmpty()) return "No connected peers."

        return peerDisplayRows.joinToString("\n\n") { p ->
            val clientText = if (p.client.isBlank()) "Unknown client" else p.client
            val kindText = if (p.connKind.isBlank()) "" else " [${p.connKind}]"
            "${p.address}$kindText\n$clientText  •  ${p.state}\n↓ ${formatSpeed(p.downloaded)}   ↑ ${formatSpeed(p.uploaded)}"
        }
    }

    /** Picks a small emoji icon for a file's row in the Files tab, purely from its extension
     *  (no content sniffing — Android's MimeTypeMap is used elsewhere for actually opening a
     *  file, but for a quick visual cue here a simple extension table is enough and doesn't
     *  need the file to exist/be downloaded yet). Unrecognized extensions fall back to a
     *  generic file icon rather than guessing. */
    private fun fileTypeIcon(relativePath: String): String {
        val ext = relativePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp", "ts", "vob" -> "🎬"
            "mp3", "flac", "wav", "aac", "ogg", "oga", "m4a", "wma", "opus", "aiff" -> "🎵"
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff", "svg" -> "🖼️"
            "epub", "mobi", "azw", "azw3", "fb2", "djvu", "pdf", "cbz", "cbr" -> "📚"
            "txt", "srt", "sub", "ass", "ssa", "md", "log", "nfo", "doc", "docx", "rtf", "odt" -> "📄"
            else -> "📦"
        }
    }

    private fun buildFilesTabView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val error = fileListErr
        if (error != null) {
            container.addView(TextView(this).apply {
                text = "ERROR: $error"
                textSize = 12f
                setTextColor(textSecondary)
            })
            return container
        }

        val cached = fileListData
        if (cached == null) {
            container.addView(TextView(this).apply {
                text = "Loading files..."
                textSize = 12f
                setTextColor(textSecondary)
            })
            return container
        }

        val (outputFolder, files) = cached

        if (files.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No file information available yet."
                textSize = 12f
                setTextColor(textSecondary)
            })
            return container
        }

        val checkBoxes = mutableListOf<CheckBox>()

        for (file in files) {
            val isSelected = fileSelections.getOrPut(file.index) { file.included }

            val rowContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
                setBackgroundColor(Color.parseColor("#3A3A3A"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val checkbox = CheckBox(this).apply {
                isChecked = isSelected
            }
            checkbox.setOnCheckedChangeListener { _, checked -> fileSelections[file.index] = checked }
            checkBoxes.add(checkbox)

            val nameView = TextView(this).apply {
                text = "${fileTypeIcon(file.relativePath)} ${file.relativePath}\n${formatBytes(file.size)}"
                textSize = 12f
                setTextColor(if (file.included) Color.WHITE else textMuted)
                setPadding(12, 0, 0, 0)
                isClickable = file.included
                isFocusable = file.included
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            if (file.included) {
                nameView.setOnClickListener { openFile(outputFolder, file) }
            }

            // Long-press any file row (whether or not it's downloaded yet) for Open/Share/
            // Rename/Delete. rowContainer rather than just nameView so the whole row responds,
            // not just the text.
            rowContainer.isLongClickable = true
            rowContainer.setOnLongClickListener {
                showFileContextMenu(outputFolder, file)
                true
            }

            topRow.addView(checkbox)
            topRow.addView(nameView)
            rowContainer.addView(topRow)

            if (file.included) {
                val filePercent = if (file.totalPieces > 0) {
                    (file.havePieces.toDouble() / file.totalPieces.toDouble()) * 100.0
                } else 0.0

                rowContainer.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = (filePercent * 10).toInt().coerceIn(0, 1000)
                    progressTintList = ColorStateList.valueOf(accentGreen)
                    progressBackgroundTintList = ColorStateList.valueOf(trackDark)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8 }
                })

                rowContainer.addView(TextView(this).apply {
                    text = "${String.format("%.1f", filePercent)}%"
                    textSize = 10f
                    setTextColor(textMuted)
                    setPadding(0, 4, 0, 0)
                })
            }

            container.addView(rowContainer)
        }

        val selectRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        val selectAllBtn = Button(this).apply {
            text = "Select All"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val selectNoneBtn = Button(this).apply {
            text = "Select None"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        selectAllBtn.setOnClickListener {
            files.forEach { fileSelections[it.index] = true }
            checkBoxes.forEach { it.isChecked = true }
        }
        selectNoneBtn.setOnClickListener {
            files.forEach { fileSelections[it.index] = false }
            checkBoxes.forEach { it.isChecked = false }
        }
        selectRow.addView(selectAllBtn)
        selectRow.addView(selectNoneBtn)
        container.addView(selectRow)

        val updateSelectionBtn = Button(this).apply {
            text = "Update File Selection"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        updateSelectionBtn.setOnClickListener {
            val selectedIndices = fileSelections.filterValues { it }.keys
            if (selectedIndices.isEmpty()) {
                Toast.makeText(this, "Select at least one file.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val csv = selectedIndices.joinToString(",")
            thread {
                val result: String = try {
                    RustBridge.updateOnlyFiles(hash, csv)
                } catch (e: Throwable) {
                    "ERROR: ${e.message}"
                }
                runOnUiThread {
                    Toast.makeText(this, if (result == "OK") "File selection updated" else result, Toast.LENGTH_SHORT).show()
                }
                fetchFiles()
            }
        }
        container.addView(updateSelectionBtn)

        return container
    }

    // ---- Per-file long-press menu: Open / Share / Rename / Delete ----
    //
    // Open and Share are plain file operations — safe, no engine involvement. Rename and
    // Delete are more delicate: a torrent's files are identified by the exact name/path baked
    // into its info dict (that's literally part of what the info-hash is computed from), and
    // librqbit has no API to rename or repoint an individual file within an already-added
    // torrent (checked directly against the vendored source — the only per-file mutator is
    // updateOnlyFiles, which changes what's WANTED, not where it lives). So renaming or
    // deleting a file here first excludes it from the torrent's active file selection via
    // updateOnlyFiles, so the engine stops expecting it at its original path and won't try to
    // re-download it thinking it went missing — at the cost of that file no longer being
    // seeded, which both dialogs say plainly rather than silently.

    private fun showFileContextMenu(outputFolder: String, file: FileEntry) {
        val displayName = file.relativePath.substringAfterLast('/')
        val actions = arrayOf("Open", "Share", "Rename", "Delete")

        AlertDialog.Builder(this)
            .setTitle(displayName)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> if (file.included) openFile(outputFolder, file) else notDownloadedYet()
                    1 -> if (file.included) shareFile(outputFolder, file) else notDownloadedYet()
                    2 -> showRenameDialog(outputFolder, file)
                    3 -> confirmDeleteFile(outputFolder, file)
                }
            }
            .show()
    }

    private fun notDownloadedYet() {
        Toast.makeText(this, "This file hasn't downloaded yet.", Toast.LENGTH_SHORT).show()
    }

    private fun shareFile(outputFolder: String, file: FileEntry) {
        thread {
            val targetFile = File(outputFolder, file.relativePath)
            if (!targetFile.exists()) {
                runOnUiThread { Toast.makeText(this, "File not downloaded yet.", Toast.LENGTH_SHORT).show() }
                return@thread
            }

            val uri = try {
                FileProvider.getUriForFile(this, "com.example.torrentorv2.fileprovider", targetFile)
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Couldn't prepare file: ${e.message}", Toast.LENGTH_SHORT).show() }
                return@thread
            }

            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(targetFile.name.substringAfterLast('.', "").lowercase()) ?: "*/*"

            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(Intent.createChooser(intent, "Share ${targetFile.name}"))
                } catch (e: Throwable) {
                    Toast.makeText(this, "No app found to share this file.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Tells the engine to stop wanting this one file (keeping every other currently-included
     *  file selected as-is), so it won't notice the file is gone/renamed on its next check and
     *  try to re-fetch it. Best-effort — proceeds with the rename/delete either way, since a
     *  failed engine call here just means slightly stale engine-side bookkeeping, not data loss. */
    private fun excludeFileFromTorrent(indexToExclude: Int) {
        val files = fileListData?.second ?: return
        val remaining = files.filter { it.included && it.index != indexToExclude }.map { it.index }
        try {
            RustBridge.updateOnlyFiles(hash, remaining.joinToString(","))
        } catch (e: Throwable) {
        }
    }

    private fun showRenameDialog(outputFolder: String, file: FileEntry) {
        val oldName = file.relativePath.substringAfterLast('/')
        val parentPart = file.relativePath.substringBeforeLast('/', "")

        val input = EditText(this).apply {
            setText(oldName)
            setSelection(0, oldName.length)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename file")
            .setMessage(
                "Torrent files are identified by their original name internally, so renaming " +
                    "removes this file from the torrent's active selection — it stops being " +
                    "seeded, but the renamed copy stays on your device either way."
            )
            .setView(container)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank() || newName == oldName) return@setPositiveButton
                performRenameFile(outputFolder, file, parentPart, newName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performRenameFile(outputFolder: String, file: FileEntry, parentPart: String, newName: String) {
        val newRelativePath = if (parentPart.isBlank()) newName else "$parentPart/$newName"
        val src = File(outputFolder, file.relativePath)
        val dst = File(outputFolder, newRelativePath)

        if (dst.exists()) {
            Toast.makeText(this, "A file with that name already exists there.", Toast.LENGTH_SHORT).show()
            return
        }

        thread {
            excludeFileFromTorrent(file.index)

            val renamed = try {
                src.renameTo(dst)
            } catch (e: Throwable) {
                false
            }

            runOnUiThread {
                Toast.makeText(
                    this,
                    if (renamed) "Renamed to $newName" else "Couldn't rename the file on disk.",
                    Toast.LENGTH_SHORT
                ).show()
                fetchFiles()
            }
        }
    }

    private fun confirmDeleteFile(outputFolder: String, file: FileEntry) {
        val displayName = file.relativePath.substringAfterLast('/')

        AlertDialog.Builder(this)
            .setTitle("Delete file")
            .setMessage(
                "Delete \"$displayName\" from your device? It will also be removed from this " +
                    "torrent's active selection, so it won't be re-downloaded automatically. " +
                    "This can't be undone."
            )
            .setPositiveButton("Delete") { _, _ -> performDeleteFile(outputFolder, file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDeleteFile(outputFolder: String, file: FileEntry) {
        thread {
            excludeFileFromTorrent(file.index)

            val target = File(outputFolder, file.relativePath)
            val deleted = try {
                !target.exists() || target.delete()
            } catch (e: Throwable) {
                false
            }

            runOnUiThread {
                Toast.makeText(
                    this,
                    if (deleted) "Deleted" else "Couldn't delete the file on disk.",
                    Toast.LENGTH_SHORT
                ).show()
                fetchFiles()
            }
        }
    }

    private fun buildTrackersTabView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        container.addView(buildNetworkSummaryCard())

        val error = extraInfoErr
        if (error != null) {
            container.addView(buildTrackerRow("ERROR", error, isError = true))
            return container
        }

        val extra = extraInfo
        if (extra == null) {
            container.addView(buildTrackerRow("Loading trackers...", null, isError = false))
            return container
        }

        if (extra.trackers.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No trackers in this torrent's metadata (it may rely on DHT for peer discovery)."
                textSize = 12f
                setTextColor(textSecondary)
                setPadding(0, 8, 0, 0)
            })
            return container
        }

        for (tracker in extra.trackers) {
            container.addView(buildTrackerRow(tracker, null, isError = false))
        }

        container.addView(TextView(this).apply {
            text = "Per-tracker peer counts and announce errors aren't shown — librqbit's " +
                "engine doesn't expose live tracker responses, only the tracker list itself."
            textSize = 10f
            setTextColor(Color.parseColor("#777777"))
            setPadding(0, 12, 0, 0)
        })

        return container
    }

    private fun buildNetworkSummaryCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(cardBg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }

        val parts = cachedNetworkStatusRaw.split("|")
        val ok = parts.getOrNull(0) == "OK"

        if (!ok) {
            card.addView(TextView(this).apply {
                text = "Network status unavailable."
                textSize = 12f
                setTextColor(textMuted)
            })
            return card
        }

        val dhtEnabled = parts.getOrNull(1) == "1"
        val dhtNodes = parts.getOrNull(2) ?: "?"
        val lsdEnabled = parts.getOrNull(3) == "1"

        fun addSummaryLine(label: String, working: Boolean?, detail: String) {
            if (card.childCount > 0) {
                card.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                        topMargin = 12
                        bottomMargin = 4
                    }
                    setBackgroundColor(Color.parseColor("#3A3A3A"))
                })
            }

            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            line.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            line.addView(TextView(this).apply {
                text = when (working) {
                    true -> "Working"
                    false -> "Off"
                    null -> "Not supported"
                }
                textSize = 12f
                setTextColor(
                    when (working) {
                        true -> statusGreenColor
                        false -> textMuted
                        null -> textMuted
                    }
                )
            })
            card.addView(line)

            if (detail.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(textMuted)
                    setPadding(0, 2, 0, 0)
                })
            }
        }

        addSummaryLine("DHT", dhtEnabled, if (dhtEnabled) "Nodes in routing table: $dhtNodes" else "")
        addSummaryLine("LSD (Local Peer Discovery)", lsdEnabled, "")
        addSummaryLine("PeX", null, "Not implemented by this engine")

        return card
    }

    private fun buildTrackerRow(title: String, subtitle: String?, isError: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(cardBg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        row.addView(TextView(this).apply {
            text = title
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (isError) Color.parseColor("#F44336") else Color.WHITE)
        })

        if (!subtitle.isNullOrBlank()) {
            row.addView(TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(textMuted)
                setPadding(0, 4, 0, 0)
            })
        }

        return row
    }

    // Comment is only ever known if this torrent was added (via a .torrent file, since a
    // magnet link never carries one) after the comment-capture feature existed — see the
    // commentPrefs field comment. Not every .torrent file sets one either way, so "no comment"
    // is the normal case, not an error — this just says so plainly instead of showing an
    // empty box.
    private fun buildCommentTabView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        container.addView(sectionHeader("Torrent Comment"))

        val comment = commentPrefs.getString(hash, null)

        val body = TextView(this).apply {
            textSize = 13f
            setPadding(20, 8, 20, 20)
            setTextIsSelectable(!comment.isNullOrBlank())
            if (comment.isNullOrBlank()) {
                text = "No comment available for this torrent.\n\n" +
                    "This can mean the torrent's .torrent file simply didn't set one (most " +
                    "don't), it was added as a magnet link (magnets never carry a comment), " +
                    "or it was added before this app started capturing comments."
                setTextColor(textMuted)
            } else {
                text = comment
                setTextColor(textSecondary)
            }
        }
        container.addView(body)

        if (!comment.isNullOrBlank()) {
            val copyBtn = Button(this).apply {
                text = "Copy Comment"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = 20; topMargin = 4 }
            }
            copyBtn.setOnClickListener { copyToClipboard("Comment", comment) }
            container.addView(copyBtn)
        }

        return container
    }

    // ---- Actions ----

    private fun togglePauseResume() {
        val t = currentTorrent ?: return
        val goingToPause = t.status != "paused"
        thread {
            try {
                if (goingToPause) RustBridge.pauseTorrent(hash) else RustBridge.resumeTorrent(hash)
                AppLog.info(if (goingToPause) "Paused: ${t.name}" else "Resumed: ${t.name}")
            } catch (e: Throwable) {
                AppLog.warning("Failed to ${if (goingToPause) "pause" else "resume"} ${t.name}: ${e.message}")
            }
            pollHeader()
        }
    }

    private fun confirmDelete() {
        val name = currentTorrent?.name ?: "this torrent"
        val options = arrayOf(
            "Remove torrent (keep downloaded files)",
            "Remove torrent and delete files (permanent)"
        )

        AlertDialog.Builder(this)
            .setTitle("Delete torrent")
            .setItems(options) { _, which ->
                val deleteFiles = which == 1
                thread {
                    try {
                        RustBridge.deleteTorrent(hash, deleteFiles)
                        AppLog.info("Removed torrent: $name (deleteFiles=$deleteFiles)")
                        commentsPrefs.edit().remove(hash).apply()
                    } catch (e: Throwable) {
                        AppLog.warning("Failed to remove torrent $name: ${e.message}")
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Torrent removed", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOverflowMenu() {
        val options = arrayOf("Copy Hash", "Copy Magnet Link", "Open Folder", "Statistics", "Move Location...")

        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToClipboard("Info hash", hash)
                    1 -> copyToClipboard("Magnet link", buildMagnetLink())
                    2 -> openTorrentFolder()
                    3 -> showStatisticsDialog()
                    4 -> startMoveLocationFlow()
                }
            }
            .show()
    }

    // ---- Move Location ----
    //
    // librqbit has no in-place "relocate this torrent's files" API (checked directly against
    // the vendored source: output_folder is a plain immutable field baked into the torrent at
    // add-time, read-only everywhere else, and the per-torrent storage backend captures it
    // once and never re-reads it). So "moving" a torrent here means: move the files on disk
    // ourselves, then remove the torrent from the engine (WITHOUT deleting files — there's
    // nothing left at the old folder to delete) and re-add it pointed at the new folder using
    // a magnet link built from its own hash+name. The re-add briefly needs the network to
    // resolve metadata again, and the moved files get recognized/re-verified rather than
    // re-downloaded — the same mechanism that already lets every torrent resume from disk on
    // a normal app restart. If the torrent was paused before the move, it's paused again
    // right after the re-add, honoring the app's "a paused torrent never auto-resumes" rule.

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestAllFilesAccessIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Throwable) {
            }
        }
    }

    private fun showFolderBrowserDialog(startDir: File, onChosen: (String) -> Unit) {
        fun showFor(dir: File) {
            val subdirs = (dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: arrayOf())
                .sortedBy { it.name.lowercase() }

            val hasParent = dir.parentFile != null
            val items = mutableListOf<String>()
            if (hasParent) items.add(".. (up one level)")
            items.addAll(subdirs.map { "📁 ${it.name}" })

            AlertDialog.Builder(this)
                .setTitle(dir.absolutePath)
                .setItems(items.toTypedArray()) { _, which ->
                    if (hasParent && which == 0) {
                        showFor(dir.parentFile!!)
                    } else {
                        val offset = if (hasParent) 1 else 0
                        val chosen = subdirs.getOrNull(which - offset)
                        if (chosen != null) showFor(chosen)
                    }
                }
                .setPositiveButton("Move Here") { _, _ -> onChosen(dir.absolutePath) }
                .setNeutralButton("New Folder") { _, _ -> showNewFolderDialog(dir) { showFor(dir) } }
                .setNegativeButton("Cancel", null)
                .show()
        }

        showFor(if (startDir.exists() && startDir.isDirectory) startDir else Environment.getExternalStorageDirectory())
    }

    /** Prompts for a name and creates a new subfolder inside parentDir, then calls
     *  onDone() either way (success, failure, or cancel) so the caller can just re-show the
     *  folder browser at the same spot — the newly created folder (if it worked) shows up in
     *  that refreshed listing like any other folder. */
    private fun showNewFolderDialog(parentDir: File, onDone: () -> Unit) {
        val input = EditText(this).apply {
            hint = "Folder name"
        }
        val padded = FrameLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("New folder in ${parentDir.name.ifBlank { parentDir.absolutePath }}")
            .setView(padded)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() ->
                        Toast.makeText(this, "Folder name can't be empty.", Toast.LENGTH_SHORT).show()
                    name.contains("/") || name.contains("\\") ->
                        Toast.makeText(this, "Folder name can't contain / or \\.", Toast.LENGTH_SHORT).show()
                    File(parentDir, name).exists() ->
                        Toast.makeText(this, "A folder named \"$name\" already exists.", Toast.LENGTH_SHORT).show()
                    File(parentDir, name).mkdir() ->
                        Toast.makeText(this, "Created \"$name\"", Toast.LENGTH_SHORT).show()
                    else ->
                        Toast.makeText(this, "Couldn't create that folder here.", Toast.LENGTH_SHORT).show()
                }
                onDone()
            }
            .setNegativeButton("Cancel") { _, _ -> onDone() }
            .show()
    }

    /**
     * Always fetches the file list fresh right here (instead of relying on fileListData
     * already being populated from whichever tab happens to be open) and chains straight
     * into the folder browser as soon as it arrives — a single tap always gets you to the
     * picker. The old version silently no-op'd behind a toast when fileListData wasn't
     * already cached (e.g. opened from the Peers/Pieces/Trackers tab), which is why the
     * folder browser sometimes didn't appear to open at all.
     */
    private fun startMoveLocationFlow() {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccessIfNeeded()
            Toast.makeText(
                this,
                "Grant \"All files access\" for TorrentOrV2, then try Move Location again.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("Move Location")
            .setMessage("Loading this torrent's file list...")
            .setCancelable(false)
            .create()
        loadingDialog.show()

        thread {
            val raw: String = try {
                RustBridge.getTorrentFiles(hash)
            } catch (e: Throwable) {
                "ERROR|${e.message}"
            }
            val parsed = if (!raw.startsWith("ERROR|")) parseFilesResponse(raw) else null

            runOnUiThread {
                loadingDialog.dismiss()

                if (parsed == null) {
                    Toast.makeText(this, "Couldn't load this torrent's file list — try again.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                fileListData = parsed
                fileListErr = null

                val oldFolder = parsed.first
                val externalRoot = Environment.getExternalStorageDirectory()

                // Starting the browser at oldFolder's parent only makes sense when oldFolder
                // is somewhere under public/shared storage. When it's the app's own private
                // internal storage instead (e.g. this torrent was saved before a custom
                // folder was ever set — see the "save path is wrong" fix), Android won't let
                // this app list anything above it (those directories belong to the system/
                // other apps), so climbing "up one level" from there is a dead end that can
                // never reach Downloads or anywhere else public. Start at the public storage
                // root instead in that case, so there's always a way out to a real folder.
                val startDir = if (oldFolder.startsWith(externalRoot.absolutePath)) {
                    File(oldFolder).parentFile ?: externalRoot
                } else {
                    externalRoot
                }

                showFolderBrowserDialog(startDir) { newFolder ->
                    if (File(newFolder).absolutePath == File(oldFolder).absolutePath) {
                        Toast.makeText(this, "That's already this torrent's folder.", Toast.LENGTH_SHORT).show()
                    } else {
                        confirmAndMove(oldFolder, newFolder)
                    }
                }
            }
        }
    }

    private fun confirmAndMove(oldFolder: String, newFolder: String) {
        AlertDialog.Builder(this)
            .setTitle("Move torrent location")
            .setMessage(
                "librqbit has no built-in \"move\" — this app will move the files on disk to:\n\n$newFolder\n\n" +
                    "then remove and re-add the torrent pointed at the new folder. The moved files should be " +
                    "recognized and re-verified rather than re-downloaded (the same way every torrent already " +
                    "resumes from disk on a normal app restart). This needs a brief moment of network access to " +
                    "resolve the torrent's metadata again, and the torrent will be paused while it happens."
            )
            .setPositiveButton("Move") { _, _ -> performMove(oldFolder, newFolder) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performMove(oldFolder: String, newFolder: String) {
        val wasPaused = currentTorrent?.status == "paused"
        val magnetUri = buildMagnetLink()
        val files = fileListData?.second ?: emptyList()

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Moving...")
            .setMessage("Moving files, please wait.")
            .setCancelable(false)
            .create()
        progressDialog.show()

        thread {
            try {
                RustBridge.pauseTorrent(hash)
            } catch (e: Throwable) {
            }

            File(newFolder).mkdirs()

            var moveError: String? = null
            for (file in files) {
                val src = File(oldFolder, file.relativePath)
                val dst = File(newFolder, file.relativePath)
                if (!src.exists()) continue // e.g. a deselected file that was never downloaded

                dst.parentFile?.mkdirs()
                val moved = try {
                    src.renameTo(dst)
                } catch (e: Throwable) {
                    false
                }

                if (!moved) {
                    // Cross-volume move (e.g. internal storage -> SD card) — renameTo fails
                    // across filesystems, so fall back to a streamed copy then delete the
                    // original.
                    try {
                        src.copyTo(dst, overwrite = true)
                        src.delete()
                    } catch (e: Throwable) {
                        moveError = "Couldn't move ${file.relativePath}: ${e.message}"
                        break
                    }
                }
            }

            if (moveError != null) {
                val err = moveError
                runOnUiThread {
                    progressDialog.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Move failed")
                        .setMessage(
                            "$err\n\nAny files already moved are at:\n$newFolder\n\n" +
                                "The torrent itself wasn't changed — it's just paused. You can retry, or move " +
                                "the remaining files back manually."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
                return@thread
            }

            // Files are moved — the old folder now has nothing left in it for this torrent,
            // so deleteFiles=false is correct here (there's nothing to delete).
            try {
                RustBridge.deleteTorrent(hash, false)
            } catch (e: Throwable) {
            }

            val listResult: String = try {
                RustBridge.listOnlyAddMagnet(magnetUri)
            } catch (e: Throwable) {
                "ERROR|${e.message}"
            }

            if (!listResult.startsWith("OK|")) {
                runOnUiThread {
                    progressDialog.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Move: files relocated, but re-add failed")
                        .setMessage(
                            "The files are now at:\n$newFolder\n\nbut re-adding the torrent failed (this step " +
                                "needs a moment of network access to resolve its metadata again). Add it back " +
                                "manually with this magnet link, pointed at that folder:\n\n$magnetUri"
                        )
                        .setPositiveButton("Copy Magnet Link") { _, _ -> copyToClipboard("Magnet link", magnetUri) }
                        .setNegativeButton("Close", null)
                        .show()
                }
                return@thread
            }

            val token = listResult.substringAfter("OK|").substringBefore("\n")

            // Preserve the same file selection the torrent already had, rather than letting
            // a bare re-add implicitly download everything again.
            val onlyFilesCsv = files.filter { it.included }.map { it.index }.joinToString(",")

            val confirmResult: String = try {
                RustBridge.confirmAdd(token, onlyFilesCsv, newFolder)
            } catch (e: Throwable) {
                "ADD ERROR: ${e.message}"
            }

            if (wasPaused) {
                try {
                    RustBridge.pauseTorrent(hash)
                } catch (e: Throwable) {
                }
            }

            // Clear this screen's stale per-hash caches so General/Files re-fetch fresh data
            // against the new folder instead of showing what's now out of date.
            fileListData = null
            fileListErr = null
            pieceDataRaw = null

            runOnUiThread {
                progressDialog.dismiss()
                if (confirmResult.startsWith("METADATA RESOLVED")) {
                    Toast.makeText(this, "Moved to $newFolder", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Move result: $confirmResult", Toast.LENGTH_LONG).show()
                }
                fetchFiles()
                pollHeader()
                if (selectedTab == "general") renderContent()
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun buildMagnetLink(): String {
        val name = currentTorrent?.name ?: ""
        val encodedName = Uri.encode(name)
        return "magnet:?xt=urn:btih:$hash&dn=$encodedName"
    }

    private fun openTorrentFolder() {
        val cachedFolder = fileListData?.first
        if (cachedFolder != null) {
            openFolder(cachedFolder)
            return
        }
        thread {
            val raw: String = try {
                RustBridge.getTorrentFiles(hash)
            } catch (e: Throwable) {
                "ERROR|${e.message}"
            }
            val parsed = if (!raw.startsWith("ERROR|")) parseFilesResponse(raw) else null
            runOnUiThread {
                if (parsed != null) {
                    openFolder(parsed.first)
                } else {
                    Toast.makeText(this, "Couldn't determine the save folder.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openFolder(path: String) {
        thread {
            val dir = File(path)
            if (!dir.exists()) {
                runOnUiThread { Toast.makeText(this, "Folder not found yet.", Toast.LENGTH_SHORT).show() }
                return@thread
            }

            val uri = try {
                FileProvider.getUriForFile(this, "com.example.torrentorv2.fileprovider", dir)
            } catch (e: Throwable) {
                null
            }

            runOnUiThread {
                if (uri == null) {
                    Toast.makeText(this, "Couldn't open folder:\n$path", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                val primaryIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "resource/folder")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val opened = try {
                    startActivity(primaryIntent)
                    true
                } catch (e: Throwable) {
                    false
                }
                if (opened) return@runOnUiThread

                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(fallbackIntent)
                } catch (e: Throwable) {
                    Toast.makeText(this, "No file manager app found to open this folder.\n$path", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openFile(outputFolder: String, file: FileEntry) {
        thread {
            val targetFile = File(outputFolder, file.relativePath)
            if (!targetFile.exists()) {
                runOnUiThread { Toast.makeText(this, "File not downloaded yet.", Toast.LENGTH_SHORT).show() }
                return@thread
            }

            val uri = try {
                FileProvider.getUriForFile(this, "com.example.torrentorv2.fileprovider", targetFile)
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Couldn't prepare file: ${e.message}", Toast.LENGTH_SHORT).show() }
                return@thread
            }

            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(targetFile.name.substringAfterLast('.', "").lowercase()) ?: "*/*"

            runOnUiThread {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(intent)
                } catch (e: Throwable) {
                    Toast.makeText(this, "No app found to open this file.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatTorrentStatsText(): String {
        val t = currentTorrent ?: return "No data available (torrent may have been removed)."
        val ratio = if (t.downloadedBytes > 0) t.uploadedBytes.toDouble() / t.downloadedBytes.toDouble() else 0.0
        return buildString {
            appendLine("Downloaded: ${formatBytes(t.downloadedBytes)}")
            appendLine("Uploaded: ${formatBytes(t.uploadedBytes)}")
            append("Share Ratio: ${String.format("%.2f", ratio)}")
        }
    }

    private fun showStatisticsDialog() {
        val messageView = TextView(this).apply {
            textSize = 15f
            setPadding(48, 32, 48, 32)
            text = formatTorrentStatsText()
        }

        var dialogRunning = true
        val updateHandler = Handler(Looper.getMainLooper())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Statistics — ${currentTorrent?.name ?: ""}")
            .setView(messageView)
            .setPositiveButton("Close", null)
            .create()

        val updateTask = object : Runnable {
            override fun run() {
                if (!dialogRunning) return
                messageView.text = formatTorrentStatsText()
                updateHandler.postDelayed(this, 3000L)
            }
        }

        dialog.setOnDismissListener {
            dialogRunning = false
            updateHandler.removeCallbacksAndMessages(null)
        }

        dialog.show()
        updateHandler.postDelayed(updateTask, 3000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        mainHandler.removeCallbacksAndMessages(null)
    }
}