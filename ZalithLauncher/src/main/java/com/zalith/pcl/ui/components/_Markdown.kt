/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.zalith.pcl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.markdown.AstBlockNodeComposer
import com.halilibo.richtext.markdown.BasicMarkdown
import com.halilibo.richtext.markdown.node.AstNode
import com.halilibo.richtext.ui.CodeBlockStyle
import com.halilibo.richtext.ui.ListStyle
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.TableStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle
import com.zalith.pcl.ui.theme.cardColor

@Composable
fun MarkdownView(
    content: String,
    modifier: Modifier = Modifier,
    richTextStyle: RichTextStyle = defaultRichTextStyle(),
) {
    RichText(
        modifier = modifier,
        style = richTextStyle
    ) {
        Markdown(content = softWrapMarkdownUrls(content))
    }
}

/**
 * 把 Markdown 里的链接改写成显式链接 `[文本](地址)`。
 *
 * 两个问题一起解决：
 * 1) **折行**：Compose 不会在「一个词」内部折行，一串 URL 会被当成超长 token 顶出容器
 *    （首次启动的协议弹窗只有 90% 屏宽）。这里往**显示文本**里按 24 字符插零宽空格，
 *    零宽空格不占宽度，复制出来也不影响阅读；
 * 2) **链接边界**：CommonMark 的自动链接会把紧跟 URL 的全角标点一起吞进地址
 *    （`<https://…/PCL2）。PCL2>` 连「）。PCL2」都成了链接的一部分）。
 *    改写成 `[文本](地址)` 后，地址只取 URL 本身，后面的标点回到正文。
 *
 * 代码块（``` 围起来的段）原样保留；`[文字](地址)` 的链接目标也不碰。
 */
private val URL_PATTERN = Regex(
    "<https?://[^\\s<>]+>|https?://[^\\s<>\\[\\]`\u3000-\u303F\uFF00-\uFFEF]+"
)

private fun softWrapMarkdownUrls(content: String): String {
    if (!content.contains("http")) return content
    // 按代码块切开，奇数下标是 ``` 里面的内容，原样放回
    return content.split("```").mapIndexed { index, part ->
        if (index % 2 == 1) part else rewriteUrls(part)
    }.joinToString("```")
}

private fun rewriteUrls(segment: String): String {
    return URL_PATTERN.replace(segment) { match ->
        val raw = match.value
        val isAutolink = raw.startsWith("<")
        // `[文字](地址)` 里的地址：已经是显式链接，别再包一层
        val start = match.range.first
        val isLinkTarget = start >= 2 && segment[start - 1] == '(' && segment[start - 2] == ']'
        if (isLinkTarget) return@replace raw

        val inner = if (isAutolink) raw.removePrefix("<").removeSuffix(">") else raw
        // 结尾的 ASCII 标点不属于地址
        val url = inner.trimEnd('.', ',', ';', ':', '!', '?', ')')
        val tail = if (isAutolink) "" else inner.substring(url.length)
        if (url.isEmpty()) return@replace raw

        val display = if (url.length <= 24) url else url.chunked(24).joinToString("\u200B")
        "[$display]($url)$tail"
    }
}

@Composable
fun MarkdownView(
    node: AstNode,
    modifier: Modifier = Modifier,
    astBlockNodeComposer: AstBlockNodeComposer? = null,
    richTextStyle: RichTextStyle = defaultRichTextStyle(),
) {
    RichText(
        modifier = modifier,
        style = richTextStyle
    ) {
        BasicMarkdown(
            astNode = node,
            astBlockNodeComposer = astBlockNodeComposer
        )
    }
}

@Composable
fun defaultRichTextStyle(
    influencedByBackground: Boolean = true,
    codeBackground: Color = cardColor(influencedByBackground),
    headingColor: Color = MaterialTheme.colorScheme.primary,
    tableColor: Color = MaterialTheme.colorScheme.outlineVariant,
    linkColor: Color = MaterialTheme.colorScheme.secondary,
    linkBackgroundColor: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
): RichTextStyle {
    return RichTextStyle(
        headingStyle = { level, textStyle ->
            when (level) {
                0 -> TextStyle(
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = headingColor,
                )
                1 -> TextStyle(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = headingColor,
                )
                2 -> TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = headingColor,
                )
                3 -> TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = headingColor,
                )
                4 -> TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = headingColor,
                )
                5 -> TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = headingColor,
                )
                else -> textStyle
            }
        },
        listStyle = ListStyle(

        ),
        codeBlockStyle = CodeBlockStyle(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = codeBackground,
                    shape = MaterialTheme.shapes.small
                ).horizontalScroll(
                    state = rememberScrollState()
                ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            padding = 8.sp
        ),
        tableStyle = TableStyle(
            borderColor = tableColor,
        ),
        stringStyle = RichTextStringStyle(
            linkStyle = TextLinkStyles(
                style = SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Bold
                ),
                pressedStyle = SpanStyle(
                    color = linkColor,
                    background = linkBackgroundColor,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Bold
                )
            ),
            codeStyle = SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = codeBackground
            )
        )
    )
}
