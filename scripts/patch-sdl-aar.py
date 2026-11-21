#!/usr/bin/env python3
"""
给 SDL-release.aar 里的 libSDL3.so 打 arm64 补丁。

背景
----
Cleanroom 0.7.16 用 SDL3 作为窗口系统。Minecraft 初始化时会调用
SDL_SetWindowSize 设置窗口尺寸：

    com.cleanroommc.client.sdl.SDLException: SDL_SetWindowSize failed:
    That operation is not supported

SDL3 的 Android 视频驱动没有实现 SetWindowSize 回调（_this->SetWindowSize
为 NULL），于是 SDL_SetWindowSize 在已经写好 window->w/h 之后，仍旧走
"操作不支持" 的错误分支并返回 false，Cleanroom 的 check() 就抛异常了。

补丁
----
在 arm64-v8a 的 libSDL3.so 里，把 SDL_SetWindowSize 中
"回调为 NULL 就跳去报错" 的那条 cbz 改成跳去末尾的 `mov w0,#1`（返回 true）。
窗口的逻辑尺寸在跳转前已经写入，跳过驱动通知对 Android 没有副作用。

其他 ABI 暂不处理（当前目标设备是 arm64）。
"""

import io
import os
import shutil
import struct
import sys
import tempfile
import zipfile

AAR_REL = "ZalithLauncher/libs/SDL-release.aar"
TARGET_ABI = "arm64-v8a"
TARGET_SO = f"jni/{TARGET_ABI}/libSDL3.so"

# ldr x8, [x0, #off] —— SDL_SetWindow* 里取 _this->SetWindow* 驱动回调的编码
#  (函数名, ldr 编码)
PATCH_TARGETS = [
    ("SDL_SetWindowSize",      0xF9403408),  # ldr x8, [x0, #0x68]
    ("SDL_SetWindowPosition",  0xF9403008),  # ldr x8, [x0, #0x60]
]
MOV_W0_1 = 0x52800020


def load_elf64(data):
    """返回 (sections, dynsym_entries)。只支持 ELF64 小端。"""
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise ValueError("not an ELF64 little-endian file")
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, stype, flags, addr, offset, size, link, info, align, entsize = \
            struct.unpack_from("<IIQQQQIIQQ", data, off)
        sections.append(dict(name=name, type=stype, addr=addr, offset=offset,
                             size=size, link=link, entsize=entsize))

    dynsym = next((s for s in sections if s["type"] == 11), None)  # SHT_DYNSYM
    if dynsym is None:
        raise ValueError("no .dynsym")
    strtab = sections[dynsym["link"]]

    syms = {}
    count = dynsym["size"] // 24
    for i in range(count):
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
    """返回 (patched_count, 变更说明列表)。可重复执行：已打过补丁的会被跳过。"""
    sections, syms = load_elf64(data)

    # vaddr -> file offset 的辅助（按 section 查）
    def file_offset(vaddr):
        for s in sections:
            if s["type"] == 1 and s["addr"] <= vaddr < s["addr"] + s["size"]:
                return s["offset"] + (vaddr - s["addr"])
        raise ValueError(f"no section for vaddr {vaddr:#x}")

    messages = []
    for func_name, ldr_code in PATCH_TARGETS:
        if func_name not in syms:
            messages.append(f"  {func_name}: 符号不存在，跳过")
            continue
        func, size = syms[func_name]
        end = func + size
        foff = file_offset(func)

        words = {}
        for i in range(0, size, 4):
            words[func + i] = (foff + i,
                               struct.unpack_from("<I", data, foff + i)[0])

        # 函数里最后一条 mov w0,#1（返回 true 的出口）
        ret_true = None
        for addr in sorted(words, reverse=True):
            if words[addr][1] == MOV_W0_1:
                ret_true = addr
                break
        if ret_true is None:
            messages.append(f"  {func_name}: 找不到 mov w0,#1，跳过")
            continue

        done = False
        for addr in sorted(words):
            if words[addr][1] != ldr_code:
                continue
            for k in range(1, 8):
                cur = addr + k * 4
                if cur not in words:
                    break
                _, w = words[cur]
                if (w & 0xFF000000) != 0xB4000000 or (w & 0x1F) != 8:
                    continue
                imm19 = (w >> 5) & 0x7FFFF
                if imm19 & 0x40000:
                    imm19 -= 0x80000
                target = cur + (imm19 << 2)
                if ret_true == target:
                    messages.append(f"  {func_name}: 已打过补丁，跳过")
                    done = True
                    break
                if not (func <= target < end):
                    continue
                new_imm = (ret_true - cur) >> 2
                if not (-0x40000 <= new_imm < 0x40000):
                    raise ValueError("jump out of range")
                new_w = (w & ~(0x7FFFF << 5)) | ((new_imm & 0x7FFFF) << 5)
                struct.pack_into("<I", data, words[cur][0], new_w)
                messages.append(
                    f"  {func_name}: cbz x8 @ {cur:#x} 由 {target:#x} 改为 {ret_true:#x}")
                done = True
                break
            if done:
                break

        if not done:
            messages.append(f"  {func_name}: 没找到错误分支 cbz")
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
    shutil.move(tmp, aar_path)
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
