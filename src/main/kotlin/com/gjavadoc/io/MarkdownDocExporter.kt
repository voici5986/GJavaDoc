package com.gjavadoc.io

import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.emoji.EmojiExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.toc.TocExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.html.HtmlRenderer

/**
 * Convert Markdown to a Word-compatible HTML string and save as .doc.
 * We intentionally output HTML with a .doc extension because Microsoft Word
 * opens such files seamlessly and preserves tables and basic formatting well.
 */
object MarkdownDocExporter {
    private val options = MutableDataSet().apply {
        set(Parser.EXTENSIONS, listOf(
            TablesExtension.create(),
            AutolinkExtension.create(),
            StrikethroughExtension.create(),
            TaskListExtension.create(),
            TocExtension.create(),
            EmojiExtension.create(),
        ))
        set(TablesExtension.WITH_CAPTION, false)
        set(TablesExtension.COLUMN_SPANS, false)
        set(TablesExtension.APPEND_MISSING_COLUMNS, true)
        set(TablesExtension.DISCARD_EXTRA_COLUMNS, true)
        set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true)
    }

    private val parser: Parser = Parser.builder(options).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder(options)
        // Allow raw HTML blocks/inline so complex tables render instead of being escaped
        .escapeHtml(false)
        .softBreak("\n")
        .build()

    fun renderToWordHtml(markdown: String, title: String = "GJavaDoc"): String {
        val doc: Node = parser.parse(markdown)
        val body = renderer.render(doc)
        // Basic CSS to make tables and code blocks readable in Word
        val css = """
            body { font-family: -apple-system, Segoe UI, Arial, Helvetica, sans-serif; font-size: 12pt; }
            table { border-collapse: collapse; width: 100%; }
            th, td { border: 1px solid #999; padding: 4pt 6pt; }
            th { background: #f0f0f0; }
            pre, code { font-family: SFMono-Regular, Consolas, Menlo, monospace; }
            pre { background: #f8f8f8; border: 1px solid #e0e0e0; padding: 8pt; overflow-x: auto; }
            h1, h2, h3 { border-bottom: 1px solid #e0e0e0; padding-bottom: 2pt; }
        """.trimIndent()
        return buildString {
            append("<!DOCTYPE html>\n")
            append("<html><head>\n")
            append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("<title>")
            append(escapeHtml(title))
            append("</title>\n")
            append("<style>\n")
            append(css)
            append("\n</style>\n")
            append("</head><body>\n")
            append(body)
            append("\n</body></html>")
        }
    }

    private fun escapeHtml(text: String): String = buildString {
        for (ch in text) when (ch) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '&' -> append("&amp;")
            else -> append(ch)
        }
    }
}
