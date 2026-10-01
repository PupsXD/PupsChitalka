#!/usr/bin/env python3
"""Makes onnxruntime-android's Java API run on the ONNX Runtime that sherpa-onnx already ships.

Both AARs carry lib/<abi>/libonnxruntime.so, and the two copies are not interchangeable: ONNX Runtime
labels its exported symbols with its version, and sherpa-onnx 1.13.8 links against VERS_1.28.2 while
onnxruntime-android 1.28.0's JNI wants VERS_1.28.0. On the phone the loser of the merge fails with
  dlopen failed: cannot locate symbol "OrtGetApiBase" referenced by "libonnxruntime4j_jni.so"
Both are the same C API (version 28), and the JNI library uses only OrtGetApiBase and two
OrtSessionOptionsAppendExecutionProvider_* functions, which sherpa-onnx's copy exports. So this script
rewrites the version the JNI library asks for (the name in .dynstr and its ELF hash in .gnu.version_r)
and drops the AAR's own libonnxruntime.so.

  python tools/patch-onnxruntime-aar.py <onnxruntime-android-1.28.0.aar> app/libs/onnxruntime-android-1.28.0-sherpa.aar
"""
import struct
import sys
import zipfile

OLD, NEW = b"VERS_1.28.0", b"VERS_1.28.2"


def elf_hash(name: bytes) -> int:
    h = 0
    for c in name:
        h = ((h << 4) + c) & 0xFFFFFFFF
        g = h & 0xF0000000
        if g:
            h ^= g >> 24
        h &= ~g & 0xFFFFFFFF
    return h


def sections(data: bytes):
    """Offset, size and link of every section of a little-endian ELF64 file, by name; and the raw headers."""
    shoff, = struct.unpack_from("<Q", data, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    raw = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize) for i in range(shnum)]
    names_at = raw[shstrndx][4]
    result = {}
    for sh in raw:
        name = data[names_at + sh[0]:data.index(b"\0", names_at + sh[0])].decode()
        result[name] = {"offset": sh[4], "size": sh[5], "link": sh[6]}
    return result, raw


def patch_jni(lib: bytes) -> bytes:
    if lib[:4] != b"\x7fELF" or lib[4] != 2 or lib[5] != 1:
        raise SystemExit("expected a little-endian 64-bit ELF")
    data = bytearray(lib)
    secs, raw = sections(lib)
    need = secs[".gnu.version_r"]
    dynstr = raw[need["link"]][4]
    patched = 0
    at = need["offset"]
    while True:
        _, cnt, _, aux, nxt = struct.unpack_from("<HHIII", data, at)
        a = at + aux
        for _ in range(cnt):
            vna_hash, flags, other, name, a_next = struct.unpack_from("<IHHII", data, a)
            start = dynstr + name
            if data[start:start + len(OLD) + 1] == OLD + b"\0":
                data[start:start + len(NEW)] = NEW
                struct.pack_into("<I", data, a, elf_hash(NEW))
                patched += 1
            a += a_next
        if not nxt:
            break
        at += nxt
    if patched != 1:
        raise SystemExit(f"expected one {OLD.decode()} requirement, patched {patched}")
    return bytes(data)


def main(src: str, dst: str) -> None:
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            name = item.filename
            if name.endswith("/libonnxruntime.so"):
                continue  # sherpa-onnx brings this one
            data = zin.read(name)
            if name.endswith("/libonnxruntime4j_jni.so"):
                if name.startswith("jni/arm64-v8a/") or name.startswith("jni/x86_64/"):
                    data = patch_jni(data)
                else:
                    continue  # the app ships arm64-v8a only; 32-bit ABIs are not patched
            zout.writestr(item, data)
    print(f"wrote {dst}")


if __name__ == "__main__":
    main(*sys.argv[1:3])
