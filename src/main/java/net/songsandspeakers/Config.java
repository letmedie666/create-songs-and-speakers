package net.songsandspeakers;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server settings (saved per world in serverconfig/songsandspeakers-server.toml). */
public final class Config {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue RADIO_ENABLED;
    public static final ModConfigSpec.BooleanValue STATION_CHUNK_LOADING;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        RADIO_ENABLED = b.comment("Allow portable radios to receive radio stations.")
                .translation("songsandspeakers.configuration.radioEnabled")
                .define("radioEnabled", true);
        STATION_CHUNK_LOADING = b.comment("Keep the chunk of a radio station loaded while it is on air,",
                        "so the broadcast does not stop when everyone walks away.")
                .translation("songsandspeakers.configuration.stationChunkLoading")
                .define("stationChunkLoading", true);
        SPEC = b.build();
    }

    private Config() {}

    public static boolean radioEnabled() {
        return !SPEC.isLoaded() || RADIO_ENABLED.get();
    }

    public static boolean stationChunkLoading() {
        return !SPEC.isLoaded() || STATION_CHUNK_LOADING.get();
    }
}
