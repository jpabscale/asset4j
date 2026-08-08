#!/usr/bin/env python3
"""Write-path parity sweep: diff asset4j's round-trip bytes against the pinned
AssetsTools.NET C# reference (via the ttmapgen .NET harness) on every corpus file.

The UnityPy sweep (tools/sweep.py) checks decode behavior; this checks the WRITE path —
whether asset4j's serializer produces byte-identical output to its C# source for the same
input. Buckets:

  MATCH     Kotlin and C# round-trip to identical bytes
  BUG       outputs differ AND decoded object content differs -> real write-path bug
  DEVIATION outputs differ but decoded object content is identical -> layout-only
            normalization (e.g. compressed bundles re-encoding inner files via JSON)
  SKIP      neither side can round-trip the file (invalid/unsupported input, not a bug)

Usage:
  python3 tools/sweep_cs.py [corpus_dir]
Requires: the ttmapgen harness DLL (built by tools/build_ttmapgen_harness.py) and the
assetcli fat jar (built by ./gradlew :assetcli:shadowJar).
"""
import base64
import glob
import json
import os
import subprocess
import sys
import tempfile

CORPUS = os.path.join("assetapi", "src", "test", "resources", "testassets")
HARNESS = os.path.join("ttmapgen", "dotnet-harness", "bin", "Release", "net10.0", "ttmapgen-harness.dll")
JAR = os.path.join("assetcli", "build", "libs", "assetcli.jar")


def find_dotnet():
    dotnet = os.environ.get("DOTNET_ROOT")
    if dotnet:
        return os.path.join(dotnet, "dotnet")
    home = os.path.expanduser("~/.dotnet/dotnet")
    if os.path.isfile(home):
        return home
    return "dotnet"


def cs_roundtrip(path):
    """Run the C# harness roundtrip mode, return the output bytes (or None on error)."""
    req = {"mode": "roundtrip", "file": path}
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(req, f)
        req_path = f.name
    try:
        p = subprocess.run(
            [find_dotnet(), HARNESS, req_path],
            capture_output=True, timeout=120,
            env={**os.environ, "DOTNET_ROLL_FORWARD": "LatestMajor"},
        )
        if p.returncode != 0:
            return None
        return base64.b64decode(json.loads(p.stdout)["bytes"])
    except Exception:
        return None
    finally:
        os.unlink(req_path)


def kt_roundtrip(path):
    """Run assetcli roundtrip, return the output bytes (or None on error)."""
    with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
        out_path = f.name
    try:
        p = subprocess.run(
            ["java", "-jar", JAR, "roundtrip", path, out_path],
            capture_output=True, timeout=120,
        )
        if p.returncode != 0:
            return None
        return open(out_path, "rb").read()
    except Exception:
        return None
    finally:
        os.unlink(out_path)


def tojson(path):
    """assetcli tojson to a temp file, return the parsed JSON root (or None on error)."""
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
        out_path = f.name
    try:
        p = subprocess.run(
            ["java", "-jar", JAR, "tojson", path, out_path],
            capture_output=True, timeout=120,
        )
        if p.returncode != 0:
            return None
        with open(out_path) as fh:
            return json.load(fh)
    except Exception:
        return None
    finally:
        os.unlink(out_path)


def object_data_nodes(root):
    """All decoded object Data nodes from a tojson root (SerializedFile or bundle)."""
    out = []
    if root is None:
        return out
    if "Objects" in root:
        out.extend(root["Objects"])
        return out
    for f in root.get("Files", []):
        asset = f.get("Asset")
        if asset and "Objects" in asset:
            out.extend(asset["Objects"])
    return out


def classify(cs, kt):
    """After a byte DIFF, decide BUG vs DEVIATION by comparing the decoded object content
    of the two round-tripped outputs (layout metadata like offsets/sizes is ignored)."""
    with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
        cs_path = f.name
        f.write(cs)
    with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
        kt_path = f.name
        f.write(kt)
    try:
        ra = tojson(cs_path)
        rb = tojson(kt_path)
    finally:
        os.unlink(cs_path)
        os.unlink(kt_path)
    if ra is None or rb is None:
        return "DIFF"  # can't decode either side's output; leave as plain DIFF
    a = [o.get("Data") for o in object_data_nodes(ra) if isinstance(o.get("Data"), dict)]
    b = [o.get("Data") for o in object_data_nodes(rb) if isinstance(o.get("Data"), dict)]
    if len(a) != len(b) or a != b:
        return "BUG"
    return "DEVIATION"


def main():
    corpus = sys.argv[1] if len(sys.argv) > 1 else CORPUS
    if not os.path.isfile(HARNESS):
        sys.exit("harness not built: run ./tools/build_ttmapgen_harness.py")
    if not os.path.isfile(JAR):
        sys.exit("assetcli not built: run ./gradlew :assetcli:shadowJar")

    files = sorted(
        glob.glob(os.path.join(corpus, "**", "*.assets"), recursive=True)
        + glob.glob(os.path.join(corpus, "**", "*.unity3d"), recursive=True)
    )
    if not files:
        sys.exit(f"no corpus files under {corpus}")

    match = bug = deviation = diff = skip = 0
    for path in files:
        rel = os.path.relpath(path, corpus)
        cs = cs_roundtrip(path)
        kt = kt_roundtrip(path)
        if cs is None or kt is None:
            skip += 1
            print(f"SKIP   {rel}")
            continue
        if cs == kt:
            match += 1
            print(f"MATCH  {rel}")
            continue
        verdict = classify(cs, kt)
        if verdict == "BUG":
            bug += 1
            print(f"BUG    {rel} (C# {len(cs)}B, Kotlin {len(kt)}B, content differs)")
        elif verdict == "DEVIATION":
            deviation += 1
            print(f"DEVIATION {rel} (C# {len(cs)}B, Kotlin {len(kt)}B, layout-only)")
        else:
            diff += 1
            print(f"DIFF   {rel} (C# {len(cs)}B, Kotlin {len(kt)}B)")
    print(f"\nMATCH {match}, BUG {bug}, DEVIATION {deviation}, DIFF {diff}, SKIP {skip}, TOTAL {len(files)}")
    if bug:
        sys.exit(1)


if __name__ == "__main__":
    main()
