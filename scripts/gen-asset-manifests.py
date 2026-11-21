#!/usr/bin/env python3
"""生成 / 校验 assets 组件的 SHA-1 清单（manifest.json）。

上游 ZalithLauncher 2.8.1 起，运行时组件（LWJGL、authlib-injector、caciocavallo、
launcher 组件、JNA）在解包前会用 assets 内的 manifest.json 做完整性校验；
但上游仓库里并不提交这些清单文件，需要构建时生成。

用法：
    python3 scripts/gen-asset-manifests.py          # 生成/更新所有清单
    python3 scripts/gen-asset-manifests.py --check  # 只检查，过期则退出码 1

注意：这里只扫描源码 assets 目录。LWJGL 的 natives 来自 libs 下的
natives AAR，只在 Gradle 的 mergeAssets 阶段合并，源码目录里没有；
构建时 ZalithLauncher/build.gradle.kts 会按最终（含 ABI 过滤）的 assets
重新生成一次清单，那份才是随 APK 发布的版本。
"""

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "ZalithLauncher" / "src" / "main" / "assets"

# 所有走 UnpackSingleTask 校验的组件目录（相对 assets）
COMPONENT_DIRS = [
    "components/auth_libs",
    "components/caciocavallo",
    "components/caciocavallo17",
    "components/launcher",
    "app_runtime/lwjgl/3.3.3",
    "app_runtime/lwjgl/3.4.1",
    "runtimes/jna",
]

# 清单文件本身与旧版 version 标记不参与校验
EXCLUDED_NAMES = {"manifest.json", "version"}

MANIFEST_NAME = "manifest.json"
FORMAT_VERSION = 1
ALGORITHM = "SHA-1"


def sha1_of(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_manifest(component: str) -> dict:
    base = ASSETS / component
    if not base.is_dir():
        raise SystemExit(f"组件目录不存在：{base}")

    files = []
    for path in sorted(base.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(base).as_posix()
        # 只排除组件根目录下的元数据文件
        if "/" not in relative and relative in EXCLUDED_NAMES:
            continue
        files.append(
            {
                "path": relative,
                "size": path.stat().st_size,
                "sha1": sha1_of(path),
            }
        )

    if not files:
        raise SystemExit(f"组件目录为空：{base}")

    return {
        "formatVersion": FORMAT_VERSION,
        "component": component,
        "algorithm": ALGORITHM,
        "files": files,
    }


def render(manifest: dict) -> str:
    return json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    check_only = "--check" in sys.argv[1:]
    changed = []

    for component in COMPONENT_DIRS:
        manifest_path = ASSETS / component / MANIFEST_NAME
        expected = render(build_manifest(component))
        actual = manifest_path.read_text(encoding="utf-8") if manifest_path.is_file() else None

        if actual == expected:
            print(f"OK        {manifest_path.relative_to(ROOT)}")
            continue

        changed.append(manifest_path.relative_to(ROOT))
        if check_only:
            print(f"OUTDATED  {manifest_path.relative_to(ROOT)}")
        else:
            manifest_path.write_text(expected, encoding="utf-8")
            print(f"UPDATED   {manifest_path.relative_to(ROOT)}")

    if check_only and changed:
        print("\n清单已过期，请运行：python3 scripts/gen-asset-manifests.py", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
