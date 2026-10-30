package dev.interstitialbyways.network;

import dev.interstitialbyways.InterstitialByways;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/** Persistent station graph plus per-player discovery and familiarity. Stored once, on the overworld. */
public class NetworkData extends SavedData {
    private static final String NAME = "interstitial_byways_network";

    public static class Station {
        public String id, name;
        public ResourceKey<Level> dim;
        public BlockPos pos;
        public boolean active, gate, resolved = true;
    }
    public static class Player {
        public final Set<String> known = new HashSet<>();
        public final Map<String, Integer> trips = new HashMap<>();
        public String selected = "";
    }

    public final Map<String, Station> stations = new LinkedHashMap<>();
    public final Set<String> edges = new HashSet<>();     // "a|b" with a<b
    public final Map<UUID, Player> players = new HashMap<>();

    public static NetworkData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(NetworkData::new, NetworkData::load, null), NAME);
    }

    public static String stationId(ResourceKey<Level> dim, BlockPos p) {
        return dim.location() + "@" + p.getX() + "," + p.getY() + "," + p.getZ();
    }
    public static String gateId(ResourceKey<Level> dim) { return "gate@" + dim.location(); }

    public Player player(UUID id) { return players.computeIfAbsent(id, k -> new Player()); }

    public void link(String a, String b) {
        if (a.equals(b)) return;
        edges.add(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a);
        setDirty();
    }
    public boolean linked(String a, String b) {
        return edges.contains(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a);
    }
    public List<String> neighbours(String id) {
        List<String> out = new ArrayList<>();
        for (String e : edges) {
            int i = e.indexOf('|');
            String a = e.substring(0, i), b = e.substring(i + 1);
            if (a.equals(id)) out.add(b); else if (b.equals(id)) out.add(a);
        }
        return out;
    }
    /** All active stations reachable from {@code from} over the graph (breadth-first). */
    public Set<String> reachable(String from) {
        Set<String> seen = new LinkedHashSet<>(); Deque<String> q = new ArrayDeque<>();
        seen.add(from); q.add(from);
        while (!q.isEmpty()) for (String n : neighbours(q.poll())) {
            Station s = stations.get(n);
            if (s != null && s.active && seen.add(n)) q.add(n);
        }
        seen.remove(from);
        return seen;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag sl = new ListTag();
        for (Station s : stations.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", s.id); t.putString("name", s.name); t.putString("dim", s.dim.location().toString());
            t.putLong("pos", s.pos.asLong()); t.putBoolean("active", s.active);
            t.putBoolean("gate", s.gate); t.putBoolean("resolved", s.resolved);
            sl.add(t);
        }
        tag.put("stations", sl);
        ListTag el = new ListTag(); for (String e : edges) el.add(StringTag.valueOf(e)); tag.put("edges", el);
        ListTag pl = new ListTag();
        for (var e : players.entrySet()) {
            CompoundTag t = new CompoundTag(); t.putUUID("uuid", e.getKey());
            t.putString("selected", e.getValue().selected);
            ListTag k = new ListTag(); for (String s : e.getValue().known) k.add(StringTag.valueOf(s)); t.put("known", k);
            CompoundTag tr = new CompoundTag(); e.getValue().trips.forEach(tr::putInt); t.put("trips", tr);
            pl.add(t);
        }
        tag.put("players", pl);
        return tag;
    }

    public static NetworkData load(CompoundTag tag, HolderLookup.Provider registries) {
        NetworkData d = new NetworkData();
        for (Tag raw : tag.getList("stations", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw; Station s = new Station();
            s.id = t.getString("id"); s.name = t.getString("name");
            s.dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.getString("dim")));
            s.pos = BlockPos.of(t.getLong("pos")); s.active = t.getBoolean("active");
            s.gate = t.getBoolean("gate"); s.resolved = t.getBoolean("resolved");
            d.stations.put(s.id, s);
        }
        for (Tag e : tag.getList("edges", Tag.TAG_STRING)) d.edges.add(e.getAsString());
        for (Tag raw : tag.getList("players", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw; Player p = d.player(t.getUUID("uuid"));
            p.selected = t.getString("selected");
            for (Tag k : t.getList("known", Tag.TAG_STRING)) p.known.add(k.getAsString());
            CompoundTag tr = t.getCompound("trips"); for (String k : tr.getAllKeys()) p.trips.put(k, tr.getInt(k));
        }
        return d;
    }
}
