package io.github.liquiHg.hexencrypt.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashSet;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class ClientSpellCache {
    private static Set<String> unlockedSpells = new HashSet<>();

    public static void update(Set<String> spells) {
        unlockedSpells = new HashSet<>(spells);
    }

    public static boolean isUnlocked(String spellName) {
        return unlockedSpells.contains(spellName);
    }
}