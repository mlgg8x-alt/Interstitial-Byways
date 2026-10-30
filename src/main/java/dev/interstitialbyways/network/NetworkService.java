package dev.interstitialbyways.network;

import dev.interstitialbyways.InterstitialByways;
import dev.interstitialbyways.compat.Destinations;
import dev.interstitialbyways.network.NetworkData.Station;
import dev.interstitialbyways.world.LiminalChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/** All server-authoritative network rules: registration, linking, discovery, familiarity and travel. */
public final class NetworkService {
    private NetworkService() {}

    public static final int DISCOVERY_RADIUS_REGIONS = 2;

    public static ResourceKey<Level> liminal() { return InterstitialByways.LIMINAL_DIM; }

    /** Make sure every loaded dimension has a gate record. Cheap; safe to call often. */
    public static void ensureGates(MinecraftServer server) {
        NetworkData d = NetworkData.get(server);
        boolean changed = false;
        for (ResourceKey<Level> dim : Destinations.scan(server)) {
            String gid = NetworkData.gateId(dim);
            if (d.stations.containsKey(gid)) continue;
            Station s = new Station();
            s.id = gid; s.dim = dim; s.gate = true; s.active = true; s.name = Destinations.nameFor(dim);
            if (dim.equals(liminal())) { s.pos = LiminalChunkGenerator.hubFor(0, 0); s.resolved = true; }
            else { s.pos = BlockPos.ZERO; s.resolved = false; }
            d.stations.put(gid, s);
            if (!dim.equals(liminal())) d.link(gid, NetworkData.gateId(liminal()));
            changed = true;
        }
        if (changed) d.setDirty();
    }

    public static Station stationAt(NetworkData d, ResourceKey<Level> dim, BlockPos pos) {
        for (Station s : d.stations.values()) if (s.resolved && s.dim.equals(dim) && s.pos.equals(pos)) return s;
        return null;
    }

    public static Station register(NetworkData d, ResourceKey<Level> dim, BlockPos pos, boolean active) {
        Station s = stationAt(d, dim, pos);
        if (s != null) return s;
        s = new Station();
        s.id = NetworkData.stationId(dim, pos); s.dim = dim; s.pos = pos.immutable(); s.active = active;
        s.name = dim.equals(liminal())
                ? "Hub " + Long.toString(Math.floorMod(LiminalChunkGenerator.hash(pos.getX() >> 4, pos.getZ() >> 4, 99), 46656L), 36).toUpperCase()
                : "Station " + dim.location().getPath() + " " + (d.stations.size() + 1);
        d.stations.put(s.id, s);
        d.setDirty();
        return s;
    }

    /** Repair/activate a station and wire it into the graph. Costs an ender pearl outside creative. */
    public static boolean activate(ServerPlayer player, ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        ensureGates(server);
        NetworkData d = NetworkData.get(server);
        Station s = register(d, level.dimension(), pos, false);
        if (s.active) return true;
        if (!player.isCreative()) {
            int slot = -1;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++)
                if (player.getInventory().getItem(i).is(Items.ENDER_PEARL)) { slot = i; break; }
            if (slot < 0) { player.displayClientMessage(Component.literal("This station is dormant. Repair it with an ender pearl."), true); return false; }
            player.getInventory().getItem(slot).shrink(1);
        }
        s.active = true;
        connect(server, d, s);
        NetworkData.Player p = d.player(player.getUUID());
        p.known.add(s.id);
        for (Station g : d.stations.values()) if (g.gate) p.known.add(g.id);
        d.setDirty();
        BlockState bs = level.getBlockState(pos);
        if (bs.hasProperty(dev.interstitialbyways.block.StationBlock.LIT))
            level.setBlock(pos, bs.setValue(dev.interstitialbyways.block.StationBlock.LIT, true), 3);
        player.displayClientMessage(Component.literal("Station activated: " + s.name), false);
        return true;
    }

    private static void connect(MinecraftServer server, NetworkData d, Station s) {
        String lim = NetworkData.gateId(liminal());
        if (s.dim.equals(liminal())) d.link(s.id, lim);
        else d.link(s.id, NetworkData.gateId(s.dim));            // local gate, which itself links to the liminal gate
        List<Station> near = new ArrayList<>();
        for (Station o : d.stations.values())
            if (o != s && o.active && !o.gate && o.dim.equals(s.dim) && o.resolved) near.add(o);
        near.sort(Comparator.comparingDouble(o -> o.pos.distSqr(s.pos)));
        for (int i = 0; i < Math.min(2, near.size()); i++) d.link(s.id, near.get(i).id);
    }

    /** Extra link between this station and another active, same-network station (expansion). */
    public static boolean expand(NetworkData d, Station from, Station to) {
        if (from.id.equals(to.id) || d.linked(from.id, to.id)) return false;
        d.link(from.id, to.id); return true;
    }

    public static List<Station> destinations(NetworkData d, UUID player, Station from) {
        NetworkData.Player p = d.player(player);
        List<Station> out = new ArrayList<>();
        for (String id : d.reachable(from.id)) if (p.known.contains(id)) out.add(d.stations.get(id));
        out.sort(Comparator.comparing(s -> s.name));
        return out;
    }

    public static int familiarity(NetworkData.Player p, String from, String to) { return p.trips.getOrDefault(from + ">" + to, 0) / 3; }

    public static int cost(NetworkData.Player p, Station from, Station to) {
        int base = (to.gate ? 2 : 1) + (from.dim.equals(to.dim) ? 0 : 1);
        return Math.max(0, base - familiarity(p, from.id, to.id));
    }

    public static void cycle(ServerPlayer player, Station here, NetworkData d) {
        List<Station> dests = destinations(d, player.getUUID(), here);
        NetworkData.Player p = d.player(player.getUUID());
        if (dests.isEmpty()) { player.displayClientMessage(Component.literal("No known destinations. Explore to discover more."), true); return; }
        int idx = -1;
        for (int i = 0; i < dests.size(); i++) if (dests.get(i).id.equals(p.selected)) idx = i;
        Station next = dests.get((idx + 1) % dests.size());
        p.selected = next.id; d.setDirty();
        player.displayClientMessage(Component.literal("Destination: " + next.name + " (" + cost(p, here, next) + " levels, familiarity "
                + familiarity(p, here.id, next.id) + "). Sneak + use to travel."), true);
    }

    public static boolean travel(ServerPlayer player, Station from, NetworkData d) {
        NetworkData.Player p = d.player(player.getUUID());
        Station to = d.stations.get(p.selected);
        if (to == null || !d.reachable(from.id).contains(to.id)) { cycle(player, from, d); return false; }
        int c = cost(p, from, to);
        if (!player.isCreative() && player.experienceLevel < c) {
            player.displayClientMessage(Component.literal("Not enough experience: " + c + " levels needed."), true); return false;
        }
        ServerLevel target = player.getServer().getLevel(to.dim);
        if (target == null) { player.displayClientMessage(Component.literal("That destination is unavailable."), true); return false; }
        if (!to.resolved && !resolveGate(target, to, d)) {
            player.displayClientMessage(Component.literal("Could not find a safe arrival point."), true); return false;
        }
        BlockPos arrive = safeBeside(target, to.pos);
        if (!player.isCreative()) player.giveExperienceLevels(-c);
        String key = from.id + ">" + to.id;
        p.trips.merge(key, 1, Integer::sum);
        p.known.add(to.id);
        for (String n : d.neighbours(to.id)) p.known.add(n);       // arriving reveals the connections
        d.setDirty();
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
        player.teleportTo(target, arrive.getX() + 0.5, arrive.getY(), arrive.getZ() + 0.5, player.getYRot(), 0f);
        player.displayClientMessage(Component.literal("Arrived at " + to.name), true);
        return true;
    }

    /** First arrival in a dimension: find a safe floor, plant a station there and join it to the graph. */
    private static boolean resolveGate(ServerLevel level, Station gate, NetworkData d) {
        BlockPos spot = findSafe(level);
        if (spot == null) spot = buildPlatform(level);
        level.setBlock(spot, InterstitialByways.STATION.get().defaultBlockState().setValue(dev.interstitialbyways.block.StationBlock.LIT, true), 3);
        gate.pos = spot.immutable(); gate.resolved = true;
        d.setDirty();
        return true;
    }

    private static boolean standable(ServerLevel l, BlockPos feet) {
        BlockState below = l.getBlockState(feet.below());
        return below.isSolid() && !below.is(Blocks.MAGMA_BLOCK) && l.getBlockState(feet).getCollisionShape(l, feet).isEmpty()
                && l.getBlockState(feet.above()).getCollisionShape(l, feet.above()).isEmpty()
                && l.getFluidState(feet).isEmpty() && l.getFluidState(feet.below()).isEmpty()
                && l.getFluidState(feet.above()).isEmpty();
    }

    /** Safe standing spot at or near the origin, handling ceilinged dimensions; null if none is found. */
    public static BlockPos findSafe(ServerLevel l) {
        int min = l.getMinBuildHeight(), max = min + l.getLogicalHeight() - 2;
        for (int r = 0; r <= 64; r += 8) for (int dx = -r; dx <= r; dx += Math.max(8, r)) for (int dz = -r; dz <= r; dz += Math.max(8, r)) {
            int x = dx + 2, z = dz + 2;
            l.getChunk(x >> 4, z >> 4);
            if (l.dimensionType().hasCeiling()) {
                for (int y = min + 32; y < max - 4; y++) { BlockPos p = new BlockPos(x, y, z); if (standable(l, p)) return p; }
            } else {
                int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (y > min + 1) { BlockPos p = new BlockPos(x, y, z); if (standable(l, p)) return p; }
            }
        }
        return null;
    }

    private static BlockPos buildPlatform(ServerLevel l) {
        int y = Math.max(l.getMinBuildHeight() + 40, 64);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            l.setBlock(new BlockPos(x, y - 1, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
            for (int h = 0; h < 3; h++) l.setBlock(new BlockPos(x, y + h, z), Blocks.AIR.defaultBlockState(), 3);
        }
        return new BlockPos(0, y, 0);
    }

    /** Spot next to a station block to stand on, falling back to above it. */
    private static BlockPos safeBeside(ServerLevel l, BlockPos station) {
        l.getChunk(station.getX() >> 4, station.getZ() >> 4);
        for (BlockPos o : new BlockPos[]{station.north(), station.south(), station.east(), station.west()})
            if (standable(l, o)) return o;
        return station.above();
    }

    /** Landmark discovery: stations in the liminal network come to light when a player gets near them. */
    public static void discoverNearby(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!level.dimension().equals(liminal())) return;
        NetworkData d = NetworkData.get(level.getServer());
        NetworkData.Player p = d.player(player.getUUID());
        int rx0 = Math.floorDiv(player.getBlockX() >> 4, 6), rz0 = Math.floorDiv(player.getBlockZ() >> 4, 6);
        boolean changed = false;
        for (int rx = rx0 - DISCOVERY_RADIUS_REGIONS; rx <= rx0 + DISCOVERY_RADIUS_REGIONS; rx++)
            for (int rz = rz0 - DISCOVERY_RADIUS_REGIONS; rz <= rz0 + DISCOVERY_RADIUS_REGIONS; rz++) {
                BlockPos hub = LiminalChunkGenerator.hubFor(rx, rz);
                if (hub == null || hub.distSqr(player.blockPosition()) > 48 * 48) continue;
                Station s = register(d, level.dimension(), hub, hub.equals(LiminalChunkGenerator.hubFor(0, 0)));
                if (p.known.add(s.id)) {
                    changed = true;
                    for (String n : d.neighbours(s.id)) p.known.add(n);
                    player.displayClientMessage(Component.literal("Landmark discovered: " + s.name + (s.active ? "" : " (dormant)")), true);
                }
            }
        if (changed) d.setDirty();
    }

    public static void enterNetwork(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        ensureGates(server);
        NetworkData d = NetworkData.get(server);
        Station g = d.stations.get(NetworkData.gateId(liminal()));
        ServerLevel lim = server.getLevel(liminal());
        if (g == null || lim == null) { player.displayClientMessage(Component.literal("The Interstitial dimension is not loaded."), false); return; }
        d.player(player.getUUID()).known.add(g.id);
        BlockPos arrive = safeBeside(lim, g.pos);
        player.teleportTo(lim, arrive.getX() + 0.5, arrive.getY(), arrive.getZ() + 0.5, 0f, 0f);
        d.setDirty();
    }
}
