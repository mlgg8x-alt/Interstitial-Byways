package dev.interstitialbyways.network;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public class NetworkEvents {
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent e) { NetworkService.ensureGates(e.getServer()); }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post e) {
        if (e.getEntity() instanceof ServerPlayer sp && sp.tickCount % 40 == 0) NetworkService.discoverNearby(sp);
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("interstitial")
                .then(Commands.literal("enter").requires(s -> s.hasPermission(2)).executes(c -> {
                    NetworkService.enterNetwork(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("list").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    NetworkData d = NetworkData.get(c.getSource().getServer());
                    NetworkData.Player pd = d.player(p.getUUID());
                    for (String id : pd.known) {
                        NetworkData.Station s = d.stations.get(id);
                        if (s != null) c.getSource().sendSuccess(() -> Component.literal(
                                s.name + " [" + s.dim.location() + "] " + (s.active ? "active" : "dormant")), false);
                    }
                    return pd.known.size(); })));
    }
}
