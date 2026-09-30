package com.example.torrentorv2

import org.w3c.dom.Element
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One entry from an RSS 2.0 <item> or Atom <entry>.
 *
 * `pageLink` is the item's own page on the tracker/site — RSS/Atom's actual "this is the
 * page about this thing" field (plain <link> text in RSS, <link href="..." rel="alternate">
 * in Atom) — this is what long-pressing an item opens in the browser.
 *
 * `downloadUrl` is the actual torrent/magnet source: an explicit <enclosure url="..."/> when
 * the feed provides one, otherwise (plenty of tracker feeds skip <enclosure> and just put a
 * magnet or a direct .torrent URL straight in <link> instead) pageLink itself, but only when
 * it's obviously downloadable — never assumed to be downloadable just because it exists.
 */
data class RssItem(
    val title: String,
    val pageLink: String,
    val downloadUrl: String?,
    val pubDate: String
)

data class RssFetchResult(
    val feedTitle: String?,
    val items: List<RssItem>
)

/**
 * Fetches and parses RSS 2.0 / Atom feeds using only what ships in the Android SDK
 * (HttpURLConnection + javax.xml.parsers) — no networking or XML library dependency needed.
 */
object RssManager {

    /** Fetches and parses a feed. Performs blocking network I/O — call this off the main thread. */
    fun fetchFeed(feedUrl: String): RssFetchResult {
        val xml = fetchUrlText(feedUrl)
        return parseFeed(xml)
    }

    /** Downloads arbitrary bytes (used for turning a feed item's .torrent link into file bytes,
     *  and for downloading a search result's torrent file). */
    fun fetchUrlBytes(urlString: String): ByteArray {
        val connection = openConnection(urlString)
        try {
            connection.inputStream.use { return it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** Fetches and returns response text directly (used by SearchManager for JSON APIs). */
    fun fetchUrlTextPublic(urlString: String): String = fetchUrlText(urlString)

    private fun fetchUrlText(urlString: String): String {
        return String(fetchUrlBytes(urlString), Charsets.UTF_8)
    }

    private fun openConnection(urlString: String): HttpURLConnection {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "TorrentOrV2/1.0")
        connection.connect()

        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("HTTP $code")
        }

        return connection
    }

    private fun downloadUrlFor(enclosureUrl: String?, pageLink: String): String? {
        return when {
            !enclosureUrl.isNullOrBlank() -> enclosureUrl
            pageLink.startsWith("magnet:", ignoreCase = true) -> pageLink
            pageLink.endsWith(".torrent", ignoreCase = true) -> pageLink
            else -> null
        }
    }

    private fun parseFeed(xml: String): RssFetchResult {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xml.byteInputStream(Charsets.UTF_8))
        doc.documentElement?.normalize()

        val channelTitle = (doc.getElementsByTagName("channel").item(0) as? Element)
            ?.let { firstChildText(it, "title") }

        val itemNodes = doc.getElementsByTagName("item")
        if (itemNodes.length > 0) {
            val items = mutableListOf<RssItem>()
            for (i in 0 until itemNodes.length) {
                val el = itemNodes.item(i) as? Element ?: continue
                val title = firstChildText(el, "title") ?: "(untitled)"
                val pageLink = firstChildText(el, "link") ?: ""
                val enclosureUrl = extractEnclosureUrl(el)
                val pubDate = firstChildText(el, "pubDate") ?: firstChildText(el, "date") ?: ""
                items.add(RssItem(title, pageLink, downloadUrlFor(enclosureUrl, pageLink), pubDate))
            }
            return RssFetchResult(channelTitle, items)
        }

        // Not RSS 2.0 (no <item>s) — try Atom (<feed><entry>...</entry></feed>).
        val feedTitle = (doc.getElementsByTagName("feed").item(0) as? Element)
            ?.let { firstChildText(it, "title") } ?: channelTitle

        val entryNodes = doc.getElementsByTagName("entry")
        val items = mutableListOf<RssItem>()
        for (i in 0 until entryNodes.length) {
            val el = entryNodes.item(i) as? Element ?: continue
            val title = firstChildText(el, "title") ?: "(untitled)"
            val pageLink = extractAtomLink(el)
            val enclosureUrl = extractEnclosureUrl(el)
            val pubDate = firstChildText(el, "updated") ?: firstChildText(el, "published") ?: ""
            items.add(RssItem(title, pageLink, downloadUrlFor(enclosureUrl, pageLink), pubDate))
        }

        return RssFetchResult(feedTitle, items)
    }

    /** Direct-child text lookup — getElementsByTagName on an element only searches that
     *  element's own subtree, so this is safe to use on an <item>/<entry> without picking
     *  up a same-named tag from a sibling item. */
    private fun firstChildText(parent: Element, tagName: String): String? {
        val nodes = parent.getElementsByTagName(tagName)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.parentNode == parent) {
                val text = node.textContent?.trim()
                if (!text.isNullOrEmpty()) return text
            }
        }
        return null
    }

    private fun extractEnclosureUrl(item: Element): String? {
        val enclosures = item.getElementsByTagName("enclosure")
        for (i in 0 until enclosures.length) {
            val node = enclosures.item(i)
            if (node.parentNode == item && node is Element) {
                val url = node.getAttribute("url")
                if (url.isNotBlank()) return url.trim()
            }
        }
        return null
    }

    /** Atom <entry><link href="..." rel="alternate"/></entry> — link is an empty element
     *  carrying an href attribute, not text content, so this can't reuse firstChildText().
     *  Prefers rel="alternate" (the item's own page) when more than one <link> is present. */
    private fun extractAtomLink(entry: Element): String {
        val links = entry.getElementsByTagName("link")
        var fallback = ""
        for (i in 0 until links.length) {
            val node = links.item(i)
            if (node.parentNode == entry && node is Element) {
                val href = node.getAttribute("href")
                if (href.isBlank()) continue
                val rel = node.getAttribute("rel")
                if (rel.isBlank() || rel.equals("alternate", ignoreCase = true)) {
                    return href.trim()
                }
                if (fallback.isBlank()) fallback = href.trim()
            }
        }
        return fallback
    }
}