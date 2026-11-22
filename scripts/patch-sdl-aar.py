#!/usr/bin/env python3
"""
让 SDL3 的 SDL_SetWindowSize / SDL_SetWindowPosition 在 Android 上变成 no-op。

背景
----
Cleanroom 0.7.16 用 SDL3 作为窗口系统。Minecraft 初始化时会调用
SDL_SetWindowSize 设置窗口尺寸：

    com.cleanroommc.client.sdl.SDLException: SDL_SetWindowSize failed:
    That operation is not supported

SDL3 的 Android 视频驱动没有实现 SetWindowSize 回调（_this->SetWindowSize
为 NULL），而 SDL_SetWindowSize 在写完 window->w/h 之后仍旧走 "操作不支持"
的错误分支返回 false，Cleanroom 的 check() 就抛异常。

只把错误分支改成 "返回 true" 并不够
------------------------------------
那样 window->w/h 会被改成游戏请求的 854x480 之类的值，于是
SDL_GetWindowSize 返回 854x480，Minecraft 按这个尺寸设置 glViewport，
结果整个画面只画在屏幕左下角一小块（3000px 宽屏上的 854x480）。

因此这里直接把两个函数改成函数入口即 `return true`：

    mov w0, #1
    ret

窗口尺寸继续由 Android Surface 决定（全屏），SDL_GetWindowSize 也就返回
真实的全屏尺寸，Minecraft 的 viewport 才是全屏。

只处理 arm64-v8a（当前目标设备是 arm64）。
"""

import json
import os
import struct
import sys
import zipfile

AAR_REL = "ZalithLauncher/libs/SDL-release.aar"
TARGET_ABI = "arm64-v8a"
TARGET_SO = f"jni/{TARGET_ABI}/libSDL3.so"

TARGET_FUNCS = ["SDL_SetWindowSize", "SDL_SetWindowPosition"]
RET_TRUE = (0x52800020, 0xD65F03C0)  # mov w0,#1 ; ret


def load_elf64(data):
    """返回 (sections, dynsym 字典)。只支持 ELF64 小端。"""
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise ValueError("not an ELF64 little-endian file")
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum, _ = struct.unpack_from("<HHH", data, 0x3A)
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, stype, flags, addr, offset, size, link, info, align, entsize = \
            struct.unpack_from("<IIQQQQIIQQ", data, off)
        sections.append(dict(type=stype, addr=addr, offset=offset,
                             size=size, link=link))

    dynsym = next((s for s in sections if s["type"] == 11), None)
    if dynsym is None:
        raise ValueError("no .dynsym")
    strtab = sections[dynsym["link"]]
    syms = {}
    for i in range(dynsym["size"] // 24):
        off = dynsym["offset"] + i * 24
        st_name, st_info, st_other, st_shndx, st_value, st_size = \
            struct.unpack_from("<IBBHQQ", data, off)
        if st_value == 0:
            continue
        name_off = strtab["offset"] + st_name
        end = data.index(b"\x00", name_off)
        syms[data[name_off:end].decode("utf-8", "replace")] = (st_value, st_size)
    return sections, syms


def patch_arm64(data):
    sections, syms = load_elf64(data)
    messages = []

    def file_offset(vaddr):
        for s in sections:
            if s["type"] == 1 and s["addr"] <= vaddr < s["addr"] + s["size"]:
                return s["offset"] + (vaddr - s["addr"])
        raise ValueError(f"no section for vaddr {vaddr:#x}")

    for func_name in TARGET_FUNCS:
        if func_name not in syms:
            messages.append(f"  {func_name}: 符号不存在，跳过")
            continue
        func, size = syms[func_name]
        foff = file_offset(func)
        cur = struct.unpack_from("<II", data, foff)
        if cur == RET_TRUE:
            messages.append(f"  {func_name}: 已经是 no-op，跳过")
            continue
        struct.pack_into("<II", data, foff, *RET_TRUE)
        messages.append(f"  {func_name}: 入口改为 `mov w0,#1 ; ret`")
    return messages


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    aar_path = os.path.join(root, AAR_REL)
    if not os.path.isfile(aar_path):
        print(f"missing {aar_path}")
        return 1

    with zipfile.ZipFile(aar_path) as zin:
        items = [(info, zin.read(info.filename)) for info in zin.infolist()]

    out = []
    patched = False
    for info, raw in items:
        if info.filename == TARGET_SO:
            data = bytearray(raw)
            print(f"patching {TARGET_SO}")
            for msg in patch_arm64(data):
                print(msg)
            out.append((info, bytes(data)))
            patched = True
        else:
            out.append((info, raw))

    if not patched:
        print(f"{TARGET_SO} not found in aar")
        return 1

    tmp = aar_path + ".new"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for info, raw in out:
            zi = zipfile.ZipInfo(info.filename, date_time=(1980, 2, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = info.external_attr
            zout.writestr(zi, raw)
    os.replace(tmp, aar_path)
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
