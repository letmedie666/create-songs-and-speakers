package net.songsandspeakers;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.function.Consumer;

/** Music Manager (plays links) and Cassette Deck (plays cassettes). */
public class SpeakerBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final BooleanProperty POWER = BooleanProperty.create("power");
    /** Set on the client to open the speaker screen. Null on dedicated servers. */
    public static Consumer<BlockPos> openScreen = null;

    public enum Kind { LINK, CASSETTE }

    private final VoxelShape shape;
    private final Kind kind;

    public SpeakerBlock(Properties props, VoxelShape shape, Kind kind) {
        super(props);
        this.shape = shape;
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWER, false));
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return simpleCodec(p -> new SpeakerBlock(p, shape, kind));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWER);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shape;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(kind == Kind.LINK
                ? "tooltip.songsandspeakers.music_manager" : "tooltip.songsandspeakers.cassette_deck").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.songsandspeakers.redstone").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide && openScreen != null) openScreen.accept(pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.is(SongsAndSpeakers.PARABOLIC_ANTENNA.get())) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof SpeakerBlockEntity be) {
                if (be.installAntenna()) {
                    stack.consume(1, player);
                    player.displayClientMessage(Component.translatable("message.songsandspeakers.antenna_installed",
                            RadioSettings.format(be.getFrequency())), true);
                } else {
                    player.displayClientMessage(Component.translatable("message.songsandspeakers.antenna_already"), true);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.getItem() instanceof CassetteItem) {
            if (kind != Kind.CASSETTE) {
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.translatable("message.songsandspeakers.cassette_wrong_block"), true);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof SpeakerBlockEntity be) {
                ItemStack old = be.insertCassette(stack);
                stack.consume(1, player);
                if (!old.isEmpty() && !player.getInventory().add(old)) player.drop(old, false);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SpeakerBlockEntity be) {
            be.initRedstone(level.hasNeighborSignal(pos));
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SpeakerBlockEntity be) {
            be.onRedstone(level.hasNeighborSignal(pos));
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SpeakerBlockEntity be ? be.comparatorSignal() : 0;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof SpeakerBlockEntity be) {
            ItemStack c = be.takeCassetteForDrop();
            if (!c.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, c);
            if (be.takeAntennaForDrop()) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        new ItemStack(SongsAndSpeakers.PARABOLIC_ANTENNA.get()));
            }
            if (level instanceof ServerLevel sl) {
                RadioService.updateChunkLoading(sl, pos, false);
                RadioService.unregister(sl, pos);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }
}
