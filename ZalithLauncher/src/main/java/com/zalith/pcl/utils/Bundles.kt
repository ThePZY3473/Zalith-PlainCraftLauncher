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

package com.zalith.pcl.utils

import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import java.io.Serializable

/**
 * Android 13（API 33）上**不能**用 `Bundle.getParcelable(String, Class)`。
 *
 * 带 `Class` 的那个新 API 在框架内部要反射取 `CREATOR` 的 `clazz` 字段，取不到时会在
 * `Parcel.readParcelableCreatorInternal` 里对 null 调 `Class.isAssignableFrom`，
 * 抛出来的正是：
 *
 * ```
 * java.lang.NullPointerException: Attempt to invoke virtual method
 * 'boolean java.lang.Class.isInterface()' on a null object reference
 * ```
 *
 * 它发生在 `ActivityThread.performLaunchActivity` 里，最终表现成
 * 「Unable to start activity ...」—— 看着像 Activity 自己的问题，实际是读 Intent 参数。
 *
 * 这是平台 bug（b/232589966），Android 14 才修。androidx 的 `BundleCompat` 也是用
 * 「>= 34 才调新 API」绕开的，这里保持一致：33 退回旧的、不带 `Class` 的重载，
 * 再用 `clazz.isInstance` 自己把类型校验补回来。
 *
 * 典型受害者是 `VMActivity`：它在 `:game` 子进程里读 `LaunchConfig`。
 * 只有跨进程的 Parcelable 才会走这段反序列化，所以症状是「一启动游戏就崩，启动器本体没事」。
 */
@Suppress("DEPRECATION", "UNCHECKED_CAST")
fun <T : Parcelable> Bundle.getParcelableSafely(key: String?, clazz: Class<T>): T? {
    val raw: Parcelable? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> getParcelable(key, clazz)
        else -> getParcelable(key)
    }
    return if (raw != null && clazz.isInstance(raw)) raw as T else null
}

/** 同样的坑：带 `Class` 的 `getSerializable` 在 33 上也别调，理由见上。 */
@Suppress("DEPRECATION", "UNCHECKED_CAST")
fun <T : Serializable> Bundle.getSerializableSafely(key: String?, clazz: Class<T>): T? {
    val raw: Serializable? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> getSerializable(key, clazz)
        else -> getSerializable(key)
    }
    return if (raw != null && clazz.isInstance(raw)) raw as T else null
}