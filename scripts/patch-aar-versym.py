#!/usr/bin/env python3
"""把 AAR 里预编译 so 中某个符号的符号版本改为“无版本”（VER_NDX_GLOBAL）。

背景：Android 7 (API 25) 的 bionic linker 无法用另一个库（locale_shim）提供的
同名符号满足带版本引用，例如 libng_gl4es.so 里的 `nl_langinfo@LIBC_O`。
在 Android 8+ 上这本来由 libc.so 的同版本符号提供，Android 7 上不存在。

这里直接把引用改成无版本引用（versym = 1），让它能解析到 locale_shim 中
普通的 nl_langinfo 导出。只改 `.gnu.version` 数组中的两个字节，
不影响代码、符号表或其它重定位。

用法：
    python3 scripts/patch-aar-versym.py <aar> <symbol> [<symbol> ...]
"""

import struct
import sys
import zipfile
from pathlib import Path

SHF_ALLOC = 0x2
SHT_DYNSYM = 11
SHT_GNU_VERSYM = 0x6FFFFFFF
VER_NDX_GLOBAL = 1


def parse_sections(data: bytes):
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = data[4] == 2
    if is64:
        (e_shoff,) = struct.unpack_from("<Q", data, 0x28)
        (e_shentsize, e_shnum) = struct.unpack_from("<HH", data, 0x3A)
    else:
        (e_shoff,) = struct.unpack_from("<I", data, 0x20)
        (e_shentsize, e_shnum) = struct.unpack_from("<HH", data, 0x2E)

    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        if is64:
            name, stype, flags, addr, offset, size, link, info, align, entsize = struct.unpack_from(
                "<IIQQQQIIQQ", data, off
            )
        else:
            name, stype, flags, addr, offset, size, link, info, align, entsize = struct.unpack_from(
                "<IIIIIIIIII", data, off
            )
        sections.append(
            {
                "index": i,
                "name_off": name,
                "type": stype,
                "offset": offset,
                "size": size,
                "entsize": entsize,
            }
        )
    return is64, sections


def patch_elf(data: bytes, symbols):
    is64, sections = parse_sections(data)
    dynsym = next((s for s in sections if s["type"] == SHT_DYNSYM), None)
    versym = next((s for s in sections if s["type"] == SHT_GNU_VERSYM), None)
    if dynsym is None or versym is None:
        raise ValueError("missing .dynsym or .gnu.version")

    strtab_index = struct.unpack_from("<I", data, dynsym["offset"] + (0x18 if is64 else 0x10))[0] if False else None
    # sh_link 指向字符串表 section
    sh_link = None
    # 重新读取 section header 以拿到 sh_link
    e_shoff = struct.unpack_from("<Q" if is64 else "<I", data, 0x28 if is64 else 0x20)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x3A if is64 else 0x2E)[0]
    off = e_shoff + dynsym["index"] * e_shentsize
    if is64:
        sh_link = struct.unpack_from("<I", data, off + 40)[0]
    else:
        sh_link = struct.unpack_from("<I", data, off + 24)[0]
    strtab = sections[sh_link]

    entsize = dynsym["entsize"] or (24 if is64 else 16)
    count = dynsym["size"] // entsize
    out = bytearray(data)
    patched = []

    for i in range(count):
        item = dynsym["offset"] + i * entsize
        name_off = struct.unpack_from("<I", data, item)[0]
        start = strtab["offset"] + name_off
        end = data.index(b"\x00", start)
        name = data[start:end].decode("utf-8", "replace")
        if name in symbols:
            vpos = versym["offset"] + i * 2
            old = struct.unpack_from("<H", data, vpos)[0]
            if old != VER_NDX_GLOBAL:
                struct.pack_into("<H", out, vpos, VER_NDX_GLOBAL)
            patched.append((name, old))
    return bytes(out), patched


def patch_aar(aar_path: Path, symbols):
    with zipfile.ZipFile(aar_path) as src:
        entries = {info.filename: src.read(info.filename) for info in src.infolist()}
        infos = src.infolist()

    changed = 0
    for name in list(entries):
        if not name.endswith(".so"):
            continue
        data = entries[name]
        if data[:4] != b"\x7fELF":
            continue
        try:
            new_data, patched = patch_elf(data, symbols)
        except Exception as exc:  # noqa: BLE001
            print(f"  跳过 {name}: {exc}")
            continue
        if patched:
            entries[name] = new_data
            changed += 1
            detail = ", ".join(f"{n}:{old}->1" for n, old in patched)
            print(f"  {name}: {detail}")

    if not changed:
        print(f"{aar_path.name}: 没有需要修改的符号")
        return 0

    tmp = aar_path.with_suffix(".aar.tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in infos:
            dst.writestr(info, entries[info.filename])
    tmp.replace(aar_path)
    print(f"{aar_path.name}: 已更新 {changed} 个 so")
    return changed


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    aar = Path(sys.argv[1])
    symbols = set(sys.argv[2:])
    patch_aar(aar, symbols)
    return 0


if __name__ == "__main__":
    sys.exit(main())
