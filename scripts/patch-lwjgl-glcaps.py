#!/usr/bin/env python3
"""
给 lwjgl-3.4.1-merged-modules.jar 里的 org/lwjgl/opengl/GLCapabilities 补回
Cleanroom（lwjgly）需要的 GL_APPLE_* 能力字段。

背景
----
Cleanroom 0.7.16 的 lwjgly 2.8.0 是 LWJGL2 → LWJGL3 的兼容层，它的
org.lwjgl.opengl.ContextCapabilities 会从 LWJGL3 的
org.lwjgl.opengl.GLCapabilities 里读取 313 个能力字段，其中 14 个是
GL_APPLE_* 扩展：

    GL_APPLE_aux_depth_stencil       GL_APPLE_row_bytes
    GL_APPLE_client_storage          GL_APPLE_texture_range
    GL_APPLE_element_array           GL_APPLE_vertex_array_object
    GL_APPLE_fence                   GL_APPLE_vertex_array_range
    GL_APPLE_float_pixels            GL_APPLE_vertex_program_evaluators
    GL_APPLE_flush_buffer_range      GL_APPLE_ycbcr_422
    GL_APPLE_object_purgeable
    GL_APPLE_rgb_422

而 LWJGL 3.4.1-snapshot 的 GLCapabilities 里已经把这批 macOS 专属扩展的
boolean 字段删掉了，于是游戏启动时报：

    java.lang.NoSuchFieldError: Class org.lwjgl.opengl.GLCapabilities
    does not have member field 'boolean GL_APPLE_aux_depth_stencil'
        at org.lwjgl.opengl.ContextCapabilities.<init>(...)

补丁
----
在 GLCapabilities.class 的字段表末尾追加这 14 个 `public final boolean`。
字段值保持 JVM 默认的 false（Android 上本来也不支持 Apple 扩展），
不需要改构造器。

脚本可重复执行，已存在的字段会跳过；修改完会同步更新 manifest.json 里的
size / sha1，这样已安装的组件在下次启动时 SHA-1 校验失败会自动重装。
"""

import json
import os
import struct
import sys
import zipfile

JAR_REL = "ZalithLauncher/src/main/assets/app_runtime/lwjgl/3.4.1/lwjgl-3.4.1-merged-modules.jar"
MANIFEST_REL = "ZalithLauncher/src/main/assets/app_runtime/lwjgl/3.4.1/manifest.json"
CLASS_NAME = "org/lwjgl/opengl/GLCapabilities.class"

MISSING_FIELDS = [
    "GL_APPLE_aux_depth_stencil",
    "GL_APPLE_client_storage",
    "GL_APPLE_element_array",
    "GL_APPLE_fence",
    "GL_APPLE_float_pixels",
    "GL_APPLE_flush_buffer_range",
    "GL_APPLE_object_purgeable",
    "GL_APPLE_rgb_422",
    "GL_APPLE_row_bytes",
    "GL_APPLE_texture_range",
    "GL_APPLE_vertex_array_object",
    "GL_APPLE_vertex_array_range",
    "GL_APPLE_vertex_program_evaluators",
    "GL_APPLE_ycbcr_422",
]

CP_TAGS = {
    1: 0, 3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
    12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2,
}


def u2(b, o):
    return struct.unpack_from(">H", b, o)[0]


def u4(b, o):
    return struct.unpack_from(">I", b, o)[0]


def parse_constant_pool(data, off):
    """返回 (constant_pool_count, 常量池结束偏移, {Utf8 内容: 索引})。"""
    count = u2(data, off)
    off += 2
    utf8 = {}
    i = 1
    while i < count:
        tag = data[off]
        if tag not in CP_TAGS:
            raise ValueError(f"bad constant pool tag {tag} at {off:#x}")
        size = CP_TAGS[tag]
        if tag == 1:
            ln = u2(data, off + 1)
            utf8.setdefault(
                bytes(data[off + 3:off + 3 + ln]).decode("utf-8", "replace"), i)
            size = 2 + ln
        off += 1 + size
        i += 2 if tag in (5, 6) else 1
    return count, off, utf8


def add_fields(class_bytes, fields):
    data = bytearray(class_bytes)
    if bytes(data[:4]) != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")

    count, cp_end, utf8 = parse_constant_pool(data, 8)
    missing = [f for f in fields if f not in utf8]
    if not missing:
        return bytes(data), []

    # 常量池末尾追加字段名与描述符 "Z"
    extra = bytearray()
    name_index = {}
    next_index = count
    for name in missing:
        raw = name.encode("utf-8")
        extra += b"\x01" + struct.pack(">H", len(raw)) + raw
        name_index[name] = next_index
        next_index += 1
    if "Z" in utf8:
        descriptor_index = utf8["Z"]
    else:
        extra += b"\x01\x00\x01Z"
        descriptor_index = next_index
        next_index += 1
    new_count = next_index

    body = data[cp_end:]
    # body 布局: access(2) this(2) super(2) interfaces_count(2) interfaces(2N)
    #            fields_count(2) fields... methods... attributes...
    ifc_count = u2(body, 6)
    fields_off = 8 + 2 * ifc_count
    fields_count = u2(body, fields_off)

    new_fields = bytearray()
    for name in missing:
        new_fields += struct.pack(">HHHH", 0x0011, name_index[name], descriptor_index, 0)

    # 原字段表紧跟在 fields_count 之后
    tail_start = fields_off + 2
    # 找到原字段表结尾需要跳过现有字段的 attribute（本类字段不带属性）
    def field_entry_len(b, o):
        attrs = u2(b, o + 6)
        o += 8
        for _ in range(attrs):
            ln = u4(b, o + 2)
            o += 6 + ln
        return o

    o = tail_start
    for _ in range(fields_count):
        o = field_entry_len(body, o)

    new_body = (
        body[:fields_off]
        + struct.pack(">H", fields_count + len(missing))
        + body[tail_start:o]
        + bytes(new_fields)
        + body[o:]
    )

    cp_begin = 10
    out = (
        bytes(data[:8])
        + struct.pack(">H", new_count)
        + bytes(data[cp_begin:cp_end])
        + bytes(extra)
        + bytes(new_body)
    )
    return out, missing


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    jar_path = os.path.join(root, JAR_REL)
    manifest_path = os.path.join(root, MANIFEST_REL)
    if not os.path.isfile(jar_path):
        print(f"missing {jar_path}")
        return 1

    with zipfile.ZipFile(jar_path) as zin:
        infos = zin.infolist()
        blobs = {i.filename: zin.read(i.filename) for i in infos}

    patched, missing = add_fields(blobs[CLASS_NAME], MISSING_FIELDS)
    if not missing:
        print("GLCapabilities: 字段已存在，跳过")
        return 0

    print("追加字段: " + ", ".join(missing))
    blobs[CLASS_NAME] = patched

    with zipfile.ZipFile(jar_path, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in infos:
            zi = zipfile.ZipInfo(info.filename, date_time=(1980, 2, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = info.external_attr
            zout.writestr(zi, blobs[info.filename])

    # 更新 manifest 里的 size / sha1
    import hashlib
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)
    jar_name = os.path.basename(jar_path)
    raw = open(jar_path, "rb").read()
    for entry in manifest["files"]:
        if entry["path"] == jar_name:
            entry["size"] = len(raw)
            entry["sha1"] = hashlib.sha1(raw).hexdigest()
            print(f"manifest 更新: size={entry['size']} sha1={entry['sha1']}")
            break
    else:
        print(f"manifest 中没有 {jar_name}")
        return 1
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
