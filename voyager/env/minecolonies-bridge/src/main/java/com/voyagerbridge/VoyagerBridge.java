package com.voyagerbridge;

import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
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

        StructurePacks.ensureSelectedPack();
        StructurePackMeta pack = StructurePacks.getSelectedPack();
        if (pack == null) {
            java.util.Collection<StructurePackMeta> metas = StructurePacks.getPackMetas();
            if (metas.isEmpty()) {
                return "ERROR: no structure packs registered";
            }
            pack = metas.iterator().next();
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

        return "placed " + blockId + " at " + x + "," + y + "," + z;
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
