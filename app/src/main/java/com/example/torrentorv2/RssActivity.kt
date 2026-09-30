package com.example.torrentorv2

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RSS feed management as its own window (separate task from MainActivity, launched via
 * Intent) rather than a dialog on top of the main screen.
 *
 * This screen is a list of feed summary cards — name, item count, auto-download state, last
 * update time. Tapping a card (or its Open button) drills into that feed's own item list on
 * RssFeedItemsActivity, rather than expanding the items inline here.
 *
 * Subscriptions and fetched items live in RssStore, not here, so MainActivity's background
 * timer keeps refreshing feeds on schedule whether or not this window happens to be open.
 * This activity just polls RssStore every few seconds and redraws — the same "read the
 * shared state on a timer" approach the rest of the app uses for live data.
 */
class RssActivity : AppCompatActivity() {

    private lateinit var summaryView: TextView
    private lateinit var refreshTimesView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var intervalButton: Button
    private val redrawHandler = Handler(Looper.getMainLooper())
    private var redrawRunning = true
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val cardDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RssStore.ensureLoaded(this)

        fun wrapWidthParams() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            layoutParams = wrapWidthParams()
        }

        val title = TextView(this).apply {
            text = "RSS Feeds"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            layoutParams = wrapWidthParams()
        }

        summaryView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, 12, 0, 0)
            layoutParams = wrapWidthParams()
        }

        refreshTimesView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 8, 0, 0)
            layoutParams = wrapWidthParams()
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wrapWidthParams().apply { topMargin = 16 }
        }

        val refreshAllButton = Button(this).apply {
            text = "Refresh All"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        intervalButton = Button(this).apply {
            text = "Auto refresh: ..."
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        buttonRow.addView(refreshAllButton)
        buttonRow.addView(intervalButton)

        val urlInput = EditText(this).apply {
            hint = "RSS or Atom feed URL"
            setPadding(24, 24, 24, 24)
            layoutParams = wrapWidthParams().apply { topMargin = 16 }
        }

        val addButton = Button(this).apply {
            text = "Add Feed"
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = wrapWidthParams().apply { topMargin = 16 }
        }

        val backButton = Button(this).apply {
            text = "Back"
            layoutParams = wrapWidthParams().apply { topMargin = 20 }
        }

        root.addView(title)
        root.addView(summaryView)
        root.addView(refreshTimesView)
        root.addView(buttonRow)
        root.addView(urlInput)
        root.addView(addButton)
        root.addView(listContainer)
        root.addView(backButton)

        setContentView(ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(root)
        })

        addButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Enter a feed URL first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            urlInput.setText("")
            if (RssStore.addFeed(this, url)) {
                val entry = RssStore.feeds.last()
                RssStore.refreshFeed(this, entry) { runOnUiThread { rebuildAll() } }
                rebuildAll()
            } else {
                Toast.makeText(this, "That feed is already added.", Toast.LENGTH_SHORT).show()
            }
        }

        refreshAllButton.setOnClickListener {
            RssStore.refreshAll(this) { runOnUiThread { rebuildAll() } }
            rebuildAll()
        }

        intervalButton.setOnClickListener { showIntervalPicker() }
        backButton.setOnClickListener { finish() }

        rebuildAll()
    }

    override fun onResume() {
        super.onResume()
        redrawRunning = true
        startRedrawLoop()
    }

    override fun onPause() {
        super.onPause()
        redrawRunning = false
        redrawHandler.removeCallbacksAndMessages(null)
    }

    private fun startRedrawLoop() {
        val interval = 3000L
        val task = object : Runnable {
            override fun run() {
                if (!redrawRunning) return
                rebuildAll()
                redrawHandler.postDelayed(this, interval)
            }
        }
        redrawHandler.postDelayed(task, interval)
    }

    private fun updateIntervalButtonLabel() {
        val currentMs = RssStore.getRefreshIntervalMs(this)
        val label = RSS_REFRESH_INTERVAL_OPTIONS.firstOrNull { it.second == currentMs }?.first
            ?: "${currentMs / 60000} min"
        intervalButton.text = "Auto refresh: Every $label"
    }

    private fun showIntervalPicker() {
        val currentMs = RssStore.getRefreshIntervalMs(this)
        val labels = RSS_REFRESH_INTERVAL_OPTIONS.map { it.first }.toTypedArray()
        val currentIndex = RSS_REFRESH_INTERVAL_OPTIONS.indexOfFirst { it.second == currentMs }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Auto-refresh every")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val (label, ms) = RSS_REFRESH_INTERVAL_OPTIONS[which]
                RssStore.setRefreshIntervalMs(this, ms)
                updateIntervalButtonLabel()
                Toast.makeText(this, "Feeds will refresh every $label", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rebuildAll() {
        updateIntervalButtonLabel()

        val count = RssStore.feeds.size
        summaryView.text = "$count feed(s). Tap a feed to open it."

        val lastMs = RssStore.getLastRefreshMs(this)
        val nextMs = RssStore.getNextRefreshMs(this)
        refreshTimesView.text = buildString {
            append("Last refresh: ")
            append(if (lastMs == 0L) "Never" else timestampFormat.format(Date(lastMs)))
            append("\nNext refresh: ")
            append(if (nextMs == 0L) "—" else timestampFormat.format(Date(nextMs)))
        }

        rebuildList()
    }

    private fun rebuildList() {
        listContainer.removeAllViews()

        if (RssStore.feeds.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "No feeds added yet. Paste a feed URL above and tap Add Feed."
                textSize = 13f
                setTextColor(Color.parseColor("#999999"))
                setPadding(0, 16, 0, 0)
            })
            return
        }

        for (entry in RssStore.feeds) {
            listContainer.addView(buildFeedCard(entry))
        }
    }

    private fun buildFeedCard(entry: RssFeedEntry): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            isClickable = true
        }
        card.setOnClickListener { openFeed(entry) }

        card.addView(TextView(this).apply {
            text = if (entry.loading) "${entry.name}  (refreshing...)" else entry.name
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        card.addView(TextView(this).apply {
            text = entry.url
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
        })

        val statusLine = buildString {
            append("${entry.items.size} item(s)")
            append("  •  Auto-download: ${if (entry.autoDownload) "On" else "Off"}")
            if (entry.lastRefreshMs != 0L) {
                append("  •  updated ${cardDateFormat.format(Date(entry.lastRefreshMs))}")
            }
        }
        card.addView(TextView(this).apply {
            text = statusLine
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 8, 0, 0)
        })

        val error = entry.error
        if (error != null) {
            card.addView(TextView(this).apply {
                text = "ERROR: $error"
                textSize = 12f
                setTextColor(Color.parseColor("#F44336"))
                setPadding(0, 8, 0, 0)
            })
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        val openButton = Button(this).apply {
            text = "Open"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val refreshButton = Button(this).apply {
            text = "Refresh"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        openButton.setOnClickListener { openFeed(entry) }
        refreshButton.setOnClickListener {
            RssStore.refreshFeed(this, entry) { runOnUiThread { rebuildAll() } }
            rebuildAll()
        }

        buttonRow.addView(openButton)
        buttonRow.addView(refreshButton)
        card.addView(buttonRow)

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        val autoDownloadButton = Button(this).apply {
            text = if (entry.autoDownload) "Auto-download: On" else "Auto-download: Off"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val removeButton = Button(this).apply {
            text = "Remove"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Click on the auto-download button toggles it directly — no separate confirmation
        // needed, same as flipping the DHT/LSD checkboxes on the main screen. The button
        // itself, and the "Auto-download: On/Off" text in the status line above, are the
        // only indicators; there's no hidden state.
        autoDownloadButton.setOnClickListener {
            RssStore.setAutoDownload(this, entry, !entry.autoDownload)
            rebuildAll()
        }
        removeButton.setOnClickListener {
            RssStore.removeFeed(this, entry)
            rebuildAll()
        }

        secondRow.addView(autoDownloadButton)
        secondRow.addView(removeButton)
        card.addView(secondRow)

        return card
    }

    private fun openFeed(entry: RssFeedEntry) {
        startActivity(Intent(this, RssFeedItemsActivity::class.java).apply {
            putExtra(RssFeedItemsActivity.EXTRA_FEED_URL, entry.url)
        })
    }
}