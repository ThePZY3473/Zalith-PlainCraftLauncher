/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 上游版权：Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 * 本文件自 2026 年起被修改；修改声明与名称/标识的处理见仓库根目录 NOTICE.md。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。分发时请保留本声明、上游版权与 LICENSE。
 */

package com.zalith.pcl.ui.pcl2

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zalith.pcl.R
import com.zalith.pcl.legal.AgreementStore
import com.zalith.pcl.setting.launcherMMKV
import com.zalith.pcl.ui.pcl2.components.PclButton
import com.zalith.pcl.ui.pcl2.components.PclColorState
import com.zalith.pcl.ui.pcl2.components.PclText
import com.zalith.pcl.ui.pcl2.theme.PclDimens
import com.zalith.pcl.ui.pcl2.theme.PclPalettes
import com.zalith.pcl.ui.pcl2.theme.PclTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.zalith.pcl.ui.pcl2.theme.PclTextStyle
import com.zalith.pcl.ui.pcl2.theme.pclPalette
import kotlinx.coroutines.delay

/**
 * 条款版本不再写死成整数：以 `assets/legal/data.json` 的 `version` 为准，
 * 改协议内容时只要更新那个字段，用户下次启动就会重新看到并重新确认。
 */
private const val LEGACY_TERMS_KEY = "terms_accepted_version"

/** 必须停留的最短时间（秒）。 */
private const val MIN_READ_SECONDS = 10

/** 把老的整数版本记录清掉，避免它一直留在 MMKV 里没有意义。 */
private fun dropLegacyTermsRecord() {
    runCatching { launcherMMKV().remove(LEGACY_TERMS_KEY) }
}

/** 当前协议是否已经同意过（版本一致即算同意）。 */
fun pclTermsAccepted(context: android.content.Context): Boolean {
    val version = AgreementStore.load(context)?.version
    // 清单读不到时不拦人，避免安装包异常时把用户挡在门外
    if (version == null) return true
    dropLegacyTermsRecord()
    return AgreementStore.acceptedVersion(context) == version
}

private fun markTermsAccepted(context: android.content.Context) {
    val version = AgreementStore.load(context)?.version ?: return
    AgreementStore.markAccepted(context, version)
}

/**
 * 首次启动的条款确认页。
 *
 * 三个硬性条件全部满足才允许点「同意」：
 *  1. 正文**滚动到最底部**（内容不足一屏时直接算已到底）；
 *  2. 从打开本页起**停留满 10 秒**；
 *  3. 用户主动点击按钮（不自动跳转）。
 *
 * 未同意前返回键被拦截，只能用底部「退出」离开 —— 避免「随手按返回」跳过条款。
 */
@Composable
fun PclTermsScreen(onAccept: () -> Unit) {
    PclTheme(palette = PclPalettes.blue) {
        PclTermsContent(onAccept = onAccept)
    }
}

@Composable
private fun PclTermsContent(onAccept: () -> Unit) {
    val p = pclPalette
    val context = LocalContext.current
    // 两份文件：使用条款 + 隐私政策（清单来自 assets/legal/data.json）
    val docs = remember { AgreementStore.docsForLocale(context) }
    var index by remember { mutableIntStateOf(0) }
    val scrolls = remember { mutableMapOf<Int, ScrollState>() }
    val readDocs = remember { mutableStateMapOf<Int, Boolean>() }

    var startedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var now by remember { mutableLongStateOf(startedAt) }

    // 计时器：200ms 一跳，够刷新「还需 N 秒」，又不会每帧重组。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            now = SystemClock.elapsedRealtime()
        }
    }

    val waitedSeconds = ((now - startedAt) / 1000L).toInt()
    val remainingSeconds = (MIN_READ_SECONDS - waitedSeconds).coerceAtLeast(0)
    val scroll = scrolls.getOrPut(index) { ScrollState(0) }
    // 内容不足一屏时 maxValue 就是 0，此时视为「已读到结尾」。
    LaunchedEffect(scroll, index) {
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (value, max) ->
            if (max == 0 || value >= max - 4) readDocs[index] = true
        }
    }
    val allRead = docs.isNotEmpty() && docs.indices.all { readDocs[it] == true }
    val canAccept = allRead && remainingSeconds == 0

    BackHandler(enabled = true) {
        // 未同意前不允许返回键退出；要离开请用底部「退出」按钮。
    }

    Column(modifier = Modifier.fillMaxSize().background(p.bg1)) {
        // ---- 标题栏：跟主界面同一套配方（三段渐变 + 应用图标），不再是纯色通栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PclDimens.titleBarHeight)
                .background(Brush.horizontalGradient(p.titleBarGradient)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    // 必须用 PNG 版 logo：`R.mipmap.ic_launcher` 在 Android 8+ 是
                    // `<adaptive-icon>` XML，Compose 的 `painterResource` 不认这种 drawable，
                    // 会抛 IllegalArgumentException（Only VectorDrawables and rasterized
                    // asset types are supported）—— 启动即崩就是它。
                    painter = painterResource(R.drawable.ic_launcher_logo),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                PclText(text = "ZPCL Launcher", color = Color.White, fontSize = 16, bold = true)
                PclText(
                    text = "使用条款与隐私政策",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12,
                )
            }
        }

        // ---- 文档切换（通常就是「使用条款」「隐私政策」两份）
        if (docs.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                docs.forEachIndexed { i, doc ->
                    PclButton(
                        text = doc.title,
                        enabled = i != index,
                        onClick = { index = i },
                    )
                }
            }
        }

        // ---- 正文（唯一可滚动区域，按当前文档滚动）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            val doc = docs.getOrNull(index)
            if (doc == null) {
                PclBody("协议内容读取失败，请检查安装包是否完整。")
            } else {
                LegalMarkdown(doc.body)
            }
            Spacer(modifier = Modifier.height(20.dp))
            PclText(
                text = if (doc == null) "" else "—— 以上为全部内容 ——",
                color = p.gray3,
                fontSize = 12,
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // ---- 底部操作区（固定在窗口底部，不随正文滚动）
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(p.gray5))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PclText(
                text = when {
                    docs.isEmpty() -> "协议内容不可用，可直接继续"
                    remainingSeconds > 0 -> "请阅读条款，还需 $remainingSeconds 秒"
                    !allRead -> "还有 ${docs.indices.count { readDocs[it] != true }} 份未读完"
                    else -> "已阅读完毕，可以继续"
                },
                color = if (canAccept) p.gray2 else p.gray3,
                fontSize = 12,
                modifier = Modifier.weight(1f),
            )
            PclButton(
                text = "退出",
                colorState = PclColorState.Red,
                onClick = { (context as? Activity)?.finish() },
            )
            PclButton(
                text = "我已阅读并同意",
                enabled = canAccept,
                onClick = {
                    markTermsAccepted(context)
                    onAccept()
                },
            )
        }
    }
}

@Composable
private fun PclSection(text: String) {
    val p = pclPalette
    Spacer(modifier = Modifier.height(14.dp))
    PclText(text = text, color = p.color2, fontSize = 14, bold = true)
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun PclBody(text: String) {
    val p = pclPalette
    // 条款正文里的 **加粗** 标记直接去掉，PclText 不支持富文本；保留纯文本即可。
    PclText(
        text = text.replace("**", ""),
        color = p.color1,
        fontSize = 13,
        modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
    )
}

/**
 * 极简 Markdown 渲染：只认标题、二级标题、列表项和普通段落。
 *
 * 协议原文放在 `assets/legal/` 下的 .md 文件里，没必要为了几十行文字引入完整的 Markdown 库：
 * 这里把 `#`/`##`/`-` 和 `**加粗**` 处理掉，其余按段落原样显示。
 */
@Composable
private fun LegalMarkdown(markdown: String) {
    val lines = markdown.lines()
    lines.forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.isBlank() -> Unit
            // 文档标题：# 开头的第一行，用大一号的字显示出来
            line.startsWith("# ") -> PclDocTitle(line.removePrefix("# ").trim())
            line.startsWith("## ") -> PclSection(line.removePrefix("## ").trim())
            line.startsWith("- ") -> PclRichBody("· " + softWrapUrls(line.removePrefix("- ").trim()))
            else -> PclRichBody(softWrapUrls(line))
        }
    }
}

/**
 * 给长链接插入零宽空格，让它能在任意位置折行。
 *
 * Compose 的文本按「词」折行，一串没有空格的 URL 会被当成一个超长单词直接顶出屏幕
 * （协议里的 GitHub 链接就是这样溢出的）。零宽空格不占宽度，复制出来也不影响阅读。
 */
private fun softWrapUrls(text: String): String {
    // 协议原文里写成 `<https://…>` 的自动链接，这里是纯文本渲染，尖括号要摘掉
    val plain = text.replace(Regex("<(https?://[^>]+)>"), "$1")
    val url = Regex("https?://[^\\s<>\u3000-\u303F\uFF00-\uFFEF]+")
    return url.replace(plain) { match ->
        val value = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')')
        if (value.length <= 24) value else value.chunked(24).joinToString("\u200B")
    }
}

/** 协议文档的大标题。 */
@Composable
private fun PclDocTitle(text: String) {
    val p = pclPalette
    PclText(
        text = text,
        color = p.color1,
        fontSize = 17,
        bold = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

/**
 * 支持 `**加粗**` 的正文。
 *
 * 协议里的「**项目主体：**」「**版本：**」这类标签在原文档里是加粗的，
 * 直接当纯文本渲染会跟正文混成一片；这里按段内样式渲染，标题和标签才立得住。
 */
@Composable
private fun PclRichBody(text: String) {
    val p = pclPalette
    val annotated = buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val start = text.indexOf("**", index)
            if (start < 0) {
                append(text.substring(index))
                break
            }
            val end = text.indexOf("**", start + 2)
            if (end < 0) {
                append(text.substring(index))
                break
            }
            append(text.substring(index, start))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.substring(start + 2, end))
            }
            index = end + 2
        }
    }
    Text(
        text = annotated,
        style = PclTextStyle.base(p).copy(
            color = p.color1,
            fontSize = 13.sp,
            lineHeight = PclTextStyle.lineHeight(13),
        ),
        overflow = TextOverflow.Visible,
        modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
    )
}
