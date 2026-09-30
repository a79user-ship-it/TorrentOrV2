package com.example.torrentorv2

import android.content.Context
import android.graphics.Color

/**
 * App-wide accent color theme — the color used for progress bars, section headers, and other
 * decorative highlights across MainActivity and TorrentDetailActivity. The app's base dark
 * palette (backgrounds, card colors, text colors) stays the same across every theme; only the
 * accent swaps. Semantic colors (green = "working"/enabled, red = error) are kept as literal
 * colors wherever they're used for that meaning, separate from this, so switching to e.g. the
 * Crimson Red theme doesn't make a "Working" status line confusingly read as an error.
 *
 * Persisted in the same "torrentor_prefs" SharedPreferences file as the app's other settings.
 * Each screen reads AppTheme.accentColor(context) when it builds its views (this app has no
 * XML layouts / data binding to push live updates through), so applying a newly picked theme
 * to the current screen is a plain Activity.recreate() — cheap, and instant since nothing here
 * depends on network or the engine.
 */
object AppTheme {
    data class Theme(val id: String, val label: String, val accentHex: String)

    val THEMES = listOf(
        Theme("green", "Forest Green", "#4CAF50"),
        Theme("blue", "Ocean Blue", "#2196F3"),
        Theme("purple", "Deep Purple", "#9C27B0"),
        Theme("orange", "Amber Orange", "#FF9800"),
        Theme("red", "Crimson Red", "#F44336"),
        Theme("teal", "Teal", "#009688"),
        Theme("pink", "Hot Pink", "#E91E63")
    )

    private const val PREFS_NAME = "torrentor_prefs"
    private const val KEY_THEME_ID = "theme_id"
    private const val DEFAULT_THEME_ID = "green"

    fun currentThemeId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_ID, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID
    }

    fun setThemeId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_ID, id)
            .apply()
    }

    fun currentTheme(context: Context): Theme {
        val id = currentThemeId(context)
        return THEMES.firstOrNull { it.id == id } ?: THEMES.first()
    }

    fun accentColor(context: Context): Int = Color.parseColor(currentTheme(context).accentHex)
}