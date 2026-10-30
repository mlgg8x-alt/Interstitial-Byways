package dev.interstitialbyways.block;

import dev.interstitialbyways.network.NetworkData;
import dev.interstitialbyways.network.NetworkService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Use: activate if dormant, otherwise cycle destination. Sneak + use: travel to the selected destination. */
public class StationBlock extends Block {
    public static final BooleanProperty LIT = BooleanProperty.create("lit");

    public StationBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(LIT, false));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(LIT); }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        ServerLevel sl = (ServerLevel) level;
        NetworkData d = NetworkData.get(sl.getServer());
        NetworkService.ensureGates(sl.getServer());
        NetworkData.Station here = NetworkService.stationAt(d, sl.dimension(), pos);
        if (here == null || !here.active) {
            if (!NetworkService.activate(sp, sl, pos)) return InteractionResult.CONSUME;
            here = NetworkService.stationAt(d, sl.dimension(), pos);
            if (here == null) return InteractionResult.CONSUME;
        }
        d.player(sp.getUUID()).known.add(here.id);
        if (sp.isShiftKeyDown()) NetworkService.travel(sp, here, d);
        else NetworkService.cycle(sp, here, d);
        return InteractionResult.CONSUME;
    }
}
