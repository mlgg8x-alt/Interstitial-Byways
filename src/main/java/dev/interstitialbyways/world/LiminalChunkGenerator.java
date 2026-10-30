package dev.interstitialbyways.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.interstitialbyways.InterstitialByways;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Deterministic, seed-independent liminal lattice: regions of 64x64 blocks pick a theme, 8-block cells form rooms. */
public class LiminalChunkGenerator extends ChunkGenerator {
    public static final MapCodec<LiminalChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)
    ).apply(i, LiminalChunkGenerator::new));

    public static final int FLOOR = 63;       // top solid layer; walkable surface is FLOOR + 1
    public static final int TOP = 127;

    public enum Theme { OFFICE, TUNNEL, INDUSTRIAL, ROAD, BRIDGE, ARCHIVE }

    public LiminalChunkGenerator(BiomeSource source) { super(source); }

    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    // ---- deterministic helpers (also used by hub discovery) ----
    public static long hash(int a, int b, int salt) {
        long h = a * 341873128712L + b * 132897987541L + salt * 0x9E3779B97F4A7C15L;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return h;
    }
    public static double rnd(int a, int b, int salt) { return (hash(a, b, salt) >>> 11) / (double) (1L << 53); }

    public static Theme themeAt(int x, int z) {
        return Theme.values()[(int) Math.floorMod(hash(Math.floorDiv(x, 64), Math.floorDiv(z, 64), 7), Theme.values().length)];
    }
    public static int ceilingHeight(Theme t) {
        return switch (t) { case OFFICE -> 4; case TUNNEL -> 6; case INDUSTRIAL -> 9; case ROAD -> 12; case BRIDGE -> 24; case ARCHIVE -> 7; };
    }

    /** Hub (station room) chunk for a 6x6-chunk region, or null if the region has none. Region (0,0) always has one. */
    public static BlockPos hubFor(int rx, int rz) {
        boolean origin = rx == 0 && rz == 0;
        if (!origin && hash(rx, rz, 11) % 2 != 0) return null;
        int cx = rx * 6 + (origin ? 0 : (int) Math.floorMod(hash(rx, rz, 12), 6));
        int cz = rz * 6 + (origin ? 0 : (int) Math.floorMod(hash(rx, rz, 13), 6));
        return new BlockPos(cx * 16 + 8, FLOOR + 1, cz * 16 + 8);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState rs, StructureManager sm, ChunkAccess chunk) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        BlockPos hub = hubFor(Math.floorDiv(chunk.getPos().x, 6), Math.floorDiv(chunk.getPos().z, 6));
        boolean hubChunk = hub != null && (hub.getX() >> 4) == chunk.getPos().x && (hub.getZ() >> 4) == chunk.getPos().z;

        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = baseX + lx, z = baseZ + lz;
            Theme theme = themeAt(x, z);
            int h = ceilingHeight(theme);
            boolean inHub = hubChunk && Math.abs(x - hub.getX()) <= 6 && Math.abs(z - hub.getZ()) <= 6;
            if (inHub) h = Math.max(h, 8);
            int ceil = FLOOR + 1 + h;          // first ceiling block
            boolean wall = !inHub && isWall(theme, x, z);
            boolean pit = !inHub && theme == Theme.BRIDGE && !onDeck(x, z);

            for (int y = 0; y <= TOP; y++) {
                BlockState s;
                if (y == 0) s = Blocks.BEDROCK.defaultBlockState();
                else if (pit && y <= 20) s = y <= 3 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                else if (y <= FLOOR) s = floorBlock(theme, x, z, y, inHub, pit);
                else if (y < ceil) s = wall ? wallBlock(theme, x, z, y) : interior(theme, x, z, y, ceil, inHub);
                else s = ceilingBlock(theme, x, z, y, ceil);
                if (!s.isAir()) chunk.setBlockState(pos.set(x, y, z), s, false);
            }
        }
        if (hubChunk) chunk.setBlockState(new BlockPos(hub.getX(), FLOOR + 1, hub.getZ()),
                InterstitialByways.STATION.get().defaultBlockState(), false);
        return CompletableFuture.completedFuture(chunk);
    }

    /** Lattice walls every 8 blocks with doorways; every 32nd line has no wall, forming open arteries that keep the plane connected. */
    private static boolean isWall(Theme t, int x, int z) {
        if (t == Theme.ROAD || t == Theme.BRIDGE) return false;
        int mx = Math.floorMod(x, 8), mz = Math.floorMod(z, 8);
        if (mx == 0 && Math.floorMod(x, 32) != 0) {          // wall running along z
            int seg = Math.floorDiv(z, 8), col = Math.floorDiv(x, 8);
            int door = 2 + (int) Math.floorMod(hash(col, seg, 21), 4);
            return !(rnd(col, seg, 22) < 0.7 && Math.abs(mz - door) <= 1);
        }
        if (mz == 0 && Math.floorMod(z, 32) != 0) {          // wall running along x
            int seg = Math.floorDiv(x, 8), row = Math.floorDiv(z, 8);
            int door = 2 + (int) Math.floorMod(hash(seg, row, 23), 4);
            return !(rnd(seg, row, 24) < 0.7 && Math.abs(mx - door) <= 1);
        }
        return false;
    }

    private static boolean onDeck(int x, int z) { return Math.floorMod(x, 32) < 5 || Math.floorMod(z, 32) < 5; }

    private static BlockState floorBlock(Theme t, int x, int z, int y, boolean hub, boolean pit) {
        if (y < FLOOR) return Blocks.STONE.defaultBlockState();
        if (hub) return ((x + z) & 1) == 0 ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState();
        return switch (t) {
            case OFFICE -> Blocks.BROWN_TERRACOTTA.defaultBlockState();
            case TUNNEL -> Blocks.GRAY_CONCRETE.defaultBlockState();
            case INDUSTRIAL -> ((x ^ z) & 4) == 0 ? Blocks.IRON_BLOCK.defaultBlockState() : Blocks.DEEPSLATE_TILES.defaultBlockState();
            case ROAD -> (Math.floorMod(z, 32) == 2 && (x & 7) < 4) ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.BLACK_CONCRETE.defaultBlockState();
            case BRIDGE -> Blocks.STONE_BRICKS.defaultBlockState();
            case ARCHIVE -> Blocks.SPRUCE_PLANKS.defaultBlockState();
        };
    }

    private static BlockState wallBlock(Theme t, int x, int z, int y) {
        return switch (t) {
            case OFFICE -> Blocks.YELLOW_TERRACOTTA.defaultBlockState();
            case TUNNEL -> Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
            case INDUSTRIAL -> y % 3 == 0 ? Blocks.IRON_BARS.defaultBlockState() : Blocks.WAXED_WEATHERED_COPPER.defaultBlockState();
            case ARCHIVE -> (y & 1) == 0 ? Blocks.BOOKSHELF.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState();
            default -> Blocks.STONE_BRICKS.defaultBlockState();
        };
    }

    private static BlockState interior(Theme t, int x, int z, int y, int ceil, boolean hub) {
        // pillars on a sparse grid break up open halls
        if (!hub && (t == Theme.ROAD || t == Theme.INDUSTRIAL) && Math.floorMod(x, 16) == 8 && Math.floorMod(z, 16) == 8)
            return t == Theme.ROAD ? Blocks.STONE_BRICK_WALL.defaultBlockState() : Blocks.WAXED_OXIDIZED_CUT_COPPER.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    private static BlockState ceilingBlock(Theme t, int x, int z, int y, int ceil) {
        if (y > ceil) return Blocks.STONE.defaultBlockState();
        boolean lamp = Math.floorMod(x, 8) == 4 && Math.floorMod(z, 8) == 4;
        if (lamp) return t == Theme.ARCHIVE ? Blocks.LANTERN.defaultBlockState() : Blocks.SEA_LANTERN.defaultBlockState();
        return switch (t) {
            case OFFICE -> Blocks.WHITE_TERRACOTTA.defaultBlockState();
            case ARCHIVE -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            default -> Blocks.SMOOTH_STONE.defaultBlockState();
        };
    }

    // ---- boilerplate ----
    @Override public void applyCarvers(WorldGenRegion r, long seed, RandomState rs, BiomeManager bm, StructureManager sm, ChunkAccess c, GenerationStep.Carving step) {}
    @Override public void buildSurface(WorldGenRegion r, StructureManager sm, RandomState rs, ChunkAccess c) {}
    @Override public void spawnOriginalMobs(WorldGenRegion r) {}
    @Override public int getGenDepth() { return 128; }
    @Override public int getSeaLevel() { return 0; }
    @Override public int getMinY() { return 0; }
    @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState rs) { return FLOOR + 1; }
    @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState rs) {
        BlockState[] col = new BlockState[level.getHeight()];
        for (int i = 0; i < col.length; i++) col[i] = i <= FLOOR ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        return new NoiseColumn(level.getMinBuildHeight(), col);
    }
    @Override public void addDebugScreenInfo(List<String> info, RandomState rs, BlockPos pos) {
        info.add("Liminal theme: " + themeAt(pos.getX(), pos.getZ()));
    }
}
