package com.zhongbai233.net_music_can_play_bili.block;

import com.mojang.serialization.MapCodec;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import com.zhongbai233.net_music_can_play_bili.item.MediaManagementToolItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
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

public class VideoProjectorBlock extends Block implements EntityBlock {
    /**
     * 设备朝向。用全部六个方向而不是 HORIZONTAL_FACING：旧存档里已经存在 {@code facing=up}，
     * 收窄取值域会让存档重新解析方块状态时失败。放置时写入水平方向，上下仅作历史兼容。
     */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final BooleanProperty ACTIVATED = BooleanProperty.create("activated");
    private static final MapCodec<VideoProjectorBlock> CODEC = simpleCodec(VideoProjectorBlock::new);
    private static final VoxelShape SHAPE = Block.box(2.75, 0, 2.75, 13.25, 5.3, 13.25);

    public VideoProjectorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.UP)
                .setValue(ACTIVATED, false));
    }

    @Override
    protected MapCodec<VideoProjectorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVATED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 与原版熔炉一致：正面朝向放置者。这样模型朝向与投射面法线同向（投射面也是正对放置者）。
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** 旋转/镜像时保持朝向语义（与原版熔炉等朝向方块一致）。 */
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
        return new VideoProjectorBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, net.minecraft.core.BlockPos pos,
            CollisionContext context) {
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

    /**
     * 让投射面正对放置者。
     *
     * <p>方块实体里 {@code projectionYaw} 默认 180°（法线朝北），所以此前不论从哪个方向放置，
     * 屏幕都固定朝北。这里按放置者的水平视线写入初始值，只吸附四个正方向便于对齐建筑；玩家之后
     * 仍可在配置界面的 yaw 滑条（0–360°）上任意微调。放置者可能为空（发射器），此时保留默认朝向。</p>
     */
    private static void applyPlacementFacing(Level level, BlockPos pos, LivingEntity placer) {
        if (placer == null) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector) {
            projector.setProjectionYaw(placementYaw(placer.getDirection()));
            // setProjectionYaw 只调 setChanged()，跨端还要靠方块实体更新包；否则客户端在区块重载前
            // 仍按默认 180° 渲染，表现为"放下时朝向没变"。
            projector.markDirtyAndSync();
        }
    }

    /** 从物品 NBT 读取远程连接目标并写入视频投影仪方块实体 */
    private static void applyLinkedPosition(Level level, BlockPos pos, ItemStack stack, LivingEntity placer) {
        BlockPos linkedPos = LinkHelper.readLinkFromItem(stack);
        if (linkedPos == null) {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof VideoProjectorBlockEntity projector) {
            projector.linkTo(linkedPos);
            if (!(placer instanceof Player player) || !player.isCreative()) {
                LinkHelper.clearLinkFromItem(stack);
                clearLinkedBlockEntityData(stack);
            }
        }
    }

    /**
     * 同步写入标准方块实体组件。MinecartRevolution 等通用方块载具会复制该组件，
     * 因而无需依赖本模组的物品 CUSTOM_DATA 即可保留唱片机链接。
     *
     * <p><b>必须补 id:</b>{@code BLOCK_ENTITY_DATA} 的持久化 codec 是
     * {@code CustomData.CODEC_WITH_ID},要求 NBT 含字符串类型的 {@code id}(方块实体类型)。
     * 从创造栏取出的新物品没有该组件,此时若直接写入就会得到一个"缺 id"的组件,
     * 玩家背包保存(自动保存/退出世界)时抛
     * {@code IllegalStateException: Missing id for entity in: {...}} 并使服务端崩溃。</p>
     */
    public static void writeLinkedBlockEntityData(ItemStack stack, BlockPos linkedPos) {
        CustomData existing = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        CompoundTag tag = existing != null ? existing.copyTag() : new CompoundTag();
        ensureBlockEntityId(tag);
        tag.putBoolean("LinkedTarget_has", true);
        tag.putInt("LinkedTarget_x", linkedPos.getX());
        tag.putInt("LinkedTarget_y", linkedPos.getY());
        tag.putInt("LinkedTarget_z", linkedPos.getZ());
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
    }

    /**
     * 保证 {@code BLOCK_ENTITY_DATA} 携带方块实体类型 id。
     * 只在本模组投影仪自身的数据里补写,不会覆盖别处写好的 id。
     */
    private static void ensureBlockEntityId(CompoundTag tag) {
        if (tag.contains("id", Tag.TAG_STRING)) {
            return;
        }
        ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(ModBlockEntities.VIDEO_PROJECTOR.get());
        if (key != null) {
            tag.putString("id", key.toString());
        }
    }

    /** 清除标准方块实体组件中的唱片机链接，同时保留投影参数等其它数据。 */
    public static void clearLinkedBlockEntityData(ItemStack stack) {
        CustomData existing = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (existing == null) {
            return;
        }
        CompoundTag tag = existing.copyTag();
        tag.remove("LinkedTarget_has");
        tag.remove("LinkedTarget_x");
        tag.remove("LinkedTarget_y");
        tag.remove("LinkedTarget_z");
        if (tag.isEmpty()) {
            stack.remove(DataComponents.BLOCK_ENTITY_DATA);
            return;
        }
        ensureBlockEntityId(tag);
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (hand == InteractionHand.MAIN_HAND && stack.getItem() instanceof MediaManagementToolItem) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!player.mayBuild()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            openClientScreen(pos);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!player.mayBuild()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            openClientScreen(pos);
        }
        return InteractionResult.SUCCESS;
    }

    private static void openClientScreen(BlockPos pos) {
        com.zhongbai233.net_music_can_play_bili.client.VideoProjectorClient.openScreen(pos);
    }
}
