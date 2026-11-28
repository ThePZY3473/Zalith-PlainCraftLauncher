# ZPCL Launcher

ZPCL Launcher 是面向 Android 设备的 Minecraft: Java Edition 启动器。

启动核心基于 [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2) 与
[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)；界面按
[Plain Craft Launcher 2](https://github.com/Hex-Dragon/PCL2)（下称 PCL2）的版式与交互重新实现，
使用 Jetpack Compose 编写。

> 当前版本：**1.5.6（versionCode 200105）**，对应上游 Zalith Launcher 2 **2.8.1**。
> 本项目为 Zalith Launcher 2 的非官方修改版，与上游官方无隶属关系，亦未获其背书。
> 名称与标识的处理、修改范围、问题反馈渠道见 [NOTICE.md](NOTICE.md)。

---

## 下载

本仓库**不发布 GitHub Release**，APK 只作为 [GitHub Actions](https://github.com/ThePZY3473/Zalith-PlainCraftLauncher/actions) 的构建产物提供：

1. 打开最近一次成功的 **Build CI** 运行记录；
2. 在页面底部的 **Artifacts** 里下载 `debug apk (arm64)`；
3. 解压后得到 APK，直接覆盖安装。

| 产物 | 架构 | 适用设备 |
|---|---|---|
| `debug apk (arm64)` | arm64-v8a | 绝大多数 Android 手机与平板 |

推送触发的构建默认只出 arm64。需要其他架构时，在 **Actions → Build APK → Run workflow** 里把 `arch` 改成 `arm`、`x86_64`、`x86` 或 `all`。

Debug 变体的包名是 `com.zpcl.launcher.debug`，使用项目签名构建，可以直接覆盖安装；它和正式版 `com.zpcl.launcher` 是两个独立应用，实例与存档位于共享存储，不受影响。

---

## 目录

- [下载](#下载)
- [功能](#功能)
- [两条产线](#两条产线)
- [构建](#构建)
- [插件开发](#插件开发)
- [目录结构](#目录结构)
- [版本与发布](#版本与发布)
- [已知限制](#已知限制)
- [许可与致谢](#许可与致谢)
- [关于本fork](#关于本fork)

---

## 功能

### 界面

- 首页 / 下载 / 版本设置 / 联机 / 设置，按 PCL2 的分栏结构重排（左栏列表 + 右侧内容区）
- 鼠标可用：列表项、按钮、下拉菜单都有指向高亮；低缩放或矮窗口下弹窗正文可滚动、按钮固定在底部
- 二级页是真正的页面栈：资源详情 → 前置资源 → 再返回，逐层退回而不是直接跳回首页

### 下载

- **原版游戏**：选版本 → 装游戏本体，可同时安装 Fabric / Forge / NeoForge / Quilt / OptiFine
- **社区资源**：Mod / 整合包 / 资源包 / 光影包 / 存档
  - 默认来源是 **Modrinth**。CurseForge 官方接口需要 `x-api-key`；开发版未配置 Key 时，CurseForge 搜索使用镜像，不请求官方接口。
  - 默认 **32 条并发连接**，可在「设置 → 下载 → 最大线程数」调整，下一次下载生效
  - 下载源可切「自动 / 官方 / 镜像」，失败会自动换源重试

### 实例

- 新建、复制、删除、重置；每个实例有独立的版本设置（渲染器、内存、Java 参数等）
- 整合包：本地导入与系统分享接入，安装前可自定义版本名，失败自动清理不完整版本
- 导出整合包：按 PCL 的匹配规则打包，规则里的特殊字符（如 `[宝]`）不会再把启动器打崩

### 运行

- 多渲染器可选，并为 Sodium / Iris 这类硬性要求桌面 GL 3.3+ 的模组做自动兼容
- 陶瓦（Terracotta）联机模块内置，未启用时联机页给出引导
- 更新检查：`latest_version_md.json` + 官网分流，更新弹窗里常驻「从官网下载（若下载缓慢）」

### 插件

启动器支持单独安装的插件 APK；插件清单和资源放在**插件自身**的 `assets/zplugin/`，不是开发版内置主题：

- **皮肤插件**：换配色、圆角、玻璃质感，可选接管部分版块页面
- **自带界面插件**：`entry: true` 的插件会在首页多一个胶囊入口，页面内容由插件自己声明
- 插件能往版块左栏追加条目、整块接管某个版块、通过 ContentProvider 提供版本列表等数据

写插件请看 **[docs/PLUGIN_GUIDE.md](docs/PLUGIN_GUIDE.md)**（目录结构、`plugin.json`、
`pages.json`、积木与动作清单、数据通道、最小示例与排错表）。

---

## 两条产线

本仓库维护开发线；正式发行使用单独的工程，两条线的代码和发布制品须分别核对。

| | 开发线 | 正式线 |
|---|---|---|
| 用途 | 日常开发、调试 | 对外发行 |
| 应用包名 | `com.zpcl.launcher.debug` | `com.zpcl.launcher` |
| 常用构建类型 | Debug | Release |
| 微软正版登录 | 保留登录代码；默认不配置 Client ID | 发行构建按需配置 Client ID |
| 内置主题切换及解锁 | 不包含；保留默认配色和外部皮肤插件接口 | 正式线独有 |

`assembleRelease` 只是构建当前工程的 Gradle Release 变体，不会把开发线变成正式线，也不会补上正式线独有功能。

---

## 构建

### 环境

- **JDK 17**（`JAVA_HOME` 指向 JDK 17；JDK 8 会在 Gradle 阶段直接失败）
- Android SDK：`minSdk 24`、`targetSdk 34`，`compileSdk` 见
  [`ZalithLauncher/build.gradle.kts`](ZalithLauncher/build.gradle.kts)
- 首次构建需要联网拉依赖

### 命令

```bash
# 调试包（单架构，约 210 MB）
export JAVA_HOME=/path/to/jdk17
./gradlew :ZalithLauncher:assembleDebug -Darch=arm64

# 在当前工程构建 Release 变体（仍需本地签名配置）
./gradlew :ZalithLauncher:assembleRelease -Darch=arm64
```

`-Darch` 可指定 `arm`、`arm64`、`x86`、`x86_64`；使用 `all` 或不传则构建包含全部 ABI 的包。

| 传入 | 产物 |
|---|---|
| `arm64` | `arm64-v8a` |
| `arm` | `armeabi-v7a` |
| `x86` / `x86_64` | 对应架构 |
| 不传 | 全 ABI（无架构后缀） |

产物在 `ZalithLauncher/build/outputs/apk/{debug,release}/`，文件名以 `ZPCL-Launcher-` 开头，Debug 包额外带有 `Debug-`。

### 签名

当前工程的 Debug 和 Release 变体均使用 `ZalithLauncher/build.gradle.kts` 中的本地签名配置。公开源码不含项目签名私钥；从干净克隆构建时，需要在本地使用自己的 keystore 和别名、密码，或为自己的 Debug 构建改用 Android 默认调试签名。项目签名使用的密码可通过 `STORE_PASSWORD` / `KEY_PASSWORD` 或受 Git 忽略的本地文件配置。请勿提交签名文件或密码；不同签名的 APK 不能直接覆盖安装。

### 微软登录和 CurseForge

开发源码不内置项目的微软 OAuth Client ID 或 CurseForge API Key。构建脚本可从环境变量 `OAUTH_CLIENT_ID` / `CURSEFORGE_API_KEY` 或仓库根目录受 Git 忽略的 `.oauth_client_id.txt` / `.curseforge_api.txt` 读取配置，**目前不会按 Debug / Release 变体隔离这些输入**。维护者仅在正式线构建环境提供项目专用配置；未配置 Client ID 时离线登录不受影响。

---

## 插件开发

插件是一个普通 APK，只要包内带 `assets/zplugin/plugin.json`，启动器就把它当插件识别。
页面用 JSON 描述（`pages.json`），启动器只负责渲染，具体内容与数据都在插件里。

完整协议见 **[docs/PLUGIN_GUIDE.md](docs/PLUGIN_GUIDE.md)**，包括：

- `plugin.json` 全部字段（含 `entry` / `label` / `ui`）
- `pages` / `sections`（整块接管版块）/ `entries`（往左栏加一条）
- 积木（title、text、card、button、switch、status…）与动作（page、launch、open、copy、install、app…）
- ContentProvider 数据通道：`content://<包名>.pclplugin` 的 `call(method, arg)`
- 升级注意：**插件 APK 的 `versionCode` 必须递增**，否则启动器不会重新解包

---

## 目录结构

| 路径 | 内容 |
|---|---|
| `ZalithLauncher/` | 启动器主模块（Gradle 模块名沿用上游，**只是路径**；程序显示名与标识不含上游名） |
| `…/ui/pcl2/` | PCL2 界面：外壳、左栏、各版块页面 |
| `…/ui/pcl2/components/` | PCL 控件库（按钮、列表项、卡片、弹窗、下拉） |
| `…/game/` | 下载、启动、渲染器、插件管理 |
| `…/game/plugin/` | 插件识别与安装（以 `assets/zplugin/plugin.json` 为识别依据） |
| `docs/PLUGIN_GUIDE.md` | 插件开发指南 |
| `docs/THIRD_PARTY.md` | 第三方组件与许可清单 |
| `latest_version_md.json` | 应用内「检查更新」读取的版本清单 |
| `CardGrid/` `ColorPicker/` `InputMap/` `LayerController/` `LWJGL/` `Terracotta/` | 渲染、输入、联机等子模块 |
| `.github/workflows/` | GitHub Actions：推送时做 Debug 编译校验并上传 Debug APK 产物，不注入发行配置 |

---

## 版本与发布

本仓库 Releases 不挂 APK ，可以在 Action 里找到 APK 。

**关于 CI：** 推送触发的 [构建工作流](.github/workflows/push_ci.yml) 只做 Debug 编译校验，不向 Debug 构建注入正版登录或 CurseForge 配置；编译产物以 `debug apk (<架构>)` 的名义上传到运行记录的 Artifacts，供测试下载。只有以 `Release` 变体调用时才使用仓库 Secrets；这类构建由维护者自己发起，需要对应架构的 RELEASE 时才手动触发。

正式发行时：

1. 维护 `ZalithLauncher/gradle.properties` 的显示版本与 `versionCode`；要覆盖升级，`versionCode` 必须递增，两条线一起改。
2. 同步 `latest_version_md.json` 的版本、`code`、官网地址与更新说明——客户端「检查更新」读的就是它。
3. 在 APK 下载处注明该具体制品对应的源码提交、标签或归档，不以开发分支或仓库首页代替正式 APK 的对应源。详见 [NOTICE.md](NOTICE.md)。

`launcher_name` 与 `CLIENT_TOKEN_SEED` 属于登录与数据目录的标识，**不要修改**。

---

## 已知限制

- 仅支持 Android（`minSdk 24`）；iOS / 桌面端不在范围内
- CurseForge 官方接口需要 `x-api-key`，未内置时只能使用 Modrinth 或镜像（现在没有hh）
- 开发线默认未配置微软登录 Client ID，离线和第三方认证不受影响（现在没有hh）（当然可以用账号管理模组进行替代）。
- 皮肤插件同一时间只生效一个；多个一起启用会互相覆盖外观
- 插件升级后必须让插件 APK 的 `versionCode` 递增，否则启动器不会重新解包（界面会停留在旧配置）

---

## 许可与致谢

本项目按 **[GNU GPLv3](LICENSE)** 分发，是 Zalith Launcher 2 的非官方修改版。程序在适用法律允许的范围内按现状提供，不附带担保。上游的名称、标注与版权告知要求，以及当前版本的实际标注位置，见下方修改声明。

修改声明、名称处理、源码获取与署名见 **[NOTICE.md](NOTICE.md)**；第三方组件与许可见
**[docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)**。

`Zalith Launcher` / `PCL2` 的名称与标识归各自作者所有；本项目只在「基于谁」的意义上提及它们，
不用作自身名称，也不声称与其有关联。

感谢 [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2)、
[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)、
[PCL2](https://github.com/Hex-Dragon/PCL2) 以及所有上游依赖的作者。

界面按 PCL2 的版式与交互重新实现，未使用 PCL2 的源代码；PCL2 以 GNU GPLv3 发布，仓库地址 <https://github.com/Hex-Dragon/PCL2>。

---

## 关于本fork

*(虽然好像没人看)*

- 本人（ThePZY3473/CutePZY）**几乎没有**编程基础，依靠AI进行*~~许愿式~~*编程，此fork仅用于满足本人需要，如果你不需要本仓库做出的改动，建议回到上游仓库

- 如果你被本仓库不规范的做法或其他任何原因惹恼，并愿意帮助这个*几乎没有编程基础的可怜人*，可以提一提Issues。*~~（别交pr，我看不懂）~~*

- 本仓库相对于上游进行了以下改动：
1 : 向下兼容到Android7.0(Sdk24)（不稳定！）
