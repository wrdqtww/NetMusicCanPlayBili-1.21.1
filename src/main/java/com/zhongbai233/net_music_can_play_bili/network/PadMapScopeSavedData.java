package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.UUID;

/** 每个服务端存档/后端持久化一个 Pad 地图缓存作用域 UUID。 */
public final class PadMapScopeSavedData extends SavedData {
    private static final String NAME = "pad_map_scope";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Codec<PadMapScopeSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("worldScopeId").forGetter(data -> data.worldScopeId))
            .apply(instance, PadMapScopeSavedData::new));

    public static final SavedData.Factory<PadMapScopeSavedData> FACTORY = new SavedData.Factory<>(
            PadMapScopeSavedData::new,
            PadMapScopeSavedData::load);

    private final String worldScopeId;

    public PadMapScopeSavedData() {
        this(UUID.randomUUID().toString());
        setDirty();
    }

    private PadMapScopeSavedData(String worldScopeId) {
        this.worldScopeId = normalize(worldScopeId);
    }

    public static PadMapScopeSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, this)
                .resultOrPartial(error -> LOGGER.error("Failed to save pad map scope: {}", error))
                .ifPresent(encoded -> {
                    if (encoded instanceof CompoundTag compound) {
                        tag.merge(compound);
                    }
                });
        return tag;
    }

    private static PadMapScopeSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(error -> LOGGER.warn("Failed to load pad map scope: {}", error))
                .orElseGet(PadMapScopeSavedData::new);
    }

    public String worldScopeId() {
        return worldScopeId;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return value.trim();
    }
}
