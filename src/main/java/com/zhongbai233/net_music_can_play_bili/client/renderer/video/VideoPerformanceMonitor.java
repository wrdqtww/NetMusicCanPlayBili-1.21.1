package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;

import java.util.Arrays;

/** Thread-safe per-candidate collector for the five-second performance budget. */
public final class VideoPerformanceMonitor {
    private static final int MAX_DECODE_SAMPLES = 512;
    private static final long RESOURCE_SAMPLE_INTERVAL_NANOS = 500_000_000L;

    private final long[] decodeSamples = new long[MAX_DECODE_SAMPLES];
    /**
     * p95 的排序暂存区与按代次缓存。
     *
     * <p>{@code snapshot()} 在渲染线程上每帧会被调用 2–3 次，原实现每次 {@code Arrays.copyOf} 512 长
     * 数组再 {@code Arrays.sort}，即每秒上百次 copy+sort 外加等量堆分配。p95 只取决于采样内容、
     * 与调用时刻无关，因此这里复用一块预分配 scratch，并用"采样代次"做失效判据：只有解码线程写入
     * 新样本时才重排一次。所有访问都在 {@code synchronized} 方法内，scratch 复用是安全的。</p>
     */
    private final long[] sortedScratch = new long[MAX_DECODE_SAMPLES];
    private long sampleGeneration;
    private long sortedGeneration = -1L;
    private double cachedP95Millis;
    private int decodeSampleCount;
    private int decodeSampleCursor;
    private long decodeNanosTotal;
    private long decodedFrames;
    private long starvationCount;
    private long droppedFrames;
    private long startedNanos;
    private long suspendedStartedNanos;
    private long suspendedNanos;
    private long lastResourceSampleNanos;
    private long nativeFrameBytesPeak;
    private long nativeSurfacePeak;
    private long firstSyncDriftMagnitudeMillis = Long.MIN_VALUE;
    private long latestSyncDriftMillis;
    private long previousSyncDriftMagnitudeMillis = Long.MIN_VALUE;
    private int consecutiveDriftGrowthSamples;
    private long observationEpoch;
    private int targetFps = 1;
    private String backend = "unknown";
    private boolean started;

    public synchronized void start(long nowNanos, int targetFps, String backend) {
        Arrays.fill(decodeSamples, 0L);
        decodeSampleCount = 0;
        decodeSampleCursor = 0;
        sampleGeneration = 0L;
        sortedGeneration = -1L;
        cachedP95Millis = 0.0D;
        decodeNanosTotal = 0L;
        decodedFrames = 0L;
        starvationCount = 0L;
        droppedFrames = 0L;
        startedNanos = nowNanos;
        suspendedStartedNanos = 0L;
        suspendedNanos = 0L;
        lastResourceSampleNanos = 0L;
        nativeFrameBytesPeak = 0L;
        nativeSurfacePeak = 0L;
        firstSyncDriftMagnitudeMillis = Long.MIN_VALUE;
        latestSyncDriftMillis = 0L;
        previousSyncDriftMagnitudeMillis = Long.MIN_VALUE;
        consecutiveDriftGrowthSamples = 0;
        this.targetFps = Math.max(1, targetFps);
        this.backend = backend == null || backend.isBlank() ? "unknown" : backend;
        observationEpoch++;
        started = true;
    }

    public synchronized void pause(long nowNanos) {
        if (started && suspendedStartedNanos == 0L) {
            suspendedStartedNanos = Math.max(startedNanos, nowNanos);
        }
    }

    public synchronized void resume(long nowNanos) {
        if (started && suspendedStartedNanos != 0L) {
            suspendedNanos += Math.max(0L, nowNanos - suspendedStartedNanos);
            suspendedStartedNanos = 0L;
        }
    }

    public synchronized void recordDecodedFrame(long decodeNanos) {
        if (!started) {
            return;
        }
        long safe = Math.max(0L, decodeNanos);
        decodedFrames++;
        decodeNanosTotal += safe;
        decodeSamples[decodeSampleCursor] = safe;
        decodeSampleCursor = (decodeSampleCursor + 1) % decodeSamples.length;
        decodeSampleCount = Math.min(decodeSamples.length, decodeSampleCount + 1);
        sampleGeneration++;
    }

    public synchronized void recordStarvation() {
        if (started) {
            starvationCount++;
        }
    }

    public synchronized void recordDroppedFrames(long count) {
        if (started && count > 0L) {
            droppedFrames += count;
        }
    }

    public synchronized void recordSyncDriftMillis(long driftMillis) {
        if (!started) {
            return;
        }
        long magnitude = driftMillis == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(driftMillis);
        latestSyncDriftMillis = driftMillis;
        if (firstSyncDriftMagnitudeMillis == Long.MIN_VALUE) {
            firstSyncDriftMagnitudeMillis = magnitude;
        }
        if (previousSyncDriftMagnitudeMillis != Long.MIN_VALUE
                && magnitude > previousSyncDriftMagnitudeMillis + 5L) {
            consecutiveDriftGrowthSamples++;
        } else if (previousSyncDriftMagnitudeMillis != Long.MIN_VALUE
                && magnitude <= previousSyncDriftMagnitudeMillis) {
            consecutiveDriftGrowthSamples = 0;
        }
        previousSyncDriftMagnitudeMillis = magnitude;
    }
    public synchronized void resetSyncDriftWindow() {
        firstSyncDriftMagnitudeMillis = Long.MIN_VALUE;
        latestSyncDriftMillis = 0L;
        previousSyncDriftMagnitudeMillis = Long.MIN_VALUE;
        consecutiveDriftGrowthSamples = 0;
    }


    /** Samples process-wide native counters at most twice per second. */
    public void sampleNativeResources(long nowNanos) {
        long epoch;
        synchronized (this) {
            if (!started || (lastResourceSampleNanos != 0L
                    && nowNanos - lastResourceSampleNanos < RESOURCE_SAMPLE_INTERVAL_NANOS)) {
                return;
            }
            lastResourceSampleNanos = nowNanos;
            epoch = observationEpoch;
        }
        VideoNativeDecoder.NativeMemoryStats stats = VideoNativeDecoder.nativeMemoryStats();
        synchronized (this) {
            if (started && epoch == observationEpoch && stats.available()) {
                nativeFrameBytesPeak = Math.max(nativeFrameBytesPeak,
                        Math.max(stats.ffmpegCurrentBytes(), stats.d3d11LogicalBytesCurrent()));
                nativeSurfacePeak = Math.max(nativeSurfacePeak, stats.d3d11SurfaceCurrent());
            }
        }
    }

    /** 返回当前采样窗口的 p95 解码耗时；仅在采样代次变化时重排一次。 */
    private double p95Millis() {
        if (sortedGeneration != sampleGeneration) {
            System.arraycopy(decodeSamples, 0, sortedScratch, 0, decodeSampleCount);
            Arrays.sort(sortedScratch, 0, decodeSampleCount);
            cachedP95Millis = decodeSampleCount == 0 ? 0.0D
                    : sortedScratch[Math.min(decodeSampleCount - 1,
                            (int) Math.ceil(decodeSampleCount * 0.95D) - 1)] / 1_000_000.0D;
            sortedGeneration = sampleGeneration;
        }
        return cachedP95Millis;
    }

    public synchronized VideoPerformanceFallbackPolicy.Snapshot snapshot(long nowNanos) {
        long observationNanos = observationNanos(nowNanos);
        double seconds = observationNanos / 1_000_000_000.0D;
        double actualFps = seconds > 0.0D ? decodedFrames / seconds : 0.0D;
        double averageMillis = decodedFrames > 0L
                ? decodeNanosTotal / (double) decodedFrames / 1_000_000.0D : 0.0D;
        double p95Millis = p95Millis();
        long latestMagnitude = latestSyncDriftMillis == Long.MIN_VALUE
                ? Long.MAX_VALUE : Math.abs(latestSyncDriftMillis);
        long driftGrowth = firstSyncDriftMagnitudeMillis == Long.MIN_VALUE
                ? 0L : Math.max(0L, latestMagnitude - firstSyncDriftMagnitudeMillis);
        long totalOutcomes = decodedFrames + droppedFrames;
        double dropRatio = totalOutcomes > 0L ? droppedFrames / (double) totalOutcomes : 0.0D;
        return new VideoPerformanceFallbackPolicy.Snapshot(
                observationNanos / 1_000_000L, targetFps, decodedFrames, actualFps,
                averageMillis, p95Millis, starvationCount, droppedFrames, dropRatio,
                latestSyncDriftMillis, driftGrowth, consecutiveDriftGrowthSamples,
                backend, nativeFrameBytesPeak, nativeSurfacePeak);
    }

    public synchronized boolean started() {
        return started;
    }

    private long observationNanos(long nowNanos) {
        if (!started) {
            return 0L;
        }
        long pendingSuspension = suspendedStartedNanos != 0L
                ? Math.max(0L, nowNanos - suspendedStartedNanos) : 0L;
        return Math.max(0L, nowNanos - startedNanos - suspendedNanos - pendingSuspension);
    }
}
