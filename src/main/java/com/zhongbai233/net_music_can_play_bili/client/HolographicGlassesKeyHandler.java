package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.network.ClearEquippedBindingPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(value = Dist.CLIENT)
public final class HolographicGlassesKeyHandler {
    private static final String CATEGORY = "key.categories.net_music_can_play_bili.main";
    private static final KeyMapping OPEN_CONFIG = new KeyMapping(
            "key.net_music_can_play_bili.holographic_glasses_config",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            CATEGORY);

    /**
     * 清理头戴装备绑定。26.x 起该功能一直有服务端实现(ClearEquippedBindingPacket)与语言键,
     * 但从未注册键位 —— 导致键位与网络包双双成为死链路。此处按"默认不绑定"注册,
     * 玩家可在按键设置中自行指派,功能即刻可用。
     */
    private static final KeyMapping CLEAR_EQUIPPED_BINDINGS = new KeyMapping(
            "key.net_music_can_play_bili.clear_equipped_bindings",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            CATEGORY);

    private HolographicGlassesKeyHandler() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CONFIG);
        event.register(CLEAR_EQUIPPED_BINDINGS);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN_CONFIG.consumeClick()) {
            if (minecraft.screen != null) {
                continue;
            }
            Player player = minecraft.player;
            if (player == null) {
                continue;
            }
            if (!HolographicGlassesAbility.has(EquippedMediaItems.firstHolographicGlasses(player))) {
                player.sendSystemMessage(Component.translatable(
                        "message.net_music_can_play_bili.holographic_glasses.need_equipped_for_config"));
                continue;
            }
            minecraft.setScreen(new HolographicScreenConfigTestScreen(true));
        }
        while (CLEAR_EQUIPPED_BINDINGS.consumeClick()) {
            if (minecraft.screen != null || minecraft.player == null) {
                continue;
            }
            PacketDistributor.sendToServer(new ClearEquippedBindingPacket());
        }
    }
}
