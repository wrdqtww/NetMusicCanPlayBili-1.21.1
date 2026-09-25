package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.renderer.ClientDisplayProperties;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/** 客户端本地的全息眼镜隐私保护状态。 */
public final class HolographicGlassesClient {
    private HolographicGlassesClient() {
    }

    public static boolean active() {
        Player player = Minecraft.getInstance().player;
        return player != null && HolographicGlassesAbility.has(EquippedMediaItems.firstHolographicGlasses(player));
    }

    public static boolean shouldHideProjectorVideos() {
        return active();
    }

    public static UUID boundMp4DeviceId() {
        List<HolographicGlassesItem.ScreenBinding> bindings = screenBindings();
        return bindings.isEmpty() ? null : bindings.getFirst().deviceId();
    }

    /** 上一次解析所用的（物品栈身份, CustomData 身份）与其结果。 */
    private static ItemStack cachedBindingStack;
    private static CustomData cachedBindingData;
    private static List<HolographicGlassesItem.ScreenBinding> cachedBindings = List.of();

    /**
     * 读取眼镜上绑定的屏幕列表（带记忆化）。
     *
     * <p>{@code readScreenBindings} 会做一次整棵 NBT 深拷贝（{@code customData.copyTag()}）并重建
     * record 列表，而本方法在渲染线程上每帧、每个视频会话都会调用一次（
     * {@code VideoPlaybackInstance#hasHolographicTurntableConsumer} 由
     * {@code VideoPlaybackPresentation#submit} 无条件调用）。</p>
     *
     * <p>缓存键用两者<b>身份</b>而非内容：{@code firstEquipped} 返回的是背包/Curios 里的实时栈实例，
     * 身份在栈未被替换时稳定；而本模组写绑定一律走 {@code stack.set(DataComponents.CUSTOM_DATA, ...)}，
     * 必然换成新的 {@link CustomData} 实例。因此身份比较既能正确失效，又只有 O(1) 成本——若改为比较
     * NBT 内容，递归遍历的代价本身就和要省掉的深拷贝同量级。</p>
     */
    public static List<HolographicGlassesItem.ScreenBinding> screenBindings() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return List.of();
        }
        ItemStack head = EquippedMediaItems.firstHolographicGlasses(player);
        if (!HolographicGlassesAbility.has(head)) {
            return List.of();
        }
        CustomData data = head.get(DataComponents.CUSTOM_DATA);
        if (head == cachedBindingStack && data == cachedBindingData) {
            return cachedBindings;
        }
        List<HolographicGlassesItem.ScreenBinding> bindings = HolographicGlassesItem.readScreenBindings(head);
        cachedBindingStack = head;
        cachedBindingData = data;
        cachedBindings = bindings;
        return bindings;
    }

    public static boolean handlesTurntable(BlockPos turntablePos) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceKey<Level> dimension = minecraft.level != null ? minecraft.level.dimension() : null;
        return handlesTurntable(dimension, turntablePos);
    }

    public static boolean handlesTurntable(ResourceKey<Level> dimension, BlockPos turntablePos) {
        if (!ClientDisplayProperties.holographicWorldScreenEnabled()
                || dimension == null || turntablePos == null) {
            return false;
        }
        for (HolographicGlassesItem.ScreenBinding binding : screenBindings()) {
            if (binding.source() != null && binding.source().isTurntable()
                    && dimension.equals(binding.source().dimension())
                    && turntablePos.equals(binding.source().pos())) {
                return true;
            }
        }
        return false;
    }

    public static HolographicGlassesItem.ScreenConfig screenConfig() {
        List<HolographicGlassesItem.ScreenBinding> bindings = screenBindings();
        return bindings.isEmpty() ? HolographicGlassesItem.defaultScreenConfig() : bindings.getFirst().config();
    }
}
