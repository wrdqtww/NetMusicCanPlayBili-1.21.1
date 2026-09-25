package com.zhongbai233.net_music_can_play_bili.block;

import com.mojang.serialization.MapCodec;
import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.LyricProjectorClient;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 歌词投影仪方块
 */
public class LyricProjectorBlock extends Block implements EntityBlock {
    /** 同视频投影仪：用全部六方向，保证旧存档的 {@code facing=up} 仍可解析。 */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final BooleanProperty LINKED = BooleanProperty.create("linked");
    public static final BooleanProperty ACTIVATED = BooleanProperty.create("activated");
    private static final MapCodec<LyricProjectorBlock> CODEC = simpleCodec(LyricProjectorBlock::new);
    private static final VoxelShape SHAPE = Block.box(2.75, 0, 2.75, 13.25, 5.3, 13.25);

    public LyricProjectorBlock(Properties properties) {
        super(properties.sound(SoundType.METAL)
                .strength(2.0F)
                .lightLevel(state -> state.getValue(ACTIVATED) ? 15 : 0)
                .noOcclusion());
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.UP)
                .setValue(LINKED, false)
                .setValue(ACTIVATED, false));
    }

    @Override
    protected MapCodec<LyricProjectorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LINKED, ACTIVATED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LyricProjectorBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()) {
            applyPlacementFacing(level, pos, placer);
            applyLinkedPosition(level, pos, stack, placer);
        }
    }

    /**
     * 放置者的水平朝向 → 投射面 yaw。
     *
     * <p>渲染侧屏幕法线为 {@code (sin yaw, 0, cos yaw)}：yaw 180° 时法线朝北，与方块实体
     * {@code projectionYaw} 的既有默认值完全一致。要让屏幕正对放置者，法线必须指向其视线的
     * 反方向，于是映射为 南→180°、东→270°、北→0°、西→90°。</p>
     *
     * <p><b>不能直接用 {@code Direction#toYRot()}：</b>那是实体 yaw 约定（南0/西90/北180/东270），
     * 与渲染侧在东西两个方向上符号相反。实测正是"东西正确、南北反了"——因为两套约定在 0°/180°
     * 上取值相同，只在 90°/270° 上相差一个符号。</p>
     */
    private static float placementYaw(Direction facing) {
        return switch (facing) {
            case NORTH -> 0.0F;
            case EAST -> 270.0F;
            case SOUTH -> 180.0F;
            case WEST -> 90.0F;
            default -> 180.0F;
        };
    }

    /** 让投影面正对放置者，映射规则与视频投影仪完全一致（见 {@link #placementYaw}）。 */
    private static void applyPlacementFacing(Level level, BlockPos pos, LivingEntity placer) {
        if (placer == null) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof LyricProjectorBlockEntity projector) {
            projector.setProjectionYaw(placementYaw(placer.getDirection()));
            projector.markDirtyAndSync();
        }
    }

    /** 从物品 NBT 读取远程连接目标并写入投影仪方块实体 */
    private static void applyLinkedPosition(Level level, BlockPos pos, ItemStack stack, LivingEntity placer) {
        BlockPos linkedPos = LinkHelper.readLinkFromItem(stack);
        if (linkedPos == null)
            return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof LyricProjectorBlockEntity projector) {
            projector.linkTo(linkedPos);
            if (!(placer instanceof Player player) || !player.isCreative()) {
                LinkHelper.clearLinkFromItem(stack);
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!player.mayBuild()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            LyricProjectorClient.openScreen(pos);
        }
        return InteractionResult.SUCCESS;
    }
}
