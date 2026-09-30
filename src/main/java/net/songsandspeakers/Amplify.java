package net.songsandspeakers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Boomboxes near a music block make it audible further away. */
public final class Amplify {
    public static final int SEARCH_RADIUS = 6;
    public static final int BASE_RANGE = 32;
    public static final int RANGE_PER_BOOMBOX = 24;
    public static final int MAX_BOOMBOXES = 4;

    private Amplify() {}

    public static int countBoomboxes(BlockGetter level, BlockPos source) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(source.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                source.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
            if (level.getBlockState(p).getBlock() instanceof BoomboxBlock && ++n >= MAX_BOOMBOXES) break;
        }
        return n;
    }

    public static int range(int boomboxes) {
        return BASE_RANGE + RANGE_PER_BOOMBOX * Math.min(boomboxes, MAX_BOOMBOXES);
    }

    /** True if a music block is playing within reach of this boombox. */
    public static boolean musicNearby(BlockGetter level, BlockPos boombox) {
        for (BlockPos p : BlockPos.betweenClosed(boombox.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                boombox.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof SpeakerBlock && s.getValue(SpeakerBlock.POWER)) return true;
        }
        return false;
    }
}
