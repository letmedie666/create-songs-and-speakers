package net.songsandspeakers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Settings stored on a portable radio. Frequency is in tenths of MHz (875 = 87.5 FM). */
public record RadioSettings(int frequency, boolean on, float volume) {
    public static final int MIN_FREQ = 875;
    public static final int MAX_FREQ = 1080;
    public static final RadioSettings DEFAULT = new RadioSettings(1000, false, 0.7f);

    public static final Codec<RadioSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("frequency").forGetter(RadioSettings::frequency),
            Codec.BOOL.fieldOf("on").forGetter(RadioSettings::on),
            Codec.FLOAT.fieldOf("volume").forGetter(RadioSettings::volume)
    ).apply(i, RadioSettings::new));

    public static final StreamCodec<ByteBuf, RadioSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RadioSettings::frequency,
            ByteBufCodecs.BOOL, RadioSettings::on,
            ByteBufCodecs.FLOAT, RadioSettings::volume,
            RadioSettings::new);

    public RadioSettings clean() {
        return new RadioSettings(clampFreq(frequency), on, Math.max(0f, Math.min(1f, volume)));
    }

    public static int clampFreq(int f) {
        return Math.max(MIN_FREQ, Math.min(MAX_FREQ, f));
    }

    public static String format(int f) {
        return (f / 10) + "." + (f % 10);
    }
}
