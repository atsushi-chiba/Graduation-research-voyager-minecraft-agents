package com.voyagerbridge;

import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Minimal HTTP bridge so an LLM agent process can drive world/colony state
 * directly from inside the server JVM, without needing a real (Forge-aware)
 * client connection. MineColonies/Structurize register mandatory FML network
 * channels, so a plain vanilla-protocol bot (mineflayer) cannot even log in -
 * this mod sidesteps that entirely by calling the same standard Block/Level
 * APIs a player's client action would trigger, from the server side.
 */
@Mod(VoyagerBridge.MODID)
public class VoyagerBridge {
    public static final String MODID = "voyagerbridge";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PORT = 8089;
    private static final GameProfile AI_PROFILE =
            new GameProfile(UUID.nameUUIDFromBytes("voyager-ai".getBytes(StandardCharsets.UTF_8)), "VoyagerAI");

    private MinecraftServer server;
    private HttpServer httpServer;

    public VoyagerBridge() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        this.server = event.getServer();
        try {
            httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
            httpServer.createContext("/place", this::handlePlace);
            httpServer.createContext("/found", this::handleFound);
            httpServer.createContext("/spawnCitizen", this::handleSpawnCitizen);
            httpServer.createContext("/status", this::handleStatus);
            httpServer.createContext("/requestBuild", this::handleRequestBuild);
            httpServer.createContext("/giveToCitizen", this::handleGiveToCitizen);
            httpServer.createContext("/giveTexturedBlock", this::handleGiveTexturedBlock);
            httpServer.createContext("/openRequests", this::handleOpenRequests);
            httpServer.createContext("/resolveRequest", this::handleResolveRequest);
            httpServer.createContext("/clearCitizenInventory", this::handleClearCitizenInventory);
            httpServer.createContext("/ping", VoyagerBridge::handlePing);
            httpServer.setExecutor(null);
            httpServer.start();
            LOGGER.info("VoyagerBridge HTTP server listening on port {}", PORT);
        } catch (IOException e) {
            LOGGER.error("VoyagerBridge failed to start HTTP server", e);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private static void handlePing(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"status\":\"ok\"}");
    }

    // POST /place?x=100&y=64&z=200&block=minecolonies:blockhuttownhall
    private void handlePlace(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            String query = exchange.getRequestURI().getQuery();
            java.util.Map<String, String> params = parseQuery(query);
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            int z = Integer.parseInt(params.get("z"));
            String blockId = params.getOrDefault("block", "minecolonies:blockhuttownhall");

            // World mutation must happen on the server thread, not the HTTP
            // handler thread, so schedule it and block this thread for the result.
            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    result.complete(placeOnServerThread(x, y, z, blockId));
                } catch (Exception e) {
                    result.complete("ERROR: " + e);
                }
            });
            String outcome = result.get();
            if (outcome.startsWith("ERROR")) {
                respond(exchange, 500, "{\"error\":\"" + escape(outcome) + "\"}");
            } else {
                respond(exchange, 200, "{\"result\":\"" + escape(outcome) + "\"}");
            }
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // POST /found?x=..&y=..&z=..&name=MyColony
    // Founds a colony on a previously-placed (but not yet colonized) town
    // hall, mirroring what CreateColonyMessage does when a player confirms
    // the in-game "found colony" prompt - but triggered over HTTP instead.
    private void handleFound(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            int z = Integer.parseInt(params.get("z"));
            String name = params.getOrDefault("name", "VoyagerColony");

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    result.complete(foundOnServerThread(x, y, z, name));
                } catch (Exception e) {
                    result.complete("ERROR: " + e);
                }
            });
            String outcome = result.get();
            if (outcome.startsWith("ERROR")) {
                respond(exchange, 500, "{\"error\":\"" + escape(outcome) + "\"}");
            } else {
                respond(exchange, 200, "{\"result\":\"" + escape(outcome) + "\"}");
            }
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    private String foundOnServerThread(int x, int y, int z, String colonyName) {
        ServerLevel level = server.overworld();
        Player fakePlayer = new FakePlayer(level, AI_PROFILE);

        // placing a townhall block already triggers MineColonies' setPlacedBy which
        // auto-creates the colony - check for that before attempting a second createColony
        IColony existingOwned = IColonyManager.getInstance().getIColonyByOwner(level, fakePlayer);
        if (existingOwned != null) {
            existingOwned.setName(colonyName);
            return "founded colony " + existingOwned.getID() + " (" + colonyName + ")";
        }

        BlockPos pos = new BlockPos(x, y, z);
        BlockEntity tileEntity = level.getBlockEntity(pos);
        if (!(tileEntity instanceof TileEntityColonyBuilding)) {
            return "ERROR: no colony building tile entity at " + x + "," + y + "," + z;
        }
        TileEntityColonyBuilding hut = (TileEntityColonyBuilding) tileEntity;

        StructurePackMeta pack = getPinnedPack();
        if (pack == null) {
            return "ERROR: no structure packs registered";
        }

        IColony created = IColonyManager.getInstance().createColony(level, pos, fakePlayer, colonyName, pack.getName());
        created.getServerBuildingManager().addNewBuilding(hut, level);
        return "founded colony " + created.getID() + " (" + colonyName + ") with pack " + pack.getName();
    }

    // POST /spawnCitizen?colonyId=1
    private void handleSpawnCitizen(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int colonyId = Integer.parseInt(params.get("colonyId"));

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, server.overworld());
                    if (colony == null) {
                        result.complete("ERROR: no colony with id " + colonyId);
                        return;
                    }
                    int before = colony.getCitizenManager().getCurrentCitizenCount();
                    colony.getCitizenManager().spawnOrCreateCivilian(null, colony.getWorld(), new java.util.ArrayList<>(), true);
                    int after = colony.getCitizenManager().getCurrentCitizenCount();
                    result.complete("citizen count " + before + " -> " + after);
                } catch (Exception e) {
                    LOGGER.error("spawnCitizen failed", e);
                    result.complete("ERROR: " + e);
                }
            });
            String outcome = result.get();
            if (outcome.startsWith("ERROR")) {
                respond(exchange, 500, "{\"error\":\"" + escape(outcome) + "\"}");
            } else {
                respond(exchange, 200, "{\"result\":\"" + escape(outcome) + "\"}");
            }
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // GET /status - lists all colonies with id/name/center/citizen count, so
    // an LLM agent can decide what to do next without needing a game client.
    private void handleStatus(HttpExchange exchange) throws IOException {
        java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                java.util.List<IColony> colonies = IColonyManager.getInstance().getAllColonies();
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < colonies.size(); i++) {
                    IColony c = colonies.get(i);
                    BlockPos center = c.getCenter();
                    if (i > 0) sb.append(",");
                    sb.append("{")
                      .append("\"id\":").append(c.getID()).append(",")
                      .append("\"name\":\"").append(escape(c.getName())).append("\",")
                      .append("\"x\":").append(center.getX()).append(",")
                      .append("\"y\":").append(center.getY()).append(",")
                      .append("\"z\":").append(center.getZ()).append(",")
                      .append("\"buildings\":[");
                    java.util.List<ICitizenData> citizens = c.getCitizenManager().getCitizens();
                    java.util.Map<BlockPos, IBuilding> buildings = c.getServerBuildingManager().getBuildings();
                    boolean firstBld = true;
                    for (java.util.Map.Entry<BlockPos, IBuilding> bldEntry : buildings.entrySet()) {
                        if (!firstBld) sb.append(",");
                        firstBld = false;
                        BlockPos bp = bldEntry.getKey();
                        IBuilding bld = bldEntry.getValue();
                        ResourceLocation bldKey = ForgeRegistries.BLOCKS.getKey(
                            level.getBlockState(bp).getBlock());
                        String bldType = bldKey != null ? bldKey.getPath() : "unknown";
                        boolean operational = bld.getBuildingLevel() >= 1 && !bld.isPendingConstruction();
                        boolean inTerritory = c.isCoordInColony(level, bp);
                        java.util.List<Integer> workerIds = citizens.stream()
                            .filter(w -> w.getWorkBuilding() != null
                                && w.getWorkBuilding().getPosition().equals(bp))
                            .map(ICitizenData::getId)
                            .collect(java.util.stream.Collectors.toList());
                        sb.append("{")
                          .append("\"x\":").append(bp.getX()).append(",")
                          .append("\"y\":").append(bp.getY()).append(",")
                          .append("\"z\":").append(bp.getZ()).append(",")
                          .append("\"type\":\"").append(escape(bldType)).append("\",")
                          .append("\"level\":").append(bld.getBuildingLevel()).append(",")
                          .append("\"pending\":").append(bld.isPendingConstruction()).append(",")
                          .append("\"operational\":").append(operational).append(",")
                          .append("\"inTerritory\":").append(inTerritory).append(",")
                          .append("\"workers\":").append(workerIds)
                          .append("}");
                    }
                    // Research-unlocked buildings: which research-gated building types
                    // have had their unlock-research completed in this colony.
                    sb.append("],\"researchUnlocked\":[");
                    boolean firstR = true;
                    for (String bType : RESEARCH_GATED_BUILDINGS) {
                        ResourceLocation eff = new ResourceLocation("minecolonies", "effects/" + bType);
                        if (c.getResearchManager().getResearchEffects().getEffectStrength(eff) > 0) {
                            if (!firstR) sb.append(",");
                            sb.append("\"").append(bType).append("\"");
                            firstR = false;
                        }
                    }
                    sb.append("],\"citizens\":[");
                    for (int j = 0; j < citizens.size(); j++) {
                        ICitizenData cit = citizens.get(j);
                        if (j > 0) sb.append(",");
                        String jobName = cit.getJob() == null ? "unemployed"
                            : cit.getJob().getJobRegistryEntry().getKey().getPath();
                        String jobStatusStr = cit.getJobStatus() != null
                            ? cit.getJobStatus().name().toLowerCase() : "unknown";
                        IBuilding workBld = cit.getWorkBuilding();
                        sb.append("{")
                          .append("\"id\":").append(cit.getId()).append(",")
                          .append("\"name\":\"").append(escape(cit.getName())).append("\",")
                          .append("\"job\":\"").append(escape(jobName)).append("\",")
                          .append("\"jobStatus\":\"").append(jobStatusStr).append("\",");
                        if (workBld != null) {
                            BlockPos wp = workBld.getPosition();
                            sb.append("\"workBuilding\":{")
                              .append("\"x\":").append(wp.getX()).append(",")
                              .append("\"y\":").append(wp.getY()).append(",")
                              .append("\"z\":").append(wp.getZ()).append(",")
                              .append("\"level\":").append(workBld.getBuildingLevel())
                              .append("}");
                        } else {
                            sb.append("\"workBuilding\":null");
                        }
                        sb.append("}");
                    }
                    sb.append("]}");
                }
                sb.append("]");
                result.complete(sb.toString());
            } catch (Exception e) {
                LOGGER.error("status failed", e);
                result.complete("ERROR: " + e);
            }
        });
        try {
            String outcome = result.get();
            if (outcome.startsWith("ERROR")) {
                respond(exchange, 500, "{\"error\":\"" + escape(outcome) + "\"}");
            } else {
                respond(exchange, 200, outcome);
            }
        } catch (Exception e) {
            respond(exchange, 500, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // POST /requestBuild?x=..&y=..&z=..
    // Queues a work order for the building at this position, mirroring
    // BuildRequestMessage (the network message the in-game "Build" button
    // sends). Placing a hut block alone never creates a work order - that's
    // a separate, deliberate step in the GUI, so the colony's builders sit
    // idle forever without this.
    private void handleRequestBuild(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            int z = Integer.parseInt(params.get("z"));

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    BlockPos pos = new BlockPos(x, y, z);
                    IColony colony = findColonyAt(level, pos);
                    if (colony == null) {
                        result.complete("ERROR: no colony at " + x + "," + y + "," + z);
                        return;
                    }
                    IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
                    if (building == null) {
                        result.complete("ERROR: no building registered at " + x + "," + y + "," + z);
                        return;
                    }
                    if (building.isPendingConstruction()) {
                        result.complete("already has a pending work order");
                        return;
                    }
                    Block targetBlock = level.getBlockState(pos).getBlock();
                    ResourceLocation targetKey = ForgeRegistries.BLOCKS.getKey(targetBlock);
                    boolean isBuilderHut = targetKey != null && "blockhutbuilder".equals(targetKey.getPath());

                    // Research gate: some buildings require university research before
                    // they can be built. The effect ID follows the naming convention
                    // minecolonies:effects/<block_registry_path> (verified from JAR:
                    // effects/blockhutsawmill.json, effects/blockhutflorist.json, etc.).
                    // getResearchEffectIdFrom(Block) is unreliable server-side (returns
                    // null before research is done), so we use a static list of
                    // research-gated buildings derived from the JAR's effects/ directory,
                    // and check effect strength directly via the constructed effect ID.
                    if (!isBuilderHut && targetKey != null
                            && RESEARCH_GATED_BUILDINGS.contains(targetKey.getPath())) {
                        ResourceLocation effectId = new ResourceLocation("minecolonies",
                                "effects/" + targetKey.getPath());
                        double strength = colony.getResearchManager().getResearchEffects()
                                .getEffectStrength(effectId);
                        if (strength <= 0) {
                            result.complete("ERROR: " + targetKey.getPath()
                                + " requires university research (effect: " + effectId
                                + "); complete that research at the University first");
                            return;
                        }
                    }

                    // Builder level gate: upgrading a building from level N to N+1
                    // requires a Builder's Hut at level >= N+1. Level-0 builders can
                    // only build their own hut (exempted above). Queueing a work order
                    // no builder can execute leaves it stuck at 0/0 steps in the UI.
                    if (!isBuilderHut) {
                        int targetLevel = building.getBuildingLevel();          // current level
                        int requiredBuilderLevel = targetLevel + 1;             // need this to upgrade
                        boolean hasCapableBuilder = colony.getServerBuildingManager().getBuildings().values().stream()
                            .anyMatch(bld -> {
                                ResourceLocation bldKey = ForgeRegistries.BLOCKS.getKey(
                                    level.getBlockState(bld.getPosition()).getBlock());
                                return bldKey != null && "blockhutbuilder".equals(bldKey.getPath())
                                    && bld.getBuildingLevel() >= requiredBuilderLevel;
                            });
                        if (!hasCapableBuilder) {
                            result.complete("ERROR: upgrading this building (level " + targetLevel + "→" + (targetLevel + 1)
                                + ") needs a Builder's Hut at level " + requiredBuilderLevel
                                + "; upgrade a Builder's Hut first");
                            return;
                        }
                    }
                    // requestUpgrade's second argument is the BlockPos of the builder's
                    // hut that should handle this work order - NOT the target building's
                    // own position. Passing pos (the target building) worked for builder
                    // huts building themselves (pos == their own hut), but silently
                    // produced no work order for every other building type.
                    // For builder huts: assign to self (pos). For others: find any lv1+ hut.
                    BlockPos builderHutPos = isBuilderHut ? pos
                        : colony.getServerBuildingManager().getBuildings().values().stream()
                            .filter(bld -> {
                                ResourceLocation bldKey = ForgeRegistries.BLOCKS.getKey(
                                    level.getBlockState(bld.getPosition()).getBlock());
                                return bldKey != null && "blockhutbuilder".equals(bldKey.getPath())
                                    && bld.getBuildingLevel() >= 1;
                            })
                            .map(IBuilding::getPosition)
                            .findFirst()
                            .orElse(pos);
                    Player fakePlayer = new FakePlayer(level, AI_PROFILE);
                    building.requestUpgrade(fakePlayer, builderHutPos);
                    result.complete("requested build for building at " + x + "," + y + "," + z
                        + " (assigned to builder hut at " + builderHutPos + ")");
                } catch (Exception e) {
                    LOGGER.error("requestBuild failed", e);
                    result.complete("ERROR: " + e);
                }
            });
            String outcome = result.get();
            if (outcome.startsWith("ERROR")) {
                respond(exchange, 500, "{\"error\":\"" + escape(outcome) + "\"}");
            } else {
                respond(exchange, 200, "{\"result\":\"" + escape(outcome) + "\"}");
            }
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // POST /giveToCitizen?colonyId=1&citizenId=3&item=minecraft:oak_log&count=80
    // Inserts items directly into a citizen's personal inventory. Used as a
    // workaround while the colony's Warehouse is still level 0 (and so isn't
    // a functioning storage building yet) - the builder can use materials
    // it's personally carrying even before any warehouse exists.
    private void handleGiveToCitizen(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int colonyId = Integer.parseInt(params.get("colonyId"));
            int citizenId = Integer.parseInt(params.get("citizenId"));
            String itemId = params.get("item");
            int count = Integer.parseInt(params.getOrDefault("count", "1"));

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, server.overworld());
                    if (colony == null) {
                        result.complete("ERROR: no colony with id " + colonyId);
                        return;
                    }
                    ICitizenData citizen = colony.getCitizenManager().getCivilian(citizenId);
                    if (citizen == null) {
                        result.complete("ERROR: no citizen with id " + citizenId);
                        return;
                    }
                    java.util.Optional<AbstractEntityCitizen> entityOpt = citizen.getEntity();
                    if (entityOpt.isEmpty()) {
                        result.complete("ERROR: citizen " + citizenId + " has no live entity right now");
                        return;
                    }
                    Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
                    if (item == null) {
                        result.complete("ERROR: unknown item id " + itemId);
                        return;
                    }
                    InventoryCitizen inv = entityOpt.get().getInventoryCitizen();
                    ItemStack remainder = new ItemStack(item, count);
                    int slots = inv.getSlots();
                    for (int slot = 0; slot < slots && !remainder.isEmpty(); slot++) {
                        remainder = inv.insertItem(slot, remainder, false);
                    }
                    int given = count - remainder.getCount();
                    result.complete("gave " + given + "/" + count + " of " + itemId + " to citizen " + citizenId);
                } catch (Exception e) {
                    LOGGER.error("giveToCitizen failed", e);
                    result.complete("ERROR: " + e);
                }
            });
            String outcome = result.get();
            if (outcome.startsWith("ERROR")) {
                respond(exchange, 500, "{\"error\":\"" + escape(outcome) + "\"}");
            } else {
                respond(exchange, 200, "{\"result\":\"" + escape(outcome) + "\"}");
            }
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // POST /giveTexturedBlock?colonyId=1&citizenId=3&block=domum_ornamentum:double_crossed&count=1
    //      &tex1=minecraft:block/oak_planks&mat1=minecraft:stripped_spruce_wood
    //      &tex2=minecraft:block/dark_oak_planks&mat2=minecraft:stripped_oak_wood
    //
    // Domum Ornamentum's "Framed"/retexturable blocks (the ones the in-game
    // resource list shows as e.g. "Framed Stripped Oak Wood") are not plain
    // items - each instance carries a `textureData` compound directly on the
    // ItemStack's NBT (read via ItemStack.getTagElement("textureData") - NOT
    // nested under vanilla's "BlockEntityTag", which was our first, wrong,
    // guess and produced a buggy item whose frame/centre randomly reassigned
    // on every render) mapping texture-path ResourceLocations (from the
    // block's model, e.g. "minecraft:block/oak_planks") to the actual
    // material block used for that texture slot (e.g.
    // "minecraft:stripped_spruce_wood" for the frame).
    //
    // Rather than conjuring the finished item from nothing (which is what
    // MineColonies' own `creativeresolve` debug option does), this endpoint
    // requires the citizen to actually be holding one of each named material
    // (`mat1`, `mat2`, ...) per output item, and consumes them - an "exchange"
    // rather than a cheat. This mirrors what an Architect's Cutter recipe
    // would consume/produce, since we can't call that recipe's own assemble()
    // method directly (it overrides a vanilla Recipe method and is SRG-named
    // in this un-deobfuscated jar) - we recovered the exact NBT key/value
    // format instead by reading a real placed instance out of a shipped
    // .blueprint file's tile_entities list.
    private void handleGiveTexturedBlock(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int colonyId = Integer.parseInt(params.get("colonyId"));
            int citizenId = Integer.parseInt(params.get("citizenId"));
            String blockId = params.get("block");
            int count = Integer.parseInt(params.getOrDefault("count", "1"));

            CompoundTag textureData = new CompoundTag();
            java.util.List<Item> materials = new java.util.ArrayList<>();
            for (int i = 1; params.containsKey("tex" + i); i++) {
                String tex = params.get("tex" + i);
                String mat = params.get("mat" + i);
                textureData.putString(tex, mat);
                Item matItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(mat));
                if (matItem == null) {
                    respond(exchange, 400, "{\"error\":\"unknown material item " + escape(mat) + "\"}");
                    return;
                }
                materials.add(matItem);
            }
            if (textureData.isEmpty()) {
                respond(exchange, 400, "{\"error\":\"no tex1/mat1 (etc) pairs given\"}");
                return;
            }

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, server.overworld());
                    if (colony == null) {
                        result.complete("ERROR: no colony with id " + colonyId);
                        return;
                    }
                    ICitizenData citizen = colony.getCitizenManager().getCivilian(citizenId);
                    if (citizen == null) {
                        result.complete("ERROR: no citizen with id " + citizenId);
                        return;
                    }
                    java.util.Optional<AbstractEntityCitizen> entityOpt = citizen.getEntity();
                    if (entityOpt.isEmpty()) {
                        result.complete("ERROR: citizen " + citizenId + " has no live entity right now");
                        return;
                    }
                    Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(blockId));
                    if (item == null) {
                        result.complete("ERROR: unknown item id " + blockId);
                        return;
                    }

                    InventoryCitizen inv = entityOpt.get().getInventoryCitizen();

                    // Check first that every material is available in the required
                    // quantity before consuming anything, so a partial shortage never
                    // leaves the citizen short some materials with nothing to show for it.
                    for (Item mat : materials) {
                        if (countItem(inv, mat) < count) {
                            result.complete("ERROR: not enough " + ForgeRegistries.ITEMS.getKey(mat)
                                    + " (need " + count + ")");
                            return;
                        }
                    }
                    for (Item mat : materials) {
                        extractItem(inv, mat, count);
                    }

                    ItemStack stack = new ItemStack(item, count);
                    stack.getOrCreateTag().put("textureData", textureData);

                    ItemStack remainder = stack;
                    int slots = inv.getSlots();
                    for (int slot = 0; slot < slots && !remainder.isEmpty(); slot++) {
                        remainder = inv.insertItem(slot, remainder, false);
                    }
                    int given = count - remainder.getCount();
                    result.complete("crafted " + given + "/" + count + " of " + blockId
                            + " from " + materials.size() + " material(s) for citizen " + citizenId);
                } catch (Exception e) {
                    LOGGER.error("giveTexturedBlock failed", e);
                    result.complete("ERROR: " + e);
                }
            });
            String outcome = result.get();
            if (outcome.startsWith("ERROR")) {
                respond(exchange, 500, "{\"error\":\"" + escape(outcome) + "\"}");
            } else {
                respond(exchange, 200, "{\"result\":\"" + escape(outcome) + "\"}");
            }
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    private static int countItem(InventoryCitizen inv, Item item) {
        int total = 0;
        int slots = inv.getSlots();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // Removes up to `amount` of `item` from the inventory, stopping early once
    // enough has been pulled. Caller must have already confirmed via countItem()
    // that at least `amount` exists, so this should never come up short.
    private static void extractItem(InventoryCitizen inv, Item item, int amount) {
        int remaining = amount;
        int slots = inv.getSlots();
        for (int slot = 0; slot < slots && remaining > 0; slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                inv.extractItem(slot, take, false);
                remaining -= take;
            }
        }
    }

    // GET /openRequests?x=..&y=..&z=..&citizenId=3
    // Lists what a citizen's building still needs, straight from the
    // colony's own request system (IBuilding.getOpenRequests), instead of
    // guessing from blueprint files. Each entry's `displayStack` is exactly
    // what /resolveRequest would hand back if asked to fulfill it - read it
    // to decide whether raw materials need to be gathered first.
    private void handleOpenRequests(HttpExchange exchange) throws IOException {
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            int z = Integer.parseInt(params.get("z"));
            int citizenId = Integer.parseInt(params.get("citizenId"));

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    BlockPos pos = new BlockPos(x, y, z);
                    IColony colony = IColonyManager.getInstance().getIColony(level, pos);
                    if (colony == null) {
                        result.complete("ERROR: no colony at " + x + "," + y + "," + z);
                        return;
                    }
                    IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
                    if (building == null) {
                        result.complete("ERROR: no building registered at " + x + "," + y + "," + z);
                        return;
                    }
                    java.util.Collection<IRequest<?>> requests = building.getOpenRequests(citizenId);
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    for (IRequest<?> req : requests) {
                        if (!first) sb.append(",");
                        first = false;
                        java.util.List<ItemStack> displayStacks = req.getDisplayStacks();
                        ItemStack stack = displayStacks.isEmpty() ? ItemStack.EMPTY : displayStacks.get(0);
                        CompoundTag textureData = !stack.isEmpty() ? stack.getTagElement("textureData") : null;
                        boolean textured = textureData != null;
                        sb.append("{")
                          .append("\"description\":\"").append(escape(req.getShortDisplayString().getString())).append("\",")
                          .append("\"item\":\"").append(stack.isEmpty() ? "" : escape(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())))).append("\",")
                          .append("\"count\":").append(stack.getCount()).append(",")
                          .append("\"textured\":").append(textured);
                        // For textured blocks, expose the required raw materials so callers
                        // can give them to the citizen before resolving the request.
                        if (textured) {
                            sb.append(",\"materials\":[");
                            boolean firstMat = true;
                            for (String texKey : textureData.getAllKeys()) {
                                String matId = textureData.getString(texKey);
                                if (!firstMat) sb.append(",");
                                firstMat = false;
                                sb.append("{\"item\":\"").append(escape(matId))
                                  .append("\",\"count\":").append(stack.getCount()).append("}");
                            }
                            sb.append("]");
                        }
                        sb.append("}");
                    }
                    sb.append("]");
                    result.complete(sb.toString());
                } catch (Exception e) {
                    LOGGER.error("openRequests failed", e);
                    result.complete("ERROR: " + e);
                }
            });
            String outcome = result.get();
            if (outcome.startsWith("ERROR")) {
                respond(exchange, 500, "{\"error\":\"" + escape(outcome) + "\"}");
            } else {
                respond(exchange, 200, outcome);
            }
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // POST /resolveRequest?x=..&y=..&z=..&citizenId=3
    // Fulfills the citizen's oldest open request at this building using the
    // request system's own IBuilding.overruleNextOpenRequestOfCitizenWithStack,
    // which is what GM/creative-resolve style overrides use internally - but
    // rather than conjuring it for free, this is an equivalent-exchange: if
    // the request's own display stack carries Domum Ornamentum "textureData"
    // (a Framed decorative block), we first check the citizen is actually
    // holding one of each named raw material and consume them; plain
    // material requests are granted as-is (creativeresolve in the server
    // config already does this same thing automatically, so this endpoint
    // mainly exists for the textured-block case and as an explicit,
    // LLM-triggerable alternative to waiting on creativeresolve's own retry
    // timing).
    private void handleResolveRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            int z = Integer.parseInt(params.get("z"));
            int citizenId = Integer.parseInt(params.get("citizenId"));

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    BlockPos pos = new BlockPos(x, y, z);
                    IColony colony = IColonyManager.getInstance().getIColony(level, pos);
                    if (colony == null) {
                        result.complete("ERROR: no colony at " + x + "," + y + "," + z);
                        return;
                    }
                    IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
                    if (building == null) {
                        result.complete("ERROR: no building registered at " + x + "," + y + "," + z);
                        return;
                    }
                    ICitizenData citizen = colony.getCitizenManager().getCivilian(citizenId);
                    if (citizen == null) {
                        result.complete("ERROR: no citizen with id " + citizenId);
                        return;
                    }
                    java.util.Collection<IRequest<?>> requests = building.getOpenRequests(citizenId);
                    if (requests.isEmpty()) {
                        result.complete("ERROR: no open requests for citizen " + citizenId + " at this building");
                        return;
                    }
                    IRequest<?> req = requests.iterator().next();
                    java.util.List<ItemStack> displayStacks = req.getDisplayStacks();
                    if (displayStacks.isEmpty()) {
                        result.complete("ERROR: request has no display stack to fulfill with");
                        return;
                    }
                    ItemStack stack = displayStacks.get(0);

                    CompoundTag textureData = stack.getTagElement("textureData");
                    if (textureData != null) {
                        java.util.Optional<AbstractEntityCitizen> entityOpt = citizen.getEntity();
                        if (entityOpt.isEmpty()) {
                            result.complete("ERROR: citizen " + citizenId + " has no live entity right now");
                            return;
                        }
                        InventoryCitizen inv = entityOpt.get().getInventoryCitizen();
                        java.util.List<Item> materials = new java.util.ArrayList<>();
                        for (String key : textureData.getAllKeys()) {
                            String matId = textureData.getString(key);
                            Item matItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(matId));
                            if (matItem == null) {
                                result.complete("ERROR: unknown material item " + matId);
                                return;
                            }
                            materials.add(matItem);
                        }
                        for (Item mat : materials) {
                            if (countItem(inv, mat) < stack.getCount()) {
                                result.complete("ERROR: not enough " + ForgeRegistries.ITEMS.getKey(mat)
                                        + " (need " + stack.getCount() + ") to craft " + req.getShortDisplayString().getString());
                                return;
                            }
                        }
                        for (Item mat : materials) {
                            extractItem(inv, mat, stack.getCount());
                        }
                        // overruleNextOpenRequestOfCitizenWithStack only changes request
                        // state to OVERRULED - it does not put the item in the citizen's
                        // inventory. The builder AI won't exit NEEDS_ITEM until the
                        // finished textured block physically arrives in its inventory.
                        ItemStack toDeliver = stack.copy();
                        for (int slot = 0; slot < inv.getSlots() && !toDeliver.isEmpty(); slot++) {
                            toDeliver = inv.insertItem(slot, toDeliver, false);
                        }
                    }

                    boolean resolved = building.overruleNextOpenRequestOfCitizenWithStack(citizen, stack);
                    result.complete(resolved
                            ? "resolved request for citizen " + citizenId + ": " + req.getShortDisplayString().getString()
                            : "ERROR: overrule returned false (request may have already been resolved)");
                } catch (Exception e) {
                    LOGGER.error("resolveRequest failed", e);
                    result.complete("ERROR: " + e);
                }
            });
            String outcome = result.get();
            if (outcome.startsWith("ERROR")) {
                respond(exchange, 500, "{\"error\":\"" + escape(outcome) + "\"}");
            } else {
                respond(exchange, 200, "{\"result\":\"" + escape(outcome) + "\"}");
            }
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // POST /clearCitizenInventory?colonyId=1&citizenId=2
    // Extracts every item from the citizen's personal inventory and drops them
    // in the world at the citizen's feet (or discards if the entity isn't loaded).
    // Used to unblock citizens whose inventory was filled by erroneous supply runs.
    private void handleClearCitizenInventory(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int colonyId = Integer.parseInt(params.get("colonyId"));
            int citizenId = Integer.parseInt(params.get("citizenId"));

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, server.overworld());
                    if (colony == null) { result.complete("ERROR: no colony " + colonyId); return; }
                    ICitizenData citizen = colony.getCitizenManager().getCivilian(citizenId);
                    if (citizen == null) { result.complete("ERROR: no citizen " + citizenId); return; }
                    java.util.Optional<AbstractEntityCitizen> entityOpt = citizen.getEntity();
                    if (entityOpt.isEmpty()) { result.complete("ERROR: citizen has no live entity"); return; }
                    InventoryCitizen inv = entityOpt.get().getInventoryCitizen();
                    int cleared = 0;
                    for (int slot = 0; slot < inv.getSlots(); slot++) {
                        if (!inv.getStackInSlot(slot).isEmpty()) {
                            inv.extractItem(slot, Integer.MAX_VALUE, false);
                            cleared++;
                        }
                    }
                    result.complete("cleared " + cleared + " slot(s) for citizen " + citizenId);
                } catch (Exception e) {
                    LOGGER.error("clearCitizenInventory failed", e);
                    result.complete("ERROR: " + e);
                }
            });
            String outcome = result.get();
            if (outcome.startsWith("ERROR")) {
                respond(exchange, 500, "{\"error\":\"" + escape(outcome) + "\"}");
            } else {
                respond(exchange, 200, "{\"result\":\"" + escape(outcome) + "\"}");
            }
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // Buildings that require university research before they can be built.
    // Derived from data/minecolonies/researches/effects/blockhu*.json entries
    // in minecolonies-*.jar. Effect IDs follow the pattern
    // minecolonies:effects/<block_registry_path>; effect strength > 0 = unlocked.
    private static final java.util.Set<String> RESEARCH_GATED_BUILDINGS =
        java.util.Set.of(
            "blockhutalchemist", "blockhutarchery", "blockhutbarracks",
            "blockhutblacksmith", "blockhutcombatacademy", "blockhutcomposter",
            "blockhutconcretemixer", "blockhutcrusher", "blockhutdyer",
            "blockhutenchanter", "blockhutfletcher", "blockhutflorist",
            "blockhutglassblower", "blockhutgraveyard", "blockhuthospital",
            "blockhutlibrary", "blockhutmechanic", "blockhutmysticalsite",
            "blockhutnetherworker", "blockhutplantation", "blockhutsawmill",
            "blockhutschool", "blockhutsifter", "blockhutsmeltery",
            "blockhutstonemason", "blockhutstonesmeltery"
        );

    // type name (the part of the block id after "blockhut") -> blueprint path
    // relative to a structure pack's root, e.g. "blockhutbuilder" ->
    // "fundamentals/builder1.blueprint". Discovered by listing the Colonial
    // pack's bundled blueprints inside minecolonies-*.jar; folders are not
    // consistent (fundamentals/, craftsmanship/<sub>/, military/, etc.) so
    // this can't be derived from a simple naming rule - it's looked up here
    // instead of resolved dynamically, since Structurize's Blueprint class
    // overrides a vanilla method (getBlockState) whose name only resolves
    // correctly when the dependency jar is properly deobfuscated, which
    // `fg.deobf(files(...))` does not actually do for plain local file refs.
    private static final java.util.Map<String, String> BLUEPRINT_PATHS = new java.util.HashMap<>();
    static {
        // Fundamentals
        BLUEPRINT_PATHS.put("townhall",      "fundamentals/townhall1.blueprint");
        BLUEPRINT_PATHS.put("builder",       "fundamentals/builder1.blueprint");
        BLUEPRINT_PATHS.put("citizen",       "fundamentals/house1.blueprint");
        BLUEPRINT_PATHS.put("hospital",      "fundamentals/hospital1.blueprint");
        BLUEPRINT_PATHS.put("tavern",        "fundamentals/tavern1.blueprint");
        BLUEPRINT_PATHS.put("lumberjack",    "fundamentals/forester1.blueprint");
        BLUEPRINT_PATHS.put("miner",         "fundamentals/mine1.blueprint");
        BLUEPRINT_PATHS.put("kitchen",       "fundamentals/restaurant1.blueprint");
        // Craftsmanship / Storage
        BLUEPRINT_PATHS.put("warehouse",     "craftsmanship/storage/warehouse1.blueprint");
        BLUEPRINT_PATHS.put("deliveryman",   "craftsmanship/storage/courier1.blueprint");
        // Craftsmanship / Carpentry
        BLUEPRINT_PATHS.put("sawmill",       "craftsmanship/carpentry/sawmill1.blueprint");
        BLUEPRINT_PATHS.put("fletcher",      "craftsmanship/carpentry/fletcher1.blueprint");
        // Craftsmanship / Metallurgy
        BLUEPRINT_PATHS.put("blacksmith",    "craftsmanship/metallurgy/blacksmith1.blueprint");
        BLUEPRINT_PATHS.put("mechanic",      "craftsmanship/metallurgy/mechanic1.blueprint");
        BLUEPRINT_PATHS.put("smeltery",      "craftsmanship/metallurgy/smeltery1.blueprint");
        // Craftsmanship / Masonry
        BLUEPRINT_PATHS.put("stonemason",    "craftsmanship/masonry/stonemason1.blueprint");
        BLUEPRINT_PATHS.put("stonesmeltery", "craftsmanship/masonry/stonesmeltery1.blueprint");
        BLUEPRINT_PATHS.put("crusher",       "craftsmanship/masonry/crusher1.blueprint");
        BLUEPRINT_PATHS.put("sifter",        "craftsmanship/masonry/sifter1.blueprint");
        // Craftsmanship / Luxury
        BLUEPRINT_PATHS.put("baker",         "craftsmanship/luxury/bakery1.blueprint");
        BLUEPRINT_PATHS.put("cook",          "craftsmanship/luxury/cookery1.blueprint");
        BLUEPRINT_PATHS.put("dyer",          "craftsmanship/luxury/dyer1.blueprint");
        BLUEPRINT_PATHS.put("glassblower",   "craftsmanship/luxury/glassblower1.blueprint");
        BLUEPRINT_PATHS.put("concretemixer", "craftsmanship/luxury/concretemixer1.blueprint");
        BLUEPRINT_PATHS.put("alchemist",     "craftsmanship/luxury/alchemisttower1.blueprint");
        // Agriculture / Horticulture
        BLUEPRINT_PATHS.put("farmer",        "agriculture/horticulture/farm1.blueprint");
        BLUEPRINT_PATHS.put("composter",     "agriculture/horticulture/composter1.blueprint");
        BLUEPRINT_PATHS.put("florist",       "agriculture/horticulture/flowershop1.blueprint");
        BLUEPRINT_PATHS.put("plantation",    "agriculture/horticulture/plantation1.blueprint");
        // Agriculture / Husbandry
        BLUEPRINT_PATHS.put("fisherman",     "agriculture/husbandry/fisher1.blueprint");
        BLUEPRINT_PATHS.put("beekeeper",     "agriculture/husbandry/apiary1.blueprint");
        BLUEPRINT_PATHS.put("chickenherder", "agriculture/husbandry/chickenfarmer1.blueprint");
        BLUEPRINT_PATHS.put("cowboy",        "agriculture/husbandry/cowhand1.blueprint");
        BLUEPRINT_PATHS.put("shepherd",      "agriculture/husbandry/shepherd1.blueprint");
        BLUEPRINT_PATHS.put("swineherder",   "agriculture/husbandry/swineherd1.blueprint");
        BLUEPRINT_PATHS.put("rabbithutch",   "agriculture/husbandry/rabbithutch1.blueprint");
        // Military
        BLUEPRINT_PATHS.put("guardtower",    "military/guardtower1.blueprint");
        BLUEPRINT_PATHS.put("barracks",      "military/barracks1.blueprint");
        BLUEPRINT_PATHS.put("barrackstower", "military/barrackstower1.blueprint");
        BLUEPRINT_PATHS.put("archery",       "military/archery1.blueprint");
        BLUEPRINT_PATHS.put("combatacademy", "military/combatacademy1.blueprint");
        BLUEPRINT_PATHS.put("gatehouse",     "military/gatehouse1.blueprint");
        // Education
        BLUEPRINT_PATHS.put("library",       "education/library1.blueprint");
        BLUEPRINT_PATHS.put("school",        "education/school1.blueprint");
        BLUEPRINT_PATHS.put("university",    "education/university1.blueprint");
        // Mystic
        BLUEPRINT_PATHS.put("enchanter",     "mystic/enchanterstower1.blueprint");
        BLUEPRINT_PATHS.put("graveyard",     "mystic/graveyard1.blueprint");
        BLUEPRINT_PATHS.put("netherworker",  "mystic/nethermine1.blueprint");
    }

    // The BLUEPRINT_PATHS table was derived specifically from the "Colonial"
    // pack's bundled blueprints, so pin to that pack explicitly rather than
    // trusting StructurePacks.getSelectedPack() (which is whatever pack
    // happened to register/select first and varies across server restarts -
    // other packs may not share the same folder layout for every building).
    private static final String PINNED_PACK_NAME = "Colonial";

    private StructurePackMeta getPinnedPack() {
        StructurePackMeta pack = StructurePacks.getStructurePack(PINNED_PACK_NAME);
        if (pack != null) {
            return pack;
        }
        StructurePacks.ensureSelectedPack();
        pack = StructurePacks.getSelectedPack();
        if (pack != null) {
            return pack;
        }
        java.util.Collection<StructurePackMeta> metas = StructurePacks.getPackMetas();
        return metas.isEmpty() ? null : metas.iterator().next();
    }

    private String placeOnServerThread(int x, int y, int z, String blockId) {
        ServerLevel level = server.overworld();
        ResourceLocation rl = new ResourceLocation(blockId);
        Block block = ForgeRegistries.BLOCKS.getValue(rl);
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            return "ERROR: unknown block id " + blockId;
        }
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        BlockPos pos = new BlockPos(x, y, z);

        // Footprint collision check: reject if pos falls inside any existing building's
        // bounding box. MineColonies has no such check itself - the Builder will silently
        // demolish normal blocks of any overlapping building during construction.
        IColony nearbyColony = IColonyManager.getInstance().getIColony(level, pos);
        if (nearbyColony != null) {
            for (IBuilding existingBld : nearbyColony.getServerBuildingManager().getBuildings().values()) {
                // Note: no same-position exception here. Placing a different building type
                // at an existing building's anchor block silently destroys it in MineColonies
                // and leaves the replacement in a broken state where requestUpgrade() no
                // longer creates work orders. Treat same-anchor as a footprint collision.
                try {
                    if (existingBld.isInBuilding(pos)) {
                        ResourceLocation existKey = ForgeRegistries.BLOCKS.getKey(
                            level.getBlockState(existingBld.getPosition()).getBlock());
                        String existType = existKey != null ? existKey.getPath() : "unknown";
                        return "ERROR: (" + x + "," + y + "," + z + ") is inside existing building "
                            + existType + " at " + existingBld.getPosition()
                            + " - choose a position outside its footprint";
                    }
                } catch (Exception ignored) {
                    // getCorners() may not be initialised for buildings with no work order yet
                }
            }
        }
        BlockState state = block.defaultBlockState();

        level.setBlockAndUpdate(pos, state);

        // The blueprint pack/path MUST be set on the tile entity before
        // setPlacedBy runs below - setPlacedBy is what triggers MineColonies'
        // own building registration internally, and the registered IBuilding
        // object caches its own copy of the blueprint path at that moment.
        // Setting it afterwards only updates the tile entity's copy, leaving
        // the IBuilding with a stale empty path that breaks requestUpgrade()
        // later with a StringIndexOutOfBoundsException ("Failed to get
        // rotation of building ... with path: ").
        String blueprintNote = "";
        BlockEntity tileEntity = level.getBlockEntity(pos);
        if (tileEntity instanceof TileEntityColonyBuilding) {
            String typeName = rl.getPath().replaceFirst("^blockhut", "");
            String path = BLUEPRINT_PATHS.get(typeName);
            if (path != null) {
                StructurePackMeta pack = getPinnedPack();
                if (pack != null) {
                    ((TileEntityColonyBuilding) tileEntity).setStructurePack(pack);
                    ((TileEntityColonyBuilding) tileEntity).setBlueprintPath(path);
                    blueprintNote = " (blueprint " + pack.getName() + "/" + path + ")";
                }
            } else {
                blueprintNote = " (no known blueprint path for type '" + typeName + "', placed without one)";
            }
        }

        FakePlayer fakePlayer = new FakePlayer(level, AI_PROFILE);
        ItemStack stack = item != null ? new ItemStack(item, 1) : ItemStack.EMPTY;
        // setPlacedBy is a standard Block API hook; MineColonies overrides it on
        // its hut blocks to register the building/colony, even though we never
        // imported its (obfuscated) class - virtual dispatch finds it at runtime.
        block.setPlacedBy(level, pos, state, fakePlayer, stack);

        // Colony territory check: after placement, verify the block ended up inside
        // the colony's claimed territory. MineColonies limits citizen pathfinding to
        // within colony-claimed chunks; a building registered outside the territory
        // will have an assigned worker but that worker will never actually move there.
        // initialColonySize defaults to 4 chunks (64 blocks) at townhall level 0.
        String territoryWarning = "";
        IColony placedInColony = findColonyAt(level, pos);
        if (placedInColony != null && !placedInColony.isCoordInColony(level, pos)) {
            BlockPos center = placedInColony.getCenter();
            double dist = Math.sqrt(Math.pow(pos.getX() - center.getX(), 2)
                + Math.pow(pos.getZ() - center.getZ(), 2));
            territoryWarning = " WARNING: position is outside colony claimed territory"
                + " (dist=" + (int) dist + " from center; workers assigned here may not move)";
        }

        return "placed " + blockId + " at " + x + "," + y + "," + z + blueprintNote + territoryWarning;
    }

    // Robust colony lookup: tries the standard position-based lookup first, then
    // falls back to scanning all colonies for one that has a building registered
    // at the given position. The fallback iterates getBuildings() directly rather
    // than calling getBuilding(pos) because getBuilding() may use a different
    // key-lookup path than the map's own containsKey, causing false negatives when
    // the colony was auto-created via setPlacedBy rather than the normal founding flow.
    private IColony findColonyAt(ServerLevel level, BlockPos pos) {
        IColony colony = IColonyManager.getInstance().getIColony(level, pos);
        if (colony != null) return colony;
        for (IColony c : IColonyManager.getInstance().getAllColonies()) {
            if (!c.getDimension().equals(level.dimension())) continue;
            if (c.getServerBuildingManager().getBuildings().containsKey(pos)) return c;
        }
        return null;
    }

    private static java.util.Map<String, String> parseQuery(String query) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            map.put(pair.substring(0, idx), pair.substring(idx + 1));
        }
        return map;
    }

    private static String escape(String s) {
        return s.replace("\"", "'").replace("\n", " ");
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
