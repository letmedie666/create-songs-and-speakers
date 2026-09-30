package net.songsandspeakers.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.songsandspeakers.Amplify;
import net.songsandspeakers.SongsAndSpeakers;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** A song streamed from a link. Distance fading is done by hand so the range can change while it plays. */
public class StreamSoundInstance extends AbstractTickableSoundInstance {
    private final String url;
    private final BlockPos source;
    private final boolean radio;          // true = portable radio: no position, same volume everywhere
    private final double skipSeconds;     // where in the song to start (shared position)
    private float loudness;               // volume slider, 0..1
    private int range = Amplify.BASE_RANGE;
    private int ticks;
    private volatile boolean killed;
    private volatile boolean finished;
    private volatile Component error;     // set when the link could not be played
    private volatile String state = "starting";
    private volatile long bytesDelivered;
    private volatile int sampleRate;
    private final long createdNanos = System.nanoTime();

    public StreamSoundInstance(BlockPos pos, String url, float gain, double skipSeconds, boolean radio) {
        super(SongsAndSpeakers.STREAM_SOUND.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.url = url;
        this.source = pos.immutable();
        this.radio = radio;
        this.skipSeconds = Math.max(0, skipSeconds);
        this.looping = false;
        this.attenuation = Attenuation.NONE;
        if (radio) {
            this.relative = true;
            this.x = 0;
            this.y = 0;
            this.z = 0;
        } else {
            this.relative = false;
            this.x = pos.getX() + 0.5;
            this.y = pos.getY() + 0.5;
            this.z = pos.getZ() + 0.5;
        }
        this.loudness = clamp(gain);
        updateRange();
        updateVolume();
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    public void setGain(float gain) {
        this.loudness = clamp(gain);
        updateVolume();
    }

    public int getRange() { return range; }
    public BlockPos getSourcePos() { return source; }
    public String getUrl() { return url; }
    public boolean isKilled() { return killed; }
    public boolean hasFinished() { return finished; }
    public boolean hasFailed() { return error != null; }
    public Component getError() { return error; }

    /** One line for /songsdebug: what this sound is doing right now. */
    public String describe() {
        double secs = sampleRate > 0 ? bytesDelivered / 2.0 / sampleRate : 0;
        String err = error == null ? "" : " error=" + error.getString();
        return String.format("%s: %s | %s | audio handed to the game: %.1fs | volume=%.2f range=%d | killed=%s finished=%s%s",
                radio ? "radio" : "block " + source.toShortString(), state, url, secs, volume, range, killed, finished, err);
    }

    /** Stop for good, even if the stream is still being downloaded. */
    public void kill() {
        killed = true;
        stop();
    }

    private void updateRange() {
        if (radio) return;
        var level = Minecraft.getInstance().level;
        if (level != null) range = Amplify.range(Amplify.countBoomboxes(level, source));
    }

    /** Full volume in the first quarter of the range, then fades to silence at the edge. */
    private void updateVolume() {
        if (killed) {
            this.volume = 0f;
            return;
        }
        if (radio) {
            // stay quiet next to the station itself, so the same song is not heard twice
            this.volume = SpeakerPlayback.isPlayingAt(source) ? 0f : loudness;
            return;
        }
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        double d = cam.isInitialized() ? Math.sqrt(cam.getPosition().distanceToSqr(x, y, z)) : 0;
        float fade = (float) Math.max(0, Math.min(1, (range - d) / (range * 0.75)));
        this.volume = loudness * fade;
    }

    @Override
    public void tick() {
        if (killed) {
            stop(); // the sound engine checks isStopped() every tick
            return;
        }
        if (++ticks % 20 == 0) updateRange();
        updateVolume();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            if (killed) throw new CompletionException(new IOException("stopped"));
            try {
                SongsAndSpeakers.LOGGER.info("[songsandspeakers] opening {} (starting {}s into the song)", url, String.format("%.1f", skipSeconds));
                state = "opening the link";
                AudioStream opened = UrlAudio.open(url, skipSeconds);
                sampleRate = (int) opened.getFormat().getSampleRate();
                state = "streaming";
                SongsAndSpeakers.LOGGER.info("[songsandspeakers] opened {}: {} Hz, {} ch, ready after {} ms", url, sampleRate,
                        opened.getFormat().getChannels(), (System.nanoTime() - createdNanos) / 1_000_000L);
                return (AudioStream) new EndTrackingStream(opened);
            } catch (AudioLinkException e) {
                state = "FAILED to open";
                if (!killed) {
                    error = e.describe();
                    SongsAndSpeakers.LOGGER.warn("[songsandspeakers] can't play {}: {}", url, e.getMessage());
                }
                throw new CompletionException(e);
            } catch (RuntimeException e) {
                state = "FAILED to open";
                if (!killed) {
                    error = new AudioLinkException("not_audio").describe();
                    SongsAndSpeakers.LOGGER.warn("[songsandspeakers] can't play {}", url, e);
                }
                throw new CompletionException(e);
            }
        }, Util.nonCriticalIoPool());
    }

    /** Marks the song as finished when the decoder runs out of data. */
    private final class EndTrackingStream implements AudioStream {
        private final AudioStream inner;

        EndTrackingStream(AudioStream inner) {
            this.inner = inner;
        }

        @Override
        public AudioFormat getFormat() {
            return inner.getFormat();
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            if (killed) {
                finished = true;
                return BufferUtils.createByteBuffer(0);
            }
            ByteBuffer b = inner.read(size);
            if (b == null || !b.hasRemaining()) {
                // an empty buffer means "the song is over"; if it is over because the connection died, say so
                if (!killed && error == null && inner instanceof UrlAudio.PrefetchStream ps && ps.failure() != null) {
                    error = Component.translatable("error.songsandspeakers.connection_lost");
                    SongsAndSpeakers.LOGGER.warn("[songsandspeakers] {} ended early: {}", url, ps.failure().toString());
                }
                finished = true;
                return BufferUtils.createByteBuffer(0);
            }
            if (bytesDelivered == 0) SongsAndSpeakers.LOGGER.info("[songsandspeakers] first audio handed to the game for {}", url);
            bytesDelivered += b.remaining();
            return b;
        }

        @Override
        public void close() throws IOException {
            finished = true;
            if (state.equals("streaming")) state = "stream closed";
            inner.close();
        }
    }
}
