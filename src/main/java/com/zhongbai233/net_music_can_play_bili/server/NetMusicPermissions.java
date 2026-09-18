package com.zhongbai233.net_music_can_play_bili.server;

import com.mojang.authlib.GameProfile;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/** NeoForge 权限节点与命令权限查询入口。 */
public final class NetMusicPermissions {
    /** 原版权限等级（1.21.1 中 getProfilePermissions 直接返回 int）：2=GAMEMASTERS, 3=ADMINS, 4=OWNERS。 */
    private static final int OP_LEVEL_GAMEMASTERS = 2;
    private static final int OP_LEVEL_OWNERS = 4;

    public static final PermissionNode<Boolean> AUDIT_SOURCES = booleanNode(
            "audit.sources",
            "NetMusic Bili audit sources",
            "允许查询当前正在播放的现代化唱片机/MP4 音源。",
            NetMusicPermissions::defaultOpLevelTwo);
    public static final PermissionNode<Boolean> PAD_REFRESH = booleanNode(
            "pad.refresh",
            "NetMusic Bili pad refresh",
            "允许刷新 Pad 服务端临时数据。",
            NetMusicPermissions::defaultOpLevelTwo);
    public static final PermissionNode<Boolean> WHITELIST_MANAGE = booleanNode(
            "whitelist.manage",
            "NetMusic Bili whitelist manage",
            "允许管理 Bili/NetMusic 链接白名单并打开审核界面。",
            NetMusicPermissions::defaultOpLevelFour);
    public static final PermissionNode<Boolean> CONTROL_CONSOLE_ADMIN = booleanNode(
            "control_console.admin",
            "NetMusic Bili control console admin",
            "允许绕过中控台 owner/accessMode 编辑限制并恢复无人认领的中控台；OP2 及以上始终允许。",
            NetMusicPermissions::defaultOpLevelTwo);

    private NetMusicPermissions() {
    }

    public static void onPermissionGather(PermissionGatherEvent.Nodes event) {
        event.addNodes(AUDIT_SOURCES, PAD_REFRESH, WHITELIST_MANAGE, CONTROL_CONSOLE_ADMIN);
    }

    public static boolean has(CommandSourceStack source, PermissionNode<Boolean> node) {
        if (source == null) {
            return false;
        }
        try {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                return true;
            }
            if (isSingleplayerOwner(source, player)) {
                return true;
            }
            return PermissionAPI.getPermission(player, node);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 中控台管理员判定。原版 OP2 及以上是不可被外部权限处理器否决的兜底，
     * 非 OP 玩家仍可通过 NeoForge/LuckPerms 权限节点获得管理能力。
     */
    public static boolean canAdministerControlConsole(CommandSourceStack source) {
        if (source == null) {
            return false;
        }
        try {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                return true;
            }
            boolean singleplayerOwner = isSingleplayerOwner(source, player);
            boolean opLevelTwoOrHigher = hasVanillaPermission(player, OP_LEVEL_GAMEMASTERS);
            if (ControlConsolePermissionPolicy.grantsAdministrator(
                    singleplayerOwner, opLevelTwoOrHigher, false)) {
                return true;
            }
            return ControlConsolePermissionPolicy.grantsAdministrator(
                    false, false, PermissionAPI.getPermission(player, CONTROL_CONSOLE_ADMIN));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static PermissionNode<Boolean> booleanNode(String name, String readableName, String description,
            PermissionNode.PermissionResolver<Boolean> defaultResolver) {
        PermissionNode<Boolean> node = new PermissionNode<>(NetMusicCanPlayBili.MODID, name, PermissionTypes.BOOLEAN,
                defaultResolver);
        node.setInformation(Component.literal(readableName), Component.literal(description));
        return node;
    }

    private static boolean defaultOpLevelTwo(ServerPlayer player, java.util.UUID playerUUID,
            net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext<?>... context) {
        return hasVanillaPermission(player, OP_LEVEL_GAMEMASTERS);
    }

    private static boolean defaultOpLevelFour(ServerPlayer player, java.util.UUID playerUUID,
            net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext<?>... context) {
        return hasVanillaPermission(player, OP_LEVEL_OWNERS);
    }

    private static boolean hasVanillaPermission(ServerPlayer player, int minimum) {
        if (player == null) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        GameProfile profile = player.getGameProfile();
        if (profile == null) {
            return false;
        }
        return server.getProfilePermissions(profile) >= minimum;
    }

    private static boolean isSingleplayerOwner(CommandSourceStack source, ServerPlayer player) {
        GameProfile profile = player.getGameProfile();
        return profile != null && source.getServer().isSingleplayerOwner(profile);
    }
}