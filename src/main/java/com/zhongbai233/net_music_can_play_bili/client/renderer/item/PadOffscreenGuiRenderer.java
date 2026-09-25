package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.client.PadHandheldMediaProfile;
import com.zhongbai233.net_music_can_play_bili.client.PadRenderProperties;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapProjection;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadPerfLogger;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pad 显卡离屏界面渲染器,输出固定横屏 448x256 逻辑纹理。
 *
 * <p>1.21.1 无 26.x 的 GuiRenderer/GuiRenderState/SubmitNodeStorage 离屏 GUI 管线:改为
 * 直接把同一套 GuiGraphics 绘制(私有 BufferSource)画进自建 {@link TextureTarget},
 * 再经 {@link RenderTargetTexture} 以动态纹理暴露给世界内屏幕渲染。</p>
 */
final class PadOffscreenGuiRenderer implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PadRenderProperties.Offscreen PROPERTIES = PadRenderProperties.offscreen();
    private static final int SCALE = PROPERTIES.scale();
    private static final float MAP_PAN_RENDER_BLOCKS = PROPERTIES.mapPanRenderBlocks();
    private static final float MAP_YAW_RENDER_DEGREES = PROPERTIES.mapYawRenderDegrees();
    private static final int PLAYBACK_REFRESH_TICKS = PROPERTIES.playbackRefreshTicks();
    private static final int WIDTH = PadGuiTexture.WIDTH;
    private static final int HEIGHT = PadGuiTexture.HEIGHT;
    private static final int TARGET_WIDTH = WIDTH * Math.max(1, SCALE);
    private static final int TARGET_HEIGHT = HEIGHT * Math.max(1, SCALE);
    private static final long MAX_GUI_FPS = PROPERTIES.maxFps();
    private static final long MIN_FRAME_INTERVAL_NANOS = MAX_GUI_FPS <= 0L ? 0L : 1_000_000_000L / MAX_GUI_FPS;
    private final ResourceLocation textureId;
    private TextureTarget target;
    private RenderTargetTexture texture;
    private MultiBufferSource.BufferSource guiBuffer;
    /** {@code guiBuffer} 底层的 off-heap 缓冲；1.21.1 的 BufferSource 不负责释放它。 */
    private ByteBufferBuilder guiBufferBuilder;
    private boolean registered;
    private boolean failed;
    private boolean loggedReady;
    private PadGuiViewState lastView;
    private int lastRenderedTick = Integer.MIN_VALUE;
    private long lastRenderedNanos;
    private long lastFocusRevision = Long.MIN_VALUE;
    private final PadMapRenderContext mapRenderContext;

    PadOffscreenGuiRenderer(String textureKey) {
        String safeKey = textureKey == null || textureKey.isBlank() ? "fallback"
                : textureKey.toLowerCase(java.util.Locale.ROOT);
        this.textureId = ResourceLocation.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/pad_gui_offscreen_" + safeKey);
        this.mapRenderContext = new PadMapRenderContext(safeKey);
    }

    ResourceLocation textureId(UUID deviceId) {
        request(deviceId);
        return textureId;
    }

    void request(UUID deviceId) {
        if (failed) {
            return;
        }
        try {
            ensureResources();
        } catch (RuntimeException ex) {
            failed = true;
            close();
            LOGGER.warn("Pad 离屏 GUI 初始化失败: {}", textureId, ex);
        }
    }

    void tickMapLayer(UUID deviceId) {
        if (failed) {
            return;
        }
        try {
            PadGuiViewState view = PadGuiViewState.capture(deviceId);
            mapRenderContext.tick(view.map());
        } catch (RuntimeException ex) {
            failed = true;
            close();
            LOGGER.warn("Pad 地图离屏层 tick 更新失败: {}", textureId, ex);
        }
    }

    void renderFrameStart(UUID deviceId, float partialTick) {
        if (failed) {
            return;
        }
        try {
            ensureResources();
            MP4HandheldVideoClient.update(deviceId, PadHandheldMediaProfile.INSTANCE);
            PadGuiViewState view = PadGuiViewState.capture(deviceId, partialTick);
            long now = System.nanoTime();
            if (!shouldRender(view)) {
                return;
            }
            if (!shouldRenderNow(view, now)) {
                return;
            }
            long started = System.nanoTime();
            render(view);
            lastView = view;
            lastRenderedTick = view.ticks();
            lastFocusRevision = view.focusRevision();
            PadPerfLogger.recordGuiFrame(System.nanoTime() - started);
            if (!loggedReady) {
                loggedReady = true;
                LOGGER.info("Pad 离屏 GUI 已启用: texture={} target={}x{} logical={}x{} scale={}", textureId,
                        TARGET_WIDTH, TARGET_HEIGHT, WIDTH, HEIGHT, Math.max(1, SCALE));
            }
        } catch (RuntimeException ex) {
            failed = true;
            close();
            LOGGER.warn("Pad 离屏 GUI 渲染失败: {}", textureId, ex);
        }
    }

    private void ensureResources() {
        if (target != null) {
            return;
        }
        target = new TextureTarget(TARGET_WIDTH, TARGET_HEIGHT, true, Minecraft.ON_OSX);
        target.setFilterMode(9729);
        texture = new RenderTargetTexture(target);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getTextureManager().register(textureId, texture);
        registered = true;
        // 隔离 BufferSource:本渲染器在世界渲染事件层级调用,绝不能 flush 共享 GUI/世界批次。
        guiBufferBuilder = new ByteBufferBuilder(4096);
        guiBuffer = MultiBufferSource.immediate(guiBufferBuilder);
    }

    private boolean shouldRender(PadGuiViewState view) {
        if (lastView == null) {
            return true;
        }
        if (view.focusRevision() != lastFocusRevision
                || view.document().sequence() != lastView.document().sequence()
                || view.document().locked() != lastView.document().locked()
                || view.document().mediaEntries().size() != lastView.document().mediaEntries().size()
                || view.document().triggerPoints().size() != lastView.document().triggerPoints().size()
                || view.map() != lastView.map()) {
            return true;
        }
        if (mapPanChanged(view, lastView) || yawChanged(view.playerYaw(), lastView.playerYaw())) {
            return true;
        }
        return shouldRefreshPlayback(view);
    }

    private boolean mapPanChanged(PadGuiViewState view, PadGuiViewState previous) {
        float dx = view.playerX() - previous.playerX();
        float dz = view.playerZ() - previous.playerZ();
        return dx * dx + dz * dz >= MAP_PAN_RENDER_BLOCKS * MAP_PAN_RENDER_BLOCKS;
    }

    private boolean yawChanged(float yaw, float previousYaw) {
        float delta = Math.abs(Math.floorMod(Math.round((yaw - previousYaw) * 100.0F + 18000.0F), 36000) / 100.0F
                - 180.0F);
        return delta >= MAP_YAW_RENDER_DEGREES;
    }

    private boolean shouldRefreshPlayback(PadGuiViewState view) {
        return view.deviceId() != null
                && ClientMediaPlayback.hasPlayback(view.deviceId())
                && view.ticks() - lastRenderedTick >= PLAYBACK_REFRESH_TICKS;
    }

    private boolean shouldRenderNow(PadGuiViewState view, long now) {
        if (lastView == null || view.map() != lastView.map() || view.focusRevision() != lastFocusRevision) {
            return true;
        }
        return MIN_FRAME_INTERVAL_NANOS <= 0L || now - lastRenderedNanos >= MIN_FRAME_INTERVAL_NANOS;
    }

    private void render(PadGuiViewState view) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableBlend();
        target.bindWrite(true);
        RenderSystem.enableScissor(0, 0, TARGET_WIDTH, TARGET_HEIGHT);
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        RenderSystem.clear(16640, Minecraft.ON_OSX);
        // GuiGraphics 输出的是逻辑坐标(WIDTH x HEIGHT),不是离屏纹理的像素尺寸。
        // 上游 26.x 用 TARGET_WIDTH/HEIGHT 是因为它的 GuiRenderer 内部还有一层 scale;
        // 1.21.1 直接用 GuiGraphics,投影必须覆盖逻辑尺寸,否则界面只占左上角 1/SCALE^2。
        Matrix4f projection = new Matrix4f().setOrtho(0.0F, WIDTH, HEIGHT, 0.0F, -21000.0F, 21000.0F);
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
        try {
            // GuiGraphics 的每个绘制方法(fill/drawString/...)末尾都会调用 flushIfUnmanaged(),
            // 在 managed=false 的默认状态下立即 endBatch。因此它必须在离屏 target 已绑定、
            // 投影已就绪之后才构造并绘制;否则全部顶点会落到当时绑定的 framebuffer(主屏),
            // 离屏纹理只剩 clear 的结果 —— 屏幕上一片空白的根因。
            GuiGraphics graphics = new GuiGraphics(minecraft, guiBuffer);
            drawGui(graphics, view);
            graphics.flush();
            guiBuffer.endBatch();
            texture.refreshView();
        } finally {
            RenderSystem.disableScissor();
            target.unbindWrite();
            minecraft.getMainRenderTarget().bindWrite(true);
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.disableBlend();
            lastRenderedNanos = System.nanoTime();
        }
    }

    private void drawGui(GuiGraphics g, PadGuiViewState view) {
        if (view.document().locked()) {
            if (view.transparentVideoOverlay()) {
                drawLockedVideoOverlay(g, view);
                return;
            }
            drawMapCard(g, view, 0, 0, WIDTH, HEIGHT, false);
            drawLockedAudioProgress(g, view);
            return;
        }
        fill(g, 0, 0, WIDTH, HEIGHT, 0xFF071018);
        fill(g, 3, 3, WIDTH - 6, HEIGHT - 6, 0xFF101A26);
        drawStatusBar(g, view);
        drawMapCard(g, view, 14, 44, 270, 198, true);
        drawMediaLibrary(g, view, 300, 48, 132, 98);
        if (hasSelectedPoint(view)) {
            drawPointEditor(g, view, 300, 130, 132, 86);
        } else {
            drawPlaybackCard(g, view, 300, 166, 132, 42);
        }
        drawBottomHint(g, view);
        drawFocusFeedback(g);
        outline(g, 1, 1, WIDTH - 2, HEIGHT - 2, 0xFF02060A);
        outline(g, 5, 5, WIDTH - 10, HEIGHT - 10, 0x6637A9FF);
    }

    private void drawLockedVideoOverlay(GuiGraphics g, PadGuiViewState view) {
        if (view.controlsVisible()) {
            drawLockedVideoControls(g, view);
        } else {
            drawLockedVideoSubtitle(g, view, 206);
        }
        drawFocusFeedback(g);
    }

    private void drawFocusFeedback(GuiGraphics g) {
        boolean locked = lastView != null && lastView.document().locked();
        drawControlFeedback(g, "MAP", locked ? 0 : 14, locked ? 0 : 44, locked ? WIDTH : 270, locked ? HEIGHT : 198);
        if (locked) {
            return;
        }
        drawControlFeedback(g, "MEDIA", 300, 48, 132, 98);
        drawControlFeedback(g, "EDITOR", 300, 130, 132, 86);
        drawControlFeedback(g, "PLAYBACK", 300, 166, 132, 42);
        drawControlFeedback(g, "PUBLISH", 300, 220, 132, 18);
    }

    private void drawControlFeedback(GuiGraphics g, String control, int x, int y, int w, int h) {
        if (com.zhongbai233.net_music_can_play_bili.client.PadFocusState.pressControl(control)) {
            outline(g, x - 2, y - 2, w + 4, h + 4, 0xFFFFD166);
            fill(g, x, y, w, h, 0x22FFD166);
        } else if (com.zhongbai233.net_music_can_play_bili.client.PadFocusState.hoverControl(control)) {
            outline(g, x - 1, y - 1, w + 2, h + 2, 0xAA89D6FF);
        }
    }

    private void drawStatusBar(GuiGraphics g, PadGuiViewState view) {
        fill(g, 8, 8, WIDTH - 16, 26, 0xFF172638);
        text(g, 20, 16, "PAD MAP / 横屏", 0xFFBDE7FF);
        String right = view.document().locked() ? "LOCKED" : "DRAFT";
        text(g, WIDTH - 20 - Minecraft.getInstance().font.width(right), 16, right,
                view.document().locked() ? 0xFFFFC857 : 0xFF6FE28A);
    }

    private void drawMapCard(GuiGraphics g, PadGuiViewState view, int x, int y, int w, int h,
            boolean framed) {
        if (framed) {
            fill(g, x, y, w, h, 0xFF1B2A38);
            outline(g, x, y, w, h, 0xFF2F4A60);
        }
        int inset = framed ? 6 : 0;
        int innerX = x + inset;
        int innerY = y + inset;
        int innerW = w - inset * 2;
        int innerH = h - inset * 2;
        PadMapProjection.Rect mapRect = framed ? PadMapProjection.fitRect(innerX, innerY, innerW, innerH,
                PadMapLayerTexture.VIEW_WIDTH, PadMapLayerTexture.VIEW_HEIGHT, WIDTH / (float) HEIGHT)
                : new PadMapProjection.Rect(innerX, innerY, innerW, innerH);
        mapRenderContext.draw(g, view, mapRect);
        if (framed) {
            drawMapHoleFrame(g, x, y, w, h, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
        }
    }

    private void drawMapHoleFrame(GuiGraphics g, int x, int y, int w, int h, int innerX, int innerY,
            int innerW, int innerH) {
        outline(g, x, y, w, h, 0xFF2F4A60);
        outline(g, innerX - 1, innerY - 1, innerW + 2, innerH + 2, 0x6637A9FF);
    }

    private void drawMediaLibrary(GuiGraphics g, PadGuiViewState view, int x, int y, int w, int h) {
        fill(g, x, y, w, h, 0xFF111B27);
        outline(g, x, y, w, h, 0xFF2F4A60);
        text(g, x + 10, y + 10, "媒体库 " + view.document().mediaEntries().size(), 0xFFE8E0C8);
        text(g, x + 88, y + 10, "点位 " + view.document().triggerPoints().size(), 0xFFFFC857);
        if (view.document().mediaEntries().isEmpty()) {
            centeredText(g, x + w / 2, y + 52, "暂无歌曲", 0xFF7E8EA3);
            centeredText(g, x + w / 2, y + 68, "把唱片放到 Pad", 0xFF526173);
            return;
        }
        int row = 0;
        for (PadMediaEntry entry : view.document().mediaEntries()) {
            if (row >= 4) {
                break;
            }
            int rowY = y + 30 + row * 16;
            fill(g, x + 8, rowY, w - 16, 15, 0xFF1B2A38);
            String name = mediaName(entry);
            text(g, x + 14, rowY + 4, trim(name, 10), 0xFFEAF2FF);
            text(g, x + w - 32, rowY + 4, "#" + entry.mediaId(), 0xFF89D6FF);
            row++;
        }
        if (com.zhongbai233.net_music_can_play_bili.client.PadFocusState.draggingMedia()) {
            centeredText(g, x + w / 2, y + h - 15, "拖到地图创建点位", 0xFFFFD166);
        }
    }

    private void drawPointEditor(GuiGraphics g, PadGuiViewState view, int x, int y, int w, int h) {
        Optional<PadTriggerPoint> selected = view.document().triggerPoints().stream()
                .filter(point -> com.zhongbai233.net_music_can_play_bili.client.PadFocusState
                        .selectedPoint(point.pointId()))
                .findFirst();
        if (selected.isEmpty()) {
            return;
        }
        PadTriggerPoint point = selected.get();
        fill(g, x, y, w, h, 0xEE0E1824);
        outline(g, x, y, w, h, 0xFFFFD166);
        text(g, x + 8, y + 7, trim(point.name().isBlank() ? "点位" : point.name(), 12), 0xFFFFF3C4);
        text(g, x + 8, y + 18,
                "R" + point.radiusBlocks() + " V" + point.volumePerMille() / 10 + "% #" + point.mediaId(),
                0xFF89D6FF);
        drawEditorButton(g, x + 8, y + 28, 52, 15, point.visible() ? "显示" : "隐藏",
                point.visible() ? 0xFFE84A5F : 0xFF7E8EA3);
        drawEditorButton(g, x + 66, y + 28, 58, 15, "删除", 0xFFFF6B6B);
        drawEditorButton(g, x + 8, y + 45, 28, 15, "R-", 0xFFBDE7FF);
        drawEditorButton(g, x + 40, y + 45, 28, 15, "R+", 0xFFBDE7FF);
        drawEditorButton(g, x + 74, y + 45, 50, 15, point.loop() ? "循环" : "单次", 0xFF6FE28A);
        drawEditorButton(g, x + 8, y + 62, 52, 15,
                point.triggerMode().name().equals("MANUAL") ? "手动" : "半径", 0xFFFFC857);
        drawEditorButton(g, x + 66, y + 62, 28, 15, "V-", 0xFFBDE7FF);
        drawEditorButton(g, x + 96, y + 62, 28, 15, "V+", 0xFFBDE7FF);
    }

    private boolean hasSelectedPoint(PadGuiViewState view) {
        return view.document().triggerPoints().stream()
                .anyMatch(point -> com.zhongbai233.net_music_can_play_bili.client.PadFocusState
                        .selectedPoint(point.pointId()));
    }

    private void drawEditorButton(GuiGraphics g, int x, int y, int w, int h, String label, int color) {
        fill(g, x, y, w, h, 0xFF1B2A38);
        outline(g, x, y, w, h, color);
        centeredText(g, x + w / 2, y + 4, label, color);
    }

    private void drawBottomHint(GuiGraphics g, PadGuiViewState view) {
        fill(g, 300, 220, 132, 18, 0xFF172638);
        outline(g, 300, 220, 132, 18, 0xFF6FE28A);
        centeredText(g, 366, 225, "发布副本", 0xFF6FE28A);
    }

    private void drawLockedAudioProgress(GuiGraphics g, PadGuiViewState view) {
        UUID deviceId = view.deviceId();
        boolean playing = deviceId != null && ClientMediaPlayback.hasPlayback(deviceId);
        boolean paused = PadFocusState.pausedPlaybackAvailable() && !PadFocusState.pausedVideo();
        if (deviceId == null || (!playing && !paused)) {
            return;
        }
        long elapsed = playing ? Math.max(0L, ClientMediaPlayback.elapsedMillis(deviceId))
                : PadFocusState.pausedElapsedMillis();
        long duration = playing ? Math.max(0L, ClientMediaPlayback.durationMillis(deviceId))
                : PadFocusState.pausedDurationMillis();
        String song = ClientMediaPlayback.songName(deviceId);
        String lyric = playing
                ? (view.subtitlePrimaryMode() ? ClientMediaPlayback.lyricLine(deviceId)
                        : ClientMediaPlayback.translatedLyricLine(deviceId))
                : "";
        if (playing && (lyric == null || lyric.isBlank())) {
            lyric = view.subtitlePrimaryMode() ? ClientMediaPlayback.translatedLyricLine(deviceId)
                    : ClientMediaPlayback.lyricLine(deviceId);
        }
        if (view.subtitlesEnabled() && lyric != null && !lyric.isBlank()) {
            drawVideoSubtitle(g, lyric, 158);
        }
        fill(g, 24, 178, WIDTH - 48, 64, 0xBB05070C);
        outline(g, 24, 178, WIDTH - 48, 64, 0x664F6278);
        text(g, 36, 184, trim(song == null || song.isBlank() ? "Pad 音乐播放" : song, 24), 0xFFFFF3C4);
        String time = timeLabel(elapsed, duration);
        text(g, WIDTH - 36 - Minecraft.getInstance().font.width(time), 184, time, 0xFF89D6FF);
        drawProgressPercent(g, 36, 199, WIDTH - 72, 5, view.mediaProgress());
        drawStopButton(g, 146, 210, 30, 24, 0xAA1C2230, 0xFFFF8B8B);
        playPauseButton(g, 200, 206, 46, 32, playing ? 0xDD70D8FF : 0xDDFFD26E, 0xFF071018, playing);
        smallToggle(g, 270, 210, 54, 24, view.subtitleMenuOpen() ? 0xAA4D3568 : 0xAA202635, "字幕");
        if (view.subtitleMenuOpen()) {
            drawSubtitleMenu(g, view);
        }
    }

    private void drawLockedVideoControls(GuiGraphics g, PadGuiViewState view) {
        UUID deviceId = view.deviceId();
        boolean playing = deviceId != null && ClientMediaPlayback.hasPlayback(deviceId);
        long elapsed = deviceId != null ? Math.max(0L, ClientMediaPlayback.elapsedMillis(deviceId)) : 0L;
        long duration = deviceId != null ? Math.max(0L, ClientMediaPlayback.durationMillis(deviceId)) : 0L;
        String title = deviceId != null ? ClientMediaPlayback.songName(deviceId) : "";

        fill(g, 0, 0, WIDTH, 10, 0x9905070C);
        fill(g, 0, HEIGHT - 10, WIDTH, 10, 0x9905070C);
        fill(g, 0, 10, 10, HEIGHT - 20, 0x9905070C);
        fill(g, WIDTH - 10, 10, 10, HEIGHT - 20, 0x9905070C);
        outline(g, 7, 7, WIDTH - 14, HEIGHT - 14, 0x66273040);

        fill(g, 14, 14, WIDTH - 28, 26, 0x77000000);
        text(g, 25, 21, "Pad 视频播放", 0xFFEAF2FF);
        text(g, 128, 21, playing ? "LIVE PLAYBACK" : "PAUSED", playing ? 0xFF6DFFB0 : 0xFFFFC46D);
        smallToggle(g, 278, 18, 44, 16, view.subtitleMenuOpen() ? 0xAA4D3568 : 0xAA202635, "字幕");
        text(g, 333, 22, view.qualityLabel(), 0xFFDCEBFF);
        String right = timeLabel(elapsed, duration);
        text(g, WIDTH - 24 - Minecraft.getInstance().font.width(right), 21, right, 0xFF89D6FF);

        fill(g, 16, 171, WIDTH - 32, 66, 0xAA05070C);
        drawProgressPercent(g, 32, 184, WIDTH - 64, 6, view.mediaProgress());
        text(g, 32, 196, formatTime(elapsed), 0xFFB8C5DE);
        text(g, WIDTH - 60, 196, formatTime(duration), 0xFFB8C5DE);
        drawStopButton(g, 146, 204, 30, 24, 0xAA1C2230, 0xFFFF8B8B);
        playPauseButton(g, 200, 198, 46, 34, playing ? 0xDD70D8FF : 0xDDFFD26E, 0xFF071018, playing);
        smallToggle(g, 270, 204, 54, 24, view.qualityMenuOpen() ? 0xAA234969 : 0xAA202635, "画质");
        centeredText(g, 224, 225, trim(title == null || title.isBlank() ? "Pad 播放中" : title, 28), 0xFFFFF3C4);
        drawLockedVideoSubtitle(g, view, 145);
        if (view.subtitleMenuOpen()) {
            drawSubtitleMenu(g, view);
        }
        if (view.qualityMenuOpen()) {
            drawQualityMenu(g, view);
        }
    }

    private void drawSubtitleMenu(GuiGraphics g, PadGuiViewState view) {
        fill(g, 232, 48, 108, 114, 0xEE10141E);
        text(g, 248, 55, "字幕设置", 0xFF8CCBFF);
        subtitleOption(g, 244, 72, 52, 14, "关", !view.subtitlesEnabled());
        subtitleOption(g, 244, 94, 52, 14, "主", view.subtitlesEnabled() && view.subtitlePrimaryMode());
        subtitleOption(g, 244, 116, 52, 14, "副", view.subtitlesEnabled() && !view.subtitlePrimaryMode());
        subtitleOption(g, 244, 138, 82, 14, "AI字幕", view.subtitleAiEnabled());
    }

    private void drawQualityMenu(GuiGraphics g, PadGuiViewState view) {
        fill(g, 318, 42, 94, 132, 0xEE10141E);
        text(g, 350, 50, "画质", 0xFF8CCBFF);
        for (int i = 0; i < com.zhongbai233.net_music_can_play_bili.client.PadFocusState.QUALITIES.length; i++) {
            String quality = com.zhongbai233.net_music_can_play_bili.client.PadFocusState.QUALITIES[i];
            boolean selected = view.qualityLabel().equals(quality);
            fill(g, 330, 65 + i * 13, 70, 11, selected ? 0xFF263B5F : 0xFF171D2A);
            outline(g, 330, 65 + i * 13, 70, 11, selected ? 0xFF74C7FF : 0xFF394154);
            text(g, 336, 66 + i * 13, quality, selected ? 0xFFEAF2FF : 0xFFB8C5DE);
        }
    }

    private void subtitleOption(GuiGraphics g, int x, int y, int w, int h, String label, boolean selected) {
        fill(g, x, y, w, h, selected ? 0xFF263B5F : 0xFF171D2A);
        outline(g, x, y, h, h, selected ? 0xFF74C7FF : 0xFF5A6378);
        if (selected) {
            text(g, x + 3, y + 2, "✓", 0xFFEAF2FF);
        }
        text(g, x + h + 5, y + 2, label, 0xFFEAF2FF);
    }

    private void drawLockedVideoSubtitle(GuiGraphics g, PadGuiViewState view, int y) {
        if (!view.subtitlesEnabled()) {
            return;
        }
        UUID deviceId = view.deviceId();
        String subtitle = deviceId != null ? MP4HandheldVideoClient.currentSubtitle(deviceId) : "";
        if (subtitle != null && !subtitle.isBlank()) {
            drawVideoSubtitle(g, subtitle, y);
        }
    }

    private void drawVideoSubtitle(GuiGraphics g, String subtitle, int y) {
        Font font = Minecraft.getInstance().font;
        int textWidth = Math.min(408, font.width(subtitle));
        int bgWidth = Math.min(420, Math.max(36, textWidth + 18));
        int bgX = 224 - bgWidth / 2;
        fill(g, bgX, y - 3, bgWidth, 15, 0x77000000);
        fill(g, bgX + 1, y - 2, bgWidth - 2, 13, 0x55203048);
        centeredText(g, 224, y, subtitle, 0xF2DCEBFF);
    }

    private void drawProgressPercent(GuiGraphics g, int x, int y, int w, int h, float progress) {
        fill(g, x, y, w, h, 0xFF32384A);
        int filled = Math.max(h, Math.round(w * Math.max(0.0F, Math.min(1.0F, progress))));
        fill(g, x, y, Math.min(w, filled), h, 0xFF74C7FF);
        fill(g, x + Math.min(w, filled) - h, y - h / 2, h * 2, h * 2, 0xFF9CA3AD);
        outline(g, x + Math.min(w, filled) - h, y - h / 2, h * 2, h * 2, 0xFF4B515D);
    }

    private void drawStopButton(GuiGraphics g, int x, int y, int w, int h, int bg, int fg) {
        fill(g, x + 3, y + 4, w, h, 0x55000000);
        fill(g, x, y, w, h, bg);
        outline(g, x, y, w, h, 0xFF394154);
        int side = Math.min(w, h) / 3;
        fill(g, x + w / 2 - side / 2, y + h / 2 - side / 2, side, side, fg);
    }

    private static final int[] PLAY_TRIANGLE_ROWS = { 1, 3, 5, 7, 9, 11, 9, 7, 5, 3, 1 };

    private void playPauseButton(GuiGraphics g, int x, int y, int w, int h, int bg, int fg,
            boolean playing) {
        fill(g, x + 3, y + 4, w, h, 0x55000000);
        fill(g, x, y, w, h, bg);
        outline(g, x, y, w, h, 0xFF394154);
        if (playing) {
            int barW = Math.max(2, w / 14);
            int barH = Math.min(w, h) / 3;
            int gap = Math.max(2, w / 18);
            fill(g, x + w / 2 - gap / 2 - barW, y + h / 2 - barH / 2, barW, barH, fg);
            fill(g, x + w / 2 + gap / 2, y + h / 2 - barH / 2, barW, barH, fg);
        } else {
            drawTriangleForward(g, x + w / 2, y + h / 2, fg);
        }
    }

    private void drawTriangleForward(GuiGraphics g, int cx, int cy, int color) {
        int left = cx - PLAY_TRIANGLE_ROWS[PLAY_TRIANGLE_ROWS.length / 2] / 2;
        int top = cy - PLAY_TRIANGLE_ROWS.length / 2;
        for (int row = 0; row < PLAY_TRIANGLE_ROWS.length; row++) {
            fill(g, left, top + row, PLAY_TRIANGLE_ROWS[row], 1, color);
        }
    }

    private void smallToggle(GuiGraphics g, int x, int y, int w, int h, int bg, String label) {
        fill(g, x, y, w, h, bg);
        outline(g, x, y, w, h, 0xFF394154);
        centeredText(g, x + w / 2, y + h / 2 - 4, label, 0xFFDCEBFF);
    }

    private String mediaName(PadMediaEntry entry) {
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(entry.disc());
        if (info != null && info.songName != null && !info.songName.isBlank()) {
            return info.songName;
        }
        return "歌曲 #" + entry.mediaId();
    }

    private String trim(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private void drawPlaybackCard(GuiGraphics g, PadGuiViewState view, int x, int y, int w, int h) {
        fill(g, x, y, w, h, 0xFF111B27);
        outline(g, x, y, w, h, 0xFF2F4A60);
        UUID deviceId = view.deviceId();
        if (deviceId != null && ClientMediaPlayback.hasPlayback(deviceId)) {
            String song = ClientMediaPlayback.songName(deviceId);
            long elapsed = ClientMediaPlayback.elapsedMillis(deviceId);
            long duration = ClientMediaPlayback.durationMillis(deviceId);
            centeredText(g, x + w / 2, y + 6, trim(song.isBlank() ? "Pad 播放中" : song, 14), 0xFFFFF3C4);
            drawProgressBar(g, x + 10, y + 22, w - 20, 5, elapsed, duration);
            centeredText(g, x + w / 2, y + 31, timeLabel(elapsed, duration) + " · 点按停止", 0xFF89D6FF);
            return;
        }
        centeredText(g, x + w / 2, y + 9, "Pad 播放目标", 0xFFE8E0C8);
        centeredText(g, x + w / 2, y + 25, "点击点位播放", 0xFF89D6FF);
    }

    private void drawProgressBar(GuiGraphics g, int x, int y, int w, int h, long elapsed, long duration) {
        fill(g, x, y, w, h, 0xFF243243);
        int filled = duration > 0L ? Math.round(Math.max(0L, Math.min(duration, elapsed)) * w / (float) duration) : 0;
        if (filled > 0) {
            fill(g, x, y, Math.min(w, filled), h, 0xFF6FE28A);
        }
        outline(g, x, y, w, h, 0xFF2F4A60);
    }

    private String timeLabel(long elapsed, long duration) {
        if (duration <= 0L) {
            return formatTime(elapsed);
        }
        return formatTime(elapsed) + "/" + formatTime(duration);
    }

    private String formatTime(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return (seconds / 60L) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60L);
    }

    private void fill(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    /** 1.21.1 GuiGraphics 无 outline:用四条 1px 填充重建 26.x 描边语义。 */
    private void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void text(GuiGraphics g, int x, int y, String value, int color) {
        Font font = Minecraft.getInstance().font;
        if (value == null || value.isEmpty()) {
            return;
        }
        g.drawString(font, value, x, y, color);
    }

    private void centeredText(GuiGraphics g, int centerX, int y, String value, int color) {
        Font font = Minecraft.getInstance().font;
        if (value == null || value.isEmpty()) {
            return;
        }
        g.drawString(font, Component.literal(value), centerX - font.width(value) / 2, y, color);
    }

    @Override
    public void close() {
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            registered = false;
        }
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
        texture = null;
        guiBuffer = null;
        if (guiBufferBuilder != null) {
            guiBufferBuilder.close();
            guiBufferBuilder = null;
        }
        mapRenderContext.close();
    }
}