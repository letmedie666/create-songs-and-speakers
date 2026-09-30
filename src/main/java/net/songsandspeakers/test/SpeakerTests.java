package net.songsandspeakers.test;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.songsandspeakers.*;
import net.songsandspeakers.Payloads.SpeakerAction;

import java.util.List;

@GameTestHolder(SongsAndSpeakers.MODID)
@PrefixGameTestTemplate(false)
public class SpeakerTests {
    private static SpeakerBlockEntity place(GameTestHelper h, net.minecraft.world.level.block.Block block, BlockPos rel) {
        h.setBlock(rel, block);
        return (SpeakerBlockEntity) h.getBlockEntity(rel);
    }

    private static void act(SpeakerBlockEntity be, ServerPlayer p, int action, String url, int index) {
        be.handleAction(new SpeakerAction(be.getBlockPos(), action, url, index, 0.5f), p);
    }

    private static void check(GameTestHelper h, boolean ok, String msg) {
        if (!ok) h.fail(msg);
    }

    @GameTest(template = "empty")
    public static void managerRepeat(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        SpeakerBlockEntity be = place(h, SongsAndSpeakers.MUSIC_MANAGER.get(), new BlockPos(1, 1, 1));
        act(be, p, SpeakerAction.PLAY_URL, "http://example.com/a.mp3", 0);
        check(h, be.isPlaying(), "should play after PLAY_URL");
        check(h, be.getRepeatMode() == 0, "repeat must start off");
        be.onTrackEnded(be.getNonce(), false);
        check(h, !be.isPlaying(), "repeat OFF: must stop when the song ends");

        act(be, p, SpeakerAction.CYCLE_REPEAT, "", 0);
        check(h, be.getRepeatMode() == 1, "repeat should turn on");
        act(be, p, SpeakerAction.PLAY_URL, "http://example.com/a.mp3", 0);
        int n = be.getNonce();
        be.onTrackEnded(n, false);
        check(h, be.isPlaying() && be.getNonce() == n + 1, "repeat ON: must restart");
        be.onTrackEnded(n, false);
        check(h, be.getNonce() == n + 1, "old report must be ignored");

        act(be, p, SpeakerAction.CYCLE_REPEAT, "", 0);
        check(h, be.getRepeatMode() == 0, "manager repeat must go back to off (2 states)");
        be.onTrackEnded(be.getNonce(), false);
        check(h, !be.isPlaying(), "repeat turned OFF while playing: must stop at the end");

        act(be, p, SpeakerAction.PLAY_URL, "http://example.com/a.mp3", 0);
        act(be, p, SpeakerAction.STOP, "", 0);
        check(h, !be.isPlaying(), "STOP must stop");
        check(h, !h.getBlockState(new BlockPos(1, 1, 1)).getValue(SpeakerBlock.POWER), "block must show off");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void deckPlaylist(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        SpeakerBlockEntity be = place(h, SongsAndSpeakers.CASSETTE_DECK.get(), new BlockPos(1, 1, 1));
        ItemStack tape = new ItemStack(SongsAndSpeakers.CASSETTE_TAPE.get());
        tape.set(SongsAndSpeakers.TRACKS.get(), List.of(new Track("a", "http://x/a.mp3"), new Track("b", "http://x/b.mp3"),
                new Track("c", "http://x/c.mp3")));
        be.insertCassette(tape);
        check(h, be.isPlaying() && be.getTrackIndex() == 0, "inserting a cassette starts track 1");
        be.onTrackEnded(be.getNonce(), false);
        check(h, be.getTrackIndex() == 1 && be.isPlaying(), "goes to track 2");
        be.onTrackEnded(be.getNonce(), false);
        check(h, be.getTrackIndex() == 2 && be.isPlaying(), "goes to track 3");
        be.onTrackEnded(be.getNonce(), false);
        check(h, !be.isPlaying(), "repeat OFF: stops after the last track");

        act(be, p, SpeakerAction.CYCLE_REPEAT, "", 0); // 1 = this track
        act(be, p, SpeakerAction.PLAY_TRACK, "", 1);
        be.onTrackEnded(be.getNonce(), false);
        check(h, be.getTrackIndex() == 1 && be.isPlaying(), "repeat TRACK: same track again");

        act(be, p, SpeakerAction.CYCLE_REPEAT, "", 0); // 2 = all
        act(be, p, SpeakerAction.PLAY_TRACK, "", 2);
        be.onTrackEnded(be.getNonce(), false);
        check(h, be.getTrackIndex() == 0 && be.isPlaying(), "repeat ALL: back to track 1");

        act(be, p, SpeakerAction.CYCLE_REPEAT, "", 0); // back to 0
        check(h, be.getRepeatMode() == 0, "repeat cycles back to off");
        act(be, p, SpeakerAction.PLAY_TRACK, "", 2);
        be.onTrackEnded(be.getNonce(), false);
        check(h, !be.isPlaying(), "repeat OFF again: stops after the last track");

        act(be, p, SpeakerAction.CYCLE_REPEAT, "", 0);
        act(be, p, SpeakerAction.CYCLE_REPEAT, "", 0); // all
        act(be, p, SpeakerAction.PLAY_TRACK, "", 0);
        for (int i = 0; i < 3; i++) be.onTrackEnded(be.getNonce(), true);
        check(h, !be.isPlaying(), "all links broken: must give up, not loop forever");

        act(be, p, SpeakerAction.PLAY_URL, "http://x/z.mp3", 0);
        check(h, !be.getUrl().equals("http://x/z.mp3"), "deck must ignore links");
        act(be, p, SpeakerAction.EJECT, "", 0);
        check(h, be.getCassette().isEmpty(), "eject empties the deck");
        check(h, p.getInventory().hasAnyMatching(st -> st.is(SongsAndSpeakers.CASSETTE_TAPE.get()) && CassetteItem.tracks(st).size() == 3), "cassette with its tracks goes back to the player");
        h.succeed();
    }

    @GameTest(template = "empty", batch = "radio")
    public static void radioStation(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        SpeakerBlockEntity be = place(h, SongsAndSpeakers.MUSIC_MANAGER.get(), new BlockPos(1, 1, 1));
        check(h, be.installAntenna(), "antenna installs");
        check(h, !be.installAntenna(), "second antenna refused");
        act(be, p, SpeakerAction.SET_FREQ, "", 1013);
        check(h, be.getFrequency() == 1013, "frequency set");
        act(be, p, SpeakerAction.SET_FREQ, "", 5000);
        check(h, be.getFrequency() == RadioSettings.MAX_FREQ, "frequency clamped");
        act(be, p, SpeakerAction.SET_FREQ, "", 1013);
        act(be, p, SpeakerAction.PLAY_URL, "http://example.com/a.mp3", 0);

        var c = be.getBlockPos().getCenter();
        p.teleportTo(c.x + 2, c.y, c.z); // stand next to our station (other stations on the same frequency may exist)
        ItemStack radio = new ItemStack(SongsAndSpeakers.RADIO_ANTENNA.get());
        radio.set(SongsAndSpeakers.RADIO.get(), new RadioSettings(1013, true, 0.5f));
        p.getInventory().setItem(0, radio);
        radio = p.getInventory().getItem(0);
        RadioService.tick(h.getLevel().getServer());
        check(h, RadioService.isListening(p, be.getBlockPos()), "radio on the right frequency hears the nearest station");

        radio.set(SongsAndSpeakers.RADIO.get(), new RadioSettings(990, true, 0.5f));
        RadioService.tick(h.getLevel().getServer());
        check(h, !RadioService.isListening(p, be.getBlockPos()), "other frequency hears nothing");

        radio.set(SongsAndSpeakers.RADIO.get(), new RadioSettings(1013, true, 0.5f));
        act(be, p, SpeakerAction.STOP, "", 0);
        RadioService.tick(h.getLevel().getServer());
        check(h, !RadioService.isListening(p, be.getBlockPos()), "stopped station is off air");

        act(be, p, SpeakerAction.REMOVE_ANTENNA, "", 0);
        check(h, !be.hasAntenna(), "antenna removed");
        check(h, p.getInventory().contains(new ItemStack(SongsAndSpeakers.PARABOLIC_ANTENNA.get())), "antenna goes back to player");
        h.succeed();
    }

    @GameTest(template = "empty", batch = "radio")
    public static void boomboxRadio(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        SpeakerBlockEntity be = place(h, SongsAndSpeakers.MUSIC_MANAGER.get(), new BlockPos(1, 1, 1));
        be.installAntenna();
        act(be, p, SpeakerAction.SET_FREQ, "", 1021);
        act(be, p, SpeakerAction.PLAY_URL, "http://example.com/a.mp3", 0);
        var c = be.getBlockPos().getCenter();
        p.teleportTo(c.x + 2, c.y, c.z);

        // a fresh boombox is off; right-click in the air switches it on
        ItemStack box = new ItemStack(SongsAndSpeakers.BOOMBOX_ITEM.get());
        box.set(SongsAndSpeakers.RADIO.get(), new RadioSettings(1021, false, 0.5f));
        p.getInventory().setItem(20, box); // a slot in the bag, not a hand
        RadioService.tick(h.getLevel().getServer());
        check(h, !RadioService.isListening(p, be.getBlockPos()), "a switched-off boombox is silent");

        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, box.copy());
        ItemStack held = p.getMainHandItem();
        held.getItem().use(h.getLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
        check(h, RadioItem.settings(p.getMainHandItem()).on(), "right-click in the air switches the boombox on");
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.getInventory().setItem(20, held.copy());
        RadioService.tick(h.getLevel().getServer());
        check(h, RadioService.isListening(p, be.getBlockPos()), "a switched-on boombox in the inventory plays the station");

        ItemStack inBag = p.getInventory().getItem(20);
        inBag.set(SongsAndSpeakers.RADIO.get(), new RadioSettings(990, true, 0.5f));
        RadioService.tick(h.getLevel().getServer());
        check(h, !RadioService.isListening(p, be.getBlockPos()), "other frequency hears nothing");

        inBag.set(SongsAndSpeakers.RADIO.get(), new RadioSettings(1021, true, 0.5f));
        p.getInventory().setItem(20, ItemStack.EMPTY);
        RadioService.tick(h.getLevel().getServer());
        check(h, !RadioService.isListening(p, be.getBlockPos()), "without the boombox nothing is heard");
        check(h, SongsAndSpeakers.BOOMBOX_ITEM.get().getDefaultMaxStackSize() == 1, "boomboxes do not stack (each has its own tuning)");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void pauseResume(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        SpeakerBlockEntity be = place(h, SongsAndSpeakers.MUSIC_MANAGER.get(), new BlockPos(1, 1, 1));
        act(be, p, SpeakerAction.PLAY, "http://example.com/a.mp3", 0);
        check(h, be.isPlaying(), "PLAY with a link starts it");
        h.runAfterDelay(40, () -> {
            act(be, p, SpeakerAction.PAUSE, "", 0);
            check(h, !be.isPlaying() && be.isPaused(), "PAUSE pauses");
            long off = be.getPausedOffset();
            check(h, off >= 38 && off <= 42, "pause remembers the position, got " + off);
            int n = be.getNonce();
            h.runAfterDelay(30, () -> {
                act(be, p, SpeakerAction.PLAY, "http://example.com/a.mp3", 0);
                long now = h.getLevel().getGameTime();
                check(h, be.isPlaying() && !be.isPaused(), "PLAY resumes");
                check(h, now - be.getStartTick() == off, "resume continues from the same place");
                check(h, be.getNonce() == n + 1, "clients are told to restart at the new position");
                act(be, p, SpeakerAction.PAUSE, "", 0);
                act(be, p, SpeakerAction.PLAY, "http://example.com/b.mp3", 0);
                check(h, be.getUrl().equals("http://example.com/b.mp3") && h.getLevel().getGameTime() - be.getStartTick() == 0,
                        "PLAY with another link starts it from 0:00");
                act(be, p, SpeakerAction.STOP, "", 0);
                check(h, !be.isPlaying() && !be.isPaused(), "STOP stops and forgets the position");
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void repeatRestartsFromZero(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        SpeakerBlockEntity be = place(h, SongsAndSpeakers.MUSIC_MANAGER.get(), new BlockPos(1, 1, 1));
        act(be, p, SpeakerAction.CYCLE_REPEAT, "", 0);
        act(be, p, SpeakerAction.PLAY_URL, "http://example.com/a.mp3", 0);
        h.runAfterDelay(30, () -> {
            be.onTrackEnded(be.getNonce(), false);
            check(h, be.isPlaying() && be.getStartTick() == h.getLevel().getGameTime(),
                    "a repeated song starts again from 0:00, not from the old position");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void deckResume(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        SpeakerBlockEntity be = place(h, SongsAndSpeakers.CASSETTE_DECK.get(), new BlockPos(1, 1, 1));
        ItemStack tape = new ItemStack(SongsAndSpeakers.CASSETTE_TAPE.get());
        tape.set(SongsAndSpeakers.TRACKS.get(), List.of(new Track("a", "http://x/a.mp3"), new Track("b", "http://x/b.mp3")));
        be.insertCassette(tape);
        act(be, p, SpeakerAction.PLAY_TRACK, "", 1);
        act(be, p, SpeakerAction.PAUSE, "", 0);
        act(be, p, SpeakerAction.PLAY, "", 0);
        check(h, be.isPlaying() && be.getTrackIndex() == 1 && be.getUrl().equals("http://x/b.mp3"), "deck resumes the same track");
        check(h, be.comparatorSignal() == 2, "comparator shows track number 2");
        act(be, p, SpeakerAction.STOP, "", 0);
        act(be, p, SpeakerAction.PLAY, "", 0);
        check(h, be.isPlaying() && be.getTrackIndex() == 1, "play after stop starts the selected track");
        act(be, p, SpeakerAction.STOP, "", 0);
        check(h, be.comparatorSignal() == 0, "comparator is 0 when stopped");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void redstoneControl(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        BlockPos pos = new BlockPos(1, 1, 1);
        SpeakerBlockEntity be = place(h, SongsAndSpeakers.MUSIC_MANAGER.get(), pos);
        act(be, p, SpeakerAction.SET_LINK, "http://example.com/a.mp3", 0);
        check(h, !be.isPlaying(), "saving a link does not start it");
        h.setBlock(pos.east(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK);
        h.runAfterDelay(2, () -> {
            check(h, be.isPlaying(), "redstone signal starts the music");
            check(h, be.comparatorSignal() == 15, "comparator 15 while playing");
            h.setBlock(pos.east(), net.minecraft.world.level.block.Blocks.AIR);
            h.runAfterDelay(2, () -> {
                check(h, !be.isPlaying() && be.isPaused(), "signal off pauses");
                h.setBlock(pos.east(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK);
                h.runAfterDelay(2, () -> {
                    check(h, be.isPlaying(), "signal on again resumes");
                    h.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty")
    public static void links(GameTestHelper h) {
        check(h, LinkFixer.fix("https://drive.google.com/file/d/AbC_12-x/view?usp=sharing")
                .equals("https://drive.google.com/uc?export=download&id=AbC_12-x"), "google drive share link");
        check(h, LinkFixer.fix("https://drive.google.com/open?id=XyZ").equals("https://drive.google.com/uc?export=download&id=XyZ"),
                "google drive open link");
        check(h, LinkFixer.fix("https://www.dropbox.com/s/abc/song.mp3?dl=0").equals("https://www.dropbox.com/s/abc/song.mp3?dl=1"),
                "dropbox dl=0");
        check(h, LinkFixer.fix("https://www.dropbox.com/s/abc/song.mp3").equals("https://www.dropbox.com/s/abc/song.mp3?dl=1"),
                "dropbox without dl");
        check(h, LinkFixer.fix("https://github.com/u/r/blob/main/music/a.ogg").equals("https://raw.githubusercontent.com/u/r/main/music/a.ogg"),
                "github blob");
        check(h, LinkFixer.fix("  https://example.com/a.mp3  ").equals("https://example.com/a.mp3"), "plain link unchanged (trimmed)");
        check(h, LinkFixer.isYoutube("https://www.youtube.com/watch?v=x") && LinkFixer.isYoutube("https://youtu.be/x")
                && LinkFixer.isYoutube("https://music.youtube.com/watch?v=x"), "youtube detected");
        check(h, !LinkFixer.isYoutube("https://example.com/youtube.com.mp3"), "not youtube");
        check(h, !LinkFixer.isHttp("ftp://x/a.mp3") && LinkFixer.isHttp("HTTPS://x/a.mp3"), "http check");
        check(h, Track.splitLinks("http://a/1.mp3 http://a/2.mp3").size() == 2, "two links with a space");
        check(h, Track.splitLinks("http://a/1.mp3http://a/2.mp3").size() == 2, "two links glued together");
        check(h, Track.splitLinks("  http://a/1.mp3  ").size() == 1, "one link");
        check(h, Track.splitLinks("   ").isEmpty(), "empty");
        check(h, new Track("", "https://x.com/music/My%20Song.mp3?raw=1").displayName().equals("My%20Song.mp3"), "name from link");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void cassetteEditing(GameTestHelper h) {
        ItemStack tape = new ItemStack(SongsAndSpeakers.CASSETTE_TAPE.get());
        Payloads.EditCassette.apply(tape, "  My mix  ", List.of(new Track(" a ", " http://x/a.mp3 "), new Track("b", "  ")));
        check(h, tape.getHoverName().getString().equals("My mix"), "cassette gets the title");
        check(h, CassetteItem.tracks(tape).equals(List.of(new Track("a", "http://x/a.mp3"))), "tracks cleaned, empty links dropped");
        Payloads.EditCassette.apply(tape, "", List.of());
        check(h, !tape.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME), "empty title removes the name");
        check(h, CassetteItem.tracks(tape).isEmpty(), "empty playlist");
        h.succeed();
    }
}
