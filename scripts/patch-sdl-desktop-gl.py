#!/usr/bin/env python3
"""
给预编译的 libSDL3.so 打「桌面 GL 枚举查询」补丁。

背景
----
Cleanroom 用 SDL 建窗口/上下文，LWJGL2 接管上下文时会调用
SDL_GL_GetAttribute(SDL_GL_DEPTH_SIZE)。SDL3 把它翻译成桌面 GL 的
glGetIntegerv(GL_DEPTH_BITS) 并检查 glGetError()，而 GLES 规范没有这个枚举，
直通 GLES 的渲染器（MobileGlues）会让驱动返回 GL_INVALID_ENUM，SDL 判定失败，
Cleanroom 抛 SDLException，游戏在初始化阶段崩溃。桌面 GL 模拟层（GL4ES/Krypton）
自己实现了这些枚举，所以不崩。

补丁内容（仅 arm64-v8a，偏移相对 SDL_GL_GetAttribute 起始）
---------------------------------------------------------
1. SDL_GL_DEPTH_SIZE 分支不再查询驱动，直接返回值 24：
     mov w19, #0xd56          -> mov w8, #24
     b   通用查询路径          -> b 0x1cedb4 (str w8,[x1])
2. 其余位深属性（red/green/blue/alpha/stencil）查询失败时不再报错：
     cbz w0, 成功路径          -> b 成功路径

对 Krypton / GL4ES 等本来就能成功的渲染器没有行为变化（它们不进错误分支，
depth 查询被绕过但返回值本来就是 24）。

用法
----
    python3 scripts/patch-sdl-desktop-gl.py ZalithLauncher/libs/SDL-release.aar
    python3 scripts/patch-sdl-desktop-gl.py /path/to/libSDL3.so

重复执行安全（已打过的补丁会被识别并跳过）。
"""

import argparse
import struct
import sys
import zipfile
from pathlib import Path

FUNC_NAME = "SDL_GL_GetAttribute"

# (相对函数起始的偏移, 原始指令, 补丁后指令, 说明)
PATCHES = [
    (0x110, 0x5281AAD3, 0x52800308,
     "SDL_GL_DEPTH_SIZE 分支：mov w19,#0xd56 -> mov w8,#24"),
    (0x114, 0x14000039, 0x14000035,
     "SDL_GL_DEPTH_SIZE 分支：b 通用查询 -> b str/return 路径"),
    (0x25C, 0x34000640, 0x14000032,
     "glGetError() 检查：cbz -> b（查询失败也当作成功）"),
]


def parse_elf_sections(data):
    (e_shoff,) = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum = struct.unpack_from("<HH", data, 0x3A)
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        _name, stype = struct.unpack_from("<II", data, off)
        addr, offset, size = struct.unpack_from("<QQQ", data, off + 16)
        link, _info, _align, entsize = struct.unpack_from("<IIQQ", data, off + 40)
        sections.append({
            "type": stype, "addr": addr, "offset": offset,
            "size": size, "link": link, "entsize": entsize,
        })
    return sections


def find_symbol(data, sections, name):
    symtab = next(s for s in sections if s["type"] == 11)  # SHT_DYNSYM
    strtab = sections[symtab["link"]]

    def cstr(off):
        end = data.index(b"\0", off)
        return data[off:end].decode("latin1")

    entsize = symtab["entsize"] or 24
    for i in range(symtab["size"] // entsize):
        off = symtab["offset"] + i * entsize
        nm, _info, _other, _shndx, value, size = struct.unpack_from("<IBBHQQ", data, off)
        if cstr(strtab["offset"] + nm) == name:
            return value, size
    return None


def patch_so(data: bytearray) -> bool:
    sections = parse_elf_sections(data)
    sym = find_symbol(data, sections, FUNC_NAME)
    if sym is None:
        raise SystemExit(f"找不到符号 {FUNC_NAME}，可能不是同一个 SDL 版本")
    func_addr, func_size = sym

    text = next(s for s in sections
                if s["type"] == 1 and s["addr"] <= func_addr < s["addr"] + s["size"])
    base = text["offset"] + (func_addr - text["addr"])

    changed = 0
    for rel, old, new, desc in PATCHES:
        off = base + rel
        actual = struct.unpack_from("<I", data, off)[0]
        if actual == new:
            print(f"  - {desc}: 已是补丁后状态，跳过")
            continue
        if actual != old:
            raise SystemExit(
                f"偏移 0x{rel:x} 处的指令是 0x{actual:08x}，与预期 0x{old:08x} 不符；"
                f"SDL 版本可能变了，需要重新分析"
            )
        struct.pack_into("<I", data, off, new)
        changed += 1
        print(f"  - {desc}: 已打补丁")
    return changed > 0


def patch_aar(aar: Path) -> None:
    entry = "jni/arm64-v8a/libSDL3.so"
    with zipfile.ZipFile(aar) as zin:
        names = zin.namelist()
        if entry not in names:
            raise SystemExit(f"{aar} 中没有 {entry}")
        data = bytearray(zin.read(entry))

    if not patch_so(data):
        print("无须修改")
        return

    tmp = aar.with_suffix(".aar.tmp")
    with zipfile.ZipFile(aar) as zin, zipfile.ZipFile(tmp, "w") as zout:
        for item in zin.infolist():
            payload = zin.read(item.filename)
            if item.filename == entry:
                payload = bytes(data)
            zout.writestr(item, payload)
    tmp.replace(aar)
    print(f"已写回 {aar}")


def main():
    parser = argparse.ArgumentParser(description="给 libSDL3.so 打桌面 GL 查询补丁")
    parser.add_argument("path", type=Path, help="SDL AAR 或裸 libSDL3.so")
    args = parser.parse_args()

    if not args.path.exists():
        raise SystemExit(f"文件不存在：{args.path}")

    if args.path.suffix == ".so":
        data = bytearray(args.path.read_bytes())
        if patch_so(data):
            args.path.write_bytes(bytes(data))
            print(f"已写回 {args.path}")
        else:
            print("无须修改")
    else:
        patch_aar(args.path)


if __name__ == "__main__":
    sys.exit(main())
