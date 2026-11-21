/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 上游版权：Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 * 本文件自 2026 年起被修改；修改声明与名称/标识的处理见仓库根目录 NOTICE.md。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。分发时请保留本声明、上游版权与 LICENSE。
 */

package com.zalith.pcl.game.renderer.renderers

import com.zalith.pcl.R
import com.zalith.pcl.game.renderer.ConfigurableRenderer
import com.zalith.pcl.game.renderer.config.ConfigTarget
import com.zalith.pcl.game.renderer.config.rendererConfigSpec

/**
 * MobileGL —— 桌面 OpenGL 4.6 的实现层，内置 `libMobileGL.so`。
 *
 * 上游：<https://github.com/MobileGL-Dev/MobileGL>（GNU LGPL-3.0，与本项目 GPLv3 兼容）。
 * 库以动态链接方式随 APK 分发，对应源码可从上述仓库取得；本文件不含对上游的任何修改。
 *
 * 环境变量与上游 Android 插件保持一致：`LIBGL_ES=3` + `LIBGL_EGL=libMobileGL.so`
 * 让游戏进程通过 EGL 加载它，`MOBILEGL_BACKEND_TYPE` 选择后端。
 */
object MobileGLRenderer : ConfigurableRenderer {
    override val configSpec = rendererConfigSpec {
        fixedEnv(
            "LIBGL_ES" to "3",
            "LIBGL_EGL" to "libMobileGL.so",
            "POJAV_RENDERER" to "opengles3",
            // 单进程（monolith）构建没有分离的服务端，传输层固定 inproc
            "MOBILEGL_TRANSPORT" to "inproc",
        )
        choice(
            id = "backend",
            title = R.string.renderer_mobilegl_backend_title,
            description = R.string.renderer_mobilegl_backend_desc,
            default = 0,
            target = ConfigTarget.Environment("MOBILEGL_BACKEND_TYPE")
        ) {
            option(
                "DirectGLES",
                R.string.renderer_mobilegl_backend_gles,
                R.string.renderer_mobilegl_backend_gles_desc
            )
            option(
                "DirectVulkan",
                R.string.renderer_mobilegl_backend_vulkan,
                R.string.renderer_mobilegl_backend_vulkan_desc
            )
        }
        switch(
            id = "disableTimerQuery",
            title = R.string.renderer_mobilegl_timerquery_title,
            description = R.string.renderer_mobilegl_timerquery_desc,
            default = true,
            target = ConfigTarget.Environment("MOBILEGL_DISABLE_TIMERQUERY")
        )
        switch(
            id = "relaxedSemantics",
            title = R.string.renderer_mobilegl_relaxed_title,
            description = R.string.renderer_mobilegl_relaxed_desc,
            default = false,
            target = ConfigTarget.Environment("MOBILEGL_RELAXED_SEMANTICS")
        )
    }

    override fun getRendererId(): String = "opengles3_mobilegl"

    override fun getUniqueIdentifier(): String = "6f1d2c7a-3b4e-4a58-9c21-8f0b5d7e9a13"

    override fun getRendererName(): String = "MobileGL"

    override fun getRendererSummary(): String = "OpenGL 4.6"

    override fun getRendererLibrary(): String = "libMobileGL.so"

    override fun getRendererEGL(): String = "libMobileGL.so"

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }
}
