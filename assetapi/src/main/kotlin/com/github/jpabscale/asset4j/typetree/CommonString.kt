// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/TypeTreeType.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.typetree

/**
 * The string table used for commonly occurring strings in type trees
 * (mirrors the C# `TypeTreeType.COMMON_STRING_TABLE` byte array).
 */
object CommonString {
    val TABLE: ByteArray = (

        "AABB\u0000AnimationClip\u0000AnimationCurve\u0000AnimationState\u0000Array\u0000Base\u0000BitField\u0000bitset\u0000bool\u0000char\u0000ColorRGBA\u0000Component\u0000data\u0000deque\u0000dou" +
        "ble\u0000dynamic_array\u0000FastPropertyName\u0000first\u0000float\u0000Font\u0000GameObject\u0000Generic Mono\u0000GradientNEW\u0000GUID\u0000GUIStyle\u0000int\u0000list\u0000long long" +
        "\u0000map\u0000Matrix4x4f\u0000MdFour\u0000MonoBehaviour\u0000MonoScript\u0000m_ByteSize\u0000m_Curve\u0000m_EditorClassIdentifier\u0000m_EditorHideFlags\u0000m_Enabled\u0000m" +
        "_ExtensionPtr\u0000m_GameObject\u0000m_Index\u0000m_IsArray\u0000m_IsStatic\u0000m_MetaFlag\u0000m_Name\u0000m_ObjectHideFlags\u0000m_PrefabInternal\u0000m_PrefabPar" +
        "entObject\u0000m_Script\u0000m_StaticEditorFlags\u0000m_Type\u0000m_Version\u0000Object\u0000pair\u0000PPtr<Component>\u0000PPtr<GameObject>\u0000PPtr<Material>\u0000PPtr" +
        "<MonoBehaviour>\u0000PPtr<MonoScript>\u0000PPtr<Object>\u0000PPtr<Prefab>\u0000PPtr<Sprite>\u0000PPtr<TextAsset>\u0000PPtr<Texture>\u0000PPtr<Texture2D>\u0000PP" +
        "tr<Transform>\u0000Prefab\u0000Quaternionf\u0000Rectf\u0000RectInt\u0000RectOffset\u0000second\u0000set\u0000short\u0000size\u0000SInt16\u0000SInt32\u0000SInt64\u0000SInt8\u0000staticvector\u0000" +
        "string\u0000TextAsset\u0000TextMesh\u0000Texture\u0000Texture2D\u0000Transform\u0000TypelessData\u0000UInt16\u0000UInt32\u0000UInt64\u0000UInt8\u0000unsigned int\u0000unsigned long" +
        " long\u0000unsigned short\u0000vector\u0000Vector2f\u0000Vector3f\u0000Vector4f\u0000m_ScriptingClassIdentifier\u0000Gradient\u0000Type*\u0000int2_storage\u0000int3_stora" +
        "ge\u0000BoundsInt\u0000m_CorrespondingSourceObject\u0000m_PrefabInstance\u0000m_PrefabAsset\u0000FileSize\u0000Hash128\u0000RenderingLayerMask\u0000fixed_array\u0000" +
        "EntityId\u0000LoadableObjectId\u0000LoadableSceneId\u0000"
    ).toByteArray(Charsets.UTF_8)
}
