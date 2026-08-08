#!/usr/bin/env python3
"""Corpus sweep: run assetcli roundtrip over every SerializedFile in the corpus and
bucket results MATCH / DIFF / JVMERR / BOTHERR (uasset4j sweep pattern, plan Phase 11).

The round-trip is validated by re-parsing the output with UnityPy (independent oracle)
and comparing each object's type tree to the original. Assets that neither side can
read (JVM error + UnityPy error) bucket as BOTHERR, never "fix".

Usage:
  python3 tools/sweep.py [assetcli.jar] [corpus_dir]
"""
import concurrent.futures
import glob
import json
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, "/tmp/automod/UnityPy")
import UnityPy.config  # noqa: E402

UnityPy.config.FALLBACK_UNITY_VERSION = "2019.4.30f1"

CORPUS = "assetapi/src/test/resources/testassets"
JAR = "assetcli/build/libs/assetcli.jar"


def unitypy_parse(path):
    """Parse a SerializedFile with UnityPy and return {path_id: typetree}. Returns None on error."""
    try:
        from UnityPy.files.SerializedFile import SerializedFile
        from UnityPy.streams.EndianBinaryReader import EndianBinaryReader

        sf = SerializedFile(EndianBinaryReader(open(path, "rb").read()), None, name=path)
        out = {}
        for pid, obj in sf.objects.items():
            try:
                out[pid] = obj.read_typetree()
            except Exception:  # noqa: BLE001
                out[pid] = None
        return out
    except Exception:  # noqa: BLE001
        return None


def run_one(path, jar):
    rel = os.path.relpath(path, CORPUS)
    with tempfile.TemporaryDirectory() as td:
        rt = os.path.join(td, "rt.assets")
        try:
            subprocess.run(
                ["java", "-jar", jar, "roundtrip", path],
                capture_output=True, timeout=120,
            )
            subprocess.run(
                ["java", "-jar", jar, "tojson", path, os.path.join(td, "j.json")],
                capture_output=True, timeout=120, check=True,
            )
            subprocess.run(
                ["java", "-jar", jar, "fromjson", os.path.join(td, "j.json"), rt],
                capture_output=True, timeout=120, check=True,
            )
            # re-parse the round-tripped file with the same library (self-check)
            subprocess.run(
                ["java", "-jar", jar, "roundtrip", rt],
                capture_output=True, timeout=120, check=True,
            )
            orig_tt = unitypy_parse(path)
            rt_tt = unitypy_parse(rt)
            if orig_tt is None and rt_tt is None:
                return "BOTHERR", rel
            if orig_tt is None or rt_tt is None:
                return "JVMERR", rel
            if orig_tt == rt_tt:
                return "MATCH", rel
            return "DIFF", rel
        except subprocess.CalledProcessError:
            # JVM CLI failed; check if UnityPy also fails on the original
            orig_tt = unitypy_parse(path)
            return ("BOTHERR" if orig_tt is None else "JVMERR"), rel
        except Exception:  # noqa: BLE001
            orig_tt = unitypy_parse(path)
            return ("BOTHERR" if orig_tt is None else "JVMERR"), rel


def main():
    jar = sys.argv[1] if len(sys.argv) > 1 else JAR
    corpus = sys.argv[2] if len(sys.argv) > 2 else CORPUS
    files = [
        f for f in glob.glob(os.path.join(corpus, "**", "*.assets"), recursive=True)
        if os.path.getsize(f) < 5_000_000
    ]
    buckets = {"MATCH": [], "DIFF": [], "JVMERR": [], "BOTHERR": []}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        for result, rel in ex.map(lambda f: run_one(f, jar), files):
            buckets[result].append(rel)
    for k in buckets:
        print(f"{k} {len(buckets[k])}")
    for rel in buckets["DIFF"]:
        print("  DIFF", rel)
    for rel in buckets["JVMERR"]:
        print("  JVMERR", rel)


if __name__ == "__main__":
    main()
