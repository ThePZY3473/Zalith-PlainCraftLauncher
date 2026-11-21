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
 * 给 Markdown 里的裸链接插入零宽空格，让它能在任意位置折行。
 *
 * Compose 不会在「一个词」内部折行：一串 URL 会被当成超长 token，直接顶出容器。
 * 首次启动的协议弹窗只有 90% 屏宽，GitHub 链接必然溢出 —— 看上去就是「一整条连在一起」。
 * 零宽空格不占宽度，复制出来也不影响阅读。
 *
 * `[文字](https://…)` 这种链接**目标**里不插，否则点开就是坏地址。
 */
private val BARE_URL_PATTERN =
    Regex("https?://[^\\s<>\"'`\u3000-\u303F\uFF00-\uFFEF]+")

private fun softWrapMarkdownUrls(content: String): String {
    if (!content.contains("http")) return content
    return BARE_URL_PATTERN.replace(content) { match ->
        val start = match.range.first
        val isLinkTarget = start >= 2 && content[start - 1] == '(' && content[start - 2] == ']'
        val value = match.value
        when {
            isLinkTarget -> value
            value.length <= 24 -> value
            else -> value.chunked(24).joinToString("\u200B")
        }
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
