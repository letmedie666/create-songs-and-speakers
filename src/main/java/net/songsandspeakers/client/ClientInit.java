package net.songsandspeakers.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.songsandspeakers.CassetteItem;
import net.songsandspeakers.SpeakerBlock;
import net.songsandspeakers.SpeakerBlockEntity;

public final class ClientInit {
    private ClientInit() {}

    public static void init(net.neoforged.fml.ModContainer container) {
        container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                net.neoforged.neoforge.client.gui.ConfigurationScreen::new);
        SpeakerBlock.openScreen = pos -> {
            var mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.getBlockEntity(pos) instanceof SpeakerBlockEntity be) {
                mc.setScreen(new SpeakerScreen(be));
            }
        };
        CassetteItem.openEditor = (hand, stack) -> Minecraft.getInstance().setScreen(new CassetteScreen(hand, stack));
        SpeakerBlockEntity.clientSync = SpeakerPlayback::sync;
        SpeakerBlockEntity.clientRemoved = be -> SpeakerPlayback.stop(be.getBlockPos());
        net.songsandspeakers.RadioItem.openScreen = (hand, stack) -> Minecraft.getInstance().setScreen(new RadioScreen(hand, stack));
        net.songsandspeakers.Payloads.RadioState.clientHandler = RadioPlayback::apply;
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RegisterClientCommandsEvent e) ->
                e.getDispatcher().register(net.minecraft.commands.Commands.literal("songsdebug").executes(ctx -> {
                    var mc = Minecraft.getInstance();
                    var src = ctx.getSource();
                    float master = mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER);
                    float records = mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.RECORDS);
                    src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(String.format(
                            "[Songs & Speakers] master volume %.0f%%, Jukebox/Note Blocks volume %.0f%% (this mod plays through that slider)",
                            master * 100, records * 100)), false);
                    var lines = SpeakerPlayback.debugLines();
                    if (lines.isEmpty()) src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("No music block is playing on this client."), false);
                    for (String l : lines) src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(l), false);
                    src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(RadioPlayback.debugLine()), false);
                    return 1;
                })));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            SpeakerPlayback.tick();
            RadioPlayback.tick();
        });
    }
}
