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
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
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

package com.zalith.pcl.viewmodel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zalith.pcl.BuildConfig
import com.zalith.pcl.R
import com.zalith.pcl.path.GLOBAL_CLIENT
import com.zalith.pcl.path.GLOBAL_JSON
import com.zalith.pcl.path.URL_PROJECT_INFO
import com.zalith.pcl.setting.AllSettings
import com.zalith.pcl.ui.components.MarqueeText
import com.zalith.pcl.ui.components.SimpleListDialog
import com.zalith.pcl.ui.screens.content.elements.DisabledAlpha
import com.zalith.pcl.ui.upgrade.UpgradeDialog
import com.zalith.pcl.ui.upgrade.UpgradeFilesDialog
import com.zalith.pcl.BuildKeys
import com.zalith.pcl.upgrade.GithubContentApi
import com.zalith.pcl.upgrade.RemoteData
import com.zalith.pcl.upgrade.TooFrequentOperationException
import com.zalith.pcl.upgrade.UpdateSourceKind
import com.zalith.pcl.upgrade.UpdateSourceProbe
import com.zalith.pcl.upgrade.UpgradeSource
import com.zalith.pcl.ui.pcl2.data.PclRemoteContent
import com.zalith.pcl.ui.pcl2.data.PclUpdateSources
import com.zalith.pcl.utils.logging.Logger
import com.zalith.pcl.utils.network.safeBodyAsJson
import com.zalith.pcl.utils.network.withRetry
import com.zalith.pcl.utils.string.decodeBase64
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "LauncherUpgradeVM"

sealed interface LauncherUpgradeOperation {
    data object None : LauncherUpgradeOperation
    /** 已检查到启动器存在新版本，展示更新信息 */
    data class Upgrade(
        val data: RemoteData,
        /** 已选中的更新源名字；为空表示还没选源，点「立即更新」会先进选源框。 */
        val sourceName: String? = null,
    ) : LauncherUpgradeOperation
    /** 选择要安装的安装包文件 */
    data class SelectApk(
        val data: RemoteData,
        val sourceName: String? = null,
        /**
         * 其它可用源的安装包。选中某个架构后，按 [RemoteData.RemoteFile.arch] 过滤出
         * 同架构的地址一并交给下载引擎做备用 —— 主源下到一半断了能自动换到另一个源，
         * 而不是让用户对着「下载失败」重试同一条坏链接。
         */
        val alternatives: List<RemoteData.RemoteFile> = emptyList(),
    ) : LauncherUpgradeOperation
    /** 选择从哪个源下载（先握手探测，连不上的源不列出来） */
    data class ChooseSource(val data: RemoteData) : LauncherUpgradeOperation
    /** 打开网盘分享 */
    data class OpenCloudDrive(val cloudDrive: RemoteData.CloudDrive) : LauncherUpgradeOperation
}

/**
 * 更新源探测的界面状态。
 *
 * 与 [LauncherUpgradeOperation] 分开：弹窗要不要显示、显示什么，取决于探测进度；
 * 而「当前该弹哪个框」由 operation 决定。两者混在一个 sealed 里会让「探测完但用户
 * 已经关掉弹窗」这种情况变得很难表达。
 */
sealed interface UpdateSourceState {
    data object Idle : UpdateSourceState

    /** 正在并发握手，界面上显示「正在测试下载源」。 */
    data object Probing : UpdateSourceState

    /** 探测完成，[sources] 一定非空（空的走 [Empty]）。 */
    data class Ready(val sources: List<UpgradeSource>) : UpdateSourceState

    /** 所有源都握手失败：给一句说明 + 退回网盘的入口。 */
    data class Empty(val message: String) : UpdateSourceState
}

/**
 * 最新版本的信息获取源
 */
private const val LATEST_VERSION = "latest_version_md.json"
private const val LATEST_API_URL = "$URL_PROJECT_INFO/$LATEST_VERSION"
private const val LATEST_API_CHINESE_URL = "https://raw.githubusercontent.com/zuanxu53-hub/ZPCL-Launcher/main/$LATEST_VERSION"
/**
 * 国内备用镜像：jsDelivr 的 GitHub 加速。
 *
 * `api.github.com` 与 `raw.githubusercontent.com` 在中国大陆都经常连不上，
 * 只留它们两个时「检查更新」会直接失败（用户看到的就是「点了没反应」）。
 * jsDelivr 走的是自家 CDN，同一份文件能正常取到。
 */
private const val LATEST_API_JSDELIVR_URL = "https://cdn.jsdelivr.net/gh/zuanxu53-hub/ZPCL-Launcher@main/$LATEST_VERSION"

/** 一次「检查更新」最多等这么久（毫秒）：超时就当失败，别让按钮一直转。 */
private const val UPDATE_TIMEOUT_MS = 60_000L
// GitHub Releases 接口地址已挪进 UpdateSourceProbe（那边要独立探测，不能依赖 ViewModel）。

/**
 * 官网兜底地址。
 *
 * `zpcl.fun` 是当前证书实际签发的域名（`zpcl.craftmc.cn` 会 301 过来）。编译期的
 * `url_home` 写的是 `z-pcl.cc.cd:28722`，实测部分网络下这个端口连不上 ——
 * 两个都放进候选里探测，比赌其中一个可靠。
 */
private const val FALLBACK_SITE_BASE = "https://zpcl.fun"

/**
 * 用于记录启动器更新 ViewModel
 */
class LauncherUpgradeViewModel: ViewModel() {
    var operation by mutableStateOf<LauncherUpgradeOperation>(LauncherUpgradeOperation.None)

    /** 「选择更新源」弹窗的探测进度。只在 [LauncherUpgradeOperation.ChooseSource] 期间有意义。 */
    var sourceState by mutableStateOf<UpdateSourceState>(UpdateSourceState.Idle)
        private set

    private val checkMutex = Mutex()

    /**
     * 开始探测更新源。
     *
     * 同一个弹窗被重复组合时会反复调到这里，[UpdateSourceState.Probing] 期间直接返回，
     * 避免每次重组都重开一轮 5 个并发请求。探测本身有 4 秒单源上限，
     * 最坏情况就是 4 秒后出结果。
     */
    fun startProbe() {
        if (sourceState is UpdateSourceState.Probing) return
        sourceState = UpdateSourceState.Probing
        viewModelScope.launch {
            val direct = runCatching { UpdateSourceProbe.probeAll(siteBases()) }
                .onFailure { Logger.warning(TAG, "更新源探测异常：${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(emptyList())
            // 直链源排前面（按握手耗时），跳转类入口附在最后。
            val sources = direct + externalSources()
            sourceState = if (sources.isEmpty()) {
                UpdateSourceState.Empty("当前网络下所有下载源都没能在 4 秒内握手成功。")
            } else {
                Logger.info(TAG, "可用更新源 ${sources.size} 个：${sources.joinToString { "${it.name}(${it.kind})" }}")
                UpdateSourceState.Ready(sources)
            }
        }
    }

    /**
     * 官网「下载方式」里那些**不提供直链**的入口（123 云盘、QQ 群）。
     *
     * 它们不是安装包列表，没法用来下载，但用户必须能看到 —— 直链全挂的时候，
     * 这是唯一的出路。选中后由界面直接丢给浏览器打开，不经过下载引擎。
     *
     * GitHub / gh-proxy 那几条会被过滤掉：探测阶段已经把它们变成了真正的直链源，
     * 重复列出来只会让用户困惑（同一个包出现两次）。
     */
    private suspend fun externalSources(): List<UpgradeSource> =
        runCatching { PclUpdateSources.fetchAll() }
            .onFailure { Logger.warning(TAG, "拉取官网下载方式失败：${it.message}") }
            .getOrDefault(emptyList())
            .filterNot { src ->
                val u = src.url.lowercase()
                u.contains("github.com") || u.contains("gh-proxy")
            }
            .map { src ->
                UpgradeSource(
                    id = "external:${src.url}",
                    name = src.name.ifBlank { "网盘下载" },
                    kind = UpdateSourceKind.EXTERNAL,
                    files = emptyList(),
                    latencyMs = 0L,
                    origin = src.url,
                    url = src.url,
                )
            }

    /**
     * 用户选定了某个源：把它给的安装包列表当成这一轮的候选，直接进「选安装包」。
     *
     * 其余可用源的同架构包作为备用地址带上（见 [LauncherUpgradeOperation.SelectApk.alternatives]）。
     */
    fun chooseSource(source: UpgradeSource) {
        val data = (operation as? LauncherUpgradeOperation.ChooseSource)?.data ?: return
        val others = (sourceState as? UpdateSourceState.Ready)?.sources.orEmpty()
            .filter { it.id != source.id }
            .flatMap { it.files }
        operation = LauncherUpgradeOperation.SelectApk(
            data = data.copy(files = source.files),
            sourceName = source.name,
            alternatives = others,
        )
        sourceState = UpdateSourceState.Idle
    }

    /** 关掉选源框时复位，下次打开重新探测（网络环境可能已经变了）。 */
    fun resetProbe() {
        sourceState = UpdateSourceState.Idle
    }

    /**
     * 官网候选地址。
     *
     * 这里有三个来源，按优先级：
     *  1. [BuildKeys.URL_HOME] —— 编译期 `url_home`（当前写的是 `z-pcl.cc.cd:28722`）；
     *  2. [PclRemoteContent.baseUrl] —— 运行期可被 MMKV 覆盖的那个站点根地址；
     *  3. `https://zpcl.fun` —— 实测可用的正式域名兜底。
     *
     * 以前只认第 1 个：那个域名一旦连不上（换域名/端口被封），官网接口就永远失败，
     * 启动器只能回退 GitHub —— 而 GitHub 在国内又经常连不上，最后就是「有更新但下不了」。
     * 现在三个一起探测，哪个通就用哪个；全都不通就把官网相关的源隐藏掉。
     */
    private fun siteBases(): List<String> = buildList {
        BuildKeys.URL_HOME.trim().trimEnd('/').takeIf { it.isNotBlank() }?.let(::add)
        runCatching { PclRemoteContent.baseUrl() }.getOrNull()
            ?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let(::add)
        add(FALLBACK_SITE_BASE)
    }.distinct()

    /**
     * 检查是否在限频时间内
     * @param time 限频时间（毫秒）
     * @param lastCheckTime 上次检查的时间戳
     */
    private fun isWithinRateLimit(
        time: Long,
        lastCheckTime: Long
    ): Boolean {
        val currentTime = System.currentTimeMillis()
        if (lastCheckTime > currentTime) {
            //用户调整到了未来的时间，无法正常判断
            //直接允许进行检查
            return false
        }
        return currentTime - lastCheckTime < time
    }

    /**
     * 更新最后一次检查的时间
     */
    private fun updateLastCheckTime() {
        AllSettings.lastUpgradeCheck.save(System.currentTimeMillis())
    }

    /** 同一个 ViewModel 实例只做一次启动检查（重组 / 重入不会重复打网络）。 */
    private var startCheckLaunched = false

    /**
     * 在启动时检查更新。
     *
     * **不再做「一小时一次」的限频**：按需求改成每次进入应用都查一次。
     * 本地 checks 只防同一个 ViewModel 实例被重复调用（`PclUpgradeHost` 每次组合
     * 都会调它），跨进程/跨 Activity 重建仍会重新检查，也就是「每次进入都查」。
     */
    fun checkOnAppStart(
        onIsLatest: suspend () -> Unit = {}
    ) {
        if (startCheckLaunched) return
        startCheckLaunched = true
        viewModelScope.launch {
            val data = fetchRemoteData()
            if (data != null) {
                checkForUpgrade(
                    data = data,
                    lastIgnored = AllSettings.lastIgnoredVersion.getValue(),
                    ignoreDismissedVersions = true, //启动时检查忽略用户已忽略的版本
                    onUpgrade = { data ->
                        operation = LauncherUpgradeOperation.Upgrade(data)
                    },
                    onIsLatest = onIsLatest
                )
            }
            updateLastCheckTime()
        }
    }

    /**
     * 用户在设置内手动点击检查更新
     * @param onInProgress 准备检查更新
     * @param onIsLatest 当前启动器是最新版
     * @param onChecked 拿到远端数据后回调（null = 网络失败）。
     *   PCL 界面用它区分「失败 / 已最新 / 有新版本」并弹出对应的 PCL 弹窗 ——
     *   只看 Boolean 返回值分不清前两者，而那边没有 Toast 可以兜底。
     */
    suspend fun checkManually(
        onInProgress: suspend () -> Unit = {},
        onIsLatest: suspend () -> Unit = {},
        onChecked: suspend (RemoteData?) -> Unit = {},
    ): Boolean {
        return checkMutex.withLock {
            if (
                isWithinRateLimit(
                    time = TimeUnit.SECONDS.toMillis(5L),
                    lastCheckTime = AllSettings.lastUpgradeCheck.getValue()
                )
            ) throw TooFrequentOperationException()

            onInProgress()

            val data = fetchRemoteData()
            if (data != null) {
                checkForUpgrade(
                    data = data,
                    lastIgnored = AllSettings.lastIgnoredVersion.getValue(),
                    ignoreDismissedVersions = false,
                    onUpgrade = { data ->
                        operation = LauncherUpgradeOperation.Upgrade(data)
                    },
                    onIsLatest = onIsLatest
                )
            }
            updateLastCheckTime()
            onChecked(data)
            data != null
        }
    }

    /**
     * 从远端获取最新的启动器信息。
     *
     * 整体套一层 60 秒超时：`GLOBAL_CLIENT` 单次请求 30 秒超时，主源 + 两个镜像源
     * 各自还带重试，网络不通时能拖上好几分钟 —— 用户点完「检查更新」只会觉得死了。
     */
    private suspend fun fetchRemoteData(): RemoteData? {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(UPDATE_TIMEOUT_MS) { fetchRemoteDataInner() }
        }
    }

    private suspend fun fetchRemoteDataInner(): RemoteData? {
        return runCatching {
            withRetry(logTag = "LauncherUpgrade", maxRetries = 2) {
                //获取最新的启动器信息
                val api = GLOBAL_CLIENT.get(LATEST_API_URL).safeBodyAsJson<GithubContentApi>()
                //需要Base64解密
                val contentString = decodeBase64(api.content)
                // 这里**不再顺带拉安装包列表**。
                //
                // 以前是 `files.ifEmpty { fetchFiles() }`：只要 json 里没写死 files，
                // 每次检查更新都会额外打一次官网 `/api/apks`。而官网域名一旦连不上，
                // 这一步要等 30 秒超时 × 重试，用户点「检查更新」看到的是一分钟的转圈。
                // 现在列表改由「选择更新源」阶段按源并发探测，这里只负责版本信息。
                GLOBAL_JSON.decodeFromString(RemoteData.serializer(), contentString)
            }
        }.getOrElse { e ->
            if (Locale.getDefault().language == "zh") {
                // 国内依次试 raw.githubusercontent → jsDelivr，任一成功即可。
                // 镜像源只重试一次：再失败就该立刻把「连不上」告诉他，而不是继续等。
                var result: RemoteData? = null
                for (url in listOf(LATEST_API_CHINESE_URL, LATEST_API_JSDELIVR_URL)) {
                    result = runCatching {
                        Logger.info(TAG, "Check for updates via mirror: $url")
                        withRetry(logTag = "LauncherUpgrade_Chinese", maxRetries = 1) {
                            GLOBAL_CLIENT.get(url).safeBodyAsJson<RemoteData>()
                        }
                    }.getOrNull()
                    if (result != null) break
                }
                if (result == null) {
                    Logger.warning(TAG, "Failed to check for launcher upgrade!", e)
                }
                result
            } else {
                Logger.warning(TAG, "Failed to check for launcher upgrade!", e)
                null
            }
        }
    }

    // 安装包列表的获取已迁移到 [UpdateSourceProbe]：
    // 那里按「源」并发握手，连不上的源直接不显示，而不是在这里静默回退到 GitHub。
    // 以前那套 fetchFiles / fetchSiteAssets / fetchReleaseAssets 的问题是：
    // 官网返回非空就彻底短路 GitHub（官网给了 5 条但全失效时没有任何兜底），
    // 官网返回空才回退，而回退那一步在国内容易被墙 —— 用户只看到「没有可下载的包」。

    /**
     * 检查启动器是否需要更新
     * @param lastIgnored 上次弹出更新弹窗时，用户所忽略的版本号
     * @param ignoreDismissedVersions 是否忽略用户已忽略的版本
     * @param onUpgrade 发现需要更新时调用
     * @param onIsLatest 当前已是最新版本时
     */
    private suspend fun checkForUpgrade(
        data: RemoteData,
        lastIgnored: Int?,
        ignoreDismissedVersions: Boolean,
        onUpgrade: suspend (RemoteData) -> Unit,
        onIsLatest: suspend () -> Unit = {}
    ) {
        val currentVersionCode = BuildConfig.VERSION_CODE
        if (currentVersionCode < data.code) {
            //启动器为旧版本
            when {
                ignoreDismissedVersions && lastIgnored == data.code -> {
                    //忽略这次更新
                    Logger.info(TAG, "Launcher update detected: $currentVersionCode -> ${data.code}, but ignored by user")
                }
                else -> {
                    //弹出更新弹窗
                    Logger.info(TAG, "Launcher update detected: $currentVersionCode -> ${data.code}, dialog shown to user")
                    onUpgrade(data)
                }
            }
        } else {
            Logger.info(TAG, "Launcher is running the latest version: $currentVersionCode")
            onIsLatest()
        }
    }
}

@Composable
fun LauncherUpgradeOperation(
    operation: LauncherUpgradeOperation,
    onChanged: (LauncherUpgradeOperation) -> Unit,
    onIgnoredClick: (code: Int) -> Unit,
    onLinkClick: (String) -> Unit
) {
    when (operation) {
        is LauncherUpgradeOperation.None -> {}
        is LauncherUpgradeOperation.Upgrade -> {
            UpgradeDialog(
                data = operation.data,
                onDismissRequest = {
                    onChanged(LauncherUpgradeOperation.None)
                },
                onFilesClick = {
                    onChanged(LauncherUpgradeOperation.SelectApk(operation.data))
                },
                onIgnored = {
                    onIgnoredClick(operation.data.code)
                },
                onLinkClick = onLinkClick,
                onCloudDriveClick = { cloudDrive ->
                    onChanged(LauncherUpgradeOperation.OpenCloudDrive(cloudDrive))
                }
            )
        }
        is LauncherUpgradeOperation.ChooseSource -> {
            // 上游 Material 界面没有「选源」这一屏：这里直接按 json 里给的 files 进选包，
            // 没有就关掉。否则 operation 会停在一个没人渲染的状态 —— 弹窗消失、点什么都不开。
            // PCL 界面走的是完整流程，见 PclUpgradeHost。
            LaunchedEffect(operation) {
                onChanged(
                    if (operation.data.files.isEmpty()) LauncherUpgradeOperation.None
                    else LauncherUpgradeOperation.SelectApk(operation.data)
                )
            }
        }
        is LauncherUpgradeOperation.SelectApk -> {
            UpgradeFilesDialog(
                data = operation.data,
                onDismissRequest = {
                    onChanged(LauncherUpgradeOperation.None)
                },
                onFileSelected = { file ->
                    onLinkClick(file.uri)
                    onChanged(LauncherUpgradeOperation.None)
                }
            )
        }
        is LauncherUpgradeOperation.OpenCloudDrive -> {
            val current by remember(operation) {
                mutableStateOf<RemoteData.CloudDrive.Link?>(null)
            }
            SimpleListDialog(
                title = stringResource(R.string.upgrade_cloud_drive),
                items = operation.cloudDrive.links,
                onItemSelected = { link ->
                    onLinkClick(link.link)
                },
                onDismissRequest = {
                    onChanged(LauncherUpgradeOperation.None)
                },
                current = current,
                itemLayout = { item, isCurrent, onClick ->
                    CloudDriveLayout(
                        link = item,
                        selected = isCurrent,
                        onClick = onClick
                    )
                },
                showConfirm = true,
                confirmText = {
                    MarqueeText(text = stringResource(R.string.generic_confirm))
                }
            )
        }
    }
}


@Composable
private fun CloudDriveLayout(
    link: RemoteData.CloudDrive.Link,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled
        )
        Column(
            modifier = Modifier.alpha(if (enabled) 1.0f else DisabledAlpha),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            //网盘名称
            MarqueeText(
                modifier = Modifier.fillMaxWidth(),
                text = link.name,
                style = MaterialTheme.typography.labelMedium
            )
            //网盘链接
            MarqueeText(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(0.7f),
                text = link.link,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}