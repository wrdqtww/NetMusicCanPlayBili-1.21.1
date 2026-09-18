package com.zhongbai233.net_music_can_play_bili.init;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.block.LiveStreamerBlock;
import com.zhongbai233.net_music_can_play_bili.block.ControlConsoleBlock;
import com.zhongbai233.net_music_can_play_bili.block.LyricProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.block.SpeakerBlock;
import com.zhongbai233.net_music_can_play_bili.block.VideoProjectorBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(NetMusicCanPlayBili.MODID);

    public static final DeferredBlock<Block> MODERN_TURNTABLE = BLOCKS.register(
            "modern_turntable",
            id -> new ModernTurntableBlock(BlockBehaviour.Properties.of()));

    public static final DeferredBlock<Block> LYRIC_PROJECTOR = BLOCKS.register(
            "lyric_projector",
            id -> new LyricProjectorBlock(BlockBehaviour.Properties.of()));

    public static final DeferredBlock<Block> VIDEO_PROJECTOR = BLOCKS.register(
            "video_projector",
            id -> new VideoProjectorBlock(BlockBehaviour.Properties.of()));

    public static final DeferredBlock<Block> SPEAKER = BLOCKS.register(
            "speaker",
            id -> new SpeakerBlock(BlockBehaviour.Properties.of()));

    public static final DeferredBlock<Block> LIVE_STREAMER = BLOCKS.register(
            "live_streamer",
            id -> new LiveStreamerBlock(BlockBehaviour.Properties.of()));

    public static final DeferredBlock<Block> CONTROL_CONSOLE = BLOCKS.register(
            "control_console",
            id -> new ControlConsoleBlock(BlockBehaviour.Properties.of()));

    private ModBlocks() {
    }
}
