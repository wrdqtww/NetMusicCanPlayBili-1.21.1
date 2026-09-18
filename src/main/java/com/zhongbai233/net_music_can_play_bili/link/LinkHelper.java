package com.zhongbai233.net_music_can_play_bili.link;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;

/**
 * 方块链接的通用工具方法。
 * 支持两种存储方式：
 * <ul>
 * <li>物品 NBT（{@link DataComponents#CUSTOM_DATA}）：手持链接物品时创建链接</li>
 * <li>方块实体 NBT（{@link CompoundTag}）：方块放置后持久化</li>
 * </ul>
 */
public final class LinkHelper {
    /** 物品 CUSTOM_DATA 中存储链接目标的 key */
    public static final String LINK_X = "linked_x";
    public static final String LINK_Y = "linked_y";
    public static final String LINK_Z = "linked_z";
    public static final String LINK_DIMENSION = "linked_dimension";
    public static final String LINK_SOURCE_KIND = "linked_source_kind";

    public enum ControlConsoleSourceKind {
        TURNTABLE,
        LIVE_STREAMER
    }

    public record ControlConsoleLink(BlockPos pos, String dimension, ControlConsoleSourceKind sourceKind,
            boolean legacy) {
    }

    private LinkHelper() {
    }

    // ──── 物品 NBT 操作 ────

    /** 将目标位置写入物品，并添加附魔光效 */
    public static void writeLinkToItem(ItemStack stack, BlockPos targetPos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(LINK_X, targetPos.getX());
        tag.putInt(LINK_Y, targetPos.getY());
        tag.putInt(LINK_Z, targetPos.getZ());
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                existing -> existing.update(existingTag -> existingTag.merge(tag)));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
    }

    public static void writeControlConsoleLinkToItem(ItemStack stack, BlockPos targetPos, String dimension,
            ControlConsoleSourceKind sourceKind) {
        writeLinkToItem(stack, targetPos);
        CompoundTag tag = new CompoundTag();
        tag.putString(LINK_DIMENSION, dimension);
        tag.putString(LINK_SOURCE_KIND, sourceKind.name());
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                existing -> existing.update(existingTag -> existingTag.merge(tag)));
    }

    @Nullable
    public static ControlConsoleLink readControlConsoleLinkFromItem(ItemStack stack) {
        BlockPos pos = readLinkFromItem(stack);
        if (pos == null) {
            return null;
        }
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = customData != null ? customData.copyTag() : new CompoundTag();
        String dimension = tag.getString(LINK_DIMENSION);
        String kindName = tag.getString(LINK_SOURCE_KIND);
        if (dimension.isBlank() || kindName.isBlank()) {
            return new ControlConsoleLink(pos, null, null, true);
        }
        try {
            return new ControlConsoleLink(pos, dimension, ControlConsoleSourceKind.valueOf(kindName), false);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** 从物品读取链接目标位置，若未设置则返回 null */
    @Nullable
    public static BlockPos readLinkFromItem(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty())
            return null;
        CompoundTag tag = customData.copyTag();
        if (!tag.contains(LINK_X))
            return null;
        return new BlockPos(
                tag.getInt(LINK_X),
                tag.getInt(LINK_Y),
                tag.getInt(LINK_Z));
    }

    /** 清除物品上的链接数据和光效 */
    public static void clearLinkFromItem(ItemStack stack) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> existing.update(tag -> {
            tag.remove(LINK_X);
            tag.remove(LINK_Y);
            tag.remove(LINK_Z);
            tag.remove(LINK_DIMENSION);
            tag.remove(LINK_SOURCE_KIND);
        }));
        CustomData remaining = stack.get(DataComponents.CUSTOM_DATA);
        if (remaining != null && remaining.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        }
        stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    }

    // ──── 方块实体 NBT 操作 ────

    /** 将链接位置写入 BE 持久化数据 */
    public static void saveLinkToBE(CompoundTag output, @Nullable BlockPos pos,
            String hasKey, String xKey, String yKey, String zKey) {
        output.putBoolean(hasKey, pos != null);
        if (pos != null) {
            output.putInt(xKey, pos.getX());
            output.putInt(yKey, pos.getY());
            output.putInt(zKey, pos.getZ());
        }
    }

    /** 从 BE 持久化数据读取链接位置，若不存在则返回 null */
    @Nullable
    public static BlockPos loadLinkFromBE(CompoundTag input,
            String hasKey, String xKey, String yKey, String zKey) {
        if (!getBooleanOr(input, hasKey, false))
            return null;
        return new BlockPos(
                getIntOr(input, xKey, 0),
                getIntOr(input, yKey, 0),
                getIntOr(input, zKey, 0));
    }

    // ──── 1.21.1 CompoundTag 兼容读取(26.x 风格缺省读的等价物) ────

    /** 键存在且类型匹配时返回对应值，否则返回默认值。 */
    public static int getIntOr(CompoundTag tag, String key, int fallback) {
        return tag.contains(key, Tag.TAG_INT) ? tag.getInt(key) : fallback;
    }

    public static long getLongOr(CompoundTag tag, String key, long fallback) {
        return tag.contains(key, Tag.TAG_LONG) ? tag.getLong(key) : fallback;
    }

    public static float getFloatOr(CompoundTag tag, String key, float fallback) {
        return tag.contains(key, Tag.TAG_FLOAT) ? tag.getFloat(key) : fallback;
    }

    public static double getDoubleOr(CompoundTag tag, String key, double fallback) {
        return tag.contains(key, Tag.TAG_DOUBLE) ? tag.getDouble(key) : fallback;
    }

    public static boolean getBooleanOr(CompoundTag tag, String key, boolean fallback) {
        return tag.contains(key, Tag.TAG_BYTE) ? tag.getBoolean(key) : fallback;
    }

    public static String getStringOr(CompoundTag tag, String key, String fallback) {
        return tag.contains(key, Tag.TAG_STRING) ? tag.getString(key) : fallback;
    }

    /** 子 CompoundTag：不存在时返回空 CompoundTag（26.x childOrEmpty 的等价物）。 */
    public static CompoundTag childOrEmpty(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_COMPOUND) ? tag.getCompound(key) : new CompoundTag();
    }

    /** Compound 列表：不存在时返回空 ListTag（26.x childrenListOrEmpty 的等价物）。 */
    public static ListTag childrenListOrEmpty(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_LIST) ? tag.getList(key, Tag.TAG_COMPOUND) : new ListTag();
    }
}