package io.github.liquiHg.hexencrypt.command;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.common.lib.HexItems;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.liquiHg.hexencrypt.network.ModNetwork;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Mod.EventBusSubscriber(modid = HexEncrypt.MODID)
public class ModCommands {

    /** Tab 补全：列出所有 hexencrypt:iss_* 动作的短名（去掉 iss_ 前缀）。 */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ISS_SPELLS = (ctx, builder) -> {
        Registry<ActionRegistryEntry> registry = IXplatAbstractions.INSTANCE.getActionRegistry();
        List<String> names = new ArrayList<>();
        for (ResourceLocation id : registry.keySet()) {
            if (!id.getNamespace().equals(HexEncrypt.MODID)) continue;
            String path = id.getPath();
            if (path.startsWith("iss_")) {
                names.add(path.substring("iss_".length()));
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("hexencrypt")
                        .requires(source -> source.hasPermission(2))
                        // ---- 原有：解锁/锁定 ----
                        .then(Commands.literal("unlock")
                                .then(Commands.argument("spell", ResourceLocationArgument.id())
                                        .executes(ctx -> unlockSpell(ctx, ResourceLocationArgument.getId(ctx, "spell")))
                                )
                        )
                        .then(Commands.literal("lock")
                                .then(Commands.argument("spell", ResourceLocationArgument.id())
                                        .executes(ctx -> lockSpell(ctx, ResourceLocationArgument.getId(ctx, "spell")))
                                )
                        )
                        .then(Commands.literal("unlockall")
                                .executes(ModCommands::unlockAll)
                        )
                        .then(Commands.literal("lockall")
                                .executes(ModCommands::lockAll)
                        )
                        .then(Commands.literal("list")
                                .executes(ModCommands::listSpells)
                        )
                        .then(Commands.literal("clear")
                                .executes(ModCommands::clearSpells)
                        )
                        // ---- 原有：卷轴 ----
                        .then(Commands.literal("scroll")
                                .then(Commands.argument("spell", StringArgumentType.string())
                                        .suggests(SUGGEST_ISS_SPELLS)
                                        .executes(ModCommands::giveScroll)
                                )
                        )
                        .then(Commands.literal("scrollAll")
                                .executes(ModCommands::giveScrollAll)
                        )
        );
    }

    // ==================== 原有命令 ====================

    private static int unlockSpell(CommandContext<CommandSourceStack> ctx, ResourceLocation spellId) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null || "none".equals(spell.getSpellName())) {
            ctx.getSource().sendFailure(Component.literal("未知法术: " + spellId));
            return 0;
        }
        boolean success = SpellUnlockManager.unlockSpell(player, spell);
        if (success) {
            ctx.getSource().sendSuccess(() -> Component.literal("已解锁法术: " + spellId), true);
            ModNetwork.sendUnlockedSpells(player, SpellUnlockManager.getUnlockedSpells(player));
        } else {
            ctx.getSource().sendFailure(Component.literal("法术已解锁: " + spellId));
        }
        return success ? 1 : 0;
    }

    private static int lockSpell(CommandContext<CommandSourceStack> ctx, ResourceLocation spellId) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null || "none".equals(spell.getSpellName())) {
            ctx.getSource().sendFailure(Component.literal("未知法术: " + spellId));
            return 0;
        }
        boolean success = SpellUnlockManager.lockSpell(player, spell);
        if (success) {
            ctx.getSource().sendSuccess(() -> Component.literal("已锁定法术: " + spellId), true);
            ModNetwork.sendUnlockedSpells(player, SpellUnlockManager.getUnlockedSpells(player));
        } else {
            ctx.getSource().sendFailure(Component.literal("法术未解锁: " + spellId));
        }
        return success ? 1 : 0;
    }

    private static int unlockAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Set<ResourceLocation> allSpells = SpellRegistry.REGISTRY.get().getKeys();
        int count = 0;
        for (ResourceLocation id : allSpells) {
            AbstractSpell spell = SpellRegistry.getSpell(id);
            if (spell != null && !"none".equals(spell.getSpellName())) {
                if (SpellUnlockManager.unlockSpell(player, spell)) {
                    count++;
                }
            }
        }
        final int finalCount = count;
        ctx.getSource().sendSuccess(() -> Component.literal("已解锁所有法术，共 " + finalCount + " 个。"), true);
        ModNetwork.sendUnlockedSpells(player, SpellUnlockManager.getUnlockedSpells(player));
        return finalCount;
    }

    private static int lockAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SpellUnlockManager.setUnlockedSpells(player, Collections.emptySet());
        ctx.getSource().sendSuccess(() -> Component.literal("已锁定所有法术。"), true);
        ModNetwork.sendUnlockedSpells(player, Collections.emptySet());
        return 1;
    }

    private static int listSpells(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Set<String> unlocked = SpellUnlockManager.getUnlockedSpells(player);
        if (unlocked.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("当前没有已解锁的法术。"), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("已解锁法术: " + String.join(", ", unlocked)), false);
        }
        return unlocked.size();
    }

    private static int clearSpells(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SpellUnlockManager.setUnlockedSpells(player, Collections.emptySet());
        ctx.getSource().sendSuccess(() -> Component.literal("已清空所有解锁记录。"), true);
        ModNetwork.sendUnlockedSpells(player, Collections.emptySet());
        return 1;
    }

    // ==================== 卷轴命令 ====================

    private static int giveScroll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String spellName = StringArgumentType.getString(ctx, "spell");
        // 兼容带不带 iss_ 前缀
        String path = spellName.startsWith("iss_") ? spellName : "iss_" + spellName;
        ResourceLocation id = new ResourceLocation(HexEncrypt.MODID, path);

        Registry<ActionRegistryEntry> registry = IXplatAbstractions.INSTANCE.getActionRegistry();
        ActionRegistryEntry entry = registry.get(id);
        if (entry == null) {
            ctx.getSource().sendFailure(Component.literal("未知法术动作: " + id));
            return 0;
        }

        giveOrDrop(player, makeScroll(entry.prototype()));
        ctx.getSource().sendSuccess(() -> Component.literal("已给予卷轴: " + id), false);
        return 1;
    }

    private static int giveScrollAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Registry<ActionRegistryEntry> registry = IXplatAbstractions.INSTANCE.getActionRegistry();

        int count = 0;
        for (ResourceLocation id : registry.keySet()) {
            if (!id.getNamespace().equals(HexEncrypt.MODID)) continue;
            ActionRegistryEntry entry = registry.get(id);
            if (entry == null) continue;
            giveOrDrop(player, makeScroll(entry.prototype()));
            count++;
        }

        final int n = count;
        ctx.getSource().sendSuccess(() -> Component.literal("已给予 " + n + " 个卷轴"), false);
        return n;
    }

    /**
     * 尝试放进背包，放不下的部分掉落到玩家脚下。
     * player.getInventory().add(stack) 会把能塞进去的部分塞进去，
     * 并返回剩余数量（0 表示全部塞进去了）。
     */
    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack remainder = stack.copy();
        player.getInventory().add(remainder);
        if (!remainder.isEmpty()) {
            player.drop(remainder, false);
        }
    }

    /** 用 HexPattern 构造一个带正确 NBT 的卷轴物品。 */
    private static ItemStack makeScroll(HexPattern pattern) {
        ItemStack stack = new ItemStack(HexItems.SCROLL_SMOL);
        stack.getOrCreateTag().put("pattern", pattern.serializeToNBT());
        return stack;
    }
}