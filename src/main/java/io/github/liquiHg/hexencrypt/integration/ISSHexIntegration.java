package io.github.liquiHg.hexencrypt.integration;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.actions.OpCastContinuousIronsSpell;
import io.github.liquiHg.hexencrypt.actions.OpCastIronsSpell;
import io.github.liquiHg.hexencrypt.actions.OpCastIronsSpellWithTarget;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.RegisterEvent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Mod.EventBusSubscriber(modid = HexEncrypt.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ISSHexIntegration {

    public static final List<String> REGISTERED_ISS_SPELL_IDS = new ArrayList<>();
    private static final Set<String> USED_SIGNATURES = new HashSet<>();

    private static final List<AbstractSpell> PENDING_SPELLS = new ArrayList<>();

    private static final int MAX_SUFFIX_LEN = 6;

    // ==================== 官能团段位定义 ====================

    private static final String SCHOOL_ELDRITCH  = "aedwwedewaqawqqaqq";
    private static final String SCHOOL_NATURE    = "wqaqwawqaqw";
    private static final String SCHOOL_LIGHTNING = "qeaqadeedew";
    private static final String SCHOOL_ICE       = "eadaedeadaedeadae";
    private static final String SCHOOL_BLOOD     = "edewqaqqqeeeeqeweweweweweddw";
    private static final String SCHOOL_EVOCATION = "eeeaaeeedewwewqaaedwaeadwe";
    private static final String SCHOOL_HOLY      = "adeaaedaqadeaaeda";
    private static final String SCHOOL_ENDER     = "qdeddwaqwqqqwq";
    private static final String SCHOOL_FIRE      = "aaqawawa";
    private static final String SCHOOL_FALLBACK  = "wdw";

    private static final String TARGET_SELF   = "qaq";
    private static final String TARGET_OTHER  = "ede";
    private static final String TARGET_SUMMON = "aqqa";

    private static final String CAST_NONE       = "eqaa";
    private static final String CAST_INSTANT    = "www";
    private static final String CAST_CONTINUOUS = "qqqqq";
    private static final String CAST_LONG       = "qqqqqaw";

    private static final Set<String> SELF_TARGET_SPELLS = Set.of(
            "heal", "greater_heal", "haste", "fortify",
            "blessing_of_life", "shield", "evasion",
            "invisibility", "oakskin", "cleanse", "angel_wing",
            "frost_step", "blood_step", "teleport", "recall",
            "abyssal_shroud", "planar_sight", "gluttony",
            "heartstop", "acupuncture", "ascension", "charge",
            "counterspell", "pocket_dimension", "cloud_of_regeneration",
            "healing_circle", "wisp", "divine_smite"
    );

    /**
     * 手动强制名单：出现这里就强制走 WithTarget。
     * 一般不需要维护，自动检测覆盖大部分情况。只在自动检测漏了时补。
     */
    private static final Set<String> FORCE_WITH_TARGET = Set.of(
            // 暂无
    );

    /**
     * 手动排除名单：即使自动检测命中，也强制不走 WithTarget。
     * 用于个别自动化判定失误的情况。
     */
    private static final Set<String> NEVER_WITH_TARGET = Set.of(
            // 暂无
    );

    // ==================== ASM 扫描 ====================

    /** 目标数据基类，简称"简单目标"。 */
    private static final String TARGET_ENTITY_CAST_DATA =
            "io/redspace/ironsspellbooks/capabilities/magic/TargetEntityCastData";

    /**
     * 复杂目标类型：法术引用了这些类型时，说明它的 onCast 期望的是经过转换的数据，
     * 而不是裸的 TargetEntityCastData。这种法术不能走 WithTarget。
     */
    private static final Set<String> COMPLEX_CAST_DATA_TYPES = Set.of(
            "io/redspace/ironsspellbooks/spells/TargetedTargetAreaCastData",
            "io/redspace/ironsspellbooks/capabilities/magic/TelekinesisData",
            "io/redspace/ironsspellbooks/capabilities/magic/ChainCastData",
            "io/redspace/ironsspellbooks/capabilities/magic/QuickCastData"
    );

    // ==================== 阶段 1：捕获并缓存法术 ====================

    @SubscribeEvent
    public static void onRegisterSpells(RegisterEvent event) {
        if (!event.getRegistryKey().equals(SpellRegistry.SPELL_REGISTRY_KEY)) return;

        HexEncrypt.LOGGER.info("[Hex加密] 捕获到 ISS 法术注册事件，开始缓存法术列表...");

        var spellRegistry = SpellRegistry.REGISTRY.get();
        if (spellRegistry == null) {
            HexEncrypt.LOGGER.error("[Hex加密] 无法获取 ISS 法术注册表！");
            return;
        }

        PENDING_SPELLS.clear();
        for (ResourceLocation id : spellRegistry.getKeys()) {
            AbstractSpell spell = SpellRegistry.getSpell(id);
            if (spell != null && spell != SpellRegistry.none() && spell.isEnabled()) {
                PENDING_SPELLS.add(spell);
            }
        }
        HexEncrypt.LOGGER.info("[Hex加密] 缓存了 {} 个待解析的 ISS 法术。", PENDING_SPELLS.size());
    }

    // ==================== 阶段 2：CommonSetup 中解析并注册 ====================

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ISSHexIntegration::registerAllHexActions);
    }

    private static void registerAllHexActions() {
        if (PENDING_SPELLS.isEmpty()) {
            HexEncrypt.LOGGER.warn("[Hex加密] 无待处理法术，跳过 Hex 动作注册。");
            return;
        }

        HexEncrypt.LOGGER.info("[Hex加密] 开始为 {} 个 ISS 法术注册 Hex 动作...", PENDING_SPELLS.size());

        USED_SIGNATURES.clear();
        REGISTERED_ISS_SPELL_IDS.clear();

        Registry<ActionRegistryEntry> actionRegistry = IXplatAbstractions.INSTANCE.getActionRegistry();

        int success = 0;
        int withTargetCount = 0;
        int continuousCount = 0;
        int normalCount = 0;

        Map<String, String> actionKindLog = new HashMap<>();

        for (AbstractSpell spell : PENDING_SPELLS) {
            ResourceLocation spellId = spell.getSpellResource();
            String spellName = spell.getSpellName();
            ResourceLocation hexId = new ResourceLocation(HexEncrypt.MODID, "iss_" + spellName);

            HexPattern pattern = generateStructuredPattern(spell, hexId.toString());

            boolean withTarget;
            if (FORCE_WITH_TARGET.contains(spellName)) {
                withTarget = true;
            } else if (NEVER_WITH_TARGET.contains(spellName)) {
                withTarget = false;
            } else {
                withTarget = detectSpellIsSimpleTarget(spell);
            }

            Action action;
            String kind;
            if (withTarget) {
                action = new OpCastIronsSpellWithTarget(spellId);
                kind = "WithTarget";
                withTargetCount++;
            } else if (spell.getCastType() == CastType.CONTINUOUS) {
                action = new OpCastContinuousIronsSpell(spellId);
                kind = "Continuous";
                continuousCount++;
            } else {
                action = new OpCastIronsSpell(spellId);
                kind = "Normal";
                normalCount++;
            }
            actionKindLog.put(spellName, kind);

            try {
                Registry.register(actionRegistry, hexId, new ActionRegistryEntry(pattern, action));
                REGISTERED_ISS_SPELL_IDS.add(hexId.toString());
                success++;
            } catch (IllegalStateException e) {
                HexEncrypt.LOGGER.error(
                        "[Hex加密] 无法注册 {}：Hex 动作注册表已冻结（时机过晚）。", hexId, e);
            } catch (Throwable t) {
                HexEncrypt.LOGGER.error("[Hex加密] 注册 {} 时发生未知错误。", hexId, t);
            }
        }

        HexEncrypt.LOGGER.info("[Hex加密] 成功注册了 {}/{} 个 ISS 法术，唯一签名数 = {}。",
                success, PENDING_SPELLS.size(), USED_SIGNATURES.size());
        HexEncrypt.LOGGER.info("[Hex加密] 分派统计：WithTarget={} Continuous={} Normal={}",
                withTargetCount, continuousCount, normalCount);

        List<String> withTargetNames = new ArrayList<>();
        for (Map.Entry<String, String> e : actionKindLog.entrySet()) {
            if ("WithTarget".equals(e.getValue())) withTargetNames.add(e.getKey());
        }
        withTargetNames.sort(String::compareTo);
        HexEncrypt.LOGGER.info("[Hex加密] 走 WithTarget 操作符的法术: {}", withTargetNames);

        printReminder();
    }

    // ==================== 主流程 ====================

    private static HexPattern generateStructuredPattern(AbstractSpell spell, String hexId) {
        String school = getSchoolSegment(spell);
        String target = getTargetSegment(classifyTarget(spell));
        String cast   = getCastSegment(spell.getCastType());
        String base   = school + target + cast;

        for (int suffixLen = 0; suffixLen <= MAX_SUFFIX_LEN; suffixLen++) {
            String suffix = suffixLen == 0 ? "" : hashSuffix(spell.getSpellName(), suffixLen);
            HexPattern pattern;
            try {
                pattern = HexPattern.fromAnglesUnchecked(base + suffix, HexDir.EAST);
            } catch (IllegalStateException | IllegalArgumentException e) {
                continue;
            }
            if (USED_SIGNATURES.add(pattern.anglesSignature())) {
                HexEncrypt.LOGGER.debug("[Hex加密] {} 使用签名，长度={}", hexId, base.length() + suffixLen);
                return pattern;
            }
        }

        for (int attempt = 0; attempt < 64; attempt++) {
            String suffix = hashSuffix(spell.getSpellName() + "#" + attempt, MAX_SUFFIX_LEN);
            HexPattern pattern;
            try {
                pattern = HexPattern.fromAnglesUnchecked(base + suffix, HexDir.EAST);
            } catch (IllegalStateException | IllegalArgumentException e) {
                continue;
            }
            if (USED_SIGNATURES.add(pattern.anglesSignature())) {
                HexEncrypt.LOGGER.debug("[Hex加密] {} 使用扰动签名，attempt={}", hexId, attempt);
                return pattern;
            }
        }

        HexEncrypt.LOGGER.warn("[Hex加密] {} 无法生成唯一签名，使用兜底", hexId);
        try {
            return HexPattern.fromAnglesUnchecked(base + hashSuffix(spell.getSpellName(), MAX_SUFFIX_LEN), HexDir.EAST);
        } catch (Exception e) {
            return HexPattern.fromAnglesUnchecked("waadwawdaaweewq", HexDir.EAST);
        }
    }

    private static String hashSuffix(String spellName, int len) {
        long h = mixSeed(spellName);
        char[] turns = {'w', 'e', 'q', 'd', 'a'};
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(turns[(int) Math.floorMod(h, 5)]);
            h = h / 5 ^ 0x9E3779B97F4A7C15L;
        }
        return sb.toString();
    }

    // ==================== 学派识别（读配置文件） ====================

    private static String getSchoolSegment(AbstractSpell spell) {
        String path = ISSHexConfig.getSchoolPath(spell);
        if (path == null) {
            HexEncrypt.LOGGER.debug(
                    "[Hex加密] 未在配置中找到法术 {} 的学派，使用 FALLBACK。",
                    spell.getSpellName());
            return SCHOOL_FALLBACK;
        }
        return schoolPathToSegment(path);
    }

    private static String schoolPathToSegment(String path) {
        return switch (path.toLowerCase(Locale.ROOT)) {
            case "eldritch"      -> SCHOOL_ELDRITCH;
            case "nature"        -> SCHOOL_NATURE;
            case "lightning"     -> SCHOOL_LIGHTNING;
            case "ice", "frost"  -> SCHOOL_ICE;
            case "blood"         -> SCHOOL_BLOOD;
            case "evocation"     -> SCHOOL_EVOCATION;
            case "holy"          -> SCHOOL_HOLY;
            case "ender"         -> SCHOOL_ENDER;
            case "fire"          -> SCHOOL_FIRE;
            default              -> SCHOOL_FALLBACK;
        };
    }

    // ==================== 自动检测：法术是否能用 WithTarget ====================

    /**
     * 用 ASM 扫描法术类的全部方法，收集它引用的所有 CastData 类型。
     *
     * 判定规则：
     *   引用了 TARGET_ENTITY_CAST_DATA，且没有引用任何 COMPLEX_CAST_DATA_TYPES
     *   → 简单目标法术，可以用 OpCastIronsSpellWithTarget。
     *
     * 这个规则比"只看 checkPreCastConditions"更准：
     *   - sacrifice/blight：只引用 TargetEntityCastData → 命中
     *   - haste/slow：引用 TargetEntityCastData + TargetedTargetAreaCastData → 排除
     *   - telekinesis：引用 TargetEntityCastData + TelekinesisData → 排除
     */
    private static boolean detectSpellIsSimpleTarget(AbstractSpell spell) {
        Class<?> cls = spell.getClass();
        String resourceName = cls.getSimpleName() + ".class";

        try (InputStream is = cls.getResourceAsStream(resourceName)) {
            if (is == null) {
                HexEncrypt.LOGGER.debug(
                        "[Hex加密][检测] 无法读取 {}.class，跳过检测", cls.getName());
                return false;
            }

            ClassReader cr = new ClassReader(is);
            Set<String> referenced = new HashSet<>();

            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            // 包含 NEW、CHECKCAST、INSTANCEOF
                            if (TARGET_ENTITY_CAST_DATA.equals(type)
                                    || COMPLEX_CAST_DATA_TYPES.contains(type)) {
                                referenced.add(type);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            boolean hasBasic = referenced.contains(TARGET_ENTITY_CAST_DATA);
            boolean hasComplex = false;
            for (String t : referenced) {
                if (COMPLEX_CAST_DATA_TYPES.contains(t)) {
                    hasComplex = true;
                    break;
                }
            }

            boolean result = hasBasic && !hasComplex;
            if (result) {
                HexEncrypt.LOGGER.debug(
                        "[Hex加密][检测] {} → WithTarget（引用类型: {}）",
                        spell.getSpellName(), referenced);
            } else if (hasBasic) {
                HexEncrypt.LOGGER.debug(
                        "[Hex加密][检测] {} → 非 WithTarget（引用类型: {}）",
                        spell.getSpellName(), referenced);
            }
            return result;

        } catch (Throwable t) {
            HexEncrypt.LOGGER.debug(
                    "[Hex加密][检测] 检测 {} 时出错，默认不用 WithTarget: {}",
                    spell.getSpellName(), t.toString());
            return false;
        }
    }

    // ==================== 其他分类 ====================

    private static String getCastSegment(CastType castType) {
        if (castType == null) return CAST_NONE;
        return switch (castType) {
            case NONE       -> CAST_NONE;
            case INSTANT    -> CAST_INSTANT;
            case CONTINUOUS -> CAST_CONTINUOUS;
            case LONG       -> CAST_LONG;
        };
    }

    private static String classifyTarget(AbstractSpell spell) {
        String n = spell.getSpellName().toLowerCase(Locale.ROOT);
        if (n.contains("summon") || n.contains("raise_dead")) return "SUMMON";
        if (SELF_TARGET_SPELLS.contains(n)) return "SELF";
        return "OTHER";
    }

    private static String getTargetSegment(String target) {
        return switch (target) {
            case "SELF"   -> TARGET_SELF;
            case "SUMMON" -> TARGET_SUMMON;
            default       -> TARGET_OTHER;
        };
    }

    // ==================== 工具 ====================

    private static long mixSeed(String s) {
        long seed = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < s.length(); i++) {
            seed = seed * 31L + s.charAt(i);
            seed ^= seed >>> 27;
            seed *= 0x94D049BB133111EBL;
        }
        return seed;
    }

    private static void printReminder() {
        HexEncrypt.LOGGER.info("======================================================================");
        HexEncrypt.LOGGER.info("[Hex加密] 本模组使用“官能团前缀 + hash 后缀”方案生成图案；");
        HexEncrypt.LOGGER.info("[Hex加密] 每个 ISS 动作都拥有唯一签名，不再依赖打乱。");
        HexEncrypt.LOGGER.info("[Hex加密] 已注册的 ISS 动作数：{}", REGISTERED_ISS_SPELL_IDS.size());
        HexEncrypt.LOGGER.info("======================================================================");
    }
}