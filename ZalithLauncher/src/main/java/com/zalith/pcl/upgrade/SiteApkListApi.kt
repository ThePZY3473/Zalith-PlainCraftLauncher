/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 上游版权：Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 * 本文件自 2026 年起被修改；修改声明与名称/标识的处理见仓库根目录 NOTICE.md。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。分发时请保留本声明、上游版权与 LICENSE。
 */

package com.zalith.pcl.upgrade

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 官网 `/api/apks` 的返回结构。
 *
 * 存在意义：更新检查原来只认 `api.github.com` 的 Releases 接口，国内经常连不上，
 * 表现就是「查到有新版本，但一个可下载的包都没有」。官网在国内直连没问题，
 * 于是让它作为首选源，字段名与 [RemoteData.RemoteFile] 一一对应，前端不用做二次映射。
 *
 * `arch` 只会是 all / arm / arm64 / x86 / x86_64 之一（服务端已经把后台自由填写的
 * 文本归一化过了）。
 */
@Serializable
data class SiteApkListApi(
    @SerialName("files")
    val files: List<File> = emptyList(),
) {
    @Serializable
    data class File(
        @SerialName("file_name")
        val fileName: String,
        @SerialName("uri")
        val uri: String,
        @SerialName("arch")
        val arch: String = "all",
        @SerialName("size")
        val size: Long = 0L,
    )
}
