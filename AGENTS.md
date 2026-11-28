# 项目约定

## 这是 ZPCL-Launcher 的 fork

本仓库基于上游 [zuanxu53-hub/ZPCL-Launcher](https://github.com/zuanxu53-hub/ZPCL-Launcher)
（更上游是 [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2)），
额外维护 minSdk 24（Android 7.0）兼容与若干本地化修改。

## 从上游同步代码后：手动重放 README / NOTICE 的定制

**不要再写脚本自动化这一步。** 原来有一个 `scripts/apply-fork-docs.py`，已经删除：
上游段落一改，脚本就会静默匹配不到、或者把内容改歪，反而更难发现。
从上游同步代码后，由 AI 或人工按照下面的清单**逐条核对并手工改回**。

核对起点永远是这一条命令：

```bash
git diff upstream/main -- README.md NOTICE.md
```

正常情况下差异应该**只有**下面列出的这些。多出来的部分是上游新改动，需要逐条判断是
保留上游、还是套用本 fork 的写法；如果上游改写的正好是本 fork 定制过的那一段，**手工合并**，
不要直接整段丢弃上游内容。

### README.md 的 5 处定制

1. **目录入口**
   在 `- [许可与致谢](#许可与致谢)` 这一行之后插入：

   ```
   - [关于本fork](#关于本fork)
   ```

   如果上游调整了目录顺序，就让它跟在「许可与致谢」后面即可。

2. **构建环境里的 minSdk**
   上游是 `- Android SDK：\`minSdk 26\`、\`targetSdk 34\`，\`compileSdk\` 见`，
   本 fork 改成 `minSdk 24`。全文出现的 `minSdk 26` 都要改成 `minSdk 24`
   （正文「环境」和「已知限制」两处通常都有）。

3. **「## 版本与发布」第一段**
   上游原文（整段替换，不要只改半句）：

   > 安装包以 **GitHub Release 附件**的形式发布，每个 Release 的正文注明它对应的源码 tag；官网 <https://zpcl.fun> 是对外的下载入口，`url_home` 也指向它。仓库仍跟踪部分构建所用的预编译依赖；它们不是启动器安装包，也不是源码，其来源与许可见 [第三方组件清单](docs/THIRD_PARTY.md)。

   本 fork 写成：

   > 本仓库 Releases 不挂 APK ，可以在 Action 里找到 APK 。

   注意保留原文里的空格和标点（`APK ，` 那个空格是现状，别顺手"修"掉，
   否则每次 diff 都会多出一行噪音）。

4. **「## 已知限制」两条**
   - `- CurseForge 官方接口需要 \`x-api-key\`，未内置时只能使用 Modrinth 或镜像`
     行尾追加 `（现在没有hh）`
   - `- 开发线默认未配置微软登录 Client ID，离线和第三方认证不受影响。`
     改成 `- 开发线默认未配置微软登录 Client ID，离线和第三方认证不受影响（现在没有hh）（当然可以用账号管理模组进行替代）。`

5. **文末「## 关于本fork」章节**
   在「许可与致谢」之后加一条 `---` 分隔线，再接下面这段原文：

   ```markdown
   ## 关于本fork

   *(虽然好像没人看)*

   - 本人（ThePZY3473/CutePZY）**几乎没有**编程基础，依靠AI进行*~~许愿式~~*编程，此fork仅用于满足本人需要，如果你不需要本仓库做出的改动，建议回到上游仓库

   - 如果你被本仓库不规范的做法或其他任何原因惹恼，并愿意帮助这个*几乎没有编程基础的可怜人*，可以提一提Issues。*~~（别交pr，我看不懂）~~*

   - 本仓库相对于上游进行了以下改动：
   1 : 向下兼容到Android7.0(Sdk24)（不稳定！）
   ```

### NOTICE.md 的 3 处定制

1. **仓库地址**
   上游写 `zuanxu53-hub/ZPCL-Launcher`，本 fork 写 `CThePZY3473/ZPCL-Launcher`
   （出现在「本仓库」条目和「五、问题反馈」的 Issues 链接里，两处都要改）。


2. **「三、开发源码与安装包的对应关系」末段**
   上游原文：

   > 1.5.5 起的安装包作为 GitHub Release 附件分发，正文标注对应的源码 tag。需要说明的是：正式线（含内置主题与正版登录接线）与开发线并非同一份源树，本仓库目前公开的是开发线；在正式线对应源码公开或以其他方式提供之前，不能把这些 APK 视为已具备完整的对应源码。

   本 fork 写成：

   > 本仓库安装包通常位于Action，大概不会发release。

   这一条是 GPLv3 §6 的对应关系说明，改之前想清楚：如果以后真的开始发 Release，
   这里的说法也要跟着改回去，不能一直挂着"不发 release"。

3. **「五、问题反馈」的 Issues 链接**
   同上第 1 条，替换成确认后的本仓库地址。

### 文档之外要一起留意的

- `ZalithLauncher/build.gradle.kts` 的 `minSdk` 必须保持 24；Cargo/NDK 配置里如果出现
  26 的硬编码也要一起改。上游同步后跑一次 `grep -rn "minSdk 26" .` 确认没有漏网。
- 本 fork 的 `AndroidManifest.xml` 里有一句 `tools:overrideLibrary` 覆盖
  openal-soft / NG-GL4ES / SimpleFPEWrapper 的 minSdk 26 声明，这段注释不要删。

## 其他注意

- `*.bak` 是本地备份，不提交。
- 签名密钥 `ZalithLauncher/zalith_launcher.jks`（别名 `cutepzy`）随仓库分发，
  密码来自环境变量 `STORE_PASSWORD` / `KEY_PASSWORD` 或根目录
  `.store_password.txt` / `.key_password.txt`（已 gitignore）。
- 本地没有 Android SDK，构建验证靠 GitHub Actions，改完推送后让用户手动下载
  `debug apk (arm64)`。
- 测试机走 `adb connect 127.0.0.1:5667`（Android 7.1.2 / SDK 25 / arm64 / vmos）；
  重启设备前必须先问用户。

## SimpleFPEWrapper 现状（2026-11）

结论：**目前是"只有壳、没接线"的状态，编译进 APK 但运行时不生效。**

具体：

- `ZalithLauncher/libs/SimpleFPEWrapper-release.aar` 随仓库提供（arm64-v8a / armeabi-v7a /
  x86 / x86_64 四套 `libSimpleFPEWrapper.so`），Gradle 通过 `fileTree(libs)` 一并打包进 APK。
- `classes.jar` 是 22 字节的空壳，AAR 只有 native 库，`AndroidManifest.xml` 声明 minSdk 26，
  靠本 fork 的 `tools:overrideLibrary="top.mobilegl.simplefpewrapper"` 在 minSdk 24 上放行。
- Kotlin 侧只有 `game/renderer/SimpleFPEWrapper.kt` 里的四个函数：
  `getSfpewLibraryPath()`、`isSfpewConfiguredFor()`、`isSfpewEnabledFor()`、`detectFpeEmulatorMods()`。
- **`getSfpewLibraryPath()` 和 `isSfpewEnabledFor()` 没有任何调用者**（上游 2.8.1 也是如此，
  不是本 fork 删掉的）。也就是说没有任何代码把 `libSimpleFPEWrapper.so` 预加载进渲染器进程，
  也没有设置 `SFPEW_LAYER` 之类的环境变量。`MobileGluesRenderer.getConfiguredEnv()` 只加了
  `MG_DIR_PATH`。
- 实际生效的只有 UI 和检测部分：
  - `supportsSfpewByDefault()`：只有 MobileGlues 返回 true；
  - 设置项 `sfpewPluginLayer` / `sfpewRelaxedContext`（全局 + 版本级）；
  - `detectFpeEmulatorMods()` 会识别 `angelica` / `actinium`，检测到就禁用兼容层；
  - `LauncherElements.kt:312` 用 `isSfpewConfiguredFor()` 给低版本游戏弹提示。
- 对当前测试版本（1.12.2-Cleanroom + Krypton Wrapper）**没有任何影响**，因为 Krypton 不是
  Plugin 且 `supportsSfpewByDefault()` 为 false。

要真正接线，需要在新版本上补这些（目前没做，也不在上游计划里）：

1. 启动时若 `isSfpewEnabledFor(...)` 为 true，把 `libSimpleFPEWrapper.so` 用
   `RTLD_GLOBAL` 预加载到 **游戏进程**，或者通过 `LD_PRELOAD` 等效机制注入；
2. 确认 SFPEW 期望的入口符号/环境变量（看 MobileGL-Dev/SimpleFPEWrapper 源码），
   把渲染器插件包装层串起来；
3. minSdk 24 上还要验证它的 `libc` 符号引用是否又缺 Android 8+ 的符号
   （`@LIBC_O` 那一类问题，参考 `libshm.so` 的做法）。

在接线完成前，别在 UI 上把它写成"可用功能"。
