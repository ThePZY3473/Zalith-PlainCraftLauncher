# NOTICE — 修改声明与来源

本仓库是 **Zalith Launcher 2** 的修改版，按 GNU 通用公共许可证第 3 版（GPLv3）分发。

- **本版本名称**：ZPCL Launcher
- **本版本版本号**：1.5.4（versionCode 200086）
- **上游项目**：Zalith Launcher 2 — <https://github.com/ZalithLauncher/ZalithLauncher2>
- **上游源码基线**：2.8.1（2026-11 合并；此前基线为 2.6.1）
- **上游版权**：Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
- **本仓库**：<https://github.com/zuanxu53-hub/ZPCL-Launcher>
- **许可证**：GPLv3，全文见 [LICENSE](LICENSE)

## 一、这是修改版（GPLv3 §5(a)）

本程序**不是** Zalith Launcher 2 的官方版本，与上游作者无隶属关系，也未获其背书。

对上游源码的修改自 2025 年起持续进行，最近一次修改：**2026-11**。主要修改内容：

| 范围 | 修改 |
|---|---|
| 界面 | 按 PCL2 的版式重做主界面、下载、版本设置、联机等页面（`ui/pcl2/`） |
| 下载 | 多线程下载引擎、镜像换源、Modrinth / CurseForge 双源、整合包导入导出 |
| 启动 | 多渲染器选择与自动兼容、启动参数、日志收集 |
| 扩展 | 插件机制（皮肤 / 自带界面插件）、页面 JSON 协议 |
| 修复 | 导航栈、弹窗裁切、整合包导入竞态、导出规则正则崩溃等 |
| 保留 | 上游 2.8 已移除的 Cleanroom 模组加载器支持（`ModLoader.CLEANROOM` 及其下载/启动链路） |

**与上游 2.8.1 的差异说明**：本版是以 2.6.1 为基线、合并上游 2.8.1 改动后的分支。
上游 2.8 新增的 `PetLib`（Rust 原生 3D 皮肤预览模块）**未包含**在本版中，
因此本版没有「3D 皮肤预览」功能，账号界面沿用 2.x 原有的 2D 皮肤预览。
其余上游改动（下载器、整合包、账号、渲染器配置模型等）均已合并。

逐文件的修改历史见本仓库的 git 记录；每个被修改过的源文件都在文件头保留了上游版权声明与本项目说明。

## 二、名称与标识（GPLv3 §7(c) 附加条款）

上游在 GPLv3 §7 下附加了以下条款，本仓库遵守：

1. 修改版的名称**不得包含** `ZalithLauncher` 或其缩写 `ZL`，也不得使用与官方名称相近、可能引起混淆的名称。
   - 本版本原名 `Zalith-Plain Craft Launcher`、短名 `ZL2` **不符合**该条款，已于 2026-11 改名为
     **ZPCL Launcher**（短名 `ZPCL`）。
2. 必须在**启动页或主界面以明显方式**标注「非官方修改版」。
   - 已在原生启动页、PCL 解压页顶栏、设置 → 关于页三处标注：
     「非官方修改版 · 与 Zalith Launcher 官方无关」。
3. 不得移除程序中显示的版权声明（§7(b)）。本仓库保留了全部上游版权头与 LICENSE。

## 三、源代码（GPLv3 §6）

本仓库即对应源代码，与对外分发的安装包同源同版本；构建方法见 [README](README.md#构建)。

本仓库**不存放安装包**（仓库与 Releases 均不含 APK），分发的 APK 由本项目官网提供。
即便如此，GPLv3 §6 要求的「对应源代码」始终可以从本仓库完整取得：任何拿到安装包的人，
都能在 <https://github.com/zuanxu53-hub/ZPCL-Launcher> 取得同一版本的源码。

## 四、第三方组件

随程序分发的第三方库及其许可见 [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)。
其中包含 LGPL / Apache / MIT 等不同许可的组件，各自条款优先于本项目整体许可。

## 五、商标与素材

- `Zalith Launcher`、`Zalith Launcher 2` 的名称与标识归其作者所有；
- `Plain Craft Launcher`、`PCL2` 的名称与标识归龙腾猫跃所有；
- 本项目的界面**版式参考** PCL2，但不使用其名称作为自身标识，也不声称与其有关联；
- 仓库内的图标素材沿用上游仓库资源（随 GPLv3 一同分发）。若上游作者对素材另有要求，
  以作者要求为准，届时会替换为本项目自制素材。

## 六、问题反馈

本修改版的问题**不要**提交给上游仓库。请在本仓库提 issue：
<https://github.com/zuanxu53-hub/ZPCL-Launcher/issues>

反馈时请附：版本号（设置 → 关于）、设备型号与 Android 版本、复现步骤、日志（设置 → 其他 → 导出日志）。
