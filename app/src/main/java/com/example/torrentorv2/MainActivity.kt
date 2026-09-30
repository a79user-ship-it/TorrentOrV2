package com.example.torrentorv2

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

data class TorrentInfo(
    val hash: String,
    val name: String,
    val status: String,
    val percent: Double,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val uploadedBytes: Long,
    val downSpeedBytes: Long,
    val upSpeedBytes: Long,
    val eta: String,
    val connPeers: Long,
    val knownPeers: Long
)

data class PeerRow(
    val address: String,
    val client: String,
    val state: String,
    val connKind: String,
    val downloaded: Long,
    val uploaded: Long
)

data class PeerSnapshotCache(
    val timestampMs: Long,
    val rows: Map<String, PeerRow>
)

data class PendingFileRow(
    val index: Int,
    val path: String,
    val size: Long,
    var selected: Boolean = true
)

data class FileEntry(
    val index: Int,
    val relativePath: String,
    val size: Long,
    val included: Boolean,
    val havePieces: Int = 0,
    val totalPieces: Int = 0
)

data class ExtraInfo(
    val pieceLength: Long,
    val isPrivate: Boolean,
    val trackers: List<String>
)

// RssFeedEntry, RssItem, and RssManager now live in RssStore.kt / RssManager.kt — RSS
// management moved out to its own window (RssActivity) instead of a dialog here, so the
// data model needs to be shared across activities rather than private to this file.

private fun formatSpeedForGraph(bytesPerSec: Long): String {
    if (bytesPerSec < 1024) return "$bytesPerSec B/s"
    val units = arrayOf("KB/s", "MB/s", "GB/s")
    var value = bytesPerSec.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return String.format("%.1f %s", value, units[unitIndex])
}

/**
 * A minimal qBittorrent-style live speed graph: two auto-scaling polylines (download,
 * upload) over a rolling window of the most recent samples. Fed one (down, up) sample per
 * poll via addSample() — this view holds no polling logic of its own, MainActivity's
 * existing 1s refresh loop drives it.
 */
class SpeedGraphView(context: Context) : View(context) {
    private val maxSamples = 120 // ~2 minutes at the app's 1s poll interval

    private val downHistory = ArrayDeque<Long>()
    private val upHistory = ArrayDeque<Long>()

    private val downColor = Color.parseColor("#4CAF50")
    private val upColor = Color.parseColor("#2196F3")

    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = downColor
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = upColor
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        isAntiAlias = true
    }

    fun addSample(downBytesPerSec: Long, upBytesPerSec: Long) {
        downHistory.addLast(downBytesPerSec)
        upHistory.addLast(upBytesPerSec)
        while (downHistory.size > maxSamples) downHistory.removeFirst()
        while (upHistory.size > maxSamples) upHistory.removeFirst()
        invalidate()
    }

    private fun drawSeries(canvas: Canvas, history: ArrayDeque<Long>, paint: Paint, maxValue: Float, w: Float, h: Float) {
        if (history.size < 2) return

        val path = Path()
        val stepX = w / (maxSamples - 1).toFloat()
        val startIndex = maxSamples - history.size

        history.forEachIndexed { idx, value ->
            val x = (startIndex + idx) * stepX
            val y = h - (value / maxValue) * h * 0.95f - h * 0.02f
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        canvas.drawPath(path, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Color.parseColor("#1A1A1A"))

        val gridRows = 4
        for (i in 0..gridRows) {
            val y = h * i / gridRows
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val maxValue = maxOf(
            downHistory.maxOrNull() ?: 0L,
            upHistory.maxOrNull() ?: 0L,
            1L
        ).toFloat()

        drawSeries(canvas, downHistory, downPaint, maxValue, w, h)
        drawSeries(canvas, upHistory, upPaint, maxValue, w, h)

        val currentDown = downHistory.lastOrNull() ?: 0L
        val currentUp = upHistory.lastOrNull() ?: 0L

        labelPaint.color = downColor
        canvas.drawText("↓ ${formatSpeedForGraph(currentDown)}", 16f, 34f, labelPaint)
        labelPaint.color = upColor
        canvas.drawText("↑ ${formatSpeedForGraph(currentUp)}", 16f, 68f, labelPaint)

        labelPaint.color = Color.parseColor("#999999")
        val maxLabel = "Peak: ${formatSpeedForGraph(maxValue.toLong())}"
        canvas.drawText(maxLabel, w - labelPaint.measureText(maxLabel) - 16f, 34f, labelPaint)
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var magnetInput: EditText
    private lateinit var magnetResultView: TextView
    private lateinit var hashAddInput: EditText
    private lateinit var hashResultView: TextView
    private lateinit var torrentFileResultView: TextView
    private lateinit var serviceResultView: TextView
    private lateinit var torrentsContainer: LinearLayout
    private lateinit var pendingAddContainer: LinearLayout
    private lateinit var downloadFolderLabel: TextView
    private lateinit var storageInfoView: TextView
    private lateinit var dhtCheckBox: CheckBox
    private lateinit var lsdCheckBox: CheckBox
    private lateinit var wifiOnlyCheckBox: CheckBox
    private lateinit var speedGraphView: SpeedGraphView
    private lateinit var torrentSearchInput: EditText
    private lateinit var sortButton: Button
    private lateinit var filterButton: Button
    private lateinit var selectModeButton: Button
    private lateinit var selectionActionsRow: LinearLayout
    private lateinit var selectedCountLabel: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoRefreshRunning = true

    private val expandedHashes = mutableSetOf<String>()
    private val selectedTabByHash = mutableMapOf<String, String>()
    private val pieceDataCache = mutableMapOf<String, String>()
    private val peerSnapshotCache = mutableMapOf<String, PeerSnapshotCache>()
    private val peerDisplayCache = mutableMapOf<String, List<PeerRow>>()
    private val fileListCache = mutableMapOf<String, Pair<String, List<FileEntry>>>()
    private val fileListError = mutableMapOf<String, String>()
    private val fileSelectionOverrides = mutableMapOf<String, MutableMap<Int, Boolean>>()
    private val extraInfoCache = mutableMapOf<String, ExtraInfo>()
    private val extraInfoError = mutableMapOf<String, String>()

    /** Raw "OK|dhtEnabled|dhtNodes|lsdEnabled" (or "ERROR|...") from getNetworkStatus(),
     *  refreshed every 3s by startNetworkStatusAutoRefresh() — read by the Trackers tab's
     *  DHT/LSD/PeX summary rows so they don't each need their own polling call. */
    private var cachedNetworkStatusRaw: String = ""

    /** Latest known TorrentInfo per hash, refreshed every 1s poll — read by the per-torrent
     *  Statistics dialog so it doesn't need its own polling call. */
    private val lastTorrentsByHash = mutableMapOf<String, TorrentInfo>()

    /** Empty string means "use the app's default download folder". Persisted in SharedPreferences. */
    private var downloadFolderOverride: String = ""

    /** Per-hash "first seen" timestamp, used for the Date Added sort. Backed by its own
     *  SharedPreferences file so it survives app restarts. Rust/librqbit doesn't track a real
     *  add-time anywhere in its public API, so a torrent restored from librqbit's own session
     *  persistence gets stamped the first time this app process notices it again — the closest
     *  approximation of "add time" available without a Rust-side change. */
    private val dateAddedPrefs by lazy { getSharedPreferences("torrentor_date_added", Context.MODE_PRIVATE) }

    /** User-typed personal notes per torrent (edited from the details screen's General tab),
     *  keyed by info hash — only read here so a torrent's note gets cleaned up when it's
     *  removed, same as dateAddedPrefs above. */
    private val commentsPrefs by lazy { getSharedPreferences("torrentor_comments", Context.MODE_PRIVATE) }

    /** Torrent list sort/filter/search state (Kotlin-side only — RustBridge.getTorrentInfo()
     *  always returns the full unsorted list; these are applied client-side in
     *  applySortFilterSearch() so they can be changed instantly without a re-poll). */
    private var searchQuery: String = ""
    private var sortField: String = "dateAdded" // name | size | status | progress | dateAdded
    private var sortAscending: Boolean = false
    private var statusFilterKey: String = "all" // all | downloading | seeding | paused | initializing | error

    /** Multi-select state: when on, torrent cards show a checkbox instead of only offering
     *  the existing "all torrents" batch actions or one-at-a-time buttons. Cleared whenever
     *  select mode is turned off, and any hash removed from selectedForBatch when its
     *  torrent is deleted (individually or as part of a batch). */
    private var selectionModeEnabled: Boolean = false
    private val selectedForBatch = mutableSetOf<String>()

    private var pendingToken: String? = null
    private var pendingFetching = false
    private var pendingFiles: MutableList<PendingFileRow> = mutableListOf()
    private var pendingError: String? = null

    /** The torrent's "comment" field (from the .torrent file itself, or blank for a magnet —
     *  magnets never carry one), captured while the file-selection screen is still open. Held
     *  here only until confirmPendingAdd() knows the torrent's real hash, at which point it's
     *  written into torrentCommentPrefs so the Comment tab can read it back later — librqbit
     *  has no runtime API that exposes comment for an already-added torrent, so this add-time
     *  capture is the only chance to ever see it. */
    private var pendingComment: String? = null

    /** hash -> comment, persisted so it survives app restarts. Only ever populated for
     *  torrents added from this point forward (see pendingComment above) — a torrent added
     *  before this feature existed just won't have an entry, and the Comment tab shows
     *  "No comment" for it, same as it would for a magnet with no comment at all. */
    private val commentPrefs by lazy {
        getSharedPreferences("torrentor_comments", Context.MODE_PRIVATE)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val torrentFilePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                torrentFileResultView.text = "No file selected."
                return@registerForActivityResult
            }

            torrentFileResultView.text = "Reading file..."

            thread {
                val bytes = readBytesFromUri(uri)

                if (bytes == null) {
                    runOnUiThread {
                        torrentFileResultView.text = "ERROR: couldn't read the selected file"
                    }
                    return@thread
                }

                runOnUiThread { beginTorrentFileAdd(bytes) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        downloadFolderOverride = getSharedPreferences("torrentor_prefs", Context.MODE_PRIVATE)
            .getString("download_folder", "") ?: ""

        RssStore.ensureLoaded(this)
        AppLog.init(applicationContext)

        requestNotificationPermissionIfNeeded()
        requestIgnoreBatteryOptimizationsIfNeeded()
        startTorrentService()

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun wrapWidthParams() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val title = TextView(this).apply {
            text = "TorrentOrV2"
            textSize = 28f
            layoutParams = wrapWidthParams()
        }

        statusView = TextView(this).apply {
            text = "Rust engine: starting (via service)..."
            textSize = 18f
            setPadding(0, 32, 0, 0)
            layoutParams = wrapWidthParams()
        }

        magnetInput = EditText(this).apply {
            hint = "Paste magnet link here"
            setPadding(24, 24, 24, 24)
            layoutParams = wrapWidthParams().apply { topMargin = 24 }
        }

        val addButton = Button(this).apply {
            text = "Add Magnet"
            layoutParams = wrapWidthParams()
        }

        magnetResultView = TextView(this).apply {
            text = ""
            textSize = 16f
            setPadding(0, 24, 0, 0)
            layoutParams = wrapWidthParams()
        }

        hashAddInput = EditText(this).apply {
            hint = "Or enter a 40-character info hash"
            setPadding(24, 24, 24, 24)
            layoutParams = wrapWidthParams().apply { topMargin = 24 }
        }

        val addHashButton = Button(this).apply {
            text = "Add via Info Hash"
            layoutParams = wrapWidthParams()
        }

        hashResultView = TextView(this).apply {
            text = ""
            textSize = 16f
            setPadding(0, 24, 0, 0)
            layoutParams = wrapWidthParams()
        }

        val chooseFileButton = Button(this).apply {
            text = "Choose .torrent File"
            layoutParams = wrapWidthParams().apply { topMargin = 24 }
        }

        torrentFileResultView = TextView(this).apply {
            text = ""
            textSize = 16f
            setPadding(0, 24, 0, 0)
            layoutParams = wrapWidthParams()
        }

        downloadFolderLabel = TextView(this).apply {
            textSize = 14f
            setPadding(0, 24, 0, 0)
            layoutParams = wrapWidthParams()
        }

        val changeFolderButton = Button(this).apply {
            text = "Change Download Folder"
            textSize = 13f
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        storageInfoView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 16, 0, 0)
            layoutParams = wrapWidthParams()
        }

        val networkHeader = TextView(this).apply {
            text = "Network"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 32, 0, 0)
            layoutParams = wrapWidthParams()
        }

        val networkPrefs = getSharedPreferences("torrentor_prefs", Context.MODE_PRIVATE)

        dhtCheckBox = CheckBox(this).apply {
            text = "DHT (find peers without a tracker)"
            textSize = 14f
            isChecked = networkPrefs.getBoolean("dht_enabled", true)
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        lsdCheckBox = CheckBox(this).apply {
            text = "Local Peer Discovery / LSD (find peers on the same WiFi/LAN)"
            textSize = 14f
            isChecked = networkPrefs.getBoolean("lsd_enabled", true)
            layoutParams = wrapWidthParams()
        }

        val networkNote = TextView(this).apply {
            text = "DHT/LSD changes apply the next time you fully close and reopen the app " +
                "(the engine only reads these at startup). Peer Exchange (PEX) isn't " +
                "supported by the underlying torrent engine, so there's no toggle for it here."
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 8, 0, 0)
            layoutParams = wrapWidthParams()
        }

        wifiOnlyCheckBox = CheckBox(this).apply {
            text = "Wi-Fi Only (pause active downloads on mobile data)"
            textSize = 14f
            isChecked = WifiOnlyPolicy.isEnabled(this@MainActivity)
            layoutParams = wrapWidthParams().apply { topMargin = 16 }
        }

        val wifiOnlyNote = TextView(this).apply {
            text = "Applies immediately, no restart needed. Only pauses torrents that are " +
                "currently live — a torrent you paused yourself stays paused either way, and " +
                "won't be auto-resumed by this even back on WiFi."
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 8, 0, 0)
            layoutParams = wrapWidthParams()
        }

        val networkFeaturesButton = Button(this).apply {
            text = "Network Features"
            textSize = 13f
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        val speedLimitsButton = Button(this).apply {
            text = "Speed Limits"
            textSize = 13f
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        val themeButton = Button(this).apply {
            text = "Theme: ${AppTheme.currentTheme(this@MainActivity).label}"
            textSize = 13f
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        pendingAddContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = wrapWidthParams().apply { topMargin = 24 }
            visibility = View.GONE
        }

        val serviceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wrapWidthParams().apply {
                topMargin = 24
            }
        }

        val startServiceButton = Button(this).apply {
            text = "Start Service"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val stopServiceButton = Button(this).apply {
            text = "Stop Service"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        serviceRow.addView(startServiceButton)
        serviceRow.addView(stopServiceButton)

        serviceResultView = TextView(this).apply {
            text = "Service auto-started"
            textSize = 16f
            setPadding(0, 16, 0, 0)
            layoutParams = wrapWidthParams()
        }

        val globalStatsButton = Button(this).apply {
            text = "Global Statistics"
            layoutParams = wrapWidthParams().apply { topMargin = 16 }
        }

        val speedGraphLabel = TextView(this).apply {
            text = "Speed"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 32, 0, 0)
            layoutParams = wrapWidthParams()
        }

        speedGraphView = SpeedGraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (180 * resources.displayMetrics.density).toInt()
            ).apply { topMargin = 12 }
        }

        val rssFeedsButton = Button(this).apply {
            text = "RSS Feeds"
            layoutParams = wrapWidthParams().apply { topMargin = 24 }
        }

        val logButton = Button(this).apply {
            text = "Execution Log"
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        val searchButton = Button(this).apply {
            text = "Search"
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        val torrentsHeader = TextView(this).apply {
            text = "Torrents"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 32, 0, 0)
            layoutParams = wrapWidthParams()
        }

        val torrentSearchRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = wrapWidthParams().apply { topMargin = 12 }
        }

        torrentSearchInput = EditText(this).apply {
            hint = "Search torrents by name"
            setPadding(24, 24, 24, 24)
            layoutParams = wrapWidthParams()
        }

        val sortFilterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        sortButton = Button(this).apply {
            text = "Sort: Date Added ↓"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        filterButton = Button(this).apply {
            text = "Filter: All"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        sortFilterRow.addView(sortButton)
        sortFilterRow.addView(filterButton)

        val batchActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        val pauseAllButton = Button(this).apply {
            text = "Pause All"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val resumeAllButton = Button(this).apply {
            text = "Resume All"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val deleteAllButton = Button(this).apply {
            text = "Delete All"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        batchActionsRow.addView(pauseAllButton)
        batchActionsRow.addView(resumeAllButton)
        batchActionsRow.addView(deleteAllButton)

        selectModeButton = Button(this).apply {
            text = "Select Multiple"
            textSize = 12f
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        selectionActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
            visibility = View.GONE
        }

        selectedCountLabel = TextView(this).apply {
            text = "0 selected"
            textSize = 12f
            setPadding(0, 0, 0, 8)
        }

        val selectQuickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wrapWidthParams()
        }
        val selectAllVisibleBtn = Button(this).apply {
            text = "Select All"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val selectNoneBtn2 = Button(this).apply {
            text = "Select None"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        selectQuickRow.addView(selectAllVisibleBtn)
        selectQuickRow.addView(selectNoneBtn2)

        val selectActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }
        val pauseSelectedBtn = Button(this).apply {
            text = "Pause Selected"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val resumeSelectedBtn = Button(this).apply {
            text = "Resume Selected"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val deleteSelectedBtn = Button(this).apply {
            text = "Delete Selected"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        selectActionRow.addView(pauseSelectedBtn)
        selectActionRow.addView(resumeSelectedBtn)
        selectActionRow.addView(deleteSelectedBtn)

        selectionActionsRow.addView(selectedCountLabel)
        selectionActionsRow.addView(selectQuickRow)
        selectionActionsRow.addView(selectActionRow)

        torrentSearchRow.addView(torrentSearchInput)
        torrentSearchRow.addView(sortFilterRow)
        torrentSearchRow.addView(batchActionsRow)
        torrentSearchRow.addView(selectModeButton)
        torrentSearchRow.addView(selectionActionsRow)

        torrentsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = wrapWidthParams()
        }

        contentLayout.addView(title)
        contentLayout.addView(statusView)
        contentLayout.addView(magnetInput)
        contentLayout.addView(addButton)
        contentLayout.addView(magnetResultView)
        contentLayout.addView(hashAddInput)
        contentLayout.addView(addHashButton)
        contentLayout.addView(hashResultView)
        contentLayout.addView(chooseFileButton)
        contentLayout.addView(torrentFileResultView)
        contentLayout.addView(downloadFolderLabel)
        contentLayout.addView(changeFolderButton)
        contentLayout.addView(storageInfoView)
        contentLayout.addView(networkHeader)
        contentLayout.addView(dhtCheckBox)
        contentLayout.addView(lsdCheckBox)
        contentLayout.addView(networkNote)
        contentLayout.addView(wifiOnlyCheckBox)
        contentLayout.addView(wifiOnlyNote)
        contentLayout.addView(networkFeaturesButton)
        contentLayout.addView(speedLimitsButton)
        contentLayout.addView(themeButton)
        contentLayout.addView(pendingAddContainer)
        contentLayout.addView(serviceRow)
        contentLayout.addView(serviceResultView)
        contentLayout.addView(globalStatsButton)
        contentLayout.addView(speedGraphLabel)
        contentLayout.addView(speedGraphView)
        contentLayout.addView(rssFeedsButton)
        contentLayout.addView(logButton)
        contentLayout.addView(searchButton)
        contentLayout.addView(torrentsHeader)
        contentLayout.addView(torrentSearchRow)
        contentLayout.addView(torrentsContainer)

        val verticalScroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(contentLayout)
        }

        setContentView(verticalScroll)

        updateDownloadFolderLabel()

        changeFolderButton.setOnClickListener {
            if (!hasAllFilesAccess()) {
                requestAllFilesAccessIfNeeded()
                Toast.makeText(
                    this,
                    "Grant \"All files access\" for TorrentOrV2, then tap this button again.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val start = if (downloadFolderOverride.isNotBlank()) {
                File(downloadFolderOverride)
            } else {
                Environment.getExternalStorageDirectory()
            }
            showFolderBrowserDialog(start)
        }

        addButton.setOnClickListener {
            val magnetUri = magnetInput.text.toString().trim()

            if (magnetUri.isEmpty()) {
                magnetResultView.text = "Please paste a magnet link first."
                return@setOnClickListener
            }

            beginMagnetOrHashAdd(magnetUri, magnetResultView)
        }

        addHashButton.setOnClickListener {
            val hash = hashAddInput.text.toString().trim()

            if (hash.length != 40) {
                hashResultView.text = "Info hash must be exactly 40 characters."
                return@setOnClickListener
            }

            beginMagnetOrHashAdd(hash, hashResultView)
        }

        chooseFileButton.setOnClickListener {
            torrentFilePicker.launch("*/*")
        }

        startServiceButton.setOnClickListener {
            startTorrentService()
            serviceResultView.text = "Service start requested"
        }

        stopServiceButton.setOnClickListener {
            val intent = Intent(this, TorrentService::class.java)
            stopService(intent)
            serviceResultView.text = "Service stop requested"
        }

        globalStatsButton.setOnClickListener {
            showGlobalStatsDialog()
        }

        dhtCheckBox.setOnCheckedChangeListener { _, checked ->
            networkPrefs.edit().putBoolean("dht_enabled", checked).apply()
            AppLog.info("DHT ${if (checked) "enabled" else "disabled"} (takes effect on next app restart)")
            Toast.makeText(this, "DHT ${if (checked) "enabled" else "disabled"} — restart the app to apply", Toast.LENGTH_LONG).show()
        }

        lsdCheckBox.setOnCheckedChangeListener { _, checked ->
            networkPrefs.edit().putBoolean("lsd_enabled", checked).apply()
            AppLog.info("LSD ${if (checked) "enabled" else "disabled"} (takes effect on next app restart)")
            Toast.makeText(this, "LSD ${if (checked) "enabled" else "disabled"} — restart the app to apply", Toast.LENGTH_LONG).show()
        }

        wifiOnlyCheckBox.setOnCheckedChangeListener { _, checked ->
            WifiOnlyPolicy.setEnabled(this, checked)
            WifiOnlyPolicy.reevaluate(this)
            AppLog.info("Wi-Fi Only ${if (checked) "enabled" else "disabled"}")
            Toast.makeText(
                this,
                "Wi-Fi Only ${if (checked) "enabled" else "disabled"} — applying now",
                Toast.LENGTH_SHORT
            ).show()
        }

        networkFeaturesButton.setOnClickListener {
            showNetworkStatusDialog()
        }

        speedLimitsButton.setOnClickListener {
            showSpeedLimitsDialog()
        }

        themeButton.setOnClickListener {
            val labels = AppTheme.THEMES.map { it.label }.toTypedArray()
            val currentIndex = AppTheme.THEMES.indexOfFirst { it.id == AppTheme.currentThemeId(this) }.coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle("Choose a theme")
                .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                    AppTheme.setThemeId(this, AppTheme.THEMES[which].id)
                    dialog.dismiss()
                    // Rebuilds this screen's views from scratch so the new accent color (torrent
                    // card progress bars) shows immediately — this app has no XML layouts/data
                    // binding to push a live theme change through, so recreate() is the simplest
                    // way to apply it without a full app restart. TorrentDetailActivity picks up
                    // the new theme on its own next time it's opened, since it reads
                    // AppTheme.accentColor() fresh each time it builds its views.
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        rssFeedsButton.setOnClickListener {
            startActivity(Intent(this, RssActivity::class.java))
        }

        logButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        searchButton.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        torrentSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                refreshTorrentCardsFromCache()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        sortButton.setOnClickListener { showSortDialog() }
        filterButton.setOnClickListener { showFilterDialog() }

        pauseAllButton.setOnClickListener {
            val hashes = lastTorrentsByHash.keys.toList()
            if (hashes.isEmpty()) {
                Toast.makeText(this, "No torrents to pause.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            thread {
                for (h in hashes) {
                    try { RustBridge.pauseTorrent(h) } catch (e: Throwable) { }
                }
                AppLog.info("Paused all torrents (${hashes.size})")
                refreshTorrentInfo()
            }
        }

        resumeAllButton.setOnClickListener {
            val hashes = lastTorrentsByHash.keys.toList()
            if (hashes.isEmpty()) {
                Toast.makeText(this, "No torrents to resume.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            thread {
                for (h in hashes) {
                    try { RustBridge.resumeTorrent(h) } catch (e: Throwable) { }
                }
                AppLog.info("Resumed all torrents (${hashes.size})")
                refreshTorrentInfo()
            }
        }

        deleteAllButton.setOnClickListener {
            val hashes = lastTorrentsByHash.keys.toList()
            if (hashes.isEmpty()) {
                Toast.makeText(this, "No torrents to delete.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val options = arrayOf(
                "Remove all torrents (keep downloaded files)",
                "Remove all torrents and delete files (permanent)"
            )

            AlertDialog.Builder(this)
                .setTitle("Delete ALL torrents (${hashes.size})")
                .setItems(options) { _, which ->
                    val deleteFiles = which == 1

                    thread {
                        for (h in hashes) {
                            try {
                                RustBridge.deleteTorrent(h, deleteFiles)
                            } catch (e: Throwable) {
                            }
                            expandedHashes.remove(h)
                            selectedTabByHash.remove(h)
                            pieceDataCache.remove(h)
                            peerSnapshotCache.remove(h)
                            peerDisplayCache.remove(h)
                            fileListCache.remove(h)
                            fileListError.remove(h)
                            fileSelectionOverrides.remove(h)
                            extraInfoCache.remove(h)
                            extraInfoError.remove(h)
                            dateAddedPrefs.edit().remove(h).apply()
                            commentsPrefs.edit().remove(h).apply()
                            selectedForBatch.remove(h)
                        }
                        AppLog.info("Removed all torrents (${hashes.size}, deleteFiles=$deleteFiles)")
                        refreshTorrentInfo()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        selectModeButton.setOnClickListener {
            selectionModeEnabled = !selectionModeEnabled
            selectModeButton.text = if (selectionModeEnabled) "Cancel Select" else "Select Multiple"
            selectionActionsRow.visibility = if (selectionModeEnabled) View.VISIBLE else View.GONE
            if (!selectionModeEnabled) selectedForBatch.clear()
            updateSelectedCountLabel()
            refreshTorrentCardsFromCache()
        }

        selectAllVisibleBtn.setOnClickListener {
            val visible = applySortFilterSearch(lastTorrentsByHash.values.toList())
            selectedForBatch.addAll(visible.map { it.hash })
            updateSelectedCountLabel()
            refreshTorrentCardsFromCache()
        }

        selectNoneBtn2.setOnClickListener {
            selectedForBatch.clear()
            updateSelectedCountLabel()
            refreshTorrentCardsFromCache()
        }

        pauseSelectedBtn.setOnClickListener {
            val hashes = selectedForBatch.toList()
            if (hashes.isEmpty()) {
                Toast.makeText(this, "No torrents selected.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            thread {
                for (h in hashes) {
                    try { RustBridge.pauseTorrent(h) } catch (e: Throwable) { }
                }
                AppLog.info("Paused ${hashes.size} selected torrent(s)")
                refreshTorrentInfo()
            }
        }

        resumeSelectedBtn.setOnClickListener {
            val hashes = selectedForBatch.toList()
            if (hashes.isEmpty()) {
                Toast.makeText(this, "No torrents selected.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            thread {
                for (h in hashes) {
                    try { RustBridge.resumeTorrent(h) } catch (e: Throwable) { }
                }
                AppLog.info("Resumed ${hashes.size} selected torrent(s)")
                refreshTorrentInfo()
            }
        }

        deleteSelectedBtn.setOnClickListener {
            val hashes = selectedForBatch.toList()
            if (hashes.isEmpty()) {
                Toast.makeText(this, "No torrents selected.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val options = arrayOf(
                "Remove selected torrents (keep downloaded files)",
                "Remove selected torrents and delete files (permanent)"
            )

            AlertDialog.Builder(this)
                .setTitle("Delete ${hashes.size} selected torrent(s)")
                .setItems(options) { _, which ->
                    val deleteFiles = which == 1

                    thread {
                        for (h in hashes) {
                            try {
                                RustBridge.deleteTorrent(h, deleteFiles)
                            } catch (e: Throwable) {
                            }
                            expandedHashes.remove(h)
                            selectedTabByHash.remove(h)
                            pieceDataCache.remove(h)
                            peerSnapshotCache.remove(h)
                            peerDisplayCache.remove(h)
                            fileListCache.remove(h)
                            fileListError.remove(h)
                            fileSelectionOverrides.remove(h)
                            extraInfoCache.remove(h)
                            extraInfoError.remove(h)
                            dateAddedPrefs.edit().remove(h).apply()
                            commentsPrefs.edit().remove(h).apply()
                            selectedForBatch.remove(h)
                        }
                        AppLog.info("Removed ${hashes.size} selected torrent(s) (deleteFiles=$deleteFiles)")
                        runOnUiThread { updateSelectedCountLabel() }
                        refreshTorrentInfo()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        startAutoRefresh()
        startPiecesAutoRefresh()
        startPeersAutoRefresh()
        startFilesAutoRefresh()
        startStorageAutoRefresh()
        startNetworkStatusAutoRefresh()
        startRssAutoRefresh()

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    // ---- Handling magnet links / .torrent files opened from outside the app ----

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        // Prevent re-handling the same intent again (e.g. on rotation / re-entry).
        intent.action = null

        when (uri.scheme?.lowercase()) {
            "magnet" -> {
                Toast.makeText(this, "Opening magnet link...", Toast.LENGTH_SHORT).show()
                beginMagnetOrHashAdd(uri.toString(), magnetResultView)
            }
            else -> {
                Toast.makeText(this, "Opening torrent file...", Toast.LENGTH_SHORT).show()
                thread {
                    val bytes = readBytesFromUri(uri)
                    if (bytes == null) {
                        runOnUiThread {
                            Toast.makeText(this, "Couldn't read the .torrent file.", Toast.LENGTH_SHORT).show()
                        }
                        return@thread
                    }
                    runOnUiThread { beginTorrentFileAdd(bytes) }
                }
            }
        }
    }

    private fun readBytesFromUri(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Throwable) {
            null
        }
    }

    // ---- Custom save folder (Feature 3) ----
    //
    // librqbit's native Rust file I/O needs a real filesystem path, not a SAF
    // content:// tree URI, so picking a folder outside the app's own storage
    // requires the MANAGE_EXTERNAL_STORAGE ("All files access") permission plus
    // a hand-rolled folder browser (no Storage Access Framework picker here).

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
                // Some OEM builds block this intent outright; nothing more we can do from code.
            }
        }
    }

    private fun showFolderBrowserDialog(startDir: File) {
        // A plain AlertDialog only has 3 button slots (positive/neutral/negative), which is
        // why "New Folder..." used to be squeezed into the list as item 0 instead of sitting
        // with the other actions. This builds the whole thing as one custom view instead —
        // a scrollable list of just the actual folders (plus ".. up one level") on top, and
        // all 4 actions (Use This Folder / Use App Default / New Folder / Cancel) together as
        // a 2x2 button grid underneath — so "New Folder" reads as an action alongside the
        // others, not as a strange first entry in the folder list.
        fun showFor(dir: File) {
            val subdirs = (dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: arrayOf())
                .sortedBy { it.name.lowercase() }
            val hasParent = dir.parentFile != null

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val listLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 8, 32, 8)
            }

            fun addRow(label: String, onClick: () -> Unit) {
                listLayout.addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 14f
                    setPadding(16, 28, 16, 28)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                })
            }

            if (hasParent) {
                addRow(".. (up one level)") { showFor(dir.parentFile!!) }
            }
            for (sub in subdirs) {
                addRow("📁 ${sub.name}") { showFor(sub) }
            }
            if (subdirs.isEmpty() && !hasParent) {
                listLayout.addView(TextView(this@MainActivity).apply {
                    text = "No subfolders here."
                    textSize = 13f
                    setTextColor(Color.parseColor("#999999"))
                    setPadding(16, 24, 16, 24)
                })
            }

            val scroll = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.density * 280).toInt()
                )
                addView(listLayout)
            }
            root.addView(scroll)

            val dialog = AlertDialog.Builder(this)
                .setTitle(dir.absolutePath)
                .setView(root)
                .create()

            fun actionButton(label: String, onClick: () -> Unit): Button = Button(this@MainActivity).apply {
                text = label
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onClick() }
            }

            val buttonRow1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 8, 24, 4)
                addView(actionButton("Use This Folder") {
                    setDownloadFolderOverride(dir.absolutePath)
                    dialog.dismiss()
                })
                addView(actionButton("Use App Default") {
                    setDownloadFolderOverride("")
                    dialog.dismiss()
                })
            }
            val buttonRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 4, 24, 16)
                addView(actionButton("Create New Folder") {
                    showNewFolderDialog(dir) { showFor(dir) }
                    dialog.dismiss()
                })
                addView(actionButton("Cancel") { dialog.dismiss() })
            }
            root.addView(buttonRow1)
            root.addView(buttonRow2)

            dialog.show()
        }

        showFor(if (startDir.exists() && startDir.isDirectory) startDir else Environment.getExternalStorageDirectory())
    }

    /** Prompts for a name and creates a new subfolder inside parentDir, then calls onDone()
     *  either way (success, failure, or cancel) so the caller can just re-show the folder
     *  browser at the same spot — a newly created folder shows up in that refreshed listing
     *  like any other folder. */
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

    private fun setDownloadFolderOverride(path: String) {
        downloadFolderOverride = path

        getSharedPreferences("torrentor_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("download_folder", path)
            .apply()

        updateDownloadFolderLabel()
        updateStorageInfo()

        Toast.makeText(
            this,
            if (path.isBlank()) "New torrents will save to the app's default folder" else "New torrents will save to: $path",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateDownloadFolderLabel() {
        downloadFolderLabel.text = if (downloadFolderOverride.isBlank()) {
            "Save folder: App default"
        } else {
            "Save folder: $downloadFolderOverride"
        }
    }

    // ---- Storage space (used / free / total on the disk holding the active save folder) ----

    private fun currentStorageDir(): File {
        return if (downloadFolderOverride.isNotBlank()) {
            File(downloadFolderOverride)
        } else {
            File(filesDir, "torrentor_test")
        }
    }

    private fun updateStorageInfo() {
        thread {
            // StatFs needs a path that actually exists on the target filesystem — the save
            // folder itself may not have been created yet (e.g. before the first torrent),
            // so walk up to the nearest existing ancestor.
            var probe: File? = currentStorageDir()
            while (probe != null && !probe.exists()) {
                probe = probe.parentFile
            }
            val target = probe ?: Environment.getExternalStorageDirectory()

            val text = try {
                val statFs = StatFs(target.absolutePath)
                val blockSize = statFs.blockSizeLong
                val totalBytes = statFs.blockCountLong * blockSize
                val freeBytes = statFs.availableBlocksLong * blockSize
                val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0)

                "Storage — Used: ${formatBytes(usedBytes)}  •  Free: ${formatBytes(freeBytes)}  •  Total: ${formatBytes(totalBytes)}"
            } catch (e: Throwable) {
                "Storage: unavailable"
            }

            runOnUiThread {
                storageInfoView.text = text
            }
        }
    }

    private fun startStorageAutoRefresh() {
        val interval = 3000L

        val task = object : Runnable {
            override fun run() {
                if (!autoRefreshRunning) return
                updateStorageInfo()
                mainHandler.postDelayed(this, interval)
            }
        }

        updateStorageInfo()
        mainHandler.postDelayed(task, interval)
    }

    /** Keeps cachedNetworkStatusRaw fresh every 3s so the Trackers tab's DHT/LSD/PeX summary
     *  rows update live without each expanded torrent card running its own polling loop. */
    private fun startNetworkStatusAutoRefresh() {
        val interval = 3000L

        fun refreshOnce() {
            thread {
                val raw: String = try {
                    RustBridge.getNetworkStatus()
                } catch (e: Throwable) {
                    "ERROR|${e.message}"
                }
                runOnUiThread {
                    cachedNetworkStatusRaw = raw
                    refreshTorrentInfo()
                }
            }
        }

        val task = object : Runnable {
            override fun run() {
                if (!autoRefreshRunning) return
                refreshOnce()
                mainHandler.postDelayed(this, interval)
            }
        }

        refreshOnce()
        mainHandler.postDelayed(task, interval)
    }

    // ---- Shared add flows (used by the buttons above AND by handleIncomingIntent) ----

    private fun beginMagnetOrHashAdd(uriOrHash: String, clearView: TextView) {
        clearView.text = ""
        AppLog.info("Adding magnet/hash: $uriOrHash")

        startPendingAdd(fetchingLabel = "Fetching file list...") {
            RustBridge.addMagnet(uriOrHash, downloadFolderOverride)
        }

        thread {
            val result: String = try {
                RustBridge.listOnlyAddMagnet(uriOrHash)
            } catch (e: Throwable) {
                "ERROR|${e.message}"
            }

            handlePendingAddResult(result)
        }
    }

    /**
     * Must be called from the UI thread. Sets up the pending-add state (and marks
     * pendingFetching = true) synchronously before spawning the background lookup —
     * this matters because listOnlyAddTorrentFile is pure local parsing (no network),
     * so it can finish faster than a runOnUiThread post would land, which used to
     * cause handlePendingAddResult to see pendingFetching still false and silently
     * discard the result, leaving the screen stuck on "Fetching file list...".
     */
    private fun beginTorrentFileAdd(bytes: ByteArray) {
        torrentFileResultView.text = "Fetching file list..."
        AppLog.info("Adding .torrent file (${bytes.size} bytes)")
        startPendingAdd(fetchingLabel = "Fetching file list...") {
            RustBridge.addTorrentFile(bytes, downloadFolderOverride)
        }

        thread {
            val result: String = try {
                RustBridge.listOnlyAddTorrentFile(bytes)
            } catch (e: Throwable) {
                "ERROR|${e.message}"
            }

            handlePendingAddResult(result)
        }
    }

    /** Shows a result message in all three result views, then clears it after a few seconds. */
    private fun showResultAndAutoClear(message: String) {
        magnetResultView.text = message
        hashResultView.text = message
        torrentFileResultView.text = message

        mainHandler.postDelayed({
            if (magnetResultView.text == message) magnetResultView.text = ""
            if (hashResultView.text == message) hashResultView.text = ""
            if (torrentFileResultView.text == message) torrentFileResultView.text = ""
        }, 4000L)
    }

    // ---- File-selection ("pending add") flow ----

    private fun startPendingAdd(fetchingLabel: String, skipAction: () -> String) {
        pendingToken = null
        pendingFetching = true
        pendingFiles = mutableListOf()
        pendingError = null
        var skipped = false

        renderPendingAdd(fetchingLabel) {
            if (!skipped) {
                skipped = true
                val token = pendingToken
                pendingFetching = false
                renderPendingAddHidden()

                thread {
                    val result: String = try {
                        skipAction()
                    } catch (e: Throwable) {
                        "ERROR: ${e.message}"
                    }

                    runOnUiThread {
                        showResultAndAutoClear(result)
                    }

                    refreshTorrentInfo()

                    if (token != null) {
                        try {
                            RustBridge.cancelPendingAdd(token)
                        } catch (e: Throwable) {
                        }
                    }
                }
            }
        }
    }

    private fun handlePendingAddResult(result: String) {
        if (!pendingFetching) {
            if (result.startsWith("OK|")) {
                val token = result.substringAfter("OK|").substringBefore("\n")
                try {
                    RustBridge.cancelPendingAdd(token)
                } catch (e: Throwable) {
                }
            }
            return
        }

        pendingFetching = false

        if (result.startsWith("ERROR|")) {
            pendingError = result.substringAfter("ERROR|")
            runOnUiThread { renderPendingAddError(pendingError ?: "Unknown error") }
            return
        }

        val lines = result.lines()
        if (lines.isEmpty() || !lines[0].startsWith("OK|")) {
            runOnUiThread { renderPendingAddError("Unexpected response from engine.") }
            return
        }

        val token = lines[0].substringAfter("OK|")
        pendingToken = token

        // Line 1 is always "COMMENT|<comment or blank>" now — a fixed second line the Rust
        // side always sends right after the OK| line, whether or not this torrent has one.
        val commentLine = lines.getOrNull(1)
        pendingComment = if (commentLine != null && commentLine.startsWith("COMMENT|")) {
            commentLine.substringAfter("COMMENT|").ifBlank { null }
        } else {
            null
        }
        val fileLines = if (commentLine != null && commentLine.startsWith("COMMENT|")) {
            lines.drop(2)
        } else {
            lines.drop(1)
        }

        val files = mutableListOf<PendingFileRow>()
        for (line in fileLines) {
            if (line.isBlank()) continue
            val parts = line.split("|")
            if (parts.size < 3) continue
            val index = parts[0].toIntOrNull() ?: continue
            val path = parts[1]
            val size = parts[2].toLongOrNull() ?: 0L
            files.add(PendingFileRow(index, path, size, selected = true))
        }
        pendingFiles = files

        runOnUiThread { renderPendingAddFileList() }
    }

    private fun formatBytesStatic(bytes: Long): String {
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

    private fun renderPendingAddHidden() {
        pendingAddContainer.visibility = View.GONE
        pendingAddContainer.removeAllViews()
    }

    private fun renderPendingAdd(label: String, onSkip: () -> Unit) {
        pendingAddContainer.visibility = View.VISIBLE
        pendingAddContainer.removeAllViews()

        val statusText = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
        }

        val skipButton = Button(this).apply {
            text = "Skip - Download All Now"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        skipButton.setOnClickListener { onSkip() }

        val cancelButton = Button(this).apply {
            text = "Cancel"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        cancelButton.setOnClickListener { cancelPendingAddFlow() }

        pendingAddContainer.addView(statusText)
        pendingAddContainer.addView(skipButton)
        pendingAddContainer.addView(cancelButton)
    }

    private fun renderPendingAddError(message: String) {
        pendingAddContainer.visibility = View.VISIBLE
        pendingAddContainer.removeAllViews()

        val errorText = TextView(this).apply {
            text = "ERROR: $message"
            textSize = 16f
            setTextColor(Color.WHITE)
        }

        val dismissButton = Button(this).apply {
            text = "Dismiss"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        dismissButton.setOnClickListener {
            renderPendingAddHidden()
        }

        pendingAddContainer.addView(errorText)
        pendingAddContainer.addView(dismissButton)
    }

    private fun renderPendingAddFileList() {
        pendingAddContainer.visibility = View.VISIBLE
        pendingAddContainer.removeAllViews()

        val headerText = TextView(this).apply {
            text = "Select files to download (${pendingFiles.size} files)"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        pendingAddContainer.addView(headerText)

        val checkBoxes = mutableListOf<CheckBox>()

        for (file in pendingFiles) {
            val row = CheckBox(this).apply {
                text = "${file.path}  (${formatBytesStatic(file.size)})"
                textSize = 13f
                setTextColor(Color.WHITE)
                isChecked = file.selected
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
            }
            row.setOnCheckedChangeListener { _, checked -> file.selected = checked }
            checkBoxes.add(row)
            pendingAddContainer.addView(row)
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
            pendingFiles.forEach { it.selected = true }
            checkBoxes.forEach { it.isChecked = true }
        }
        selectNoneBtn.setOnClickListener {
            pendingFiles.forEach { it.selected = false }
            checkBoxes.forEach { it.isChecked = false }
        }

        selectRow.addView(selectAllBtn)
        selectRow.addView(selectNoneBtn)
        pendingAddContainer.addView(selectRow)

        val downloadRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        val downloadSelectedBtn = Button(this).apply {
            text = "Download Selected"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val downloadAllBtn = Button(this).apply {
            text = "Download All"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        downloadSelectedBtn.setOnClickListener {
            val selectedIndices = pendingFiles.filter { it.selected }.map { it.index }
            if (selectedIndices.isEmpty()) {
                Toast.makeText(this, "Select at least one file.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmPendingAdd(selectedIndices.joinToString(","))
        }

        downloadAllBtn.setOnClickListener {
            confirmPendingAdd("")
        }

        downloadRow.addView(downloadSelectedBtn)
        downloadRow.addView(downloadAllBtn)
        pendingAddContainer.addView(downloadRow)

        val cancelButton = Button(this).apply {
            text = "Cancel"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        cancelButton.setOnClickListener { cancelPendingAddFlow() }
        pendingAddContainer.addView(cancelButton)
    }

    private fun cancelPendingAddFlow() {
        val token = pendingToken
        pendingToken = null
        pendingFiles = mutableListOf()
        pendingComment = null
        pendingFetching = false
        renderPendingAddHidden()

        if (token != null) {
            thread {
                try {
                    RustBridge.cancelPendingAdd(token)
                } catch (e: Throwable) {
                }
            }
        }
    }

    private fun confirmPendingAdd(onlyFilesCsv: String) {
        val token = pendingToken ?: return

        renderPendingAdd("Adding torrent...") { }
        pendingAddContainer.getChildAt(1)?.visibility = View.GONE
        pendingAddContainer.getChildAt(2)?.visibility = View.GONE

        val commentToSave = pendingComment

        thread {
            val result: String = try {
                RustBridge.confirmAdd(token, onlyFilesCsv, downloadFolderOverride)
            } catch (e: Throwable) {
                "ADD ERROR: ${e.message}"
            }

            if (result.startsWith("METADATA RESOLVED") && !commentToSave.isNullOrBlank()) {
                val hash = result.substringAfter("hash: ").substringBefore(")")
                if (hash.isNotBlank()) {
                    commentPrefs.edit().putString(hash, commentToSave).apply()
                }
            }

            pendingToken = null
            pendingFiles = mutableListOf()
            pendingComment = null

            runOnUiThread {
                showResultAndAutoClear(result)
                renderPendingAddHidden()
            }

            refreshTorrentInfo()
        }
    }

    // ---- Existing app functionality ----

    private fun startTorrentService() {
        val intent = Intent(this, TorrentService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Throwable) {
                // Some OEM builds block this intent outright; nothing more we can do from code.
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun buildMagnetLink(t: TorrentInfo): String {
        val encodedName = Uri.encode(t.name)
        return "magnet:?xt=urn:btih:${t.hash}&dn=$encodedName"
    }

    private fun parseTorrents(raw: String): List<TorrentInfo> {
        if (raw.isBlank()) return emptyList()

        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 12) return@mapNotNull null

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
            .toList()
    }

    private fun formatBytes(bytes: Long): String = formatBytesStatic(bytes)

    private fun formatSpeed(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"

    private fun rebuildTorrentCards(torrents: List<TorrentInfo>) {
        torrentsContainer.removeAllViews()

        if (torrents.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No torrents added yet"
                textSize = 16f
                setPadding(0, 24, 0, 0)
            }
            torrentsContainer.addView(emptyView)
            return
        }

        for (t in torrents) {
            torrentsContainer.addView(buildTorrentCard(t))
        }
    }

    private fun buildTorrentCard(t: TorrentInfo): View {
        val cardBackground = Color.parseColor("#2A2A2A")
        val cardSelectedBackground = Color.parseColor("#2F4A34") // subtle green tint, selection mode only
        val textPrimary = Color.WHITE
        val textSecondary = Color.parseColor("#CCCCCC")
        val accentGreen = AppTheme.accentColor(this)
        val trackDark = Color.parseColor("#555555")

        val isSelectedForBatch = selectionModeEnabled && selectedForBatch.contains(t.hash)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(if (isSelectedForBatch) cardSelectedBackground else cardBackground)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }

        if (selectionModeEnabled) {
            val selectCheckBox = CheckBox(this).apply {
                text = "Select"
                textSize = 13f
                setTextColor(textPrimary)
                isChecked = isSelectedForBatch
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
            }
            selectCheckBox.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedForBatch.add(t.hash) else selectedForBatch.remove(t.hash)
                updateSelectedCountLabel()
                refreshTorrentCardsFromCache()
            }
            card.addView(selectCheckBox)
        }

        val nameView = TextView(this).apply {
            text = t.name
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        }

        val statusView = TextView(this).apply {
            text = "${t.status}  •  ${String.format("%.1f", t.percent)}%  •  ${formatBytes(t.downloadedBytes)} / ${formatBytes(t.totalBytes)}"
            textSize = 14f
            setPadding(0, 8, 0, 0)
            setTextColor(textSecondary)
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = (t.percent * 10).toInt().coerceIn(0, 1000)
            progressTintList = ColorStateList.valueOf(accentGreen)
            progressBackgroundTintList = ColorStateList.valueOf(trackDark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }

        val etaSuffix = if (t.percent < 100.0 && t.eta.isNotBlank()) "   ETA: ${t.eta}" else ""
        val speedRow = TextView(this).apply {
            text = "↓ ${formatSpeed(t.downSpeedBytes)}   ↑ ${formatSpeed(t.upSpeedBytes)}   Peers: ${t.connPeers}/${t.knownPeers}$etaSuffix"
            textSize = 14f
            setPadding(0, 12, 0, 0)
            setTextColor(textSecondary)
        }

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }

        fun smallButton(label: String): Button = Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val pauseBtn = smallButton("Pause")
        val resumeBtn = smallButton("Resume")
        val deleteBtn = smallButton("Delete")
        val detailsBtn = smallButton("Details")

        actionRow.addView(pauseBtn)
        actionRow.addView(resumeBtn)
        actionRow.addView(deleteBtn)
        actionRow.addView(detailsBtn)

        val copyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        val copyHashBtn = smallButton("Copy Hash")
        val copyMagnetBtn = smallButton("Copy Magnet")
        val statsBtn = smallButton("Statistics")
        copyRow.addView(copyHashBtn)
        copyRow.addView(copyMagnetBtn)
        copyRow.addView(statsBtn)

        val folderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        val openFolderBtn = smallButton("Open Folder")
        folderRow.addView(openFolderBtn)

        copyHashBtn.setOnClickListener {
            copyToClipboard("Info hash", t.hash)
        }

        copyMagnetBtn.setOnClickListener {
            copyToClipboard("Magnet link", buildMagnetLink(t))
        }

        statsBtn.setOnClickListener {
            showTorrentStatsDialog(t.hash, t.name)
        }

        openFolderBtn.setOnClickListener {
            val cachedFolder = fileListCache[t.hash]?.first
            if (cachedFolder != null) {
                openFolder(cachedFolder)
            } else {
                thread {
                    val raw: String = try {
                        RustBridge.getTorrentFiles(t.hash)
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
        }

        pauseBtn.setOnClickListener {
            thread {
                try {
                    RustBridge.pauseTorrent(t.hash)
                    AppLog.info("Paused: ${t.name}")
                } catch (e: Throwable) {
                    AppLog.warning("Failed to pause ${t.name}: ${e.message}")
                }
                refreshTorrentInfo()
            }
        }

        resumeBtn.setOnClickListener {
            thread {
                try {
                    RustBridge.resumeTorrent(t.hash)
                    AppLog.info("Resumed: ${t.name}")
                } catch (e: Throwable) {
                    AppLog.warning("Failed to resume ${t.name}: ${e.message}")
                }
                refreshTorrentInfo()
            }
        }

        deleteBtn.setOnClickListener {
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
                            RustBridge.deleteTorrent(t.hash, deleteFiles)
                            AppLog.info("Removed torrent: ${t.name} (deleteFiles=$deleteFiles)")
                        } catch (e: Throwable) {
                            AppLog.warning("Failed to remove torrent ${t.name}: ${e.message}")
                        }
                        expandedHashes.remove(t.hash)
                        selectedTabByHash.remove(t.hash)
                        pieceDataCache.remove(t.hash)
                        peerSnapshotCache.remove(t.hash)
                        peerDisplayCache.remove(t.hash)
                        fileListCache.remove(t.hash)
                        fileListError.remove(t.hash)
                        fileSelectionOverrides.remove(t.hash)
                        extraInfoCache.remove(t.hash)
                        extraInfoError.remove(t.hash)
                        dateAddedPrefs.edit().remove(t.hash).apply()
                        commentsPrefs.edit().remove(t.hash).apply()
                        selectedForBatch.remove(t.hash)
                        refreshTorrentInfo()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        detailsBtn.setOnClickListener {
            openTorrentDetail(t.hash)
        }

        card.addView(nameView)
        card.addView(statusView)
        card.addView(progressBar)
        card.addView(speedRow)
        card.addView(actionRow)
        card.addView(copyRow)
        card.addView(folderRow)

        // Tapping anywhere else on the card also opens the full Torrent Details screen —
        // the per-torrent General/Files/Trackers/Peers/Pieces tabs used to expand in place
        // right here; they now live in TorrentDetailActivity instead. Skipped in selection
        // mode so a tap there toggles the checkbox instead of navigating away.
        if (!selectionModeEnabled) {
            card.isClickable = true
            card.setOnClickListener { openTorrentDetail(t.hash) }
        }

        return card
    }

    private fun openTorrentDetail(hash: String) {
        val intent = Intent(this, TorrentDetailActivity::class.java)
        intent.putExtra(TorrentDetailActivity.EXTRA_HASH, hash)
        startActivity(intent)
    }

    private fun buildGeneralTabText(t: TorrentInfo): String {
        val ratio = if (t.downloadedBytes > 0) {
            t.uploadedBytes.toDouble() / t.downloadedBytes.toDouble()
        } else {
            0.0
        }

        val extra = extraInfoCache[t.hash]
        val extraErr = extraInfoError[t.hash]

        return buildString {
            appendLine("Total Size: ${formatBytes(t.totalBytes)}")
            appendLine("Downloaded: ${formatBytes(t.downloadedBytes)}")
            appendLine("Uploaded: ${formatBytes(t.uploadedBytes)}")
            appendLine("Share Ratio: ${String.format("%.2f", ratio)}")
            appendLine("Download Speed: ${formatSpeed(t.downSpeedBytes)}")
            appendLine("Upload Speed: ${formatSpeed(t.upSpeedBytes)}")
            appendLine("ETA: ${if (t.eta.isBlank()) "Unknown" else t.eta}")
            appendLine("Connections: ${t.connPeers} (${t.knownPeers} known)")
            when {
                extra != null -> {
                    appendLine("Piece Size: ${formatBytes(extra.pieceLength)}")
                    appendLine("Private: ${if (extra.isPrivate) "Yes" else "No"}")
                }
                extraErr != null -> appendLine("Piece Size: unavailable ($extraErr)")
                else -> appendLine("Piece Size: loading...")
            }
            append("Info Hash: ${t.hash}")
        }
    }

    // ---- Statistics dialogs (per-torrent + global all-time), both auto-refresh every 3s ----

    private fun formatTorrentStatsText(hash: String): String {
        val t = lastTorrentsByHash[hash]
            ?: return "No data available (torrent may have been removed)."

        val ratio = if (t.downloadedBytes > 0) {
            t.uploadedBytes.toDouble() / t.downloadedBytes.toDouble()
        } else {
            0.0
        }

        return buildString {
            appendLine("Downloaded: ${formatBytes(t.downloadedBytes)}")
            appendLine("Uploaded: ${formatBytes(t.uploadedBytes)}")
            append("Share Ratio: ${String.format("%.2f", ratio)}")
        }
    }

    private fun showTorrentStatsDialog(hash: String, name: String) {
        val messageView = TextView(this).apply {
            textSize = 15f
            setPadding(48, 32, 48, 32)
            text = formatTorrentStatsText(hash)
        }

        var running = true
        val updateHandler = Handler(Looper.getMainLooper())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Statistics — $name")
            .setView(messageView)
            .setPositiveButton("Close", null)
            .create()

        val updateTask = object : Runnable {
            override fun run() {
                if (!running) return
                messageView.text = formatTorrentStatsText(hash)
                updateHandler.postDelayed(this, 3000L)
            }
        }

        dialog.setOnDismissListener {
            running = false
            updateHandler.removeCallbacksAndMessages(null)
        }

        dialog.show()
        updateHandler.postDelayed(updateTask, 3000L)
    }

    private fun showGlobalStatsDialog() {
        val messageView = TextView(this).apply {
            textSize = 15f
            setPadding(48, 32, 48, 32)
            text = "Loading..."
        }

        var running = true
        val updateHandler = Handler(Looper.getMainLooper())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Global Statistics (All-Time)")
            .setView(messageView)
            .setPositiveButton("Close", null)
            .create()

        fun refreshOnce() {
            thread {
                val raw: String = try {
                    RustBridge.getGlobalStats()
                } catch (e: Throwable) {
                    "0|0"
                }

                val parts = raw.split("|")
                val downloaded = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                val uploaded = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                val ratio = if (downloaded > 0) uploaded.toDouble() / downloaded.toDouble() else 0.0

                runOnUiThread {
                    if (!running) return@runOnUiThread
                    messageView.text = buildString {
                        appendLine("All-Time Downloaded: ${formatBytes(downloaded)}")
                        appendLine("All-Time Uploaded: ${formatBytes(uploaded)}")
                        append("All-Time Share Ratio: ${String.format("%.2f", ratio)}")
                    }
                }
            }
        }

        val updateTask = object : Runnable {
            override fun run() {
                if (!running) return
                refreshOnce()
                updateHandler.postDelayed(this, 3000L)
            }
        }

        dialog.setOnDismissListener {
            running = false
            updateHandler.removeCallbacksAndMessages(null)
        }

        dialog.show()
        refreshOnce()
        updateHandler.postDelayed(updateTask, 3000L)
    }

    // ---- Network Features dialog (DHT / PEX / LSD), auto-refreshes every 3s ----

    private fun appendColoredLine(builder: SpannableStringBuilder, text: String, color: Int) {
        val start = builder.length
        builder.append(text)
        builder.append("\n")
        builder.setSpan(ForegroundColorSpan(color), start, start + text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun buildNetworkStatusText(raw: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val green = Color.parseColor("#4CAF50")
        val red = Color.parseColor("#F44336")
        val yellow = Color.parseColor("#FFC107")
        val gray = Color.parseColor("#999999")
        val white = Color.WHITE

        if (!raw.startsWith("OK|")) {
            appendColoredLine(builder, "Network status unavailable.", red)
            return builder
        }

        val parts = raw.removePrefix("OK|").split("|")
        val dhtEnabled = parts.getOrNull(0) == "1"
        val dhtNodes = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val lsdEnabled = parts.getOrNull(2) == "1"

        appendColoredLine(builder, "DHT", white)
        appendColoredLine(builder, "DHT: ${if (dhtEnabled) "Enabled" else "Disabled"}", if (dhtEnabled) green else red)
        if (dhtEnabled) {
            appendColoredLine(builder, "DHT nodes: $dhtNodes", if (dhtNodes > 0) green else yellow)
            appendColoredLine(builder, "Status: DHT peer discovery is enabled", green)
        } else {
            appendColoredLine(builder, "Status: DHT peer discovery is disabled", red)
        }
        builder.append("\n")

        appendColoredLine(builder, "PEX", white)
        appendColoredLine(builder, "PEX: Not supported", gray)
        appendColoredLine(
            builder,
            "Note: The underlying torrent engine (librqbit) has no Peer Exchange " +
                "implementation, so this can't actually be turned on — it's not a toggle here.",
            gray
        )
        builder.append("\n")

        appendColoredLine(builder, "LSD (Local Service Discovery)", white)
        appendColoredLine(builder, "LSD: ${if (lsdEnabled) "Enabled" else "Disabled"}", if (lsdEnabled) green else red)
        appendColoredLine(
            builder,
            "Status: Local Service Discovery is ${if (lsdEnabled) "enabled" else "disabled"}",
            if (lsdEnabled) green else red
        )
        appendColoredLine(
            builder,
            "Note: LSD only finds peers on the same WiFi/LAN. A live discovered-peer count " +
                "isn't exposed by the engine, so it isn't shown here.",
            gray
        )
        builder.append("\n")

        appendColoredLine(builder, "Green = enabled/working   Red = disabled   Yellow = waiting", gray)

        return builder
    }

    private fun showNetworkStatusDialog() {
        val messageView = TextView(this).apply {
            textSize = 14f
            setPadding(48, 32, 48, 32)
            text = "Loading..."
        }

        var running = true
        val updateHandler = Handler(Looper.getMainLooper())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Network Features")
            .setView(ScrollView(this).apply { addView(messageView) })
            .setPositiveButton("Close", null)
            .create()

        fun refreshOnce() {
            thread {
                val raw: String = try {
                    RustBridge.getNetworkStatus()
                } catch (e: Throwable) {
                    "ERROR|${e.message}"
                }

                runOnUiThread {
                    if (!running) return@runOnUiThread
                    messageView.text = buildNetworkStatusText(raw)
                }
            }
        }

        val updateTask = object : Runnable {
            override fun run() {
                if (!running) return
                refreshOnce()
                updateHandler.postDelayed(this, 3000L)
            }
        }

        dialog.setOnDismissListener {
            running = false
            updateHandler.removeCallbacksAndMessages(null)
        }

        dialog.show()
        refreshOnce()
        updateHandler.postDelayed(updateTask, 3000L)
    }

    // ---- Global speed limits ----
    //
    // Unlike DHT/LSD, this is a real runtime setting on librqbit's side (Session.ratelimits,
    // backed by the governor crate's token-bucket limiter) — RustBridge.setSpeedLimits()
    // applies immediately, no app restart needed. Persisted in bytes/sec in SharedPreferences
    // so TorrentService can re-apply it on every engine start (see its onCreate).

    private fun showSpeedLimitsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }

        val downloadInput = EditText(this).apply {
            hint = "Download limit, KB/s (blank = unlimited)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val uploadInput = EditText(this).apply {
            hint = "Upload limit, KB/s (blank = unlimited)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }

        val speedPrefs = getSharedPreferences("torrentor_prefs", Context.MODE_PRIVATE)
        val savedDownBps = speedPrefs.getLong("download_limit_bps", 0L)
        val savedUpBps = speedPrefs.getLong("upload_limit_bps", 0L)
        if (savedDownBps > 0L) downloadInput.setText((savedDownBps / 1024L).toString())
        if (savedUpBps > 0L) uploadInput.setText((savedUpBps / 1024L).toString())

        container.addView(downloadInput)
        container.addView(uploadInput)

        AlertDialog.Builder(this)
            .setTitle("Speed Limits")
            .setMessage("Applies immediately — no restart needed. Leave a field blank (or 0) for unlimited.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val downKbps = downloadInput.text.toString().trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val upKbps = uploadInput.text.toString().trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val downBps = downKbps * 1024L
                val upBps = upKbps * 1024L

                speedPrefs.edit()
                    .putLong("download_limit_bps", downBps)
                    .putLong("upload_limit_bps", upBps)
                    .apply()

                thread {
                    val result: String = try {
                        RustBridge.setSpeedLimits(downBps, upBps)
                    } catch (e: Throwable) {
                        "ERROR: ${e.message}"
                    }
                    AppLog.info("Speed limits set (down=$downBps B/s, up=$upBps B/s): $result")
                }

                val summary = buildString {
                    append(if (downBps > 0L) "Download: $downKbps KB/s" else "Download: unlimited")
                    append("  •  ")
                    append(if (upBps > 0L) "Upload: $upKbps KB/s" else "Upload: unlimited")
                }
                Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Clear (Unlimited)") { _, _ ->
                speedPrefs.edit()
                    .putLong("download_limit_bps", 0L)
                    .putLong("upload_limit_bps", 0L)
                    .apply()

                thread {
                    try {
                        RustBridge.setSpeedLimits(0L, 0L)
                    } catch (e: Throwable) {
                    }
                }

                Toast.makeText(this, "Speed limits cleared (unlimited)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- RSS feeds ----
    //
    // RSS management itself (subscriptions, per-feed refresh, the item list, the long-press-
    // to-open-site behavior) now lives in RssActivity/RssStore — its own window instead of a
    // dialog here. This class still owns the background auto-refresh loop so feeds keep
    // updating on schedule whether or not that window happens to be open, reading the
    // configurable interval from RssStore fresh each cycle so a change made in RssActivity's
    // interval picker takes effect on the very next tick without any extra plumbing.

    private fun startRssAutoRefresh() {
        val task = object : Runnable {
            override fun run() {
                if (!autoRefreshRunning) return
                if (RssStore.feeds.isNotEmpty()) RssStore.refreshAll(this@MainActivity)
                mainHandler.postDelayed(this, RssStore.getRefreshIntervalMs(this@MainActivity))
            }
        }

        // Refresh once right away on launch (there's no reason to make the user wait a full
        // interval to see items from feeds they already subscribed to), then settle into the
        // regular, configurable interval.
        if (RssStore.feeds.isNotEmpty()) RssStore.refreshAll(this)
        mainHandler.postDelayed(task, RssStore.getRefreshIntervalMs(this))
    }

    /**
     * Card-list Trackers tab. DHT and LSD show this session's real, live state (from
     * cachedNetworkStatusRaw, same data as the Network Features dialog) — DHT's "Nodes"
     * count is the engine's whole routing table size, not a per-torrent figure, since
     * librqbit doesn't expose per-torrent DHT peer counts. PeX is shown as unsupported
     * rather than guessed at: librqbit's public API has no PEX (BEP 11) implementation
     * anywhere, so there's no real data to show for it.
     *
     * Each real tracker is listed by URL only. librqbit's API (Api::api_torrent_details,
     * api_stats_v1, api_dht_stats, etc. — checked against its published docs) exposes no
     * per-tracker peer counts or announce errors, so those columns from other clients'
     * Trackers tabs aren't reproduced here rather than being filled with invented numbers.
     */
    private fun buildTrackersTabView(hash: String): View {
        val textSecondary = Color.parseColor("#CCCCCC")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        container.addView(buildNetworkSummaryCard())

        val error = extraInfoError[hash]
        if (error != null) {
            container.addView(buildTrackerRow("ERROR", error, isError = true))
            return container
        }

        val extra = extraInfoCache[hash]
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

    /** DHT/LSD/PeX summary card at the top of the Trackers tab — same style as a tracker
     *  row below it, so it reads as "three more sources of peers" the way the reference
     *  screenshot lays it out, just with DHT/LSD reflecting real session state and PeX
     *  honestly marked unsupported instead of both being invented. */
    private fun buildNetworkSummaryCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
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
                setTextColor(Color.parseColor("#999999"))
            })
            return card
        }

        val dhtEnabled = parts.getOrNull(1) == "1"
        val dhtNodes = parts.getOrNull(2) ?: "?"
        val lsdEnabled = parts.getOrNull(3) == "1"

        fun addSummaryLine(label: String, working: Boolean?, detail: String) {
            if (card.childCount > 0) {
                card.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        2
                    ).apply {
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
                        true -> Color.parseColor("#4CAF50")
                        false -> Color.parseColor("#999999")
                        null -> Color.parseColor("#999999")
                    }
                )
            })
            card.addView(line)

            if (detail.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(Color.parseColor("#999999"))
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
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        row.addView(TextView(this).apply {
            text = title
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(if (isError) Color.parseColor("#F44336") else Color.WHITE)
        })

        if (!subtitle.isNullOrBlank()) {
            row.addView(TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#999999"))
                setPadding(0, 4, 0, 0)
            })
        }

        return row
    }

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

    private fun fetchExtraFor(hash: String) {
        // Static metadata (piece size, private flag, trackers) — fetch once and cache,
        // no need for the polling pattern used by pieces/peers/files.
        if (extraInfoCache.containsKey(hash) || extraInfoError.containsKey(hash)) return

        thread {
            val raw: String = try {
                RustBridge.getTorrentExtra(hash)
            } catch (e: Throwable) {
                "ERROR|${e.message}"
            }

            if (raw.startsWith("ERROR|")) {
                extraInfoError[hash] = raw.substringAfter("ERROR|")
            } else {
                val parsed = parseExtraResponse(raw)
                if (parsed != null) {
                    extraInfoCache[hash] = parsed
                } else {
                    extraInfoError[hash] = "Unexpected response from engine."
                }
            }

            runOnUiThread {
                if (expandedHashes.contains(hash) &&
                    (selectedTabByHash[hash] == "general" || selectedTabByHash[hash] == "trackers")
                ) {
                    refreshTorrentInfo()
                }
            }
        }
    }

    private fun buildPiecesTabText(hash: String): String {
        val raw = pieceDataCache[hash] ?: return "Loading piece data..."
        if (raw.startsWith("ERROR")) return raw

        val parts = raw.split("|")
        if (parts.size < 3) return "No piece data available."

        val have = parts[0].toIntOrNull() ?: 0
        val total = parts[1].toIntOrNull() ?: 0
        val bits = parts[2]

        return "Pieces: $have / $total\n\n${buildPieceTriangle(bits)}"
    }

    /**
     * Lays the same have/missing bitfield out as a centered triangle (pyramid) instead of a
     * fixed-width rectangular grid: row 1 gets the first 1 piece, row 2 the next 2, row 3 the
     * next 3, and so on, each row centered under the one below it. It's the same ■ (have) /
     * □ (missing) data and same left-to-right, top-to-bottom piece order as before — only the
     * shape changed. The very last row absorbs whatever pieces are left once the count runs
     * out, so it may be shorter than a full triangle row would be.
     */
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

    private fun buildPeersTabText(hash: String): String {
        val rows = peerDisplayCache[hash]
            ?: return "Loading peer data..."

        if (rows.isEmpty()) return "No connected peers."

        return rows.joinToString("\n\n") { p ->
            val clientText = if (p.client.isBlank()) "Unknown client" else p.client
            val kindText = if (p.connKind.isBlank()) "" else " [${p.connKind}]"
            "${p.address}$kindText\n$clientText  •  ${p.state}\n↓ ${formatSpeed(p.downloaded)}   ↑ ${formatSpeed(p.uploaded)}"
        }
    }

    private fun parsePeerRows(raw: String): Map<String, PeerRow> {
        if (raw.isBlank() || raw.startsWith("ERROR")) return emptyMap()

        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 6) return@mapNotNull null

                val address = parts[0]
                PeerRow(
                    address = address,
                    client = parts[1],
                    state = parts[2],
                    connKind = parts[3],
                    downloaded = parts[4].toLongOrNull() ?: 0L,
                    uploaded = parts[5].toLongOrNull() ?: 0L
                )
            }
            .associateBy { it.address }
    }

    private fun fetchPiecesFor(hash: String) {
        thread {
            val result: String = try {
                RustBridge.getTorrentPieces(hash)
            } catch (e: Throwable) {
                "ERROR: ${e.message}"
            }

            pieceDataCache[hash] = result

            runOnUiThread {
                if (selectedTabByHash[hash] == "pieces" && expandedHashes.contains(hash)) {
                    refreshTorrentInfo()
                }
            }
        }
    }

    private fun fetchPeersFor(hash: String) {
        thread {
            val raw: String = try {
                RustBridge.getTorrentPeers(hash)
            } catch (e: Throwable) {
                "ERROR: ${e.message}"
            }

            val newRows = parsePeerRows(raw)
            val now = System.currentTimeMillis()
            val previous = peerSnapshotCache[hash]

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

            peerSnapshotCache[hash] = PeerSnapshotCache(now, newRows)
            peerDisplayCache[hash] = displayRows

            runOnUiThread {
                if (selectedTabByHash[hash] == "peers" && expandedHashes.contains(hash)) {
                    refreshTorrentInfo()
                }
            }
        }
    }

    // ---- Files tab (view/open downloaded files in-app) ----

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

    private fun fetchFilesFor(hash: String) {
        thread {
            val raw: String = try {
                RustBridge.getTorrentFiles(hash)
            } catch (e: Throwable) {
                "ERROR|${e.message}"
            }

            if (raw.startsWith("ERROR|")) {
                fileListError[hash] = raw.substringAfter("ERROR|")
                fileListCache.remove(hash)
            } else {
                val parsed = parseFilesResponse(raw)
                if (parsed != null) {
                    fileListCache[hash] = parsed
                    fileListError.remove(hash)
                }
            }

            runOnUiThread {
                if (selectedTabByHash[hash] == "files" && expandedHashes.contains(hash)) {
                    refreshTorrentInfo()
                }
            }
        }
    }

    private fun startFilesAutoRefresh() {
        val interval = 3000L

        val task = object : Runnable {
            override fun run() {
                for (hash in expandedHashes) {
                    if (selectedTabByHash[hash] == "files") {
                        fetchFilesFor(hash)
                    }
                }
                mainHandler.postDelayed(this, interval)
            }
        }

        mainHandler.postDelayed(task, interval)
    }

    private fun buildFilesTabView(hash: String): View {
        val textSecondary = Color.parseColor("#CCCCCC")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val error = fileListError[hash]
        if (error != null) {
            container.addView(TextView(this).apply {
                text = "ERROR: $error"
                textSize = 12f
                setTextColor(textSecondary)
            })
            return container
        }

        val cached = fileListCache[hash]
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

        val hashSelections = fileSelectionOverrides.getOrPut(hash) { mutableMapOf() }
        val checkBoxes = mutableListOf<CheckBox>()

        for (file in files) {
            val isSelected = hashSelections.getOrPut(file.index) { file.included }

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
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            checkbox.setOnCheckedChangeListener { _, checked ->
                hashSelections[file.index] = checked
            }
            checkBoxes.add(checkbox)

            val nameView = TextView(this).apply {
                text = "${file.relativePath}\n${formatBytes(file.size)}"
                textSize = 12f
                setTextColor(if (file.included) Color.WHITE else Color.parseColor("#777777"))
                setPadding(12, 0, 0, 0)
                isClickable = file.included
                isFocusable = file.included
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            if (file.included) {
                nameView.setOnClickListener {
                    openFile(outputFolder, file)
                }
            }

            topRow.addView(checkbox)
            topRow.addView(nameView)
            rowContainer.addView(topRow)

            if (file.included) {
                val filePercent = if (file.totalPieces > 0) {
                    (file.havePieces.toDouble() / file.totalPieces.toDouble()) * 100.0
                } else {
                    0.0
                }

                val fileProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = (filePercent * 10).toInt().coerceIn(0, 1000)
                    progressTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                    progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#555555"))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8 }
                }
                rowContainer.addView(fileProgressBar)

                rowContainer.addView(TextView(this).apply {
                    text = "${String.format("%.1f", filePercent)}%"
                    textSize = 10f
                    setTextColor(Color.parseColor("#999999"))
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
            files.forEach { hashSelections[it.index] = true }
            checkBoxes.forEach { it.isChecked = true }
        }
        selectNoneBtn.setOnClickListener {
            files.forEach { hashSelections[it.index] = false }
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
            val selectedIndices = hashSelections.filterValues { it }.keys
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
                    Toast.makeText(
                        this,
                        if (result == "OK") "File selection updated" else result,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                fetchFilesFor(hash)
            }
        }
        container.addView(updateSelectionBtn)

        return container
    }

    /**
     * Opens a torrent's save folder in whatever file manager the user has installed.
     * There's no single Android API for "open this folder" — we hand a content:// URI
     * (via FileProvider) to ACTION_VIEW, trying the MIME type most file managers expect
     * first ("resource/folder"), then falling back to the DocumentsContract directory
     * MIME type, since support varies by OEM/file-manager app.
     */
    private fun openFolder(path: String) {
        thread {
            val dir = File(path)
            if (!dir.exists()) {
                runOnUiThread {
                    Toast.makeText(this, "Folder not found yet.", Toast.LENGTH_SHORT).show()
                }
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
                    Toast.makeText(
                        this,
                        "No file manager app found to open this folder.\n$path",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    private fun openFile(outputFolder: String, file: FileEntry) {
        thread {
            val targetFile = File(outputFolder, file.relativePath)

            if (!targetFile.exists()) {
                runOnUiThread {
                    Toast.makeText(this, "File not downloaded yet.", Toast.LENGTH_SHORT).show()
                }
                return@thread
            }

            val uri = try {
                FileProvider.getUriForFile(
                    this,
                    "com.example.torrentorv2.fileprovider",
                    targetFile
                )
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't prepare file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                return@thread
            }

            val mimeType = guessMimeType(targetFile.name)

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

    private fun startPiecesAutoRefresh() {
        val interval = 3000L

        val task = object : Runnable {
            override fun run() {
                for (hash in expandedHashes) {
                    if (selectedTabByHash[hash] == "pieces") {
                        fetchPiecesFor(hash)
                    }
                }
                mainHandler.postDelayed(this, interval)
            }
        }

        mainHandler.postDelayed(task, interval)
    }

    private fun startPeersAutoRefresh() {
        val interval = 3000L

        val task = object : Runnable {
            override fun run() {
                for (hash in expandedHashes) {
                    if (selectedTabByHash[hash] == "peers") {
                        fetchPeersFor(hash)
                    }
                }
                mainHandler.postDelayed(this, interval)
            }
        }

        mainHandler.postDelayed(task, interval)
    }

    // ---- Sort / filter / search (Kotlin-side only, see field comments near their declaration) ----

    /** Buckets a torrent into one of the filter categories. Built only from what librqbit
     *  actually reports (stats.state is one of "initializing"/"live"/"paused"/"error" — see
     *  librqbit's TorrentStatsState — plus percent), no invented states: a "live" torrent at
     *  ~100% is showing as a seed, otherwise it's downloading. */
    private fun categorize(t: TorrentInfo): String {
        return when {
            t.status == "error" -> "error"
            t.status == "paused" -> "paused"
            t.status == "initializing" -> "initializing"
            t.status == "live" && t.percent >= 99.95 -> "seeding"
            else -> "downloading"
        }
    }

    private fun dateAddedFor(hash: String): Long = dateAddedPrefs.getLong(hash, Long.MAX_VALUE)

    private fun applySortFilterSearch(torrents: List<TorrentInfo>): List<TorrentInfo> {
        var list = torrents

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter { it.name.lowercase().contains(q) }
        }

        if (statusFilterKey != "all") {
            list = list.filter { categorize(it) == statusFilterKey }
        }

        val comparator: Comparator<TorrentInfo> = when (sortField) {
            "name" -> compareBy { it.name.lowercase() }
            "size" -> compareBy { it.totalBytes }
            "status" -> compareBy { it.status }
            "progress" -> compareBy { it.percent }
            else -> compareBy { dateAddedFor(it.hash) } // "dateAdded"
        }

        return if (sortAscending) list.sortedWith(comparator) else list.sortedWith(comparator.reversed())
    }

    /** Re-renders the torrent cards from the last-known data with the current sort/filter/
     *  search applied, without waiting for the next 1s poll — used when the user changes the
     *  search text or the sort/filter choice so the list updates instantly. */
    private fun refreshTorrentCardsFromCache() {
        rebuildTorrentCards(applySortFilterSearch(lastTorrentsByHash.values.toList()))
    }

    private fun updateSelectedCountLabel() {
        selectedCountLabel.text = "${selectedForBatch.size} selected"
    }

    private fun showSortDialog() {
        val options = listOf(
            Triple("Date Added (Newest First)", "dateAdded", false),
            Triple("Date Added (Oldest First)", "dateAdded", true),
            Triple("Name (A-Z)", "name", true),
            Triple("Name (Z-A)", "name", false),
            Triple("Size (Largest First)", "size", false),
            Triple("Size (Smallest First)", "size", true),
            Triple("Progress (Highest First)", "progress", false),
            Triple("Progress (Lowest First)", "progress", true),
            Triple("Status (A-Z)", "status", true)
        )

        AlertDialog.Builder(this)
            .setTitle("Sort torrents by")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                val (label, field, ascending) = options[which]
                sortField = field
                sortAscending = ascending
                sortButton.text = "Sort: $label"
                refreshTorrentCardsFromCache()
            }
            .show()
    }

    private fun showFilterDialog() {
        val options = listOf(
            "All" to "all",
            "Downloading" to "downloading",
            "Seeding" to "seeding",
            "Paused" to "paused",
            "Initializing" to "initializing",
            "Error" to "error"
        )

        AlertDialog.Builder(this)
            .setTitle("Filter torrents")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                val (label, key) = options[which]
                statusFilterKey = key
                filterButton.text = "Filter: $label"
                refreshTorrentCardsFromCache()
            }
            .show()
    }

    private fun refreshTorrentInfo() {
        thread {
            val raw: String = try {
                RustBridge.getTorrentInfo()
            } catch (e: Throwable) {
                ""
            }

            val torrents = parseTorrents(raw)
            val totalDown = torrents.sumOf { it.downSpeedBytes }
            val totalUp = torrents.sumOf { it.upSpeedBytes }

            // Stamp "first seen" time for any hash we haven't recorded yet, for the Date
            // Added sort (see dateAddedPrefs' declaration comment for why this is an
            // approximation rather than a real Rust-side add timestamp).
            for (t in torrents) {
                if (!dateAddedPrefs.contains(t.hash)) {
                    dateAddedPrefs.edit().putLong(t.hash, System.currentTimeMillis()).apply()
                }
            }

            runOnUiThread {
                lastTorrentsByHash.clear()
                for (t in torrents) lastTorrentsByHash[t.hash] = t

                rebuildTorrentCards(applySortFilterSearch(torrents))
                speedGraphView.addSample(totalDown, totalUp)
                if (statusView.text == "Rust engine: starting (via service)...") {
                    statusView.text = "Rust engine: running (via service)"
                }
            }
        }
    }

    private fun startAutoRefresh() {
        val refreshInterval = 1000L

        val refreshTask = object : Runnable {
            override fun run() {
                if (!autoRefreshRunning) return
                refreshTorrentInfo()
                mainHandler.postDelayed(this, refreshInterval)
            }
        }

        mainHandler.postDelayed(refreshTask, refreshInterval)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoRefreshRunning = false
        mainHandler.removeCallbacksAndMessages(null)
    }
}