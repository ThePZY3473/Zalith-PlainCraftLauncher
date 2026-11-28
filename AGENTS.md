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

4. **「## 下载」章节**
   上游原文主推 「GitHub Releases + 官网 zpcl.fun 镜像」并附五架构下载表，
   本 fork 整章改写成 GitHub Actions 产物说明：

   > ## 下载
   >
   > 本仓库**不发布 GitHub Release**，APK 只作为 GitHub Actions 的构建产物提供……
   > （后面是下载步骤、`debug apk (arm64)` 产物表、如何手动跑其他架构）

   同时全文两处「不上传 APK」的说法要一起改：
   - 目录表里 `.github/workflows/` 那行 → 「做 Debug 编译校验并上传 Debug APK 产物」
   - 「关于 CI」段落 → 「编译产物以 `debug apk (<架构>)` 的名义上传到运行记录的 Artifacts」

5. **「## 已知限制」两条**
   - `- CurseForge 官方接口需要 \`x-api-key\`，未内置时只能使用 Modrinth 或镜像`
     行尾追加 `（现在没有hh）`
   - `- 开发线默认未配置微软登录 Client ID，离线和第三方认证不受影响。`
     改成 `- 开发线默认未配置微软登录 Client ID，离线和第三方认证不受影响（现在没有hh）（当然可以用账号管理模组进行替代）。`

6. **文末「## 关于本fork」章节**
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
   上游写 `zuanxu53-hub/ZPCL-Launcher`，本 fork 写 `ThePZY3473/Zalith-PlainCraftLauncher`
   （出现在「本仓库」条目和「五、问题反馈」的 Issues 链接里，两处都要改）。
   这个名字必须和 `git remote -v` 的 origin 一致，别按上游的仓库名去拼。


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
- 上游 `FileSink.kt` 用的是 `targetFile.toPath()`，本 fork 必须改成
  `Paths.get(targetFile.path)`：Android 7（API 25）的 `java.io.File` 没有 `toPath()`，
  而 desugar 不重写平台类。每次同步后 `grep -rn "\.toPath()" ZalithLauncher/src/main/java`
  复查（`LibraryComponents.toPath()` 是本仓库自己的扩展，返回 String，不算）。

## MobileGL 渲染器（2026-11 同步 v1.5.6 时引入）

- `libMobileGL.so` 只有 `arm64-v8a` 一份（17 MB，NDK 27 单进程构建）。`Renderers.init`
  里按 `Build.SUPPORTED_ABIS` 判断，非 arm64 环境不注册这个渲染器，避免选中后 dlopen 失败。
- 原始 so 引用了 `nl_langinfo@LIBC_O` 与 `pthread_getname_np@LIBC_O`（Android 8+），
  Android 7 上加载会失败。本 fork 已就地 patch：
  ```bash
  python3 scripts/patch-aar-elf.py ZalithLauncher/src/main/jniLibs/arm64-v8a/libMobileGL.so \
      --symbol nl_langinfo --symbol pthread_getname_np --needed liblog.so=libshm.so
  ```
  - `--symbol`：把符号版本降到 `VER_NDX_GLOBAL`，让 `libshm.so` 的 shim 能解析；
  - `--needed`：`liblog.so` → `libshm.so`（名字等长；`libshm` 自己依赖 `liblog`，
    `__android_log_print` 仍能解析）。
  - `__write_chk@LIBC_N` 是 API 24 就有，不用处理。
- 上游如果更新了 `libMobileGL.so`，上面这条命令要重跑一次，否则 Android 7 上再次加载失败。

## 其他注意

- `*.bak` 是本地备份，不提交。
- 签名密钥 `ZalithLauncher/zalith_launcher.jks`（别名 `cutepzy`）随仓库分发，
  密码来自环境变量 `STORE_PASSWORD` / `KEY_PASSWORD` 或根目录
  `.store_password.txt` / `.key_password.txt`（已 gitignore）。
- 本地没有 Android SDK，构建验证靠 GitHub Actions，改完推送后让用户手动下载
  `debug apk (arm64)`。
- 测试机走 `adb connect 127.0.0.1:5667`（Android 7.1.2 / SDK 25 / arm64 / vmos）；
  重启设备前必须先问用户。

## SDL3 桌面 GL 查询补丁（2026-11，修 MobileGlues 下 Cleanroom 启动崩溃）

- 现象：Cleanroom 0.7.16 + MobileGlues 启动即崩，
  `SDL_GL_GetAttribute(6) failed: OpenGL error: GL_INVALID_ENUM`（`6` = `SDL_GL_DEPTH_SIZE`）。
  SDL3 把该属性翻译成桌面 GL 的 `glGetIntegerv(GL_DEPTH_BITS = 0xD56)`，GLES
  规范没有这个枚举；MobileGlues 对未知 pname 直接透传给驱动，驱动返回
  `GL_INVALID_ENUM`，SDL 据此判定查询失败，Cleanroom 无法接管上下文。
- 先试过在 SDL 与 EGL 入口之间插一层 shim（`SDL_EGL_LIBRARY` 指向 shim、
  `EGLSHIM_REAL_LIB` 指向真实库），但 SDL 实际没有走这条加载路径，shim 从未
  被加载，已放弃并删除，不要重复这个思路。
- 最终做法：`python3 scripts/patch-sdl-desktop-gl.py ZalithLauncher/libs/SDL-release.aar`
  直接改 AAR 里 `jni/arm64-v8a/libSDL3.so` 的 `SDL_GL_GetAttribute`
  （偏移相对函数起始，固定）：
  1. `0x110`：`mov w19,#0xd56` → `mov w8,#24`，`0x114` 的 `b` 改指向
     `str w8,[x1]`，即 DEPTH_SIZE 不再查询驱动，直接返回 24；
  2. `0x25C`：`cbz w0, …` → `b …`，其余位深属性（red/green/blue/alpha/stencil）
     查询失败也当成功，值为 0。
- `libSDL2.so` 只是把 SDL2 的 attr 换表后尾调用 SDL3 的实现，因此只改 SDL3 即可。
- 脚本会校验原指令，SDL 版本变了会直接报错而不是改坏；上游换 SDL 版本后要重跑。
- 对 Krypton / GL4ES 等本来就能查到值的渲染器行为不变（它们不进错误分支）。

## SimpleFPEWrapper 现状（2026-11，已接线）

结论：**Kotlin 侧接线已完成**，对齐上游 ZalithLauncher2 2.8.2 的做法。

- `ZalithLauncher/libs/SimpleFPEWrapper-release.aar` 随仓库提供（arm64-v8a / armeabi-v7a /
  x86 / x86_64 四套 `libSimpleFPEWrapper.so`），Gradle 通过 `fileTree(libs)` 一并打包进 APK。
- `classes.jar` 是 22 字节的空壳，AAR 只有 native 库，`AndroidManifest.xml` 声明 minSdk 26，
  靠本 fork 的 `tools:overrideLibrary="top.mobilegl.simplefpewrapper"` 在 minSdk 24 上放行。

### 接线方式（不是预加载，是顶替 SDL 的 GL/EGL 入口）

上游的做法是让 SFPEW 直接充当 SDL 的 GL 和 EGL 库，它内部再按 `SFPEW_EGL` 去 dlopen
真正的渲染器。本 fork 在 `game/launch/GameLauncher.kt` 里照搬：

1. `setRendererEnv()` 里，`isSfpewEnabledFor(renderer, version)` 为真时：
   - `SFPEW_LAYER=1`
   - `SFPEW_EGL` = 已设置的 `SDL_EGL_LIBRARY` → `POJAVEXEC_EGL` → `libEGL.so`
   - 版本开了宽松上下文时 `SFPEW_RELAXED_CONTEXT=1`
   - `SDL_OPENGL_LIBRARY` 与 `SDL_EGL_LIBRARY` 都改成 `getSfpewLibraryPath()`
2. `progressFinalUserArgs()` 里 `-Dorg.lwjgl.opengl.libname` 改走
   `getGameRendererLibrary(version)`（启用时返回 SFPEW 路径），否则 LWJGL 会绕过兼容层。
3. `dlopenEngine()` 里仍然 dlopen **原渲染器库**（上游也是这样），用于启动器进程的探测。

### 启用条件

沿用 2.8.1 的两参数判定：`!version.fpeEmulatorModDetected && isSfpewConfiguredFor(...)`。

- 插件渲染器 → 版本级 `sfpewPluginLayer`（默认 `FOLLOW_GLOBAL`，全局默认 **false**）
- 内置渲染器 → `supportsSfpewByDefault()`，目前只有 MobileGlues 返回 true
- `detectFpeEmulatorMods()` 识别 `angelica` / `actinium`，检测到就禁用

因此默认行为：**Krypton Wrapper（插件）+ 全局开关关闭 → 不启用**；MobileGlues 默认启用。
测试 1.12.2-Cleanroom 时请留意渲染器是不是 MobileGlues，别把 SFPEW 的问题算到 Krypton 头上。

### 未移植的两点（上游 2.8.2 有）

- 渲染器级开关 `ConfigTarget.SfpewLayer` / `LoadedRenderer.sfpewLayerEnabled`：
  我们这版没有 `RendererLaunchConfig` 那套，先沿用全局 + 版本级设置。
- FPE 的版本下限豁免：我们这版 `MobileGluesRenderer` 没覆盖 `getMinMCVersion()`，
  本来就不拦版本，不需要。

### Android 7 上的 AAR

上游原版 so 引用了 `nl_langinfo@LIBC_O`（Android 8+）。本仓库里的 AAR 已 patch 过：

```bash
python3 scripts/patch-aar-elf.py ZalithLauncher/libs/SimpleFPEWrapper-release.aar \
    --symbol nl_langinfo --needed liblog.so=libshm.so
```

`--needed` 是必须的：`nl_langinfo` 在 Android 7 的 libc 里不存在，只有让 `libshm.so`
进 local group 才能解析到 `locale_shim.c` 里的实现；`libshm` 自己依赖 `liblog`，
`__android_log_print` 照样能用。上游换新版 AAR 时这条命令要重跑。
