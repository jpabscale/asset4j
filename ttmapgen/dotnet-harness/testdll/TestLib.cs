using System;
using System.Collections.Generic;

namespace UnityEngine
{
    // stubs so the fixture can derive real MonoBehaviour/ScriptableObject subclasses
    // without referencing the UnityEngine assembly (netstandard2.0 self-contained)
    public class MonoBehaviour { }
    public class ScriptableObject { }
}

namespace MyGame
{
    [Serializable]
    public class Player : UnityEngine.ScriptableObject
    {
        public string m_Name;
        public int m_Level;
        public float m_Speed;
        public bool m_IsActive;
        public List<int> m_Scores;
        public MyGame.Weapon m_Weapon;
        public MyGame.PlayerData m_Data;
    }

    [Serializable]
    public class Weapon : UnityEngine.MonoBehaviour
    {
        public string m_DisplayName;
        public int m_Damage;
    }

    // not a MonoBehaviour/ScriptableObject itself, but reachable transitively from
    // Player.m_Data — auto-enumeration must include it via the field walk
    [Serializable]
    public class PlayerData
    {
        public int m_Hp;
        public string m_Title;
    }
}
