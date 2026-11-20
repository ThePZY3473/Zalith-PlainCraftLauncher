/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 上游版权：Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 * 本文件自 2026 年起被修改；修改声明与名称/标识的处理见仓库根目录 NOTICE.md。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。分发时请保留本声明、上游版权与 LICENSE。
 */

package com.zalith.pcl.upgrade

import com.zalith.pcl.path.GLOBAL_JSON
import com.zalith.pcl.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一个「能真的把安装包给你」的更新源。
 *
 * ---- 为什么要先探测再列出来
 *
 * 以前只有一条隐式链路：官网 `/api/apks` → 拿到的 uri 直接当下载地址。国内环境下
 * 这条链路有三处会断：官网域名本身可能连不上、官网转发的 GitHub 直链大概率握手超时、
 * gh-proxy 加速域名也时好时坏。而界面上**完全看不出来**是哪一环挂了 —— 用户看到的是
 * 「有新版本」→「立即更新」→ 进度条卡住 → 失败，只能一遍遍重试同一个坏源。
 *
 * 所以改成：有更新时先把候选源并发握手一遍，**连不上的直接不显示**，只把真正可用的
 * 源交给用户挑。用户选中的那个作为主源，其余可用源的同架构包地址作为备用，
 * 一起交给 [com.zalith.pcl.game.download.engine.DownloadEngine]（它本来就支持多源换源）。
 *
 * @param id 稳定标识（`site:https://host` / `github` / `github-proxy`），用于默认勾选与日志。
 * @param name 弹窗里显示的名字。
 * @param kind 来源类型，决定下载失败时的提示语气。
 * @param files 该源给出的安装包列表。直链源至少一条；[UpdateSourceKind.EXTERNAL] 为空。
 * @param latencyMs 握手耗时，用来排序和给用户一个「哪个快」的直观参考。
 * @param origin 该源的根地址或说明，仅用于日志与排查。
 * @param url 跳转类源的目标地址（网盘、群链接）；直链源为 null。
 */
data class UpgradeSource(
    val id: String,
    val name: String,
    val kind: UpdateSourceKind,
    val files: List<RemoteData.RemoteFile>,
    val latencyMs: Long,
    val origin: String,
    val url: String? = null,
)

enum class UpdateSourceKind {
    /** 官网自己的接口（可能给站内直链，也可能转发 GitHub）。 */
    SITE,

    /** `api.github.com` 的 Releases 接口。 */
    GITHUB,

    /** GitHub 下载地址前面加了一层反代前缀。 */
    GITHUB_PROXY,

    /**
     * 不提供直链，只能打开浏览器让用户自己去取（123 云盘、QQ 群）。
     *
     * 这类入口没法「下载完自动安装」，选中后启动器直接把链接丢给系统浏览器 ——
     * 也就是用户说的「不支持直链下载的，选完自动跳转」。
     */
    EXTERNAL,
}

/**
 * 一次探测的结果。
 *
 * @param siteAvailable 官网这一侧是否可用。官网不可用时，调用方不该再显示
 *   「官网下载方式」里的跳转入口（123 云盘、QQ 群）—— 它们本来就是从官网取的，
 *   官网都连不上，把这些条目列出来只会让用户点了没反应。
 * @param sources 弹窗里要列的下载源。
 */
data class UpdateSourceProbeResult(
    val siteAvailable: Boolean,
    val sources: List<UpgradeSource>,
)

/**
 * 更新源探测。
 *
 * 全程用 [HttpURLConnection] 而不是 Ktor 的 `GLOBAL_CLIENT`：这里要的是**毫秒级的失败**，
 * 而全局客户端自带 30 秒超时与两次重试，一个坏域名就能把整个弹窗拖到一分钟以上。
 * 探测只关心「TCP + TLS 握手能不能在 4 秒内完成、拿到一份能解析的列表」，用裸连接最直接。
 */
object UpdateSourceProbe {

    private const val TAG = "UpdateSourceProbe"

    /** 单个源的握手预算。超过就当作「这个源在当前网络下不可用」。 */
    private const val TIMEOUT_MS = 4_000

    private const val RELEASE_LATEST =
        "https://api.github.com/repos/zuanxu53-hub/ZPCL-Launcher/releases/latest"

    /**
     * gh-proxy 反代前缀。
     *
     * 与官网后台「下载方式」里那条加速地址同源（`axisnow.gh-proxy.org`）。写死是有意的：
     * 探测需要一个**不依赖官网接口**的独立入口，否则官网一挂，加速源也跟着消失。
     */
    private const val PROXY_PREFIX = "https://axisnow.gh-proxy.org/"

    /**
     * 探测更新源：**官网优先，官网整体失败才退回 GitHub**。
     *
     * 需求是「下载方式从官网拿，官网失败就只显示 GitHub 的」。所以这里不再像以前那样
     * 把官网和 GitHub 并列成两个平级选项 —— 两边同时出现时，用户会以为是两条不同的
     * 下载渠道，而官网多数时候转发的就是 GitHub 的同一批地址。
     *
     * @param siteBases 官网候选地址，按优先级排列。调用方负责去重与去尾斜杠。
     */
    suspend fun probeUpdate(siteBases: List<String>): UpdateSourceProbeResult = coroutineScope {
        // 官网的多个候选地址是**互为容灾**的关系，不是并列选项：
        // 三个都通时只留最快的那一个，否则会出现三条内容完全一样的「官网」。
        val bestSite = siteBases
            .map { base -> async(Dispatchers.IO) { probeSite(base) } }
            .awaitAll()
            .filterNotNull()
            .minByOrNull { it.latencyMs }

        if (bestSite == null) {
            // 官网整体不可用：只给 GitHub（直连 + 加速）
            val github = async(Dispatchers.IO) { probeGithub() }.await()
            val proxy = if (github != null) {
                async(Dispatchers.IO) { probeProxy(github.files) }.await()
            } else {
                null
            }
            return@coroutineScope UpdateSourceProbeResult(
                siteAvailable = false,
                sources = listOfNotNull(github, proxy).sortedBy { it.latencyMs },
            )
        }

        // 官网可用：只列官网这一条。
        // 但它转发的是 GitHub 直链时，再补一条加速通道 —— 那不是「另一个来源」，
        // 只是同一条地址的反代；官网通、GitHub 被墙时，这条就是唯一的活路。
        val proxy = if (bestSite.files.all { it.uri.startsWith("https://github.com/") }) {
            async(Dispatchers.IO) { probeProxy(bestSite.files) }.await()
        } else {
            null
        }
        UpdateSourceProbeResult(
            siteAvailable = true,
            sources = listOfNotNull(bestSite, proxy).sortedBy { it.latencyMs },
        )
    }

    // ---------------------------------------------------------------- 各源探测

    private fun probeSite(base: String): UpgradeSource? {
        val started = System.nanoTime()
        val body = httpGet("$base/api/apks", accept = "application/json") ?: return null
        val parsed = runCatching {
            GLOBAL_JSON.decodeFromString(SiteApkListApi.serializer(), body)
        }.getOrElse { e ->
            Logger.warning(TAG, "官网列表解析失败 $base：${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val files = parsed.files.mapNotNull { f ->
            f.fileName.takeIf { it.isNotBlank() }?.let { name ->
                RemoteData.RemoteFile(
                    fileName = name,
                    uri = f.uri,
                    arch = archFromSiteText(f.arch),
                    size = f.size,
                )
            }
        }
        if (files.isEmpty()) {
            Logger.info(TAG, "官网列表为空，跳过 $base")
            return null
        }
        val direct = files.any { it.uri.contains("/download/apk/") }
        val host = hostOf(base).ifEmpty { base }
        return UpgradeSource(
            id = "site:$base",
            name = if (direct) "官网直下 · $host" else "官网转发 · $host",
            kind = UpdateSourceKind.SITE,
            files = files,
            latencyMs = elapsedMs(started),
            origin = base,
        )
    }

    private fun probeGithub(): UpgradeSource? {
        val started = System.nanoTime()
        val body = httpGet(RELEASE_LATEST, accept = "application/vnd.github+json") ?: return null
        val parsed = runCatching {
            GLOBAL_JSON.decodeFromString(GithubReleaseApi.serializer(), body)
        }.getOrElse { e ->
            Logger.warning(TAG, "GitHub Releases 解析失败：${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val files = parsed.assets.mapNotNull { asset ->
            archFromFileName(asset.name)?.let { arch ->
                RemoteData.RemoteFile(
                    fileName = asset.name,
                    uri = asset.downloadUrl,
                    arch = arch,
                    size = asset.size,
                )
            }
        }
        if (files.isEmpty()) return null
        return UpgradeSource(
            id = "github",
            name = "GitHub Releases",
            kind = UpdateSourceKind.GITHUB,
            files = files,
            latencyMs = elapsedMs(started),
            origin = RELEASE_LATEST,
        )
    }

    /**
     * 探测加速源：把整份 GitHub 列表套上前缀，只对其中一条发 `Range: bytes=0-0`。
     *
     * 不下载整个包（180MB），只验证「前缀 + 地址」这条路径能通、能拿到 2xx/206。
     * 反代域名挂了、或者它到 GitHub 的回源断了，都会在这里 4 秒内暴露出来。
     * 探针通过后整份列表都换成加速地址 —— 用户选加速源时仍然能挑架构。
     */
    private fun probeProxy(files: List<RemoteData.RemoteFile>): UpgradeSource? {
        val accelerated = files.map { it.copy(uri = PROXY_PREFIX + it.uri) }
        val seed = accelerated.firstOrNull { it.arch == RemoteData.RemoteFile.Arch.ARM64 }
            ?: accelerated.first()
        val started = System.nanoTime()
        val ok = probeRange(seed.uri) ?: return null
        Logger.info(TAG, "加速源可用，首包耗时 ${ok}ms")
        return UpgradeSource(
            id = "github-proxy",
            name = "GitHub 加速 · gh-proxy",
            kind = UpdateSourceKind.GITHUB_PROXY,
            files = accelerated,
            latencyMs = elapsedMs(started),
            origin = PROXY_PREFIX,
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun elapsedMs(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000

    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrNull().orEmpty()

    /**
     * 读一个完整响应体（用于 JSON 接口）。返回 null 表示握手失败或状态码不正常。
     */
    private fun httpGet(url: String, accept: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConnection(url, accept)
            val code = conn.responseCode
            if (code !in 200..299) {
                Logger.warning(TAG, "探测 $url 返回 HTTP $code")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Logger.warning(TAG, "探测 $url 失败：${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 只取首字节，验证「这条下载路径真的能出数据」。
     * @return 首包耗时（毫秒）；失败返回 null。
     */
    private fun probeRange(url: String): Long? {
        var conn: HttpURLConnection? = null
        val started = System.nanoTime()
        return try {
            conn = openConnection(url, accept = "*/*").apply {
                setRequestProperty("Range", "bytes=0-0")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Logger.warning(TAG, "探测 $url 返回 HTTP $code")
                return null
            }
            val one = conn.inputStream.use { it.read() }
            if (one < 0) {
                Logger.warning(TAG, "探测 $url 没有返回任何字节")
                return null
            }
            elapsedMs(started)
        } catch (e: Exception) {
            Logger.warning(TAG, "探测 $url 失败：${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            // 不带 identity 时服务器可能 gzip，直接 readText 会拿到压缩字节。
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "ZPCL-Launcher/2")
        }
}

/**
 * 官网接口里的 `arch` 是字符串，转成枚举。认不出来的一律当全架构包 ——
 * 与「不认识就当能装」的保守策略一致，总比整条记录被丢掉强。
 */
internal fun archFromSiteText(raw: String): RemoteData.RemoteFile.Arch = when (raw.lowercase().trim()) {
    "arm64", "aarch64", "arm64-v8a" -> RemoteData.RemoteFile.Arch.ARM64
    "arm", "armeabi", "armeabi-v7a", "v7a" -> RemoteData.RemoteFile.Arch.ARM
    "x86_64", "x64" -> RemoteData.RemoteFile.Arch.X86_64
    "x86" -> RemoteData.RemoteFile.Arch.X86
    else -> RemoteData.RemoteFile.Arch.ALL
}

/**
 * 从安装包文件名推断架构（如 `xxx-arm64-v8a.apk` → ARM64）。
 *
 * 不是 `.apk` 的资产（源码包、校验文件）返回 null，调用方据此过滤掉。
 * 认不出 ABI 后缀的 `.apk` 视为全架构包 —— 构建脚本对 `-Darch=all` 不追加后缀。
 */
internal fun archFromFileName(name: String): RemoteData.RemoteFile.Arch? {
    val n = name.lowercase()
    if (!n.endsWith(".apk")) return null
    return when {
        // 先判 arm64：`arm64-v8a` 里也含 `arm`，顺序反了会把 64 位机判成 32 位。
        "arm64" in n || "aarch64" in n -> RemoteData.RemoteFile.Arch.ARM64
        "armeabi" in n || "-v7a" in n -> RemoteData.RemoteFile.Arch.ARM
        "x86_64" in n -> RemoteData.RemoteFile.Arch.X86_64
        "x86" in n -> RemoteData.RemoteFile.Arch.X86
        "universal" in n || "-all" in n -> RemoteData.RemoteFile.Arch.ALL
        else -> RemoteData.RemoteFile.Arch.ALL
    }
}
