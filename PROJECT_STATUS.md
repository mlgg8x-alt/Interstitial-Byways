# Interstitial Byways — Project Status

Targets: Minecraft 1.21.1, NeoForge 21.1.235, Java 21, ModDevGradle 2.0.78. Mod id `interstitial_byways`.
Compatibility target: the ATM10 (All the Mods 10 Aeronautics) instance.

## Verified (this session)
- `./gradlew build` succeeds and produces `build/libs/interstitial_byways-0.1.0.jar` (draft compiled without source changes beyond the package/mod-id rename).
- `./gradlew runServer` boots a dedicated server with the mod loaded and no errors.
- Dimension `interstitial_byways:network` registers, its chunks generate (25 force-loaded chunks, solid terrain, bedrock floor, `locate biome` finds `interstitial_byways:liminal`), driven over RCON.
- Fixed one real bug found by the smoke test: `liminal.json` biome had `"carvers": []` (must be `{}` in 1.21.1), which aborted registry loading.

## Implemented but NOT verified (needs a player)
- Phase 1: custom `liminal` chunk generator, six themes (office, tunnel, industrial, road, bridge, archive), region hubs, `/interstitial enter` (op).
- Phase 2: Transit Station block + recipe, persistent network graph (`NetworkData`), activation with an ender pearl, XP-cost travel, safe-arrival search.
- Phase 3: `Destinations` scans loaded dimensions at runtime (Nether, End, Aether, `alex_caves_dimensions:*`, others).
- Phase 4: proximity landmark discovery, route familiarity lowering travel cost.

## Not tested
- Anything requiring a connected player: station use, travel, safe arrival, discovery, persistence across restarts, client rendering/textures, multiplayer.
- Behavior alongside the actual ATM10 mod list (no modpack available here).
- Generation lighting/atmosphere, performance (`fillFromNoise` sets blocks individually).

## Not done
- Phase 5 content (settlements, loot, elevators/doors, mobs/ambience), Alex's Caves underground-biome targeting, destination GUI, sounds, Phase 6 optimization.

## Next steps
1. Load the jar in the ATM10 instance and exercise `/interstitial enter`, station activation and travel by hand; report issues.
2. Add GameTests for generation and travel logic that can run headless.
3. Then Phase 5 / Alex's Caves biome targeting.
