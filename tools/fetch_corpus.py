#!/usr/bin/env python3
"""Fetch the asset4j test corpus from the pinned upstream repos into a gitignored dir.

Mirrors uasset4j's CI pattern: the corpus is never committed to the repo. CI (and developers)
run this to materialize `assetapi/src/test/resources/testassets/` from pinned SHAs in
`gradle.properties`. Sources and licenses are recorded in NOTICE.md (kept in git).

Sources (all pinned by SHA):
  - UnityDataTools fixtures (Unity Companion License)  -> embedded/external type-tree variants
  - AddressableAssetsWebinar "Unity Royale" (MIT)       -> real shipped-game bundles + player .assets
  - UnityPy test samples (MIT, Git LFS)                 -> seed bundles
"""

import io
import os
import shutil
import sys
import tarfile
import urllib.request

try:
    import zstandard  # noqa: F401  (optional; not used yet)
except ImportError:
    pass


def prop(name):
    with open("gradle.properties") as f:
        for line in f:
            line = line.strip()
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} missing in gradle.properties")


def download(url, dst):
    print(f"downloading {url}")
    urllib.request.urlretrieve(url, dst)


def extract_subpath(tarball, subpath, dst):
    """Extract a single directory subtree from a GitHub codeload tarball.

    The tarball root is ``<repo>-<sha>/``; we locate ``<subpath>`` under it, then
    extract that subtree into ``dst`` (path relative to ``<repo>-<sha>/``).
    """
    with tarfile.open(tarball, "r:gz") as tf:
        marker = "/" + subpath + "/"
        base = None
        for name in tf.getnames():
            if marker in name:
                base = name[: name.index(marker) + 1]
                break
        if base is None:
            raise SystemExit(f"subpath {subpath} not found in tarball")
        for m in tf.getmembers():
            if not m.isfile():
                continue
            if m.name.startswith(base + subpath + "/"):
                m.name = os.path.relpath(m.name, base)
                tf.extract(m, dst)


def main():
    testassets = "assetapi/src/test/resources/testassets"
    shutil.rmtree(testassets, ignore_errors=True)
    os.makedirs(testassets, exist_ok=True)
    # NOTICE.md is committed; copy it from the repo's own resources so it survives the wipe.
    notice_src = os.path.join(os.path.dirname(__file__), "..", "assetapi", "src", "test", "resources",
                              "testassets-notice", "NOTICE.md")
    notice_dst = os.path.join(testassets, "NOTICE.md")
    if os.path.exists(notice_src):
        shutil.copy2(notice_src, notice_dst)

    pins = {
        "unitydatatools": prop("asset4j.unitydatatools.sha"),
        "addressableassetswebinar": prop("asset4j.addressableassetswebinar.sha"),
        "unitypy": prop("asset4j.unitypy.sha"),
    }

    # 1) UnityDataTools fixtures
    tarball = "unitydatatools.tar.gz"
    download(
        f"https://codeload.github.com/Unity-Technologies/UnityDataTools/tar.gz/{pins['unitydatatools']}",
        tarball,
    )
    extract_subpath(tarball, "TestCommon/Data", testassets)
    os.remove(tarball)

    # 2) AddressableAssetsWebinar (Unity Royale) — player .assets + Addressables bundles
    tarball = "addressableassetswebinar.tar.gz"
    download(
        f"https://codeload.github.com/Unity-Technologies/AddressableAssetsWebinar/tar.gz/{pins['addressableassetswebinar']}",
        tarball,
    )
    extract_subpath(tarball, "UnityRoyale", testassets)
    os.remove(tarball)

    # 3) UnityPy test samples (Git LFS) — fetched via the LFS media endpoint by OID
    lfs_urls = [
        ("atlas_test", "61ee5afb5f5c64f0a4f65f039d09b2509198363e713423289c895067ac215177"),
        ("banner_1", "6bd0c3bc36c97e5f1cd2de71bb58477b6c32ac2c254a5705884306c56b94417f"),
        ("char_118_yuki.ab", "6198cb71c7a820256208332bbd375d21b18ed1a36a12ad04be1eef2111b014a1"),
        ("xinzexi_2_n_tex", "a9f6e6bba7110cce647ef752d66655d13ce5850b013a60d80d4e8b08953e02ea"),
        ("xinzexi_2_n_tex_mesh", "8bb2082b9586b3f8f3bad2d22d33346ec0492053f2a76323aa8b4b4047368ca8"),
    ]
    outdir = os.path.join(testassets, "unitypy")
    os.makedirs(outdir, exist_ok=True)
    for name, oid in lfs_urls:
        url = f"https://media.githubusercontent.com/media/K0lb3/UnityPy/{pins['unitypy']}/tests/samples/{name}"
        download(url, os.path.join(outdir, name))

    print("corpus ->", testassets)


if __name__ == "__main__":
    main()
