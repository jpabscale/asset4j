# Port tracker — AssetsTools.NET-9aa8c6e → asset4j

Tracks each upstream C# file to its Kotlin port and tests. Status: **not ported** | **stub** (skeleton,
throws for unported paths) | **ported** (statement-parallel, may defer downstream deps).

Pinned upstream: https://github.com/nesrak1/AssetsTools.NET commit
`9aa8c6ead19b7667ed6d4d9c0c9fd48967433e1a` (2026-08-02).
READ-ONLY reference checkout: `/tmp/automod/AssetsTools.NET/AssetTools.NET/`.

## Port tree (dependency levels, ported bottom-up)

| Lvl | Files | Depends on | Status |
|---|---|---|---|
| 0 | `Standard/IO/AssetsFileReader.cs`, `AssetsFileWriter.cs`, `Standard/AssetsFileFormat/Hash128.cs`, `GUID128.cs` | — | Phase 1–2 |
| 1 | `TypeTreeType.cs` (CommonString table), `TypeTreeNode.cs`, `TypeTreeNodeFlags.cs`, `TypeTreeBlob.cs` | L0 | Phase 4 / 4.1 |
| 2 | `AssetBundleFileFormat/*` (read), `AssetsFileFormat/*` (read: Header/FileInfo/AssetsFile/Metadata/External/PPtr/ExternalType) | L0+L1 | Phase 3 / 5 |
| 3 | `AssetTypeClass/AssetTypeValue.cs`, `AssetTypeTemplateField.cs`, `AssetTypeValueField.cs`, `RefTypeManager.cs`, `AssetTypeArrayInfo.cs`, `EnumValueTypes.cs` | L1 | Phase 6 |
| 4 | value read (template field decode) | L3 | Phase 6 |
| 5 | value write (template field encode) | L3+L4 | Phase 7 |
| 6 | `AssetsFile.cs` Write path, `AssetBundleFile.cs` Write path | L2+L5 | Phase 8 / 9 |
| 7 | `ttmap/` schema (Kotlin data classes, not a port) | L1 | Phase 4.2 |
| 8 | `ttmapgen/` generators (tthm/DLL/IL2CPP → ttmap) | L7+L2, .NET subprocess | 4.3-4.5 done; IL2CPP validated on Melon Playground (Android ELF, 281 classes) |
| 9 | `api/AssetService.kt` + `assetcli` | L2+L6+L7 | Phase 10 / 12 |

## M1 — Foundation (Phases 0–3)

| C# file | Kotlin file | Status | Tests |
|---|---|---|---|
| `Standard/IO/AssetsFileReader.cs` | `io/AssetsFileReader.kt` | ported | `AssetsFileReaderWriterTest` |
| `Standard/IO/AssetsFileWriter.cs` | `io/AssetsFileWriter.kt` | ported | `AssetsFileReaderWriterTest` |
| `Standard/AssetsFileFormat/Hash128.cs` | `io/Hash128.kt` | ported | `Hash128Test` |
| `Standard/AssetsFileFormat/GUID128.cs` | `io/GUID128.kt` | ported | `GUID128Test` |
| `Standard/AssetsFileFormat/TypeTreeType.cs` (CommonString) | `typetree/CommonString.kt` | ported | `CommonStringTest` |
| `Standard/AssetsFileFormat/TypeTreeNode.cs` | `typetree/TypeTreeNode.kt` | ported | (tested via blob) |
| `Standard/AssetsFileFormat/TypeTreeNodeFlags.cs` | `typetree/TypeTreeNodeFlags.kt` | ported | |
| `Standard/AssetsFileFormat/TypeTreeType.cs` | `typetree/TypeTreeType.kt` | ported | |
| `Standard/AssetsFileFormat/TypeTreeBlob.cs` | `typetree/TypeTreeBlob.kt` | ported | |
| `Extra/AssetClassID.cs` | `typetree/AssetClassID.kt` | ported | |
| `Standard/AssetsFileFormat/AssetsTypeReference.cs` | `serializedfile/AssetTypeReference.kt` | ported | |
| `Standard/AssetsBundleFileFormat/*` | `bundle/*` | ported (read+write+pack) | `AssetBundleFileTest` |
| LZ4/LZMA (via lz4-java/xz) | `bundle/UnityCompression.kt` | ported | `AssetBundleFileTest` |
## M2 — Type trees (Phases 4–5)

| C# file | Kotlin file | Status | Tests |
|---|---|---|---|
| `Standard/AssetsFileFormat/AssetsFileHeader.cs` | `serializedfile/AssetsFileHeader.kt` | ported | `AssetsFileTest` |
| `Standard/AssetsFileFormat/AssetFileInfo.cs` | `serializedfile/AssetFileInfo.kt` | ported | `AssetsFileTest` |
| `Standard/AssetsFileFormat/AssetsFile.cs` | `serializedfile/AssetsFile.kt` (read+write) | ported | `AssetsFileTest` |
| `Standard/AssetsFileFormat/AssetsFileExternal.cs` | `serializedfile/AssetsFileExternal.kt` | ported | |
| `Standard/AssetsFileFormat/AssetsFileExternalType.cs` | `serializedfile/AssetsFileExternalType.kt` | ported | |
| `Standard/AssetsFileFormat/AssetPPtr.cs` | `serializedfile/AssetPPtr.kt` | ported | |
| `Standard/AssetsFileFormat/AssetsFileMetadata.cs` | `serializedfile/AssetsFileMetadata.kt` | ported | `AssetsFileTest` |
| `Standard/AssetsFileFormat/TypeTreeNode.cs` | `typetree/TypeTreeNode.kt` | ported | |
| `Standard/AssetsFileFormat/TypeTreeNodeFlags.cs` | `typetree/TypeTreeNodeFlags.kt` | ported | |
| `Standard/AssetsFileFormat/TypeTreeType.cs` | `typetree/TypeTreeType.kt` | ported | |
| `Standard/AssetsFileFormat/TypeTreeBlob.cs` | `typetree/TypeTreeBlob.kt` | ported | |
| (ttmap schema — new code) | `ttmap/Ttmap.kt` | ported | `TtmapTest` |

## M3 — Object round-trip (Phases 6–9)

| C# file | Kotlin file | Status | Tests |
|---|---|---|---|
| `Standard/AssetTypeClass/AssetTypeValue.cs` | `value/AssetTypeValue.kt` | ported | `TypeTreeHelperTest` |
| `Standard/AssetTypeClass/AssetTypeValueField.cs` | `value/AssetTypeValueField.kt` | ported | `TypeTreeHelperTest` |
| `Standard/AssetTypeClass/AssetTypeTemplateField.cs` | `value/AssetTypeTemplateField.kt` | ported (read+write) | `TypeTreeHelperTest` |
| `Standard/AssetTypeClass/RefTypeManager.cs` | `value/RefTypeManager.kt` | ported (ref-types only) | |
| (TypeTreeHelper read/write — new code, from template fields) | `value/TypeTreeHelper.kt` | ported | `TypeTreeHelperTest` |

## M4 — API (Phases 10)

| C# file | Kotlin file | Status | Tests |
|---|---|---|---|
| (uasset4j UAssetService mirror — new code) | `api/AssetService.kt` | ported | `AssetServiceTest` |

Deferred / out of scope (v1): ClassDatabaseFile/*, AssetManager/*, NewReplacer/*, Texture/*,
MonoCecil/Cpp2IL JVM ports (subprocess bridge instead).
