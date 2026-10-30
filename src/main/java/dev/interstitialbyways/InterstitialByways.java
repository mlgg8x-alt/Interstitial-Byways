package dev.interstitialbyways;

import dev.interstitialbyways.block.StationBlock;
import dev.interstitialbyways.network.NetworkEvents;
import dev.interstitialbyways.world.LiminalChunkGenerator;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(InterstitialByways.ID)
public class InterstitialByways {
    public static final String ID = "interstitial_byways";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);

    public static final DeferredBlock<StationBlock> STATION = BLOCKS.register("station",
            () -> new StationBlock(BlockBehaviour.Properties.of().strength(3.0f, 1200f).lightLevel(s -> s.getValue(StationBlock.LIT) ? 12 : 3)));
    public static final DeferredItem<BlockItem> STATION_ITEM = ITEMS.registerSimpleBlockItem("station", STATION);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<LiminalChunkGenerator>> LIMINAL =
            GENERATORS.register("liminal", () -> LiminalChunkGenerator.CODEC);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.interstitial_byways"))
                    .icon(() -> STATION_ITEM.get().getDefaultInstance())
                    .displayItems((p, out) -> out.accept(STATION_ITEM.get())).build());

    public static final ResourceKey<Level> LIMINAL_DIM = ResourceKey.create(Registries.DIMENSION, id("network"));

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(ID, path); }

    public InterstitialByways(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        GENERATORS.register(modBus);
        TABS.register(modBus);
        NeoForge.EVENT_BUS.register(new NetworkEvents());
    }
}
