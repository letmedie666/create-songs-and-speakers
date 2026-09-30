package net.songsandspeakers.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import net.songsandspeakers.Payloads;
import net.songsandspeakers.SpeakerBlockEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps track of which block is playing what on this client.
 * Everything runs on the client main thread and happens immediately, so a sound can never be
 * "forgotten" while it keeps playing.
 */
public final class SpeakerPlayback {
    /** Joining less than this many seconds late starts from 0:00 (keeps the intro for the one who pressed Play). */
    static final double MIN_SKIP_SECONDS = 1.0;

    private static final class Active {
        final StreamSoundInstance sound;
        final String url;
        final int nonce;
        boolean reported;

        Active(StreamSoundInstance sound, String url, int nonce) {
            this.sound = sound;
            this.url = url;
            this.nonce = nonce;
        }
    }

    private static final Map<BlockPos, Active> ACTIVE = new HashMap<>();

    private SpeakerPlayback() {}

    static double secondsSince(long startTick) {
        var level = Minecraft.getInstance().level;
        if (level == null) return 0;
        double s = (level.getGameTime() - startTick) / 20.0;
        return s < MIN_SKIP_SECONDS ? 0 : s;
    }

    public static void sync(SpeakerBlockEntity be) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) syncNow(be);
        else mc.execute(() -> syncNow(be));
    }

    private static void syncNow(SpeakerBlockEntity be) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = be.getBlockPos().immutable();
        if (!be.isPlaying() || be.getUrl().isEmpty()) {
            stopNow(pos);
            return;
        }
        Active cur = ACTIVE.get(pos);
        if (cur != null && cur.url.equals(be.getUrl()) && cur.nonce == be.getNonce()) {
            cur.sound.setGain(be.getVolume()); // same song, maybe new volume
            return;
        }
        stopNow(pos);
        StreamSoundInstance s = new StreamSoundInstance(pos, be.getUrl(), be.getVolume(), secondsSince(be.getStartTick()), false);
        ACTIVE.put(pos, new Active(s, be.getUrl(), be.getNonce()));
        mc.getSoundManager().play(s);
        warnIfMuted();
    }

    /** Everything plays through the "Jukebox/Note Blocks" slider: tell the player when it (or the master volume) is 0. */
    static void warnIfMuted() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float master = mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER);
        float records = mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.RECORDS);
        if (master <= 0f || records <= 0f) {
            mc.player.displayClientMessage(Component.translatable("message.songsandspeakers.muted").withStyle(net.minecraft.ChatFormatting.YELLOW), false);
        }
    }

    /** True if this client hears the block itself (positional sound) right now. */
    public static boolean isPlayingAt(BlockPos pos) {
        Active a = ACTIVE.get(pos);
        if (a == null || a.sound.isKilled() || a.reported) return false;
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        return cam.isInitialized() && cam.getPosition().distanceTo(pos.getCenter()) < a.sound.getRange();
    }

    public static void stop(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos p = pos.immutable();
        if (mc.isSameThread()) stopNow(p);
        else mc.execute(() -> stopNow(p));
    }

    private static void stopNow(BlockPos pos) {
        Active a = ACTIVE.remove(pos);
        if (a != null) {
            a.sound.kill(); // also stops it if the stream is still loading
            Minecraft.getInstance().getSoundManager().stop(a.sound);
        }
    }

    /** Lines for /songsdebug. */
    public static java.util.List<String> debugLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var e : ACTIVE.entrySet()) {
            var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
            double d = cam.isInitialized() ? cam.getPosition().distanceTo(e.getKey().getCenter()) : -1;
            boolean active = Minecraft.getInstance().getSoundManager().isActive(e.getValue().sound);
            out.add(e.getValue().sound.describe() + String.format(" | distance=%.1f | sound engine active=%s", d, active));
        }
        return out;
    }

    static void showError(Component error, String url) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String name = new net.songsandspeakers.Track("", url).displayName();
        if (name.length() > 40) name = name.substring(0, 37) + "...";
        mc.player.displayClientMessage(Component.translatable("error.songsandspeakers.prefix", name, error)
                .withStyle(net.minecraft.ChatFormatting.RED), false);
    }

    /** Called every client tick: notices finished or broken songs and tells the server. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!ACTIVE.isEmpty()) {
                for (Active a : ACTIVE.values()) a.sound.kill();
                ACTIVE.clear();
            }
            return;
        }
        for (var e : ACTIVE.entrySet()) {
            Active a = e.getValue();
            if (a.reported || a.sound.isKilled()) continue;
            if (a.sound.hasFailed()) {
                a.reported = true;
                var cam = mc.gameRenderer.getMainCamera();
                if (cam.isInitialized() && cam.getPosition().distanceTo(e.getKey().getCenter()) < a.sound.getRange()) {
                    showError(a.sound.getError(), a.url);
                }
                PacketDistributor.sendToServer(new Payloads.TrackEnded(e.getKey(), a.nonce, true));
            } else if (a.sound.hasFinished() && !mc.getSoundManager().isActive(a.sound)) {
                a.reported = true;
                PacketDistributor.sendToServer(new Payloads.TrackEnded(e.getKey(), a.nonce, false));
            }
        }
    }
}
