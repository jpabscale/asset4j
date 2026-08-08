#!/usr/bin/env python3
"""Oracle: mirror the AssetsTools.NET bundle read path in Python (header + block info + LZ4/
LZMA decompression) and dump each contained file's bytes to a dir, plus a manifest JSON.
Used to validate the asset4j Kotlin bundle port (Phase 3)."""
import hashlib
import json
import os
import struct
import sys

import lz4.block
import lzma


def decompress_lzma(data: bytes, out_size: int) -> bytes:
    props, dict_size = struct.unpack("<BI", data[:5])
    lc = props % 9
    remainder = props // 9
    pb = remainder // 5
    lp = remainder % 5
    dec = lzma.LZMADecompressor(
        format=lzma.FORMAT_RAW,
        filters=[{"id": lzma.FILTER_LZMA1, "dict_size": dict_size, "lc": lc, "lp": lp, "pb": pb}],
    )
    return dec.decompress(data[5:])[:out_size]


def decompress_lz4(data: bytes, out_size: int) -> bytes:
    return lz4.block.decompress(data, uncompressed_size=out_size)


def read_bundle(path: str):
    data = open(path, "rb").read()
    pos = data.index(b"\x00") + 1
    signature = data[: pos - 1].decode()
    version = struct.unpack(">I", data[pos : pos + 4])[0]
    pos += 4
    e = data.index(b"\x00", pos)
    gen_ver = data[pos:e].decode()
    pos = e + 1
    e = data.index(b"\x00", pos)
    eng_ver = data[pos:e].decode()
    pos = e + 1
    assert signature == "UnityFS", signature

    total_size, comp_size, decomp_size, flags = struct.unpack(">QIII", data[pos : pos + 20])
    pos += 20
    if version >= 7:
        pos = (pos + 15) & ~15

    # bundle info offset
    if flags & 0x80:  # at end
        info_off = total_size - comp_size
    else:
        ret = len(gen_ver) + len(eng_ver) + 0x1A
        if version >= 7:
            if flags & 0x100:
                ret = ((ret + 0x0A) + 15) & ~15
            else:
                ret = ((ret + len(signature) + 1) + 15) & ~15
        else:
            if flags & 0x100:
                ret = ret + 0x0A
            else:
                ret = ret + len(signature) + 1
        info_off = ret

    comp_type = flags & 0x3F
    if comp_type == 0:
        info_bytes = data[info_off : info_off + comp_size]
    elif comp_type == 1:
        info_bytes = decompress_lzma(data[info_off : info_off + comp_size], decomp_size)
    else:
        info_bytes = decompress_lz4(data[info_off : info_off + comp_size], decomp_size)

    p = 0
    p += 16  # hash
    # Block info / directory info are read BIG-endian (the bundle reader is BigEndian).
    block_count = struct.unpack(">i", info_bytes[p : p + 4])[0]
    p += 4
    blocks = []
    for _ in range(block_count):
        dec, cmp, fl = struct.unpack(">IIH", info_bytes[p : p + 10])
        p += 10
        blocks.append((dec, cmp, fl))
    dir_count = struct.unpack(">i", info_bytes[p : p + 4])[0]
    p += 4
    entries = []
    for _ in range(dir_count):
        off, size, fl2 = struct.unpack(">qqI", info_bytes[p : p + 20])
        p += 20
        e = info_bytes.index(b"\x00", p)
        name = info_bytes[p:e].decode("utf-8", "replace")
        p = e + 1
        entries.append((name, off, size, fl2))

    # file data offset (GetFileDataOffset)
    ret = len(gen_ver) + len(eng_ver) + 0x1A
    if flags & 0x100:
        ret += 0x0A
    else:
        ret += len(signature) + 1
    if version >= 7:
        ret = (ret + 15) & ~15
    if not (flags & 0x80):
        ret += comp_size
    if flags & 0x200:
        ret = (ret + 15) & ~15
    data_start = ret

    out = bytearray()
    for dec, cmp, fl in blocks:
        ctype = fl & 0x3F
        chunk = data[data_start : data_start + cmp]
        data_start += cmp
        if ctype == 0:
            out += chunk
        elif ctype == 1:
            out += decompress_lzma(chunk, dec)
        else:
            out += decompress_lz4(chunk, dec)

    return signature, version, eng_ver, entries, bytes(out)


def main():
    outdir = "/tmp/opencode/bundle_out"
    os.makedirs(outdir, exist_ok=True)
    manifest = {}
    for root, _dirs, files in os.walk("assetapi/src/test/resources/testassets"):
        for fn in files:
            if not (fn.endswith(".bundle") or fn.endswith(".unity3d") or fn.endswith(".ab")):
                continue
            path = os.path.join(root, fn)
            try:
                sig, ver, eng, entries, data = read_bundle(path)
                info = {"signature": sig, "version": ver, "engine": eng, "files": {}}
                for name, off, size, fl in entries:
                    raw = data[off : off + size]
                    info["files"][name] = {
                        "size": len(raw),
                        "sha256": hashlib.sha256(raw).hexdigest(),
                    }
                manifest[path] = info
            except Exception as e:  # noqa: BLE001
                manifest[path] = {"error": f"{type(e).__name__}: {e}"}
    with open("/tmp/opencode/bundle_manifest.json", "w") as f:
        json.dump(manifest, f, indent=1)
    print("wrote", len(manifest), "entries")
    for k, v in manifest.items():
        if "error" in v:
            print(" ERR", k.split("/")[-1][:45], v["error"][:60])
        else:
            print(" OK ", k.split("/")[-1][:45], "files:", list(v["files"].keys()))


if __name__ == "__main__":
    main()
