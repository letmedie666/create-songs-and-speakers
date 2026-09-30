package net.songsandspeakers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.function.Consumer;

public final class Payloads {
    private Payloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SongsAndSpeakers.MODID, name));
    }

    /** Buttons in the speaker screen. */
    public record SpeakerAction(BlockPos pos, int action, String url, int index, float volume) implements CustomPacketPayload {
        public static final int PLAY_URL = 0, STOP = 1, VOLUME = 2, PLAY_TRACK = 3, NEXT = 4, PREV = 5, EJECT = 6,
                CYCLE_REPEAT = 7, SET_FREQ = 8, REMOVE_ANTENNA = 9, PLAY = 10, PAUSE = 11, SET_LINK = 12;
        public static final Type<SpeakerAction> TYPE = id("speaker_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerAction> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SpeakerAction::pos,
                ByteBufCodecs.VAR_INT, SpeakerAction::action,
                ByteBufCodecs.stringUtf8(Track.MAX_URL), SpeakerAction::url,
                ByteBufCodecs.VAR_INT, SpeakerAction::index,
                ByteBufCodecs.FLOAT, SpeakerAction::volume,
                SpeakerAction::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(SpeakerAction msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) return;
                var level = player.level();
                if (!level.isLoaded(msg.pos()) || player.distanceToSqr(msg.pos().getCenter()) > 64.0) return;
                if (level.getBlockEntity(msg.pos()) instanceof SpeakerBlockEntity be) be.handleAction(msg, player);
            });
        }
    }

    /** A client tells the server that the current song ended (or could not be loaded). */
    public record TrackEnded(BlockPos pos, int nonce, boolean failed) implements CustomPacketPayload {
        public static final Type<TrackEnded> TYPE = id("track_ended");
        public static final StreamCodec<RegistryFriendlyByteBuf, TrackEnded> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TrackEnded::pos,
                ByteBufCodecs.VAR_INT, TrackEnded::nonce,
                ByteBufCodecs.BOOL, TrackEnded::failed,
                TrackEnded::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(TrackEnded msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) return;
                var level = player.level();
                if (!level.isLoaded(msg.pos())) return;
                boolean near = player.distanceToSqr(msg.pos().getCenter()) <= 160.0 * 160.0;
                if (!near && !RadioService.isListening(player, msg.pos())) return;
                if (level.getBlockEntity(msg.pos()) instanceof SpeakerBlockEntity be) be.onTrackEnded(msg.nonce(), msg.failed());
            });
        }
    }

    /** Save the playlist (and title) written in the cassette editor. */
    public record EditCassette(boolean offhand, String title, List<Track> tracks) implements CustomPacketPayload {
        public static final int MAX_TITLE = 50;
        public static final Type<EditCassette> TYPE = id("edit_cassette");
        public static final StreamCodec<RegistryFriendlyByteBuf, EditCassette> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, EditCassette::offhand,
                ByteBufCodecs.stringUtf8(MAX_TITLE * 4), EditCassette::title,
                Track.LIST_STREAM_CODEC, EditCassette::tracks,
                EditCassette::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        /** Writes the playlist and title onto a cassette. */
        public static void apply(ItemStack stack, String title, List<Track> tracks) {
            List<Track> clean = Track.sanitize(tracks);
            if (clean.isEmpty()) stack.remove(SongsAndSpeakers.TRACKS.get());
            else stack.set(SongsAndSpeakers.TRACKS.get(), clean);
            String t = title == null ? "" : title.trim();
            if (t.length() > MAX_TITLE) t = t.substring(0, MAX_TITLE);
            if (t.isEmpty()) stack.remove(DataComponents.CUSTOM_NAME);
            else stack.set(DataComponents.CUSTOM_NAME, Component.literal(t));
        }

        public static void handle(EditCassette msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) return;
                ItemStack stack = player.getItemInHand(msg.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
                if (stack.getItem() instanceof CassetteItem) apply(stack, msg.title(), msg.tracks());
            });
        }
    }

    /** Server -> client: what the player's radio should play. */
    public record RadioState(boolean playing, BlockPos pos, String url, int nonce, long startTick, float volume,
                             List<Integer> activeFrequencies) implements CustomPacketPayload {
        public static final RadioState OFF = new RadioState(false, BlockPos.ZERO, "", 0, 0L, 0f, List.of());
        public static final Type<RadioState> TYPE = id("radio_state");
        private static final StreamCodec<io.netty.buffer.ByteBuf, List<Integer>> FREQS =
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(256));
        public static final StreamCodec<RegistryFriendlyByteBuf, RadioState> STREAM_CODEC = StreamCodec.of(
                (buf, m) -> {
                    ByteBufCodecs.BOOL.encode(buf, m.playing());
                    BlockPos.STREAM_CODEC.encode(buf, m.pos());
                    ByteBufCodecs.stringUtf8(Track.MAX_URL).encode(buf, m.url());
                    ByteBufCodecs.VAR_INT.encode(buf, m.nonce());
                    ByteBufCodecs.VAR_LONG.encode(buf, m.startTick());
                    ByteBufCodecs.FLOAT.encode(buf, m.volume());
                    FREQS.encode(buf, m.activeFrequencies());
                },
                buf -> new RadioState(
                        ByteBufCodecs.BOOL.decode(buf),
                        BlockPos.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.stringUtf8(Track.MAX_URL).decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf),
                        ByteBufCodecs.FLOAT.decode(buf),
                        FREQS.decode(buf)));

        /** Set on the client. */
        public static Consumer<RadioState> clientHandler = null;

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(RadioState msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (clientHandler != null) clientHandler.accept(msg);
            });
        }
    }

    /** Save the portable radio settings. */
    public record EditRadio(boolean offhand, RadioSettings settings) implements CustomPacketPayload {
        public static final Type<EditRadio> TYPE = id("edit_radio");
        public static final StreamCodec<RegistryFriendlyByteBuf, EditRadio> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, EditRadio::offhand,
                RadioSettings.STREAM_CODEC, EditRadio::settings,
                EditRadio::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(EditRadio msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) return;
                ItemStack stack = player.getItemInHand(msg.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
                if (stack.getItem() instanceof RadioItem || stack.getItem() instanceof BoomboxItem) {
                    stack.set(SongsAndSpeakers.RADIO.get(), msg.settings().clean());
                }
            });
        }
    }
}
