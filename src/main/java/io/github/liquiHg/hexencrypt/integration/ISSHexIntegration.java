package io.github.liquiHg.hexencrypt.integration;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.common.lib.HexRegistries;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.actions.OpCastContinuousIronsSpell;
import io.github.liquiHg.hexencrypt.actions.OpCastIronsSpell;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

@Mod.EventBusSubscriber(modid = HexEncrypt.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ISSHexIntegration {

    public static final List<String> REGISTERED_ISS_SPELL_IDS = new ArrayList<>();

    /**
     * 已生成过的签名，用于在运行时二次去重（理论上用不上，但加一层保险）。
     */
    private static final Set<String> USED_SIGNATURES = new HashSet<>();

    @SubscribeEvent
    public static void onRegisterSpells(RegisterEvent event) {
        if (!event.getRegistryKey().equals(SpellRegistry.SPELL_REGISTRY_KEY)) {
            return;
        }

        HexEncrypt.LOGGER.info("[Hex加密] 捕获到 ISS 法术注册事件，开始提取法术...");

        var spellRegistry = SpellRegistry.REGISTRY.get();
        if (spellRegistry == null) {
            HexEncrypt.LOGGER.error("[Hex加密] 无法获取 ISS 法术注册表！");
            return;
        }

        List<AbstractSpell> spells = new ArrayList<>();
        for (ResourceLocation id : spellRegistry.getKeys()) {
            AbstractSpell spell = SpellRegistry.getSpell(id);
            if (spell != null && spell != SpellRegistry.none() && spell.isEnabled()) {
                spells.add(spell);
            }
        }
        HexEncrypt.LOGGER.info("[Hex加密] 从 ISS 注册表提取到 {} 个有效法术。", spells.size());

        Registry<ActionRegistryEntry> actionRegistry = IXplatAbstractions.INSTANCE.getActionRegistry();

        for (AbstractSpell spell : spells) {
            ResourceLocation spellId = spell.getSpellResource();
            String spellName = spell.getSpellName();
            ResourceLocation hexId = new ResourceLocation(HexEncrypt.MODID, "iss_" + spellName);

            HexPattern pattern = generateDeterministicPattern(spellName, hexId.toString());

            Action action;
            if (spell.getCastType() == CastType.CONTINUOUS) {
                action = new OpCastContinuousIronsSpell(spellId);
            } else {
                action = new OpCastIronsSpell(spellId);
            }

            Registry.register(actionRegistry, hexId, new ActionRegistryEntry(pattern, action));
            REGISTERED_ISS_SPELL_IDS.add(hexId.toString());
        }

        HexEncrypt.LOGGER.info("[Hex加密] 成功注册了 {} 个 ISS 法术图案。", REGISTERED_ISS_SPELL_IDS.size());
        printPerWorldPatternJson();
    }

    /**
     * 为每个 ISS 法术生成一个确定性的、互不相同的原型图案。
     *
     * <h3>生成策略</h3>
     * <ul>
     *   <li>用 {@code spellName} 的哈希（经过 xorshift 搅拌）作为随机种子，
     *       保证同一法术每次都得到同一个图案，且不同法术种子互不相同。</li>
     *   <li>随机生成一条长度 10~16 的转向签名。长度足够长是"有多个欧拉路径"的
     *       前提——短签名（2~5）往往是直线段，在六边形网格里只有唯一一条欧拉轨迹，
     *       ScrungledPatternsSave 调用 EulerPathFinder 时会抛异常。</li>
     *   <li>字符池只用 {@code w/e/q/d/a} 五个转向，刻意排除 {@code 's'}（掉头），
     *       掉头会 100% 立刻产生自交。</li>
     *   <li>用 {@code HexPattern.fromAngles} 校验。这个工厂方法会检测自交，
     *       自交时抛 {@code IllegalStateException}，捕获后换下一个随机签名重试。</li>
     *   <li>最多重试 1000 次。以 10~16 长度、5 字符池估算，一次成功率约 30%~60%，
     *       连续 1000 次失败的概率是 10^-300 量级，实际不可能发生。</li>
     * </ul>
     *
     * <h3>为什么必须"多个欧拉路径"</h3>
     * Hex 的 Per-World 图案系统（{@code ScrungledPatternsSave}）会对每个 per-world
     * 图案调用 {@code EulerPathFinder} 生成"打乱版"笔顺。如果图案在网格图上只有
     * 唯一一条欧拉轨迹（典型是直线段），打乱过程会找不到替代路径而抛异常，
     * 导致玩家进入世界后卷轴 tick 时 NPE 崩服。
     */
    private static HexPattern generateDeterministicPattern(String spellName, String uniqueKey) {
        // ---------- 1. 用 spellName 生成一个高质量的种子 ----------
        // 不要直接用 String.hashCode()：它在短字符串上的碰撞率不可忽略，
        // 而且它对字符顺序的敏感度不均匀。这里用一个 xorshift 混合器。
        long seed = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < spellName.length(); i++) {
            seed = seed * 31L + spellName.charAt(i);
            seed ^= seed >>> 27;
            seed *= 0x94D049BB133111EBL;
        }
        Random random = new Random(seed);

        // ---------- 2. 准备字符池 ----------
        // 只保留 5 种合法转向，'s'(掉头) 会造成自交所以排除
        char[] turns = {'w', 'e', 'q', 'd', 'a'};
        HexDir[] dirs = HexDir.values();

        // ---------- 3. 重试直到生成了一个合法且未使用过的签名 ----------
        for (int attempt = 0; attempt < 1000; attempt++) {
            // 长度 10~16，与原版 LIGHTNING 图案（"waadwawdaaweewq"，14 字符）量级相当
            int length = 10 + random.nextInt(7);

            StringBuilder signature = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                signature.append(turns[random.nextInt(turns.length)]);
            }
            String sigStr = signature.toString();

            // 二次去重：如果这个签名已经被别的法术用了，换下一个
            if (USED_SIGNATURES.contains(sigStr)) {
                continue;
            }

            HexDir startDir = dirs[random.nextInt(dirs.length)];

            try {
                HexPattern pattern = HexPattern.fromAngles(sigStr, startDir);
                // 校验通过：合法（不自交）且未重复
                USED_SIGNATURES.add(sigStr);
                HexEncrypt.LOGGER.debug("[Hex加密] 为 {} 生成签名: {} (startDir={})",
                        uniqueKey, sigStr, startDir);
                return pattern;
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                // 自交或非法转向，换下一个签名重试
            }
        }

        // ---------- 4. 兜底 ----------
        // 走到这里说明 1000 次随机尝试全部自交，概率极低。
        // 使用一个已被主模组验证过的、确定有多个欧拉路径的签名。
        HexEncrypt.LOGGER.warn("[Hex加密] 为 {} 随机生成图案失败，使用兜底签名", uniqueKey);
        return HexPattern.fromAngles("waadwawdaaweewq", HexDir.EAST);
    }

    private static void printPerWorldPatternJson() {
        HexEncrypt.LOGGER.info("========== 请复制以下内容保存为 data/hexcasting/tags/action/per_world_pattern.json.disabled ==========");
        HexEncrypt.LOGGER.info("{");
        HexEncrypt.LOGGER.info("  \"replace\": false,");
        HexEncrypt.LOGGER.info("  \"values\": [");
        for (int i = 0; i < REGISTERED_ISS_SPELL_IDS.size(); i++) {
            String id = REGISTERED_ISS_SPELL_IDS.get(i);
            String comma = (i == REGISTERED_ISS_SPELL_IDS.size() - 1) ? "" : ",";
            HexEncrypt.LOGGER.info("    \"{}\"{}", id, comma);
        }
        HexEncrypt.LOGGER.info("  ]");
        HexEncrypt.LOGGER.info("}");
        HexEncrypt.LOGGER.info("=================================================================================");
    }
}