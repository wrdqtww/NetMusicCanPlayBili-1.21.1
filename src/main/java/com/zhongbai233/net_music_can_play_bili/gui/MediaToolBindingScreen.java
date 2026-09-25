package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.menu.MediaToolBindingMenu;
import com.zhongbai233.net_music_can_play_bili.network.MediaToolClearBindingPacket;
import com.zhongbai233.net_music_can_play_bili.network.MediaToolConfirmBindingPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public class MediaToolBindingScreen extends AbstractContainerScreen<MediaToolBindingMenu> {
    private static final int WIDTH = 220;
    private static final int HEIGHT = 210;

    public MediaToolBindingScreen(MediaToolBindingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        int lx = leftPos, ty = topPos;
        addRenderableWidget(new BlackGoldButton(lx + 153, ty + 62, 20, 20,
                Component.literal("↓"),
                button -> PacketDistributor.sendToServer(new MediaToolConfirmBindingPacket()),
                BlackGoldUi.GOLD));
        addRenderableWidget(new BlackGoldButton(lx + 22, ty + 80, 70, 20,
                Component.translatable("gui.net_music_can_play_bili.media_tool.clear"),
                button -> PacketDistributor.sendToServer(new MediaToolClearBindingPacket()),
                BlackGoldUi.GOLD));
    }

    /** 1.21.1:面板绘制进 renderBg(替代 26.x extractBackground/extractRenderState 拆分)。 */
    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        BlackGoldUi.drawBackground(guiGraphics, width, height);
        int lx = leftPos, ty = topPos;
        BlackGoldUi.drawPanel(guiGraphics, lx, ty, imageWidth, imageHeight);
        BlackGoldUi.drawHeader(guiGraphics, font, title, lx, ty, imageWidth, 26);

        int contentTop = ty + 30;
        int contentBottom = ty + 112;
        guiGraphics.fillGradient(lx + 10, contentTop, lx + imageWidth - 10, contentBottom, 0xAA181818,
                0xAA202018);

        int dividerY = contentBottom + 2;
        guiGraphics.fillGradient(lx + 10, dividerY, lx + imageWidth - 10, dividerY + 1, BlackGoldUi.GOLD_DIM,
                BlackGoldUi.GOLD_DIM);

        BlackGoldUi.drawSlotFrame(guiGraphics, lx + 42, ty + 42, BlackGoldUi.GOLD_DIM);
        BlackGoldUi.drawSlotFrame(guiGraphics, lx + 154, ty + 42, 0xFF6688AA);
        BlackGoldUi.drawSlotFrame(guiGraphics, lx + 154, ty + 84, 0xFF66AA77);

        Component targetLabel = Component.translatable(menu.usesManualMp4TargetSlot()
                ? menu.targetKind() == MediaToolBindingMenu.TargetKind.PAD
                        ? "gui.net_music_can_play_bili.media_tool.pad_input"
                        : "gui.net_music_can_play_bili.media_tool.mp4_input"
                : "gui.net_music_can_play_bili.media_tool.target");
        guiGraphics.drawCenteredString(font, targetLabel, lx + 52, ty + 28, BlackGoldUi.GOLD);

        guiGraphics.drawString(font, Component.translatable("gui.net_music_can_play_bili.media_tool.bound_count",
                menu.headphoneBindingCount(), menu.holographicBindingCount(), menu.totalTargetBindingCount()),
                lx + 16, ty + 68, BlackGoldUi.TEXT_SECONDARY, false);

        guiGraphics.drawCenteredString(font, Component.translatable("gui.net_music_can_play_bili.media_tool.input"),
                lx + 164, ty + 28, BlackGoldUi.TEXT_PRIMARY);
        guiGraphics.drawCenteredString(font, Component.translatable("gui.net_music_can_play_bili.media_tool.output"),
                lx + 164, ty + 108, BlackGoldUi.TEXT_PRIMARY);
    }

    /** 26.x extractRenderState → render：容器默认渲染（背景+面板 → 控件 → 槽位 → renderLabels）。 */
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
    }
}
