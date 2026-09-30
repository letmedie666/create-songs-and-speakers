package net.songsandspeakers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/** One song on a cassette. */
public record Track(String name, String url) {
    public static final int MAX_TRACKS = 64;
    public static final int MAX_NAME = 64;
    public static final int MAX_URL = 2048;

    public static final Codec<Track> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(Track::name),
            Codec.STRING.fieldOf("url").forGetter(Track::url)
    ).apply(i, Track::new));

    public static final Codec<List<Track>> LIST_CODEC = CODEC.listOf();

    public static final StreamCodec<ByteBuf, Track> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_NAME * 4), Track::name,
            ByteBufCodecs.stringUtf8(MAX_URL), Track::url,
            Track::new);

    public static final StreamCodec<ByteBuf, List<Track>> LIST_STREAM_CODEC =
            STREAM_CODEC.apply(ByteBufCodecs.list(MAX_TRACKS));

    public String displayName() {
        if (!name.isBlank()) return name;
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int s = u.lastIndexOf('/');
        return s >= 0 && s < u.length() - 1 ? u.substring(s + 1) : url;
    }

    /** Several links can be pasted at once: split on spaces or where a new http(s):// begins. */
    public static List<String> splitLinks(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.trim().split("\\s+|(?=https?://)")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /** Trims, cuts to limits and drops tracks without a link. */
    public static List<Track> sanitize(List<Track> in) {
        List<Track> out = new ArrayList<>();
        for (Track t : in) {
            if (out.size() >= MAX_TRACKS) break;
            String url = t.url() == null ? "" : t.url().trim();
            if (url.isEmpty() || url.length() > MAX_URL) continue;
            String name = t.name() == null ? "" : t.name().trim();
            if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
            out.add(new Track(name, url));
        }
        return out;
    }
}
