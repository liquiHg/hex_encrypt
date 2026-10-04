package io.github.liquiHg.hexencrypt.pigment;

public enum IssSchool {
    ELDRITCH ("eldritch",  0x8A2BE2), // 邪术 - 蓝紫
    NATURE   ("nature",    0x32CD32), // 自然 - 酸橙绿
    LIGHTNING("lightning", 0xFFEE00), // 雷霆 - 亮黄
    ICE      ("ice",       0x9BD8F0), // 冰霜 - 浅冰蓝
    BLOOD    ("blood",     0x8B0000), // 猩红 - 暗红
    EVOCATION("evocation", 0x50C878), // 唤魔 - 翠绿
    HOLY     ("holy",      0xFFD700), // 神圣 - 金色
    ENDER    ("ender",     0x9400D3), // 末影 - 深紫
    FIRE     ("fire",      0xFF4500); // 炽焰 - 橙红

    public final String id;
    public final int color;

    IssSchool(String id, int color) {
        this.id = id;
        this.color = color;
    }
}