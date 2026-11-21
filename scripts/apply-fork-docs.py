#!/usr/bin/env python3
"""从上游同步代码后，重放本 fork 对 README.md / NOTICE.md 的定制。

用法：
    python3 scripts/apply-fork-docs.py

脚本是幂等的：已经应用过的文件再跑一次不会产生任何变化。
上游改动导致某条规则匹配不到时，会打印 WARN，需要手动检查对应段落。
"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------------------
# README.md
# ---------------------------------------------------------------------------

README_RULES = [
    # 「版本与发布」：本仓库通过 Actions 提供 APK
    (
        "安装包以 **GitHub Release 附件**的形式发布，每个 Release 的正文注明它对应的源码 tag；"
        "官网 <https://zpcl.fun> 是对外的下载入口，`url_home` 也指向它。仓库仍跟踪部分构建所用的预编译依赖；"
        "它们不是启动器安装包，也不是源码，其来源与许可见 [第三方组件清单](docs/THIRD_PARTY.md)。",
        "本仓库 Releases 不挂 APK ，可以在 Action 里找到 APK 。",
    ),
    # 已知限制：CurseForge 未内置 key
    (
        "- CurseForge 官方接口需要 `x-api-key`，未内置时只能使用 Modrinth 或镜像\n",
        "- CurseForge 官方接口需要 `x-api-key`，未内置时只能使用 Modrinth 或镜像（现在没有hh）\n",
    ),
    # 已知限制：正版登录未内置 client_id
    (
        "- 开发线默认未配置微软登录 Client ID，离线和第三方认证不受影响。\n",
        "- 开发线默认未配置微软登录 Client ID，离线和第三方认证不受影响（现在没有hh）（当然可以用账号管理模组进行替代）。\n",
    ),
]

README_TOC_AFTER = "- [许可与致谢](#许可与致谢)\n"
README_TOC_LINE = "- [关于本fork](#关于本fork)\n"

README_ABOUT = """

---

## 关于本fork

*(虽然好像没人看)*

- 本人（ThePZY3473/CutePZY）**几乎没有**编程基础，依靠AI进行*~~许愿式~~*编程，此fork仅用于满足本人需要，如果你不需要本仓库做出的改动，建议回到上游仓库

- 如果你被本仓库不规范的做法或其他任何原因惹恼，并愿意帮助这个*几乎没有编程基础的可怜人*，可以提一提Issues。*~~（别交pr，我看不懂）~~*

- 本仓库相对于上游进行了以下改动：
1 : 向下兼容到Android7.0(Sdk24)（不稳定！）
"""

# ---------------------------------------------------------------------------
# NOTICE.md
# ---------------------------------------------------------------------------

NOTICE_RULES = [
    # 安装包分发说明（GPLv3 §6 段落）
    (
        "1.5.5 起的安装包作为 GitHub Release 附件分发，正文标注对应的源码 tag。需要说明的是："
        "正式线（含内置主题与正版登录接线）与开发线并非同一份源树，本仓库目前公开的是开发线；"
        "在正式线对应源码公开或以其他方式提供之前，不能把这些 APK 视为已具备完整的对应源码。",
        "本仓库安装包通常位于Action，大概不会发release。",
    ),
]

# 本 fork 的仓库地址（全文替换，会覆盖「本仓库」「问题反馈」两处）
NOTICE_OLD_REPO = "zuanxu53-hub/ZPCL-Launcher"
NOTICE_NEW_REPO = "CutePZY/ZPCL-Launcher"


def apply_rules(path: Path, rules, warnings):
    text = path.read_text(encoding="utf-8")
    original = text

    for old, new in rules:
        if new in text:
            continue  # 已经应用过
        if old in text:
            text = text.replace(old, new, 1)
        else:
            warnings.append(f"{path.name}: 未找到待替换片段 -> {old.splitlines()[0][:60]!r}")

    if text != original:
        path.write_text(text, encoding="utf-8")
        print(f"已更新 {path.name}")
    else:
        print(f"无变化 {path.name}")


def main() -> int:
    warnings = []

    readme = ROOT / "README.md"
    if not readme.exists():
        print("缺少 README.md", file=sys.stderr)
        return 1

    # README：目录条目
    text = readme.read_text(encoding="utf-8")
    if README_TOC_LINE not in text:
        if README_TOC_AFTER in text:
            text = text.replace(README_TOC_AFTER, README_TOC_AFTER + README_TOC_LINE, 1)
        else:
            warnings.append("README.md: 目录里找不到「许可与致谢」条目")
        readme.write_text(text, encoding="utf-8")

    # README：正文替换 + 末尾章节
    apply_rules(readme, README_RULES, warnings)
    text = readme.read_text(encoding="utf-8")
    # 本 fork 支持到 minSdk 24，上游 README 默认写的是 26
    if "minSdk 26" in text:
        text = text.replace("minSdk 26", "minSdk 24")
        readme.write_text(text, encoding="utf-8")
        print("已更新 README.md minSdk 26 -> 24")
    if "## 关于本fork" not in text:
        readme.write_text(text.rstrip() + README_ABOUT, encoding="utf-8")
        print("已追加 README.md「关于本fork」章节")

    notice = ROOT / "NOTICE.md"
    if not notice.exists():
        print("缺少 NOTICE.md", file=sys.stderr)
        return 1

    apply_rules(notice, NOTICE_RULES, warnings)
    text = notice.read_text(encoding="utf-8")
    if NOTICE_OLD_REPO in text:
        notice.write_text(text.replace(NOTICE_OLD_REPO, NOTICE_NEW_REPO), encoding="utf-8")
        print("已更新 NOTICE.md 仓库地址")

    for w in warnings:
        print(f"WARN {w}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
