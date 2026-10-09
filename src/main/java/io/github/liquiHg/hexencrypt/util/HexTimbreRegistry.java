package io.github.liquiHg.hexencrypt.util;

import at.petrak.hexcasting.common.lib.HexSounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class HexTimbreRegistry {

    private static final Map<String, ResourceLocation> REGISTRY_NAMES = new LinkedHashMap<>();

    static {
        REGISTRY_NAMES.put("bell",        new ResourceLocation("minecraft", "block.note_block.bell"));
        REGISTRY_NAMES.put("chime",       new ResourceLocation("minecraft", "block.note_block.chime"));
        REGISTRY_NAMES.put("flute",       new ResourceLocation("minecraft", "block.note_block.flute"));
        REGISTRY_NAMES.put("guitar",      new ResourceLocation("minecraft", "block.note_block.guitar"));
        REGISTRY_NAMES.put("harp",        new ResourceLocation("minecraft", "block.note_block.harp"));
        REGISTRY_NAMES.put("xylophone",   new ResourceLocation("minecraft", "block.note_block.xylophone"));
        REGISTRY_NAMES.put("iron_xylophone", new ResourceLocation("minecraft", "block.note_block.iron_xylophone"));
        REGISTRY_NAMES.put("cow_bell",    new ResourceLocation("minecraft", "block.note_block.cow_bell"));
        REGISTRY_NAMES.put("didgeridoo",  new ResourceLocation("minecraft", "block.note_block.didgeridoo"));
        REGISTRY_NAMES.put("pling",       new ResourceLocation("minecraft", "block.note_block.pling"));
        REGISTRY_NAMES.put("bass",        new ResourceLocation("minecraft", "block.note_block.bass"));
        REGISTRY_NAMES.put("snare",       new ResourceLocation("minecraft", "block.note_block.snare"));
        REGISTRY_NAMES.put("hat",         new ResourceLocation("minecraft", "block.note_block.hat"));
        REGISTRY_NAMES.put("bit",         new ResourceLocation("minecraft", "block.note_block.bit"));
        REGISTRY_NAMES.put("banjo",       new ResourceLocation("minecraft", "block.note_block.banjo"));
    }

    private HexTimbreRegistry() {}

    /** "default" 始终可用，映射到 Hex 原版的画线音。 */
    public static SoundEvent get(String name) {
        if (name == null || name.isEmpty() || name.equals("default")) {
            return HexSounds.ADD_TO_PATTERN;
        }
        ResourceLocation id = REGISTRY_NAMES.get(name);
        if (id == null) return HexSounds.ADD_TO_PATTERN;
        SoundEvent ev = ForgeRegistries.SOUND_EVENTS.getValue(id);
        return ev != null ? ev : HexSounds.ADD_TO_PATTERN;
    }

//    public static boolean has(String name) {
//        return name == null || name.isEmpty()
//                || name.equals("default")
//                || REGISTRY_NAMES.containsKey(name);
//    }

//    /** 可用音色名（含 "default"）。 */
//    public static Set<String> names() {
//        return Collections.unmodifiableSet(REGISTRY_NAMES.keySet());
//    }

    /** 按索引取音色名，用于 DoubleIota 形式的输入。索引 0 是 "default"。 */
    public static String byIndex(int idx) {
        if (idx == 0) return "default";
        int i = 1;
        for (String n : REGISTRY_NAMES.keySet()) {
            if (i == idx) return n;
            i++;
        }
        return null;
    }

    /** 最大合法索引（含 default）。 */
    public static int maxIndex() {
        return REGISTRY_NAMES.size();   // 0 = default，1..N 对应各音色
    }
}