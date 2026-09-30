package net.songsandspeakers;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;

@Mod(SongsAndSpeakers.MODID)
public class SongsAndSpeakers {
    public static final String MODID = "songsandspeakers";
    public static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    private static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5f).sound(SoundType.METAL).noOcclusion();
    }

    public static final DeferredBlock<BoomboxBlock> BOOMBOX = BLOCKS.register("boombox",
            () -> new BoomboxBlock(props()));
    public static final DeferredBlock<SpeakerBlock> CASSETTE_DECK = BLOCKS.register("cassette_deck",
            () -> new SpeakerBlock(props(), Block.box(0, 0, 0, 16, 16, 16), SpeakerBlock.Kind.CASSETTE));
    public static final DeferredBlock<SpeakerBlock> MUSIC_MANAGER = BLOCKS.register("music_manager",
            () -> new SpeakerBlock(props(), Block.box(0, 0, 0, 16, 16, 16), SpeakerBlock.Kind.LINK));

    public static final DeferredItem<BoomboxItem> BOOMBOX_ITEM = ITEMS.register("boombox",
            () -> new BoomboxItem(BOOMBOX.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> CASSETTE_DECK_ITEM = ITEMS.registerSimpleBlockItem(CASSETTE_DECK);
    public static final DeferredItem<BlockItem> MUSIC_MANAGER_ITEM = ITEMS.registerSimpleBlockItem(MUSIC_MANAGER);
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final Supplier<DataComponentType<List<Track>>> TRACKS = COMPONENTS.registerComponentType("tracks",
            b -> b.persistent(Track.LIST_CODEC).networkSynchronized(Track.LIST_STREAM_CODEC));

    public static final DeferredItem<CassetteItem> CASSETTE_TAPE = ITEMS.register("cassette_tape",
            () -> new CassetteItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<CassetteItem> CASSETTE_TAPE_GLASS = ITEMS.register("cassette_tape_glass",
            () -> new CassetteItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> PARABOLIC_ANTENNA = ITEMS.register("parabolic_antenna",
            () -> new Item(new Item.Properties().stacksTo(1)) {
                @Override
                public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, net.minecraft.world.item.TooltipFlag flag) {
                    tooltip.add(Component.translatable("tooltip.songsandspeakers.parabolic_antenna")
                            .withStyle(net.minecraft.ChatFormatting.GRAY));
                }
            });
    public static final Supplier<DataComponentType<RadioSettings>> RADIO = COMPONENTS.registerComponentType("radio",
            b -> b.persistent(RadioSettings.CODEC).networkSynchronized(RadioSettings.STREAM_CODEC));
    public static final DeferredItem<RadioItem> RADIO_ANTENNA = ITEMS.register("radio_antenna",
            () -> new RadioItem(new Item.Properties().stacksTo(1)));

    public static final Supplier<BlockEntityType<SpeakerBlockEntity>> SPEAKER_BE = BLOCK_ENTITIES.register("speaker",
            () -> BlockEntityType.Builder.of(SpeakerBlockEntity::new, CASSETTE_DECK.get(), MUSIC_MANAGER.get()).build(null));

    public static final DeferredHolder<SoundEvent, SoundEvent> STREAM_SOUND = SOUNDS.register("stream",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "stream")));

    public static final Supplier<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.songsandspeakers"))
            .icon(() -> new ItemStack(BOOMBOX_ITEM.get()))
            .displayItems((params, out) -> {
                out.accept(BOOMBOX_ITEM.get());
                out.accept(CASSETTE_DECK_ITEM.get());
                out.accept(MUSIC_MANAGER_ITEM.get());
                out.accept(CASSETTE_TAPE.get());
                out.accept(CASSETTE_TAPE_GLASS.get());
                out.accept(PARABOLIC_ANTENNA.get());
                out.accept(RADIO_ANTENNA.get());
            }).build());

    public SongsAndSpeakers(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, Config.SPEC);
        COMPONENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        SOUNDS.register(modBus);
        TABS.register(modBus);
        modBus.addListener(this::registerPayloads);
        modBus.addListener((net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent e) -> e.register(RadioService.TICKETS));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> {
            if (e.getServer().getTickCount() % 10 == 0) RadioService.tick(e.getServer());
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> RadioService.clear());
        if (FMLEnvironment.dist.isClient()) {
            net.songsandspeakers.client.ClientInit.init(container);
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("4");
        r.playToServer(Payloads.SpeakerAction.TYPE, Payloads.SpeakerAction.STREAM_CODEC, Payloads.SpeakerAction::handle);
        r.playToServer(Payloads.TrackEnded.TYPE, Payloads.TrackEnded.STREAM_CODEC, Payloads.TrackEnded::handle);
        r.playToServer(Payloads.EditCassette.TYPE, Payloads.EditCassette.STREAM_CODEC, Payloads.EditCassette::handle);
        r.playToServer(Payloads.EditRadio.TYPE, Payloads.EditRadio.STREAM_CODEC, Payloads.EditRadio::handle);
        r.playToClient(Payloads.RadioState.TYPE, Payloads.RadioState.STREAM_CODEC, Payloads.RadioState::handle);
    }
}
