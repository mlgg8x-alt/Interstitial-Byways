package dev.interstitialbyways;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(InterstitialByways.MOD_ID)
public class InterstitialByways {
    public static final String MOD_ID = "interstitial_byways";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public InterstitialByways(IEventBus modEventBus) {
        LOGGER.info("Interstitial Byways loaded");
    }
}
