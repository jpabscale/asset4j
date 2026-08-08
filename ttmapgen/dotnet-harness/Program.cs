// Copyright (c) 2026 jpabscale — original code (not part of the AssetsTools.NET port).
// The .NET generator bridge (plan §11): drives AssetsTools.NET's MonoCecil / Cpp2IL
// template generators over a subprocess interface and emits ttmap-style JSON.
using AssetsTools.NET;
using AssetsTools.NET.Cpp2IL;
using AssetsTools.NET.Extra;
using LibCpp2IL;
using Mono.Cecil;
using System.Text;
using System.Text.Json;

if (args.Length < 1)
{
    Console.Error.WriteLine("usage: ttmapgen-harness <request.json>");
    return 1;
}

var request = JsonDocument.Parse(File.ReadAllText(args[0])).RootElement;
string mode = request.GetProperty("mode").GetString();

// roundtrip: read a SerializedFile or UnityFS bundle and write it back to bytes, so a
// sweep can diff asset4j's write path against the C# reference byte-for-byte (port-fidelity
// oracle, complementing the UnityPy behavioral oracle in tools/sweep.py).
if (mode == "roundtrip")
{
    string file = request.GetProperty("file").GetString();
    var head = File.ReadAllBytes(file);
    bool isBundle = head.Length >= 7 && Encoding.ASCII.GetString(head, 0, 7) == "UnityFS";
    var reader = new AssetsFileReader(file);
    var ms = new MemoryStream();
    if (isBundle)
    {
        var bf = new AssetBundleFile();
        bf.Read(reader);
        var w = new AssetsFileWriter(ms);
        bf.Write(w);
    }
    else
    {
        var af = new AssetsFile();
        af.Read(reader);
        var w = new AssetsFileWriter(ms);
        af.Write(w);
    }
    var roundTripped = ms.ToArray();
    var outWriter = new Utf8JsonWriter(Console.OpenStandardOutput());
    outWriter.WriteStartObject();
    outWriter.WriteString("bytes", Convert.ToBase64String(roundTripped));
    outWriter.WriteEndObject();
    outWriter.Flush();
    return 0;
}

var writer = new Utf8JsonWriter(Console.OpenStandardOutput(), new JsonWriterOptions { Indented = true });

writer.WriteStartObject();
writer.WriteString("unityVersion", request.TryGetProperty("unityVersion", out var uv) ? uv.GetString() : "6000.0.0f1");
writer.WriteString("gameVersion", request.TryGetProperty("gameVersion", out var gv) ? gv.GetString() : "unknown");
writer.WriteStartObject("types");
writer.WriteStartObject("builtin");
// MonoScript (ClassId 115) native tree — the runtime reads MonoScript objects to
// resolve MonoBehaviour script names (C# reference: AssetHelper.GetAssetsFileScriptInfo),
// so every ttmap must carry this well-known, version-stable tree.
WriteMonoScriptBuiltin(writer);
writer.WriteEndObject();

// script entries (keyed by assembly:namespace.classname) written into "script"
writer.WriteStartObject("script");
if (mode == "dll")
{
    string managedPath = request.GetProperty("managedPath").GetString();
    var gen = new MonoCecilTempGenerator(managedPath);
    var classes = request.GetProperty("classes");
    if (classes.GetArrayLength() == 0)
    {
        // auto-enumerate: every MonoBehaviour/ScriptableObject subclass in the user
        // assemblies (walking the base chain, so LocalAsset<T> scriptable assets count)
        // plus every [Serializable]/enum class reachable transitively from their fields
        classes = EnumerateMonoScriptClasses(managedPath);
    }
    WriteScriptTypes(writer, classes, gen, managedPath);
    gen.Dispose();
}
else if (mode == "il2cpp")
{
    // LibCpp2IL writes init diagnostics to Console.Out; suppress it so the JSON on
    // the raw stdout stream stays clean. The Utf8JsonWriter targets
    // Console.OpenStandardOutput() directly and is unaffected by this.
    var originalOut = Console.Out;
    Console.SetOut(TextWriter.Null);
    try
    {
        string globalMetadata = request.GetProperty("globalMetadata").GetString();
        string gameAssembly = request.GetProperty("gameAssembly").GetString();
        var gen = new Cpp2IlTempGenerator(globalMetadata, gameAssembly);
        var classes = request.GetProperty("classes");
        if (classes.GetArrayLength() == 0)
        {
            // auto-enumerate user MonoBehaviour/ScriptableObject types from the IL2CPP metadata
            classes = EnumerateIl2CppScriptClasses(gen);
        }
        WriteScriptTypesCpp2Il(writer, classes, gen);
        gen.Dispose();
    }
    finally
    {
        Console.SetOut(originalOut);
    }
}
else
{
    Console.Error.WriteLine("unknown mode " + mode);
    return 1;
}
writer.WriteEndObject(); // script

// scriptIds (empty in the harness; populated by the Kotlin side from MonoScript assets)
writer.WriteStartObject("scriptIds");
writer.WriteEndObject();
writer.WriteEndObject(); // types
writer.WriteEndObject(); // root
writer.Flush();
return 0;

// Enumerates every MonoBehaviour/ScriptableObject subclass in the user assemblies
// (Assembly-CSharp and friends), plus every [Serializable]/enum class reachable
// transitively from those scripts' fields, so the Mono `dll` path needs no class list.
// Base chains are walked (not just direct base), so generic ScriptableObject asset
// containers like HammerGameBase.LocalAsset<T> are recognized as scriptable objects.
JsonElement EnumerateMonoScriptClasses(string managedPath)
{
    var resolver = new DefaultAssemblyResolver();
    resolver.AddSearchDirectory(managedPath);
    var userTypes = new Dictionary<string, TypeDefinition>();
    foreach (var dll in Directory.GetFiles(managedPath, "*.dll"))
    {
        string name = Path.GetFileNameWithoutExtension(dll);
        if (!name.StartsWith("Assembly-CSharp")) continue;
        try
        {
            var a = AssemblyDefinition.ReadAssembly(dll, new ReaderParameters { AssemblyResolver = resolver });
            foreach (var ty in a.MainModule.Types)
                userTypes[ty.FullName] = ty;
        }
        catch (Exception) { }
    }

    // roots: base chain reaches MonoBehaviour or ScriptableObject
    var roots = new List<TypeDefinition>();
    foreach (var ty in userTypes.Values)
    {
        var b = ty.BaseType?.Resolve();
        while (b != null)
        {
            if (b.FullName == "UnityEngine.MonoBehaviour" || b.FullName == "UnityEngine.ScriptableObject")
            {
                roots.Add(ty);
                break;
            }
            b = b.BaseType?.Resolve();
        }
    }

    // reduce a field type to the underlying user type (unwrap arrays/generics/byrefs)
    static TypeDefinition FieldUserType(TypeReference ft, Dictionary<string, TypeDefinition> ut)
    {
        var t = ft;
        while (t is ArrayType at) t = at.ElementType;
        while (t is GenericInstanceType git)
        {
            var elem = git.ElementType.Resolve();
            if (elem != null && ut.ContainsKey(elem.FullName)) t = elem;
            else if (git.GenericArguments.Count > 0) t = git.GenericArguments[0];
            else return null;
        }
        if (t is ByReferenceType brt) t = brt.ElementType;
        return t.Resolve();
    }

    // BFS: roots + transitively reachable [Serializable]/enum user classes via fields
    var seen = new HashSet<string>(roots.Select(r => r.FullName));
    var queue = new Queue<TypeDefinition>(roots);
    var all = new List<TypeDefinition>(roots);
    while (queue.Count > 0)
    {
        var ty = queue.Dequeue();
        foreach (var f in ty.Fields)
        {
            var fd = FieldUserType(f.FieldType, userTypes);
            if (fd == null || !userTypes.ContainsKey(fd.FullName)) continue;
            if (fd.FullName == ty.FullName) continue;
            bool follow = fd.IsEnum || fd.IsSerializable ||
                (fd.IsClass && fd.BaseType?.FullName != "System.Object" && fd.BaseType?.FullName != "System.ValueType");
            if (follow && seen.Add(fd.FullName))
            {
                all.Add(fd);
                queue.Enqueue(fd);
            }
        }
    }

    var arr = new List<Dictionary<string, string>>();
    foreach (var t in all)
    {
        string ns = t.FullName.Contains('.') ? t.FullName.Substring(0, t.FullName.LastIndexOf('.')) : "";
        string name = t.FullName.Substring(t.FullName.LastIndexOf('.') + 1);
        // Emit the MonoBehaviour base for every script type: Unity serializes script
        // assets as MonoBehaviour-shaped objects in practice (even for ScriptableObject-
        // derived classes whose base chain goes through generic containers like
        // HammerGameBase.LocalAsset<T>), so the MonoBehaviour header (m_GameObject +
        // m_Enabled + m_Script + m_Name) is the shape that matches the on-disk bytes.
        arr.Add(new Dictionary<string, string>
        {
            { "assembly", "Assembly-CSharp" },
            { "namespace", ns },
            { "className", name },
            { "scriptableObject", "false" }
        });
    }
    using (var ms = new MemoryStream())
    {
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartArray();
            foreach (var d in arr)
            {
                w.WriteStartObject();
                w.WriteString("assembly", d["assembly"]);
                w.WriteString("namespace", d["namespace"]);
                w.WriteString("className", d["className"]);
                w.WriteBoolean("scriptableObject", d.TryGetValue("scriptableObject", out var v) && v == "true");
                w.WriteEndObject();
            }
            w.WriteEndArray();
        }
        return JsonDocument.Parse(ms.ToArray()).RootElement.Clone();
    }
}

void WriteScriptTypes(Utf8JsonWriter writer, JsonElement classes, MonoCecilTempGenerator gen, string managedPath)
{
    var unityVersion = new UnityVersion(request.TryGetProperty("unityVersion", out var ruv) ? ruv.GetString() : "6000.0.0f1");
    foreach (var cls in classes.EnumerateArray())
    {
        string assembly = cls.GetProperty("assembly").GetString();
        string ns = cls.GetProperty("namespace").GetString();
        string name = cls.GetProperty("className").GetString();
        bool isScriptableObject = cls.TryGetProperty("scriptableObject", out var so) && so.GetBoolean();
        // same MonoBehaviour/ScriptableObject base synthesis as the IL2CPP path (see
        // WriteScriptTypesCpp2Il): the C# reference merges the CLDB base into the
        // generator's script fields, so each emitted tree is self-contained.
        var baseChildren = new List<AssetTypeTemplateField>
        {
            CommonMonoTemplateHelper.PPtr("m_Script", "MonoScript", unityVersion),
            CommonMonoTemplateHelper.String("m_Name")
        };
        if (!isScriptableObject)
        {
            baseChildren.Insert(0, CommonMonoTemplateHelper.PPtr("m_GameObject", "GameObject", unityVersion));
            baseChildren.Insert(1, CommonMonoTemplateHelper.Bool("m_Enabled"));
        }
        var baseField = new AssetTypeTemplateField
        {
            Name = "Base",
            Type = name,
            ValueType = AssetValueType.None,
            IsArray = false,
            IsAligned = false,
            HasValue = false,
            Children = baseChildren
        };
        AssetTypeTemplateField result;
        try
        {
            result = gen.GetTemplateField(baseField, assembly, ns, name, unityVersion);
        }
        catch (Exception)
        {
            // unresolvable type in this assembly set; skip (mirrors LoadTypeTreeBlob's
            // "skip if we can't read it" for unreadable blobs)
            continue;
        }
        if (result == null)
            continue;
        WriteTemplate(writer, assembly, ns, name, result);
    }
}

void WriteScriptTypesCpp2Il(Utf8JsonWriter writer, JsonElement classes, Cpp2IlTempGenerator gen)
{
    var unityVersion = new UnityVersion(request.TryGetProperty("unityVersion", out var ruv) ? ruv.GetString() : "6000.0.0f1");
    gen.SetUnityVersion(unityVersion);
    foreach (var cls in classes.EnumerateArray())
    {
        string assembly = cls.GetProperty("assembly").GetString();
        string ns = cls.GetProperty("namespace").GetString();
        string name = cls.GetProperty("className").GetString();
        bool isScriptableObject = cls.TryGetProperty("scriptableObject", out var so) && so.GetBoolean();
        // The C# reference merges the CLDB's MonoBehaviour/ScriptableObject base tree
        // into the generator's script fields (AssetsManager.CreateTemplateBaseField).
        // We synthesize that base here so each emitted script tree is self-contained:
        //   MonoBehaviour: m_GameObject PPtr, m_Enabled bool, m_Script PPtr, m_Name string
        //   ScriptableObject: m_Script PPtr, m_Name string
        var baseChildren = new List<AssetTypeTemplateField>
        {
            CommonMonoTemplateHelper.PPtr("m_Script", "MonoScript", unityVersion),
            CommonMonoTemplateHelper.String("m_Name")
        };
        if (!isScriptableObject)
        {
            baseChildren.Insert(0, CommonMonoTemplateHelper.PPtr("m_GameObject", "GameObject", unityVersion));
            baseChildren.Insert(1, CommonMonoTemplateHelper.Bool("m_Enabled"));
        }
        var baseField = new AssetTypeTemplateField
        {
            Name = "Base",
            Type = name,
            ValueType = AssetValueType.None,
            IsArray = false,
            IsAligned = false,
            HasValue = false,
            Children = baseChildren
        };
        AssetTypeTemplateField result;
        try
        {
            result = gen.GetTemplateField(baseField, assembly, ns, name, unityVersion);
        }
        catch (Exception)
        {
            continue;
        }
        if (result == null)
            continue;
        WriteTemplate(writer, assembly, ns, name, result);
    }
}

// Enumerates user-defined MonoBehaviour/ScriptableObject subclasses in Assembly-CSharp
// (and any other user assemblies) so the Kotlin side doesn't need a class list for IL2CPP.
JsonElement EnumerateIl2CppScriptClasses(Cpp2IlTempGenerator gen)
{
    var unityVersion = new UnityVersion(request.TryGetProperty("unityVersion", out var ruv) ? ruv.GetString() : "6000.0.0f1");
    gen.SetUnityVersion(unityVersion);
    gen.InitializeCpp2IL();
    var meta = LibCpp2IlMain.TheMetadata;
    var arr = new List<Dictionary<string, string>>();
    foreach (var asm in meta.AssemblyDefinitions)
    {
        string asmName = asm.AssemblyName.Name;
        if (asmName == "Assembly-CSharp" || asmName.StartsWith("Assembly-CSharp"))
        {
            foreach (var t in asm.Image.Types)
            {
                string baseName = t.BaseType?.baseType?.FullName;
                if (baseName == "UnityEngine.MonoBehaviour" || baseName == "UnityEngine.ScriptableObject")
                {
                    string ns = t.FullName.Contains('.') ? t.FullName.Substring(0, t.FullName.LastIndexOf('.')) : "";
                    string name = t.FullName.Substring(t.FullName.LastIndexOf('.') + 1);
                    arr.Add(new Dictionary<string, string>
                    {
                        { "assembly", asmName },
                        { "namespace", ns },
                        { "className", name },
                        { "scriptableObject", baseName == "UnityEngine.ScriptableObject" ? "true" : "false" }
                    });
                }
            }
        }
    }
    var elem = new JsonElement();
    using (var ms = new MemoryStream())
    {
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartArray();
            foreach (var d in arr)
            {
                w.WriteStartObject();
                w.WriteString("assembly", d["assembly"]);
                w.WriteString("namespace", d["namespace"]);
                w.WriteString("className", d["className"]);
                w.WriteBoolean("scriptableObject", d.TryGetValue("scriptableObject", out var v) && v == "true");
                w.WriteEndObject();
            }
            w.WriteEndArray();
        }
        elem = JsonDocument.Parse(ms.ToArray()).RootElement;
    }
    return elem.Clone();
}

void WriteTemplate(Utf8JsonWriter writer, string assembly, string ns, string name, AssetTypeTemplateField field)
{
    string key = assembly + ":" + ns + "." + name;
    writer.WritePropertyName(key);
    writer.WriteStartObject();
    writer.WriteStartArray("nodes");
    WriteNode(writer, field, 0);
    writer.WriteEndArray();
    writer.WriteEndObject();
}

void WriteNode(Utf8JsonWriter writer, AssetTypeTemplateField field, int level)
{
    writer.WriteStartObject();
    writer.WriteNumber("version", field.Version);
    writer.WriteNumber("level", level);
    writer.WriteNumber("typeFlags", field.IsArray ? 1 : 0);
    writer.WriteString("type", field.Type);
    writer.WriteString("name", field.Name);
    writer.WriteNumber("byteSize", -1);
    writer.WriteNumber("index", 0);
    writer.WriteNumber("metaFlags", field.IsAligned ? 0x4000 : 0);
    writer.WriteNumber("refTypeHash", 0);
    writer.WriteEndObject();

    foreach (var child in field.Children)
        WriteNode(writer, child, level + 1);
}

// MonoScript (115) native tree, version-stable across Unity 5+:
//   m_Name, m_ExecutionOrder (int), m_PropertiesHash (Hash128 = 16 UInt8),
//   m_ClassName, m_Namespace, m_AssemblyName
// Mirrors the shape produced by the class database (verified against UnityDataTools
// v23 tthm blob "115" entry).
void WriteMonoScriptBuiltin(Utf8JsonWriter writer)
{
    writer.WritePropertyName("115");
    writer.WriteStartObject();
    writer.WriteStartArray("nodes");
    WriteBuiltinNode(writer, "MonoScript", "Base", 0, 0);
    WriteBuiltinStringNode(writer, "m_Name", 1);
    WriteBuiltinNode(writer, "int", "m_ExecutionOrder", 1, 0);
    WriteBuiltinNode(writer, "Hash128", "m_PropertiesHash", 1, 0);
    for (int i = 0; i < 16; i++)
        WriteBuiltinNode(writer, "UInt8", $"bytes[{i}]", 2, 0);
    WriteBuiltinStringNode(writer, "m_ClassName", 1);
    WriteBuiltinStringNode(writer, "m_Namespace", 1);
    WriteBuiltinStringNode(writer, "m_AssemblyName", 1);
    writer.WriteEndArray();
    writer.WriteEndObject();
}

void WriteBuiltinNode(Utf8JsonWriter writer, string type, string name, int level, int typeFlags)
{
    writer.WriteStartObject();
    writer.WriteNumber("version", 1);
    writer.WriteNumber("level", level);
    writer.WriteNumber("typeFlags", typeFlags);
    writer.WriteString("type", type);
    writer.WriteString("name", name);
    writer.WriteNumber("byteSize", -1);
    writer.WriteNumber("index", 0);
    writer.WriteNumber("metaFlags", 0);
    writer.WriteNumber("refTypeHash", 0);
    writer.WriteEndObject();
}

void WriteBuiltinStringNode(Utf8JsonWriter writer, string name, int level)
{
    WriteBuiltinNode(writer, "string", name, level, 0);
    WriteBuiltinNode(writer, "Array", "Array", level + 1, 1);
    WriteBuiltinNode(writer, "int", "size", level + 2, 0);
    WriteBuiltinNode(writer, "char", "data", level + 2, 0);
}
