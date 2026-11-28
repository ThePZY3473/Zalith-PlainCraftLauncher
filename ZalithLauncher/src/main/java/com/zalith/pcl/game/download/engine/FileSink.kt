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

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * [Modified from HMCL FileDownloadTask](https://github.com/HMCL-dev/HMCL/blob/59bcc7fe/HMCLCore/src/main/java/org/jackhuang/hmcl/task/FileDownloadTask.java)
 *
 * 下载落盘上下文：先写入目标同目录下的临时文件，成功收尾时校验 SHA-1
 * 并原子替换目标文件；失败丢弃时删除临时文件。
 * 写盘失败由 [broken] 标记，调用方据此在重试时放弃续传、从头开始。
 */
class FileSink(
    targetFile: File,
    private val expectedChecksum: String?,
    /**
     * 小于 [MEMORY_SINK_LIMIT] 的文件走内存直写。
     *
     * 几千个 assets 每个都「建临时文件 → 写 → rename」，光是元数据操作就够呛；
     * 1 MB 以下直接攒在内存里，校验完一次写出（PCL 也是这么处理小文件的）。
     */
    memoryMode: Boolean = false,
) : Closeable {
    // Android 7（API 25）的 java.io.File 没有 toPath()，而 desugar 不重写平台类，
    // 这里必须用 Paths.get(String)，否则启动器在 Android 7 上会 NoSuchMethodError。
    private val target: Path = Paths.get(targetFile.path)
    private val temp: Path?
    private val channel: FileChannel?
    private val memory: ByteArrayOutputStream?
    private val digest: MessageDigest? = expectedChecksum?.let { MessageDigest.getInstance("SHA-1") }

    /** 最近一次写盘是否失败；为真时既有内容不可信，重试不得续传 */
    var broken = false
        private set

    init {
        if (memoryMode) {
            temp = null
            channel = null
            memory = ByteArrayOutputStream(64 * 1024)
        } else {
            val parent = target.toAbsolutePath().parent
            Files.createDirectories(parent)
            temp = Files.createTempFile(parent, null, null)
            channel = FileChannel.open(
                temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.CREATE
            )
            memory = null
        }
    }

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        try {
            digest?.update(buffer, offset, length)
            val inMemory = memory
            if (inMemory != null) {
                inMemory.write(buffer, offset, length)
            } else {
                val wrapped = ByteBuffer.wrap(buffer, offset, length)
                while (wrapped.hasRemaining()) {
                    channel!!.write(wrapped)
                }
            }
        } catch (e: IOException) {
            broken = true
            throw e
        }
    }

    /** 下载成功后的收尾：校验 SHA-1 并把内容落到目标文件；抛出视为可重试失败 */
    fun finish() {
        var done = false
        try {
            channel?.close()

            if (expectedChecksum != null) {
                val actual = digest!!.digest().toHexString()
                if (!expectedChecksum.equals(actual, ignoreCase = true)) {
                    throw ChecksumMismatchException("SHA-1", expectedChecksum, actual)
                }
            }

            val inMemory = memory
            if (inMemory != null) {
                target.parent?.let { Files.createDirectories(it) }
                Files.write(
                    target,
                    inMemory.toByteArray(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            } else {
                Files.move(temp!!, target, StandardCopyOption.REPLACE_EXISTING)
            }
            done = true
        } finally {
            if (!done) deleteTemp()
        }
    }

    override fun close() {
        runCatching { channel?.close() }
        memory?.reset()
        deleteTemp()
    }

    private fun deleteTemp() {
        temp?.let { runCatching { Files.deleteIfExists(it) } }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }
}

/** 小于这个大小的文件走内存直写，不落临时文件 */
internal const val MEMORY_SINK_LIMIT = 1L * 1024 * 1024

