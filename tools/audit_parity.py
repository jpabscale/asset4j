#!/usr/bin/env python3
"""Member-name parity audit: check every ported Kotlin class has the public members of its
C# source (PascalCase preserved), per docs/port-tracker.md and docs/mapping.md.

This is a lightweight audit — it greps the C# and Kotlin for the public method/property
names of each tracked file and reports missing ones. It does NOT check statement order
(that is a review-time check per AGENTS.md).

Usage: python3 tools/audit_parity.py
"""
import os
import re
import sys

CSPATH = "/tmp/automod/AssetsTools.NET/AssetTools.NET"
KTPATH = "assetapi/src/main/kotlin/com/github/jpabscale/asset4j"

# C# relative path -> Kotlin relative path (from docs/port-tracker.md)
TRACKED = [
    ("Standard/IO/AssetsFileReader.cs", "io/AssetsFileReader.kt"),
    ("Standard/IO/AssetsFileWriter.cs", "io/AssetsFileWriter.kt"),
    ("Standard/AssetsFileFormat/Hash128.cs", "io/Hash128.kt"),
    ("Standard/AssetsFileFormat/GUID128.cs", "io/GUID128.kt"),
    ("Standard/AssetsFileFormat/TypeTreeNode.cs", "typetree/TypeTreeNode.kt"),
    ("Standard/AssetsFileFormat/TypeTreeType.cs", "typetree/TypeTreeType.kt"),
    ("Standard/AssetsFileFormat/TypeTreeBlob.cs", "typetree/TypeTreeBlob.kt"),
    ("Standard/AssetsFileFormat/AssetsFileHeader.cs", "serializedfile/AssetsFileHeader.kt"),
    ("Standard/AssetsFileFormat/AssetFileInfo.cs", "serializedfile/AssetFileInfo.kt"),
    ("Standard/AssetsFileFormat/AssetsFileExternal.cs", "serializedfile/AssetsFileExternal.kt"),
    ("Standard/AssetsFileFormat/AssetPPtr.cs", "serializedfile/AssetPPtr.kt"),
    ("Standard/AssetsFileFormat/AssetsFileMetadata.cs", "serializedfile/AssetsFileMetadata.kt"),
    ("Standard/AssetsFileFormat/AssetsFile.cs", "serializedfile/AssetsFile.kt"),
    ("Standard/AssetsFileFormat/AssetsTypeReference.cs", "serializedfile/AssetTypeReference.kt"),
    ("Standard/AssetsBundleFileFormat/AssetBundleFSHeader.cs", "bundle/AssetBundleFSHeader.kt"),
    ("Standard/AssetsBundleFileFormat/AssetBundleHeader.cs", "bundle/AssetBundleHeader.kt"),
    ("Standard/AssetsBundleFileFormat/AssetBundleBlockInfo.cs", "bundle/AssetBundleBlockInfo.kt"),
    ("Standard/AssetsBundleFileFormat/AssetBundleBlockAndDirInfo.cs", "bundle/AssetBundleBlockAndDirInfo.kt"),
    ("Standard/AssetsBundleFileFormat/AssetBundleDirectoryInfo.cs", "bundle/AssetBundleDirectoryInfo.kt"),
    ("Standard/AssetsBundleFileFormat/AssetBundleFile.cs", "bundle/AssetBundleFile.kt"),
    ("Standard/AssetTypeClass/AssetTypeValue.cs", "value/AssetTypeValue.kt"),
    ("Standard/AssetTypeClass/AssetTypeValueField.cs", "value/AssetTypeValueField.kt"),
    ("Standard/AssetTypeClass/AssetTypeTemplateField.cs", "value/AssetTypeTemplateField.kt"),
    ("Standard/AssetTypeClass/RefTypeManager.cs", "value/RefTypeManager.kt"),
]


def cs_members(path):
    src = open(path).read()
    names = set()
    for m in re.finditer(r"public\s+(?:static\s+)?(?:override\s+)?(?:\w+[\w<>, ]*\s+)?(\w+)\s*\(", src):
        names.add(m.group(1).lower())
    for m in re.finditer(r"public\s+(?:\w+\s+)?(\w+)\s*\{", src):
        names.add(m.group(1).lower())
    return names


def kt_members(path):
    src = open(path).read()
    names = set()
    for m in re.finditer(r"fun\s+(\w+)\s*\(", src):
        names.add(m.group(1).lower())
    for m in re.finditer(r"val\s+(\w+)", src):
        names.add(m.group(1).lower())
    for m in re.finditer(r"var\s+(\w+)", src):
        names.add(m.group(1).lower())
    # enum entries and class name do not need to mirror; also drop readX/writeX
    return names


def check_parity_markers():
    """Validate `//@parity:on EXC-XXX` / `//@parity:off EXC-XXX` markers across the ported
    tree: balanced pairs, not nested, ids reference docs/parity-exceptions.json."""
    import json
    reg_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            "docs", "parity-exceptions.json")
    try:
        with open(reg_path) as f:
            ids = {e["id"] for e in json.load(f)["exceptions"]}
    except Exception:
        print("parity-exceptions.json: unreadable")
        return 1

    on_re = re.compile(r"//@parity:on\s+([\w-]+)")
    off_re = re.compile(r"//@parity:off\s+([\w-]+)")
    bad = 0
    for root, _dirs, files in os.walk(KTPATH):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(root, name)
            src = open(path).read()
            stack = []
            for line_no, line in enumerate(src.splitlines(), 1):
                for m in on_re.finditer(line):
                    if stack:
                        print(f"{os.path.relpath(path, KTPATH)}:{line_no}: nested parity marker "
                              f"{m.group(1)} inside {stack[-1]}")
                        bad += 1
                    stack.append(m.group(1))
                for m in off_re.finditer(line):
                    if not stack:
                        print(f"{os.path.relpath(path, KTPATH)}:{line_no}: unbalanced 'off' for {m.group(1)}")
                        bad += 1
                        continue
                    top = stack.pop()
                    if top != m.group(1):
                        print(f"{os.path.relpath(path, KTPATH)}:{line_no}: 'off' {m.group(1)} mismatches open {top}")
                        bad += 1
            for leftover in stack:
                print(f"{os.path.relpath(path, KTPATH)}: unbalanced 'on' {leftover}")
                bad += 1
            for m in on_re.finditer(src):
                if m.group(1) not in ids:
                    print(f"{os.path.relpath(path, KTPATH)}: parity marker references unknown id {m.group(1)}")
                    bad += 1
    return bad


def main():
    bad = 0
    # C# members that are deliberately not mirrored: constructors, LINQ helpers, seams,
    # and documented v1 deferrals (class-database / mono-template paths).
    SKIP = {"read", "write", "close", "equals", "gethashcode", "tostring", "clone",
            "pack", "unpack", "copyto", "gettype", "getenumerator", "getcompressiontype",
            "assetsfilereader", "assetsfilewriter", "hash128", "guid128",
            "typetreenode", "typetreetype", "typetreeblob", "assetsfileheader",
            "assetfileinfo", "assetsfileexternal", "assetpptr", "assetsfilemetadata",
            "assetsfile", "assettypereference", "assetbundlefsheader",
            "assetbundleheader", "assetbundleblockinfo", "assetbundleblockanddirinfo",
            "assetbundledirectoryinfo", "assetbundlecompressiontype", "assetbundlefile",
            "assettypevalue", "assettypetemplatefield", "reftypemanager",
            # documented deferrals / convenience helpers
            "create", "setnewdata", "setremoved", "fromfield", "setfilepathfromfile",
            "findreftypebyindex", "getassetsoftype", "generatequicklookup",
            "getassetinfo", "getscriptindex", "fromclassdatabase", "withmonotemplategenerator",
            "assettypereference"}
    for cs_rel, kt_rel in TRACKED:
        cs_path = os.path.join(CSPATH, cs_rel)
        kt_path = os.path.join(KTPATH, kt_rel)
        if not os.path.exists(cs_path):
            print("MISSING C#", cs_rel)
            bad += 1
            continue
        if not os.path.exists(kt_path):
            print("MISSING KT", kt_rel)
            bad += 1
            continue
        cs = cs_members(cs_path)
        kt = kt_members(kt_path)
        missing = {m for m in cs if m not in kt and m not in SKIP and not m.startswith(("isassetsfile", "createnew"))}
        if missing:
            print(f"{kt_rel}: missing C# members {sorted(missing)}")
            bad += 1
    bad += check_parity_markers()
    print("PARITY AUDIT: " + ("GREEN" if bad == 0 else f"{bad} files with missing members / marker issues"))


if __name__ == "__main__":
    main()
