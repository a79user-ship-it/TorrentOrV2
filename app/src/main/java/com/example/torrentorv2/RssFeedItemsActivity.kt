package com.example.torrentorv2

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * One feed's item list, on its own screen — reached by tapping a feed card (or its Open
 * button) in RssActivity, rather than expanding the items inline in that list. Reads live
 * from RssStore by feed URL rather than owning any state of its own, so it reflects whatever
 * the background auto-refresh loop in MainActivity (or a manual refresh from either RSS
 * screen) has fetched, and stays correct if the feed is edited or removed while this screen
 * is open.
 */
class RssFeedItemsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FEED_URL = "feed_url"
    }

    private lateinit var headerView: TextView
    private lateinit var listContainer: LinearLayout
    private var feedUrl: String = ""
    private val redrawHandler = Handler(Looper.getMainLooper())
    private var redrawRunning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RssStore.ensureLoaded(this)
        feedUrl = intent.getStringExtra(EXTRA_FEED_URL) ?: ""

        fun wrapWidthParams() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            layoutParams = wrapWidthParams()
        }

        headerView = TextView(this).apply {
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            layoutParams = wrapWidthParams()
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wrapWidthParams().apply { topMargin = 16 }
        }

        val refreshButton = Button(this).apply {
            text = "Refresh"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val backButton = Button(this).apply {
            text = "Back"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        buttonRow.addView(refreshButton)
        buttonRow.addView(backButton)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = wrapWidthParams().apply { topMargin = 16 }
        }

        root.addView(headerView)
        root.addView(buttonRow)
        root.addView(listContainer)

        setContentView(ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(root)
        })

        refreshButton.setOnClickListener {
            findEntry()?.let { entry ->
                RssStore.refreshFeed(this, entry) { runOnUiThread { rebuild() } }
                rebuild()
            }
        }
        backButton.setOnClickListener { finish() }

        rebuild()
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
                rebuild()
                redrawHandler.postDelayed(this, interval)
            }
        }
        redrawHandler.postDelayed(task, interval)
    }

    private fun findEntry(): RssFeedEntry? = RssStore.feeds.firstOrNull { it.url == feedUrl }

    private fun rebuild() {
        val entry = findEntry()
        if (entry == null) {
            headerView.text = "Feed removed"
            listContainer.removeAllViews()
            listContainer.addView(TextView(this).apply {
                text = "This feed is no longer subscribed."
                textSize = 13f
                setTextColor(Color.parseColor("#999999"))
            })
            return
        }

        headerView.text = if (entry.loading) "${entry.name}  (refreshing...)" else entry.name

        listContainer.removeAllViews()

        val error = entry.error
        if (error != null) {
            listContainer.addView(TextView(this).apply {
                text = "ERROR: $error"
                textSize = 13f
                setTextColor(Color.parseColor("#F44336"))
                setPadding(0, 8, 0, 8)
            })
        }

        if (entry.items.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "No items yet."
                textSize = 13f
                setTextColor(Color.parseColor("#999999"))
                setPadding(0, 16, 0, 0)
            })
            return
        }

        for (item in entry.items) {
            listContainer.addView(buildItemRow(item))
        }
    }

    private fun buildItemRow(item: RssItem): LinearLayout {
        val itemRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            isClickable = true
            isLongClickable = true
        }

        itemRow.setOnLongClickListener {
            openItemSite(item)
            true
        }

        itemRow.addView(TextView(this).apply {
            text = item.title
            textSize = 12f
            setTextColor(Color.WHITE)
        })

        if (item.pubDate.isNotBlank()) {
            itemRow.addView(TextView(this).apply {
                text = item.pubDate
                textSize = 10f
                setTextColor(Color.parseColor("#999999"))
                setPadding(0, 4, 0, 0)
            })
        }

        itemRow.addView(TextView(this).apply {
            text = "Long-press to open on site"
            textSize = 10f
            setTextColor(Color.parseColor("#777777"))
            setPadding(0, 4, 0, 0)
        })

        val addItemButton = Button(this).apply {
            text = "Add Torrent"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        addItemButton.setOnClickListener { addItemAsTorrent(item) }
        itemRow.addView(addItemButton)

        return itemRow
    }

    private fun addItemAsTorrent(item: RssItem) {
        val source = item.downloadUrl
        if (source.isNullOrBlank()) {
            Toast.makeText(
                this,
                "No downloadable torrent/magnet link found for this item — try long-pressing it to open the site instead.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(this, "Adding: ${item.title}", Toast.LENGTH_SHORT).show()
        QuickAdd.addSource(this, source, item.title) { result ->
            Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
        }
    }

    /** Long-press action: open the item's own page (RSS/Atom <link>) on the tracker/site it
     *  came from — separate from downloading it. */
    private fun openItemSite(item: RssItem) {
        if (item.pageLink.isBlank() || item.pageLink.startsWith("magnet:", ignoreCase = true)) {
            Toast.makeText(this, "This item has no separate site page to open.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.pageLink)))
        } catch (e: Throwable) {
            Toast.makeText(this, "No app found to open this link.", Toast.LENGTH_SHORT).show()
        }
    }
}