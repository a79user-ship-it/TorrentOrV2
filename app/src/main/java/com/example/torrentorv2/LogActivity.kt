package com.example.torrentorv2

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.util.AttributeSet
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * A plain ScrollView containing a selectable (setTextIsSelectable(true)) TextView has a
 * well-known Android quirk: any layout pass that touches the TextView makes the platform call
 * requestChildFocus() to re-center that "focused" child into view on its own, independent of
 * any app logic — which is exactly the "jumps up and down with nothing new" behavior reported
 * here, since it can fire from a layout pass alone (rotation, a system window inset change, a
 * redraw elsewhere on screen) with no relation to rebuildLog()'s own 3s timer or new log lines
 * arriving. Overriding requestChildFocus() as a no-op disables that unwanted auto-recentering
 * entirely, while leaving this activity's own deliberate scrollToBottom() (a plain
 * fullScroll(FOCUS_DOWN)) completely unaffected, since that doesn't go through this method.
 */
class NoAutoFocusScrollScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {
    override fun requestChildFocus(child: View?, focused: View?) {
        // Intentionally does nothing — see class doc.
    }
}

/**
 * qBittorrent-style "Execution Log": a persistent, timestamped, color-coded (INFO/WARNING/
 * ERROR) console of notable app events, backed by AppLog's plain text file so entries survive
 * the app being closed and reopened, not just cleared on next launch.
 *
 * The whole log is rendered as ONE selectable TextView (not a list of separate rows), so a
 * long-press starts Android's normal text selection — drag the handles across words, lines,
 * or the whole log, then Copy from the selection action bar — the same as selecting text
 * anywhere else on the phone. A dedicated "Copy Log" button covers copying everything at once
 * without needing to select it first.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var autoScrollButton: Button
    private var autoScrollEnabled = true
    private val redrawHandler = Handler(Looper.getMainLooper())
    private var redrawRunning = true

    /** The text last rendered into logView, joined with "\n" — lets rebuildLog() tell "nothing
     *  new since last check" apart from "new lines arrived", instead of unconditionally
     *  replacing the view (which wipes any in-progress text selection) and force-scrolling
     *  every 3s regardless of whether anything actually changed. */
    private var lastRenderedText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun wrapWidthParams() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 24)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "Execution Log"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            layoutParams = wrapWidthParams()
        }

        val subtitle = TextView(this).apply {
            text = "Persists across app restarts. Long-press the log to select and copy text."
            textSize = 11f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 4, 0, 0)
            layoutParams = wrapWidthParams()
        }

        val buttonRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wrapWidthParams().apply { topMargin = 16 }
        }
        val refreshButton = Button(this).apply {
            text = "Refresh"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        autoScrollButton = Button(this).apply {
            text = "Auto-scroll: On"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttonRow1.addView(refreshButton)
        buttonRow1.addView(autoScrollButton)

        val buttonRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }
        val copyButton = Button(this).apply {
            text = "Copy Log"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clearButton = Button(this).apply {
            text = "Clear Log"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttonRow2.addView(copyButton)
        buttonRow2.addView(clearButton)

        // TextView.setTextIsSelectable(true) is what turns on long-press word/line selection
        // with drag handles and a Copy action, all built into the platform — nothing custom
        // to wire up beyond this flag.
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, 0, 0, 32)
            layoutParams = wrapWidthParams()
        }

        logScroll = NoAutoFocusScrollScrollView(this).apply {
            // Weight 1 so this scrolling log region fills all the space the header/buttons/
            // back button don't use, console-style, instead of the whole screen scrolling as
            // one long page.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(logView)
        }

        val backButton = Button(this).apply {
            text = "Back"
            layoutParams = wrapWidthParams().apply { topMargin = 8 }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(buttonRow1)
        root.addView(buttonRow2)
        root.addView(logScroll)
        root.addView(backButton)

        setContentView(root)

        refreshButton.setOnClickListener { rebuildLog() }

        autoScrollButton.setOnClickListener {
            autoScrollEnabled = !autoScrollEnabled
            autoScrollButton.text = "Auto-scroll: ${if (autoScrollEnabled) "On" else "Off"}"
            if (autoScrollEnabled) scrollToBottom()
        }

        copyButton.setOnClickListener { copyLogToClipboard() }

        clearButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear log?")
                .setMessage("This permanently deletes all recorded log entries. This can't be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    AppLog.clear()
                    lastRenderedText = null
                    rebuildLog()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        backButton.setOnClickListener { finish() }

        rebuildLog()
        scrollToBottom()
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
                rebuildLog()
                redrawHandler.postDelayed(this, interval)
            }
        }
        redrawHandler.postDelayed(task, interval)
    }

    private fun colorFor(line: String): Int {
        return when {
            line.contains("[ERROR]") -> Color.parseColor("#F44336")
            line.contains("[WARNING]") -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#CCCCCC")
        }
    }

    private fun rebuildLog() {
        val lines = AppLog.readAll()

        if (lines.isEmpty()) {
            if (lastRenderedText != "") {
                lastRenderedText = ""
                logView.text = "No log entries yet."
                logView.setTextColor(Color.parseColor("#999999"))
            }
            return
        }

        val newText = lines.joinToString("\n")

        // Nothing new since the last check (very common on the 3s auto-redraw timer, since
        // most 3s windows have no new log activity at all) — leave the view completely alone.
        // Rebuilding unconditionally used to wipe out any in-progress text selection and,
        // combined with the old "always scroll to bottom" below, yank the log back down even
        // while someone was mid-scroll reading older entries — that's the "scrolls up on its
        // own" behavior this was fixing.
        if (newText == lastRenderedText) return
        lastRenderedText = newText

        // Decide whether to follow the new content BEFORE touching the view: if the user had
        // already scrolled away from the bottom to read something older, respect that and
        // leave their scroll position where it is; only keep "following" if they were already
        // at (or very near) the bottom, the same way a chat app's live feed behaves.
        val wasAtBottom = isScrolledToBottom()

        val builder = SpannableStringBuilder()
        for ((index, line) in lines.withIndex()) {
            val start = builder.length
            builder.append(line)
            builder.setSpan(
                ForegroundColorSpan(colorFor(line)),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (index != lines.lastIndex) builder.append("\n")
        }

        logView.text = builder
        if (autoScrollEnabled && wasAtBottom) scrollToBottom()
    }

    /** Within a small pixel threshold of the very bottom of the scrollable log — forgiving
     *  enough for rounding, but tight enough that "scrolled up to read something" reliably
     *  reads as false. */
    private fun isScrolledToBottom(): Boolean {
        val child = logScroll.getChildAt(0) ?: return true
        val diff = child.bottom - (logScroll.height + logScroll.scrollY)
        return diff <= 48
    }

    private fun scrollToBottom() {
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyLogToClipboard() {
        val lines = AppLog.readAll()
        if (lines.isEmpty()) {
            Toast.makeText(this, "Log is empty.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TorrentOrV2 log", lines.joinToString("\n")))
        Toast.makeText(this, "Log copied to clipboard (${lines.size} lines).", Toast.LENGTH_SHORT).show()
    }
}