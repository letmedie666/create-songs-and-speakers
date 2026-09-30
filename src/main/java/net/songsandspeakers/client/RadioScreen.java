package net.songsandspeakers.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.songsandspeakers.Payloads;
import net.songsandspeakers.RadioItem;
import net.songsandspeakers.RadioSettings;

import java.util.List;

/** Tuner for the portable radio. */
public class RadioScreen extends Screen {
    private final InteractionHand hand;
    private int freq;
    private boolean on;
    private float volume;
    private Button power;

    public RadioScreen(InteractionHand hand, ItemStack stack) {
        super(stack.getHoverName());
        this.hand = hand;
        RadioSettings s = RadioItem.settings(stack);
        this.freq = s.frequency();
        this.on = s.on();
        this.volume = s.volume();
    }

    private void save() {
        PacketDistributor.sendToServer(new Payloads.EditRadio(hand == InteractionHand.OFF_HAND, new RadioSettings(freq, on, volume)));
    }

    private void tune(int delta) {
        freq = RadioSettings.clampFreq(freq + delta);
        save();
    }

    private void seek(int dir) {
        List<Integer> active = RadioPlayback.activeFrequencies();
        if (active.isEmpty()) return;
        Integer target = null;
        if (dir > 0) {
            for (int f : active) if (f > freq) { target = f; break; }
            if (target == null) target = active.get(0);
        } else {
            for (int i = active.size() - 1; i >= 0; i--) if (active.get(i) < freq) { target = active.get(i); break; }
            if (target == null) target = active.get(active.size() - 1);
        }
        freq = target;
        save();
    }

    @Override
    protected void init() {
        int w = 260;
        int left = (width - w) / 2;
        int y = height / 2 - 30;
        int bw = (w - 5 * 4) / 6;
        String[] labels = {"\u25C0\u25C0", "-1", "-0.1", "+0.1", "+1", "\u25B6\u25B6"};
        for (int i = 0; i < 6; i++) {
            final int k = i;
            Button b = Button.builder(Component.literal(labels[i]), btn -> {
                switch (k) {
                    case 0 -> seek(-1);
                    case 1 -> tune(-10);
                    case 2 -> tune(-1);
                    case 3 -> tune(1);
                    case 4 -> tune(10);
                    default -> seek(1);
                }
            }).bounds(left + i * (bw + 4), y, bw, 20).build();
            if (k == 0 || k == 5) b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("screen.songsandspeakers.seek")));
            addRenderableWidget(b);
        }
        power = addRenderableWidget(Button.builder(powerLabel(), b -> {
            on = !on;
            b.setMessage(powerLabel());
            save();
        }).bounds(left, y + 24, 80, 20).build());
        addRenderableWidget(new AbstractSliderButton(left + 84, y + 24, w - 84, 20, Component.empty(), volume) {
            { updateMessage(); }

            @Override
            protected void updateMessage() {
                setMessage(Component.translatable("screen.songsandspeakers.volume", Math.round(value * 100)));
            }

            @Override
            protected void applyValue() {
                volume = (float) value;
            }

            @Override
            public void onRelease(double mouseX, double mouseY) {
                super.onRelease(mouseX, mouseY);
                save();
            }
        });
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20).build());
    }

    private Component powerLabel() {
        return Component.translatable(on ? "screen.songsandspeakers.radio_on" : "screen.songsandspeakers.radio_off");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int y = height / 2 - 30;
        g.drawCenteredString(font, title, width / 2, y - 44, 0xFFFFFF);
        g.pose().pushPose();
        g.pose().translate(width / 2f, y - 28, 0);
        g.pose().scale(2f, 2f, 1f);
        String f = "FM " + RadioSettings.format(freq);
        g.drawString(font, f, -font.width(f) / 2, 0, on ? 0x55FF55 : 0x888888);
        g.pose().popPose();
        Component status;
        if (!on) status = Component.translatable("screen.songsandspeakers.radio_status_off");
        else if (RadioPlayback.hasSignal()) status = Component.translatable("screen.songsandspeakers.radio_status_playing");
        else status = Component.translatable("screen.songsandspeakers.radio_status_nosignal");
        g.drawCenteredString(font, status, width / 2, y + 70, 0xA0A0A0);
        String now = RadioPlayback.nowPlayingUrl();
        if (on && RadioPlayback.hasSignal() && !now.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("screen.songsandspeakers.now_playing",
                    new net.songsandspeakers.Track("", now).displayName()), width / 2, y + 56, 0xFFFFFF);
        }
        int n = RadioPlayback.activeFrequencies().size();
        g.drawCenteredString(font, Component.translatable("screen.songsandspeakers.radio_stations", n), width / 2, y + 82, 0x808080);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
