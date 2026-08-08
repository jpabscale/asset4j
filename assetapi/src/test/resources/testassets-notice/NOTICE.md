# Test corpus attribution

The files under `testassets/` are **downloaded at test time** by `tools/fetch_corpus.py`
(CI + local) from the pinned upstream SHAs in `gradle.properties` (uasset4j CI pattern). They are
**not committed** to this repository; this NOTICE is the single committed attribution record.

| Source | Contents | License |
|---|---|---|
| [Unity-Technologies/UnityDataTools](https://github.com/Unity-Technologies/UnityDataTools) | multi-version AssetBundle fixtures incl. embedded (`v22`/`v23_Inline`), external (`v23_extracted`), and no-type-tree variants | Unity Companion License |
| [Unity-Technologies/AddressableAssetsWebinar](https://github.com/Unity-Technologies/AddressableAssetsWebinar) | "Unity Royale" built-game player `.assets` + Addressables `.bundle` files (Unity 2019.1.4f1) | MIT |
| [K0lb3/UnityPy](https://github.com/K0lb3/UnityPy) test samples | real UnityFS bundles (Unity 5.6.7f1) + golden Mesh export, via Git LFS | MIT |

**Optional IL2CPP fixture (plan §4.5):** Melon Playground (free on Google Play) is **not**
part of the pinned CI corpus. `tools/fetch_melonplayground.py` downloads the unmodified APK
from apkcombo and extracts `global-metadata.dat` + `libil2cpp.so` to a gitignored dir for the
`ttmapgen il2cpp` test (`MELONPLAYGROUND_DIR`). Attribution: game by TwentySeven; APK mirrored
by apkcombo (MIT hosting of the unmodified release).
