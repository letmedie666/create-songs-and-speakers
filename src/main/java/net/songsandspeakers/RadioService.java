package net.songsandspeakers;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/** Server side of the radio: which blocks broadcast, who listens to what. */
public final class RadioService {
    public static final TicketController TICKETS =
            new TicketController(ResourceLocation.fromNamespaceAndPath(SongsAndSpeakers.MODID, "radio_station"));

    private static final Map<ServerLevel, Set<BlockPos>> STATIONS = new HashMap<>();
    private static final Map<UUID, Payloads.RadioState> LAST_SENT = new HashMap<>();
    private static final Map<UUID, BlockPos> LISTENING = new HashMap<>();

    private RadioService() {}

    public static void register(ServerLevel level, BlockPos pos) {
        STATIONS.computeIfAbsent(level, l -> new HashSet<>()).add(pos.immutable());
    }

    public static void unregister(ServerLevel level, BlockPos pos) {
        Set<BlockPos> set = STATIONS.get(level);
        if (set != null) set.remove(pos);
    }

    /** Keep the station's chunk loaded while it is on air, so the music does not stop when you walk away. */
    public static void updateChunkLoading(ServerLevel level, BlockPos pos, boolean onAir) {
        ChunkPos c = new ChunkPos(pos);
        TICKETS.forceChunk(level, pos, c.x, c.z, onAir && Config.stationChunkLoading(), true);
    }

    private static void send(ServerPlayer player, Payloads.RadioState state) {
        if (player.connection != null && player.connection.hasChannel(state)) PacketDistributor.sendToPlayer(player, state);
    }

    public static boolean isListening(ServerPlayer player, BlockPos pos) {
        return pos.equals(LISTENING.get(player.getUUID()));
    }

    public static void clear() {
        STATIONS.clear();
        LAST_SENT.clear();
        LISTENING.clear();
    }

    private static List<SpeakerBlockEntity> stations(ServerLevel level) {
        List<SpeakerBlockEntity> out = new ArrayList<>();
        Set<BlockPos> set = STATIONS.get(level);
        if (set == null) return out;
        for (BlockPos p : set) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof SpeakerBlockEntity be && be.hasAntenna()) out.add(be);
        }
        return out;
    }

    /** Called every 10 ticks. */
    public static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            RadioSettings radio = null;
            boolean hasRadio = false;
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.getItem() instanceof RadioItem || s.getItem() instanceof BoomboxItem) {
                    hasRadio = true;
                    RadioSettings rs = RadioItem.settings(s);
                    if (rs.on()) {
                        radio = rs;
                        break;
                    }
                }
            }
            UUID id = player.getUUID();
            if (!Config.radioEnabled()) hasRadio = false;
            if (!hasRadio) {
                LISTENING.remove(id);
                if (LAST_SENT.containsKey(id)) {
                    LAST_SENT.remove(id);
                    send(player, Payloads.RadioState.OFF);
                }
                continue;
            }
            ServerLevel level = player.serverLevel();
            List<SpeakerBlockEntity> all = stations(level);
            TreeSet<Integer> active = new TreeSet<>();
            for (SpeakerBlockEntity be : all) if (be.isPlaying()) active.add(be.getFrequency());

            Payloads.RadioState state = Payloads.RadioState.OFF;
            BlockPos listening = null;
            if (radio != null) {
                SpeakerBlockEntity best = null;
                double bestDist = Double.MAX_VALUE;
                for (SpeakerBlockEntity be : all) {
                    if (be.getFrequency() != radio.frequency() || !be.isPlaying()) continue;
                    double d = be.getBlockPos().distSqr(player.blockPosition());
                    if (d < bestDist) {
                        bestDist = d;
                        best = be;
                    }
                }
                if (best != null) {
                    listening = best.getBlockPos();
                    state = new Payloads.RadioState(true, best.getBlockPos(), best.getUrl(), best.getNonce(), best.getStartTick(),
                            radio.volume(), new ArrayList<>(active));
                } else {
                    state = new Payloads.RadioState(false, BlockPos.ZERO, "", 0, 0L, radio.volume(), new ArrayList<>(active));
                }
            } else {
                state = new Payloads.RadioState(false, BlockPos.ZERO, "", 0, 0L, 0f, new ArrayList<>(active));
            }
            if (listening != null) LISTENING.put(id, listening);
            else LISTENING.remove(id);
            if (!state.equals(LAST_SENT.get(id))) {
                LAST_SENT.put(id, state);
                send(player, state);
            }
        }
        LAST_SENT.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }
}
