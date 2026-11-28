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

package com.zalith.pcl.game.renderer

import com.zalith.pcl.game.renderer.renderers.MobileGluesRenderer
import com.zalith.pcl.path.PathManager

/**
 * @return EGL shim 原生库的绝对路径
 */
fun getEglShimLibraryPath(): String = "${PathManager.DIR_NATIVE_LIB}/libeglshim.so"

/**
 * 判断为 [renderer] 启动游戏时是否需要插入 EGL shim。
 *
 * SDL3 的 `SDL_GL_GetAttribute(SDL_GL_DEPTH_SIZE)` 会退化成桌面 GL 的
 * `glGetIntegerv(GL_DEPTH_BITS)`。GLES 规范没有这个枚举，直接把 GL 调用
 * 透传给驱动的渲染器（目前就是 MobileGlues）会收到 `GL_INVALID_ENUM`，
 * 导致 Cleanroom 无法接管上下文。桌面 GL 模拟层（GL4ES / Krypton 等）自己
 * 实现了这些枚举，不需要也不应插入 shim。
 */
fun needsDesktopGlQueryShim(renderer: RendererInterface): Boolean =
    renderer.getRendererId() == MobileGluesRenderer.getRendererId()
