package com.jarvis.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Radius
import com.jarvis.core.designsystem.Spacing
import kotlinx.coroutines.delay

/** Monospace family for inline code / code blocks (Menlo substitute). */
private object JarvisFont {
    val mono: FontFamily = FontFamily.Monospace
}

/**
 * Renders a markdown string as a column of styled text blocks, using [parseMarkdown].
 *
 * Styling: assistant prose in the serif family with a 1.55 line rhythm, headings in
 * the serif semibold ramp, code blocks on the tinted surface with a language-label
 * header strip, hairline border and a copy button.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> StyledText(block.spans, JarvisText.AssistantBody)
                is MdBlock.Heading ->
                    StyledText(
                        block.spans,
                        when (block.level) {
                            1 -> JarvisText.H1
                            2 -> JarvisText.H2
                            else -> JarvisText.H3
                        },
                    )
                is MdBlock.CodeBlock -> CodeBlock(block)
                is MdBlock.TableBlock -> TableBlockView(block)
                is MdBlock.BulletList -> ListBlock(block.items, numbered = false)
                is MdBlock.NumberedList -> ListBlock(block.items, numbered = true)
                is MdBlock.Quote -> QuoteBlock(block)
                MdBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun StyledText(
    spans: List<MdSpan>,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    val annotated =
        buildAnnotatedString {
            spans.forEach { span ->
                val spanStyle =
                    SpanStyle(
                        fontWeight =
                            when {
                                span.bold -> FontWeight.SemiBold
                                else -> style.fontWeight
                            },
                        fontStyle = if (span.italic) FontStyle.Italic else style.fontStyle,
                        fontFamily = if (span.code) JarvisFont.mono else style.fontFamily,
                        fontSize = if (span.code) JarvisText.Code.fontSize else style.fontSize,
                        // Warm inline-code chip — warm surface, never blue.
                        background =
                            if (span.code) {
                                if (isSystemInDarkTheme()) {
                                    JarvisColors.Dark.codeInlineBg
                                } else {
                                    JarvisColors.Light.codeInlineBg
                                }
                            } else {
                                Color.Unspecified
                            },
                    )
                if (span.url != null) {
                    pushStringAnnotation(tag = "URL", annotation = span.url)
                    // Links use the single accent color.
                    pushStyle(spanStyle.copy(color = JarvisColors.Accent.orange))
                    append(span.text)
                    pop()
                    pop()
                } else {
                    withStyle(spanStyle) { append(span.text) }
                }
            }
        }
    Text(
        text = annotated,
        style = style,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun QuoteBlock(block: MdBlock.Quote) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .width(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(JarvisColors.Accent.orange),
        ) {}
        StyledText(
            block.spans,
            JarvisText.AssistantBodyItalic,
            Modifier
                .padding(start = Spacing.md)
                .weight(1f),
        )
    }
}

@Composable
private fun CodeBlock(block: MdBlock.CodeBlock) {
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    // Warm-dark code surfaces in both themes (claude spec — never blue-tinted).
    val dark = isSystemInDarkTheme()
    val codeBg = if (dark) JarvisColors.Dark.codeBg else JarvisColors.Light.codeBg
    val codeBorder = if (dark) JarvisColors.Dark.codeBorder else JarvisColors.Light.codeBorder
    val codeHeader = if (dark) JarvisColors.Dark.codeHeader else JarvisColors.Light.codeHeader
    val codeFg = if (dark) JarvisColors.Dark.codeFg else JarvisColors.Light.codeFg

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(JarvisShapes.codeBlock)
                .background(codeBg)
                .border(1.dp, codeBorder, JarvisShapes.codeBlock),
    ) {
        // Header strip: language label + copy button.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(codeHeader.copy(alpha = 0.5f))
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language ?: "code",
                style = JarvisText.CodeLabel,
                color = codeFg.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy code",
                tint = codeFg.copy(alpha = 0.85f),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(Radius.small))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                        }.padding(Spacing.md)
                        .size(Spacing.xl),
            )
        }
        HorizontalDivider(color = codeBorder)
        Text(
            text = block.code,
            style = JarvisText.Code,
            color = codeFg,
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(Spacing.lg),
        )
    }
}

/**
 * Renders a GFM table: header row emphasized (semibold on the tinted surface), body rows
 * separated by hairlines, cells weight-distributed so columns align. Wide tables scroll
 * horizontally inside the rounded container.
 */
@Composable
private fun TableBlockView(block: MdBlock.TableBlock) {
    val dark = isSystemInDarkTheme()
    val codeBorder = if (dark) JarvisColors.Dark.codeBorder else JarvisColors.Light.codeBorder
    // Header emphasis per the spec: semibold text on the low-tinted surface.
    val headerBg = MaterialTheme.colorScheme.surfaceContainerLow

    // The whole table scrolls as one — per-row scroll states would desync header and
    // body columns, so the scrollable lives on the table, never on a row. Clip and
    // border sit outside the scroll so the rounded frame stays fixed while content
    // scrolls under it.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(JarvisShapes.codeBlock)
                .border(1.dp, codeBorder, JarvisShapes.codeBlock)
                .horizontalScroll(rememberScrollState()),
    ) {
        TableRow(cells = block.header, columns = block.columnCount, emphasized = true, background = headerBg)
        HorizontalDivider(color = codeBorder)
        block.rows.forEachIndexed { index, row ->
            TableRow(
                cells = row,
                columns = block.columnCount,
                emphasized = false,
                background = Color.Unspecified,
            )
            if (index < block.rows.lastIndex) {
                HorizontalDivider(color = codeBorder.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<List<MdSpan>>,
    columns: Int,
    emphasized: Boolean,
    background: Color,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (background == Color.Unspecified) {
                        Modifier
                    } else {
                        Modifier.background(background)
                    },
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEach { cell ->
            StyledText(
                spans = cell,
                style =
                    if (emphasized) {
                        JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        JarvisText.AssistantBody
                    },
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
        }
        // Pad short rows so every table keeps its column shape.
        repeat((columns - cells.size).coerceAtLeast(0)) {
            Text(
                text = "",
                style = JarvisText.AssistantBody,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
        }
    }
}

@Composable
private fun ListBlock(
    items: List<List<MdSpan>>,
    numbered: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        items.forEachIndexed { index, spans ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (numbered) "${index + 1}." else "•",
                    style = JarvisText.AssistantBody,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(20.dp),
                )
                StyledText(spans, JarvisText.AssistantBody)
            }
        }
    }
}
