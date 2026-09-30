package net.songsandspeakers.client;

import com.mojang.logging.LogUtils;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.songsandspeakers.LinkFixer;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Opens an mp3 / ogg from a direct http(s) link as a mono stream (mono = positional sound in OpenAL),
 * starting {@code skipSeconds} into the song.
 */
public final class UrlAudio {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private UrlAudio() {}

    /** Result of following redirects: the open connection and the final url. */
    private record Conn(HttpURLConnection con, String url) {}

    /** Connects to {@code link}, following redirects (also http <-> https). {@code from > 0} asks for a byte range. */
    private static Conn connect(String link, long from) throws IOException, AudioLinkException {
        try {
            return connect(link, from, false);
        } catch (AudioLinkException e) {
            // a few servers choke on unusual request headers: try once more with the bare minimum
            if (from == 0 && "http".equals(e.key()) && e.firstArg() instanceof Integer code && (code == 400 || code == 403 || code == 406 || code == 415)) {
                return connect(link, from, true);
            }
            throw e;
        }
    }

    private static Conn connect(String link, long from, boolean plain) throws IOException, AudioLinkException {
        String u = link;
        for (int i = 0; ; i++) {
            if (i >= 6) throw new AudioLinkException("http", "too many redirects");
            HttpURLConnection con = (HttpURLConnection) URI.create(u).toURL().openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Minecraft; songsandspeakers)");
            if (!plain) {
                con.setRequestProperty("Accept", "audio/*, application/ogg, */*;q=0.8");
                con.setRequestProperty("Accept-Encoding", "identity"); // a byte position only makes sense without gzip
            }
            if (from > 0) con.setRequestProperty("Range", "bytes=" + from + "-");
            int code = con.getResponseCode();
            String loc = con.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null) {
                u = URI.create(u).resolve(loc).toString();
                con.disconnect();
                if (LinkFixer.isYoutube(u)) throw new AudioLinkException("youtube");
                continue;
            }
            if (code >= 400) {
                con.disconnect();
                throw new AudioLinkException("http", code);
            }
            return new Conn(con, u);
        }
    }

    /**
     * The http body as a plain stream that survives dropped connections: when the server closes early or the
     * socket dies, it reconnects with a Range header at the exact byte where it stopped, so the decoder on top
     * never notices. (A server that cuts the connection after a while was one cause of songs stopping midway.)
     */
    static final class HttpSource extends InputStream {
        private static final int MAX_RECONNECTS = 5;
        private static final long MAX_SKIP_ON_NO_RANGE = 96L << 20;

        private String url;
        private HttpURLConnection con;
        private InputStream in;
        private final long total;      // -1 if the server did not say
        private long pos;
        private int failures;
        private volatile boolean closed;

        HttpSource(Conn first) throws IOException {
            this.url = first.url();
            attach(first.con());
            this.total = first.con().getContentLengthLong();
        }

        private void attach(HttpURLConnection c) throws IOException {
            this.con = c;
            this.in = c.getInputStream();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (true) {
                if (closed) throw new IOException("closed");
                try {
                    int n = in.read(b, off, len);
                    if (n > 0) {
                        pos += n;
                        failures = 0;
                        return n;
                    }
                    if (n < 0 && total >= 0 && pos < total) throw new EOFException("connection closed early at " + pos + "/" + total);
                    return n;
                } catch (IOException e) {
                    if (closed) throw e;
                    if (++failures > MAX_RECONNECTS) {
                        // the server promised more bytes than it has: that is the end of the file, not an error
                        if (e instanceof EOFException) return -1;
                        throw e;
                    }
                    LOGGER.info("[songsandspeakers] stream interrupted at byte {} ({}), reconnecting ({}/{})",
                            pos, e.getClass().getSimpleName(), failures, MAX_RECONNECTS);
                    reconnect();
                }
            }
        }

        private void reconnect() throws IOException {
            try {
                Thread.sleep(300L * failures);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            try {
                con.disconnect();
            } catch (Exception ignored) {
            }
            try {
                Conn c = connect(url, pos);
                url = c.url();
                boolean ranged = c.con().getResponseCode() == 206;
                attach(c.con());
                if (!ranged && pos > 0) { // server ignores Range: read and drop what we already consumed
                    if (pos > MAX_SKIP_ON_NO_RANGE) throw new IOException("server does not support resuming");
                    long left = pos;
                    byte[] trash = new byte[16384];
                    while (left > 0) {
                        int n = in.read(trash, 0, (int) Math.min(trash.length, left));
                        if (n < 0) throw new EOFException("short file on reconnect");
                        left -= n;
                    }
                }
            } catch (AudioLinkException e) {
                if (e.firstArg() instanceof Integer code && code == 416) throw new EOFException("server has no more bytes");
                throw new IOException(e.getMessage(), e);
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            try {
                in.close();
            } finally {
                con.disconnect();
            }
        }
    }

    public static AudioStream open(String link, double skipSeconds) throws AudioLinkException {
        String u = LinkFixer.fix(link);
        if (!LinkFixer.isHttp(u)) throw new AudioLinkException("not_http");
        if (LinkFixer.isYoutube(u)) throw new AudioLinkException("youtube");
        try {
            Conn conn = connect(u, 0);
            HttpURLConnection con = conn.con();
            String type = con.getContentType() == null ? "" : con.getContentType().toLowerCase(Locale.ROOT);
            if (type.startsWith("text/html")) {
                con.disconnect();
                throw new AudioLinkException("page");
            }

            BufferedInputStream in = new BufferedInputStream(new HttpSource(conn), 65536);
            in.mark(128);
            byte[] head = in.readNBytes(64);
            in.reset();
            String ascii = new String(head, StandardCharsets.ISO_8859_1);
            String lower = ascii.toLowerCase(Locale.ROOT);

            try {
                if (ascii.startsWith("OggS")) {
                    if (ascii.contains("OpusHead")) throw new AudioLinkException("format", "Opus");
                    try {
                        return new PrefetchStream(new MonoStream(new JOrbisAudioStream(in), skipSeconds));
                    } catch (IOException e) {
                        throw new AudioLinkException("not_audio");
                    }
                }
                if (ascii.startsWith("fLaC")) throw new AudioLinkException("format", "FLAC");
                if (ascii.startsWith("RIFF")) throw new AudioLinkException("format", "WAV");
                if (ascii.length() >= 8 && ascii.startsWith("ftyp", 4)) throw new AudioLinkException("format", "M4A / MP4");
                if (lower.trim().startsWith("<!doctype") || lower.trim().startsWith("<html")) throw new AudioLinkException("page");
                try {
                    return new PrefetchStream(new Mp3Stream(in, skipSeconds));
                } catch (AudioLinkException e) {
                    throw e;
                } catch (Exception e) {
                    throw new AudioLinkException("not_audio");
                }
            } catch (AudioLinkException | RuntimeException e) {
                try {
                    in.close(); // do not leak the connection when the link turns out to be unusable
                } catch (IOException ignored) {
                }
                throw e;
            }
        } catch (AudioLinkException e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new AudioLinkException("network", "unknown host");
        } catch (SocketTimeoutException | ConnectException e) {
            throw new AudioLinkException("network", "timeout");
        } catch (IllegalArgumentException e) {
            throw new AudioLinkException("not_http");
        } catch (IOException e) {
            throw new AudioLinkException("network", e.getClass().getSimpleName());
        }
    }

    /** Decodes mp3 with JLayer into 16-bit mono PCM. */
    static final class Mp3Stream implements AudioStream {
        private final InputStream raw;
        private final Bitstream bitstream;
        private final Decoder decoder = new Decoder();
        private final AudioFormat format;
        private short[] pending;
        private int pendingPos;
        private boolean eof;

        Mp3Stream(InputStream in, double skipSeconds) throws Exception {
            this.raw = in;
            this.bitstream = new Bitstream(in);
            Header h = bitstream.readFrame();
            if (h == null) throw new AudioLinkException("not_audio");
            this.format = new AudioFormat(h.frequency(), 16, 1, true, false);
            // skip frames without decoding them (fast) to reach the shared song position
            double skipped = 0;
            while (h != null && skipped + h.ms_per_frame() / 1000.0 <= skipSeconds) {
                skipped += h.ms_per_frame() / 1000.0;
                bitstream.closeFrame();
                h = bitstream.readFrame();
            }
            if (h == null) {
                eof = true;
                pending = new short[0];
            } else {
                pending = decode(h);
            }
        }

        private short[] decode(Header h) throws Exception {
            SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            bitstream.closeFrame();
            short[] buf = sb.getBuffer();
            int ch = Math.max(1, sb.getChannelCount());
            int len = sb.getBufferLength();
            short[] mono = new short[len / ch];
            for (int i = 0, o = 0; o < mono.length; i += ch, o++) {
                int sum = 0;
                for (int c = 0; c < ch; c++) sum += buf[i + c];
                mono[o] = (short) (sum / ch);
            }
            return mono;
        }

        @Override
        public AudioFormat getFormat() { return format; }

        /** More broken frames in a row than this means the file really is damaged: stop. */
        private static final int MAX_BAD_FRAMES = 400;
        private int badFrames;

        @Override
        public ByteBuffer read(int size) throws IOException {
            int samples = Math.max(1, size / 2);
            ByteBuffer out = BufferUtils.createByteBuffer(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
            while (out.remaining() >= 2 && !eof) {
                if (pending == null || pendingPos >= pending.length) {
                    Header h;
                    try {
                        h = bitstream.readFrame();
                    } catch (BitstreamException e) {
                        // A glitch in the file (lost sync, junk tag in the middle...) must not end the song:
                        // drop the broken bit and look for the next frame. A dead connection cannot be fixed.
                        IOException io = ioCause(e);
                        if (io != null) throw io;
                        if (++badFrames > MAX_BAD_FRAMES) {
                            eof = true;
                            break;
                        }
                        closeQuietly();
                        continue;
                    }
                    if (h == null) {
                        eof = true;
                        break;
                    }
                    try {
                        pending = decode(h);
                        badFrames = 0;
                    } catch (Exception bad) { // skip a broken frame instead of stopping the song
                        closeQuietly();
                        pending = null;
                        if (++badFrames > MAX_BAD_FRAMES) {
                            eof = true;
                            break;
                        }
                    }
                    pendingPos = 0;
                    continue;
                }
                out.putShort(pending[pendingPos++]);
            }
            out.flip();
            return out;
        }

        private static IOException ioCause(Throwable t) {
            for (int i = 0; t != null && i < 8; i++, t = t.getCause()) {
                if (t instanceof IOException io) return io;
            }
            return null;
        }

        private void closeQuietly() {
            try {
                bitstream.closeFrame();
            } catch (Exception ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            try {
                bitstream.close();
            } catch (Exception ignored) {
            }
            raw.close();
        }
    }

    /** Down-mixes a 16-bit stream to mono (so it is heard positionally) and skips to the shared position. */
    static final class MonoStream implements AudioStream {
        private final AudioStream inner;
        private final int channels;
        private final AudioFormat format;
        private long skipFrames;

        MonoStream(AudioStream inner, double skipSeconds) {
            this.inner = inner;
            AudioFormat f = inner.getFormat();
            this.channels = Math.max(1, f.getChannels());
            this.format = new AudioFormat(f.getSampleRate(), 16, 1, true, false);
            this.skipFrames = (long) Math.max(0, skipSeconds * f.getSampleRate());
        }

        @Override
        public AudioFormat getFormat() { return format; }

        @Override
        public ByteBuffer read(int size) throws IOException {
            while (true) {
                ByteBuffer src = inner.read(Math.max(2, size) * channels);
                if (src == null || !src.hasRemaining()) return BufferUtils.createByteBuffer(0);
                src.order(ByteOrder.LITTLE_ENDIAN);
                int frames = src.remaining() / (2 * channels);
                if (skipFrames > 0) {
                    if (frames <= skipFrames) {
                        skipFrames -= frames;
                        continue;
                    }
                    src.position(src.position() + (int) skipFrames * 2 * channels);
                    frames -= (int) skipFrames;
                    skipFrames = 0;
                }
                ByteBuffer out = BufferUtils.createByteBuffer(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < frames; i++) {
                    int sum = 0;
                    for (int c = 0; c < channels; c++) sum += src.getShort();
                    out.putShort((short) (sum / channels));
                }
                out.flip();
                return out;
            }
        }

        @Override
        public void close() throws IOException {
            inner.close();
        }
    }

    /**
     * Reads the song ahead on its own thread. Minecraft asks for audio on the shared sound thread and only keeps
     * about 0.7 s queued: if the network is slow for a moment the queue runs dry, OpenAL stops the source and the
     * song is treated as finished (this is what cut songs off after ~30 s on servers that send a fast burst at
     * the start and then throttle). Now the sound thread never waits for the network: there are up to 20 s of
     * audio ready, and if even that runs out it gets silence instead of the song being killed.
     */
    static final class PrefetchStream implements AudioStream {
        private static final int CHUNK = 16384;
        private static final long PREROLL_WAIT_NANOS = 20_000_000_000L;
        private static final long STALL_LIMIT_NANOS = 30_000_000_000L;
        private static final long WAIT_FOR_DATA_NANOS = 1_500_000_000L;
        private static final int SILENCE_BYTES = 44100; // ~0.5 s of mono 16-bit

        private final AudioStream inner;
        private final AudioFormat format;
        private final long maxAhead;
        private final long preroll;
        private final Object lock = new Object();
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private int headOffset;
        private long queued;
        private boolean done;
        private boolean closed;
        private IOException failure;
        private long stalledSince = -1;

        PrefetchStream(AudioStream inner) {
            this.inner = inner;
            this.format = inner.getFormat();
            long bytesPerSecond = (long) (format.getSampleRate() * Math.max(2, format.getFrameSize()));
            this.maxAhead = bytesPerSecond * 20L;
            this.preroll = bytesPerSecond * 5L; // Minecraft queues 4 x 1 s at once when the sound starts
            Thread t = new Thread(this::pump, "songsandspeakers-stream");
            t.setDaemon(true);
            t.start();
            synchronized (lock) { // start playing only once a little is buffered, so it does not stutter right away
                long deadline = System.nanoTime() + PREROLL_WAIT_NANOS;
                try {
                    while (!done && !closed && queued < preroll) {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) break;
                        lock.wait(left / 1_000_000L + 1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void pump() {
            try {
                while (true) {
                    synchronized (lock) {
                        while (!closed && queued >= maxAhead) lock.wait();
                        if (closed) return;
                    }
                    ByteBuffer b = inner.read(CHUNK);
                    if (b == null || !b.hasRemaining()) break;
                    byte[] arr = new byte[b.remaining()];
                    b.get(arr);
                    synchronized (lock) {
                        if (closed) return;
                        chunks.add(arr);
                        queued += arr.length;
                        lock.notifyAll();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                synchronized (lock) {
                    if (!closed) failure = e instanceof IOException io ? io : new IOException(e);
                }
                if (!closed) LOGGER.warn("[songsandspeakers] stream stopped: {}", e.toString());
            } finally {
                synchronized (lock) {
                    done = true;
                    lock.notifyAll();
                }
            }
        }

        /** Why the song ended early, or null if it ended normally. */
        IOException failure() {
            synchronized (lock) {
                return failure;
            }
        }

        @Override
        public AudioFormat getFormat() { return format; }

        @Override
        public ByteBuffer read(int size) throws IOException {
            int want = Math.max(2, size) & ~1;
            byte[] out = new byte[want];
            int n = 0;
            boolean end = false;
            synchronized (lock) {
                // Wait (on the sound thread, but only briefly) for the download instead of inventing silence:
                // a short buffer is fine for Minecraft, zeros in the middle of the song are not.
                long deadline = System.nanoTime() + WAIT_FOR_DATA_NANOS;
                while (true) {
                    while (n < want && !chunks.isEmpty()) {
                        byte[] head = chunks.peekFirst();
                        int take = Math.min(want - n, head.length - headOffset);
                        System.arraycopy(head, headOffset, out, n, take);
                        n += take;
                        headOffset += take;
                        queued -= take;
                        if (headOffset >= head.length) {
                            chunks.pollFirst();
                            headOffset = 0;
                        }
                    }
                    lock.notifyAll(); // room for the reader thread
                    if (n >= want) break;
                    if (done && chunks.isEmpty()) {
                        end = true;
                        break;
                    }
                    if (n > 0) break; // got something: hand it over now, do not keep the source waiting
                    long left = deadline - System.nanoTime();
                    if (left <= 0) break;
                    try {
                        lock.wait(left / 1_000_000L + 1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (n > 0) {
                    stalledSince = -1;
                } else if (!end) { // really nothing for a while: a short pause of silence keeps the source alive
                    long now = System.nanoTime();
                    if (stalledSince < 0) {
                        stalledSince = now;
                    } else if (now - stalledSince > STALL_LIMIT_NANOS) {
                        failure = new IOException("no data for 30 seconds");
                        end = true;
                    }
                }
            }
            if (end && n == 0) return BufferUtils.createByteBuffer(0);
            int len = n > 0 ? n : Math.min(want, SILENCE_BYTES);
            ByteBuffer buf = BufferUtils.createByteBuffer(len).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(out, 0, len).flip();
            return buf;
        }

        @Override
        public void close() throws IOException {
            synchronized (lock) {
                closed = true;
                chunks.clear();
                queued = 0;
                lock.notifyAll();
            }
            inner.close();
        }
    }
}
