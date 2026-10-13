package io.github.liquiHg.hexencrypt.network;

import at.petrak.hexcasting.api.casting.math.HexDir;
import io.github.liquiHg.hexencrypt.block.SpellInscriptionTableBlockEntity;
import io.github.liquiHg.hexencrypt.block.SpellInscriptionTableMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 通用动作包。
 * <pre>
 *   action: 0 = 画一条边, 1 = 回退, 2 = 用空白卷轴清空栈, 3 = 入栈（考察）,
 *           4 = 请求同步, 5 = 尝试解析,
 *           6 = 设置图案原点, 7 = 网格平移
 * </pre>
 */
public record PacketTableAction(int action, int quadrant, int a, int b) {

    public static final int ACT_DRAW        = 0;
    public static final int ACT_UNDO        = 1;
    public static final int ACT_CLEAR_STACK = 2;
    public static final int ACT_CONSIDER    = 3;
    public static final int ACT_REQUEST_SYNC = 4;
    public static final int ACT_PARSE = 5;
    public static final int ACT_SET_ORIGIN = 6;
    public static final int ACT_MOVE_GRID  = 7;
    public static final int ACT_PLACE_CANVAS = 8;

    public static PacketTableAction draw(int q, HexDir dir) {
        return new PacketTableAction(ACT_DRAW, q, dir.ordinal(), 0);
    }

    public static PacketTableAction undo(int q) {
        return new PacketTableAction(ACT_UNDO, q, 0, 0);
    }

    public static PacketTableAction clearStack(int q) {
        return new PacketTableAction(ACT_CLEAR_STACK, q, 0, 0);
    }

    public static PacketTableAction consider(int q) {
        return new PacketTableAction(ACT_CONSIDER, q, 0, 0);
    }

    public static PacketTableAction requestSync() {
        return new PacketTableAction(ACT_REQUEST_SYNC, 0, 0, 0);
    }

    public static PacketTableAction parse() {
        return new PacketTableAction(ACT_PARSE, 0, 0, 0);
    }

    // ⭐ 新增：落笔时设置 origin（不扣媒质）
    public static PacketTableAction setOrigin(int q, int originQ, int originR) {
        return new PacketTableAction(ACT_SET_ORIGIN, q, originQ, originR);
    }

    // ⭐ 新增：平移 origin（扣 20 媒质，由 srvMoveGrid 处理）
    public static PacketTableAction moveGrid(int q, int newQ, int newR) {
        return new PacketTableAction(ACT_MOVE_GRID, q, newQ, newR);
    }

    public static PacketTableAction placeCanvas() {
        return new PacketTableAction(ACT_PLACE_CANVAS, 0, 0, 0);
    }

    public static void encode(PacketTableAction msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.action);
        buf.writeVarInt(msg.quadrant);
        buf.writeVarInt(msg.a);
        buf.writeVarInt(msg.b);
    }

    public static PacketTableAction decode(FriendlyByteBuf buf) {
        return new PacketTableAction(
                buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(PacketTableAction msg, Supplier<NetworkEvent.Context> ctxSup) {

        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;

//            // ===== ★ 探针 A：确认包到了服务端 =====
//            player.sendSystemMessage(Component.literal(
//                    "§b[调试A] 服务端收到包 action=" + msg.action
//                            + " q=" + msg.quadrant + " dir=" + msg.dirOrdinal));

            // ===== ★ 探针 B：确认 menu 类型 =====
            if (!(player.containerMenu instanceof SpellInscriptionTableMenu menu)) {
//                player.sendSystemMessage(Component.literal(
//                        "§c[调试B] containerMenu 不是 SpellInscriptionTableMenu，实际="
//                                + player.containerMenu.getClass().getSimpleName()));
                return;
            }

            var be = menu.getBlockEntity();
            if (be == null) {
//                player.sendSystemMessage(Component.literal(
//                        "§c[调试B] menu.getBlockEntity() == null"));
                return;
            }

            // ===== ★ 探针 C：打印当前状态（仅 PARSE 时） =====
            if (msg.action == ACT_PARSE) {
                int filled = 0;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < SpellInscriptionTableBlockEntity.QUADRANT_COUNT; i++) {
                    var iota = be.getQuadrantStackIota(i);
                    if (iota != null) {
                        filled++;
                        sb.append("q").append(i).append("=").append(iota.getClass().getSimpleName()).append(" ");
                    }
                }
                var central = menu.getStoredItem();
            }

            boolean ok = false;
            switch (msg.action) {
                case ACT_DRAW -> {
                    HexDir[] dirs = HexDir.values();
                    if (msg.a < 0 || msg.a >= dirs.length) return;
                    ok = menu.srvAppendEdge(msg.quadrant, dirs[msg.a]);
                }
                case ACT_UNDO        -> ok = menu.srvUndoEdge(msg.quadrant);
                case ACT_CLEAR_STACK -> ok = menu.srvClearQuadrantStack(player, msg.quadrant);
                case ACT_CONSIDER    -> ok = menu.srvConsiderQuadrant(player, msg.quadrant);
                case ACT_PARSE       -> ok = menu.srvTryParse(player);
                case ACT_SET_ORIGIN  -> ok = menu.srvSetOrigin(msg.quadrant, msg.a, msg.b);
                case ACT_MOVE_GRID   -> ok = menu.srvMoveGrid(msg.quadrant, msg.a, msg.b);
                case ACT_PLACE_CANVAS -> ok = menu.srvPlaceCanvases(player);
                case ACT_REQUEST_SYNC -> { /* no-op */ }
                default -> { /* 未知 action，静默忽略 */ }
            }

            // ===== ★ 探针 D：确认动作返回值 =====
            if (msg.action != ACT_REQUEST_SYNC) {
//                player.sendSystemMessage(Component.literal(
//                        "§b[调试D] action=" + msg.action + " 执行结果 ok=" + ok));
            }

            // 同步回包
            ModNetwork.sendTableSync(player, PacketTableSync.of(
                    be.getMedia(),
                    be.buildQuadrantNbt()));
        });
        ctx.setPacketHandled(true);
    }
}