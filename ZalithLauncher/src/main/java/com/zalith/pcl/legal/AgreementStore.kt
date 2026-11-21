/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 上游版权：Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 * 本文件自 2026 年起被修改；修改声明与名称/标识的处理见仓库根目录 NOTICE.md。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。分发时请保留本声明、上游版权与 LICENSE。
 */

package com.zalith.pcl.legal

import android.content.Context
import com.zalith.pcl.path.GLOBAL_JSON
import com.zalith.pcl.utils.logging.Logger

private const val TAG = "AgreementStore"

/** `assets/legal/data.json`：协议清单，`version` 变了就要求重新确认。 */
private const val AGREEMENT_INDEX = "legal/data.json"
private const val PREFS = "zpcl.agreement"
private const val KEY_ACCEPTED_VERSION = "accepted_version"

/**
 * 协议清单的读取与「已同意版本」的记录。
 *
 * 之前 `SplashActivity` 直接把协议流写成 null，`data.json` 从来没被读过，
 * 于是首次启动根本不会弹协议 —— 这里把读取和版本比对补回来。
 */
object AgreementStore {

    /** 读取协议清单；解析失败时返回 null（界面据此跳过协议步骤，不阻塞启动）。 */
    fun load(context: Context): AvailableAgreement? = runCatching {
        val text = context.assets.open(AGREEMENT_INDEX).bufferedReader().use { it.readText() }
        GLOBAL_JSON.decodeFromString<AvailableAgreement>(text)
    }.onFailure {
        Logger.warning(TAG, "Failed to read $AGREEMENT_INDEX", it)
    }.getOrNull()

    /** 用户上次同意的协议版本。 */
    fun acceptedVersion(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACCEPTED_VERSION, null)

    /** 记录用户已同意某一版本的协议。 */
    fun markAccepted(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCEPTED_VERSION, version)
            .apply()
    }

    /**
     * 需要用户确认的协议：没同意过、或版本已变更时返回清单，否则返回 null。
     */
    fun pending(context: Context): AvailableAgreement? {
        val agreement = load(context) ?: return null
        if (agreement.files.all { it.dataList.isEmpty() }) return null
        return if (acceptedVersion(context) == agreement.version) null else agreement
    }

    /** 一份可直接显示的法律文件：标题 + Markdown 原文。 */
    data class LegalDoc(val title: String, val body: String)

    /** 按系统语言挑出该地区的一份或多份文件（简体中文 → `zh`，其余 → `global`）。 */
    fun docsForLocale(context: Context): List<LegalDoc> {
        val agreement = load(context) ?: return emptyList()
        val region = if (java.util.Locale.getDefault().country.equals("CN", ignoreCase = true)) "zh" else "global"
        val group = agreement.files.firstOrNull { it.effectiveRegion.equals(region, ignoreCase = true) }
            ?: agreement.files.firstOrNull { it.effectiveRegion.equals("global", ignoreCase = true) }
            ?: return emptyList()
        return group.dataList.mapNotNull { data ->
            runCatching {
                val text = context.assets.open(data.path).bufferedReader().use { it.readText() }
                LegalDoc(data.title, text)
            }.onFailure {
                Logger.warning(TAG, "Failed to read ${data.path}", it)
            }.getOrNull()
        }
    }
}
