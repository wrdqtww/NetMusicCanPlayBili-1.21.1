package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.shaders.Uniform;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainBlockSectionSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainCompilationAdmission;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewFrame;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewSectionCompiler;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainResidentSectionPolicy;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainTranslucentSortPolicy;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockModelShaper;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 渲染线程持久的 section GPU 网格缓存。
 *
 * <p>
 * 1.21.1 没有 26.x 的 UberGpuBuffer/RenderPass/DynamicUniforms；每个 section×layer 用独立的
 * {@link VertexBuffer}(STATIC) 承载，绘制走原版 renderSectionLayer 同款
 * {@code RenderType.setupRenderState + ShaderInstance(ChunkOffset UBO) + vertexBuffer.draw()}。
 * 半透明层沿用 CPU {@link MeshData.SortState} 重排后仅重传索引缓冲。
 * </p>
 */
final class TerrainPreviewGpuCache implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerrainPreviewGpuCache.class);
    private static final RenderType[] RENDER_LAYERS = {
        RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent()};
    private static final long COVERAGE_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private final Map<TerrainSectionKey, GpuSection> sections = new HashMap<>();
    private final Map<TerrainSectionKey, TerrainBlockSectionSnapshot> failedSources = new HashMap<>();
    private final ConcurrentLinkedQueue<CompilationOutcome> completedCompilations =
            new ConcurrentLinkedQueue<>();
    private final ExecutorService compilationExecutor = Executors.newSingleThreadExecutor(
            terrainCompilerThreadFactory());
    private CompilationRequest activeCompilation;
    private long compilationEpoch;
    private long generation;
    private BlockModelShaper modelShaper;
    private TerrainBounds synchronizedBounds;
    private long lastCoverageLogNanos;
    private long lastCoverageGeneration = Long.MIN_VALUE;
    private TerrainPreviewFrame exhaustedCompilationFrame;
    private Matrix4f exhaustedCompilationViewProjection;
    private long residentRevision;
    private RenderPlan cachedRenderPlan;
    private long renderPlanHits;
    private long renderPlanBuilds;
    private long sessionReleases;
    private volatile boolean closed;
    private boolean disabledForSession;
    private boolean failureLogged;

    void updateAndRender(TerrainPreviewFrame frame, Matrix4fc modelView,
            HolographicPreviewPipRenderState state) {
        if (disabledForSession) {
            return;
        }
        try {
            updateAndRenderInternal(frame, modelView, state);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            disableForSession("terrain preview GPU/render failure", failure);
        }
    }

    private void updateAndRenderInternal(TerrainPreviewFrame frame, Matrix4fc modelView,
            HolographicPreviewPipRenderState state) {
        BlockModelShaper currentModels = Minecraft.getInstance().getModelManager().getBlockModelShaper();
        if (frame.generation() != generation || currentModels != modelShaper) {
            clear();
            generation = frame.generation();
            modelShaper = currentModels;
        }
        removeTombstonedSections(frame);
        removeNonResidentSections(frame);
        synchronizeCoverage(frame);
        Matrix4f viewProjection = state.cameraFrame() != null
                ? new Matrix4f(state.cameraFrame().matrices().viewProjection()) : new Matrix4f();
        updateCompilation(frame, modelView, viewProjection);
        renderLayers(frame, modelView, viewProjection);
    }

    private void synchronizeCoverage(TerrainPreviewFrame frame) {
        if (frame.bounds().equals(synchronizedBounds)) {
            return;
        }
        synchronizedBounds = frame.bounds();
        boolean removed = sections.entrySet().removeIf(entry -> {
            if (frame.bounds().intersects(entry.getKey())) {
                return false;
            }
            entry.getValue().close();
            failedSources.remove(entry.getKey());
            return true;
        });
        if (removed) {
            residentRevision++;
            cachedRenderPlan = null;
            exhaustedCompilationFrame = null;
            exhaustedCompilationViewProjection = null;
        }
    }

    private void removeTombstonedSections(TerrainPreviewFrame frame) {
        boolean removed = false;
        for (TerrainSectionKey key : frame.removedSections()) {
            GpuSection section = sections.remove(key);
            if (section != null) {
                section.close();
                removed = true;
            }
            failedSources.remove(key);
        }
        if (removed) {
            residentRevision++;
            cachedRenderPlan = null;
            exhaustedCompilationFrame = null;
            exhaustedCompilationViewProjection = null;
        }
    }

    private void removeNonResidentSections(TerrainPreviewFrame frame) {
        boolean removed = false;
        for (TerrainSectionKey key : TerrainResidentSectionPolicy.staleSections(sections.keySet(), frame)) {
            GpuSection section = sections.remove(key);
            if (section != null) {
                section.close();
                removed = true;
            }
            failedSources.remove(key);
        }
        if (removed) {
            residentRevision++;
            cachedRenderPlan = null;
            exhaustedCompilationFrame = null;
            exhaustedCompilationViewProjection = null;
        }
    }

    private void updateCompilation(TerrainPreviewFrame frame, Matrix4fc modelView,
            Matrix4fc viewProjection) {
        consumeCompletedCompilation(frame);
        if (activeCompilation != null || closed) {
            return;
        }
        if (frame == exhaustedCompilationFrame
                && exhaustedCompilationViewProjection != null
                && exhaustedCompilationViewProjection.equals(viewProjection)) {
            return;
        }
        // 已驻留 section 的 dirty 快照优先，避免方块更新长期显示旧网格。
        for (TerrainBlockSectionSnapshot snapshot : frame.fullDetailSections()) {
            GpuSection current = sections.get(snapshot.section());
            if (current == null || current.source.get() == snapshot) {
                continue;
            }
            if (failedSources.get(snapshot.section()) == snapshot) {
                continue;
            }
            exhaustedCompilationFrame = null;
            exhaustedCompilationViewProjection = null;
            submitCompilation(frame, snapshot);
            return;
        }

        TerrainBlockSectionSnapshot nearestMissing = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        TerrainBlockSectionSnapshot nearestMissingOutsideFrustum = null;
        double nearestOutsideDistance = Double.POSITIVE_INFINITY;
        for (TerrainBlockSectionSnapshot snapshot : frame.fullDetailSections()) {
            if (sections.containsKey(snapshot.section())
                    || failedSources.get(snapshot.section()) == snapshot) {
                continue;
            }
            double distance = viewDistanceSquared(modelView, frame, snapshot.section());
            if (intersectsFrustum(frame, viewProjection, snapshot.section())) {
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestMissing = snapshot;
                }
            } else if (distance < nearestOutsideDistance) {
                nearestOutsideDistance = distance;
                nearestMissingOutsideFrustum = snapshot;
            }
        }
        if (nearestMissing == null) {
            nearestMissing = nearestMissingOutsideFrustum;
        }
        if (nearestMissing != null) {
            exhaustedCompilationFrame = null;
            exhaustedCompilationViewProjection = null;
            submitCompilation(frame, nearestMissing);
        } else {
            exhaustedCompilationFrame = frame;
            exhaustedCompilationViewProjection = new Matrix4f(viewProjection);
        }
    }

    private static boolean intersectsFrustum(TerrainPreviewFrame frame,
            Matrix4fc viewProjection, TerrainSectionKey key) {
        float x = key.minBlockX() - frame.originX();
        float y = key.minBlockY() - frame.originY();
        float z = key.minBlockZ() - frame.originZ();
        return intersectsClip(viewProjection,
                x, y, z, x + TerrainSectionKey.SIZE, y + TerrainSectionKey.SIZE,
                z + TerrainSectionKey.SIZE);
    }

    private static boolean intersectsClip(Matrix4fc matrix,
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int outsidePlanes = 0x3F;
        for (int corner = 0; corner < 8 && outsidePlanes != 0; corner++) {
            float x = (corner & 4) == 0 ? minX : maxX;
            float y = (corner & 2) == 0 ? minY : maxY;
            float z = (corner & 1) == 0 ? minZ : maxZ;
            float clipX = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
            float clipY = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
            float clipZ = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
            float clipW = matrix.m03() * x + matrix.m13() * y + matrix.m23() * z + matrix.m33();
            int cornerOutside = 0;
            if (clipX < -clipW) cornerOutside |= 1;
            if (clipX > clipW) cornerOutside |= 2;
            if (clipY < -clipW) cornerOutside |= 4;
            if (clipY > clipW) cornerOutside |= 8;
            if (clipZ < -clipW) cornerOutside |= 16;
            if (clipZ > clipW) cornerOutside |= 32;
            outsidePlanes &= cornerOutside;
        }
        return outsidePlanes == 0;
    }

    private void submitCompilation(TerrainPreviewFrame frame,
            TerrainBlockSectionSnapshot snapshot) {
        CompilationRequest request = new CompilationRequest(compilationEpoch, frame, snapshot,
                modelShaper, Minecraft.getInstance().getBlockColors());
        activeCompilation = request;
        compilationExecutor.execute(() -> {
            TerrainPreviewSectionCompiler.CompiledCpuSection compiled = null;
            Throwable failure = null;
            try {
                compiled = TerrainPreviewSectionCompiler.compile(request.frame, request.source,
                        request.modelShaper, request.blockColors);
            } catch (Throwable caught) {
                failure = caught;
            }
            if (closed) {
                if (compiled != null) {
                    compiled.close();
                }
                return;
            }
            CompilationOutcome outcome = new CompilationOutcome(request, compiled, failure);
            completedCompilations.add(outcome);
            if (closed && completedCompilations.remove(outcome)) {
                outcome.closeCompiled();
            }
        });
    }

    private void consumeCompletedCompilation(TerrainPreviewFrame frame) {
        CompilationOutcome outcome = completedCompilations.poll();
        if (outcome == null) {
            return;
        }
        if (activeCompilation == outcome.request) {
            activeCompilation = null;
        }
        boolean current = TerrainCompilationAdmission.isCurrent(frame, outcome.request.source,
                outcome.request.epoch, compilationEpoch);
        if (!current) {
            outcome.closeCompiled();
            return;
        }
        if (outcome.failure != null) {
            if (outcome.failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            TerrainPreviewRenderDiagnostics.recordFailure();
            failedSources.put(outcome.request.source.section(), outcome.request.source);
            LOGGER.warn("无法编译地形预览 section {}，保留旧网格",
                    outcome.request.source.section(), outcome.failure);
            TerrainPreviewManager.markCompiled(outcome.request.frame.generation(),
                    outcome.request.source);
            return;
        }
        try (TerrainPreviewSectionCompiler.CompiledCpuSection compiled = outcome.compiled) {
            try {
                GpuSection replacement = GpuSection.upload(compiled);
                boolean materialLod = compiled.source().blocks().stream()
                        .anyMatch(block -> block.cellSize() > 1);
                boolean translucent = compiled.layers().containsKey(RenderType.translucent());
                TerrainPreviewRenderDiagnostics.recordSectionUpload(materialLod, translucent);
                GpuSection old = sections.put(outcome.request.source.section(), replacement);
                if (old != null) {
                    old.close();
                }
                residentRevision++;
                exhaustedCompilationFrame = null;
                exhaustedCompilationViewProjection = null;
                cachedRenderPlan = null;
                failedSources.remove(outcome.request.source.section());
                TerrainPreviewManager.markCompiled(outcome.request.frame.generation(),
                        outcome.request.source);
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                if (failure instanceof Error fatal) {
                    throw fatal;
                }
                TerrainPreviewRenderDiagnostics.recordFailure();
                failedSources.put(outcome.request.source.section(), outcome.request.source);
                LOGGER.warn("无法上传地形预览 section {}，跳过该 section",
                        outcome.request.source.section(), failure);
                TerrainPreviewManager.markCompiled(outcome.request.frame.generation(),
                        outcome.request.source);
            }
        }
    }

    private void renderLayers(TerrainPreviewFrame frame, Matrix4fc modelView,
            Matrix4fc viewProjection) {
        Minecraft minecraft = Minecraft.getInstance();
        if (resortTranslucentQuads(frame, modelView, viewProjection)) {
            residentRevision++;
            cachedRenderPlan = null;
        }
        RenderPlan plan = renderPlan(frame, modelView, viewProjection);
        logCoverage(frame, plan.visible.size());
        try {
            drawLayers(minecraft, plan);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            throw new TerrainPreviewRenderFailure(failure);
        }
    }

    private boolean resortTranslucentQuads(TerrainPreviewFrame frame, Matrix4fc modelView,
            Matrix4fc viewProjection) {
        boolean changed = false;
        for (GpuSection section : sections.values()) {
            GpuLayer layer = section.layers.get(RenderType.translucent());
            if (layer == null || layer.sortState == null || layer.sortedFor(modelView)
                    || !intersectsFrustum(frame, viewProjection, section.key)) {
                continue;
            }
            float sectionX = section.key.minBlockX() - frame.originX();
            float sectionY = section.key.minBlockY() - frame.originY();
            float sectionZ = section.key.minBlockZ() - frame.originZ();
            VertexSorting sorting = VertexSorting.byDistance(point ->
                    TerrainTranslucentSortPolicy.viewDistanceSquared(modelView,
                            sectionX, sectionY, sectionZ, point.x(), point.y(), point.z()));
            layer.resort(sorting, modelView);
            changed = true;
        }
        return changed;
    }

    private void disableForSession(String message, Throwable failure) {
        disabledForSession = true;
        if (!failureLogged) {
            failureLogged = true;
            LOGGER.warn("{}; disabling terrain preview until the session is reset", message, failure);
        }
        clear();
    }

    private RenderPlan renderPlan(TerrainPreviewFrame frame, Matrix4fc modelView,
            Matrix4fc viewProjection) {
        RenderPlan cached = cachedRenderPlan;
        if (cached != null && cached.residentRevision == residentRevision
                && cached.frame == frame && cached.modelView.equals(modelView)
                && cached.viewProjection.equals(viewProjection)) {
            renderPlanHits++;
            return cached;
        }
        renderPlanBuilds++;
        List<GpuSection> visible = new ArrayList<>();
        for (GpuSection section : sections.values()) {
            if (intersectsFrustum(frame, viewProjection, section.key)) {
                visible.add(section);
            }
        }
        visible.sort(Comparator.comparingDouble(section -> viewDepth(modelView, frame, section)));
        HashMap<RenderType, List<GpuSection>> draws = new HashMap<>();
        for (RenderType layer : RENDER_LAYERS) {
            List<GpuSection> layerDraws = new ArrayList<>();
            for (GpuSection section : visible) {
                if (section.layers.containsKey(layer)) {
                    layerDraws.add(section);
                }
            }
            draws.put(layer, List.copyOf(layerDraws));
        }
        cached = new RenderPlan(frame, residentRevision, new Matrix4f(modelView),
                new Matrix4f(viewProjection), List.copyOf(visible), draws);
        cachedRenderPlan = cached;
        return cached;
    }

    private void logCoverage(TerrainPreviewFrame frame, int visibleSections) {
        if (!LOGGER.isTraceEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (frame.generation() == lastCoverageGeneration
                && now - lastCoverageLogNanos < COVERAGE_LOG_INTERVAL_NANOS) {
            return;
        }
        lastCoverageGeneration = frame.generation();
        lastCoverageLogNanos = now;
        long unknown = frame.overviewCells().stream()
                .filter(cell -> cell.material() == com.zhongbai233.net_music_can_play_bili.terrain.core
                        .TerrainCellSample.RenderCategory.UNKNOWN)
                .count();
        long overview = frame.overviewCells().size() - unknown;
        LOGGER.trace("地形预览 hardRange 覆盖: generation={}, bounds={}, nearCpu={}, overviewCells={}, wireSegments={}, unknownSections={}, "
                        + "capturePending={}, sampledSections={}, resident={}, visible={}, compilerActive={}",
                frame.generation(), frame.bounds(), frame.fullDetailSections().size(), overview,
                frame.wireframeSegments().size(), unknown,
                frame.pendingSections(), frame.sampledSections(), sections.size(), visibleSections,
                activeCompilation != null);
    }

    private static double viewDistanceSquared(Matrix4fc modelView, TerrainPreviewFrame frame,
            TerrainSectionKey key) {
        float x = key.minBlockX() - frame.originX() + 8.0F;
        float y = key.minBlockY() - frame.originY() + 8.0F;
        float z = key.minBlockZ() - frame.originZ() + 8.0F;
        double viewX = modelView.m00() * x + modelView.m10() * y + modelView.m20() * z + modelView.m30();
        double viewY = modelView.m01() * x + modelView.m11() * y + modelView.m21() * z + modelView.m31();
        double viewZ = modelView.m02() * x + modelView.m12() * y + modelView.m22() * z + modelView.m32();
        return viewX * viewX + viewY * viewY + viewZ * viewZ;
    }

    private static double viewDepth(Matrix4fc modelView, TerrainPreviewFrame frame,
            GpuSection section) {
        float x = section.key.minBlockX() - frame.originX() + 8.0F;
        float y = section.key.minBlockY() - frame.originY() + 8.0F;
        float z = section.key.minBlockZ() - frame.originZ() + 8.0F;
        return modelView.m02() * x + modelView.m12() * y
                + modelView.m22() * z + modelView.m32();
    }

    /** 原版 renderSectionLayer 映射：RenderType 状态 + 默认 uniforms + CHUNK_OFFSET + 持久 VBO。 */
    private void drawLayers(Minecraft minecraft, RenderPlan plan) {
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        for (RenderType layer : RENDER_LAYERS) {
            List<GpuSection> draws = plan.draws.get(layer);
            if (draws.isEmpty()) {
                continue;
            }
            layer.setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) {
                layer.clearRenderState();
                continue;
            }
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, plan.modelView, projection,
                    minecraft.getWindow());
            shader.apply();
            Uniform chunkOffset = shader.CHUNK_OFFSET;
            try {
                for (GpuSection section : draws) {
                    GpuLayer mesh = section.layers.get(layer);
                    if (mesh == null) {
                        continue;
                    }
                    if (chunkOffset != null) {
                        chunkOffset.set(
                                section.key.minBlockX() - plan.frame.originX(),
                                section.key.minBlockY() - plan.frame.originY(),
                                section.key.minBlockZ() - plan.frame.originZ());
                        chunkOffset.upload();
                    }
                    mesh.vertexBuffer.bind();
                    mesh.vertexBuffer.draw();
                }
            } finally {
                if (chunkOffset != null) {
                    chunkOffset.set(0.0F, 0.0F, 0.0F);
                }
                shader.clear();
                VertexBuffer.unbind();
                layer.clearRenderState();
            }
        }
    }

    void clear() {
        compilationEpoch++;
        activeCompilation = null;
        sections.values().forEach(section -> section.close());
        sections.clear();
        failedSources.clear();
        synchronizedBounds = null;
        exhaustedCompilationFrame = null;
        exhaustedCompilationViewProjection = null;
        residentRevision++;
        cachedRenderPlan = null;
        lastCoverageLogNanos = 0L;
        lastCoverageGeneration = Long.MIN_VALUE;
        if (!disabledForSession) {
            failureLogged = false;
        }
        CompilationOutcome outcome;
        while ((outcome = completedCompilations.poll()) != null) {
            outcome.closeCompiled();
        }
    }

    /** 预览会话结束时释放全部 GPU 网格；executor 保留，下一次打开时按需重建。 */
    void releaseSession() {
        if (sections.isEmpty() && activeCompilation == null
                && completedCompilations.isEmpty()) {
            return;
        }
        clear();
        disabledForSession = false;
        failureLogged = false;
        sessionReleases++;
    }

    @Override
    public void close() {
        closed = true;
        releaseSession();
        LOGGER.debug("地形预览稳态缓存: planBuilds={}, planHits={}, sessionReleases={}",
                renderPlanBuilds, renderPlanHits, sessionReleases);
        compilationExecutor.shutdownNow();
    }

    private static ThreadFactory terrainCompilerThreadFactory() {
        return task -> NetMusicThreadFactory.daemonThread("NCPB terrain section compiler", task,
                Thread.NORM_PRIORITY - 1);
    }

    private record CompilationRequest(long epoch, TerrainPreviewFrame frame,
            TerrainBlockSectionSnapshot source, BlockModelShaper modelShaper,
            BlockColors blockColors) {
    }

    private record CompilationOutcome(CompilationRequest request,
            TerrainPreviewSectionCompiler.CompiledCpuSection compiled, Throwable failure) {
        private CompilationOutcome {
            if ((compiled == null) == (failure == null)) {
                throw new IllegalArgumentException("compilation outcome must contain exactly one result");
            }
        }

        private void closeCompiled() {
            if (compiled != null) {
                compiled.close();
            }
        }
    }

    private static final class TerrainPreviewRenderFailure extends RuntimeException {
        private TerrainPreviewRenderFailure(Throwable cause) {
            super(cause);
        }
    }

    private static final class GpuSection implements AutoCloseable {
        private final TerrainSectionKey key;
        private final WeakReference<TerrainBlockSectionSnapshot> source;
        private final Map<RenderType, GpuLayer> layers;

        private GpuSection(TerrainSectionKey key, TerrainBlockSectionSnapshot source,
                Map<RenderType, GpuLayer> layers) {
            this.key = key;
            this.source = new WeakReference<>(source);
            this.layers = layers;
        }

        private static GpuSection upload(TerrainPreviewSectionCompiler.CompiledCpuSection compiled) {
            Map<RenderType, GpuLayer> layers = new HashMap<>();
            try {
                for (Map.Entry<RenderType, MeshData> entry : compiled.layers().entrySet()) {
                    GpuLayer layer = GpuLayer.create(entry.getValue(),
                            compiled.sortStates().get(entry.getKey()));
                    layers.put(entry.getKey(), layer);
                }
                return new GpuSection(compiled.source().section(), compiled.source(), layers);
            } catch (Throwable failure) {
                layers.values().forEach(layer -> layer.close());
                throw failure;
            }
        }

        @Override
        public void close() {
            layers.values().forEach(layer -> layer.close());
            layers.clear();
        }
    }

    private static final class GpuLayer implements AutoCloseable {
        private final VertexBuffer vertexBuffer;
        private final int indexCount;
        private final VertexFormat.IndexType indexType;
        private final boolean customIndices;
        @Nullable
        private final MeshData.SortState sortState;
        private Matrix4f lastSortModelView;

        private GpuLayer(VertexBuffer vertexBuffer, int indexCount, VertexFormat.IndexType indexType,
                boolean customIndices, @Nullable MeshData.SortState sortState) {
            this.vertexBuffer = vertexBuffer;
            this.indexCount = indexCount;
            this.indexType = indexType;
            this.customIndices = customIndices;
            this.sortState = sortState;
        }

        private static GpuLayer create(MeshData mesh, @Nullable MeshData.SortState sortState) {
            int indexCount = mesh.drawState().indexCount();
            VertexFormat.IndexType indexType = mesh.drawState().indexType();
            boolean customIndices = mesh.indexBuffer() != null;
            VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            // upload 会接管并在成功/失败时关闭该 mesh；CompiledCpuSection.close 的二次 close 幂等。
            vertexBuffer.upload(mesh);
            return new GpuLayer(vertexBuffer, indexCount, indexType, customIndices, sortState);
        }

        private boolean sortedFor(Matrix4fc modelView) {
            return !TerrainTranslucentSortPolicy.needsResort(lastSortModelView, modelView);
        }

        private void resort(VertexSorting sorting, Matrix4fc modelView) {
            if (sortState == null) {
                return;
            }
            int bytes = Math.max(256, indexCount * indexType.bytes);
            try (ByteBufferBuilder storage = new ByteBufferBuilder(bytes)) {
                ByteBufferBuilder.Result result = sortState.buildSortedIndexBuffer(storage, sorting);
                if (result == null) {
                    return;
                }
                vertexBuffer.uploadIndexBuffer(result);
            }
            lastSortModelView = new Matrix4f(modelView);
            TerrainPreviewRenderDiagnostics.recordTranslucentResort();
        }

        @Override
        public void close() {
            vertexBuffer.close();
        }
    }

    private record RenderPlan(TerrainPreviewFrame frame, long residentRevision,
            Matrix4f modelView, Matrix4f viewProjection, List<GpuSection> visible,
            HashMap<RenderType, List<GpuSection>> draws) {
    }
}