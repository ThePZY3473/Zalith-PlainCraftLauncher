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
 * 启动器更新弹窗用的「网盘入口」，**链接从官网拿**。
 *
 * 官网（pcl-homepage-go）提供 `GET /api/downloads`，内容就是后台
 * 「下载方式」那一栏（`/admin/downloads`）里启用中的条目：
 *
 *     {
 *       "pan123": {"key":"123pan","name":"123云盘","url":"https://…"},
 *       "items":  [ …同上结构… ]
 *     }
 *
 * 后台把那条的地址改掉，启动器这边下次拉到就跟着变 —— 换 123 云盘分享链接
 * 不需要重新打包发版。
 *
 * 拿不到（未部署新版官网 / 断网 / 后台没配这一条）时返回 null，调用方就不画
 * 那个按钮，绝不影响原有的「立即更新」流程。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.zalith.pcl.ui.pcl2.data

import com.zalith.pcl.setting.launcherMMKV
import com.zalith.pcl.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val LOG_TAG = "PclUpdateSources"

/** 成功拿到之后多久内不再重复请求（更新框与选架构框会先后各问一次）。 */
private const val FRESH_MS = 5 * 60 * 1000L

object PclUpdateSources {

    /** MMKV key：把上一次成功的结果落盘，断网/官网挂了也能照样显示入口。 */
    private const val KEY_CACHE = "pcl_update_sources_cache"

    private const val TIMEOUT_MS = 8000

    /** 官网兜底地址，与更新源探测（UpdateSourceProbe）用的是同一个。 */
    private const val FALLBACK_BASE_URL = "https://zpcl.fun"

    /**
     * 一条网盘入口。
     *
     * @param key 官网给的语义标识（`123pan` / `github` / `lanzou` …），比名字稳。
     * @param name 按钮上显示的文字，直接用后台那条的名称。
     * @param url 点开就跳的地址。
     */
    data class Source(val key: String, val name: String, val url: String)

    @Volatile
    private var cached: List<Source> = emptyList()

    @Volatile
    private var diskLoaded = false

    @Volatile
    private var lastSuccessAt = 0L

    /** 内存/磁盘里上一次的结果，纯同步，首帧就能用（没有就 null）。 */
    fun cachedSource(): Source? {
        if (!diskLoaded) {
            diskLoaded = true
            cached = readDisk()
        }
        return cached.firstOrNull { it.isCloudPan() } ?: cached.firstOrNull()
    }

    /**
     * 联网拉**全部**下载方式；失败退回缓存。
     *
     * 以前只挑「123 云盘」一条，其余几条（QQ 群、加速站）直接丢掉。
     * 现在「选择下载源」列表要把不支持直链的入口也列出来（选中就用浏览器打开），
     * 所以这里必须返回完整列表。
     *
     * 5 分钟内成功过就直接用缓存，避免更新框 → 选源框连续两次请求。
     */
    suspend fun fetchAll(): List<Source> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val fromMemory = run {
            if (!diskLoaded) {
                diskLoaded = true
                cached = readDisk()
            }
            cached
        }
        if (fromMemory.isNotEmpty() && now - lastSuccessAt < FRESH_MS) return@withContext fromMemory

        val fetched = runCatching { fetchBlocking() }.getOrElse { e ->
            Logger.warning(LOG_TAG, "拉取下载方式失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (!fetched.isNullOrEmpty()) {
            cached = fetched
            lastSuccessAt = System.currentTimeMillis()
            writeDisk(fetched)
            fetched
        } else {
            fromMemory
        }
    }

    /** 只取「网盘」那一条，给更新框上的网盘按钮用。 */
    suspend fun fetch(): Source? = fetchAll().firstOrNull { it.isCloudPan() }

    private fun fetchBlocking(): List<Source>? {
        // 和更新源探测一样，官网地址不止一个：编译期常量可能已经换域名了，
        // 这里再兜一个实测可用的正式域名，避免「直链源探测得到、跳转入口却没有」。
        val bases = buildList {
            val primary = PclRemoteContent.baseUrl()
            if (primary.isNotEmpty()) add(primary)
            add(FALLBACK_BASE_URL)
        }.distinct()

        for (base in bases) {
            val list = fetchFrom(base)
            if (!list.isNullOrEmpty()) return list
        }
        return null
    }

    private fun fetchFrom(base: String): List<Source>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$base/api/downloads").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "ZPCL/2")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Logger.warning(LOG_TAG, "GET $base/api/downloads 返回 HTTP $code")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseAll(body)
        } catch (e: Exception) {
            Logger.warning(LOG_TAG, "GET $base/api/downloads 失败：${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 解析全部条目：老的 `pan123` 单条 + 新的 `items` 列表都认，按 url 去重。 */
    private fun parseAll(body: String): List<Source> {
        val root = JSONObject(body)
        val out = mutableListOf<Source>()
        root.optJSONObject("pan123")?.let { toSource(it)?.let(out::add) }
        val items = root.optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { toSource(it)?.let(out::add) }
            }
        }
        return out.distinctBy { it.url }
    }

    private fun toSource(obj: JSONObject): Source? {
        val url = obj.optString("url").trim()
        if (url.isEmpty()) return null
        val name = obj.optString("name").trim().ifEmpty { "网盘" }
        val key = obj.optString("key").trim()
        return Source(key = key, name = name, url = url)
    }

    /** 是不是「网盘」类入口（更新框上那个按钮只认这一类）。 */
    private fun Source.isCloudPan(): Boolean =
        key == "123pan" || name.contains("123") || url.contains("123pan", ignoreCase = true)

    private fun readDisk(): List<Source> = runCatching {
        val raw = launcherMMKV().getString(KEY_CACHE, "").orEmpty()
        if (raw.isBlank()) return@runCatching emptyList()
        val arr = org.json.JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                toSource(obj)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun writeDisk(list: List<Source>) {
        runCatching {
            val arr = org.json.JSONArray()
            list.forEach { src ->
                arr.put(
                    JSONObject()
                        .put("key", src.key)
                        .put("name", src.name)
                        .put("url", src.url),
                )
            }
            launcherMMKV().putString(KEY_CACHE, arr.toString())
        }
    }
}
