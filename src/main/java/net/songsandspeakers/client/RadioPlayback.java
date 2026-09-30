package net.songsandspeakers.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.songsandspeakers.Payloads;

import java.util.List;

/** The one song the player's portable radio is playing. */
public final class RadioPlayback {
    private static Payloads.RadioState current = Payloads.RadioState.OFF;
    private static StreamSoundInstance sound;
    private static boolean reported;

    private RadioPlayback() {}

    public static List<Integer> activeFrequencies() {
        return current.activeFrequencies();
    }

    public static boolean hasSignal() {
        return current.playing() && sound != null && !sound.hasFailed();
    }

    public static String nowPlayingUrl() {
        return current.playing() ? current.url() : "";
    }

    public static String debugLine() {
        if (sound == null) return "radio: no station signal (nothing tuned / no station on this frequency)";
        return sound.describe() + " | sound engine active=" + Minecraft.getInstance().getSoundManager().isActive(sound);
    }

    public static void apply(Payloads.RadioState state) {
        Minecraft mc = Minecraft.getInstance();
        boolean same = sound != null && current.playing() && state.playing()
                && current.url().equals(state.url()) && current.nonce() == state.nonce() && current.pos().equals(state.pos());
        current = state;
        if (same) {
            sound.setGain(state.volume());
            return;
        }
        stopSound();
        if (state.playing() && !state.url().isEmpty()) {
            sound = new StreamSoundInstance(state.pos(), state.url(), state.volume(),
                    SpeakerPlayback.secondsSince(state.startTick()), true);
            reported = false;
            mc.getSoundManager().play(sound);
            SpeakerPlayback.warnIfMuted();
        }
    }

    private static void stopSound() {
        if (sound != null) {
            sound.kill();
            Minecraft.getInstance().getSoundManager().stop(sound);
            sound = null;
        }
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            stopSound();
            current = Payloads.RadioState.OFF;
            return;
        }
        if (sound == null || reported || sound.isKilled()) return;
        if (sound.hasFailed()) {
            reported = true;
            PacketDistributor.sendToServer(new Payloads.TrackEnded(current.pos(), current.nonce(), true));
        } else if (sound.hasFinished() && !mc.getSoundManager().isActive(sound)) {
            reported = true;
            PacketDistributor.sendToServer(new Payloads.TrackEnded(current.pos(), current.nonce(), false));
        }
    }
}
