package dev.interstitialbyways.compat;

import dev.interstitialbyways.InterstitialByways;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.function.Function;

/**
 * Detects every loaded dimension at runtime and gives it a display name. Other mods can call
 * {@link #registerName} to supply nicer labels; unknown dimensions still work automatically.
 * Alex's Caves cave dimensions (alex_caves_dimensions:*) get cave-themed labels.
 */
public final class Destinations {
    private static final Map<String, Function<ResourceLocation, String>> NAMERS = new LinkedHashMap<>();
    static {
        registerName("minecraft", r -> switch (r.getPath()) {
            case "overworld" -> "Overworld Gate"; case "the_nether" -> "Nether Gate"; case "the_end" -> "End Gate";
            default -> null; });
        registerName("aether", r -> "Aether Gate");
        registerName("alex_caves_dimensions", r -> "Cave Gate: " + titleCase(r.getPath()));
    }
    private Destinations() {}

    public static void registerName(String namespace, Function<ResourceLocation, String> namer) { NAMERS.put(namespace, namer); }

    public static List<ResourceKey<Level>> scan(MinecraftServer server) {
        List<ResourceKey<Level>> out = new ArrayList<>();
        for (ResourceKey<Level> k : server.levelKeys()) out.add(k);
        out.sort(Comparator.comparing(k -> k.location().toString()));
        return out;
    }

    public static String nameFor(ResourceKey<Level> dim) {
        ResourceLocation r = dim.location();
        if (dim.equals(InterstitialByways.LIMINAL_DIM)) return "The Interstitial";
        var f = NAMERS.get(r.getNamespace());
        String n = f == null ? null : f.apply(r);
        return n != null ? n : titleCase(r.getNamespace()) + " Gate: " + titleCase(r.getPath());
    }

    private static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.split("[_/]")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return b.toString().trim();
    }
}
