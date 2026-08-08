#!/usr/bin/env python3
"""Fetch the Melon Playground APK (optional IL2CPP fixture, plan §4.5) and extract the
IL2CPP inputs ttmapgen needs: global-metadata.dat + libil2cpp.so.

Not part of the pinned CI corpus: the APK is served by third-party mirrors (apkcombo)
and is not gitignored/committed. Run this once on a dev machine, then point the IL2CPP
ttmapgen test at the extracted root:

    MELONPLAYGROUND_DIR=<extract-dir> ./gradlew :ttmapgen:test --tests "*il2cpp*"

The game is free on Google Play; the APK mirror (apkcombo) is MIT-hosting of the
unmodified APK. See NOTICE.md.
"""

import io
import os
import re
import shutil
import sys
import urllib.request
import zipfile

PACKAGE = "com.TwentySeven.MelonPlayground"
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.getcwd(), "melonplayground")
    print(f"fetching Melon Playground -> {out}")

    page = get(f"https://apkcombo.com/melon-playground/{PACKAGE}/download/apk").decode("utf-8", "replace")
    hrefs = re.findall(r'href="([^"]+)"[^>]*class="[^"]*variant[^"]*"', page)
    if not hrefs:
        raise SystemExit("no download variant found on apkcombo page")

    checkin = get("https://apkcombo.com/checkin").decode("utf-8", "replace").strip()
    url = "https://apkcombo.com" + hrefs[0] + "&" + checkin + f"&package_name={PACKAGE}&lang=en"
    apk = get(url)
    print(f"downloaded APK ({len(apk) / 1e6:.1f} MB)")

    with zipfile.ZipFile(io.BytesIO(apk)) as z:
        names = z.namelist()
        meta = next(n for n in names if n.endswith("assets/bin/Data/Managed/Metadata/global-metadata.dat"))
        so = next(n for n in names if n.endswith("lib/arm64-v8a/libil2cpp.so"))
        os.makedirs(out, exist_ok=True)
        z.extract(meta, out)
        z.extract(so, out)
    print("extracted:")
    print(f"  {out}/{meta}")
    print(f"  {out}/{so}")


if __name__ == "__main__":
    main()
