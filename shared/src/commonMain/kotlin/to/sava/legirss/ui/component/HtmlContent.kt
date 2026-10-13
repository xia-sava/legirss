package to.sava.legirss.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import to.sava.legirss.html.HtmlBlock
import to.sava.legirss.html.InlineStyle
import to.sava.legirss.html.InlineText
import to.sava.legirss.ui.theme.LegirssStyle
import to.sava.legirss.ui.theme.LocalLegirssStyle

private val BLOCK_SPACING = 8.dp
private val QUOTE_BAR_WIDTH = 3.dp
private val QUOTE_PADDING = 10.dp
private val LIST_MARKER_WIDTH = 20.dp
private val PREFORMATTED_PADDING = 6.dp
private val RULE_HEIGHT = 1.dp
private val LINE_HEIGHT = 1.5.em
private const val HEADING_LEVELS_WITH_LARGER_TEXT = 4

/** 記事の本文を、ブロックの並びとして縦に描く。 */
@Composable
fun HtmlContent(blocks: List<HtmlBlock>, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BLOCK_SPACING)) {
        blocks.forEach { HtmlBlockView(it, onOpenLink) }
    }
}

@Composable
private fun HtmlBlockView(block: HtmlBlock, onOpenLink: (String) -> Unit) {
    val style = LocalLegirssStyle.current
    when (block) {
        is HtmlBlock.Paragraph -> InlineTextView(block.text, onOpenLink, bold = false, sizeDelta = 0)

        is HtmlBlock.Heading -> InlineTextView(
            text = block.text,
            onOpenLink = onOpenLink,
            bold = true,
            sizeDelta = (HEADING_LEVELS_WITH_LARGER_TEXT - block.level).coerceAtLeast(0),
        )

        is HtmlBlock.Image -> AsyncImage(
            model = block.url,
            contentDescription = block.alt,
            modifier = block.link?.let { link ->
                Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { onOpenLink(link) }
            } ?: Modifier,
        )

        is HtmlBlock.Quote -> {
            val barColor = style.colors.border
            HtmlContent(
                blocks = block.blocks,
                onOpenLink = onOpenLink,
                modifier = Modifier
                    .drawBehind { drawRect(barColor, Offset.Zero, Size(QUOTE_BAR_WIDTH.toPx(), size.height)) }
                    .padding(start = QUOTE_PADDING),
            )
        }

        is HtmlBlock.ListItem -> Row {
            BasicText(block.marker, Modifier.width(LIST_MARKER_WIDTH), style.articleTextStyle())
            HtmlContent(block.blocks, onOpenLink, Modifier.weight(1f))
        }

        is HtmlBlock.Preformatted -> BasicText(
            text = block.text,
            modifier = Modifier
                .fillMaxWidth()
                .background(style.colors.panel)
                .horizontalScroll(rememberScrollState())
                .padding(PREFORMATTED_PADDING),
            style = style.articleTextStyle().copy(fontFamily = FontFamily.Monospace),
        )

        HtmlBlock.Rule -> Box(Modifier.fillMaxWidth().height(RULE_HEIGHT).background(style.colors.border))
    }
}

@Composable
private fun InlineTextView(text: InlineText, onOpenLink: (String) -> Unit, bold: Boolean, sizeDelta: Int) {
    val style = LocalLegirssStyle.current
    val annotated = remember(text, style) { text.toAnnotatedString(style, onOpenLink) }
    val base = style.articleTextStyle()
    BasicText(
        text = annotated,
        style = base.copy(
            fontSize = (base.fontSize.value + sizeDelta).sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
    )
}

private fun LegirssStyle.articleTextStyle() = textStyle(articleFontSize).copy(lineHeight = LINE_HEIGHT)

private fun InlineText.toAnnotatedString(style: LegirssStyle, onOpenLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        append(text)
        spans.forEach { span ->
            span.style?.let { addStyle(it.toSpanStyle(), span.start, span.end) }
            span.link?.let { url ->
                addLink(
                    LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(SpanStyle(color = style.colors.link)),
                        linkInteractionListener = { onOpenLink(url) },
                    ),
                    span.start,
                    span.end,
                )
            }
        }
    }

private fun InlineStyle.toSpanStyle(): SpanStyle = when (this) {
    InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    InlineStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
    InlineStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
}
