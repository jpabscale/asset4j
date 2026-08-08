#!/usr/bin/env python3
"""Builds the ttmapgen C# harness + the pinned AssetsTools.NET reference DLLs it links
against. Single source of truth for the pinned commit: gradle.properties
(asset4j.assetstools.commit). Used by BOTH local dev (./tools/build_ttmapgen_harness.py)
and CI (.github/workflows/ci.yml) so the two never drift.

The reference checkout is extracted into .tmp/automod (gitignored scratch) and each
project is built as a separate subprocess. Extraction is verified with os.stat before
building (tar can return before the filesystem has fully caught up on some hosts).

Outputs:
  ttmapgen/dotnet-harness/bin/Release/net10.0/ttmapgen-harness.dll
Requires: dotnet (10.0 SDK), git.
"""
import os
import shutil
import stat
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ORACLE_DIR = os.path.join(REPO_ROOT, ".tmp", "automod")
AT_DIR = os.path.join(ORACLE_DIR, "AssetsTools.NET")
PROJECTS = [
    "AssetTools.NET/AssetsTools.NET.csproj",
    "AssetsTools.NET.MonoCecil/AssetsTools.NET.MonoCecil.csproj",
    "AssetsTools.NET.Cpp2IL/AssetsTools.NET.Cpp2IL.csproj",
]
DLLS = [
    "AssetTools.NET/bin/Debug/netstandard2.0/AssetsTools.NET.dll",
    "AssetsTools.NET.MonoCecil/bin/Debug/netstandard2.0/AssetsTools.NET.MonoCecil.dll",
    "AssetsTools.NET.Cpp2IL/bin/Debug/netstandard2.0/AssetsTools.NET.Cpp2IL.dll",
]


def prop(name):
    with open(os.path.join(REPO_ROOT, "gradle.properties")) as f:
        for line in f:
            line = line.strip()
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} missing in gradle.properties")


def find_dotnet():
    dotnet = shutil.which("dotnet")
    if dotnet:
        return dotnet
    home = os.path.expanduser("~/.dotnet/dotnet")
    if os.path.exists(home):
        return home
    raise SystemExit("error: dotnet not found (install the .NET 10 SDK)")


def wait_visible(path, timeout=30.0):
    """Poll stat(path) until it succeeds; some hosts don't reflect freshly-extracted
    files to stat immediately."""
    import time
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            os.stat(path)
            return True
        except OSError:
            time.sleep(0.2)
    return False


def main():
    dotnet = find_dotnet()
    print(f">> dotnet: {dotnet}")
    commit = prop("asset4j.assetstools.commit")
    print(f">> building ttmapgen harness from AssetsTools.NET {commit}")

    os.makedirs(ORACLE_DIR, exist_ok=True)
    shutil.rmtree(AT_DIR, ignore_errors=True)
    # clone the pinned commit (shallow) into AT_DIR
    print(f">> cloning {commit}")
    subprocess.run(
        ["git", "clone", "--quiet", "--filter=blob:none", "--no-checkout",
         "https://github.com/nesrak1/AssetsTools.NET.git", AT_DIR],
        check=True,
    )
    subprocess.run(["git", "-C", AT_DIR, "checkout", "--quiet", commit], check=True)
    # flush the filesystem so MSBuild's reference resolution (and stat) reliably see
    # the freshly-checked-out files
    subprocess.run(["sync"], check=True)

    marker = os.path.join(AT_DIR, "AssetTools.NET", "AssetsTools.NET.csproj")
    if not wait_visible(marker):
        raise SystemExit(f"error: cloned checkout not visible at {marker}")
    print(f">> checked out to {AT_DIR}")

    # build each reference project, then the harness — separate subprocesses, verified
    for proj in PROJECTS:
        print(f">> dotnet build {proj}")
        if not wait_visible(os.path.join(AT_DIR, proj)):
            raise SystemExit(f"error: project not visible: {proj}")
        subprocess.run([dotnet, "build", os.path.join(AT_DIR, proj), "-c", "Debug", "--nologo", "-v", "minimal"],
                       check=True, cwd=AT_DIR)
    for dll in DLLS:
        if not os.path.exists(os.path.join(AT_DIR, dll)):
            raise SystemExit(f"error: missing {dll}")
        print(f"   {os.path.basename(dll)} OK")

    # copy the built reference DLLs into the harness's lib/ (gitignored) — a stable,
    # non-ephemeral location MSBuild reliably reads (fresh-extracted files can be
    # invisible to MSBuild's reference resolution on some hosts)
    lib_dir = os.path.join(REPO_ROOT, "ttmapgen", "dotnet-harness", "lib")
    os.makedirs(lib_dir, exist_ok=True)
    for dll in DLLS:
        shutil.copy2(os.path.join(AT_DIR, dll), os.path.join(lib_dir, os.path.basename(dll)))
    print(">> reference DLLs copied to ttmapgen/dotnet-harness/lib")

    print(">> dotnet build ttmapgen-harness")
    subprocess.run([dotnet, "build", "ttmapgen/dotnet-harness/ttmapgen-harness.csproj",
                    "-c", "Release", "--nologo", "-v", "minimal"], check=True, cwd=REPO_ROOT)
    if not os.path.exists(os.path.join(REPO_ROOT, "ttmapgen/dotnet-harness/bin/Release/net10.0/ttmapgen-harness.dll")):
        raise SystemExit("error: harness build produced no ttmapgen-harness.dll")
    print("harness OK")
    print(">> ttmapgen harness ready")


if __name__ == "__main__":
    main()
