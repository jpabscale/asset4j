# asset4j

[![CI](https://github.com/jpabscale/asset4j/actions/workflows/ci.yml/badge.svg)](https://github.com/jpabscale/asset4j/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/jpabscale/asset4j.svg)](https://jitpack.io/#jpabscale/asset4j)

A **Kotlin/JVM port of [AssetsTools.NET](https://github.com/nesrak1/AssetsTools.NET)** — a Unity
`SerializedFile`/`AssetBundle` parser/serializer, in the same shape and spirit as
[uasset4j](https://github.com/jpabscale/uasset4j) is to UAssetAPI. It is a **statement-parallel**
port: the Kotlin source mirrors the C# source file-for-file, statement-for-statement, so upstream
AssetsTools.NET changes stay cheap to adopt. Like uasset4j, it is a headless **binary ⇄ JSON**
round-tripper, plus a CLI.

> **Ported AssetsTools.NET commit: `9aa8c6ead19b7667ed6d4d9c0c9fd48967433e1a`**
>
> All ported code comes from this exact upstream tree (2026-08-02, merge PR #165). The
> differential oracle — UnityPy, used by `tools/sweep.py` — and the .NET `ttmapgen` harness must
> track this commit. When a newer upstream tip is adopted, bump this sha everywhere (see
> [Keeping up with AssetsTools.NET](#keeping-up-with-assetstoolsnet)).

## What it is

- **`assetapi`** — the ported library (Kotlin/JVM, `com.github.jpabscale.asset4j.*`): binary
  readers/writers, the UnityFS bundle container, SerializedFile header/metadata/objects, type
  trees, the value model (`AssetTypeValueField`/`AssetTypeTemplateField`), the `ttmap`
  external-schema artifact, and the JSON round-trip (`AssetService`).
- **`assetcli`** — a cross-platform JVM CLI (`assetcli.jar`) with `tojson`/`fromjson`/
  `roundtrip`/`diff`/`check`.
- **`ttmapgen`** — the **ttmap generator** (tthm/DLL/IL2CPP → ttmap). The `tthm` path is pure
  Kotlin; the `dll`/`il2cpp` paths drive MonoCecil/Cpp2IL through a small .NET subprocess bridge
  (`ttmapgen/dotnet-harness`, built from the same pinned AssetsTools.NET commit).
- **Ported tests** — JUnit 5 tests against a gitignored, CI-fetched corpus (UnityDataTools
  fixtures, AddressableAssetsWebinar "Unity Royale", UnityPy samples), plus a corpus sweep
  (`tools/sweep.py`) that validates round-trips against UnityPy as an independent oracle.

### Scope

In scope: `AssetsTools.NET`'s SerializedFile/bundle/type-tree/value model (binary read **and**
write, byte-identical), the CLI, and the ttmap generator. Out of scope (v1):

- **Text-format codecs (YAML/JSON) and the patch language** — those live in automod, exactly like
  uasset4j round-trips only `.uasset` while automod owns raw `.json`. asset4j round-trips binary
  SerializedFiles only.
- **Direct `tthm` reading at runtime** — external type trees are consumed exclusively via ttmap.
- **Class database / `AssetsManager` / `NewReplacer` / texture decoding** — the class-database
  fallback and the higher-level manager layer are deferred.
- **MonoCecil / Cpp2IL JVM ports** — `ttmapgen` drives them via a .NET subprocess bridge instead
  (once-per-game cost), keeping the library itself dependency-light.

## Why

A JVM port gives one library that runs on every platform and can be loaded in-process by automod's
Unity patch pipeline (like uasset4j for UE), avoiding per-asset subprocess round-trips. The JSON
shape is a patch surface: values decode to order-preserving Jackson trees, automod patches the
tree, and asset4j re-encodes it byte-identically. For huge files, a **surgical patch** mode
(`patchObjects`/`patchObjectsJson`) decodes only the target objects and byte-copies every other —
e.g. a 452 MB `resources.assets` re-encodes in seconds instead of minutes.

## ttmap

Unity's external type trees (format ≥ 23, `TypeBlobIsDefinition == false`) are resolved from a
**ttmap** — a **gzip-wrapped JSON schema artifact** that is **asset4j's own format** (not a Unity
or AssetsTools.NET format; it plays the role UE's USMap plays for uasset4j, but the schema is
bespoke to asset4j). A ttmap is keyed by type identity and carries a `.ttmap` extension. It is
passed to both `toJsonNode` and `fromJsonNode` via the `ttmapName` parameter (mirroring
uasset4j's `mappingsName`). Runtime prefers the `scriptIds` key (Hash128 string) and falls back
to `script` by `assembly:namespace.classname`.

### ttmapgen (usage)

`ttmapgen` is the standalone ttmap generator (fat jar `ttmapgen.jar`, or the application
distribution with a launcher). It produces a ttmap from one of three sources:

```
ttmapgen <command> [args]
  tthm   <typetreedata.bundle> <serializedfile> <output.ttmap> [gameVersion]
  dll    <managedDir> <output.ttmap> <unityVersion> <gameVersion> [harnessDll]
  il2cpp <gameAssembly> <globalMetadata> <output.ttmap> <unityVersion> <gameVersion> [harnessDll]
```

- **`tthm`** — extracts external type-tree blobs from the game's `typetreedata` bundle (pure
  Kotlin, no harness).
- **`dll`** — synthesizes type trees from a managed `Managed/` directory via MonoCecil; with an
  empty class list it **auto-enumerates** every MonoBehaviour/ScriptableObject subclass (walking
  base chains) plus every `[Serializable]`/enum class reachable transitively from their fields.
- **`il2cpp`** — reads `GameAssembly.dll` + `global-metadata.dat` via Cpp2IL, auto-enumerating
  MonoBehaviour/ScriptableObject classes.

The `dll`/`il2cpp` commands drive MonoCecil/Cpp2IL through a small .NET subprocess bridge
(`ttmapgen/dotnet-harness`). Build the harness first:

```
python3 tools/build_ttmapgen_harness.py   # builds the pinned AssetsTools.NET reference + harness
java -jar ttmapgen.jar dll <Managed> <out.ttmap> <unityVersion>
```

The `<unityVersion>` is the game's exact Unity build (e.g. `2020.3.22f1`, from
`globalgamemanagers.assets` `m_EditorVersion`) and `<gameVersion>` is the game's own
build/version (e.g. the Steam buildid) — both are recorded in the ttmap.

Example: `java -jar ttmapgen.jar dll "game/WarmSnow_Data/Managed" warm_snow.ttmap 2020.3.22f1 17101361`

## Usage

```kotlin
val json: String = AssetService.toJson(Path.of("sharedassets0.assets"), ttmapName = null)
val bytes: ByteArray = AssetService.fromJson(json, ttmapName = null)
// or patch in-memory:
val node: ObjectNode = AssetService.toJsonNode(Path.of("bundle.unity3d"))
// ... automod patches node ...
val rebuilt: ByteArray = AssetService.fromJsonNode(node)
AssetService.write(rebuilt, Path.of("bundle.out.unity3d"))
```

Decode entry points (`toJsonNode`/`toJsonNodeBytes`/`decodeMatchingObjectsByScriptName`/the
`patchObjects*ByScriptName` variants) accept an optional `externalBase: Path?`. When the bundle
being decoded is a copy staged elsewhere (e.g. automod's `.temp`), `externalBase` points at the
**pristine game file** so sibling externals (MonoScript files like `sharedassets0.assets`) still
resolve from the original directory instead of the copy's — without it, those decodes would miss
the external type trees. It defaults to the bundle's own directory.

CLI:

```
java -jar assetcli.jar tojson <source> <destination> [ttmap]
java -jar assetcli.jar fromjson <source> <destination> [ttmap]
java -jar assetcli.jar roundtrip <source> [destination] [ttmap]
java -jar assetcli.jar diff <sourceA> <sourceB>
java -jar assetcli.jar check <source> [ttmap]
```

## Building locally

Requirements:

- **JDK 25** for compilation. The build uses a JDK 25 toolchain; if it isn't installed, the
  [foojay resolver](https://github.com/gradle/foojay-resolver-convention) auto-downloads it on
  first build. The produced bytecode targets **JVM 21**, so the jars run on any JVM 21+.
- **`curl` or `wget`** on first build only: the Gradle wrapper jar is not committed; `gradlew`
  fetches it from the pinned Gradle 9.6.1 release and verifies its SHA-256 (from
  `gradle.properties`).
- **.NET 10 SDK** (`~/.dotnet/dotnet`) to build the `ttmapgen` C# harness (only needed for the
  `dll`/`il2cpp` ttmap generators; `tthm` is pure Kotlin).
- Network access to Maven Central (dependencies), `services.gradle.org` (Gradle distribution), and
  GitHub (pinned corpus + AssetsTools.NET checkout).

Build, test, and produce the CLI fat jar:

```
./tools/fetch_corpus.py                 # materialize the gitignored test corpus (pinned SHAs)
./tools/build_ttmapgen_harness.py       # build the ttmapgen C# harness + its reference DLLs
./gradlew build                         # compile + run the JUnit suite
./gradlew :assetcli:shadowJar           # build the CLI: assetcli/build/libs/assetcli.jar
```

Run the CLI:

```
java -jar assetcli/build/libs/assetcli.jar
# Usage: assetcli [ tojson <source> <destination> [ttmap]
#                 | fromjson <source> <destination> [ttmap]
#                 | roundtrip <source> [ttmap]
#                 | diff <sourceA> <sourceB>
#                 | check <source> [ttmap] ]
```

Consumed via JitPack: `com.github.jpabscale:asset4j:<tag-or-sha>`.

## Porting discipline

See `AGENTS.md`, [`docs/mapping.md`](docs/mapping.md), and
[`docs/port-tracker.md`](docs/port-tracker.md). The port is **statement-parallel** to the pinned
C# source — every C# statement appears, in order, in the Kotlin (translated only through the
mapping doc). Functional parity is necessary but never sufficient. Approved divergences are
recorded in [`docs/parity-exceptions.json`](docs/parity-exceptions.json) and marked in the ported
code with `//@parity:on EXC-XXX` / `//@parity:off EXC-XXX` (validated by `tools/audit_parity.py`).
`tools/audit_parity.py` checks member-name coverage + parity-marker balance; `tools/sweep.py`
checks functional parity against UnityPy; `tools/sweep_cs.py` checks write-path byte parity
against the C# reference.

## Testing against the oracle

Functional parity is enforced by differential testing against UnityPy (an independent Python
implementation) plus the ported unit suite:

- **Corpus sweep** — `tools/sweep.py` runs asset4j's `tojson` and UnityPy on every corpus bundle
  and byte-compares the decoded JSON trees, in parallel:
  ```
  python3 tools/sweep.py
  ```
  Target state: `MATCH N, DIFF 0, JVMERR 0, BOTHERR 0`. `BOTHERR` would be cases where *both*
  implementations fail identically (oracle limitations, not port bugs).
- **Write-path sweep** — `tools/sweep_cs.py` diffs asset4j's round-trip **bytes** against the
  pinned AssetsTools.NET C# reference (via the `ttmapgen` .NET harness), complementing the
  UnityPy decode oracle with a serializer-fidelity check:
  ```
  python3 tools/sweep_cs.py
  ```
  Target state: `MATCH N, BUG 0`. On a byte mismatch the sweep auto-classifies by decoding
  both round-trip outputs and comparing object content: `BUG` = decoded content differs
  (real write-path bug); `DEVIATION` = content identical, layout-only normalization (e.g.
  compressed bundles re-encode inner files through the JSON patch surface, whereas the C#
  reference byte-copies them); `DIFF` = outputs differ but content can't be compared.
  Requires `./tools/build_ttmapgen_harness.py` and `./gradlew :assetcli:shadowJar`.
- **JUnit 5** — the ported tests run via `./gradlew test`. Game-gated tests (Melon Playground
  IL2CPP, Warm Snow surgical patching) skip when the fixture env vars (`MELONPLAYGROUND_DIR`,
  `WARMSNOW_DIR`) aren't set, so CI stays green offline.
- **CI** — GitHub Actions runs the full suite on every push/PR. The `ttmapgen` harness tests now
  actually execute in CI: the workflow sets up .NET 10, builds the pinned AssetsTools.NET
  reference + harness via the shared `tools/build_ttmapgen_harness.py`, then runs `./gradlew build`.

## Keeping up with AssetsTools.NET

Because the port is statement-parallel, adopting a newer upstream release is mechanical:

1. **Re-pin** — update `asset4j.assetstools.commit` in `gradle.properties` (the single source of
   truth used by the README, `docs/mapping.md`, `docs/port-tracker.md`, the CI, and
   `tools/build_ttmapgen_harness.py`).
2. **Diff upstream** — `git -C <AssetsTools.NET checkout> diff <old-sha>..<new-sha>` and port each
   changed C# file. Each file is a localized, statement-level translation; most diffs are small
   (new properties, version-gated branches, new class types).
3. **Update the mapping** — any new C# construct gets a `docs/mapping.md` entry before it is ported.
4. **Regenerate the oracle** — rebuild the reference DLLs + harness
   (`./tools/build_ttmapgen_harness.py`) from the new pin and re-run `tools/sweep.py` until
   `DIFF 0`.
5. **Update the tests** — fold in any new upstream test cases into the ported JUnit suite.

The pinned checkout lives at `/tmp/automod/AssetsTools.NET` (read-only reference), fetched at
build time by `tools/build_ttmapgen_harness.py`.

## Publishing

- **Git tag** a release (e.g. `9aa8c6e`, `9aa8c6e.1`) to publish. The Gradle project version
  is derived from the tag (`git describe`), so the artifact version always matches the ref it
  was built from. Without a tag it falls back to the short commit sha.
- **JitPack** builds the library on demand from the tag. The consuming coordinate is
  `com.github.jpabscale:asset4j:<tag-or-sha>` — automod:
  ```
  //> using repository https://jitpack.io
  //> using dep com.github.jpabscale:asset4j:9aa8c6e
  ```
- **GitHub Actions** (`.github/workflows/ci.yml`) builds and runs the full test suite on every
  push/PR, and on a tag push creates a GitHub Release whose assets are the **fat jars**
  `assetcli.jar` and `ttmapgen.jar`. Note: ttmapgen's `dll`/`il2cpp` modes still require the
  .NET harness, built separately per game via `tools/build_ttmapgen_harness.py`.
- The port compiles to **JVM 21 bytecode** (regardless of the build JDK), so the jar runs on any
  JVM 21+ — including automod's Zulu JDK 25 and scala-cli's default JVM.

## Layout

```
assetapi/src/main/kotlin/com/github/jpabscale/asset4j/   # ported library (mirrors AssetsTools.NET)
assetapi/src/test/                                       # ported JUnit tests + oracle fixtures
assetcli/                                                # thin CLI wrapper → assetcli.jar (fat jar)
ttmapgen/                                                # ttmap generator (tthm = Kotlin; dll/il2cpp via .NET harness)
ttmapgen/dotnet-harness/                                 # C# bridge (MonoCecil/Cpp2IL), pinned commit
tools/                                                   # fetch_corpus.py, build_ttmapgen_harness.py, sweep.py, audit_parity.py
docs/mapping.md                                          # C# → Kotlin translation contract
docs/port-tracker.md                                     # per-file port status + oracle notes
```

## License

MIT. Ported files carry the AssetsTools.NET attribution header. New parts (the CLI wrapper, the
ttmap schema, `AssetService`, the JSON/API layer, `ttmapgen`) are also MIT.

The test corpus is **not** covered by MIT: the fixtures are derived from games and remain the IP
of their rights holders; `assetapi/src/test/resources/testassets/NOTICE.md` documents each source
and its license (UnityDataTools under the Unity Companion License, Unity Royale and UnityPy under
MIT). They are internal test fixtures, never redistributed with the published artifact.
