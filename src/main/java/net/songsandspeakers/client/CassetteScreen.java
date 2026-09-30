package net.songsandspeakers.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.songsandspeakers.CassetteItem;
import net.songsandspeakers.Payloads;
import net.songsandspeakers.Track;

import java.util.ArrayList;
import java.util.List;

/** Playlist editor for a cassette held in hand. */
public class CassetteScreen extends Screen {
    private static final int ROW_H = 22;

    private final InteractionHand hand;
    private final List<Track> tracks;
    private String titleText;
    private String nameText = "";
    private String urlText = "";
    private int editing = -1;        // index being edited, -1 = adding a new one
    private boolean confirmClear;
    private int scroll;
    private int left, w, rows, listTop;

    public CassetteScreen(InteractionHand hand, ItemStack stack) {
        super(Component.translatable("screen.songsandspeakers.cassette_editor"));
        this.hand = hand;
        this.tracks = new ArrayList<>(CassetteItem.tracks(stack));
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        this.titleText = custom == null ? "" : custom.getString();
    }

    @Override
    protected void init() {
        w = Math.min(360, width - 20);
        left = (width - w) / 2;

        EditBox title = new EditBox(font, left, 30, w, 20, Component.translatable("screen.songsandspeakers.cassette_title"));
        title.setMaxLength(Payloads.EditCassette.MAX_TITLE);
        title.setValue(titleText);
        title.setHint(Component.translatable("screen.songsandspeakers.cassette_title_hint"));
        title.setResponder(s -> titleText = s);
        addRenderableWidget(title);

        int y = 66;
        EditBox name = new EditBox(font, left, y, 90, 20, Component.translatable("screen.songsandspeakers.track_name"));
        name.setMaxLength(Track.MAX_NAME);
        name.setValue(nameText);
        name.setHint(Component.translatable("screen.songsandspeakers.track_name"));
        name.setResponder(s -> nameText = s);
        addRenderableWidget(name);

        int addW = 70;
        int urlW = w - 90 - 4 - 22 - 4 - addW - 4;
        EditBox url = new EditBox(font, left + 94, y, urlW, 20, Component.translatable("screen.songsandspeakers.url"));
        url.setMaxLength(Track.MAX_URL * 4);
        url.setValue(urlText);
        url.setHint(Component.translatable("screen.songsandspeakers.url_hint"));
        url.setResponder(s -> urlText = s);
        addRenderableWidget(url);

        addRenderableWidget(Button.builder(Component.literal("\u2716"), b -> {
            nameText = "";
            urlText = "";
            editing = -1;
            rebuildWidgets();
        }).bounds(left + 94 + urlW + 4, y, 22, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.songsandspeakers.clear_fields_tip"))).build());

        Button add = addRenderableWidget(Button.builder(
                Component.translatable(editing >= 0 ? "screen.songsandspeakers.save_track" : "screen.songsandspeakers.add"),
                b -> addOrSave()).bounds(left + w - addW, y, addW, 20).build());
        add.active = tracks.size() < Track.MAX_TRACKS || editing >= 0;

        listTop = 102;
        rows = Math.max(2, (height - listTop - 12 - 36) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, tracks.size() - rows));
        for (int i = 0; i < rows && scroll + i < tracks.size(); i++) {
            int idx = scroll + i;
            int ry = listTop + 12 + i * ROW_H;
            Track t = tracks.get(idx);
            String label = (idx == editing ? "\u270E " : "") + (idx + 1) + ". " + t.displayName();
            addRenderableWidget(Button.builder(Component.literal(label), b -> startEdit(idx))
                    .bounds(left, ry, w - 72, 20)
                    .tooltip(Tooltip.create(Component.literal(t.url()))).build());
            Button up = addRenderableWidget(Button.builder(Component.literal("\u25B2"), b -> move(idx, -1))
                    .bounds(left + w - 68, ry, 20, 20).build());
            up.active = idx > 0;
            Button down = addRenderableWidget(Button.builder(Component.literal("\u25BC"), b -> move(idx, 1))
                    .bounds(left + w - 46, ry, 20, 20).build());
            down.active = idx < tracks.size() - 1;
            addRenderableWidget(Button.builder(Component.literal("\u2716"), b -> remove(idx))
                    .bounds(left + w - 24, ry, 24, 20)
                    .tooltip(Tooltip.create(Component.translatable("screen.songsandspeakers.remove_track"))).build());
        }

        int by = height - 28;
        int bw = (w - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("screen.songsandspeakers.save"), b -> {
            PacketDistributor.sendToServer(new Payloads.EditCassette(hand == InteractionHand.OFF_HAND, titleText,
                    Track.sanitize(tracks)));
            onClose();
        }).bounds(left, by, bw, 20).build());
        Button clear = addRenderableWidget(Button.builder(Component.translatable(confirmClear
                ? "screen.songsandspeakers.clear_all_confirm" : "screen.songsandspeakers.clear_all"), b -> {
            if (confirmClear) {
                tracks.clear();
                editing = -1;
                confirmClear = false;
            } else {
                confirmClear = true;
            }
            rebuildWidgets();
        }).bounds(left + bw + 4, by, bw, 20).build());
        clear.active = !tracks.isEmpty();
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + 2 * (bw + 4), by, w - 2 * (bw + 4), 20).build());
    }

    private void addOrSave() {
        List<String> links = Track.splitLinks(urlText);
        if (links.isEmpty()) return;
        String name = nameText.trim();
        if (editing >= 0 && editing < tracks.size()) {
            tracks.set(editing, new Track(name, links.get(0)));
        } else {
            for (String link : links) {
                if (tracks.size() >= Track.MAX_TRACKS) break;
                tracks.add(new Track(links.size() == 1 ? name : "", link));
            }
            scroll = Math.max(0, tracks.size() - rows);
        }
        editing = -1;
        confirmClear = false;
        nameText = "";
        urlText = "";
        rebuildWidgets();
    }

    private void startEdit(int idx) {
        if (editing == idx) { // click again = cancel editing
            editing = -1;
            nameText = "";
            urlText = "";
        } else {
            editing = idx;
            nameText = tracks.get(idx).name();
            urlText = tracks.get(idx).url();
        }
        rebuildWidgets();
    }

    private void move(int idx, int dir) {
        int to = idx + dir;
        if (to < 0 || to >= tracks.size()) return;
        Track t = tracks.remove(idx);
        tracks.add(to, t);
        if (editing == idx) editing = to;
        else if (editing == to) editing = idx;
        rebuildWidgets();
    }

    private void remove(int idx) {
        tracks.remove(idx);
        if (editing == idx) {
            editing = -1;
            nameText = "";
            urlText = "";
        } else if (editing > idx) {
            editing--;
        }
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tracks.size() > rows) {
            int old = scroll;
            scroll = Math.max(0, Math.min(tracks.size() - rows, scroll - (int) Math.signum(scrollY)));
            if (old != scroll) rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        g.drawString(font, Component.translatable("screen.songsandspeakers.cassette_title"), left, 20, 0xA0A0A0);
        g.drawString(font, Component.translatable(editing >= 0 ? "screen.songsandspeakers.editing" : "screen.songsandspeakers.new_track"),
                left, 56, 0xA0A0A0);
        g.drawString(font, Component.translatable("screen.songsandspeakers.multi_hint"), left, 88, 0x707070);
        Component head = tracks.isEmpty()
                ? Component.translatable("screen.songsandspeakers.playlist_empty")
                : Component.translatable("screen.songsandspeakers.playlist", tracks.size(), Track.MAX_TRACKS);
        g.drawString(font, head, left, listTop, 0xFFFFFF);
        if (tracks.size() > rows) {
            String more = (scroll + 1) + "-" + Math.min(tracks.size(), scroll + rows) + " / " + tracks.size();
            g.drawString(font, more, left + w - font.width(more), listTop, 0xA0A0A0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
