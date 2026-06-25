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
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
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
        hut.setStructurePack(pack);
        hut.setBlueprintPath("fundamentals/townhall1.blueprint");

        Player fakePlayer = new FakePlayer(level, AI_PROFILE);
        IColony existingOwned = IColonyManager.getInstance().getIColonyByOwner(level, fakePlayer);
        if (existingOwned != null) {
            return "ERROR: VoyagerAI already owns colony " + existingOwned.getID();
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
                      .append("\"citizens\":").append(c.getCitizenManager().getCurrentCitizenCount())
                      .append("}");
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
                    if (building.isPendingConstruction()) {
                        result.complete("already has a pending work order");
                        return;
                    }
                    Player fakePlayer = new FakePlayer(level, AI_PROFILE);
                    building.requestUpgrade(fakePlayer, pos);
                    result.complete("requested build for building at " + x + "," + y + "," + z);
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
        BLUEPRINT_PATHS.put("townhall", "fundamentals/townhall1.blueprint");
        BLUEPRINT_PATHS.put("builder", "fundamentals/builder1.blueprint");
        BLUEPRINT_PATHS.put("citizen", "fundamentals/house1.blueprint");
        BLUEPRINT_PATHS.put("forester", "fundamentals/forester1.blueprint");
        BLUEPRINT_PATHS.put("hospital", "fundamentals/hospital1.blueprint");
        BLUEPRINT_PATHS.put("tavern", "fundamentals/tavern1.blueprint");
        BLUEPRINT_PATHS.put("warehouse", "craftsmanship/storage/warehouse1.blueprint");
        BLUEPRINT_PATHS.put("deliveryman", "craftsmanship/storage/courier1.blueprint");
        BLUEPRINT_PATHS.put("sawmill", "craftsmanship/carpentry/sawmill1.blueprint");
        BLUEPRINT_PATHS.put("blacksmith", "craftsmanship/metallurgy/blacksmith1.blueprint");
        BLUEPRINT_PATHS.put("stonemason", "craftsmanship/masonry/stonemason1.blueprint");
        BLUEPRINT_PATHS.put("library", "education/library1.blueprint");
        BLUEPRINT_PATHS.put("school", "education/school1.blueprint");
        BLUEPRINT_PATHS.put("farmer", "agriculture/horticulture/farm1.blueprint");
        BLUEPRINT_PATHS.put("fisherman", "agriculture/husbandry/fisher1.blueprint");
        BLUEPRINT_PATHS.put("guardtower", "military/guardtower1.blueprint");
        BLUEPRINT_PATHS.put("barracks", "military/barracks1.blueprint");
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
        BlockState state = block.defaultBlockState();

        level.setBlockAndUpdate(pos, state);

        FakePlayer fakePlayer = new FakePlayer(level, AI_PROFILE);
        ItemStack stack = item != null ? new ItemStack(item, 1) : ItemStack.EMPTY;
        // setPlacedBy is a standard Block API hook; MineColonies overrides it on
        // its hut blocks to register the building/colony, even though we never
        // imported its (obfuscated) class - virtual dispatch finds it at runtime.
        block.setPlacedBy(level, pos, state, fakePlayer, stack);

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

        return "placed " + blockId + " at " + x + "," + y + "," + z + blueprintNote;
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
