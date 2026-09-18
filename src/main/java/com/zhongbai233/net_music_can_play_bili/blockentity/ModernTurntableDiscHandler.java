package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One-slot automation adapter for a modern turntable's music disc.
 *
 * <p>
 * 1.21.1 重写：26.x 的资源句柄与事务 API 均不存在，改为继承 {@link ItemStackHandler} 并把单槽
 * 内容桥接到唱片机方块实体的 {@code disc} 字段（getter/setter 注入）。
 * 保留原语义：容量恒为 1 件、仅接受携带歌曲信息的 {@link ItemMusicCD}、
 * 自动化提取受 {@code extractionAllowed} 门控、提交时回调 {@code commitListener}。
 * </p>
 */
final class ModernTurntableDiscHandler extends ItemStackHandler {
    private final Supplier<ItemStack> stackGetter;
    private final Consumer<ItemStack> stackSetter;
    private final BooleanSupplier extractionAllowed;
    private final Consumer<ItemStack> commitListener;

    ModernTurntableDiscHandler(Supplier<ItemStack> stackGetter, Consumer<ItemStack> stackSetter,
            BooleanSupplier extractionAllowed, Consumer<ItemStack> commitListener) {
        super(1);
        this.stackGetter = stackGetter;
        this.stackSetter = stackSetter;
        this.extractionAllowed = extractionAllowed;
        this.commitListener = commitListener;
    }

    @Override
    public int getSlots() {
        return 1;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        validateSlotIndex(slot);
        return stackGetter.get();
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        validateSlotIndex(slot);
        ItemStack previous = stackGetter.get();
        stackSetter.accept(stack);
        if (!ItemStack.matches(previous, stack)) {
            commitListener.accept(previous);
        }
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !isItemValid(slot, stack)) {
            return stack;
        }
        validateSlotIndex(slot);
        ItemStack previous = stackGetter.get();
        int limit = getSlotLimit(slot);
        if (!previous.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(stack, previous)) {
                return stack;
            }
            limit -= previous.getCount();
        }
        if (limit <= 0) {
            return stack;
        }
        boolean reachedLimit = stack.getCount() > limit;
        if (!simulate) {
            ItemStack next = reachedLimit ? stack.copyWithCount(limit) : stack;
            stackSetter.accept(next);
            commitListener.accept(previous);
        }
        return reachedLimit ? stack.copyWithCount(stack.getCount() - limit) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount == 0) {
            return ItemStack.EMPTY;
        }
        validateSlotIndex(slot);
        ItemStack existing = stackGetter.get();
        if (existing.isEmpty() || !extractionAllowed.getAsBoolean()) {
            return ItemStack.EMPTY;
        }
        int toExtract = Math.min(amount, existing.getMaxStackSize());
        if (existing.getCount() <= toExtract) {
            if (!simulate) {
                stackSetter.accept(ItemStack.EMPTY);
                commitListener.accept(existing);
                return existing;
            } else {
                return existing.copy();
            }
        } else {
            if (!simulate) {
                stackSetter.accept(existing.copyWithCount(existing.getCount() - toExtract));
                commitListener.accept(existing);
            }
            return existing.copyWithCount(toExtract);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return 1;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return stack != null && !stack.isEmpty() && ItemMusicCD.getSongInfo(stack) != null;
    }
}