package io.github.liquiHg.hexencrypt.integration;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.math.HexAngle;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.actions.*;
import io.github.liquiHg.hexencrypt.api.SpellComparators;
import io.github.liquiHg.hexencrypt.comparator.FunctionalGroupComparator;
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
import java.util.*;

@Mod.EventBusSubscriber(modid = HexEncrypt.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ISSHexIntegration {

    public static final List<String> REGISTERED_ISS_SPELL_IDS = new ArrayList<>();
    private static final Set<String> USED_SIGNATURES = new HashSet<>();

    private static final List<AbstractSpell> PENDING_SPELLS = new ArrayList<>();

    /** 各阶段命中的法术名，用于最后的汇总打印。 */
    private static final List<String> PHASE_A_NAMES = new ArrayList<>();
    private static final List<String> PHASE_B_NAMES = new ArrayList<>();
    private static final List<String> PHASE_C_NAMES = new ArrayList<>();

    /**
     * 全局随机源。不再使用 final —— 每次生成某个法术的图案前，
     * 会用该法术名派生的确定性种子重新初始化。
     * 效果：同一个法术在任何一次启动中都会生成完全相同的图案。
     * 不同法术之间互不干扰（一个法术消耗的随机数不会影响另一个）。
     */
    private static Random RANDOM = new Random();

    // ==================== 官能团段位定义 ====================

    public static final String SCHOOL_ELDRITCH  = "aedwwedewaqawqqaqq";
    public static final String SCHOOL_NATURE    = "wqaqwawqaqw";
    public static final String SCHOOL_LIGHTNING = "qeaqadeedew";
    public static final String SCHOOL_ICE       = "eadaedeadaedeadae";
    public static final String SCHOOL_BLOOD     = "edewqaqqqeeeeqeweweweweweddw";
    public static final String SCHOOL_EVOCATION = "eeeaaeeedewwewqaaedwaeadwe";
    public static final String SCHOOL_HOLY      = "adeaaedaqadeaaeda";
    public static final String SCHOOL_ENDER     = "qdeddwaqwqqqwq";
    public static final String SCHOOL_FIRE      = "aaqawawa";
    public static final String SCHOOL_FALLBACK  = "wdw";

    public static final String TARGET_SELF   = "qaq";
    public static final String TARGET_OTHER  = "ede";
    public static final String TARGET_SUMMON = "aqqa";

    public static final String CAST_NONE       = "eqaa";
    public static final String CAST_INSTANT    = "www";
    public static final String CAST_CONTINUOUS = "qqqqq";
    public static final String CAST_LONG       = "qqqqqaw";

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

    private static final Set<String> FORCE_WITH_TARGET = Set.of();
    private static final Set<String> NEVER_WITH_TARGET = Set.of();

    // ==================== 方向与增量构建辅助 ====================

    private static final HexDir[] ALL_DIRS = {
            HexDir.NORTH_EAST, HexDir.EAST, HexDir.SOUTH_EAST,
            HexDir.SOUTH_WEST, HexDir.WEST, HexDir.NORTH_WEST
    };

    /** 与 ALL_DIRS 顺序一一对应的 (Δq, Δr)。 */
    private static final int[][] DIR_DELTAS = {
            { 1, -1}, { 1,  0}, { 0,  1},
            {-1,  1}, {-1,  0}, { 0, -1}
    };

    /** 阶段 C 随机游走的起始方向（单一视角即可，其它方向等价）。 */
    private static final HexDir BASE_START_DIR = HexDir.NORTH_EAST;

    /**
     * 增量构建图案的辅助类。
     * 维护光标坐标、当前朝向、已走边集合，所有操作 O(1)。
     */
    private static final class Stroker {
        final HexDir startDir;
        final List<HexAngle> angles = new ArrayList<>();
        final Set<Long> lines = new HashSet<>();
        HexDir compass;
        int q = 0, r = 0;

        Stroker(HexDir startDir) {
            this.startDir = startDir;
            this.compass = startDir;
        }

        Stroker copy() {
            Stroker s = new Stroker(this.startDir);
            s.angles.addAll(this.angles);
            s.lines.addAll(this.lines);
            s.q = this.q;
            s.r = this.r;
            s.compass = this.compass;
            return s;
        }

        boolean canAppend(HexDir newDir) {
            if (newDir == compass.rotatedBy(HexAngle.BACK)) return false;
            int d = newDir.ordinal();
            int nq = q + DIR_DELTAS[d][0];
            int nr = r + DIR_DELTAS[d][1];
            int rev = newDir.rotatedBy(HexAngle.BACK).ordinal();
            return !lines.contains(edgeKey(q, r, d))
                    && !lines.contains(edgeKey(nq, nr, rev));
        }

        void append(HexDir newDir) {
            HexAngle angle = newDir.angleFrom(compass);
            int d = newDir.ordinal();
            int nq = q + DIR_DELTAS[d][0];
            int nr = r + DIR_DELTAS[d][1];
            int rev = newDir.rotatedBy(HexAngle.BACK).ordinal();
            lines.add(edgeKey(q, r, d));
            lines.add(edgeKey(nq, nr, rev));
            angles.add(angle);
            q = nq;
            r = nr;
            compass = newDir;
        }
    }

    /**
     * 位打包 edge key：
     *   q ∈ [-2^23, 2^23)，r ∈ [-2^23, 2^23)，dir ∈ [0, 5]
     *   打包进 64 位 long，绝对无碰撞。
     */
    private static long edgeKey(int q, int r, int dirOrdinal) {
        return ((long) (q & 0xFFFFFF) << 28)
                | ((long) (r & 0xFFFFFF) << 4)
                | (dirOrdinal & 0xF);
    }

    // ==================== 一笔画检查（仅用于自定义动作注册） ====================

    private static boolean isDrawableInOneStroke(String signature, HexDir startDir) {
        HexPattern probe = new HexPattern(startDir, new ArrayList<>());
        HexDir compass = startDir;

        for (int i = 0; i < signature.length(); i++) {
            HexAngle angle = HexAngle.Companion.fromChar(signature.charAt(i));
            if (angle == null) return false;
            HexDir newDir = compass.rotatedBy(angle);
            if (!probe.tryAppendDir(newDir)) return false;
            compass = newDir;
        }
        return true;
    }

    private static String uniquenessKey(HexPattern pattern) {
        return pattern.toString();
    }

    // ==================== ASM 扫描 ====================

    private static final String TARGET_ENTITY_CAST_DATA =
            "io/redspace/ironsspellbooks/capabilities/magic/TargetEntityCastData";

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
        //HexEncrypt.LOGGER.info("[Hex加密] onCommonSetup 触发！");
        event.enqueueWork(ISSHexIntegration::registerAllHexActions);
        SpellComparators.register(new FunctionalGroupComparator());
        //HexEncrypt.LOGGER.info("[Hex加密] 比较器已注册，数量={}",
        //        SpellComparators.all().size());
    }

    private static void registerAllHexActions() {
        if (PENDING_SPELLS.isEmpty()) {
            //HexEncrypt.LOGGER.warn("[Hex加密] 无待处理法术，跳过 Hex 动作注册。");
            return;
        }

        HexEncrypt.LOGGER.info("[Hex加密] 开始为 {} 个 ISS 法术注册 Hex 动作...", PENDING_SPELLS.size());
        long tStart = System.currentTimeMillis();

        USED_SIGNATURES.clear();
        REGISTERED_ISS_SPELL_IDS.clear();
        PHASE_A_NAMES.clear();
        PHASE_B_NAMES.clear();
        PHASE_C_NAMES.clear();

        Registry<ActionRegistryEntry> actionRegistry = IXplatAbstractions.INSTANCE.getActionRegistry();

        int success = 0;
        int withTargetCount = 0;
        int continuousCount = 0;
        int normalCount = 0;
        int drawableFailCount = 0;

        Map<String, String> actionKindLog = new HashMap<>();
        List<String> drawableFailures = new ArrayList<>();

        for (AbstractSpell spell : PENDING_SPELLS) {
            ResourceLocation spellId = spell.getSpellResource();
            String spellName = spell.getSpellName();
            ResourceLocation hexId = new ResourceLocation(HexEncrypt.MODID, "iss_" + spellName);

            HexPattern pattern = generateStructuredPattern(spell, hexId.toString());
            if (pattern == null) {
                drawableFailCount++;
                drawableFailures.add(spellName);
                continue;
            }

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
                HexEncrypt.LOGGER.error("[Hex加密] 无法注册 {}：Hex 动作注册表已冻结。", hexId, e);
            } catch (Throwable t) {
                HexEncrypt.LOGGER.error("[Hex加密] 注册 {} 时发生未知错误。", hexId, t);
            }
        }

        long elapsed = System.currentTimeMillis() - tStart;
        HexEncrypt.LOGGER.info("[Hex加密] 成功注册了 {}/{} 个 ISS 法术，唯一签名数 = {}，耗时 {} ms。",
                success, PENDING_SPELLS.size(), USED_SIGNATURES.size(), elapsed);
        HexEncrypt.LOGGER.info("[Hex加密] 分派统计：WithTarget={} Continuous={} Normal={}",
                withTargetCount, continuousCount, normalCount);

        if (!drawableFailures.isEmpty()) {
            HexEncrypt.LOGGER.warn("[Hex加密] {} 个法术找不到可画图案，已跳过: {}",
                    drawableFailCount, drawableFailures);
        }

        List<String> withTargetNames = new ArrayList<>();
        for (Map.Entry<String, String> e : actionKindLog.entrySet()) {
            if ("WithTarget".equals(e.getValue())) withTargetNames.add(e.getKey());
        }
        withTargetNames.sort(String::compareTo);
        HexEncrypt.LOGGER.info("[Hex加密] 走 WithTarget 操作符的法术: {}", withTargetNames);

        // ---- 注册自定义动作 ----
        registerCustomAction(actionRegistry,
                new ResourceLocation(HexEncrypt.MODID, "set_target"),
                "weaqawawaeweweweweweeewaqqqqqwewddaqaew",
                HexDir.SOUTH_EAST,
                new OpSetTargetVec());

        registerCustomAction(actionRegistry,
                new ResourceLocation(HexEncrypt.MODID, "clear_target"),
                "aedwewewewewewqwwdewwewadaewwwdewwewa",
                HexDir.WEST,
                new OpClearTarget());

        registerCustomAction(actionRegistry,
                new ResourceLocation(HexEncrypt.MODID, "set_timbre"),
                "eqqadaa",
                HexDir.NORTH_WEST,
                new OpSetTimbre());

        printPatternSummary();
        printReminder();
    }

    /** 注册一个 Hex 动作的辅助方法。 */
    private static void registerCustomAction(Registry<ActionRegistryEntry> registry,
                                             ResourceLocation id,
                                             String angles,
                                             HexDir startDir,
                                             Action action) {
        if (!isDrawableInOneStroke(angles, startDir)) {
            HexEncrypt.LOGGER.error(
                    "[Hex加密] 自定义动作 {} 的图案无法一笔画出，放弃注册 (angles={}, startDir={})",
                    id, angles, startDir);
            return;
        }

        try {
            HexPattern pattern = HexPattern.fromAnglesUnchecked(angles, startDir);
            String key = uniquenessKey(pattern);
            if (!USED_SIGNATURES.add(key)) {
                HexEncrypt.LOGGER.warn("[Hex加密] 自定义动作 {} 的图案与其他动作撞签名，仍注册。", id);
            }
            Registry.register(registry, id, new ActionRegistryEntry(pattern, action));
            REGISTERED_ISS_SPELL_IDS.add(id.toString());
            HexEncrypt.LOGGER.info("[Hex加密] 注册自定义动作: {} (startDir={}, len={})",
                    id, startDir, angles.length());
        } catch (Throwable t) {
            HexEncrypt.LOGGER.error("[Hex加密] 注册自定义动作 {} 失败", id, t);
        }
    }

    // ==================== 主流程 ====================

    /** 阶段 A 时间预算：完整官能团尝试的最大时长（毫秒）。 */
    private static final long BUDGET_PHASE_A_MS = 800L;
    /** 阶段 B 时间预算：舍弃 school 段后的最大时长（毫秒）。 */
    private static final long BUDGET_PHASE_B_MS = 800L;
    /** 阶段 C 尝试次数：纯随机兜底的最大迭代。 */
    private static final int PHASE_C_ATTEMPTS = 400;

    /** 每个 suffixLen 在每个 startDir 下的最大独立尝试次数。 */
    private static final int TRIALS_PER_SUFFIX_PER_DIR = 100;

    /** 每个 suffixLen 尝试多少个哈希变体。 */
    private static final int SUFFIX_VARIANTS = 8;

    /**
     * 生成图案。返回 null 表示所有尝试都失败，调用方应跳过该法术。
     *
     * 三级降级策略：
     *   A. school + target + cast + hash 后缀；
     *   B. 舍弃 school 段，只保留 target + cast + hash 后缀；
     *   C. 纯随机游走，仅求唯一签名（会输出 WARN，提示语义丢失）。
     *
     * 每个 suffixLen 会遍历 SUFFIX_VARIANTS 个变体，并且跳过
     * 独立自交的后缀字符串（isSelfAvoiding 预验证）。
     */
    private static HexPattern generateStructuredPattern(AbstractSpell spell, String hexId) {
        // ★ 确定性种子：同一法术每次启动得到相同图案
        RANDOM.setSeed(mixSeed(spell.getSpellName()));

        String school     = getSchoolSegment(spell);
        String targetKind = classifyTarget(spell);
        String target     = getTargetSegment(targetKind);
        CastType castType = spell.getCastType();
        String cast       = getCastSegment(castType);
        String spellName  = spell.getSpellName();

        // ---------- 阶段 A：school + target + cast + hash 后缀 ----------
        long deadlineA = System.currentTimeMillis() + BUDGET_PHASE_A_MS;
        for (int suffixLen = 6; suffixLen <= 14; suffixLen++) {
            if (System.currentTimeMillis() > deadlineA) break;
            for (int variant = 0; variant < SUFFIX_VARIANTS; variant++) {
                if (System.currentTimeMillis() > deadlineA) break;
                String suffix = hashSuffix(spellName, suffixLen, variant);
                if (!isSelfAvoiding(suffix)) continue;
                HexPattern p = tryBuild(
                        new String[]{school, target, cast, suffix},
                        hexId, "A", suffixLen, school, target, targetKind, cast, castType,
                        deadlineA);
                if (p != null) {
                    PHASE_A_NAMES.add(spellName);
                    return p;
                }
            }
        }

        // ---------- 阶段 B：target + cast + hash 后缀 ----------
        long deadlineB = System.currentTimeMillis() + BUDGET_PHASE_B_MS;
        for (int suffixLen = 6; suffixLen <= 20; suffixLen++) {
            if (System.currentTimeMillis() > deadlineB) break;
            for (int variant = 0; variant < SUFFIX_VARIANTS; variant++) {
                if (System.currentTimeMillis() > deadlineB) break;
                String suffix = hashSuffix(spellName, suffixLen, variant);
                if (!isSelfAvoiding(suffix)) continue;
                HexPattern p = tryBuild(
                        new String[]{target, cast, suffix},
                        hexId, "B", suffixLen, school, target, targetKind, cast, castType,
                        deadlineB);
                if (p != null) {
                    PHASE_B_NAMES.add(spellName);
                    return p;
                }
            }
        }

        // ---------- 阶段 C：纯随机游走兜底 ----------
        for (int attempt = 0; attempt < PHASE_C_ATTEMPTS; attempt++) {
            Stroker s = new Stroker(BASE_START_DIR);
            int targetLen = 20 + RANDOM.nextInt(20);
            boolean ok = true;
            for (int i = 0; i < targetLen; i++) {
                List<HexDir> valid = new ArrayList<>(6);
                for (HexDir d : ALL_DIRS) {
                    if (s.canAppend(d)) valid.add(d);
                }
                if (valid.isEmpty()) { ok = false; break; }
                s.append(valid.get(RANDOM.nextInt(valid.size())));
            }
            if (!ok) continue;

            HexPattern p = tryFinalize(s, BASE_START_DIR);
            if (p != null) {
                HexEncrypt.LOGGER.warn(
                        "[Hex加密] [C 降级] {} | 无官能团语义 | "
                                + "原定 target={}({}) cast={}({}) | 总长={}",
                        hexId, target, targetKind, cast, castType, p.getAngles().size());
                PHASE_C_NAMES.add(spellName);
                return p;
            }
        }

        HexEncrypt.LOGGER.error("[Hex加密] {} 彻底无法生成图案。", hexId);
        return null;
    }

    /** 官能团绘制失败时的细分原因。 */
    private record SegFailure(int segIdx, int charIdx, String reason) {}

    /**
     * 扁平重试：外层遍历 6 个 startDir，每个 startDir 内独立重试。
     * 段间连接长度固定为 0~5 步（允许无缝拼接，也允许随机连接）。
     *
     * @param phase "A" / "B"，用于日志标签。
     */
    private static HexPattern tryBuild(String[] segments, String hexId, String phase,
                                       int suffixLen, String school, String target,
                                       String targetKind, String cast, CastType castType,
                                       long deadline) {

        final int connMin = 0;
        final int connMax = 5;

        for (HexDir startDir : ALL_DIRS) {
            if (System.currentTimeMillis() > deadline) return null;

            for (int trial = 0; trial < TRIALS_PER_SUFFIX_PER_DIR; trial++) {
                if (System.currentTimeMillis() > deadline) return null;

                Stroker s = new Stroker(startDir);
                boolean ok = true;

                for (int segIdx = 0; segIdx < segments.length; segIdx++) {
                    if (segIdx > 0) {
                        int connLen = connMin + RANDOM.nextInt(connMax - connMin + 1);
                        for (int i = 0; i < connLen; i++) {
                            List<HexDir> valid = new ArrayList<>(6);
                            for (HexDir d : ALL_DIRS) {
                                if (s.canAppend(d)) valid.add(d);
                            }
                            if (valid.isEmpty()) { ok = false; break; }
                            s.append(pickMostSpacious(s, valid));
                        }
                        if (!ok) break;
                    }
                    if (drawSegmentDetailed(s, segIdx, segments[segIdx]) != null) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;

                HexPattern p = tryFinalize(s, startDir);
                if (p != null) {
                    if ("A".equals(phase)) {
//                        HexEncrypt.LOGGER.info(
//                                "[Hex加密] [A 完整] {} | target={}({}) cast={}({}) | "
//                                        + "startDir={} 尝试={} 总长={}",
//                                hexId, target, targetKind, cast, castType,
//                                startDir, trial, p.getAngles().size());
                    } else {
                        HexEncrypt.LOGGER.warn(
                                "[Hex加密] [B 降级] {} | 舍弃 school | "
                                        + "target={}({}) cast={}({}) | "
                                        + "startDir={} 尝试={} 总长={}",
                                hexId, target, targetKind, cast, castType,
                                startDir, trial, p.getAngles().size());
                    }
                    return p;
                }
            }
        }

        return null;
    }

    /**
     * 绘制官能团，返回失败详情或 null（成功）。
     */
    private static SegFailure drawSegmentDetailed(Stroker state, int segIdx, String seg) {
        for (int i = 0; i < seg.length(); i++) {
            HexAngle a = HexAngle.Companion.fromChar(seg.charAt(i));
            if (a == null) return new SegFailure(segIdx, i, "非法字符");
            HexDir nd = state.compass.rotatedBy(a);
            if (nd == state.compass.rotatedBy(HexAngle.BACK)) {
                return new SegFailure(segIdx, i, "回退");
            }
            if (!state.canAppend(nd)) {
                return new SegFailure(segIdx, i, "边重复");
            }
            state.append(nd);
        }
        return null;
    }

    /** 兼容旧调用。 */
    private static boolean drawSegment(Stroker state, String seg) {
        return drawSegmentDetailed(state, 0, seg) == null;
    }

    /**
     * 启发式选择方向：从 valid 中挑一个"下一步可用方向最多"的。
     * 若有多个并列，用蓄水池抽样随机挑一个。
     */
    private static HexDir pickMostSpacious(Stroker state, List<HexDir> valid) {
        HexDir best = null;
        int bestScore = -1;
        int ties = 0;

        final int q0 = state.q, r0 = state.r;

        for (HexDir d : valid) {
            int dOrd = d.ordinal();
            int nq = q0 + DIR_DELTAS[dOrd][0];
            int nr = r0 + DIR_DELTAS[dOrd][1];
            int rev = d.rotatedBy(HexAngle.BACK).ordinal();

            long e1 = edgeKey(q0, r0, dOrd);
            long e2 = edgeKey(nq, nr, rev);
            state.lines.add(e1);
            state.lines.add(e2);

            HexDir newBack = d.rotatedBy(HexAngle.BACK);
            int score = 0;
            for (HexDir d2 : ALL_DIRS) {
                if (d2 == newBack) continue;
                int d2Ord = d2.ordinal();
                int nnq = nq + DIR_DELTAS[d2Ord][0];
                int nnr = nr + DIR_DELTAS[d2Ord][1];
                int rev2 = d2.rotatedBy(HexAngle.BACK).ordinal();
                if (!state.lines.contains(edgeKey(nq, nr, d2Ord))
                        && !state.lines.contains(edgeKey(nnq, nnr, rev2))) {
                    score++;
                }
            }

            state.lines.remove(e1);
            state.lines.remove(e2);

            if (score > bestScore) {
                bestScore = score;
                best = d;
                ties = 1;
            } else if (score == bestScore) {
                ties++;
                if (RANDOM.nextInt(ties) == 0) best = d;
            }
        }
        return best;
    }

    /**
     * 把绘制结果封装为 HexPattern；若签名已被占用则返回 null。
     */
    private static HexPattern tryFinalize(Stroker result, HexDir startDir) {
        if (result == null) return null;
        HexPattern p = new HexPattern(startDir, new ArrayList<>(result.angles));
        if (USED_SIGNATURES.add(uniquenessKey(p))) return p;
        return null;
    }

    /**
     * 生成第 variant 个后缀候选。
     * variant 从 0 开始递增，不同的 variant 通过异或种子产生完全不同的字符流。
     */
    private static String hashSuffix(String spellName, int len, int variant) {
        long h = mixSeed(spellName) ^ (0x9E3779B97F4A7C15L * (variant + 1L));
        char[] turns = {'w', 'e', 'q', 'd', 'a'};
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(turns[Math.floorMod(h, 5)]);
            h = h / 5 ^ 0x9E3779B97F4A7C15L;
        }
        return sb.toString();
    }

    /**
     * 后缀独立自交检查：从 NORTH_EAST 起笔走一遍，任一字符冲突即判非法。
     * 图案的自交性质在旋转下不变，因此这个检查与 startDir 无关。
     */
    private static boolean isSelfAvoiding(String seg) {
        Stroker s = new Stroker(HexDir.NORTH_EAST);
        for (int i = 0; i < seg.length(); i++) {
            HexAngle a = HexAngle.Companion.fromChar(seg.charAt(i));
            if (a == null) return false;
            HexDir nd = s.compass.rotatedBy(a);
            if (!s.canAppend(nd)) return false;
            s.append(nd);
        }
        return true;
    }

    // ==================== 学派识别 ====================

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

    // ==================== 自动检测：法术是否需要 WithTarget ====================

    private static boolean detectSpellIsSimpleTarget(AbstractSpell spell) {
        Class<?> cls = spell.getClass();
        String resourceName = cls.getSimpleName() + ".class";

        try (InputStream is = cls.getResourceAsStream(resourceName)) {
            if (is == null) return false;

            ClassReader cr = new ClassReader(is);
            Set<String> referenced = new HashSet<>();

            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
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
            return hasBasic && !hasComplex;

        } catch (Throwable t) {
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

    /**
     * 返回所有已注册的 ISS 动作：id → 图案。
     * 只返回命名空间 = HexEncrypt.MODID 且路径以 "iss_" 开头的条目。
     */
    public static Map<ResourceLocation, HexPattern> getIssPatterns() {
        Map<ResourceLocation, HexPattern> out = new LinkedHashMap<>();
        Registry<ActionRegistryEntry> registry = IXplatAbstractions.INSTANCE.getActionRegistry();
        for (var entry : registry.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            if (!id.getNamespace().equals(HexEncrypt.MODID)) continue;
            if (!id.getPath().startsWith("iss_")) continue;
            out.put(id, entry.getValue().prototype());
        }
        return out;
    }

    public static Set<String> getAllSegmentSignatures() {
        return SEGMENT_SIGNATURES;
    }

    private static final Set<String> SEGMENT_SIGNATURES = Set.of(
            // school
            SCHOOL_ELDRITCH, SCHOOL_NATURE, SCHOOL_LIGHTNING, SCHOOL_ICE,
            SCHOOL_BLOOD, SCHOOL_EVOCATION, SCHOOL_HOLY, SCHOOL_ENDER,
            SCHOOL_FIRE, SCHOOL_FALLBACK,
            // target
            TARGET_SELF, TARGET_OTHER, TARGET_SUMMON,
            // cast
            CAST_NONE, CAST_INSTANT, CAST_CONTINUOUS, CAST_LONG
    );


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

    private static void printPatternSummary() {
        List<String> aSorted = new ArrayList<>(PHASE_A_NAMES);
        List<String> bSorted = new ArrayList<>(PHASE_B_NAMES);
        List<String> cSorted = new ArrayList<>(PHASE_C_NAMES);
        Collections.sort(aSorted);
        Collections.sort(bSorted);
        Collections.sort(cSorted);

        int aCount = aSorted.size();
        int bCount = bSorted.size();
        int cCount = cSorted.size();

        HexEncrypt.LOGGER.info("======================================================================");
        HexEncrypt.LOGGER.info("[Hex加密] 图案生成汇总：");
        HexEncrypt.LOGGER.info("[Hex加密]   [A 完整]  {} 个（school + target + cast + suffix）", aCount);
        if (aCount > 0) {
            HexEncrypt.LOGGER.info("[Hex加密]            {}", aSorted);
        }
        HexEncrypt.LOGGER.info("[Hex加密]   [B 降级]  {} 个（舍弃 school 段）", bCount);
        if (bCount > 0) {
            HexEncrypt.LOGGER.info("[Hex加密]            {}", bSorted);
        }
        HexEncrypt.LOGGER.info("[Hex加密]   [C 降级]  {} 个（无官能团语义）", cCount);
        if (cCount > 0) {
            HexEncrypt.LOGGER.info("[Hex加密]            {}", cSorted);
        }
        HexEncrypt.LOGGER.info("======================================================================");
    }

    private static void printReminder() {
        HexEncrypt.LOGGER.info("======================================================================");
        HexEncrypt.LOGGER.info("[Hex加密] 本模组使用“官能团前缀 + hash 后缀”方案生成图案；");
        HexEncrypt.LOGGER.info("[Hex加密] 每个 ISS 动作都拥有唯一签名。");
        HexEncrypt.LOGGER.info("[Hex加密] 已注册的 ISS 动作数：{}", REGISTERED_ISS_SPELL_IDS.size());
        HexEncrypt.LOGGER.info("======================================================================");
    }
}