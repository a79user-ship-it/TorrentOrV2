package com.example.torrentorv2

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Online torrent search, its own window like RssActivity/LogActivity. See SearchManager's
 * doc comment for why this only searches the Internet Archive rather than reproducing
 * qBittorrent's full plugin-based search (which mostly targets sites indexing unauthorized
 * copies of copyrighted media) — this keeps the feature genuinely useful without doing that.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var queryInput: EditText
    private lateinit var statusView: TextView
    private lateinit var resultsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            text = "Search"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            layoutParams = wrapWidthParams()
        }

        val sourceLabel = TextView(this).apply {
            text = "Source: Internet Archive (archive.org) — public-domain and openly " +
                "licensed books, software, audio and video only."
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 8, 0, 0)
            layoutParams = wrapWidthParams()
        }

        queryInput = EditText(this).apply {
            hint = "Search Internet Archive..."
            setPadding(24, 24, 24, 24)
            layoutParams = wrapWidthParams().apply { topMargin = 16 }
        }

        val searchButton = Button(this).apply {
            text = "Search"
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        statusView = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 8, 0, 0)
            layoutParams = wrapWidthParams()
        }

        resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        root.addView(title)
        root.addView(sourceLabel)
        root.addView(queryInput)
        root.addView(searchButton)
        root.addView(statusView)
        root.addView(resultsContainer)

        setContentView(ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(root)
        })

        searchButton.setOnClickListener { runSearch() }
        queryInput.setOnEditorActionListener { _, _, _ ->
            runSearch()
            true
        }
    }

    private fun runSearch() {
        val query = queryInput.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, "Type something to search for.", Toast.LENGTH_SHORT).show()
            return
        }

        statusView.text = "Searching..."
        resultsContainer.removeAllViews()

        thread {
            val results: List<SearchResult>? = try {
                SearchManager.search(query)
            } catch (e: Throwable) {
                AppLog.warning("Search: failed for \"$query\" — ${e.message}")
                null
            }

            runOnUiThread {
                if (results == null) {
                    statusView.text = "Search failed — check your connection and try again."
                    return@runOnUiThread
                }

                AppLog.info("Search: \"$query\" — ${results.size} result(s)")
                statusView.text = "${results.size} result(s)"
                for (result in results) {
                    resultsContainer.addView(buildResultRow(result))
                }
            }
        }
    }

    private fun buildResultRow(result: SearchResult): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            isClickable = true
            isLongClickable = true
        }

        row.setOnLongClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.pageUrl)))
            } catch (e: Throwable) {
                Toast.makeText(this, "No app found to open this link.", Toast.LENGTH_SHORT).show()
            }
            true
        }

        row.addView(TextView(this).apply {
            text = result.title
            textSize = 13f
            setTextColor(Color.WHITE)
        })

        row.addView(TextView(this).apply {
            text = if (result.mediatype.isNotBlank()) result.mediatype else result.identifier
            textSize = 10f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 4, 0, 0)
        })

        row.addView(TextView(this).apply {
            text = "Long-press to open on archive.org"
            textSize = 10f
            setTextColor(Color.parseColor("#777777"))
            setPadding(0, 4, 0, 0)
        })

        val addButton = Button(this).apply {
            text = "Add Torrent"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        addButton.setOnClickListener {
            Toast.makeText(this, "Adding: ${result.title}", Toast.LENGTH_SHORT).show()
            QuickAdd.addSource(this, result.downloadUrl, result.title) { addResult ->
                Toast.makeText(this, addResult, Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(addButton)

        return row
    }
}