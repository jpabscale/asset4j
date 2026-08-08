# Port mapping: AssetsTools.NET (C#) -> asset4j (Kotlin/JVM)

This file is the living translation cheat sheet for the port. It must be updated **before** any new C# construct is
ported (the port tracker rule): a reviewer must be able to look up any construct used in a ported file here.

## Pinned upstream source

- Repository: https://github.com/nesrak1/AssetsTools.NET
- READ-ONLY reference checkout: `/tmp/automod/AssetsTools.NET` (module root `AssetTools.NET/`)
- **Pinned commit: `9aa8c6ead19b7667ed6d4d9c0c9fd48967433e1a`** (2026-08-02, merge PR #165). All files are
  ported from this exact tree.
- License: MIT `Copyright (c) 2019-2026 nesrak1`. Ported files carry an attribution header.

## Project layout

| Upstream (C#) | Here (Kotlin) |
|---|---|
| `AssetTools.NET/` namespace `AssetsTools.NET` | `:assetapi` module, package `com.github.jpabscale.asset4j.*` |
| `AssetsTools.NET.Standard.*` | `com.github.jpabscale.asset4j.io/bundle/serializedfile/typetree/value` |
| `AssetsTools.NET.Extra.*` (AssetsManager, TypeTreeConverters) | only the `IMonoBehaviourTemplateGenerator` seam concept in `ttmapgen` |
| (uasset4j `UAssetService` mirror — new code) | `:assetapi` `com.github.jpabscale.asset4j.api.AssetService` |
| `:uassetcli` (uassetcli analog) | `:assetcli` module, `com.github.jpabscale.asset4j.assetcli.MainKt` |
| `:ttmapgen` (ttmap generator, new module) | `:ttmapgen` module, `com.github.jpabscale.asset4j.ttmapgen.MainKt` |
| `AssetsTools.NET.Tests/` | `assetapi/src/test/kotlin` (port-of-port + differential oracle) |
| corpus (UnityPy / UnityDataTools / AddressableAssetsWebinar) | `assetapi/src/test/resources/testassets/` (fetched, gitignored) |

Namespaces are mirrored one-to-one: `AssetsTools.NET` -> `com.github.jpabscale.asset4j`,
`AssetsTools.NET.Standard.AssetsBundleFileFormat` -> `.bundle`,
`AssetsTools.NET.Standard.AssetsFileFormat` -> `.serializedfile` (class model) / `.typetree` (type-tree),
`AssetsTools.NET.Standard.IO` -> `.io`, `AssetsTools.NET.Standard.AssetTypeClass` -> `.value`,
`AssetsTools.NET.Standard.ClassDatabaseFile` -> not ported (v1), `AssetsTools.NET.Extra.*` -> `ttmapgen` only.

## Construct mapping

**Naming rule (enforced by `tools/audit_parity.py` at milestone close):** C# public class/method/
field/property names are preserved verbatim (PascalCase) in Kotlin, so upstream diffs map
mechanically. Exceptions: the .NET base-member overrides map to their Kotlin forms
`Equals`→`equals`, `GetHashCode`→`hashCode`, `ToString`→`toString`, `CompareTo`→`compareTo`,
`Clone`→`clone`; C# parameter names keep their camelCase; constructor/method parameter names and
Kotlin-only helpers are not part of the mirrored surface.

| C# | Kotlin |
|---|---|
| `class Foo` | `class Foo` (open by default in Kotlin — mark `sealed`/`final` only where C# forbids override) |
| `sealed class Foo` | `sealed class Foo` |
| `readonly struct` | `data class` |
| `record` | `data class` |
| `interface IFoo` | `interface Foo` (no `I` prefix) |
| `abstract class` | `abstract class` |
| `enum Foo` | `enum class Foo` |
| `Foo[]` / `List<Foo>` | `List<Foo>` (read-only); `Foo[]` -> `Array<Foo>` where interop needs it |
| `Dictionary<K,V>` | `Map<K,V>` / `LinkedHashMap` where order matters |
| `HashSet<T>` | `MutableSet<T>` (LinkedHashSet where order matters) |
| `bool` | `Boolean` |
| `byte` / `sbyte` | `Byte` / `Int` (sbyte as `Byte` + sign handling) |
| `short` / `ushort` | `Short` / `Int` (ushort widened) |
| `int` / `uint` | `Int` / `Long` (uint widened) |
| `long` / `ulong` | `Long` / `UByteArray`? no — `BigInteger` or `Long` w/ masks |
| `float` / `double` | `Float` / `Double` |
| `string` / `char` | `String` / `Char` |
| `byte[]` / `ReadOnlySpan<byte>` / `Span<byte>` | `ByteArray` + offset/len params (see helpers) |
| `ref Foo` / `out Foo` | `Ref<Foo>` / `Out<Foo>` (helpers; 568 occurrences upstream) |
| `new Foo(...)` | `Foo(...)` |
| `obj.Property` get/set | `var` / `val`; getter-only + private set mirrors C# |
| `=> expr` | `fun foo() = expr` |
| `foo => bar` (lambda) | `{ foo -> bar }` |
| LINQ `.Where/.Select/.First/.Any/...` | stdlib equivalents (`filter/map/first/any`); `ToArray` where C# materializes |
| `string.Join(", ", xs)` | `xs.joinToString(", ")` |
| `Console.WriteLine` | logging via `util/Log.kt`; CLI via `println` |
| `throw new XException(msg)` | `throw XException(msg)` (exceptions mirror C# names) |
| `if (x) a else b` | `if (x) a else b` |
| `switch` / `switch expr` | `when` / `when` as expression |
| `for` / `foreach` | `for` loops / `forEach` |
| `GetType()`, `typeof(T)` | `::class`, `Foo::class` |
| `is` / `as` / cast | `is` / `as?` / `as` |
| `==` / `Equals` / `ReferenceEquals` | `==` (data-class structural) / `===` reference |
| `static` methods/fields | companion object / top-level `object` |
| `#if DEBUG` blocks | `if (BuildConfig.DEBUG)` or JUnit-managed |
| Attributes `[JsonProperty]`, `[JsonIgnore]` | `@JsonProperty`, `@JsonIgnore` (Jackson) |
| `[Introduced(EngineVersion.X)]` custom attr | enum constructor arg `(EngineVersion.X)`; see `customversions/CustomVersions.kt` (generated) |
| `enum` member `LatestVersion = VersionPlusOne - 1` | Kotlin enum keeps declaration order; add `val value: Int get() = ordinal - (if (this == LatestVersion) 2 else 0)` (LatestVersion directly follows VersionPlusOne) |
| `System.Guid` (value semantics, .NET byte layout) | `FGuid` value class in `unrealtypes/FGuid.kt` (data1 LE, data2/3 LE16, data4 BE64 — matches `Guid.ToByteArray()`) |
| `TMap<K,V>` (ordered dictionary) | `LinkedHashMap<K,V>` (insertion-ordered) — no need to port the 794-line TMap.cs |
| `Encoding.UTF8` / `Encoding.Unicode` | `Charsets.UTF_8` / `Charsets.UTF_16LE` |
| `reader.BaseStream.Position = x` / `-= n` | `reader.position = x` / `reader.position -= n` |
| `BaseStream.ReadExactly(span)` | `reader.readFully(n)` (returns `ByteArray`) |
| `stackalloc`/`Span<byte>` | `ByteArray` |
| `Array.Empty<byte>()` | `ByteArray(0)` |
| `this is AssetBinaryReader abr` (type pattern) | `this as? AssetBinaryReader` |
| C# `switch` relational pattern `case < 0:` | `when { x < 0 -> ... }` |
| `Guid.ToByteArray()` / `new Guid(bytes)` | `FGuid.toByteArray()` / `FGuid.fromBytes(bytes)` |
| `[Flags] enum X : ulong` (raw int64 flag bits) | `@JvmInline value class X(val value: Long)` + companion consts (see `Flags.kt`) |
| `ConcurrentDictionary<string, X>(StringComparer.*)` | `CIMap<X>` helper in `unversioned/CIMap.kt`: LinkedHashMap keeps original keys (iteration order/dump parity); a normalized index provides case-insensitive get/contains |
| `Encoding.ASCII.GetString(bytes)` | strict ASCII decode mapping bytes `>0x7F` to `'?'` (matches .NET ASCII fallback) |
| `interface IStruct<T>` with `abstract static T Read(AssetBinaryReader)` / `T FromString(string[], UAsset)` | `class StructAccessors<T>(val read: (AssetBinaryReader) -> T, val fromString: (Array<String>, UAsset) -> T, val write: (AssetBinaryWriter, T) -> Int, val defaultValue: () -> T)`; each struct value type exposes `companion object { val accessors = StructAccessors(...) }`; `BasePropertyData<T>` takes them as a constructor arg (Kotlin has no static-interface dispatch) |
| `abstract class BasePropertyData<T> : PropertyData<T> where T : IStruct<T>, new()` | `abstract class BasePropertyData<T>(accessors: StructAccessors<T>) : PropertyData()` with a secondary `BasePropertyData(accessors, name)`; subclasses: `constructor(name: FName?) : super(T.accessors, name)` / `constructor() : super(T.accessors)` |
| `BinaryReader.ReadSByte` / `BinaryWriter.Write(sbyte)` | `UnrealBinaryReader.ReadSByte(): Int` / `UnrealBinaryWriter.WriteSByte(Int)` |
| `writer.Seek(off, SeekOrigin.Begin)` | save `position`, set `position = off`, restore |
| `Activator.CreateInstance(T, Name)` | `registryEntry.Creator(Name)` (the registry's factory lambda) |
| `InvalidOperationException` / `NotImplementedException` | `IllegalStateException` / `NotImplementedError` |
| `GetCustomVersion<T>()` (custom-version enum) | `asset.GetCustomVersion(CustomVersion.GetCustomVersionGuidFromFriendlyName("FEnumName"))` compared against the Kotlin enum member's `.ordinal` |
| `System.Drawing.Color` / `System.DateTime` / `System.TimeSpan` | minimal Kotlin `Color`/`DateTime`/`TimeSpan` value classes (ARGB/ticks semantics; keeps the port AWT/native-image free) |
| `Math.Pow` / `Math.Floor` / `sizeof(T)` | `kotlin.math.pow` / `kotlin.math.floor` / `Float`/`Double`/`Int`.`SIZE_BYTES` |
| `Func<T>` / `Action<T>` (ctor params) | `() -> T` / `(T) -> Unit` |
| `ValueTuple<a,b,c>` | `Triple<A,B,C>` |
| `T[]` inside a generic class (`T` = type param) | `newArray(size) { ... }` / `emptyGenericArray()` helpers (Kotlin `Array`/`emptyArray` are reified). Both are `inline fun <reified T>` so the produced arrays have the correct runtime component type; a generic (non-reified) `T` field that erases to `Object[]` (e.g. `TEvaluationTreeEntryContainer<T>.Items`) constructs its array manually with `arrayOfNulls<Any>(n) as Array<T>` |
| `PropertyData.Clone()` (`MemberwiseClone()` + re-clone `Name`/`RawValue` if `ICloneable` + `HandleCloned`) | Kotlin `clone()` = `CreateClone()` + `memberwiseCopy(this, res)` (reflection shallow-copy of every base/concrete field, replicating C# `MemberwiseClone`) + `CloneInto(res)` + `HandleCloned(res)`. The reflection pass preserves fields that C# copies implicitly and that the JVM's per-type `CloneInto` does not enumerate (e.g. `TextPropertyData.Flags/HistoryType`, `StructPropertyData.SerializeNone`) |
| `(A, B)[]` tuple array | `Array<Pair<A, B>>` |
| `struct` (non-`readonly`) value type | `class` with mutable `var` members |
| `enum` with `None = -1` | `enum class X(val value: Int)` + explicit values; read/write via `ReadSByte`/`WriteSByte` |
| byte enum round-trip `(E)ReadByte()` / `Write((byte)e)` | `E.entries[ReadByte()]` / `WriteByte(e.ordinal)` |
| `Enum.TryParse<T>` / `bool.TryParse` | `T.entries.firstOrNull { it.name.equals(s, true) }` / local `TryParseBool(s): Boolean?` |
| `int.TryParse(s, out _)` | `s?.toIntOrNull() != null` |
| `TMap` int indexer / `Keys.ElementAt(i)` | `value.values.elementAt(i)` / `value.keys.elementAt(i)` |
| `Type.GetType(name)` + `Activator.CreateInstance(type)` (reflection-based FieldTypes dispatch) | explicit `fPropertyRegistry`/`uPropertyRegistry` maps keyed by the type name with non-letters stripped (mirrors the `allNonLetters` regex + `"F"`/`"U"` prefix in `MainSerializer`); instantiation via `klass.java.getDeclaredConstructor().newInstance()`; missing key falls back to `FGenericProperty`/`UGenericProperty` |
| `Type` parameter / `typeof(T)` when instantiated | `KClass<out T>`; `Activator.CreateInstance(typeof(T))` generic helper becomes `inline fun <reified T : UProperty>` |
| C# `bool TryX(..., out T propDat, out int idx)` | nullable `Pair<T, Int>?` (null when not found; `pair.first`/`pair.second` for the outs) |
| C# `bool TryX(..., out T propDat)` (single out) | nullable `T?` return (null when not found); `out UsmapArrayData arrDat` in the dummy-array-member recursion becomes `as? UsmapArrayData` |
| generic constraint `where T : UsmapProperty` on a non-generic Kotlin signature | dropped — the Kotlin method is non-generic over `UsmapProperty`/`UsmapPropertyData` |
| `Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData)` | `System.getenv("LOCALAPPDATA")`, falling back to `USERPROFILE + "/AppData/Local"` (read-only; `UAGConfig.kt` / the CLI-only mappings index) |
| `File.WriteAllText(path, text)` | `java.nio.file.Files.writeString(Path.of(path), text)` (UTF-8, no BOM) |
| `using (var sr = new FileStream(path, FileMode.Open)) { ... }` | `.use { }` over the opened stream (e.g. `File(path).inputStream().buffered().use { UAsset.DeserializeJson(it) }`) |
| `Path.GetFileNameWithoutExtension(p)` | `File(p).nameWithoutExtension` |
| `Environment.GetCommandLineArgs()` (`args[0]` = exe) | `main(args)`: command at `args[0]`, all C# indices shifted by one |
| `string.ToLowerInvariant()` | `String.lowercase()` |
| `EngineVersion.VER_UE4_0 + raw` (enum + int offset; may be undefined) | `EngineVersion.entries[EngineVersion.VER_UE4_0.ordinal + raw]` guarded by `ordinal in EngineVersion.entries.indices`, else `EngineVersion.UNKNOWN` (never throws) |
| `Enum.TryParse("VER_UE" + ver.Replace('.', '_'), out _)` (case-insensitive) | `EngineVersion.entries.firstOrNull { it.name.equals(candidate, true) } ?: EngineVersion.UNKNOWN` |
| `C# out Usmap` in `UAGConfig.TryGetMappings(name, out Usmap)` | nullable `Usmap?` return (null when not found / unparseable); caller falls back to `Usmap(name)` as a path |
| `internal static class X` (System.Text.Json converters) | `internal object X`; the `JsonConverter<T>` `Read(ref Utf8JsonReader, ...)` becomes `fun Read(node: JsonNode): T` — discriminator dispatch over Jackson's tree model (`ObjectMapper.readTree`), and `Write` throws `NotImplementedError` (C# `NotImplementedException`) |
| `System.Text.Json.JsonException` | `FormatException` (the existing UAssetAPI port exception) |
| `Properties.Resources.X` (embedded `.bin` resource) | classpath resource `src/main/resources/X.bin`, loaded via `javaClass.classLoader.getResourceAsStream` (e.g. `AC7Key.bin`) |
| `System.Numerics.BigInteger` | `java.math.BigInteger` |
| `BitConverter.GetBytes(uint).CopyTo(array, 0)` | local `WriteUInt32LE(array, offset, value)` helper |
| `Path.ChangeExtension(p, "ext")` | `p.substringBeforeLast('.', p) + ".ext"` helper |
| `#if DEBUG`-gated class (`MonitoringStream`) | class always compiled; the debug-only actions (`Debug.WriteLine`/`Debugger.Break`) are inert on the JVM |
| C# bidirectional `Stream` wrapper | `java.io.InputStream` wrapper; `Position` is tracked by counting `Read` bytes so the stop-offset check compares against a virtual stream position |
| `FileStream.OpenRead` + `Seek(off)` + bounded `Read` (lazy jmap offsets) | `java.io.RandomAccessFile(path, "r")` + `seek` + `readFully` |
| `GZipStream(strm, Decompress).CopyTo(mem)` | `java.util.zip.GZIPInputStream(fs).use { it.readBytes() }` |
| `Utf8JsonReader` streaming top-level scan (metadata/objects + token offsets) | custom byte scanner over the whole (decompressed) `ByteArray`: `JmapSpan(start, endExclusive)`, `JmapSkipWs`, `JmapReadString`, `JmapFindMatchingClose`, `JmapValueEnd`, `JmapScanMembers`; small slices (metadata, per-object JSON) are parsed with Jackson `readTree`. Whole-file buffering replaces C#'s 10 MB sliding buffer |
| C# property getter with side effect (`get { PopulateIfNeeded(); return _X; }`) | Kotlin `var X get() { PopulateIfNeeded(); return _X }` over a private backing `_X`; C# `internal` fields keep their names (`propertiesInternal`, `_Values`) |
| `internal ConcurrentDictionary<int, UsmapProperty> propertiesInternal` | `internal var propertiesInternal: LinkedHashMap<Int, UsmapProperty>` |
| private static helper with `out int pk1, out int pk2` | `Pair<Int, Int>` return (see `AC7XorKey.CalcPKeyFromNKey`) |

## Kismet bytecode (M6 — expression classes)

- `class KismetExpression` **and** `class KismetExpression<T>` (same name, one generic) — C#/CLR allow
  two classes with the same name (the generic erases to a different CLR signature). JVM erasure forces
  the generic to erase to the same binary name as the non-generic, which Kotlin rejects as
  "Redeclaration". Mapping: keep `KismetExpression` verbatim; port the generic base as
  `KismetExpressionGeneric<T>` (still `: KismetExpression`, adds `var Value: T`). Documented deviation,
  mirrored everywhere an expression does `: KismetExpression<T>()`.
- `public virtual T Token { get { ... } }` (property returning the instruction's `EExprToken`) |
  `open val Token: EExprToken get() = ...`; subclass overrides are `override val Token`.
- `public virtual void Visit(UAsset asset, ref uint offset, Action<KismetExpression, uint> visitor)`
  | `open fun Visit(asset: UAsset, offset: Ref<Long>, visitor: (KismetExpression, Long) -> Unit)`.
  `ref uint` uses the existing `Ref<T>` helper (`uint` -> `Long`); in the body `offset += N` becomes
  `offset.value = offset.value!! + N` (and `visitor(this, offset)` -> `visitor(this, offset.value!!)`).
  `uint GetSize(UAsset)` | `fun GetSize(asset: UAsset): Long` (allocates `Ref<Long>(0L)`, runs an empty
  visitor).
- `Action<T1, T2>` (two-arg) | `(T1, T2) -> Unit` (extends the one-arg `Action<T>` row above).
- Non-contiguous raw-byte enum cast `(ECastToken)reader.ReadByte()` / `Write((byte)tok)` — the byte is
  NOT the ordinal (EExprToken/ECastToken carry explicit `0x..` values). Mapping:
  `EX_PrimitiveCast.ConversionType` is stored as `ECastTokenValue(raw)` (a small wrapper carrying the
  raw byte); the JSON layer serializes the named token string when the byte matches an `ECastToken`
  entry and the raw number otherwise (Newtonsoft emits the number for undefined C# enum values).
  `Write` emits `ConversionType.value` (same byte as C#).
- `KismetExpression[]` fields with a C# `= null` implicit default (reference fields) | `var X:
  KismetExpression? = null`; C# `X.Read(...)`/`X.Visit(...)` derefs become `X!!.` at the use site.
- `struct FKismetSwitchCase` with public mutable fields + ctor (mutable struct) | `class` with `var`
  fields and a matching secondary constructor (per the mutable-`struct` row above); `new
  FKismetSwitchCase[n]` | `Array(n) { ... }`.
- `reader.ReadExpressionArray(EExprToken.EX_EndArray)` — end-token-terminated element list |
  `Array<KismetExpression>` (worktree `AssetBinaryReader` stub now takes `EExprToken`).
- Fixed-size iCode-`Visit` offsets in comments (`offset += 8; // ClassPtr (8)`) are preserved as
  integer literals (`offset.value = offset.value!! + 8`).
- `EExprToken` (C# `enum`, explicit hex byte values) -> `enum class EExprToken(val value: Int)` with
  the explicit values; `KismetExpression.Token`/`Inst` derive from the enum. Byte round-trips
  (`(EExprToken)ReadByte()`) are handled by the serializer, not by `entries[]` (values are
  non-contiguous).
- `Token.ToString().Substring(3, len - 3)` (strip the `EX_` prefix) -> `Token.name.substring(3, len)`.
- Kismet `uint` fields (`EX_Context.Offset`, `EX_Jump.CodeOffset`, `EX_JumpIfNot.CodeOffset`) ->
  `Long` via `ReadUInt32`/`WriteUInt32`; `ushort` (`EX_Assert.LineNumber`) -> `Int` via
  `ReadUInt16`/`WriteUInt16`; `byte` (`EX_ByteConst.Value`, `EX_BitFieldConst.Value`,
  `EX_Context.PropertyType`) -> `Byte` via `ReadByte().toByte()`/`WriteByte(Value.toInt())`.
- `KismetPropertyPointer` (Old `FPackageIndex?` / New `FFieldPath?` + `ShouldSerializeOld/New`) ->
  class in `kismet/bytecode`; the `XFER_PROP_POINTER` reader/writer pair is gated via
  `GetCustomVersion("FReleaseObjectVersion") >= FReleaseObjectVersion.FFieldPathOwnerSerialization.ordinal`,
  using the existing `FFieldPath` value type.
- Byte-backed Kismet enum round-trip `(E)ReadByte()`/`Write((byte)e)` -> `E.entries[ReadByte()]` /
  `WriteByte(e.ordinal)` (`EX_InstrumentationEvent.EventType`; declaration order matches C#).
- C# `FName` fields (`EX_BindDelegate.FunctionName`, `EX_InstanceDelegate.FunctionName`,
  `EX_InstrumentationEvent.EventName`) are reference-typed and null by default -> `FName? = null`,
  `!!` at the non-null write call sites (`XFER_FUNC_NAME`).
- M-Z expression classes referenced by A-L ports are stubbed in the expressions package:
  `EX_VariableBase` (base of `EX_ClassSparseDataVariable`/`EX_DefaultVariable`/`EX_InstanceVariable`)
  mirrors the C# member names but its `Read`/`Write` throw `NotImplementedError` until the M-Z
  milestone.
- `[JsonObject(MemberSerialization.OptIn)]` on `KismetExpression`/`KismetPropertyPointer`/`FScriptText`
  -> the JSON bean modifier filters each EX_* class to its C# `[JsonProperty]` members (derived-first,
  generic const `Value` last) and the `$type` mixins map to `UAssetAPI.Kismet.Bytecode.Expressions.*`.

## JSON parity

UAssetAPI uses Newtonsoft (`JsonConverter`/`JsonExtensionData`/discriminators). We use Jackson 2.19 and hand-port every
converter as a custom serializer/deserializer over `JsonGenerator`/`JsonParser` (token-level). Output schema is pinned by
the C# oracle: golden JSON files + differential tests. See `docs/json-parity.md` (seed in M4).

Implemented mechanisms (L5, `com.github.jpabscale.uasset4j.json`):

- **TypeNameHandling.Objects (`$type` on every POCO)** -> `@JsonTypeInfo(use = Id.CUSTOM, include = As.PROPERTY,
  property = "$type", visible = true)` mixins on `PropertyData`, `Export`, and the standalone POCOs
  (CustomVersion/Import/FGenerationInfo/FEngineVersion/UDataTable), resolved by a shared `UAssetTypeIdResolver`
  that maps Kotlin classes <-> the C# `"<Namespace>.<Class>, UAssetAPI"` strings (`UAssetTypeIds`).
  `UAssetTypeIds.propertyClasses` lists every concrete, JSON-serializable `PropertyData` subclass (mirroring the
  classes C# registers in its `PropertyTypeRegistry` via each type's `PropertyType` string, so Newtonsoft emits each
  with its own `$type`); abstract generic bases (`BasePropertyData`, `TPerPlatformPropertyData`,
  `TPerQualityLevelPropertyData`, `TBoxPropertyData`, `MaterialInputPropertyData<T>`) are excluded. `classFor`'s
  simple-name fallback (for `$type`s whose exact id is unmapped) only resolves when the simple name is unambiguous,
  matching `idFor`'s `KClassId` fallback.
- **Member ordering (fields-derived-first, OptIn/OptOut filtering)** -> `UAssetBeanSerializerModifier`
  (a `BeanSerializerModifier`): filters `PropertyData` beans to their `[JsonProperty]` members and orders every bean's
  properties to the exact C# output order (per-class lists); `Export` is OptOut (public members, `Asset`/internal
  excluded); `ShouldSerializeXxx()` methods are honored by wrapping `BeanPropertyWriter`.
- **Property-name casing (PascalCase, `[JsonProperty]` names verbatim)** -> `UAssetAnnotationIntrospector`:
  the pinned jackson-module-kotlin 2.19 cannot read Kotlin 2.4 metadata, so names are re-derived from
  `kotlin.reflect.full.memberProperties` (getter/setter method -> property name). `@JsonProperty(...)` still wins.
- **`[Flags]` enums (StringEnumConverter, `"A, B"` / `"None"`)** -> value-class serializer per flag type
  (`FlagsConverters`), registered on the ObjectMapper; `EPackageFlags`/`EObjectFlags`/`EPropertyTagFlags`/
  `EPropertyTagExtension`/`EClassSerializationControlExtension`/`CustomSerializationFlags`.
  Note: jackson-module-kotlin unwraps `@JvmInline value class` to its underlying primitive for deserialization, so the
  flags deserializers are attached at the bean-property level (`UAssetBeanDeserializerModifier`) and return the
  primitive the Kotlin setter accepts.
- **Leaf converters** -> `StdSerializer`/`StdDeserializer` ports of the JSON/*.cs converters: FName, FString,
  FPackageIndex, FGuid (UAPUtils.ConvertToString brace form), byte[] (base64), BitArray (bool array),
  float/double (`FSignedZeroJsonConverter`: `-0`/`+0` strings, float promoted to double before writing;
  non-zero values are formatted by `NewtonsoftDouble`, a port of Newtonsoft's `WriteValue(double)` shortest
  round-trip formatting — fixed-point below `1E-4`/at `1E+16`, scientific `E±nn` with a 2-digit exponent
  otherwise, and a trailing `.0` on integral fixed-point values), C# `byte` fields as unsigned 0-255,
  `ECastTokenValue`/`EArrayDim`/`ELifetimeCondition` (named string for defined values, raw number for
  undefined ones), FPropertyTypeName, FStringTable, TMap (ordered array of [key, value] pairs).
- `EPropertyFlags` is a C# `[Flags] ulong` enum; the 64-bit flags serializer (`flagsSerializer64`) emits the
  unsigned decimal for unnamed values (Newtonsoft writes a `ulong`), so a set high bit does not turn negative.
- **UAsset itself** -> fully custom `UAssetSerializer`/`UAssetDeserializer`: the 62 properties in exact oracle order
  (`$type` first), with typed list serializers so polymorphic container elements keep their `$type`.
- **Formatting.Indented** -> custom `UAssetPrettyPrinter`: 2-space indent, array elements on their own lines,
  `": "` separators, and Newtonsoft-style `[]`/`{}` for empty containers.
- **FName deferred resolution** -> Newtonsoft's `ToBeFilled` dictionary is `FNameToBeFilled` (thread-local);
  `DeserializeJson` resolves dummy FNames against the asset's name map after the object graph is built.
- **FGuid.fromUnsignedInts / UAPUtils.GUID** match C#'s byte layout (`bytes = [v1 LE][v2 LE][v3 LE][v4 LE]`,
  `new Guid(bytes)`) — see `unrealtypes/FGuid.kt`; this differs from the canonical UUID string mapping.
- **FGuid.toString()** ports .NET `Guid.ToString()` "D" format (lowercase, `8-4-4-4-12`): `data4` is split into
  its high 16 bits (`%04x`) + low 48 bits (`%012x`) so map keys read `453e9b50-24e4-4317-8ad5-b937f6b3bcb8`.
  `toPrettyString()` (UAPUtils.ConvertToString brace form) is unchanged and is what `GuidJsonConverter` emits.
- **Generic-erased `Value` properties** (`MaterialInputPropertyData<T>` and `TPerPlatformPropertyData<T>`): the
  nullable `T?`/`Array<T>?` static type makes jackson-module-kotlin bypass the type-registered Float/Double
  converters, so `DynamicScalarValueSerializer` is attached to the `Value` writer in `UAssetBeanSerializerModifier`
  and dispatches on the runtime value (Float/Double/arrays thereof through `FSignedZero*`, everything else via
  `findValueSerializer` + the property's `TypeSerializer`), matching Newtonsoft's global converter.
- **`LevelSequenceObjectReferenceMapPropertyData.Value`** (`TMap<Guid, FLevelSequenceLegacyObjectReference>`) has no
  `[JsonConverter]`, so Newtonsoft serializes it as a JSON object with the generic full-name `$type`
  (`UAssetAPI.UnrealTypes.TMap`2[[System.Guid, System.Private.CoreLib],[...FLevelSequenceLegacyObjectReference, UAssetAPI]], UAssetAPI`),
  Guid "D"-string keys, and `$type`-bearing `FLevelSequenceLegacyObjectReference` values — see
  `LevelSequenceObjectReferenceMapJsonSerializer`. This is distinct from `MapPropertyData.Value`, which uses
  `TMapJsonConverter` (array of [key, value] pairs). Deserialization is symmetric:
  `LevelSequenceObjectReferenceMapJsonDeserializer` (attached to the `Value` property in
  `UAssetBeanDeserializerModifier`) skips the `$type` member and parses each Guid "D"-string key / value object.
- **String escapes**: Jackson 2.19 escapes control chars as uppercase `\u00XX`; Newtonsoft uses lowercase.
  `NewtonsoftCharacterEscapes` (set on the mapper's `JsonFactory`) forces lowercase `\u00xx` for 0x00-0x1F
  while keeping the named escapes (`\n`/`\r`/`\t`/`\b`/`\f`/`\"`/`\\`).
- **`UAsset.AssetRegistryRecords`** stays `null` when `AssetRegistryDataOffset <= 0` (C# leaves the field unset,
  so it serializes as `null`), not an empty list.

## Binary I/O parity

- C# `BinaryReader`/`BinaryWriter` over a buffer -> `UnrealBinaryReader`/`UnrealBinaryWriter` (root package
  `com.github.jpabscale.uasset4j`), mirroring the two-level C# hierarchy: `UnrealBinaryReader` (primitives + FString + custom
  version container) and `AssetBinaryReader` (FName, arrays, maps, property guid, thumbnails, Kismet `XFER*`);
  same split for writers. Backed by `ByteArray` + `position` (assets are in-memory buffers, like C# `MemoryStream`).
- All primitives are read/written little-endian (C# `BitConverter` on an LE host); `ReverseIfBigEndian` is omitted as a
  no-op on every supported JVM platform.
- `MapPropertyData.MapTypeToClass` mirrors the C# branch guard: the mappings branch is taken only when
  `TryGetPropertyData(...) out UsmapMapData` *succeeds*, otherwise it falls through to `MapStructTypeOverride`
  (e.g. `"Assets"` -> `Guid` keys) — the JVM previously entered on any non-null `Mappings`.
- `UserDefinedStructExport` ports the C# `is BytePropertyData &&` guard on the `"Status"` property; a missing/non-`Status`
  property must not short-circuit into an early return.
- `ECustomVersionSerializationFormat` (Unknown/Guids/Enums/Optimized) lives beside the reader.
- Any C# unsafe/Ptr manipulation -> `ByteArray` + offset windows.

## Zstd seam

- C#: `ZstdSharp` (`new Decompressor().Unwrap(src, len)`) used in `Usmap.cs`.
- Kotlin: `util/Zstd.kt` -> `com.qyntrax.unzstd` (pure JVM, native-image-clean). Test oracle: `zstd-jni` (test-only).

## Usmap binary specifics (M2)

- `UsmapVersion`/`UsmapCompressionMethod`/`UsmapPropertyType` raw-byte mapping: `0xFF` -> `Unknown`
  (C# casts the byte to the enum, where `Unknown = 0xFF`); other out-of-range bytes are clamped to
  `Unknown` rather than producing an undefined C# enum value.
- C# `"<" + Name + ...` with a null `Name` concatenates as the empty string — ported as
  `"${Name ?: ""}"` in every `UsmapPropertyData.ToString()` so the oracle dump matches.
- `Encoding.ASCII.GetString` replaces bytes > 0x7F with `'?'` (match in `UsmapBinaryReader.ReadString`).
- Enum/schema maps are `CIMap` (original-cased keys, case-insensitive lookup) mirroring
  `ConcurrentDictionary<..., StringComparer.InvariantCultureIgnoreCase>`.
- `Usmap.GetSchemaFromName` falls back to building a schema from a matching `StructExport`/`ClassExport` in the asset
  (`Usmap.GetSchemaFromStructExport`), so a versioned blueprint CDO can resolve its class schema and walk the supertype
  chain — used by `TryGetPropertyData` for struct type resolution.
- `EArrayDim`/`ELifetimeCondition` mirror the C# raw enum casts: any `int`/`byte` is accepted (no bounds throw) and the
  raw value is retained for both serialization and `Write` (`(int)ArrayDim` / `(byte)BlueprintReplicationCondition`).

## Binary I/O parity

## Ported test suite (AssetUnitTests)

`uassetapi/src/test/kotlin/com/github/jpabscale/uasset4j/AssetUnitTests.kt` is a statement-level JUnit 5
port of `UAssetAPI.Tests/AssetUnitTests.cs` (pinned `33ef77e`):

- MSTest `[TestClass]`/`[TestMethod]` -> `class` + `@Test`; `Assert.IsTrue/IsFalse/IsNotNull/AreEqual`
  -> `org.junit.jupiter.api.Assertions.*`; `Assert.ThrowsException<T>` -> `assertThrows<T> { }`;
  `VerifyBinaryEquality` compares `ByteArray.contentEquals`.
- The corpus is read at runtime from `src/test/resources/testassets` relative to the module working
  dir (or `-Dtestassets.dir`); each test guards with `assumeTrue` when a fixture is absent so the
  suite skips instead of failing on a partial corpus.
- **Scratch outputs.** The C# tests write `MODIFIED.uasset`/`raw.json`/`*.bak` into `TestAssets`.
  The JVM port keeps the corpus read-only and redirects every output write to
  `build/test-scratch/` (or `-Dtest.scratch.dir`); each test that writes uses a per-test
  subdirectory (`TestDataTables`, `TestUnderlyingEnumTypes`, ...) so a `.uexp` emitted by one test
  cannot contaminate another test's reload (the reload concatenates the sibling `.uexp`).
  `TestACE7`/`TestClone` copy their input fixtures into the scratch dir first and operate there.
- `TestRepak` is `@Disabled` (drives the external repak tool, which has no JVM port) and
  `TestTracing` is `@Disabled` (C# compiles it behind `#if DEBUGTRACING` using internal
  `Trace.LoggingAspect` hooks).
- `TestCustomProperty` registers the test-defined `CoolPropertyData` into the shared
  `MainSerializer.PropertyTypeRegistry` before parsing (the JVM port has no reflection-based
  assembly scan like UAssetAPI.C#).

### C# value-type `Value` semantics in the JVM

C# `PropertyData<T>` with a struct `T` never has a null `Value` (`GetObject<T>()` returns
`default(T)`), so a freshly-instantiated struct-backed property serializes zeros. The JVM ports
store nullable `Value`; the `CanBeZero`/`GenerateUnversionedHeader` path instantiates such
properties via `MainSerializer.TypeToClass` and would NPE. Nullable struct-backed properties
(`GuidPropertyData`, `Box*PropertyData`, `ColorPropertyData`, `DateTimePropertyData`,
`TimespanPropertyData`) therefore default their `Value` to the zero value in `Write`/`toString`/
`CloneInto` (`Value ?: <zero>`), mirroring `default(T)`.

### Struct classes serialized as JSON beans need a no-arg constructor

C# structs (`FURL`, `TBox<T>`, the slate `FVector2f`) are deserialized by Newtonsoft via
`default(T)` + field population. Jackson needs a no-arg constructor for the same purpose; these
JVM classes carry a no-arg constructor (delegating to the field defaults, `null as T` where a
generic `T` field is unavoidable).

### Polymorphic `Any` JSON values and map keys

- C# `Dictionary<FName,...>`/`SortedDictionary<FPackageIndex,...>` keys are serialized by
  Newtonsoft's key handling; the JVM registers `KeyDeserializer`s for `FName` (deferred dummy +
  `FNameToBeFilled`, same as the value path) and `FPackageIndex` (raw index), and one for
  `FSoftObjectPath` that parses its `toString()` triple `(PackageName, AssetName, SubPathString)`
  (the JVM serializes `FMetaData.ObjectMetaDataMap`/`RootMetaDataMap` as plain JSON objects, not
  C#'s TMap pair arrays — a pre-existing parity gap not covered by the reference sweep).
- C# `FFormatArgumentValue.Value` (`object`, TypeNameHandling.Objects) is a polymorphic `Any`.
  Jackson never emits `$type` for values behind an `Any`-typed property, so a custom
  `FFormatArgumentValueJsonSerializer`/`FFormatArgumentValueJsonDeserializer` writes the C# shape
  (`$type` + int `Type` + type-id-bearing `Value` via `provider.defaultSerializeValue`) and reads
  it back.

## Opened questions / deferred

- TestAssets corpus commit strategy (LFS vs fixtures-only) — decided when the testing milestone lands.
- GraalVM native-image build for the CLI is optional (M7), not a design constraint.

## Curve support (CUE4Parse derivative — EXC-002)

The curve types in `uassetapi/src/main/kotlin/com/github/jpabscale/uasset4j/curves/` and
`exporttypes/CurveTableExport.kt` are **not** UAssetAPI ports. They are Apache-2.0 **derivative
work of CUE4Parse** (https://github.com/FabianFG/CUE4Parse), added as a deliberate extension beyond
UAssetAPI (which lacks dedicated curve types). Recorded as **EXC-002** in
`docs/parity-exceptions.json`; every derivative file carries the
`//@parity:on EXC-002` / `//@parity:off EXC-002` markers and an attribution header.

### Source layout

| CUE4Parse (C#, Apache-2.0) | Here (Kotlin) |
|---|---|
| `UE4/Objects/Engine/Curves/RealCurve.cs` | `curves/FRealCurve.kt` |
| `UE4/Objects/Engine/Curves/RichCurve.cs` | `curves/FRichCurve.kt`, `curves/FCompressedRichCurve.kt` |
| `UE4/Objects/Engine/Curves/SimpleCurve.cs` | `curves/FSimpleCurve.kt` |
| `UE4/Objects/Engine/Curves/FKeyHandle.cs` | `curves/FKeyHandle.kt` |
| `UE4/Objects/Engine/Curves/FCurveMetaData.cs` | `curves/FCurveMetaData.kt` |
| `UE4/Assets/Exports/Engine/UCurveTable.cs` (+`ECurveTableMode.cs`) | `curves/UCurveTable.kt` + `exporttypes/CurveTableExport.kt` |
| `UE4/Assets/Exports/Texture/UCurveLinearColorAtlas.cs` | `curves/UCurveLinearColorAtlas.kt` |
| `UE4/Objects/Engine/Curves/UCurveVector.cs` / `UCurveLinearColor.cs` | `curves/UCurveVector.kt` / `curves/UCurveLinearColor.kt` |
| `UE4/Objects/Engine/Curves/FAnimCurveType` (in `FCurveMetaData.cs`) | `curves/FCurveMetaData.kt` (`FAnimCurveType`) |

### What stays UAssetAPI-ported (untouched)

`unrealtypes/objects/engine/FRichCurveKey.kt`, `propertytypes/structs/engine/RichCurveKeyPropertyData.kt`,
`StringCurveKeyPropertyData.kt`, `NameCurveKeyPropertyData.kt`, and the curve enums in
`unrealtypes/engineenums/EngineEnums.kt` remain MIT/UAssetAPI parity files and are not modified.

### Mapping conventions (C# → Kotlin, this derivative subset)

- CUE4Parse enums (`ERichCurveExtrapolation`, `ERichCurveCompressionFormat`,
  `ERichCurveKeyTimeCompressionFormat`, `ECurveTableMode`) are ported as `enum class` in the
  `curves` package, **without** the `_MAX` members that the UAssetAPI enums carry.
  `ERichCurveInterpMode`/`TangentMode`/`TangentWeightMode` reuse the existing UAssetAPI enums.
- CUE4Parse structs are `class`es with `var` members (matching the `struct`→class mapping above).
- The `unsafe` pointer adapters in `FCompressedRichCurve` (`IKeyTimeAdapter`/`IKeyDataAdapter`,
  `Quantized16BitKeyTimeAdapter`, `Float32BitKeyTimeAdapter`, `Uniform/Mixed/WeightedKeyDataAdapter`)
  are ported with the same offset math over `ByteArray`; no unsafe code.
- CUE4Parse's `[StructFallback]` property-tagged struct read is ported as the `fromStruct` helpers
  (`FRichCurve.fromStruct`, `FSimpleCurve.fromStruct`, `fromStructRichCurveKey`,
  `fromStructSimpleCurveKey`, and `FRealCurveCommon.populate`) in `curves/CurveStructAccess.kt`,
  which walk a `StructPropertyData.Value` list by property name — matching the tagged/unversioned
  property path CUE4Parse uses for real assets.
- `UCurveTable` row values are property-serialized structs read through `StructPropertyData.Read`
  (like `DataTableExport` rows), not raw binaries.
- The `ShrinkCurveTableSize` gate is applied via
  `asset.GetCustomVersion(GetCustomVersionGuidFromFriendlyName("FFortniteMainBranchObjectVersion"))`
  compared against `FFortniteMainBranchObjectVersion.ShrinkCurveTableSize.ordinal`, matching the
  `.ordinal` convention already used by `FWorldTileInfo.kt`.

### Wire-order reconciliation (verified)

CUE4Parse's `FRichCurveKey(FMutableArchive)` raw constructor reads the six floats **before** the
three mode bytes; uasset4j reads **modes first**. The on-disk bytes (verified against StellarBlade
UE4.26 `UCurveFloat` exports and the byte-identical uasset4j/UAssetAPI round-trip oracle) are
**modes first**, so the new curve readers keep the modes-first order. CUE4Parse reaches its curve
structs via the `[StructFallback]` property-tagged path for real assets, so its raw-archive
constructor order is not observed in practice. See the note at the top of `curves/FRealCurve.kt`.

### JSON shape (defined here, no oracle)

There is no C# oracle for the dedicated curve types, so the JSON shape is ours to define but must
round-trip byte-identically (read → write → read is stable). The curve containers
(`FRichCurve`, `FSimpleCurve`, `FCompressedRichCurve`) serialize as plain beans (Jackson default),
and `CurveTableExport` mirrors `DataTableExport` (`Table` field holding a `UCurveTable` with
`CurveTableMode` + `RowMap`). Registering the new classes in `json/TypeIds.kt` (export + poco lists,
plus the `UCurveTable` mixin) gives them their `$type` ids.

---

## Unity-specific mapping (asset4j additions)

These rows are **asset4j-specific** — they translate the AssetsTools.NET (Unity) C# into Kotlin,
over and above the shared C#→Kotlin construct table above. Add a row here **before** any new
Unity construct is ported.

### IO

| C# | Kotlin |
|---|---|
| `class AssetsFileReader : BinaryReader` (byte stream + `Position` + `BigEndian` flag) | `open class AssetsFileReader(private val data: ByteArray)` with `var position: Int` and `var bigEndian: Boolean`; `readByte()/readBytes(n)` advance `position` (ByteArray-backed, matching uasset4j's `UnrealBinaryReader` shape; no `Stream` indirection). Public name preserved: `ReadInt16/ReadUInt16/ReadInt24/ReadUInt24/ReadInt32/ReadUInt32/ReadInt64/ReadUInt64` return `Short/Int/Int/Long/Int/Long/Long/Long` (uint widened) |
| `AssetsFileReader.ReverseShort/ReverseInt/ReverseLong` (public byte-swap helpers) | public `reverseShort/reverseInt/reverseLong` with the same shift/mask bodies |
| `Align()/Align8()/Align16()` (reader: advance `BaseStream.Position` to next 4/8/16 boundary) | `align()/align8()/align16()` advancing `position` by the same `pad = N - (pos % N); if (pad != N) pos += pad` rule |
| `ReadStringLength(int)` | `readStringLength(n: Int)` = `String(readBytes(n), UTF_8)` |
| `ReadNullTerminated()` (read bytes until 0, UTF-8) | `readNullTerminated()` collecting into a `ByteArrayOutputStream`-equivalent mutable buffer until `0x00`, then UTF-8 decode |
| `static ReadNullTerminatedArray(byte[], uint)` | `readNullTerminatedArray(bytes, pos)` scanning by index |
| `ReadCountString()` (1-byte len) / `ReadCountStringInt16()` / `ReadCountStringInt32()` | `readCountString()`/`readCountStringInt16()`/`readCountStringInt32()` (len + utf8 bytes; `uint`/`ushort` widened) |
| `AssetsFileWriter` (little-endian `BinaryWriter` + `BigEndian` flag) | `class AssetsFileWriter(var data: ByteArray)` appending to the array; `write...` primitives in little-endian; `bigEndian` inverts via `reverseShort/Int/Long` |
| `WriteRawString/WriteCountString/WriteCountStringInt16/WriteCountStringInt32/WriteNullTerminated` | same names, same length/byte semantics; `WriteCountString*` throw when `utf8ByteCount` exceeds the length field (C# creates an exception object without throwing — port throws `IllegalArgumentException` to surface the bug, documented deviation) |
| `Align/Align8/Align16` (writer: pad with `0x00` while `Position % N != 0`) | `align()/align8()/align16()` appending zero bytes until aligned |
| `ReadUInt24`/`ReadInt24` (3 bytes, `byte[]{0,b0,b1,b2}` concat) / `WriteUInt24`/`WriteInt24` (low 3 bytes of 4) | `readUInt24()/readInt24()` and `writeUInt24(v: Int)/writeInt24(v: Int)` with the same concat/skip-msb byte layout |
| `Position` get/set on `BaseStream` | `position` var on the ByteArray reader/writer; bounds checked on write (writer may grow) |

### Bundle (UnityFS)

| C# | Kotlin |
|---|---|
| `AssetBundleFSHeaderFlags` `[Flags]` (CompressedLZMA=0x1, CompressedLZ4=0x2, CompressedLZ4HC=0x4, Uncompressed=0x8) | `class AssetBundleFSHeaderFlags(val value: Long)` + companion `const val` (per the `[Flags]`→value-class row) |
| `AssetBundleFSHeader` (struct: `ReadAssetsFileSignature` checks the `UnityFS\0` magic) | `class AssetBundleFSHeader` with `var` fields; `read/readAssetsFileSignature/write` |
| `AssetBundleHeader` (abstract-ish struct dispatching on signature) | `class AssetBundleHeader` with `var signature: ByteArray`; `read(reader)` returns `AssetBundleFSHeader` when signature is `UnityFS\0`, else a legacy `AssetBundleHeader`; `type` enum (BundleType) via `when (signature)` |
| `AssetBundleBlockInfo` / `AssetBundleDirectoryInfo` / `AssetBundleBlockAndDirInfo` (structs) | `class`es with `var` fields + `read/write` |
| `AssetBundleFile` (container read/write, LZ4/LZMA decompression, `makeDummyFile`) | `class AssetBundleFile`; compression via `org.lz4:lz4-java` (`LZ4FrameInputStream`/`LZ4BlockOutputStream`) and `org.tukaani:xz` (`LZMAInputStream`) — library calls replace the ported LZ4/LZMA C# (plan §11: "algo count is a non-issue") |
| `IAssetBundleCompressProgress` / `AssetBundleLZMAProgress` | `interface AssetBundleCompressProgress` / `object AssetBundleLZMAProgress` (no-op on JVM) |
| `AssetBundleFile.Write` with `CompressType` / `BlockSize` options | `write(writer, compressType, blockSize)`; LZ4 compression via lz4-java `LZ4BlockOutputStream`, LZMA via xz `LZMAOutputStream` (block-level recompression) |

### SerializedFile / type tree

| C# | Kotlin |
|---|---|
| `AssetsFileHeader` (struct: `ReadVersion` gates >= 22 layout) | `class AssetsFileHeader` with `var` fields; `read` gates on `Version` |
| `AssetFileInfo` (`ObjectInfo`: `AbsoluteByteStart`/`ByteSize` lazy alignment) | `class AssetFileInfo` (ported name kept) |
| `AssetsFileExternal` / `AssetsFileExternalType` | `class AssetsFileExternal` / `enum class AssetsFileExternalType` |
| `AssetPPtr` (struct `m_FileID`/`m_PathID`) | `data class AssetPPtr(var m_FileID: Int, var m_PathID: Long)` |
| `AssetsFileMetadata` (types/objects/scripts/externals/refTypes/userInfo) | `class AssetsFileMetadata`; `read` version-gated |
| `TypeTreeNode` (struct, legacy per-node + blob forms) | `data class TypeTreeNode(...)` with the same fields; `read/readBlobNode/write` |
| `TypeTreeNodeFlags` (`[Flags]`, MetaFlags) | `class TypeTreeNodeFlags(val value: Long)` + companion consts |
| `TypeTreeType` (CommonString table + per-type read) | `class TypeTreeType`; the embedded `COMMON_STRING_TABLE` string array ported verbatim as `typetree/CommonString.kt` |
| `TypeTreeBlob.Read` (`tthm` header, format >= 23) | `typetree/TypeTreeBlob.kt` — **only reachable from `ttmapgen`** (ttmap gen), never from `AssetService` |
| `AssetsFile.Read`/`Write` (full file) | `serializedfile/AssetsFile.kt` `read`/`write`; PathId sort + 0x1000 padding rule ported verbatim |
| `Hash128`/`GUID128` | `io/Hash128.kt`/`io/GUID128.kt` `data class` + `parse/format/bytes` (byte layout ported verbatim) |

### Value model (AssetTypeClass)

| C# | Kotlin |
|---|---|
| `AssetTypeValue` (struct wrapping `AssetTypeValueField` + `AssetTypeArrayInfo`) | `class AssetTypeValue` with the same `var` fields |
| `AssetTypeValueField` (tree of typed values; `AsByte/AsInt/.../AsString` accessors, `children`) | `class AssetTypeValueField` with `var children: List<AssetTypeValueField>` and `asByte/asInt/.../asString` accessors |
| `AssetTypeTemplateField` (the type-tree-based template; `MakeValue`, `ReadType`, `ValueType`) | `class AssetTypeTemplateField` |
| `AssetTypeValueIterator` | `class AssetTypeValueIterator` (walk parent/children) |
| `RefTypeManager` / `ManagedReferencesRegistry` | `value/RefTypeManager.kt` (class-database ref-types; v1: minimal/no-op path) |
| `EnumValueTypes` / `AssetTypeArrayInfo` | `enum class EnumValueTypes` / `class AssetTypeArrayInfo` |
| `DummyFieldAccessException` | `class DummyFieldAccessException : Exception` |
| `IMonoBehaviourTemplateGenerator` | `ttmapgen` seam only (the C# oracle harness implements it; no JVM port) |

### ttmap (new format, not a C# port)

- The `ttmap` schema is **new code** (`ttmap/Ttmap.kt`) — not a port. Kotlin `data class`es with
  **default Jackson binding** (`jackson-module-kotlin`, no `$type`, no custom serializers); maps are
  `Map` (LinkedHashMap). gzip layer = `GZIPInputStream/OutputStream`. JSON field order = declaration
  order. Strings inline (no pool — gzip dedups). Dual-key script lookup: `scriptIds` (Hash128
  string) preferred, then `script` (`assembly:namespace.classname`).
- `$type` naming exception: asset4j's JSON uses `asset4j.*` names (this is new code, not a
  UAssetAPI port — documented exception in the plan §3).

### Binary I/O parity (Unity notes)

- Reader is ByteArray-backed like uasset4j's `UnrealBinaryReader`; the plan's `io` package carries
  the statement-parallel `Read*`/`Write*` surface from `AssetsFileReader.cs`/`AssetsFileWriter.cs`.
- `.NET` `BitConverter` little-endian assumptions are handled by explicit byte assembly (same
  pattern as uasset4j). The corpus differential (AssetsTools.NET C# harness) is the byte oracle.
- **UnityFS bundle info is big-endian.** The whole bundle stream is read with `reader.BigEndian =
  true` (set in `AssetBundleHeader.Read`), so the block-and-dir info (`AssetBundleBlockAndDirInfo.Read`)
  and the directory entries are big-endian. The *decompressed file data* inside the blocks is
  little-endian SerializedFile bytes, read via a fresh little-endian `DataReader`. This mirrors the
  C#: `UnpackInfoOnly` sets `memReader.BigEndian = Reader.BigEndian` (true) for the block info, and
  the C# `AssetBundleFile` uses a separate reader for file data.
- **UnityFS LZMA blocks have no end-of-stream marker**, so the xz decoder must call
  `enableRelaxedEndCondition()` before reading (matches the C# `Decoder.Code` which decodes exactly
  `compressedSize - 5` bytes). The LZMA header is 5 bytes (props byte + dict size) for both the
  block-info section and the data blocks — no 8-byte decompressed-size field (that variant only
  appears in old `UnityWeb` bundles, out of scope).
- **LZ4 block format**: Unity stores raw LZ4 blocks (no frame header) per `AssetBundleBlockInfo`,
  decompressed with lz4-java's `fastDecompressor().decompress(src, srcOff, dst, dstOff, dstLen)`
  given the block's decompressed size. The block-info section uses the header's compression type
  (LZ4 for all corpus bundles).

