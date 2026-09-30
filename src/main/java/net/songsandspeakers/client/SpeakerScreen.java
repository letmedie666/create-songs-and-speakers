package net.songsandspeakers.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import net.songsandspeakers.Amplify;
import net.songsandspeakers.Payloads.SpeakerAction;
import net.songsandspeakers.RadioSettings;
import net.songsandspeakers.SpeakerBlock;
import net.songsandspeakers.SpeakerBlockEntity;
import net.songsandspeakers.Track;

import java.util.ArrayList;
import java.util.List;

/** Screen of the Music Manager (link) and the Cassette Deck (cassette). */
public class SpeakerScreen extends Screen {
    private static final int ROW_H = 22;

    private final SpeakerBlockEntity be;
    private final boolean deck;
    private String linkText;
    private float volume;
    private int scroll;
    private int shownTracksHash;
    private boolean shownAntenna;
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<Integer> rowIndex = new ArrayList<>();
    private Button repeatButton;
    private EditBox linkBox;
    private Component rangeText;
    private int left, w, listTop, rows, stationY;

    public SpeakerScreen(SpeakerBlockEntity be) {
        super(be.getBlockState().getBlock().getName());
        this.be = be;
        this.deck = be.kind() == SpeakerBlock.Kind.CASSETTE;
        this.linkText = be.getLinkUrl();
        this.volume = be.getVolume();
    }

    private void send(int action, String url, int index) {
        PacketDistributor.sendToServer(new SpeakerAction(be.getBlockPos(), action, url == null ? "" : url, index, volume));
    }

    @Override
    protected void init() {
        rowButtons.clear();
        rowIndex.clear();
        w = Math.min(330, width - 20);
        left = (width - w) / 2;
        shownAntenna = be.hasAntenna();
        if (deck) initDeck();
        else initManager();
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20).build());
    }

    // ---------- Music Manager ----------

    private void initManager() {
        int bw = 56;
        linkBox = new EditBox(font, left, 30, w - 2 * (bw + 4), 20, Component.translatable("screen.songsandspeakers.url"));
        linkBox.setMaxLength(Track.MAX_URL);
        linkBox.setValue(linkText);
        linkBox.setHint(Component.translatable("screen.songsandspeakers.url_hint"));
        linkBox.setResponder(s -> linkText = s);
        addRenderableWidget(linkBox);
        setInitialFocus(linkBox);

        addRenderableWidget(Button.builder(Component.translatable("screen.songsandspeakers.paste"), b -> {
            String clip = minecraft == null ? "" : minecraft.keyboardHandler.getClipboard().trim();
            if (!clip.isEmpty()) linkBox.setValue(clip);
        }).bounds(left + w - 2 * bw - 4, 30, bw, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.songsandspeakers.paste_tip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.songsandspeakers.clear"), b -> {
            linkBox.setValue("");
            send(SpeakerAction.SET_LINK, "", 0);
        }).bounds(left + w - bw, 30, bw, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.songsandspeakers.clear_tip"))).build());

        int y = 56;
        int b4 = (w - 3 * 4) / 4;
        addRenderableWidget(Button.builder(Component.translatable("screen.songsandspeakers.play"),
                b -> send(SpeakerAction.PLAY, linkText, 0)).bounds(left, y, b4, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.songsandspeakers.pause"),
                b -> send(SpeakerAction.PAUSE, "", 0)).bounds(left + (b4 + 4), y, b4, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.songsandspeakers.stop"),
                b -> send(SpeakerAction.STOP, "", 0)).bounds(left + 2 * (b4 + 4), y, b4, 20).build());
        repeatButton = addRenderableWidget(Button.builder(repeatLabel(), b -> send(SpeakerAction.CYCLE_REPEAT, "", 0))
                .bounds(left + 3 * (b4 + 4), y, w - 3 * (b4 + 4), 20)
                .tooltip(Tooltip.create(Component.translatable("screen.songsandspeakers.repeat_tip_link"))).build());
        addVolume(left, 80, w);
        addStationRow(104);
    }

    // ---------- Cassette Deck ----------

    private void initDeck() {
        List<Track> tracks = be.getTracks();
        shownTracksHash = tracksHash();
        listTop = 20;
        rows = Math.max(2, (height - listTop - 12 - 124) / ROW_H);
        if (!be.getCassette().isEmpty()) {
            scroll = Math.max(0, Math.min(scroll, tracks.size() - rows));
            for (int i = 0; i < rows && scroll + i < tracks.size(); i++) {
                int idx = scroll + i;
                Button b = Button.builder(Component.empty(), btn -> send(SpeakerAction.PLAY_TRACK, "", idx))
                        .bounds(left, listTop + 12 + i * ROW_H, w, 20).build();
                rowButtons.add(b);
                rowIndex.add(idx);
                addRenderableWidget(b);
            }
            updateRowLabels();
        }
        int y = listTop + 12 + rows * ROW_H + 2;
        boolean has = !tracks.isEmpty();
        int b5 = (w - 4 * 4) / 5;
        String[] keys = {"prev", "play", "pause", "stop", "next"};
        int[] actions = {SpeakerAction.PREV, SpeakerAction.PLAY, SpeakerAction.PAUSE, SpeakerAction.STOP, SpeakerAction.NEXT};
        for (int i = 0; i < 5; i++) {
            final int action = actions[i];
            Button b = addRenderableWidget(Button.builder(Component.translatable("screen.songsandspeakers." + keys[i]),
                    btn -> send(action, "", 0)).bounds(left + i * (b5 + 4), y, i == 4 ? w - 4 * (b5 + 4) : b5, 20).build());
            b.active = has || action == SpeakerAction.STOP || action == SpeakerAction.PAUSE;
        }
        y += 24;
        int rw = 92, ew = 70;
        repeatButton = addRenderableWidget(Button.builder(repeatLabel(), b -> send(SpeakerAction.CYCLE_REPEAT, "", 0))
                .bounds(left, y, rw, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.songsandspeakers.repeat_tip_deck"))).build());
        Button eject = addRenderableWidget(Button.builder(Component.translatable("screen.songsandspeakers.eject"),
                b -> send(SpeakerAction.EJECT, "", 0)).bounds(left + rw + 4, y, ew, 20).build());
        eject.active = !be.getCassette().isEmpty();
        addVolume(left + rw + ew + 8, y, w - rw - ew - 8);
        addStationRow(y + 24);
    }

    // ---------- shared parts ----------

    private void addVolume(int x, int y, int width) {
        addRenderableWidget(new AbstractSliderButton(x, y, width, 20, Component.empty(), volume) {
            {
                updateMessage();
            }

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
                send(SpeakerAction.VOLUME, "", 0);
            }
        });
    }

    private void addStationRow(int y) {
        stationY = y;
        if (!be.hasAntenna()) return;
        int x = left;
        int[] deltas = {-10, -1, 1, 10};
        String[] labels = {"-1", "-0.1", "+0.1", "+1"};
        for (int i = 0; i < 4; i++) {
            final int d = deltas[i];
            if (i == 2) x += 80; // room for the frequency text
            addRenderableWidget(Button.builder(Component.literal(labels[i]),
                    b -> send(SpeakerAction.SET_FREQ, "", be.getFrequency() + d)).bounds(x, y, 30, 20).build());
            x += 34;
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.songsandspeakers.remove_antenna"),
                b -> send(SpeakerAction.REMOVE_ANTENNA, "", 0)).bounds(x, y, left + w - x, 20).build());
    }

    private Component repeatLabel() {
        int m = be.getRepeatMode();
        String key = m == SpeakerBlockEntity.REPEAT_OFF ? "screen.songsandspeakers.repeat_off"
                : (!deck || m == SpeakerBlockEntity.REPEAT_ONE) ? "screen.songsandspeakers.repeat_one"
                : "screen.songsandspeakers.repeat_all";
        return Component.translatable(key);
    }

    private int tracksHash() {
        return be.getCassette().isEmpty() ? 0 : 31 * be.getTracks().hashCode() + 1;
    }

    private void updateRowLabels() {
        List<Track> tracks = be.getTracks();
        for (int i = 0; i < rowButtons.size(); i++) {
            int idx = rowIndex.get(i);
            if (idx >= tracks.size()) continue;
            boolean current = be.isFromCassette() && idx == be.getTrackIndex() && (be.isPlaying() || be.isPaused());
            String prefix = current ? (be.isPlaying() ? "\u25B6 " : "\u2759\u2759 ") : "";
            rowButtons.get(i).setMessage(Component.literal(prefix + (idx + 1) + ". " + tracks.get(idx).displayName()));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (be.isRemoved()) {
            onClose();
            return;
        }
        if (be.hasAntenna() != shownAntenna || deck && tracksHash() != shownTracksHash) {
            rebuildWidgets();
            return;
        }
        updateRowLabels();
        if (repeatButton != null) repeatButton.setMessage(repeatLabel());
    }

    @Override
    public void onClose() {
        // remember the link typed into the Music Manager, even if it was not played
        if (!deck && !linkText.trim().equals(be.getLinkUrl())) send(SpeakerAction.SET_LINK, linkText, 0);
        super.onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int size = be.getTracks().size();
        if (deck && size > rows) {
            int old = scroll;
            scroll = Math.max(0, Math.min(size - rows, scroll - (int) Math.signum(scrollY)));
            if (old != scroll) rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);

        if (!deck) {
            g.drawString(font, Component.translatable("screen.songsandspeakers.link_label"), left, 20, 0xA0A0A0);
            String now = be.nowPlayingName();
            if ((be.isPlaying() || be.isPaused()) && !now.isEmpty()) {
                g.drawString(font, Component.translatable("screen.songsandspeakers.now_playing", now), left, stationY + 28, 0xFFFFFF);
            }
        } else if (be.getCassette().isEmpty()) {
            g.drawString(font, Component.translatable("screen.songsandspeakers.no_cassette"), left, listTop, 0xA0A0A0);
        } else {
            List<Track> tracks = be.getTracks();
            Component head = tracks.isEmpty()
                    ? Component.translatable("screen.songsandspeakers.cassette_empty", be.getCassette().getHoverName())
                    : Component.translatable("screen.songsandspeakers.cassette", be.getCassette().getHoverName(), tracks.size());
            g.drawString(font, head, left, listTop, 0xFFFFFF);
            if (tracks.size() > rows) {
                String more = (scroll + 1) + "-" + Math.min(tracks.size(), scroll + rows) + " / " + tracks.size();
                g.drawString(font, more, left + w - font.width(more), listTop, 0xA0A0A0);
            }
        }

        if (be.hasAntenna()) {
            String f = "FM " + RadioSettings.format(be.getFrequency());
            g.drawCenteredString(font, f, left + 2 * 34 + 40, stationY + 6, be.isPlaying() ? 0x55FF55 : 0xFFFFFF);
        } else {
            g.drawString(font, Component.translatable("screen.songsandspeakers.no_antenna"), left, stationY + 6, 0x808080);
        }

        Component status;
        int color;
        if (be.isPlaying()) {
            status = Component.translatable(be.hasAntenna() ? "screen.songsandspeakers.status_on_air" : "screen.songsandspeakers.status_playing");
            color = 0x55FF55;
        } else if (be.isPaused()) {
            status = Component.translatable("screen.songsandspeakers.status_paused");
            color = 0xFFFF55;
        } else {
            status = Component.translatable("screen.songsandspeakers.status_stopped");
            color = 0xAAAAAA;
        }
        g.drawCenteredString(font, status, width / 2, height - 50, color);
        if (minecraft != null && minecraft.level != null && (rangeText == null || minecraft.level.getGameTime() % 20 == 0)) {
            int n = Amplify.countBoomboxes(minecraft.level, be.getBlockPos());
            rangeText = Component.translatable("screen.songsandspeakers.range", Amplify.range(n), n);
        }
        if (rangeText != null) g.drawCenteredString(font, rangeText, width / 2, height - 38, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
