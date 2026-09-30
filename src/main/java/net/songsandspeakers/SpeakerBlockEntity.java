package net.songsandspeakers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Consumer;

/**
 * State of a Music Manager or Cassette Deck. The server decides everything; clients only play what they are told.
 * The song position is shared through {@link #getStartTick()}: every client plays from (now - startTick).
 */
public class SpeakerBlockEntity extends BlockEntity {
    /** Client-side hooks, set by ClientInit. */
    public static Consumer<SpeakerBlockEntity> clientSync = null;
    public static Consumer<SpeakerBlockEntity> clientRemoved = null;

    public static final int REPEAT_OFF = 0, REPEAT_ONE = 1, REPEAT_ALL = 2;

    private String url = "";          // what is (or was last) playing
    private String linkUrl = "";      // link typed into the Music Manager
    private boolean playing = false;
    private boolean paused = false;
    private boolean fromCassette = false;
    private int repeatMode = REPEAT_OFF;
    private float volume = 0.5f;
    private int nonce = 0;            // changes on every (re)start, so clients know to restart
    private long startTick = 0;       // game time at which the song was at 0:00
    private long pausedOffset = 0;    // ticks into the song when paused
    private int trackIndex = 0;
    private int failStreak = 0;
    private boolean redstone = false; // last redstone input, to react on changes only
    private boolean antenna = false;
    private int frequency = -1;
    private ItemStack cassette = ItemStack.EMPTY;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(SongsAndSpeakers.SPEAKER_BE.get(), pos, state);
    }

    public String getUrl() { return url; }
    public String getLinkUrl() { return linkUrl; }
    public boolean isPlaying() { return playing; }
    public boolean isPaused() { return paused; }
    public boolean isFromCassette() { return fromCassette; }
    public int getRepeatMode() { return repeatMode; }
    public float getVolume() { return volume; }
    public int getNonce() { return nonce; }
    public long getStartTick() { return startTick; }
    public long getPausedOffset() { return pausedOffset; }
    public int getTrackIndex() { return trackIndex; }
    public ItemStack getCassette() { return cassette; }
    public boolean hasAntenna() { return antenna; }
    public List<Track> getTracks() { return cassette.isEmpty() ? List.of() : CassetteItem.tracks(cassette); }

    public int getFrequency() {
        if (frequency < 0) {
            frequency = RadioSettings.MIN_FREQ
                    + Math.floorMod(worldPosition.hashCode(), RadioSettings.MAX_FREQ - RadioSettings.MIN_FREQ + 1);
        }
        return frequency;
    }

    public SpeakerBlock.Kind kind() {
        return getBlockState().getBlock() instanceof SpeakerBlock sb ? sb.kind() : SpeakerBlock.Kind.LINK;
    }

    /** Name of what is playing now, for screens. */
    public String nowPlayingName() {
        if (fromCassette) {
            List<Track> t = getTracks();
            if (trackIndex >= 0 && trackIndex < t.size()) return t.get(trackIndex).displayName();
        }
        return url.isEmpty() ? "" : new Track("", url).displayName();
    }

    /** Comparator output: 15 while the Music Manager plays; the track number (1-15) while the deck plays. */
    public int comparatorSignal() {
        if (!playing) return 0;
        return kind() == SpeakerBlock.Kind.CASSETTE ? Math.min(15, trackIndex + 1) : 15;
    }

    private long now() {
        return level == null ? 0 : level.getGameTime();
    }

    // ---------------- server logic ----------------

    public void handleAction(Payloads.SpeakerAction a, ServerPlayer player) {
        boolean deck = kind() == SpeakerBlock.Kind.CASSETTE;
        switch (a.action()) {
            case Payloads.SpeakerAction.PLAY_TRACK, Payloads.SpeakerAction.NEXT, Payloads.SpeakerAction.PREV,
                 Payloads.SpeakerAction.EJECT -> { if (!deck) return; }
            case Payloads.SpeakerAction.PLAY_URL, Payloads.SpeakerAction.SET_LINK -> { if (deck) return; }
            default -> {}
        }
        switch (a.action()) {
            case Payloads.SpeakerAction.PLAY_URL -> playLink(a.url());
            case Payloads.SpeakerAction.SET_LINK -> linkUrl = clip(a.url());
            case Payloads.SpeakerAction.PLAY -> {
                if (deck) play();
                else playLink(a.url());
            }
            case Payloads.SpeakerAction.PAUSE -> pause();
            case Payloads.SpeakerAction.STOP -> stop();
            case Payloads.SpeakerAction.VOLUME -> volume = Math.max(0f, Math.min(1f, a.volume()));
            case Payloads.SpeakerAction.PLAY_TRACK -> playTrack(a.index());
            case Payloads.SpeakerAction.NEXT -> playTrack(trackIndex + 1);
            case Payloads.SpeakerAction.PREV -> playTrack(trackIndex - 1);
            case Payloads.SpeakerAction.CYCLE_REPEAT ->
                    repeatMode = deck ? (repeatMode + 1) % 3 : (repeatMode == REPEAT_OFF ? REPEAT_ONE : REPEAT_OFF);
            case Payloads.SpeakerAction.SET_FREQ -> {
                if (!antenna) return;
                frequency = RadioSettings.clampFreq(a.index());
            }
            case Payloads.SpeakerAction.REMOVE_ANTENNA -> {
                if (!antenna) return;
                antenna = false;
                give(player, new ItemStack(SongsAndSpeakers.PARABOLIC_ANTENNA.get()));
            }
            case Payloads.SpeakerAction.EJECT -> {
                if (cassette.isEmpty()) return;
                if (fromCassette) stop();
                ItemStack out = cassette;
                cassette = ItemStack.EMPTY;
                trackIndex = 0;
                give(player, out);
            }
            default -> { return; }
        }
        changed();
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    /** Music Manager: play a link, or resume it if it is the paused one. */
    private void playLink(String link) {
        String l = clip(link);
        linkUrl = l;
        if (paused && !fromCassette && l.equals(url)) resume();
        else start(l, false);
    }

    /** "Play" button / redstone: resume if paused, otherwise start what is selected. */
    public void play() {
        if (paused) {
            resume();
        } else if (kind() == SpeakerBlock.Kind.CASSETTE) {
            playTrack(trackIndex);
        } else if (!linkUrl.isEmpty()) {
            start(linkUrl, false);
        }
    }

    public void pause() {
        if (!playing) return;
        pausedOffset = Math.max(0, now() - startTick);
        playing = false;
        paused = true;
    }

    public void stop() {
        playing = false;
        paused = false;
        pausedOffset = 0;
    }

    private void resume() {
        if (url.isEmpty()) return;
        startTick = now() - pausedOffset;
        playing = true;
        paused = false;
        nonce++;
    }

    /** Puts a cassette in. Returns the cassette that was inside before (or EMPTY). */
    public ItemStack insertCassette(ItemStack stack) {
        ItemStack old = cassette;
        cassette = stack.copyWithCount(1);
        trackIndex = 0;
        if (fromCassette) stop();
        if (!getTracks().isEmpty()) playTrack(0);
        changed();
        return old;
    }

    public ItemStack takeCassetteForDrop() {
        ItemStack out = cassette;
        cassette = ItemStack.EMPTY;
        return out;
    }

    /** Returns false if an antenna is already installed. */
    public boolean installAntenna() {
        if (antenna) return false;
        antenna = true;
        getFrequency();
        changed();
        return true;
    }

    public boolean takeAntennaForDrop() {
        boolean had = antenna;
        antenna = false;
        return had;
    }

    private void playTrack(int index) {
        List<Track> tracks = getTracks();
        if (tracks.isEmpty()) return;
        trackIndex = Math.floorMod(index, tracks.size());
        start(tracks.get(trackIndex).url(), true);
    }

    private void start(String newUrl, boolean cassetteMode) {
        url = newUrl;
        fromCassette = cassetteMode;
        playing = !url.isEmpty();
        paused = false;
        pausedOffset = 0;
        startTick = now();
        nonce++;
    }

    public void onTrackEnded(int endedNonce, boolean failed) {
        if (!playing || endedNonce != nonce) return; // already handled by another player's report
        failStreak = failed ? failStreak + 1 : 0;
        if (fromCassette) {
            List<Track> tracks = getTracks();
            if (tracks.isEmpty() || failStreak >= tracks.size()) {
                stop();                                // every link is broken: give up
            } else if (repeatMode == REPEAT_ONE && !failed) {
                playTrack(trackIndex);                 // repeat this track
            } else if (trackIndex + 1 < tracks.size()) {
                playTrack(trackIndex + 1);             // next track
            } else if (repeatMode == REPEAT_ALL) {
                playTrack(0);                          // back to the first track
            } else {
                stop();                                // end of the cassette
                trackIndex = 0;
            }
        } else {
            if (repeatMode != REPEAT_OFF && !failed) start(url, false); // play the link again from 0:00
            else stop();
        }
        changed();
    }

    /** Redstone: signal on = play / resume, signal off = pause. */
    public void onRedstone(boolean powered) {
        if (powered == redstone) return;
        redstone = powered;
        if (powered) play();
        else pause();
        changed();
    }

    public void initRedstone(boolean powered) {
        redstone = powered;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            if (state.hasProperty(SpeakerBlock.POWER) && state.getValue(SpeakerBlock.POWER) != playing) {
                level.setBlock(worldPosition, state.setValue(SpeakerBlock.POWER, playing), 3);
            }
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            if (level instanceof ServerLevel sl) {
                if (antenna) RadioService.register(sl, worldPosition);
                else RadioService.unregister(sl, worldPosition);
                RadioService.updateChunkLoading(sl, worldPosition, antenna && playing);
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel sl && antenna) RadioService.register(sl, worldPosition);
    }

    private static String clip(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > Track.MAX_URL ? s.substring(0, Track.MAX_URL) : s;
    }

    // ---------------- saving / syncing ----------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("Url", url);
        tag.putString("LinkUrl", linkUrl);
        tag.putBoolean("Playing", playing);
        tag.putBoolean("Paused", paused);
        tag.putBoolean("FromCassette", fromCassette);
        tag.putInt("RepeatMode", repeatMode);
        tag.putFloat("Volume", volume);
        tag.putInt("Nonce", nonce);
        tag.putLong("StartTick", startTick);
        tag.putLong("PausedOffset", pausedOffset);
        tag.putInt("TrackIndex", trackIndex);
        tag.putBoolean("Redstone", redstone);
        tag.putBoolean("Antenna", antenna);
        tag.putInt("Frequency", getFrequency());
        if (!cassette.isEmpty()) tag.put("Cassette", cassette.save(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        url = tag.getString("Url");
        linkUrl = tag.getString("LinkUrl");
        playing = tag.getBoolean("Playing");
        paused = tag.getBoolean("Paused");
        fromCassette = tag.getBoolean("FromCassette");
        repeatMode = tag.contains("RepeatMode") ? Math.floorMod(tag.getInt("RepeatMode"), 3)
                : (tag.getBoolean("Repeat") ? REPEAT_ALL : REPEAT_OFF);
        volume = tag.contains("Volume") ? tag.getFloat("Volume") : 0.5f;
        nonce = tag.getInt("Nonce");
        startTick = tag.getLong("StartTick");
        pausedOffset = tag.getLong("PausedOffset");
        trackIndex = tag.getInt("TrackIndex");
        redstone = tag.getBoolean("Redstone");
        antenna = tag.getBoolean("Antenna");
        frequency = tag.contains("Frequency") ? RadioSettings.clampFreq(tag.getInt("Frequency")) : -1;
        cassette = tag.contains("Cassette")
                ? ItemStack.parse(registries, tag.getCompound("Cassette")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        super.onDataPacket(net, pkt, registries);
        notifyClient();
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        notifyClient();
    }

    private void notifyClient() {
        if (level != null && level.isClientSide && clientSync != null) clientSync.accept(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel sl) RadioService.unregister(sl, worldPosition);
        if (level != null && level.isClientSide && clientRemoved != null) clientRemoved.accept(this);
    }
}
