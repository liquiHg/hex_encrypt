package io.github.liquiHg.hexencrypt.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.liquiHg.hexencrypt.network.ModNetwork;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collections;
import java.util.Set;

@Mod.EventBusSubscriber(modid = HexEncrypt.MODID)
public class ModCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("hexencrypt")
                        .requires(source -> source.hasPermission(2))
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
        );
    }

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
        // 用 final 变量传给 lambda，避免 "有效 final" 报错
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
}