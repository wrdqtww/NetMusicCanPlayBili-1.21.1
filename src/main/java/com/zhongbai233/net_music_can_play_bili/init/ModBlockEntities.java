package com.zhongbai233.net_music_can_play_bili.init;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister
            .create(Registries.BLOCK_ENTITY_TYPE, NetMusicCanPlayBili.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ModernTurntableBlockEntity>> MODERN_TURNTABLE = BLOCK_ENTITY_TYPES
            .register(
                    "modern_turntable",
                    () -> BlockEntityType.Builder.of(
                            ModernTurntableBlockEntity::new,
                            ModBlocks.MODERN_TURNTABLE.get())
                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LyricProjectorBlockEntity>> LYRIC_PROJECTOR = BLOCK_ENTITY_TYPES
            .register(
                    "lyric_projector",
                    () -> BlockEntityType.Builder.of(
                            LyricProjectorBlockEntity::new,
                            ModBlocks.LYRIC_PROJECTOR.get())
                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VideoProjectorBlockEntity>> VIDEO_PROJECTOR = BLOCK_ENTITY_TYPES
            .register(
                    "video_projector",
                    () -> BlockEntityType.Builder.of(
                            VideoProjectorBlockEntity::new,
                            ModBlocks.VIDEO_PROJECTOR.get())
                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SpeakerBlockEntity>> SPEAKER = BLOCK_ENTITY_TYPES
            .register(
                    "speaker",
                    () -> BlockEntityType.Builder.of(
                            SpeakerBlockEntity::new,
                            ModBlocks.SPEAKER.get())
                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LiveStreamerBlockEntity>> LIVE_STREAMER = BLOCK_ENTITY_TYPES
            .register(
                    "live_streamer",
                    () -> BlockEntityType.Builder.of(
                            LiveStreamerBlockEntity::new,
                            ModBlocks.LIVE_STREAMER.get())
                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ControlConsoleBlockEntity>> CONTROL_CONSOLE = BLOCK_ENTITY_TYPES
            .register(
                    "control_console",
                    () -> BlockEntityType.Builder.of(
                            ControlConsoleBlockEntity::new,
                            ModBlocks.CONTROL_CONSOLE.get())
                            .build(null));

    private ModBlockEntities() {
    }
}