package net.songsandspeakers;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * The boombox as an item: still an amplifier when placed, and also a portable radio.
 * While it is switched on it plays the station it is tuned to from anywhere in your inventory,
 * exactly like the Portable Radio.
 * <ul>
 *   <li>right-click in the air: switch on / off</li>
 *   <li>sneak + right-click: tuner (frequency, volume)</li>
 *   <li>right-click on a block: place it</li>
 * </ul>
 */
public class BoomboxItem extends BlockItem {
    public BoomboxItem(Block block, Properties props) {
        super(block, props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player != null && player.isSecondaryUseActive()) {
            return InteractionResult.PASS; // falls through to use(): sneaking opens the tuner
        }
        return super.useOn(ctx);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) {
            if (level.isClientSide && RadioItem.openScreen != null) RadioItem.openScreen.accept(hand, stack);
        } else if (!level.isClientSide) {
            RadioSettings s = RadioItem.settings(stack);
            boolean on = !s.on();
            stack.set(SongsAndSpeakers.RADIO.get(), new RadioSettings(s.frequency(), on, s.volume()));
            level.playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.6f, on ? 1.2f : 0.8f);
            player.displayClientMessage(Component.translatable(on ? "message.songsandspeakers.boombox_radio_on"
                    : "message.songsandspeakers.boombox_radio_off", RadioSettings.format(s.frequency())), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return RadioItem.settings(stack).on();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.songsandspeakers.boombox", Amplify.RANGE_PER_BOOMBOX, Amplify.SEARCH_RADIUS)
                .withStyle(ChatFormatting.GRAY));
        RadioSettings s = RadioItem.settings(stack);
        tooltip.add(Component.translatable(s.on() ? "tooltip.songsandspeakers.radio_on" : "tooltip.songsandspeakers.radio_off",
                RadioSettings.format(s.frequency())).withStyle(s.on() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.songsandspeakers.boombox_carry").withStyle(ChatFormatting.DARK_AQUA));
    }
}
