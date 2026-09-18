package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewFrame;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainWireframeMesher;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainFixedCorePolicy;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortPictureInPictureRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全息编辑器的 3D 场景预览渲染器(26.x 的 GUI 图集 PIP 在 1.21.1 无等价物)。
 *
 * <p>由 {@link com.zhongbai233.net_music_can_play_bili.gui.HolographicEditorRenderScreen} 每帧驱动:
 * {@link #render(HolographicPreviewPipRenderState)} 把场景画进私有 Framebuffer,
 * 屏幕再用 {@link com.zhongbai233.net_music_can_play_bili.port.shim.PortGuiFramebufferBlitter}
 * 把颜色纹理贴回预览矩形。26.x 的 SubmitNodeStorage/FeatureRenderDispatcher/BlockDisplayContext
 * 降级为 1.21.1 立即提交:EntityRenderDispatcher.render / BlockRenderDispatcher.renderSingleBlock /
 * BlockEntityRenderer.render 全部直接写入本渲染器自有的 BufferSource。</p>
 */
public final class HolographicPreviewPipRenderer
        extends PortPictureInPictureRenderer<HolographicPreviewPipRenderState> {
    private static final Logger LOGGER = LoggerFactory.getLogger(HolographicPreviewPipRenderer.class);
    private static final float SCENE_ORIGIN_Y_RATIO = 0.72F;
    private static final float SCREEN_FACE_EPSILON = 0.0025F;
    private static final float DEFAULT_PREVIEW_SCALE = HolographicScreenSettings.DEFAULT_PREVIEW_SCALE;
    private static final float ORBIT_FOV_DEGREES = HolographicScreenSettings.ORBIT_FOV_DEGREES;
    private static final float ORBIT_DEFAULT_CAMERA_DISTANCE = HolographicScreenSettings.ORBIT_DEFAULT_CAMERA_DISTANCE;
    private static final float ORBIT_TARGET_Y = HolographicScreenSettings.ORBIT_TARGET_Y;
    private static final float GIZMO_AXIS_WORLD_LEN = HolographicScreenSettings.GIZMO_AXIS_WORLD_LEN;
    private static final float GIZMO_ARROW_LEN = 0.12F;
    private static final float GIZMO_ARROW_WING = 0.065F;
    private static final float GIZMO_LABEL_SIZE = 0.055F;
    private static final int GIZMO_RING_SEGMENTS = 48;
    private static final float PLAYER_EYE_Y = 1.62F;
    private static final ThreadLocal<Float> ACTIVE_LINE_WIDTH_SCALE = ThreadLocal.withInitial(() -> 1.0F);

    private static volatile HolographicPreviewPipRenderer instance;

    private final TerrainPreviewGpuCache terrainGpuCache = new TerrainPreviewGpuCache();

    /** 屏幕直接驱动的单例(1.21.1 无 PIP 注册表驱动路径)。 */
    public static HolographicPreviewPipRenderer acquire() {
        HolographicPreviewPipRenderer renderer = instance;
        if (renderer == null) {
            synchronized (HolographicPreviewPipRenderer.class) {
                renderer = instance;
                if (renderer == null) {
                    renderer = new HolographicPreviewPipRenderer(null);
                    instance = renderer;
                }
            }
        }
        return renderer;
    }

    /** 26.x 注册点兼容构造:supplied 源在 1.21.1 中被忽略(基类使用隔离源)。 */
    public HolographicPreviewPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    public void render(HolographicPreviewPipRenderState state) {
        super.render(state, Math.max(1, state.x1() - state.x0()), Math.max(1, state.y1() - state.y0()));
    }

    @Override
    public Class<HolographicPreviewPipRenderState> getRenderStateClass() {
        return HolographicPreviewPipRenderState.class;
    }

    @Override
    protected void renderToTexture(HolographicPreviewPipRenderState state, PoseStack poseStack) {
        float previousLineWidthScale = ACTIVE_LINE_WIDTH_SCALE.get();
        ACTIVE_LINE_WIDTH_SCALE.set(lineWidthScale(state));
        if (!state.renderWorldTerrain()) {
            terrainGpuCache.releaseSession();
        }

        poseStack.pushPose();
        try {
            try {
                if (state.firstPerson()) {
                    // 第一人称分支仍沿用 GUI/PIP 坐标约定。
                    Lighting.setupForEntityInInventory();
                    renderFirstPersonPreview(poseStack, state);
                } else {
                    // 环绕分支使用标准世界坐标(+Y 向上),不能使用 Y 分量为负的 ENTITY_IN_UI 灯光。
                    // LEVEL 的双灯来自世界上方;灯光是 shader uniform,不依赖相机旋转的调用先后顺序。
                    Lighting.setupLevel();
                    renderOrbitPreview(poseStack, state);
                    bufferSource.endBatch();
                }
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                if (failure instanceof Error fatal) {
                    throw fatal;
                }
                TerrainPreviewRenderDiagnostics.recordFailure();
                LOGGER.warn("PIP holographic preview failed; skipping this frame", failure);
            }
        } finally {
            poseStack.popPose();
            ACTIVE_LINE_WIDTH_SCALE.set(previousLineWidthScale);
        }
    }

    private static float lineWidthScale(HolographicPreviewPipRenderState state) {
        if (state.firstPerson()) {
            return PreviewLineWidthPolicy.firstPerson();
        }
        CameraFrame frame = state.cameraFrame();
        if (frame == null) {
            return PreviewLineWidthPolicy.perspective(ORBIT_DEFAULT_CAMERA_DISTANCE);
        }
        if (frame.mode() == EditorCameraMode.ORTHOGRAPHIC) {
            float projectionScaleY = Math.abs(frame.matrices().projection().m11());
            float halfHeight = projectionScaleY > 1.0e-6F ? 1.0F / projectionScaleY : 1.0F;
            return PreviewLineWidthPolicy.orthographic(halfHeight);
        }
        Vector3f cameraPosition = frame.matrices().view().invert().getTranslation(new Vector3f());
        float dx = cameraPosition.x;
        float dy = cameraPosition.y - ORBIT_TARGET_Y;
        float dz = cameraPosition.z;
        return PreviewLineWidthPolicy.perspective((float) Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    private void renderOrbitPreview(PoseStack poseStack, HolographicPreviewPipRenderState state) {
        int width = Math.max(1, state.x1() - state.x0());
        int height = Math.max(1, state.y1() - state.y0());
        CameraFrame cameraFrame = state.cameraFrame();
        Matrix4f projection = cameraFrame != null ? cameraFrame.matrices().projection()
            : new Matrix4f().perspective((float) Math.toRadians(ORBIT_FOV_DEGREES),
                (float) width / (float) height, 0.05F, 100.0F);
        VertexSorting sorting = cameraFrame != null && cameraFrame.mode() == EditorCameraMode.ORTHOGRAPHIC
            ? VertexSorting.ORTHOGRAPHIC_Z : VertexSorting.DISTANCE_TO_ORIGIN;
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, sorting);
        try {
            PoseStack orbitPoseStack = new PoseStack();
            if (cameraFrame != null) {
                orbitPoseStack.mulPose(cameraFrame.matrices().view());
            } else {
                float previewScale = state.scale() / Math.max(1.0F, Math.min(width, height)) * 200.0F;
                float cameraDistance = ORBIT_DEFAULT_CAMERA_DISTANCE * DEFAULT_PREVIEW_SCALE
                        / Math.max(1.0F, previewScale);
                orbitPoseStack.translate(0.0F, 0.0F, -cameraDistance);
                orbitPoseStack.mulPose(Axis.XP.rotationDegrees(state.previewPitch()));
                orbitPoseStack.mulPose(Axis.YP.rotationDegrees(state.previewYaw()));
                orbitPoseStack.translate(0.0F, -ORBIT_TARGET_Y, 0.0F);
            }

            if (!state.renderWorldTerrain()) {
                submitDebugGarden(orbitPoseStack);
            }
            if (state.renderWorldTerrain()) {
                submitTerrainPreview(orbitPoseStack, state);
            }
            if (state.controlConsoleModel()) {
                renderControlConsole(orbitPoseStack);
            } else {
                renderPlayer(orbitPoseStack, state);
            }
            bufferSource.endBatch();

            drawGrid(orbitPoseStack);
            if (state.playerGlowing()) {
                drawPlayerGlowOutline(orbitPoseStack, state);
            }
            drawHolographicScreens(orbitPoseStack, state);
            if (selectedIndex(state) >= 0) {
                drawGizmo(orbitPoseStack, state);
            }
            bufferSource.endBatch();
        } finally {
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private void renderFirstPersonPreview(PoseStack ignoredPipPoseStack, HolographicPreviewPipRenderState state) {
        int width = Math.max(1, state.x1() - state.x0());
        int height = Math.max(1, state.y1() - state.y0());
        CameraFrame cameraFrame = state.cameraFrame();
        Matrix4f projection = cameraFrame != null ? cameraFrame.matrices().projection()
            : new Matrix4f().perspective((float) Math.toRadians(state.fovDegrees()),
                (float) width / (float) height, 0.05F, 100.0F);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        try {
            PoseStack poseStack = new PoseStack();
            if (cameraFrame != null) {
                poseStack.mulPose(cameraFrame.matrices().view());
            } else {
                poseStack.translate(0.0F, 0.0F, -0.001F);
                poseStack.scale(1.0F, -1.0F, -1.0F);
                poseStack.translate(0.0F, -PLAYER_EYE_Y, 0.0F);
            }
            drawHolographicScreensFrontOnly(poseStack, state);
            bufferSource.endBatch();
        } finally {
            RenderSystem.restoreProjectionMatrix();
        }
    }

    @Override
    public void close() {
        super.close();
        terrainGpuCache.close();
    }

    private void renderPlayer(PoseStack poseStack, HolographicPreviewPipRenderState state) {
        Minecraft minecraft = Minecraft.getInstance();
        LivingEntity player = state.playerEntity();
        if (player == null) {
            return;
        }
        // 1.21.1 无 EntityRenderState:按 InventoryScreen 的库存实体模式临时摆正姿态并在绘制后还原。
        float savedBodyRot = player.yBodyRot;
        float savedYRot = player.getYRot();
        float savedXRot = player.getXRot();
        float savedHeadRotO = player.yHeadRotO;
        float savedHeadRot = player.yHeadRot;
        player.yBodyRot = 0.0F;
        player.setYRot(0.0F);
        player.setXRot(0.0F);
        player.yHeadRot = 0.0F;
        player.yHeadRotO = 0.0F;

        EntityRenderDispatcher entityDispatcher = minecraft.getEntityRenderDispatcher();
        poseStack.pushPose();
        poseStack.translate(state.playerTranslation().x, state.playerTranslation().y, state.playerTranslation().z);
        poseStack.scale(state.playerScale(), state.playerScale(), state.playerScale());
        try {
            entityDispatcher.overrideCameraOrientation(new Quaternionf().rotateY((float) Math.PI));
            entityDispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() -> entityDispatcher.render(player, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F,
                    poseStack, bufferSource, LightTexture.FULL_BRIGHT));
            entityDispatcher.setRenderShadow(true);
        } finally {
            poseStack.popPose();
            player.yBodyRot = savedBodyRot;
            player.setYRot(savedYRot);
            player.setXRot(savedXRot);
            player.yHeadRotO = savedHeadRotO;
            player.yHeadRot = savedHeadRot;
        }
    }

    private void renderControlConsole(PoseStack poseStack) {
        Minecraft minecraft = Minecraft.getInstance();
        poseStack.pushPose();
        poseStack.translate(-0.5F, 0.0F, -0.5F);
        minecraft.getBlockRenderer().renderSingleBlock(ModBlocks.CONTROL_CONSOLE.get().defaultBlockState(),
                poseStack, bufferSource, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    /**
     * 固定的 3×3 原版草方块调试地板。使用当前资源包烘焙模型,角落的蒲公英也保留 cutout 材质层。
     */
    private void submitDebugGarden(PoseStack poseStack) {
        Minecraft minecraft = Minecraft.getInstance();
        for (int z = 0; z < 3; z++) {
            for (int x = 0; x < 3; x++) {
                poseStack.pushPose();
                poseStack.translate(x - 1.5F, -1.0F, z - 1.5F);
                minecraft.getBlockRenderer().renderSingleBlock(Blocks.GRASS_BLOCK.defaultBlockState(), poseStack,
                        bufferSource, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                poseStack.popPose();
            }
        }
        poseStack.pushPose();
        poseStack.translate(-1.5F, 0.0F, -1.5F);
        minecraft.getBlockRenderer().renderSingleBlock(Blocks.DANDELION.defaultBlockState(), poseStack,
                bufferSource, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    /** 按原版 section/layer 原则绘制持久网格;这里只消费不可变快照,不访问 ClientLevel。 */
    private void submitTerrainPreview(PoseStack poseStack,
            HolographicPreviewPipRenderState state) {
        TerrainPreviewFrame frame = state.terrainFrame();
        if (frame == null || frame.generation() == 0L) {
            terrainGpuCache.releaseSession();
            return;
        }
        drawTerrainBounds(poseStack, frame);
        drawUnknownTerrain(poseStack, frame);
        bufferSource.endBatch();
        terrainGpuCache.updateAndRender(frame, poseStack.last().pose(), state);
        submitTerrainBlockEntities(poseStack, state, frame);
    }

    private void submitTerrainBlockEntities(PoseStack poseStack,
            HolographicPreviewPipRenderState state, TerrainPreviewFrame frame) {
        if (frame.blockEntities().isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        var dispatcher = minecraft.getBlockEntityRenderDispatcher();
        for (var preview : frame.blockEntities()) {
            BlockEntity blockEntity = preview.blockEntity();
            if (blockEntity == null || blockEntity.isRemoved() || dispatcher.getRenderer(blockEntity) == null) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(preview.worldPos().getX() - frame.originX() - 0.5F,
                    preview.worldPos().getY() - frame.originY(),
                    preview.worldPos().getZ() - frame.originZ() - 0.5F);
            try {
                dispatcher.getRenderer(blockEntity).render(blockEntity, 0.0F, poseStack, bufferSource,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                TerrainPreviewRenderDiagnostics.recordBlockEntitySubmission();
            } catch (Throwable incompatibleRenderer) {
                if (incompatibleRenderer instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                LOGGER.debug("Skipping incompatible terrain block-entity renderer at {}",
                        preview.worldPos(), incompatibleRenderer);
            } finally {
                poseStack.popPose();
            }
        }
    }

    private void drawTerrainBounds(PoseStack poseStack, TerrainPreviewFrame frame) {
        float minX = frame.bounds().minX() - frame.originX() - 0.5F;
        float minY = frame.bounds().minY() - frame.originY();
        float minZ = frame.bounds().minZ() - frame.originZ() - 0.5F;
        float maxX = frame.bounds().maxX() - frame.originX() + 0.5F;
        float maxY = frame.bounds().maxY() - frame.originY() + 1.0F;
        float maxZ = frame.bounds().maxZ() - frame.originZ() + 0.5F;
        box(poseStack.last(), minX, minY, minZ, maxX, maxY, maxZ, 0x7045E7FF, 1.2F);
    }

    private void drawUnknownTerrain(PoseStack poseStack, TerrainPreviewFrame frame) {
        PoseStack.Pose pose = poseStack.last();
        for (TerrainWireframeMesher.Segment segment : frame.wireframeSegments()) {
                boolean unknown = segment.material() == com.zhongbai233.net_music_can_play_bili.terrain.core
                    .TerrainCellSample.RenderCategory.UNKNOWN;
                double centerDistance = Math.sqrt(Math.pow((segment.x1() + segment.x2()) * 0.5D - frame.coreCenterX(), 2.0D)
                    + Math.pow((segment.y1() + segment.y2()) * 0.5D - frame.coreCenterY(), 2.0D)
                    + Math.pow((segment.z1() + segment.z2()) * 0.5D - frame.coreCenterZ(), 2.0D));
                boolean mapColored = segment.color() != 0;
                int alpha = mapColored
                        ? (int) Math.clamp(0xD8 - centerDistance * 0.5D, 0x90, 0xD8)
                        : (int) Math.clamp(0x64 - centerDistance * 0.35D, 0x30, 0x64);
                int color = mapColored ? alpha << 24 | segment.color() & 0x00FFFFFF
                        : unknown ? 0x384B3F66 : alpha << 24 | 0x006FCB78;
                line(pose,
                    segment.x1() - frame.originX() - 0.5F, segment.y1() - frame.originY(),
                    segment.z1() - frame.originZ() - 0.5F,
                    segment.x2() - frame.originX() - 0.5F, segment.y2() - frame.originY(),
                    segment.z2() - frame.originZ() - 0.5F,
                    color, mapColored ? 1.2F : unknown ? 0.7F : 0.9F);
                if (!unknown && !mapColored) {
                    drawWireBranch(pose, frame, segment);
                }
        }
    }

            private void drawWireBranch(PoseStack.Pose pose, TerrainPreviewFrame frame,
                TerrainWireframeMesher.Segment segment) {
            long seed = ((long) frame.originX() * 341873128712L)
                ^ ((long) frame.originY() * 132897987541L)
                ^ ((long) frame.originZ() * 42317861L);
            int axis = segment.x1() != segment.x2() ? 0 : segment.y1() != segment.y2() ? 1 : 2;
            if (!TerrainFixedCorePolicy.emitsBranch(seed, segment.x1(), segment.y1(), segment.z1(), axis)) {
                return;
            }
            int direction = TerrainFixedCorePolicy.branchDirection(seed,
                segment.x1(), segment.y1(), segment.z1(), axis);
            float length = (float) TerrainFixedCorePolicy.branchLength(seed,
                segment.x1(), segment.y1(), segment.z1(), axis);
            float startX = segment.x1();
            float startY = segment.y1();
            float startZ = segment.z1();
            float dx = direction == 0 ? -length : direction == 1 ? length : 0.0F;
            float dy = direction == 2 ? -length : direction == 3 ? length : 0.0F;
            float dz = direction == 4 ? -length : direction == 5 ? length : 0.0F;
            float endX = Math.clamp(startX + dx, frame.bounds().minX(), frame.bounds().maxX() + 1.0F);
            float endY = Math.clamp(startY + dy, frame.bounds().minY(), frame.bounds().maxY() + 1.0F);
            float endZ = Math.clamp(startZ + dz, frame.bounds().minZ(), frame.bounds().maxZ() + 1.0F);
            float localStartX = startX - frame.originX() - 0.5F;
            float localStartY = startY - frame.originY();
            float localStartZ = startZ - frame.originZ() - 0.5F;
            float localEndX = endX - frame.originX() - 0.5F;
            float localEndY = endY - frame.originY();
            float localEndZ = endZ - frame.originZ() - 0.5F;
            line(pose, localStartX, localStartY, localStartZ,
                localEndX, localEndY, localEndZ, 0x406FCB78, 0.75F);
            }

    private void drawGizmo(PoseStack poseStack, HolographicPreviewPipRenderState state) {
        int index = selectedIndex(state);
        poseStack.pushPose();
        poseStack.translate(screenOffsetX(state, index) + screenPivotX(state, index),
                1.55F + screenOffsetY(state, index) + screenPivotY(state, index),
                screenDistance(state, index) + screenPivotZ(state, index));
        if (state.localSpace()) {
            poseStack.mulPose(Axis.YP.rotationDegrees(screenYaw(state, index)));
            poseStack.mulPose(Axis.XP.rotationDegrees(screenPitch(state, index)));
            poseStack.mulPose(Axis.ZP.rotationDegrees(screenRoll(state, index)));
        }

        PoseStack.Pose pose = poseStack.last();
        int handle = state.gizmoHandle();
        int encodedTool = state.gizmoTool();
        int tool = encodedTool & 0xFF;

        boolean centerSelected = handle == 1;
        boolean xSelected = handle == 2;
        boolean ySelected = handle == 3;
        boolean zSelected = handle == 4;
        boolean ringXSelected = handle == 5;
        boolean ringYSelected = handle == 6;
        boolean ringZSelected = handle == 7;

        line(pose, 0.0F, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F,
                xSelected ? 0xFFFF7777 : 0xE0FF4D4D, xSelected ? 2.4F : 1.5F);
        drawArrowHead(pose, 'x', xSelected ? 0xFFFF7777 : 0xE0FF4D4D, xSelected ? 2.4F : 1.5F);
        line(pose, 0.0F, 0.0F, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F,
                ySelected ? 0xFF77FF99 : 0xE04DFF72, ySelected ? 2.4F : 1.5F);
        drawArrowHead(pose, 'y', ySelected ? 0xFF77FF99 : 0xE04DFF72, ySelected ? 2.4F : 1.5F);
        line(pose, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN,
                zSelected ? 0xFF77B7FF : 0xE04DA3FF, zSelected ? 2.4F : 1.5F);
        drawArrowHead(pose, 'z', zSelected ? 0xFF77B7FF : 0xE04DA3FF, zSelected ? 2.4F : 1.5F);
        box(pose, -0.035F, -0.035F, -0.035F, 0.035F, 0.035F, 0.035F,
                centerSelected ? 0xFFFFFFFF : 0xFFE8E8E8, 1.4F);
        drawAxisLabel(pose, 'X', GIZMO_AXIS_WORLD_LEN + 0.16F, 0.0F, 0.0F, 0xFFFF7777);
        drawAxisLabel(pose, 'Y', 0.0F, GIZMO_AXIS_WORLD_LEN + 0.16F, 0.0F, 0xFF77FF99);
        drawAxisLabel(pose, tool == 2 ? 'S' : 'Z', 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN + 0.16F, 0xFF77B7FF);

        if (tool == 1) {
            drawGizmoRing(pose, 'x', ringXSelected ? 0xFFFF9999 : 0xC0FF4D4D,
                ringXSelected ? 2.2F : 1.2F);
            drawGizmoRing(pose, 'y', ringYSelected ? 0xFF99FFCC : 0xC04DFF72,
                ringYSelected ? 2.2F : 1.2F);
            drawGizmoRing(pose, 'z', ringZSelected ? 0xFF99C7FF : 0xC04DA3FF,
                ringZSelected ? 2.2F : 1.2F);
        }
        if (tool == 2) {
            box(pose, GIZMO_AXIS_WORLD_LEN - 0.04F, -0.04F, -0.04F, GIZMO_AXIS_WORLD_LEN + 0.04F, 0.04F,
                    0.04F, 0xFFFF7777, 1.4F);
            box(pose, -0.04F, GIZMO_AXIS_WORLD_LEN - 0.04F, -0.04F, 0.04F, GIZMO_AXIS_WORLD_LEN + 0.04F,
                    0.04F, 0xFF77FF99, 1.4F);
            box(pose, -0.04F, -0.04F, GIZMO_AXIS_WORLD_LEN - 0.04F, 0.04F, 0.04F,
                    GIZMO_AXIS_WORLD_LEN + 0.04F, 0xFF77B7FF, 1.4F);
        }
        poseStack.popPose();
    }

    private void drawArrowHead(PoseStack.Pose pose, char axis, int color,
            float lineWidth) {
        if (axis == 'x') {
            line(pose, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN,
                    GIZMO_ARROW_WING, 0.0F, color, lineWidth);
            line(pose, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN,
                    -GIZMO_ARROW_WING, 0.0F, color, lineWidth);
            line(pose, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN,
                    0.0F, GIZMO_ARROW_WING, color, lineWidth);
            line(pose, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN,
                    0.0F, -GIZMO_ARROW_WING, color, lineWidth);
        } else if (axis == 'y') {
            line(pose, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, GIZMO_ARROW_WING,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, 0.0F, color, lineWidth);
            line(pose, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, -GIZMO_ARROW_WING,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, 0.0F, color, lineWidth);
            line(pose, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, GIZMO_ARROW_WING, color, lineWidth);
            line(pose, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, 0.0F,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, -GIZMO_ARROW_WING, color, lineWidth);
        } else {
            line(pose, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, GIZMO_ARROW_WING, 0.0F,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, color, lineWidth);
            line(pose, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, -GIZMO_ARROW_WING, 0.0F,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, color, lineWidth);
            line(pose, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, GIZMO_ARROW_WING,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, color, lineWidth);
            line(pose, 0.0F, 0.0F, GIZMO_AXIS_WORLD_LEN, 0.0F, -GIZMO_ARROW_WING,
                    GIZMO_AXIS_WORLD_LEN - GIZMO_ARROW_LEN, color, lineWidth);
        }
    }

    private void drawAxisLabel(PoseStack.Pose pose, char label, float x, float y, float z,
            int color) {
        if (label == 'X') {
            line(pose, x - GIZMO_LABEL_SIZE, y - GIZMO_LABEL_SIZE, z, x + GIZMO_LABEL_SIZE,
                    y + GIZMO_LABEL_SIZE, z, color, 1.7F);
            line(pose, x - GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x + GIZMO_LABEL_SIZE,
                    y - GIZMO_LABEL_SIZE, z, color, 1.7F);
        } else if (label == 'Y') {
            line(pose, x - GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x, y, z, color, 1.7F);
            line(pose, x + GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x, y, z, color, 1.7F);
            line(pose, x, y, z, x, y - GIZMO_LABEL_SIZE, z, color, 1.7F);
        } else if (label == 'S') {
            line(pose, x + GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x - GIZMO_LABEL_SIZE,
                    y + GIZMO_LABEL_SIZE, z, color, 1.7F);
            line(pose, x - GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x - GIZMO_LABEL_SIZE,
                    y, z, color, 1.7F);
            line(pose, x - GIZMO_LABEL_SIZE, y, z, x + GIZMO_LABEL_SIZE, y, z, color, 1.7F);
            line(pose, x + GIZMO_LABEL_SIZE, y, z, x + GIZMO_LABEL_SIZE,
                    y - GIZMO_LABEL_SIZE, z, color, 1.7F);
            line(pose, x + GIZMO_LABEL_SIZE, y - GIZMO_LABEL_SIZE, z, x - GIZMO_LABEL_SIZE,
                    y - GIZMO_LABEL_SIZE, z, color, 1.7F);
        } else {
            line(pose, x - GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x + GIZMO_LABEL_SIZE,
                    y + GIZMO_LABEL_SIZE, z, color, 1.7F);
            line(pose, x + GIZMO_LABEL_SIZE, y + GIZMO_LABEL_SIZE, z, x - GIZMO_LABEL_SIZE,
                    y - GIZMO_LABEL_SIZE, z, color, 1.7F);
            line(pose, x - GIZMO_LABEL_SIZE, y - GIZMO_LABEL_SIZE, z, x + GIZMO_LABEL_SIZE,
                    y - GIZMO_LABEL_SIZE, z, color, 1.7F);
        }
    }

    private void drawGizmoRing(PoseStack.Pose pose, char axis, int color,
            float lineWidth) {
        float radius = GIZMO_AXIS_WORLD_LEN * 0.66F;
        Vector3f previous = ringPoint(axis, radius, 0.0F);
        for (int i = 1; i <= GIZMO_RING_SEGMENTS; i++) {
            float angle = (float) (Math.PI * 2.0D * i / GIZMO_RING_SEGMENTS);
            Vector3f current = ringPoint(axis, (float) Math.cos(angle) * radius,
                    (float) Math.sin(angle) * radius);
            line(pose, previous.x, previous.y, previous.z, current.x, current.y, current.z,
                    color, lineWidth);
            previous = current;
        }
    }

    private static Vector3f ringPoint(char axis, float a, float b) {
        return switch (axis) {
            case 'x' -> new Vector3f(0.0F, a, b);
            case 'y' -> new Vector3f(a, 0.0F, b);
            default -> new Vector3f(a, b, 0.0F);
        };
    }

    private void drawPlayerGlowOutline(PoseStack poseStack, HolographicPreviewPipRenderState state) {
        LivingEntity player = state.playerEntity();
        if (player == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(state.playerTranslation().x, state.playerTranslation().y, state.playerTranslation().z);
        poseStack.scale(state.playerScale(), state.playerScale(), state.playerScale());

        float width = Math.max(0.58F, player.getBbWidth()) * 0.5F + 0.055F;
        float height = Math.max(1.8F, player.getBbHeight()) + 0.08F;
        float minY = -0.03F;
        float maxY = height;
        PoseStack.Pose pose = poseStack.last();
        int outer = 0xF045E7FF;
        int inner = 0x9045E7FF;

        box(pose, -width, minY, -width, width, maxY, width, outer, 2.5F);
        box(pose, -width * 0.92F, minY + 0.03F, -width * 0.92F, width * 0.92F, maxY - 0.03F,
                width * 0.92F, inner, 1.2F);
        poseStack.popPose();
    }

    private void drawHolographicScreens(PoseStack poseStack, HolographicPreviewPipRenderState state) {
        int count = screenCount(state);
        for (int i = 0; i < count; i++) {
            drawHolographicScreen(poseStack, state, i, i == selectedIndex(state));
        }
    }

    private void drawHolographicScreen(PoseStack poseStack, HolographicPreviewPipRenderState state, int index,
            boolean selected) {
        poseStack.pushPose();
        poseStack.translate(0.0F, 1.55F, 0.0F);
        poseStack.mulPose(screenTransform(state, index, 1.0F));

        float halfH = screenHeight(state, index) * 0.5F;
        float halfW = halfH * screenAspect(state, index);
        PoseStack.Pose pose = poseStack.last();
        int type = elementType(state, index);
        if (type == 1) {
            drawSubtitleElement(pose, halfW, halfH, selected);
        } else if (type == 2) {
            drawAudioElement(pose, halfW, halfH, selected);
        } else {
            drawScreenElement(pose, halfW, halfH, selected);
        }
        poseStack.popPose();
    }

        /** 屏幕是带厚度的框体:局部 +Z 为实际渲染正面,青色内框/箭头标记正面。 */
        private void drawScreenElement(PoseStack.Pose pose,
            float halfW, float halfH, boolean selected) {
        float depth = Math.max(0.018F, Math.min(halfW, halfH) * 0.055F);
        int frontColor = selected ? 0xF045E7FF : 0xA845E7FF;
        int backColor = selected ? 0xD0FF9F43 : 0x88FF9F43;
        float frontWidth = selected ? 2.2F : 1.4F;
        screenWire(pose, -halfW, -halfH, halfW, halfH, depth, frontColor, frontWidth);
        screenWire(pose, -halfW, -halfH, halfW, halfH, -depth, backColor, selected ? 1.5F : 1.0F);
        line(pose, -halfW, -halfH, -depth, -halfW, -halfH, depth, frontColor, 1.0F);
        line(pose, halfW, -halfH, -depth, halfW, -halfH, depth, frontColor, 1.0F);
        line(pose, -halfW, halfH, -depth, -halfW, halfH, depth, frontColor, 1.0F);
        line(pose, halfW, halfH, -depth, halfW, halfH, depth, frontColor, 1.0F);

        float insetX = halfW * 0.12F;
        float insetY = halfH * 0.14F;
        screenWire(pose, -halfW + insetX, -halfH + insetY,
            halfW - insetX, halfH - insetY, depth + SCREEN_FACE_EPSILON, frontColor, 1.0F);
        float arrow = Math.max(0.10F, Math.min(halfW, halfH) * 0.30F);
        float frontZ = depth + arrow * 0.58F;
        line(pose, 0.0F, 0.0F, depth, 0.0F, 0.0F, frontZ, frontColor, 2.0F);
        line(pose, 0.0F, 0.0F, frontZ, -arrow * 0.32F, 0.0F,
            frontZ - arrow * 0.34F, frontColor, 1.5F);
        line(pose, 0.0F, 0.0F, frontZ, arrow * 0.32F, 0.0F,
            frontZ - arrow * 0.34F, frontColor, 1.5F);

        line(pose, -halfW * 0.72F, -halfH * 0.72F, -depth - SCREEN_FACE_EPSILON,
            halfW * 0.72F, halfH * 0.72F, -depth - SCREEN_FACE_EPSILON, backColor, 1.2F);
        line(pose, -halfW * 0.72F, halfH * 0.72F, -depth - SCREEN_FACE_EPSILON,
            halfW * 0.72F, -halfH * 0.72F, -depth - SCREEN_FACE_EPSILON, backColor, 1.2F);
        }

        /** 字幕元素是窄文本牌,三条不同长度的基线比平面矩形更接近字幕语义。 */
        private void drawSubtitleElement(PoseStack.Pose pose,
            float halfW, float halfH, boolean selected) {
        float textHalfH = Math.max(0.10F, halfH * 0.42F);
        float textHalfW = Math.max(textHalfH * 2.8F, halfW * 0.72F);
        int color = selected ? 0xFFFFE08A : 0xB8FFD166;
        screenWire(pose, -textHalfW, -textHalfH, textHalfW, textHalfH,
            SCREEN_FACE_EPSILON, color, selected ? 2.1F : 1.35F);
        float z = SCREEN_FACE_EPSILON * 2.0F;
        line(pose, -textHalfW * 0.78F, textHalfH * 0.46F, z,
            textHalfW * 0.78F, textHalfH * 0.46F, z, color, 1.5F);
        line(pose, -textHalfW * 0.66F, 0.0F, z,
            textHalfW * 0.66F, 0.0F, z, color, 1.5F);
        line(pose, -textHalfW * 0.48F, -textHalfH * 0.46F, z,
            textHalfW * 0.48F, -textHalfH * 0.46F, z, color, 1.5F);
        line(pose, -textHalfW, -textHalfH * 1.25F, 0.0F,
            textHalfW, -textHalfH * 1.25F, 0.0F, 0x70FFD166, 1.0F);
        }

        /** 音响元素是有厚度的竖向箱体,正面双单元并从正面方向发出声波。 */
        private void drawAudioElement(PoseStack.Pose pose,
            float halfW, float halfH, boolean selected) {
        float bodyHalfH = Math.max(0.24F, halfH * 0.72F);
        float bodyHalfW = Math.max(0.14F, Math.min(halfW * 0.42F, bodyHalfH * 0.52F));
        float depth = bodyHalfW * 0.55F;
        int color = selected ? 0xFFD7B3FF : 0xB8B47CFF;
        box(pose, -bodyHalfW, -bodyHalfH, -depth,
            bodyHalfW, bodyHalfH, depth, color, selected ? 2.0F : 1.25F);
        drawSpeakerCone(pose, 0.0F, bodyHalfH * 0.38F, depth + SCREEN_FACE_EPSILON,
            bodyHalfW * 0.30F, color);
        drawSpeakerCone(pose, 0.0F, -bodyHalfH * 0.34F, depth + SCREEN_FACE_EPSILON,
            bodyHalfW * 0.58F, color);
        drawSoundWave(pose, bodyHalfW + 0.06F, depth + 0.04F,
            bodyHalfH * 0.48F, color);
        }

        private void drawSpeakerCone(PoseStack.Pose pose,
            float centerX, float centerY, float z, float radius, int color) {
        float previousX = centerX + radius;
        float previousY = centerY;
        for (int i = 1; i <= 12; i++) {
            float angle = (float) (Math.PI * 2.0D * i / 12.0D);
            float x = centerX + radius * (float) Math.cos(angle);
            float y = centerY + radius * (float) Math.sin(angle);
            line(pose, previousX, previousY, z, x, y, z, color, 1.2F);
            previousX = x;
            previousY = y;
        }
        }

        private void drawSoundWave(PoseStack.Pose pose,
            float startX, float z, float halfHeight, int color) {
        for (int wave = 0; wave < 2; wave++) {
            float radius = startX + wave * 0.10F;
            float previousX = radius;
            float previousY = -halfHeight * (0.58F + wave * 0.30F);
            for (int i = 1; i <= 8; i++) {
            float angle = (float) (-Math.PI * 0.5D + Math.PI * i / 8.0D);
            float x = radius + (0.06F + wave * 0.04F) * (float) Math.cos(angle);
            float y = halfHeight * (0.58F + wave * 0.30F) * (float) Math.sin(angle);
            line(pose, previousX, previousY, z, x, y, z, color, 1.2F);
            previousX = x;
            previousY = y;
            }
        }
        }

    private void drawHolographicScreensFrontOnly(PoseStack poseStack, HolographicPreviewPipRenderState state) {
        int count = screenCount(state);
        for (int i = 0; i < count; i++) {
            drawHolographicScreenFrontOnly(poseStack, state, i, i == selectedIndex(state));
        }
    }

    private void drawHolographicScreenFrontOnly(PoseStack poseStack, HolographicPreviewPipRenderState state, int index,
            boolean selected) {
        poseStack.pushPose();
        poseStack.translate(0.0F, 1.55F, 0.0F);
        poseStack.mulPose(screenTransform(state, index, -1.0F));

        float halfH = screenHeight(state, index) * 0.5F;
        float halfW = halfH * screenAspect(state, index);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer front = bufferSource.getBuffer(RenderType.debugQuads());
        emitQuad(front, pose, -halfW, -halfH, halfW, halfH, SCREEN_FACE_EPSILON, selected ? 0xD82BE7FF : 0x802BE7FF,
                false);
        poseStack.popPose();
    }

    private static int selectedIndex(HolographicPreviewPipRenderState state) {
        return state.selectedScreen() >= 0 && state.selectedScreen() < screenCount(state)
            ? state.selectedScreen() : -1;
    }

    private static int screenCount(HolographicPreviewPipRenderState state) {
        return Math.max(1, state.screenDistances() != null ? state.screenDistances().length : 0);
    }

    private static float screenDistance(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenDistances(), index, state.screenDistance());
    }

    private static float screenOffsetX(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenOffsetXs(), index, state.screenOffsetX());
    }

    private static float screenOffsetY(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenOffsetYs(), index, state.screenOffsetY());
    }

    private static float screenHeight(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenHeights(), index, state.screenHeight());
    }

    private static float screenAspect(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenAspects(), index, state.screenAspect());
    }

    private static float screenRoll(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenRolls(), index, state.screenRoll());
    }

    private static float screenYaw(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenYaws(), index, 0.0F);
    }

    private static float screenPitch(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenPitches(), index, 0.0F);
    }

    private static float screenScaleX(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenScaleXs(), index, 1.0F);
    }

    private static float screenScaleY(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenScaleYs(), index, 1.0F);
    }

    private static float screenScaleZ(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenScaleZs(), index, 1.0F);
    }

    private static float screenPivotX(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenPivotXs(), index, 0.0F);
    }

    private static float screenPivotY(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenPivotYs(), index, 0.0F);
    }

    private static float screenPivotZ(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenPivotZs(), index, 0.0F);
    }

    private static float screenSkewXByY(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenSkewXByYs(), index, 0.0F);
    }

    private static float screenSkewYByX(HolographicPreviewPipRenderState state, int index) {
        return valueAt(state.screenSkewYByXs(), index, 0.0F);
    }

    private static Matrix4f screenTransform(HolographicPreviewPipRenderState state, int index,
            float offsetYSign) {
        return EditorTransform.fromEulerDegrees(
                new Vector3f(screenOffsetX(state, index), offsetYSign * screenOffsetY(state, index),
                        screenDistance(state, index)),
                screenYaw(state, index), screenPitch(state, index), screenRoll(state, index),
                new Vector3f(screenScaleX(state, index), screenScaleY(state, index),
                        screenScaleZ(state, index)),
                new Vector3f(screenPivotX(state, index), screenPivotY(state, index),
                        screenPivotZ(state, index)),
                screenSkewXByY(state, index), screenSkewYByX(state, index)).matrix();
    }

    private static int elementType(HolographicPreviewPipRenderState state, int index) {
        int[] types = state.elementTypes();
        return types != null && index >= 0 && index < types.length ? types[index] : 0;
    }

    private static float valueAt(float[] values, int index, float fallback) {
        return values != null && index >= 0 && index < values.length ? values[index] : fallback;
    }

    private void drawGrid(PoseStack poseStack) {
        PoseStack.Pose pose = poseStack.last();
        for (int i = -4; i <= 4; i++) {
            line(pose, i * 0.5F, 0.0F, -2.5F, i * 0.5F, 0.0F, 3.5F, 0x4E5E4A1A);
        }
        for (int i = -5; i <= 7; i++) {
            float z = i * 0.5F;
            int alpha = z <= 0.0F ? 0x665E4A1A : 0x4A5E4A1A;
            line(pose, -2.0F, 0.0F, z, 2.0F, 0.0F, z, alpha);
        }
        line(pose, -2.0F, 0.01F, 0.0F, 2.0F, 0.01F, 0.0F, 0xAA45E7FF);
        line(pose, 0.0F, 0.01F, -2.5F, 0.0F, 0.01F, 3.5F, 0xAAFFB347);
    }

    private void box(PoseStack.Pose pose, float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ, int color, float lineWidth) {
        line(pose, minX, minY, minZ, maxX, minY, minZ, color, lineWidth);
        line(pose, maxX, minY, minZ, maxX, minY, maxZ, color, lineWidth);
        line(pose, maxX, minY, maxZ, minX, minY, maxZ, color, lineWidth);
        line(pose, minX, minY, maxZ, minX, minY, minZ, color, lineWidth);

        line(pose, minX, maxY, minZ, maxX, maxY, minZ, color, lineWidth);
        line(pose, maxX, maxY, minZ, maxX, maxY, maxZ, color, lineWidth);
        line(pose, maxX, maxY, maxZ, minX, maxY, maxZ, color, lineWidth);
        line(pose, minX, maxY, maxZ, minX, maxY, minZ, color, lineWidth);

        line(pose, minX, minY, minZ, minX, maxY, minZ, color, lineWidth);
        line(pose, maxX, minY, minZ, maxX, maxY, minZ, color, lineWidth);
        line(pose, maxX, minY, maxZ, maxX, maxY, maxZ, color, lineWidth);
        line(pose, minX, minY, maxZ, minX, maxY, maxZ, color, lineWidth);
    }

    private void screenWire(PoseStack.Pose pose, float minX, float minY, float maxX,
            float maxY, float z, int color, float lineWidth) {
        line(pose, minX, minY, z, maxX, minY, z, color, lineWidth);
        line(pose, maxX, minY, z, maxX, maxY, z, color, lineWidth);
        line(pose, maxX, maxY, z, minX, maxY, z, color, lineWidth);
        line(pose, minX, maxY, z, minX, minY, z, color, lineWidth);
    }

    private static void emitQuad(VertexConsumer buffer, PoseStack.Pose pose, float minX, float minY, float maxX,
            float maxY, float z, int color, boolean reverse) {
        if (reverse) {
            vertex(buffer, pose, minX, maxY, z, color);
            vertex(buffer, pose, maxX, maxY, z, color);
            vertex(buffer, pose, maxX, minY, z, color);
            vertex(buffer, pose, minX, minY, z, color);
        } else {
            vertex(buffer, pose, minX, minY, z, color);
            vertex(buffer, pose, maxX, minY, z, color);
            vertex(buffer, pose, maxX, maxY, z, color);
            vertex(buffer, pose, minX, maxY, z, color);
        }
    }

    private void line(PoseStack.Pose pose, float x1, float y1, float z1, float x2,
            float y2, float z2, int color) {
        line(pose, x1, y1, z1, x2, y2, z2, color, 1.0F);
    }

    private void line(PoseStack.Pose pose, float x1, float y1, float z1, float x2,
            float y2, float z2, int color, float lineWidth) {
        float visibleWidth = Math.clamp(lineWidth * ACTIVE_LINE_WIDTH_SCALE.get(), 1.0F, 8.0F);
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float lengthSquared = dx * dx + dy * dy + dz * dz;
        float normalX;
        float normalY;
        float normalZ;
        if (lengthSquared > 1.0e-12F) {
            float inverseLength = 1.0F / (float) Math.sqrt(lengthSquared);
            normalX = dx * inverseLength;
            normalY = dy * inverseLength;
            normalZ = dz * inverseLength;
        } else {
            normalX = 0.0F;
            normalY = 1.0F;
            normalZ = 0.0F;
        }
        // 1.21.1 rendertype_lines 的宽度来自全局 LineWidth uniform(ShaderInstance.apply 时读取),
        // 因此宽度变化必须先 flush 旧批次再切换;消费者也必须重新获取以避免引用过期 builder。
        applyLineWidth(visibleWidth);
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());
        buffer.addVertex(pose, x1, y1, z1).setColor(color)
                .setNormal(pose, normalX, normalY, normalZ);
        buffer.addVertex(pose, x2, y2, z2).setColor(color)
                .setNormal(pose, normalX, normalY, normalZ);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, int color) {
        buffer.addVertex(pose, x, y, z).setColor(color).setNormal(0.0F, 0.0F, -1.0F);
    }

    @Override
    protected String getTextureLabel() {
        return "ncpb_holographic_preview";
    }

    @Override
    protected float getTranslateY(int textureHeight, int pixelScale) {
        return textureHeight * SCENE_ORIGIN_Y_RATIO;
    }
}