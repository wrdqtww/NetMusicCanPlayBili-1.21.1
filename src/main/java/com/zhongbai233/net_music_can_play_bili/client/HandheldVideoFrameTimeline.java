package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaTimelineView;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaDeviceProfile;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoFrame;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoPipelineConfig;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Frame validation, bounded queueing, and media-timeline presentation for handheld video. */
final class HandheldVideoFrameTimeline {
    private static final HandheldVideoPipelineConfig CONFIG = HandheldVideoPipelineConfig.fromSystemProperties(
            "ncpb.mp4.video");
    private static final AtomicBoolean HIGH_RESOLUTION_WARNING_SHOWN = new AtomicBoolean(false);

    private HandheldVideoFrameTimeline() {
    }

    static long latestFrameMillis(HandheldDeviceVideoState state, HandheldVideoSession session) {
        long latestPts = -1L;
        HandheldVideoFrame latest = state.latestFrame.get();
        if (latest != null) {
            latestPts = Math.max(latestPts, latest.ptsNanos());
        }
        synchronized (state.frameQueueLock) {
            for (HandheldVideoFrame frame : state.frameQueue) {
                latestPts = Math.max(latestPts, frame.ptsNanos());
            }
        }
        return latestPts >= 0L ? session.decoderStartOffsetMillis + latestPts / 1_000_000L : -1L;
    }

    static boolean hasFrameBytes(Fmp4NativeVideoDecoder.DecodedFrame decoded, int requiredBytes) {
        ByteBuffer buffer = decoded.buffer();
        if (buffer != null) {
            return decoded.byteLength() >= requiredBytes && buffer.remaining() >= requiredBytes;
        }
        byte[] data = decoded.data();
        return data != null && data.length >= requiredBytes;
    }

    static long framePtsOrFallback(long decodedPtsNanos, long frameIndex, int fps) {
        if (decodedPtsNanos >= 0L) {
            return decodedPtsNanos;
        }
        return Math.max(0L, Math.round(frameIndex * 1_000_000_000.0D / Math.max(1, fps)));
    }

    static int requiredFrameBytes(Fmp4NativeVideoDecoder.DecodedFrame.Format format, int width, int height) {
        int pixels = Math.max(1, width) * Math.max(1, height);
        return switch (format) {
            case NV12, YUV420P -> pixels + pixels / 2;
            case RGBA -> pixels * 4;
        };
    }

    /**
     * 手持视频是否必须解码成 RGBA。
     *
     * <p>与投影仪路径共用同一判据 {@link VideoBillboardPreview#requiresRgbaFallback()}：装了 Iris
     * shaderpack，<b>或者</b>自定义 YUV 着色器在当前平台不可用。</p>
     *
     * <p>原实现只判断前者，于是默认配置（无 shaderpack）下会话被建成 NV12 输出——而 1.21.1 上
     * 承载 NV12 的自建 RenderType 不会被光栅化，同时 RGBA 回退层又因格式不是 RGBA 而拒绝上传，
     * 两边都走不通，表现为 MP4 与掌机<b>永远没有画面</b>。</p>
     */
    static boolean shouldUseRgbaFallback() {
        return VideoBillboardPreview.requiresRgbaFallback();
    }

    static boolean hasActiveRgbaConsumer(HandheldDeviceVideoState state) {
        return state != null && System.nanoTime() <= state.rgbaConsumerUntilNanoTime;
    }

    static MP4HandheldVideoClient.DecodeSize chooseDecodeSize(int sourceWidth, int sourceHeight) {
        int safeSourceWidth = Math.max(2, sourceWidth);
        int safeSourceHeight = Math.max(2, sourceHeight);
        int maxWidth = CONFIG.maxAllowedWidth();
        int maxHeight = CONFIG.maxAllowedHeight();
        double scale = Math.min(1.0D, Math.min(maxWidth / (double) safeSourceWidth,
                maxHeight / (double) safeSourceHeight));
        int width = evenAtLeastTwo((int) Math.round(safeSourceWidth * scale));
        int height = evenAtLeastTwo((int) Math.round(safeSourceHeight * scale));
        if (width > maxWidth) {
            width = evenAtLeastTwo(maxWidth);
            height = evenAtLeastTwo((int) Math.round(width * safeSourceHeight / (double) safeSourceWidth));
        }
        if (height > maxHeight) {
            height = evenAtLeastTwo(maxHeight);
            width = evenAtLeastTwo((int) Math.round(height * safeSourceWidth / (double) safeSourceHeight));
        }
        return new MP4HandheldVideoClient.DecodeSize(width, height);
    }

    static void maybeWarnHighResolution(MP4HandheldVideoClient.DecodeSize decodeSize) {
        if (decodeSize.width() <= CONFIG.highResWarningWidth()
                && decodeSize.height() <= CONFIG.highResWarningHeight()) {
            return;
        }
        if (!HIGH_RESOLUTION_WARNING_SHOWN.compareAndSet(false, true)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.mp4.high_resolution_warning",
                    decodeSize.width(), decodeSize.height()));
        }
    }

    static boolean waitForDecodeLead(UUID deviceId, HandheldDeviceVideoState state, HandheldVideoSession session,
            long framePtsNanos) {
        long targetNanos = Math.max(0L, framePtsNanos);
        while (!session.closed.get() && session.key.equals(state.activeKey)) {
            HandheldMediaDeviceProfile profile = MP4HandheldVideoClient.profileFor(deviceId);
            HandheldMediaPlayback playback = profile.playback(deviceId);
            if (playback == null || !session.key.playbackSessionId().equals(playback.playbackSessionId())) {
                return false;
            }
            long visualMillis = anchoredVisualMillis(deviceId, profile, playback);
            long visualNanos = sessionRelativeVisualNanos(session, visualMillis);
            long leadNanos = targetNanos - visualNanos;
            if (leadNanos <= CONFIG.maxDecodeLeadNanos()) {
                return true;
            }
            pumpFrameForTimeline(state, session, visualMillis);
            long sleepMillis = Math.min(CONFIG.frameWaitSliceMillis(),
                    Math.max(1L, (leadNanos - CONFIG.maxDecodeLeadNanos()) / 1_000_000L));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static boolean shouldDropStaleStartupFrame(UUID deviceId, HandheldDeviceVideoState state,
            HandheldVideoSession session, long framePtsNanos) {
        if (CONFIG.startupDropLagNanos() <= 0L) {
            return false;
        }
        HandheldMediaDeviceProfile profile = MP4HandheldVideoClient.profileFor(deviceId);
        HandheldMediaPlayback playback = profile.playback(deviceId);
        long visualNanos = sessionRelativeVisualNanos(session, anchoredVisualMillis(deviceId, profile, playback));
        return visualNanos - Math.max(0L, framePtsNanos) > CONFIG.startupDropLagNanos() && frameQueueEmpty(state);
    }

    static boolean offerFrame(HandheldDeviceVideoState state, HandheldVideoSession session, HandheldVideoFrame frame) {
        synchronized (state.frameQueueLock) {
            while (!session.closed.get() && session.key.equals(state.activeKey)
                    && state.frameQueue.size() >= Math.max(1, CONFIG.frameQueueCapacity())) {
                try {
                    state.frameQueueLock.wait(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (session.closed.get() || !session.key.equals(state.activeKey)) {
                return false;
            }
            state.frameQueue.addLast(frame);
            state.frameQueueLock.notifyAll();
            return true;
        }
    }

    static boolean frameQueueEmpty(HandheldDeviceVideoState state) {
        synchronized (state.frameQueueLock) {
            return state.frameQueue.isEmpty();
        }
    }

    static void clearFrameQueue(HandheldDeviceVideoState state) {
        synchronized (state.frameQueueLock) {
            for (HandheldVideoFrame frame : state.frameQueue) {
                frame.close();
            }
            state.frameQueue.clear();
            state.frameQueueLock.notifyAll();
        }
    }

    static long sessionRelativeVisualNanos(HandheldVideoSession session, long visualMillis) {
        return Math.max(0L, visualMillis - session.decoderStartOffsetMillis) * 1_000_000L;
    }

    static long anchoredVisualMillis(UUID deviceId, HandheldMediaDeviceProfile profile,
            HandheldMediaPlayback playback) {
        if (playback == null || playback.timeline() == null) {
            return -1L;
        }
        return ClientMediaTimelineView.forHandheldOwner(deviceId, playback,
                MP4HandheldVideoClient.profileFor(deviceId, profile).hasStartedSound(deviceId, playback.sessionId()),
                playback.timeline().visualMillis(), playback.timeline().totalMillis()).visualMillis();
    }

    static boolean pumpFrameForTimeline(HandheldDeviceVideoState state, HandheldVideoSession session,
            long visualMillis) {
        long visualNanos = sessionRelativeVisualNanos(session, visualMillis);
        HandheldVideoFrame selected = null;
        long droppedFrames = 0L;
        synchronized (state.frameQueueLock) {
            while (!state.frameQueue.isEmpty()) {
                HandheldVideoFrame first = state.frameQueue.peekFirst();
                if (first.ptsNanos() > visualNanos + CONFIG.earlyToleranceNanos() && selected == null) {
                    break;
                }
                HandheldVideoFrame candidate = state.frameQueue.pollFirst();
                if (candidate.ptsNanos() <= visualNanos + CONFIG.earlyToleranceNanos()) {
                    if (selected != null) {
                        selected.close();
                        droppedFrames++;
                    }
                    selected = candidate;
                } else {
                    state.frameQueue.addFirst(candidate);
                    break;
                }
            }
            while (state.frameQueue.size() > 1
                    && visualNanos - state.frameQueue.peekFirst().ptsNanos() > CONFIG.maxLateFrameNanos()) {
                if (selected != null) {
                    selected.close();
                    droppedFrames++;
                }
                selected = state.frameQueue.pollFirst();
            }
        }
        session.performanceMonitor.recordDroppedFrames(droppedFrames);
        if (selected != null) {
            HandheldVideoFrame previous = state.latestFrame.getAndSet(selected);
            if (previous != null) {
                previous.close();
            }
            state.frameSequence.incrementAndGet();
            synchronized (state.frameQueueLock) {
                state.frameQueueLock.notifyAll();
            }
            session.observeTimelineAndEvaluate(state, visualMillis);
            return true;
        }
        if (session.performanceMonitor.started() && frameQueueEmpty(state)) {
            session.performanceMonitor.recordStarvation();
        }
        session.observeTimelineAndEvaluate(state, visualMillis);
        return false;
    }

    private static int evenAtLeastTwo(int value) {
        int safe = Math.max(2, value);
        return (safe & 1) == 0 ? safe : safe - 1;
    }
}
