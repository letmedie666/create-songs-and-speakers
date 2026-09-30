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

/** Portable radio: hear a radio station anywhere in the same dimension while it is in your inventory. */
public class RadioItem extends Item {
    public static BiConsumer<InteractionHand, ItemStack> openScreen = null;

    public RadioItem(Properties props) {
        super(props);
    }

    public static RadioSettings settings(ItemStack stack) {
        return stack.getOrDefault(SongsAndSpeakers.RADIO.get(), RadioSettings.DEFAULT);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide && openScreen != null) openScreen.accept(hand, stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return settings(stack).on();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        RadioSettings s = settings(stack);
        tooltip.add(Component.translatable(s.on() ? "tooltip.songsandspeakers.radio_on" : "tooltip.songsandspeakers.radio_off",
                RadioSettings.format(s.frequency())).withStyle(s.on() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.songsandspeakers.radio_hint").withStyle(ChatFormatting.DARK_AQUA));
    }
}
