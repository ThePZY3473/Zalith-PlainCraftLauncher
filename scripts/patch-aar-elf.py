#!/usr/bin/env python3
"""就地修改 AAR 里预编译 so 的两类属性。

背景（Android 7 / API 25）：
1. 预编译库按版本引用 `nl_langinfo@LIBC_O`，而 Android 7 的 bionic 无法用
   另一个库（locale_shim）的同名符号满足这种带版本引用。把引用改成无版本
   （versym = VER_NDX_GLOBAL）后，普通导出即可解析。
2. 更麻烦的是 bionic 在 `dlopen` 的符号解析里并不总是纳入 RTLD_GLOBAL 库，
   于是把 shim 直接写进这些库的 DT_NEEDED，让它成为 local group 的一员，
   符号解析才有保证。

两处修改都只改写既有字节（相同长度或更短），不动段结构、代码和重定位。

用法：
    python3 scripts/patch-aar-elf.py <aar> \
        [--symbol nl_langinfo ...] \
        [--needed liblog.so=libshim.so ...]
"""

import argparse
import struct
import sys
import zipfile
from pathlib import Path

SHT_DYNSYM = 11
SHT_DYNAMIC = 6
SHT_GNU_VERSYM = 0x6FFFFFFF
DT_NEEDED = 1
VER_NDX_GLOBAL = 1


class Elf:
    def __init__(self, data: bytes):
        if data[:4] != b"\x7fELF":
            raise ValueError("not an ELF file")
        self.data = data
        self.is64 = data[4] == 2
        if self.is64:
            (self.shoff,) = struct.unpack_from("<Q", data, 0x28)
            self.shentsize, self.shnum = struct.unpack_from("<HH", data, 0x3A)
        else:
            (self.shoff,) = struct.unpack_from("<I", data, 0x20)
            self.shentsize, self.shnum = struct.unpack_from("<HH", data, 0x2E)
        self.sections = []
        for i in range(self.shnum):
            off = self.shoff + i * self.shentsize
            if self.is64:
                fields = struct.unpack_from("<IIQQQQIIQQ", data, off)
            else:
                fields = struct.unpack_from("<IIIIIIIIII", data, off)
            name, stype, _flags, addr, offset, size, link, _info, _align, entsize = fields
            self.sections.append(
                {
                    "index": i,
                    "type": stype,
                    "addr": addr,
                    "offset": offset,
                    "size": size,
                    "link": link,
                    "entsize": entsize,
                }
            )

    def first(self, stype):
        return next((s for s in self.sections if s["type"] == stype), None)

    def string_at(self, strtab, offset):
        start = strtab["offset"] + offset
        end = self.data.index(b"\x00", start)
        return self.data[start:end].decode("utf-8", "replace")

    def dyn(self):
        dynsym = self.first(SHT_DYNSYM)
        dynstr = self.sections[dynsym["link"]]
        return dynsym, dynstr


def patch_versym(elf: Elf, out: bytearray, symbols):
    dynsym, dynstr = elf.dyn()
    versym = elf.first(SHT_GNU_VERSYM)
    if versym is None:
        return []
    entsize = dynsym["entsize"] or (24 if elf.is64 else 16)
    patched = []
    for i in range(dynsym["size"] // entsize):
        name_off = struct.unpack_from("<I", elf.data, dynsym["offset"] + i * entsize)[0]
        if elf.string_at(dynstr, name_off) in symbols:
            pos = versym["offset"] + i * 2
            old = struct.unpack_from("<H", elf.data, pos)[0]
            if old != VER_NDX_GLOBAL:
                struct.pack_into("<H", out, pos, VER_NDX_GLOBAL)
                patched.append(f"{elf.string_at(dynstr, name_off)}:{old}->1")
    return patched


def patch_needed(elf: Elf, out: bytearray, mapping):
    dyn = elf.first(SHT_DYNAMIC)
    if dyn is None:
        return []
    dynstr = elf.sections[dyn["link"]]
    entsize = 16 if elf.is64 else 8
    fmt = "<qQ" if elf.is64 else "<iI"
    patched = []
    for i in range(dyn["size"] // entsize):
        pos = dyn["offset"] + i * entsize
        tag, val = struct.unpack_from(fmt, elf.data, pos)
        if tag != DT_NEEDED:
            continue
        name = elf.string_at(dynstr, val)
        new = mapping.get(name)
        if not new or new == name:
            continue
        if len(new) > len(name):
            print(f"    跳过 {name} -> {new}：新名字更长")
            continue
        start = dynstr["offset"] + val
        out[start : start + len(name)] = new.encode() + b"\x00" * (len(name) - len(new))
        patched.append(f"{name}->{new}")
    return patched


def patch_aar(aar: Path, symbols, needed):
    with zipfile.ZipFile(aar) as src:
        infos = src.infolist()
        entries = {info.filename: src.read(info.filename) for info in infos}

    changed = 0
    for name in list(entries):
        if not name.endswith(".so"):
            continue
        try:
            elf = Elf(entries[name])
        except ValueError as exc:
            print(f"  跳过 {name}: {exc}")
            continue
        out = bytearray(entries[name])
        detail = patch_versym(elf, out, symbols) + patch_needed(elf, out, needed)
        if detail:
            entries[name] = bytes(out)
            changed += 1
            print(f"  {name}: {', '.join(detail)}")

    if not changed:
        print(f"{aar.name}: 没有需要修改的内容")
        return 0

    tmp = aar.with_suffix(".aar.tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in infos:
            dst.writestr(info, entries[info.filename])
    tmp.replace(aar)
    print(f"{aar.name}: 已更新 {changed} 个 so")
    return changed


def patch_elf_file(path: Path, symbols, needed):
    """直接就地修改裸 so（不是 AAR 里的那种，比如 jniLibs 下的预编译库）。"""
    data = path.read_bytes()
    try:
        elf = Elf(data)
    except ValueError as exc:
        print(f"{path.name}: {exc}")
        return 0
    out = bytearray(data)
    detail = patch_versym(elf, out, symbols) + patch_needed(elf, out, needed)
    if not detail:
        print(f"{path.name}: 没有需要修改的内容")
        return 0
    path.write_bytes(bytes(out))
    print(f"{path.name}: {', '.join(detail)}")
    return 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("aar", type=Path, help="AAR 文件，或后缀为 .so 的裸 ELF")
    parser.add_argument("--symbol", action="append", default=[])
    parser.add_argument("--needed", action="append", default=[])
    args = parser.parse_args()

    needed = {}
    for item in args.needed:
        old, _, new = item.partition("=")
        needed[old] = new
    if args.aar.suffix == ".so":
        patch_elf_file(args.aar, set(args.symbol), needed)
    else:
        patch_aar(args.aar, set(args.symbol), needed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
