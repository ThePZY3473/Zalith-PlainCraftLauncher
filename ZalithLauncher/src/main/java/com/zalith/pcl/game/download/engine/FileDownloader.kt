/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 上游版权：Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 * 本文件自 2026 年起被修改；修改声明与名称/标识的处理见仓库根目录 NOTICE.md。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。分发时请保留本声明、上游版权与 LICENSE。
 */

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

package com.zalith.pcl.game.download.engine

/**
 * 单文件下载作业：把 [DownloadRequest] 交给 [Fetcher] 按候选源顺序下载，
 * 期间把落盘字节计入 [stats]。
 *
 * 大文件先尝试 [SegmentedDownloader] 的**分段并行**（多连接同时拉，能明显拉高单文件速度）；
 * 只要有一点条件不满足或中途出错，就原样回退到单连接 [Fetcher]，语义完全不变。
 */
class FileDownloader(
    private val request: DownloadRequest,
    private val stats: DownloadStats,
    /** 收尾阶段（待下文件只剩几个）返回 true：这时并发已经塌了，小文件也值得切段 */
    private val tailBoost: () -> Boolean = { false },
    /** 整批只剩 1~3 个文件：连 BMCLAPI 这种被禁多线程的源也放开（段数会被压低） */
    private val lastFew: () -> Boolean = { false },
) {
    suspend fun download() {
        val boost = tailBoost()
        val allowBlocked = lastFew()
        if (SegmentedDownloader.downloadOrNull(
                urls = request.urls,
                targetFile = request.targetFile,
                sha1 = request.sha1,
                expectedSize = request.expectedSize,
                onBytes = stats::addBytes,
                minSegmentSize = if (boost) {
                    SegmentedDownloader.TAIL_MIN_SEGMENT_SIZE
                } else {
                    SegmentedDownloader.MIN_SEGMENT_SIZE_FOR_CALLER
                },
                minTotalSize = if (boost) {
                    SegmentedDownloader.TAIL_MIN_TOTAL_SIZE
                } else {
                    SegmentedDownloader.MIN_TOTAL_SIZE
                },
                maxParallelFiles = if (boost) {
                    SegmentedDownloader.TAIL_MAX_PARALLEL_FILES
                } else {
                    SegmentedDownloader.MAX_PARALLEL_FILES_FOR_CALLER
                },
                allowBlockedHosts = allowBlocked,
                maxSegments = if (allowBlocked) {
                    SegmentedDownloader.TAIL_BLOCKED_MAX_SEGMENTS
                } else {
                    SegmentedDownloader.MAX_SEGMENTS
                },
            )
        ) {
            return
        }
        Fetcher.downloadFile(
            urls = request.urls,
            targetFile = request.targetFile,
            sha1 = request.sha1,
            onBytes = stats::addBytes
        )
        //下载前未知大小的文件：分子已随下载计入，这里把实际大小补进分母
        if (request.expectedSize <= 0) {
            stats.registerBytes(request.targetFile.length())
        }
    }
}
