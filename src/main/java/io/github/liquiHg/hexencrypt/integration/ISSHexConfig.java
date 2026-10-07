package io.github.liquiHg.hexencrypt.integration;

import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ISS 法术 -> 学派 映射配置。
 *
 * 为什么不直接调 spell.getSchoolType()：
 *   SpellConfigManager 的配置在 CommonSetup 阶段还没有加载，
 *   而 Hex 的 action registry 在 CommonSetup 之后就冻结。
 *   两者窗口不重叠，所以图案生成阶段拿不到真实学派。
 *
 * 方案：用配置文件维护映射，图案生成只读这张表，不碰任何 ISS API。
 * 配置文件位置：config/hexencrypt-iss-schools.toml
 */
@Mod.EventBusSubscriber(modid = HexEncrypt.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ISSHexConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENTRIES;

    /** 编译后的映射表，key 同时包含短名和完整 ID。volatile + 替换引用保证读取一致。 */
    private static volatile Map<String, String> SPELL_TO_SCHOOL = Collections.emptyMap();

    private ISSHexConfig() {
    }

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment(
                "ISS 法术 -> 学派 映射表。",
                "格式： \"<spell>=<school>\"",
                "  <spell>  完整 ID 或短名，例如 irons_spellbooks:fireball 或 fireball",
                "  <school> 九大学派之一的路径：fire / ice / lightning / holy / ender / blood / evocation / nature / eldritch",
                "",
                "未在表中出现的法术将使用 FALLBACK 段位。",
                "修改此文件后可以 /reload 或重启游戏生效。"
        );
        ENTRIES = b.defineListAllowEmpty(
                "entries",
                ISSHexConfig::defaultEntries,
                o -> o instanceof String
        );
        SPEC = b.build();
    }

    // ==================== 默认值：106 个 ISS 内置法术 ====================

    private static List<String> defaultEntries() {
        return List.of(
                // ---- blood ----
                "acupuncture=blood",
                "blood_needles=blood",
                "blood_slash=blood",
                "blood_step=blood",
                "devour=blood",
                "heartstop=blood",
                "raise_dead=blood",
                "ray_of_siphoning=blood",
                "wither_skull=blood",
                "sacrifice=blood",

                // ---- ender ----
                "counterspell=ender",
                "dragon_breath=ender",
                "evasion=ender",
                "magic_arrow=ender",
                "magic_missile=ender",
                "starfall=ender",
                "teleport=ender",
                "summon_ender_chest=ender",
                "recall=ender",
                "portal=ender",
                "echoing_strikes=ender",
                "black_hole=ender",
                "summon_swords=ender",
                "shadow_slash=ender",

                // ---- evocation ----
                "chain_creeper=evocation",
                "fang_strike=evocation",
                "fang_ward=evocation",
                "firecracker=evocation",
                "gust=evocation",
                "invisibility=evocation",
                "lob_creeper=evocation",
                "shield=evocation",
                "spectral_hammer=evocation",
                "summon_horse=evocation",
                "summon_vex=evocation",
                "slow=evocation",
                "arrow_volley=evocation",
                "wololo=evocation",
                "throw=evocation",

                // ---- fire ----
                "blaze_storm=fire",
                "burning_dash=fire",
                "fireball=fire",
                "firebolt=fire",
                "fire_breath=fire",
                "magma_bomb=fire",
                "wall_of_fire=fire",
                "heat_surge=fire",
                "flaming_strike=fire",
                "scorch=fire",
                "flaming_barrage=fire",
                "fire_arrow=fire",
                "raise_hell=fire",

                // ---- holy ----
                "angel_wing=holy",
                "blessing_of_life=holy",
                "cloud_of_regeneration=holy",
                "fortify=holy",
                "greater_heal=holy",
                "guiding_bolt=holy",
                "healing_circle=holy",
                "heal=holy",
                "sunbeam=holy",
                "wisp=holy",
                "divine_smite=holy",
                "haste=holy",
                "cleanse=holy",

                // ---- ice ----
                "cone_of_cold=ice",
                "frost_step=ice",
                "ice_block=ice",
                "icicle=ice",
                "summon_polar_bear=ice",
                "ray_of_frost=ice",
                "frostwave=ice",
                "ice_spikes=ice",
                "ice_tomb=ice",
                "snowball=ice",
                "frostbite=ice",

                // ---- lightning ----
                "ascension=lightning",
                "chain_lightning=lightning",
                "charge=lightning",
                "electrocute=lightning",
                "lightning_bolt=lightning",
                "lightning_lance=lightning",
                "shockwave=lightning",
                "thunderstorm=lightning",
                "ball_lightning=lightning",
                "volt_strike=lightning",

                // ---- nature ----
                "acid_orb=nature",
                "blight=nature",
                "poison_arrow=nature",
                "poison_breath=nature",
                "poison_splash=nature",
                "root=nature",
                "spider_aspect=nature",
                "firefly_swarm=nature",
                "oakskin=nature",
                "earthquake=nature",
                "stomp=nature",
                "gluttony=nature",
                "touch_dig=nature",

                // ---- eldritch ----
                "abyssal_shroud=eldritch",
                "sculk_tentacles=eldritch",
                "sonic_boom=eldritch",
                "planar_sight=eldritch",
                "telekinesis=eldritch",
                "eldritch_blast=eldritch",
                "pocket_dimension=eldritch"
        );
    }

    // ==================== 编译与查询 ====================

    @SubscribeEvent
    public static void onLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) rebuild();
    }

    @SubscribeEvent
    public static void onReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) rebuild();
    }

    /** 从配置值重建映射表。可在 CommonSetup 里再调一次作为保险。 */
    public static void rebuild() {
        Map<String, String> map = new HashMap<>();
        List<? extends String> entries = ENTRIES.get();
        if (entries != null) {
            for (Object o : entries) {
                if (!(o instanceof String s)) continue;
                int eq = s.indexOf('=');
                if (eq <= 0 || eq == s.length() - 1) continue;
                String spell = s.substring(0, eq).trim();
                String school = s.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                if (spell.isEmpty() || school.isEmpty()) continue;

                map.put(spell, school);
                // 短名同时写入默认命名空间形式，查询时两种都能命中
                if (!spell.contains(":")) {
                    map.put("irons_spellbooks:" + spell, school);
                }
            }
        }
        SPELL_TO_SCHOOL = Collections.unmodifiableMap(map);
        HexEncrypt.LOGGER.info("[Hex加密] ISS 学派映射已加载，共 {} 条。", map.size());
    }

    /** 查某个法术的学派路径。没有命中返回 null。 */
    public static String getSchoolPath(AbstractSpell spell) {
        Map<String, String> m = SPELL_TO_SCHOOL;
        String shortName = spell.getSpellName();
        String fullId = spell.getSpellResource().toString();
        String s = m.get(shortName);
        if (s == null) s = m.get(fullId);
        return s;
    }
}