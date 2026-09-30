package net.songsandspeakers;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.BiConsumer;

public class CassetteItem extends Item {
    /** Set on the client: opens the cassette editor. */
    public static BiConsumer<InteractionHand, ItemStack> openEditor = null;

    public CassetteItem(Properties props) {
        super(props);
    }

    public static List<Track> tracks(ItemStack stack) {
        return stack.getOrDefault(SongsAndSpeakers.TRACKS.get(), List.of());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide && openEditor != null) openEditor.accept(hand, stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        List<Track> t = tracks(stack);
        if (t.isEmpty()) {
            tooltip.add(Component.translatable("tooltip.songsandspeakers.empty").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.songsandspeakers.tracks", t.size()).withStyle(ChatFormatting.GRAY));
            for (int i = 0; i < Math.min(5, t.size()); i++) {
                tooltip.add(Component.literal((i + 1) + ". " + t.get(i).displayName()).withStyle(ChatFormatting.DARK_GRAY));
            }
            if (t.size() > 5) tooltip.add(Component.literal("...").withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.translatable("tooltip.songsandspeakers.edit_hint").withStyle(ChatFormatting.DARK_AQUA));
    }
}
