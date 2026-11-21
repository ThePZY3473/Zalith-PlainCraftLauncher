/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 上游版权：Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 * 本文件自 2026 年起被修改；修改声明与名称/标识的处理见仓库根目录 NOTICE.md。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。分发时请保留本声明、上游版权与 LICENSE。
 */

/*
 * Zalith Launcher 2 — PCL2 UI port
 *
 * 单文件「分段并行」下载器。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.zalith.pcl.game.download.engine

import com.zalith.pcl.path.URL_USER_AGENT
import com.zalith.pcl.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "SegmentedDownloader"

/**
 * 把**一个大文件**切成若干段、用多条连接同时拉，最后合并校验。
 *
 * ---- 调度方式参考 PCL2（`ModNet.vb` 的 `TryBeginThread`）
 *
 * PCL 不预先切成 N 等份就完事，而是「边下边切」：起几条第 0 段开始的连接，
 * 之后**谁剩下的字节最多就把谁的尾巴 40% 切出去**给一条新连接，直到达到线程上限；
 * 切分有下限（PCL 是 `FilePieceLimit = 256KB`），太碎反而全是握手开销。
 * 单连接快的网络下它就停在少数几段，连接质量参差时才自动铺开 —— 这比固定等分更适应抖动。
 *
 * ---- 为什么不再强制要求 ETag / Last-Modified
 *
 * PCL 全程不要求强校验器，靠**下完之后的 size + hash 校验**兜底，校验不过就换源重来。
 * 我们原来的实现把 `ETag/Last-Modified` 当成分段前提，结果很多镜像（尤其国内中转）
 * 压根不返回这两个头 —— 于是几百 MB 的包一直只能单连接爬。现在改成：有校验器就带上
 * `If-Range`，没有也照样分段；拼完先对 size 再对 sha1，不对就退回单连接。
 *
 * ---- 其它几条来自 PCL 的经验
 *
 * 1. `Accept-Encoding: identity`：压缩流的 Range 按压缩后字节算，多段拼接必然错位。
 * 2. 某些源**明确不适合多线程**（BMCLAPI 会限流返 403/429、GitHub 会掐连接），
 *    这些域名直接不分段，交给单连接。
 * 3. 每段自己重试（PCL 的线程级重试），一段挂了不必把整个文件从头再来。
 * 4. 临时文件与目标同目录，最后 `renameTo` 才是原子的；跨目录会退化成复制。
 */
object SegmentedDownloader {

    /**
     * 低于这个大小不分段。
     *
     * assets 里绝大多数文件是几百 KB，分段省下的时间还不够多付几次握手 + 慢启动，
     * 而多出来的请求会把整批几千个文件的握手开销翻几倍 —— 那就是负优化。
     */
    const val MIN_TOTAL_SIZE = 8L * 1024 * 1024

    /** 单段最小长度：段太小（比如 200KB 切 8 段）每条连接都在慢启动，反而更慢 */
    private const val MIN_SEGMENT_SIZE = 2L * 1024 * 1024

    /** 单个文件最多几条连接（PCL 的上限是设置里的线程数，这里取一个手机上网够用的值） */
    const val MAX_SEGMENTS = 12

    /** 切分下限：剩余不足这么多就不再切（PCL 的 FilePieceLimit 是 256KB，手机上放宽一些） */
    private const val SPLIT_MIN_REMAINING = 1L * 1024 * 1024

    /** 切分时新连接接走尾巴的比例（PCL 取 40%） */
    private const val SPLIT_TAIL_RATIO = 0.4

    /** 单段的重试次数：段内断流后从当前进度续上，不重下整份 */
    private const val SEGMENT_RETRY = 3

    /** 监督切分的间隔 */
    private const val SUPERVISE_INTERVAL_MS = 250L

    /**
     * 全进程**同时**在做分段下载的文件数上限。
     *
     * 批量下载的 `maxConnections` 是 32~64 —— 如果这些作业里有十几个大文件、
     * 每个再开十来段，峰值就是几百个并发 socket。手机上足以把网络栈/内存压垮，
     * 表现出来是「下载变快了一下，然后整个应用卡住」。配额用满时**立刻返回 false
     * 让这个文件走单连接**，绝不排队等待 —— 排队会让一批文件互相等，比慢一点糟糕得多。
     */
    private const val MAX_PARALLEL_FILES = 4

    private val activeSegmentedFiles = AtomicInteger(0)

    private const val TIMEOUT_MILLIS = 15_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_REDIRECTS = 8

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""")

    /**
     * 不适合多线程的域名（照搬 PCL 的黑名单思路）。
     *
     * BMCLAPI 的限流很激进：并发一多就返 403/429，反而比单连接慢；
     * GitHub 的下载链路会掐多连接。这些源交给 [Fetcher] 单连接即可。
     */
    private val MULTI_THREAD_BLOCKED_HOSTS = listOf(
        "bmclapi",
        "github.com",
        "githubusercontent",
        "optifine.net",
        "momot.rs",
        "meloong.com",
    )

    private fun isMultiThreadBlocked(url: URL): Boolean {
        val host = url.host.lowercase()
        return MULTI_THREAD_BLOCKED_HOSTS.any { host.contains(it) }
    }

    /**
     * 尝试分段并行下载。
     *
     * @param expectedSize 清单里声明的大小；小于 [MIN_SEGMENT_SIZE] 时**一次请求都不发**直接返回
     *        （这是它不拖慢小文件下载的关键 —— 判定完全靠本地数字）。
     * @return true 表示文件已就位且校验通过；false 表示没走成，调用方应回退到 [Fetcher]
     */
    suspend fun downloadOrNull(
        urls: List<String>,
        targetFile: File,
        sha1: String?,
        expectedSize: Long,
        onBytes: (Long) -> Unit,
        maxSegments: Int = MAX_SEGMENTS,
        minSegmentSize: Long = MIN_SEGMENT_SIZE,
    ): Boolean {
        if (expectedSize in 1 until minSegmentSize) return false

        if (activeSegmentedFiles.incrementAndGet() > MAX_PARALLEL_FILES) {
            activeSegmentedFiles.decrementAndGet()
            return false
        }

        try {
            for (candidate in urls.distinct()) {
                val url = try {
                    URL(candidate)
                } catch (e: Exception) {
                    continue
                }
                if (!url.protocol.equals("http", ignoreCase = true) &&
                    !url.protocol.equals("https", ignoreCase = true)
                ) {
                    continue
                }
                if (isMultiThreadBlocked(url)) {
                    Logger.info(TAG, "Multi-thread disabled for host: ${url.host}")
                    continue
                }

                var reported = 0L
                var succeeded = false
                try {
                    val result = attempt(
                        url = url,
                        targetFile = targetFile,
                        sha1 = sha1,
                        maxSegments = maxSegments,
                        minSegmentSize = minSegmentSize,
                    ) { delta ->
                        reported += delta
                        onBytes(delta)
                    }
                    if (result) {
                        succeeded = true
                        return true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.warning(TAG, "Segmented download gave up: $candidate", e)
                } finally {
                    // 已经报给统计的字节要扣回去：接下来 Fetcher 会把这个文件从头下一遍，
                    // 不扣的话总进度会先虚高再回落（下载页看起来就是「100% 又变回 60%」）。
                    if (!succeeded && reported != 0L) {
                        onBytes(-reported)
                    }
                }
            }
            return false
        } finally {
            activeSegmentedFiles.decrementAndGet()
        }
    }

    /** 一段待下的区间；`cursor` 是当前写到哪，`end` 会在切分时被调小 */
    private class Segment(start: Long, end: Long) {
        val cursor = AtomicLong(start)
        val end = AtomicLong(end)

        fun remaining(): Long = (end.get() - cursor.get() + 1).coerceAtLeast(0L)

        /** 把尾巴按 [ratio] 切出去；剩余不够或已经被别人切过就返回 null */
        fun splitTail(ratio: Double): Segment? {
            val currentEnd = end.get()
            val currentCursor = cursor.get()
            val remaining = currentEnd - currentCursor + 1
            if (remaining < SPLIT_MIN_REMAINING) return null
            val newStart = currentCursor + (remaining * (1.0 - ratio)).toLong()
            if (newStart <= currentCursor || newStart > currentEnd) return null
            // 先缩小这一段的右边界：正在跑的那条连接下一轮读到这里就停了
            if (!end.compareAndSet(currentEnd, newStart - 1)) return null
            return Segment(newStart, currentEnd)
        }
    }

    /** 单个候选源的一次分段下载；false = 这个源不适合分段（不是错误） */
    private suspend fun attempt(
        url: URL,
        targetFile: File,
        sha1: String?,
        maxSegments: Int,
        minSegmentSize: Long,
        report: (Long) -> Unit,
    ): Boolean {
        val probe = probe(url) ?: return false
        val total = probe.total
        if (total < MIN_TOTAL_SIZE) return false

        val parent = targetFile.absoluteFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false

        val segmentCount = (total / minSegmentSize).toInt().coerceIn(2, maxSegments)
        val temp = File(parent, "${targetFile.name}.zpart")
        runCatching { if (temp.exists()) temp.delete() }

        Logger.info(
            TAG,
            "Segmented download: ${targetFile.name}, $total bytes, " +
                "$segmentCount initial segments, validator=${probe.validator ?: "none"}"
        )

        try {
            RandomAccessFile(temp, "rw").use { raf ->
                raf.setLength(total)
                raf.channel.use { channel ->
                    coroutineScope {
                        val scope = this
                        val segments = CopyOnWriteArrayList<Segment>()
                        val jobs = CopyOnWriteArrayList<Job>()

                        fun launchWorker(segment: Segment) {
                            segments += segment
                            jobs += scope.launch(Dispatchers.IO) {
                                runInterruptible {
                                    runSegment(url, channel, segment, probe.validator, report)
                                }
                            }
                        }

                        for (index in 0 until segmentCount) {
                            // 初始边界按乘法分配，最后一段吃掉除不尽的余数
                            val start = total * index / segmentCount
                            val endInclusive = total * (index + 1) / segmentCount - 1
                            launchWorker(Segment(start, endInclusive))
                        }

                        // 监督者：还有空位就把「剩得最多」的那段的尾巴切出去（PCL 的 TryBeginThread）
                        val supervisor = launch(Dispatchers.Default) {
                            while (isActive) {
                                delay(SUPERVISE_INTERVAL_MS)
                                if (jobs.size >= maxSegments) continue
                                val candidate = segments
                                    .filter { it.remaining() >= SPLIT_MIN_REMAINING }
                                    .maxByOrNull { it.remaining() } ?: continue
                                val tail = candidate.splitTail(SPLIT_TAIL_RATIO) ?: continue
                                Logger.debug(
                                    TAG,
                                    "Split segment for ${targetFile.name}: tail $SPLIT_TAIL_RATIO"
                                )
                                launchWorker(tail)
                            }
                        }

                        jobs.joinAll()
                        supervisor.cancel()
                    }
                    channel.force(true)
                }
            }

            if (temp.length() != total) {
                throw IOException("Size mismatch after merge: ${temp.length()} != $total")
            }
            if (sha1 != null) {
                val actual = runInterruptible(Dispatchers.IO) { sha1Of(temp) }
                if (!actual.equals(sha1, ignoreCase = true)) {
                    throw IOException("SHA-1 mismatch: $actual != $sha1")
                }
            }
            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("Cannot replace existing file: $targetFile")
            }
            if (!temp.renameTo(targetFile)) {
                throw IOException("Cannot move temp file to $targetFile")
            }
            return true
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }

    /**
     * 把一段拉完，中途断流就从当前进度续上（PCL 的线程级重试）。
     *
     * 段被监督者切开后 `remaining()` 会立刻变成 0，这里自然收工。
     */
    private fun runSegment(
        url: URL,
        channel: FileChannel,
        segment: Segment,
        validator: String?,
        report: (Long) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            if (segment.remaining() <= 0L) return
            try {
                fetchSegment(url, channel, segment, validator, report)
                if (segment.remaining() <= 0L) return
                // 连接正常结束但这段的字节没收全：按断流处理，重试时从 cursor 续传
                throw IOException("Segment ended early, ${segment.remaining()} bytes left")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                attempt++
                if (attempt > SEGMENT_RETRY) throw e
                Logger.warning(TAG, "Segment retry #$attempt (left=${segment.remaining()})", e)
                // 这里跑在 runInterruptible 包住的 IO 线程上，退避用 sleep 即可
                Thread.sleep(200L * attempt)
            }
        }
    }

    /** 拉取 [segment] 当前窗口里的数据，直接写进 [channel] 的对应位置（阻塞，靠线程中断取消） */
    private fun fetchSegment(
        url: URL,
        channel: FileChannel,
        segment: Segment,
        validator: String?,
        report: (Long) -> Unit,
    ) {
        var current = url
        var redirects = 0
        while (true) {
            val start = segment.cursor.get()
            val windowEnd = segment.end.get()
            if (start > windowEnd) return

            val connection = open(current)
            try {
                connection.setRequestProperty("Range", "bytes=$start-$windowEnd")
                connection.setRequestProperty("Accept-Encoding", "identity")
                // 有校验器就带上：下载期间文件若被换掉，服务器会返回 200 整份，下面的 206 断言会拦住
                if (validator != null) {
                    connection.setRequestProperty("If-Range", validator)
                }
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location: $current")
                    if (redirects++ >= MAX_REDIRECTS) throw IOException("Too many redirects: $url")
                    current = URL(current, location)
                    continue
                }
                if (code != 206) {
                    throw IOException("Segment $start-$windowEnd: unexpected HTTP $code")
                }
                val rangeStart = parseContentRange(connection.getHeaderField("Content-Range"), 1)
                if (rangeStart != null && rangeStart != start) {
                    throw IOException("Segment $start-$windowEnd: Content-Range starts at $rangeStart")
                }

                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("Cancelled")
                        }
                        val position = segment.cursor.get()
                        val limit = segment.end.get()
                        if (position > limit) return
                        // 读之前按当前窗口截断：段刚被切走时不会多读、更不会写进别人那段
                        val want = minOf(buffer.size.toLong(), limit - position + 1).toInt()
                        val read = input.read(buffer, 0, want)
                        if (read < 0) return
                        if (read == 0) continue

                        var offset = position
                        val slice = ByteBuffer.wrap(buffer, 0, read)
                        while (slice.hasRemaining()) {
                            // 绝对位置写：多段共用一个 channel 也是线程安全的
                            offset += channel.write(slice, offset)
                        }
                        segment.cursor.addAndGet(read.toLong())
                        report(read.toLong())
                    }
                }
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    /**
     * 探测：是否支持 Range、总大小、有没有强校验器。
     * 校验器现在是**可选**的（没有也照样分段，最后用 size + sha1 校验），
     * 只有「不支持 Range / 被重编码 / 拿不到总大小」才返回 null。
     */
    private fun probe(url: URL): Probe? {
        var current = url
        var redirects = 0
        while (true) {
            val connection = open(current)
            try {
                connection.setRequestProperty("Range", "bytes=0-0")
                // 探测也必须 identity：协商出 gzip 就说明这条链路会重编码，分段不可用
                connection.setRequestProperty("Accept-Encoding", "identity")
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location") ?: return null
                    if (redirects++ >= MAX_REDIRECTS) return null
                    current = URL(current, location)
                    continue
                }
                // 只有 206 才证明服务器真的按区间给数据；200 说明它无视 Range，直接整份返回
                if (code != 206) {
                    Logger.info(TAG, "Range not supported (HTTP $code): $current")
                    return null
                }
                val encoding = connection.getHeaderField("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                    return null
                }
                val total = parseContentRange(connection.getHeaderField("Content-Range"), 3)
                    ?: return null
                return Probe(total, strongValidatorOf(connection))
            } catch (e: Exception) {
                return null
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun open(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", URL_USER_AGENT)
        return connection
    }

    /**
     * 强校验器：`If-Range` 只认「强」ETag 或 Last-Modified。
     * 弱 ETag（`W/"..."`）按 RFC 不能用于 If-Range，给了也可能被服务器忽略 —— 那就干脆不带。
     */
    private fun strongValidatorOf(connection: HttpURLConnection): String? {
        val etag = connection.getHeaderField("ETag")
        if (etag != null && !etag.startsWith("W/", ignoreCase = true)) return etag
        return connection.getHeaderField("Last-Modified")
    }

    private fun parseContentRange(value: String?, group: Int): Long? {
        val match = CONTENT_RANGE.find(value ?: return null) ?: return null
        return match.groupValues[group].toLongOrNull()
    }

    private fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("Cancelled")
                }
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private class Probe(val total: Long, val validator: String?)
}
