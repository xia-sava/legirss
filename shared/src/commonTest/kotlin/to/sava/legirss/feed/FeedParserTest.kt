package to.sava.legirss.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeedParserTest {
    private val feedUrl = "https://example.com/blog/feed.xml"

    private val rss2 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel>
            <title>  Example
              Blog </title>
            <link>https://example.com/blog/</link>
            <description>About</description>
            <item>
              <title>First &amp; foremost</title>
              <link>/blog/first</link>
              <guid isPermaLink="false">tag:example.com,2026:first</guid>
              <description><![CDATA[<p>Summary
        text</p>]]></description>
              <content:encoded><![CDATA[<p>Full <b>body</b></p>]]></content:encoded>
              <dc:creator>Alice</dc:creator>
              <category>Tech</category>
              <category>Other</category>
              <pubDate>Fri, 14 Aug 2026 18:30:29 +0900</pubDate>
            </item>
            <item>
              <guid>https://example.com/blog/second</guid>
              <description>&lt;p&gt;Only a   description here&lt;/p&gt;</description>
            </item>
            <item>
              <link>https://example.com/blog/empty</link>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private val rdf = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/"
                 xmlns:dc="http://purl.org/dc/elements/1.1/">
          <channel rdf:about="https://example.com/">
            <title>RDF Site</title>
            <link>https://example.com/</link>
          </channel>
          <item rdf:about="https://example.com/a">
            <title>RDF item</title>
            <link>https://example.com/a</link>
            <dc:date>2026-08-14T18:30:29+09:00</dc:date>
            <dc:subject>News</dc:subject>
          </item>
        </rdf:RDF>
    """.trimIndent()

    private val atom = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://atom.example/base/">
          <title type="text">Atom Site</title>
          <link rel="self" href="https://atom.example/feed"/>
          <link rel="alternate" href="/"/>
          <entry>
            <id>urn:uuid:1</id>
            <title type="html">Atom &lt;em&gt;entry&lt;/em&gt;</title>
            <link rel="enclosure" href="https://atom.example/file.mp3"/>
            <link href="posts/1"/>
            <summary>Short</summary>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>X <i>html</i></p></div></content>
            <author><name>Bob</name></author>
            <category term="Life"/>
            <updated>2026-08-15T00:00:00Z</updated>
            <published>2026-08-14T09:30:29Z</published>
          </entry>
        </feed>
    """.trimIndent()

    /** RSS 2.0 のチャンネルのタイトルとリンクを読み、タイトルの空白をまとめる。 */
    @Test
    fun readsRssChannel() {
        val feed = assertNotNull(FeedParser.parse(rss2, feedUrl))

        assertEquals("Example Blog", feed.title)
        assertEquals("https://example.com/blog/", feed.siteUrl)
        assertEquals(2, feed.items.size)
    }

    /** RSS 2.0 の記事の各項目を読む。相対リンクはチャンネルのリンクを基準に解決し、本文の改行は保つ。 */
    @Test
    fun readsRssItem() {
        val item = assertNotNull(FeedParser.parse(rss2, feedUrl)).items[0]

        assertEquals("tag:example.com,2026:first", item.guid)
        assertEquals("tag:example.com,2026:first", item.identity)
        assertEquals("First & foremost", item.title)
        assertEquals("https://example.com/blog/first", item.link)
        assertEquals("<p>Summary\ntext</p>", item.summary)
        assertEquals("<p>Full <b>body</b></p>", item.content)
        assertEquals("Alice", item.author)
        assertEquals("Tech", item.category)
        assertEquals(1_786_699_829_000L, item.publishedAt)
    }

    /** リンクが無ければ URL の形の guid をリンクにし、タイトルが無ければ要約の先頭をタイトルにする。 */
    @Test
    fun fallsBackWhenRssItemLacksLinkOrTitle() {
        val item = assertNotNull(FeedParser.parse(rss2, feedUrl)).items[1]

        assertEquals("https://example.com/blog/second", item.link)
        assertEquals("Only a description here", item.title)
        assertNull(item.publishedAt)
    }

    /** RSS 1.0 では記事が channel の外に並ぶ。日時は dc:date、カテゴリは dc:subject から読む。 */
    @Test
    fun readsRdf() {
        val feed = assertNotNull(FeedParser.parse(rdf, feedUrl))
        val item = feed.items.single()

        assertEquals("RDF Site", feed.title)
        assertEquals("RDF item", item.title)
        assertEquals("https://example.com/a", item.identity)
        assertEquals("News", item.category)
        assertEquals(1_786_699_829_000L, item.publishedAt)
    }

    /** Atom のフィードと記事を読む。リンクは alternate のものを選び、xml:base を基準に解決する。 */
    @Test
    fun readsAtom() {
        val feed = assertNotNull(FeedParser.parse(atom, feedUrl))
        val entry = feed.items.single()

        assertEquals("Atom Site", feed.title)
        assertEquals("https://atom.example/", feed.siteUrl)
        assertEquals("urn:uuid:1", entry.guid)
        assertEquals("Atom <em>entry</em>", entry.title)
        assertEquals("https://atom.example/base/posts/1", entry.link)
        assertEquals("Short", entry.summary)
        assertEquals("Bob", entry.author)
        assertEquals("Life", entry.category)
    }

    /** Atom の xhtml の本文は中のマークアップを取り出し、日時は updated より published を優先する。 */
    @Test
    fun readsAtomContentAndDate() {
        val entry = assertNotNull(FeedParser.parse(atom, feedUrl)).items.single()

        assertEquals("<p>X <i>html</i></p>", entry.content?.replace(Regex("""\s+"""), " ")?.replace("> <", "><"))
        assertEquals(1_786_699_829_000L, entry.publishedAt)
    }

    /** ルート要素がフィードのものでなければ null を返す。 */
    @Test
    fun rejectsNonFeedDocuments() {
        assertNull(FeedParser.parse("<html><head><title>Page</title></head><body></body></html>", feedUrl))
        assertNull(FeedParser.parse("", feedUrl))
        assertNull(FeedParser.parse("<rss version=\"2.0\"></rss>", feedUrl))
    }

    /** 相対 URL を基準の URL から解決し、絶対 URL はそのまま返す。 */
    @Test
    fun resolvesRelativeUrls() {
        assertEquals("https://example.com/a/c", resolveUrl("https://example.com/a/b", "c"))
        assertEquals("https://example.com/c", resolveUrl("https://example.com/a/b", "/c"))
        assertEquals("https://other.example/x", resolveUrl("https://example.com/a/b", "https://other.example/x"))
        assertEquals("c", resolveUrl(null, "c"))
    }

    /** 相対 URL に含まれる `.` と `..` を解決する。ルートより上へは出ない。ディレクトリを指す末尾の区切りは保つ。 */
    @Test
    fun resolvesDotSegments() {
        assertEquals("https://example.com/c", resolveUrl("https://example.com/a/b", "../c"))
        assertEquals("https://example.com/a/c", resolveUrl("https://example.com/a/b", "./c"))
        assertEquals("https://example.com/c", resolveUrl("https://example.com/a/b", "../../c"))
        assertEquals("https://example.com/a/", resolveUrl("https://example.com/a/b/c", ".."))
        assertEquals("https://example.com/x?q=1", resolveUrl("https://example.com/a/b", "../x?q=1"))
    }
}
