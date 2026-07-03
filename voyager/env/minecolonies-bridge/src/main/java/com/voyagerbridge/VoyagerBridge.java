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
import net.minecraftforge.event.TickEvent;
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

    // Tick rate multiplier, set via POST /tickrate?multiplier=N.
    private static volatile int tickMultiplier = 1;

    public static int getTickMultiplier() { return tickMultiplier; }

    // Cached reflection access to MinecraftServer.nextTickTime (SRG: f_129726_).
    // Set to non-null once resolved; null means "not found or not yet searched."
    private static volatile java.lang.reflect.Field nextTickTimeField = null;
    private static volatile boolean nextTickTimeSearched = false;

    private static java.lang.reflect.Field resolveNextTickTimeField(MinecraftServer srv) {
        if (nextTickTimeSearched) return nextTickTimeField;
        nextTickTimeSearched = true;
        // Search the class hierarchy for a long field matching either name.
        Class<?> cls = srv.getClass();
        while (cls != null) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (f.getType() != long.class) continue;
                String n = f.getName();
                if ("nextTickTime".equals(n) || "f_129726_".equals(n)) {
                    f.setAccessible(true);
                    nextTickTimeField = f;
                    return f;
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // Called at the END of each server tick, before waitUntilNextTick() sleeps.
    // When multiplier > 1, we rewind nextTickTime so the upcoming managedBlock()
    // (which adds 50ms then loops until time is past) exits immediately, giving
    // approximately mult × 20 TPS (bounded by actual tick processing cost).
    //
    // IMPORTANT: nextTickTime uses Util.getMillis() = System.nanoTime()/1_000_000L
    // (milliseconds since system boot), NOT System.currentTimeMillis() (Unix epoch).
    // Using currentTimeMillis() would set nextTickTime ~58 years into the future,
    // causing the tick loop to block indefinitely.
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        keepColoniesActive();
        int mult = tickMultiplier;
        if (mult <= 1 || server == null) return;
        java.lang.reflect.Field f = resolveNextTickTimeField(server);
        if (f != null) {
            try {
                // At Phase.START, the previous managedBlock has just set nextTickTime
                // to prevNextTickTime + 50 (and then waited for it).
                // nextTickTime ≈ Util.getMillis() right now.
                // We subtract (50 - 50/mult) so that upcoming managedBlock's += 50
                // targets only 50/mult ms in the future instead of 50ms.
                // Util.getMillis() == System.nanoTime() / 1_000_000L (boot-time base)
                long cur = f.getLong(server);
                long adj = 50L - 50L / mult;  // for mult=10: 45ms
                f.setLong(server, cur - adj);
            } catch (Exception ignored) {}
        }
        suppressFloatingKicks();
        suppressLoginTimeouts();
    }

    // --- Unmanned-colony keep-alive ---
    //
    // Colony.updateState() drops a colony to UNLOADED as soon as no player is
    // near (closeSubscribers empty, no important colony player online). In
    // UNLOADED only worldTickUnloaded runs: tickWorkManager (binds claimed
    // work orders to builder huts), checkDayTime (the colony day counter that
    // day-based schedulers like the farmer's field rotation depend on),
    // tickRequests and worldTickSlow (building ticks) all stop. Entities keep
    // walking (forceloaded chunks), so the colony LOOKS alive while its brain
    // is off - builders wander on leisure forever and the day freezes.
    // This experiment runs unmanned, so while nobody is online we force the
    // state machine back to ACTIVE (both the state field and the cached
    // transition list must be swapped - see BasicStateMachine.transitionToNext).
    // updateState() flips it back every 100 machine ticks; re-forcing every 20
    // server ticks keeps the colony effectively always ACTIVE.
    private int keepActiveCounter = 0;
    private static volatile java.lang.reflect.Field colonyStateMachineField = null;
    private static volatile java.lang.reflect.Field smStateField = null;
    private static volatile java.lang.reflect.Field smTransitionMapField = null;
    private static volatile java.lang.reflect.Field smCurrentTransitionsField = null;

    private void keepColoniesActive() {
        if (server == null || ++keepActiveCounter < 20) return;
        keepActiveCounter = 0;
        if (server.getPlayerList().getPlayerCount() > 0) return; // vanilla logic is fine with players on
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                if (!(colony instanceof com.minecolonies.core.colony.Colony)) continue;
                if (colonyStateMachineField == null) {
                    colonyStateMachineField = com.minecolonies.core.colony.Colony.class
                            .getDeclaredField("colonyStateMachine");
                    colonyStateMachineField.setAccessible(true);
                }
                Object sm = colonyStateMachineField.get(colony);
                if (smStateField == null) {
                    Class<?> cls = sm.getClass();
                    while (cls != null && smStateField == null) {
                        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                            if ("state".equals(f.getName())) smStateField = f;
                            else if ("transitionMap".equals(f.getName())) smTransitionMapField = f;
                            else if ("currentStateTransitions".equals(f.getName())) smCurrentTransitionsField = f;
                        }
                        cls = cls.getSuperclass();
                    }
                    if (smStateField == null || smTransitionMapField == null || smCurrentTransitionsField == null) {
                        LOGGER.warn("keepColoniesActive: state machine fields not found (state={}, map={}, cur={})",
                                smStateField != null, smTransitionMapField != null, smCurrentTransitionsField != null);
                        return;
                    }
                    smStateField.setAccessible(true);
                    smTransitionMapField.setAccessible(true);
                    smCurrentTransitionsField.setAccessible(true);
                }
                Object cur = smStateField.get(sm);
                Object active = com.minecolonies.api.colony.ColonyState.ACTIVE;
                if (cur != active) {
                    java.util.Map<?, ?> map = (java.util.Map<?, ?>) smTransitionMapField.get(sm);
                    Object activeTransitions = map.get(active);
                    if (activeTransitions != null) {
                        smCurrentTransitionsField.set(sm, activeTransitions);
                        smStateField.set(sm, active);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("keepColoniesActive failed", e);
        }
    }

    // --- Login timeout suppression while tick-accelerated ---
    //
    // Same class of problem as the flying kick below: ServerLoginPacketListenerImpl
    // .tick() increments its `tick` counter (SRG f_10020_) once per SERVER tick and
    // disconnects with "Took too long to log in" at 600 ticks (30s at 20 TPS). At
    // 10x the whole login handshake budget shrinks to ~3 real seconds, which a real
    // client cannot meet (observed: unkositai kicked at 2026-07-02 11:59 while
    // multiplier=10). Reset the counter every tick while accelerated.
    private static volatile java.lang.reflect.Field loginTickField = null;
    private static volatile boolean loginTickSearched = false;

    private void suppressLoginTimeouts() {
        try {
            if (server.getConnection() == null) return;
            for (net.minecraft.network.Connection conn : server.getConnection().getConnections()) {
                net.minecraft.network.PacketListener listener = conn.getPacketListener();
                if (!(listener instanceof net.minecraft.server.network.ServerLoginPacketListenerImpl)) continue;
                if (!loginTickSearched) {
                    loginTickSearched = true;
                    Class<?> cls = listener.getClass();
                    while (cls != null) {
                        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                            String n = f.getName();
                            if (("tick".equals(n) || "f_10020_".equals(n)) && f.getType() == int.class) {
                                f.setAccessible(true);
                                loginTickField = f;
                            }
                        }
                        cls = cls.getSuperclass();
                    }
                }
                if (loginTickField != null) loginTickField.setInt(listener, 0);
            }
        } catch (Exception ignored) {}
    }

    // --- Anti-"flying" kick suppression while tick-accelerated ---
    //
    // ServerGamePacketListenerImpl.tick() (SRG m_9933_) runs once per SERVER
    // tick (not once per network packet). It increments aboveGroundTickCount
    // (SRG f_9737_) whenever clientIsFloating (f_9736_) is true, and kicks the
    // player with "multiplayer.disconnect.flying" once the counter exceeds 80
    // (vanilla source, confirmed via javap on the SRG server jar).
    //
    // At normal 20 TPS, 80 ticks = 4 real seconds - plenty of slack for a
    // legitimate jump's client/server position reconciliation. Our tick
    // acceleration hack (above) speeds up server-tick wall-clock cadence
    // without speeding up the connected client (which still sends movement
    // packets at real-time cadence), so at 10x that same 80-tick budget burns
    // in ~0.4 real seconds - not enough time for a normal jump's correction
    // packet to arrive, so every jump gets kicked. This is not fixable by
    // correcting a logic error; it's an unavoidable side effect of racing
    // server ticks ahead of real-time network I/O for connected players.
    // Mitigation: while multiplier > 1, reset the counters every tick so the
    // vanilla check never accumulates enough to fire. This disables that one
    // anti-cheat check during acceleration; acceptable for a private
    // research server where the "opponent" is our own client, not a griefer.
    private static volatile java.lang.reflect.Field clientIsFloatingField = null;
    private static volatile java.lang.reflect.Field aboveGroundTickCountField = null;
    private static volatile java.lang.reflect.Field clientVehicleIsFloatingField = null;
    private static volatile java.lang.reflect.Field aboveGroundVehicleTickCountField = null;
    private static volatile boolean floatingFieldsSearched = false;

    private static void resolveFloatingFields(Object connection) {
        if (floatingFieldsSearched) return;
        floatingFieldsSearched = true;
        try {
            Class<?> cls = connection.getClass();
            while (cls != null) {
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    String n = f.getName();
                    if (("clientIsFloating".equals(n) || "f_9736_".equals(n)) && f.getType() == boolean.class) {
                        f.setAccessible(true);
                        clientIsFloatingField = f;
                    } else if (("aboveGroundTickCount".equals(n) || "f_9737_".equals(n)) && f.getType() == int.class) {
                        f.setAccessible(true);
                        aboveGroundTickCountField = f;
                    } else if (("clientVehicleIsFloating".equals(n) || "f_9738_".equals(n)) && f.getType() == boolean.class) {
                        f.setAccessible(true);
                        clientVehicleIsFloatingField = f;
                    } else if (("aboveGroundVehicleTickCount".equals(n) || "f_9739_".equals(n)) && f.getType() == int.class) {
                        f.setAccessible(true);
                        aboveGroundVehicleTickCountField = f;
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception ignored) {}
    }

    private void suppressFloatingKicks() {
        if (server.getPlayerList() == null) return;
        for (net.minecraft.server.level.ServerPlayer sp : server.getPlayerList().getPlayers()) {
            Object connection = sp.connection;
            if (connection == null) continue;
            resolveFloatingFields(connection);
            try {
                if (clientIsFloatingField != null) clientIsFloatingField.setBoolean(connection, false);
                if (aboveGroundTickCountField != null) aboveGroundTickCountField.setInt(connection, 0);
                if (clientVehicleIsFloatingField != null) clientVehicleIsFloatingField.setBoolean(connection, false);
                if (aboveGroundVehicleTickCountField != null) aboveGroundVehicleTickCountField.setInt(connection, 0);
            } catch (Exception ignored) {}
        }
    }

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
            httpServer.createContext("/placeNext", this::handlePlaceNext);
            httpServer.createContext("/suggestPosition", this::handleSuggestPosition);
            httpServer.createContext("/tickrate", this::handleTickrate);
            httpServer.createContext("/debugTick", this::handleDebugTick);
            httpServer.createContext("/debugFootprints", this::handleDebugFootprints);
            httpServer.createContext("/removeBuilding", this::handleRemoveBuilding);
            httpServer.createContext("/rebalanceWorkOrders", this::handleRebalanceWorkOrders);
            httpServer.createContext("/debugWorkOrders", this::handleDebugWorkOrders);
            httpServer.createContext("/setMenu", this::handleSetMenu);
            httpServer.createContext("/stockRestaurant", this::handleStockRestaurant);
            httpServer.createContext("/neededResources", this::handleNeededResources);
            httpServer.createContext("/fillBuilderResources", this::handleFillBuilderResources);
            httpServer.createContext("/setFieldSeed", this::handleSetFieldSeed);
            httpServer.createContext("/fields", this::handleFields);
            httpServer.createContext("/debugCitizenAI", this::handleDebugCitizenAI);
            httpServer.createContext("/debugFarm", this::handleDebugFarm);
            httpServer.createContext("/debugBuilder", this::handleDebugBuilder);
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
            try { existingOwned.setName(colonyName); } catch (Exception ignored) {}
            String packName = existingOwned.getStructurePack();
            return "founded colony " + existingOwned.getID() + " (" + colonyName + ")"
                + (packName != null && !packName.isEmpty() ? " with pack " + packName : "");
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
            // Phase 1 (server thread): ensure max cap and force-load colony chunks.
            // setChunkForced schedules a ticket that ChunkMap.tick() processes on the
            // same or next server tick; isPositionEntityTicking won't be true until
            // after that tick completes. We run phase 1, sleep 100ms on the HTTP
            // thread (≈2 server ticks), then run phase 2 to do the actual spawn.
            java.util.concurrent.CompletableFuture<Void> phase1 = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, server.overworld());
                    if (colony == null) {
                        result.complete("ERROR: no colony with id " + colonyId);
                        phase1.complete(null);
                        return;
                    }
                    int before = colony.getCitizenManager().getCurrentCitizenCount();
                    int cap = colony.getCitizenManager().getMaxCitizens();
                    if (cap <= before) {
                        colony.getCitizenManager().setMaxCitizens(before + 1);
                    }
                    ServerLevel overworld = server.overworld();
                    BlockPos center = colony.getCenter();
                    // Force-load a 3×3 chunk area around town hall so entity ticking
                    // is enabled even with no player connected.
                    int cx = center.getX() >> 4;
                    int cz = center.getZ() >> 4;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            overworld.setChunkForced(cx + dx, cz + dz, true);
                        }
                    }
                    phase1.complete(null);
                } catch (Exception e) {
                    LOGGER.error("spawnCitizen phase1 failed", e);
                    result.complete("ERROR: " + e);
                    phase1.complete(null);
                }
            });
            phase1.get(); // wait for phase 1 on HTTP thread
            Thread.sleep(120); // 2-3 ticks for ChunkMap to process the tickets
            // Phase 2 (server thread): spawn the citizen now that ticking is active.
            server.execute(() -> {
                try {
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, server.overworld());
                    if (colony == null || result.isDone()) { result.complete("ERROR: colony gone"); return; }
                    int before = colony.getCitizenManager().getCurrentCitizenCount();
                    ServerLevel overworld = server.overworld();
                    BlockPos center = colony.getCenter();
                    java.util.List<BlockPos> spawnPos = new java.util.ArrayList<>();
                    spawnPos.add(center);
                    colony.getCitizenManager().spawnOrCreateCivilian(null, overworld, spawnPos, true);
                    int after = colony.getCitizenManager().getCurrentCitizenCount();
                    result.complete("citizen count " + before + " -> " + after);
                } catch (Exception e) {
                    LOGGER.error("spawnCitizen phase2 failed", e);
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
                    sb.append("],\"gameTime\":").append(level.getGameTime()).append(",\"citizens\":[");
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
                          .append("\"jobStatus\":\"").append(jobStatusStr).append("\",")
                          // Saturation (0-20). Citizens stop working to hunt for food
                          // when this runs low, so supply agents feed them proactively.
                          .append("\"saturation\":").append(String.format(java.util.Locale.ROOT, "%.1f", cit.getSaturation())).append(",");
                        // Disease info so supply agents can deliver cure items. A sick
                        // citizen's EntityAISickTask self-cures (APPLY_CURE) once every
                        // cure item is present in the citizen's own inventory - the same
                        // delivery mechanism /giveToCitizen already provides.
                        boolean sick = false;
                        String diseaseId = null;
                        StringBuilder cures = new StringBuilder();
                        try {
                            com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenDiseaseHandler dh =
                                cit.getCitizenDiseaseHandler();
                            if (dh != null && dh.isSick() && dh.getDisease() != null) {
                                sick = true;
                                com.minecolonies.core.datalistener.model.Disease disease = dh.getDisease();
                                diseaseId = disease.id().toString();
                                java.util.List<com.minecolonies.api.crafting.ItemStorage> cureItems = disease.cureItems();
                                for (int k = 0; k < cureItems.size(); k++) {
                                    if (k > 0) cures.append(",");
                                    ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(cureItems.get(k).getItem());
                                    cures.append("{\"item\":\"").append(itemKey)
                                         .append("\",\"count\":").append(cureItems.get(k).getAmount()).append("}");
                                }
                            }
                        } catch (Exception ignored) {}
                        sb.append("\"sick\":").append(sick).append(",")
                          .append("\"disease\":").append(diseaseId == null ? "null" : "\"" + diseaseId + "\"").append(",")
                          .append("\"cureItems\":[").append(cures).append("],");
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
                    // For builder huts: assign to self (pos). For others: pick an
                    // idle lv1+ hut so work fans out across builders instead of
                    // always piling onto whichever hut the stream happens to
                    // enumerate first (previously every non-builder-hut work
                    // order landed on the same single builder while the rest
                    // sat idle - see requestBuild history).
                    BlockPos builderHutPos;
                    if (isBuilderHut) {
                        builderHutPos = pos;
                    } else {
                        java.util.List<IBuilding> capableBuilderHuts = colony.getServerBuildingManager().getBuildings().values().stream()
                            .filter(bld -> {
                                ResourceLocation bldKey = ForgeRegistries.BLOCKS.getKey(
                                    level.getBlockState(bld.getPosition()).getBlock());
                                return bldKey != null && "blockhutbuilder".equals(bldKey.getPath())
                                    && bld.getBuildingLevel() >= 1;
                            })
                            .collect(java.util.stream.Collectors.toList());
                        // Pick the hut with the fewest claimed work orders. Judging by
                        // the citizen's jobStatus alone doesn't balance anything: the
                        // status stays "idle" until the builder physically starts, so
                        // a burst of requests all saw every builder idle and piled
                        // onto the first hut in enumeration order.
                        java.util.Map<BlockPos, Long> claimedCounts =
                            colony.getWorkManager().getWorkOrders().values().stream()
                                .filter(wo -> wo.getClaimedBy() != null)
                                .collect(java.util.stream.Collectors.groupingBy(
                                    wo -> wo.getClaimedBy(), java.util.stream.Collectors.counting()));
                        builderHutPos = capableBuilderHuts.stream()
                            .map(IBuilding::getPosition)
                            .min(java.util.Comparator.comparingLong(
                                hutPos -> claimedCounts.getOrDefault(hutPos, 0L)))
                            .orElse(pos);
                    }
                    // If the building has no blueprint path (can happen when getBuilding(pos)
                    // returned null during /place and the IBuilding was not patched), fix it
                    // now before requestUpgrade reads the path to create the work order.
                    if (building.getBlueprintPath() == null || building.getBlueprintPath().isEmpty()) {
                        String typeName = targetKey != null ? targetKey.getPath().replaceFirst("^blockhut", "") : "";
                        String bpPath = BLUEPRINT_PATHS.get(typeName);
                        StructurePackMeta pack = getPinnedPack();
                        if (bpPath != null && pack != null) {
                            try { building.setStructurePack(pack.getName()); } catch (NullPointerException ignored) {}
                            try { building.setBlueprintPath(bpPath); } catch (NullPointerException ignored) {}
                            BlockEntity te = level.getBlockEntity(pos);
                            if (te instanceof TileEntityColonyBuilding) {
                                try { ((TileEntityColonyBuilding) te).setStructurePack(pack); } catch (Exception ignored) {}
                                try { ((TileEntityColonyBuilding) te).setBlueprintPath(bpPath); } catch (Exception ignored) {}
                            }
                        }
                    }
                    String pathBeforeUpgrade = building.getBlueprintPath();
                    Player fakePlayer = new FakePlayer(level, AI_PROFILE);
                    building.requestUpgrade(fakePlayer, builderHutPos);
                    boolean nowPending = building.isPendingConstruction();
                    result.complete("requested build for building at " + x + "," + y + "," + z
                        + " (assigned to builder hut at " + builderHutPos + ")"
                        + (nowPending ? "" : " [WARN: not pending after requestUpgrade; path='" + pathBeforeUpgrade + "']"));
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

    // GET  /neededResources?x=&y=&z=      - a builder hut's remaining material
    //      list for its current work order: [{item, needed, available, given:0}]
    // POST /fillBuilderResources?x=&y=&z= - same list, but first tops up every
    //      deficit (needed - available) into the hut's racks in one shot.
    //
    // The builder AI requests materials one item type at a time as construction
    // reaches them, so reactive request-resolution (supply_bot's default) costs
    // a request->deliver round-trip per item type. The building already tracks
    // the whole remaining bill of materials (AbstractBuildingStructureBuilder
    // .getNeededResources()), and the builder picks materials out of its hut's
    // racks before filing requests - so bulk-filling the racks up front removes
    // the ping-pong entirely. Tools/armor are NOT in this list and still flow
    // through the normal request path.
    private void handleNeededResources(HttpExchange exchange) throws IOException {
        handleBuilderResources(exchange, false);
    }

    private void handleFillBuilderResources(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        handleBuilderResources(exchange, true);
    }

    private void handleBuilderResources(HttpExchange exchange, boolean fill) throws IOException {
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
                    if (!(building instanceof com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder sb)) {
                        result.complete("ERROR: building at " + x + "," + y + "," + z
                                + " is not a structure-builder hut");
                        return;
                    }
                    net.minecraftforge.items.IItemHandler handler = null;
                    if (fill) {
                        com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding te = building.getTileEntity();
                        if (te == null) {
                            result.complete("ERROR: hut tile entity not loaded");
                            return;
                        }
                        handler = te.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                                .resolve().orElse(null);
                        if (handler == null) {
                            result.complete("ERROR: hut has no item handler capability");
                            return;
                        }
                    }
                    StringBuilder json = new StringBuilder("[");
                    boolean first = true;
                    for (com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource res
                            : sb.getNeededResources().values()) {
                        ItemStack proto = res.getItemStack();
                        int needed = res.getAmount();
                        int available = res.getAvailable();
                        if (fill) {
                            // The module's availability counter resets to 0 whenever a
                            // new work order (re)computes its resource list and only
                            // catches up on a later tick - trusting it right after that
                            // reset would re-deliver items already sitting in the racks.
                            // Count the racks directly and use whichever is higher.
                            int inRacks = 0;
                            for (int slot = 0; slot < handler.getSlots(); slot++) {
                                ItemStack s = handler.getStackInSlot(slot);
                                if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, proto)) {
                                    inRacks += s.getCount();
                                }
                            }
                            available = Math.max(available, inRacks);
                        }
                        int inserted = 0;
                        if (fill && needed > available) {
                            int remaining = needed - available;
                            while (remaining > 0) {
                                int chunkSize = Math.min(remaining, proto.getMaxStackSize());
                                ItemStack chunk = proto.copy();
                                chunk.setCount(chunkSize);
                                ItemStack leftover = chunk;
                                for (int slot = 0; slot < handler.getSlots() && !leftover.isEmpty(); slot++) {
                                    leftover = handler.insertItem(slot, leftover, false);
                                }
                                int put = chunkSize - leftover.getCount();
                                inserted += put;
                                remaining -= chunkSize;
                                if (put < chunkSize) break; // racks full
                            }
                            if (inserted > 0) {
                                // keep the module's availability bookkeeping in sync until
                                // its own tick recomputes it
                                res.setAvailable(available + inserted);
                            }
                        }
                        if (!first) json.append(",");
                        first = false;
                        json.append("{\"item\":\"")
                            .append(escape(String.valueOf(ForgeRegistries.ITEMS.getKey(proto.getItem()))))
                            .append("\",\"needed\":").append(needed)
                            .append(",\"available\":").append(available)
                            .append(",\"given\":").append(inserted)
                            .append("}");
                    }
                    json.append("]");
                    result.complete(json.toString());
                } catch (Exception e) {
                    LOGGER.error("builderResources failed", e);
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

    // POST /stockRestaurant?x=&y=&z=&countPerItem=32
    //
    // Tops the cook hut's racks up to countPerItem of every food on its
    // RestaurantMenuModule menu. The menu module does file MinimumStock
    // requests on colony ticks, but citizens queue at the restaurant faster
    // than that pipeline delivers at 10x - stocking the racks directly means
    // the cook can serve arrivals immediately instead of them loitering.
    private void handleStockRestaurant(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            int z = Integer.parseInt(params.get("z"));
            int target = Integer.parseInt(params.getOrDefault("countPerItem", "32"));

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
                    com.minecolonies.core.colony.buildings.modules.RestaurantMenuModule menu =
                            building.getModule(com.minecolonies.core.colony.buildings.modules.BuildingModules.RESTAURANT_MENU);
                    if (menu == null) {
                        result.complete("ERROR: building at " + x + "," + y + "," + z
                                + " has no restaurant menu module (not a cook hut?)");
                        return;
                    }
                    com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding te = building.getTileEntity();
                    net.minecraftforge.items.IItemHandler handler = te == null ? null
                            : te.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                                .resolve().orElse(null);
                    if (handler == null) {
                        result.complete("ERROR: hut has no item handler (tile entity not loaded?)");
                        return;
                    }
                    StringBuilder json = new StringBuilder("[");
                    boolean first = true;
                    for (com.minecolonies.api.crafting.ItemStorage st : menu.getMenu()) {
                        ItemStack proto = st.getItemStack();
                        int have = 0;
                        for (int slot = 0; slot < handler.getSlots(); slot++) {
                            ItemStack s = handler.getStackInSlot(slot);
                            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, proto)) {
                                have += s.getCount();
                            }
                        }
                        int inserted = 0;
                        int remaining = target - have;
                        while (remaining > 0) {
                            int chunkSize = Math.min(remaining, proto.getMaxStackSize());
                            ItemStack chunk = proto.copy();
                            chunk.setCount(chunkSize);
                            ItemStack leftover = chunk;
                            for (int slot = 0; slot < handler.getSlots() && !leftover.isEmpty(); slot++) {
                                leftover = handler.insertItem(slot, leftover, false);
                            }
                            int put = chunkSize - leftover.getCount();
                            inserted += put;
                            remaining -= chunkSize;
                            if (put < chunkSize) break; // racks full
                        }
                        if (!first) json.append(",");
                        first = false;
                        json.append("{\"item\":\"")
                            .append(escape(String.valueOf(ForgeRegistries.ITEMS.getKey(proto.getItem()))))
                            .append("\",\"had\":").append(have)
                            .append(",\"given\":").append(inserted)
                            .append("}");
                    }
                    json.append("]");
                    result.complete(json.toString());
                } catch (Exception e) {
                    LOGGER.error("stockRestaurant failed", e);
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

    // POST /setMenu?x=200&y=-60&z=182&items=minecraft:bread,minecraft:cooked_beef
    //
    // Once a cook hut (restaurant) exists and is staffed, citizens stop eating
    // food from their own inventory and will only eat what is registered on the
    // restaurant's menu (RestaurantMenuModule - the list the player normally
    // edits in the hut GUI). A freshly built cook hut has an EMPTY menu, which
    // starves the entire colony. addMenuItem enforces edibility (FoodUtils.EDIBLE)
    // and a per-building-level size cap and silently ignores rejects, so the
    // response re-reads the menu to show what actually stuck.
    private void handleSetMenu(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            int z = Integer.parseInt(params.get("z"));
            String itemsCsv = params.getOrDefault("items", "");

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
                    com.minecolonies.core.colony.buildings.modules.RestaurantMenuModule menu =
                            building.getModule(com.minecolonies.core.colony.buildings.modules.BuildingModules.RESTAURANT_MENU);
                    if (menu == null) {
                        result.complete("ERROR: building at " + x + "," + y + "," + z
                                + " has no restaurant menu module (not a cook hut?)");
                        return;
                    }
                    for (String id : itemsCsv.split(",")) {
                        id = id.trim();
                        if (id.isEmpty()) continue;
                        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
                        if (item == null) {
                            result.complete("ERROR: unknown item id " + id);
                            return;
                        }
                        menu.addMenuItem(new ItemStack(item, 1));
                    }
                    StringBuilder sb = new StringBuilder("menu now (" + menu.getMenu().size() + "): ");
                    boolean first = true;
                    for (com.minecolonies.api.crafting.ItemStorage st : menu.getMenu()) {
                        if (!first) sb.append(", ");
                        first = false;
                        sb.append(ForgeRegistries.ITEMS.getKey(st.getItemStack().getItem()));
                    }
                    result.complete(sb.toString());
                } catch (Exception e) {
                    LOGGER.error("setMenu failed", e);
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

    // POST /setFieldSeed?x=&y=&z=&seed=minecraft:wheat_seeds
    //
    // A farm field (scarecrow block) grows nothing until a seed is assigned,
    // which is normally done in the scarecrow GUI - without it the farmer never
    // starts working. (x,y,z) is the scarecrow's position. Mirrors what
    // FarmFieldUpdateSeedMessage.onExecute does server-side.
    private void handleSetFieldSeed(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            int z = Integer.parseInt(params.get("z"));
            String seedId = params.get("seed");

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
                    Item seedItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(seedId));
                    if (seedItem == null) {
                        result.complete("ERROR: unknown item id " + seedId);
                        return;
                    }
                    java.util.Optional<com.minecolonies.api.colony.buildingextensions.IBuildingExtension> extOpt =
                            colony.getServerBuildingManager().getMatchingBuildingExtension(ext ->
                                    ext.getBuildingExtensionType()
                                            == com.minecolonies.api.colony.buildingextensions.registry.BuildingExtensionRegistries.farmField.get()
                                    && ext.getPosition().equals(pos));
                    if (extOpt.isEmpty()) {
                        result.complete("ERROR: no farm field registered at " + x + "," + y + "," + z
                                + " (is that the scarecrow position, and has the field been built?)");
                        return;
                    }
                    ((com.minecolonies.core.colony.buildingextensions.FarmField) extOpt.get())
                            .setSeed(new ItemStack(seedItem, 1));
                    colony.getServerBuildingManager().markBuildingExtensionsDirty();
                    result.complete("seed of field at " + x + "," + y + "," + z + " set to " + seedId);
                } catch (Exception e) {
                    LOGGER.error("setFieldSeed failed", e);
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

    // GET /debugCitizenAI?colonyId=1&citizenId=12
    //
    // Read-only dump of the internals CitizenAI.calculateNextState() uses to
    // decide WORK vs IDLE, for diagnosing citizens that wander instead of
    // working: a citizen only enters WORK when its job's workerAI is an
    // AbstractEntityAIBasic, canGoIdle() is false, and it has no leisure time
    // left (leisureTime is private, so reflection).
    private void handleDebugCitizenAI(HttpExchange exchange) throws IOException {
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int colonyId = Integer.parseInt(params.getOrDefault("colonyId", "1"));
            int citizenId = Integer.parseInt(params.get("citizenId"));

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, server.overworld());
                    if (colony == null) {
                        result.complete("ERROR: no colony with id " + colonyId);
                        return;
                    }
                    ICitizenData data = colony.getCitizenManager().getCivilian(citizenId);
                    if (data == null) {
                        result.complete("ERROR: no citizen with id " + citizenId);
                        return;
                    }
                    StringBuilder sb = new StringBuilder("{");
                    int leisure = -1;
                    try {
                        java.lang.reflect.Field f =
                                com.minecolonies.core.colony.CitizenData.class.getDeclaredField("leisureTime");
                        f.setAccessible(true);
                        leisure = f.getInt(data);
                    } catch (Exception e) {
                        LOGGER.warn("leisureTime reflection failed", e);
                    }
                    sb.append("\"leisureTime\":").append(leisure);
                    sb.append(",\"saturation\":").append(String.format(java.util.Locale.ROOT, "%.1f", data.getSaturation()));

                    com.minecolonies.api.colony.jobs.IJob<?> job = data.getJob();
                    sb.append(",\"jobClass\":\"").append(job == null ? "" : job.getClass().getSimpleName()).append("\"");
                    Object workerAI = job == null ? null : job.getWorkerAI();
                    sb.append(",\"workerAIClass\":\"").append(workerAI == null ? "null" : workerAI.getClass().getSimpleName()).append("\"");
                    boolean isBasic = workerAI instanceof com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
                    sb.append(",\"workerAIIsBasic\":").append(isBasic);
                    boolean canGoIdle = false;
                    if (isBasic) {
                        try {
                            canGoIdle = ((com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic<?, ?>) workerAI).canGoIdle();
                        } catch (Exception e) {
                            sb.append(",\"canGoIdleError\":\"").append(escape(String.valueOf(e))).append("\"");
                        }
                    }
                    sb.append(",\"canGoIdle\":").append(canGoIdle);
                    sb.append(",\"canAIBeInterrupted\":").append(job != null && job.canAIBeInterrupted());
                    String entityState = data.getEntity()
                            .map(e -> e instanceof com.minecolonies.core.entity.citizen.EntityCitizen ec
                                    ? String.valueOf(ec.getCitizenAI().getState()) : "not-EntityCitizen")
                            .orElse("no-entity");
                    sb.append(",\"citizenAIState\":\"").append(escape(entityState)).append("\"");
                    sb.append("}");
                    result.complete(sb.toString());
                } catch (Exception e) {
                    LOGGER.error("debugCitizenAI failed", e);
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

    // GET /debugFarm?x=&y=&z=  (x,y,z = farmer hut position)
    //
    // Dumps why the farmer's BuildingExtensionsModule does or doesn't hand out
    // a field: the module only re-offers a field whose checkedExtensions day
    // stamp is OLDER than colony.getDay() (fields get stamped after 4 visits
    // with nothing to do), so a farmer can legitimately idle until the next
    // colony day - or forever, if the day counter doesn't advance.
    private void handleDebugFarm(HttpExchange exchange) throws IOException {
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
                    com.minecolonies.core.colony.buildings.modules.BuildingExtensionsModule module =
                            building.getFirstModuleOccurance(
                                    com.minecolonies.core.colony.buildings.modules.BuildingExtensionsModule.class);
                    if (module == null) {
                        result.complete("ERROR: building has no BuildingExtensionsModule");
                        return;
                    }
                    StringBuilder sb = new StringBuilder("{");
                    sb.append("\"colonyDay\":").append(colony.getDay());
                    sb.append(",\"worldDayTime\":").append(level.getDayTime());
                    Object currentId = null;
                    String checked = "?";
                    try {
                        java.lang.reflect.Field fCur = com.minecolonies.core.colony.buildings.modules.BuildingExtensionsModule.class
                                .getDeclaredField("currentExtensionId");
                        fCur.setAccessible(true);
                        currentId = fCur.get(module);
                        java.lang.reflect.Field fChk = com.minecolonies.core.colony.buildings.modules.BuildingExtensionsModule.class
                                .getDeclaredField("checkedExtensions");
                        fChk.setAccessible(true);
                        checked = String.valueOf(fChk.get(module));
                    } catch (Exception e) {
                        LOGGER.warn("debugFarm reflection failed", e);
                    }
                    sb.append(",\"currentExtensionId\":\"").append(escape(String.valueOf(currentId))).append("\"");
                    sb.append(",\"checkedExtensions\":\"").append(escape(checked)).append("\"");
                    Object toWorkOn = module.getExtensionToWorkOn();
                    sb.append(",\"extensionToWorkOn\":\"").append(toWorkOn == null ? "null"
                            : escape(String.valueOf(((com.minecolonies.api.colony.buildingextensions.IBuildingExtension) toWorkOn).getPosition()))).append("\"");
                    sb.append(",\"ownedExtensions\":[");
                    boolean first = true;
                    for (com.minecolonies.api.colony.buildingextensions.IBuildingExtension ext : module.getOwnedExtensions()) {
                        if (!first) sb.append(",");
                        first = false;
                        BlockPos p = ext.getPosition();
                        sb.append("{\"x\":").append(p.getX()).append(",\"y\":").append(p.getY()).append(",\"z\":").append(p.getZ());
                        if (ext instanceof com.minecolonies.core.colony.buildingextensions.FarmField farm) {
                            sb.append(",\"stage\":\"").append(farm.getFieldStage()).append("\"");
                            ItemStack seed = farm.getSeed();
                            sb.append(",\"seed\":\"").append(seed == null || seed.isEmpty() ? ""
                                    : escape(String.valueOf(ForgeRegistries.ITEMS.getKey(seed.getItem())))).append("\"");
                        }
                        sb.append("}");
                    }
                    sb.append("]}");
                    result.complete(sb.toString());
                } catch (Exception e) {
                    LOGGER.error("debugFarm failed", e);
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

    // GET /debugBuilder?x=&y=&z=  (x,y,z = builder hut position)
    //
    // Dumps the inputs of WorkManager.tryAssignWorkOrder for one hut, to see
    // why a claimed work order isn't being bound to the building: binding
    // requires the hut's WorkerBuildingModule to have a citizen, the hut to
    // have no current work order, and the order's claimedBy to equal the
    // hut's position.
    private void handleDebugBuilder(HttpExchange exchange) throws IOException {
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
                    if (!(building instanceof com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder sb)) {
                        result.complete("ERROR: not a structure-builder hut at " + x + "," + y + "," + z);
                        return;
                    }
                    StringBuilder sbj = new StringBuilder("{");
                    sbj.append("\"class\":\"").append(building.getClass().getSimpleName()).append("\"");
                    sbj.append(",\"level\":").append(building.getBuildingLevel());
                    int woId = -1;
                    try {
                        java.lang.reflect.Field f = com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder.class
                                .getDeclaredField("workOrderId");
                        f.setAccessible(true);
                        woId = f.getInt(sb);
                    } catch (Exception e) {
                        LOGGER.warn("workOrderId reflection failed", e);
                    }
                    sbj.append(",\"workOrderId\":").append(woId);
                    sbj.append(",\"hasWorkOrder\":").append(sb.hasWorkOrder());
                    com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule wm =
                            building.getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule.class);
                    ICitizenData first = wm == null ? null : wm.getFirstCitizen();
                    sbj.append(",\"workerModule\":").append(wm != null);
                    sbj.append(",\"firstCitizen\":").append(first == null ? "null" : first.getId());
                    sbj.append(",\"buildingID\":\"").append(building.getID().toShortString()).append("\"");
                    sbj.append(",\"orders\":[");
                    boolean firstOrd = true;
                    for (com.minecolonies.api.colony.workorders.IWorkOrder wo : colony.getWorkManager().getWorkOrders().values()) {
                        if (!firstOrd) sbj.append(",");
                        firstOrd = false;
                        sbj.append("{\"id\":").append(wo.getID())
                           .append(",\"claimedBy\":\"").append(wo.getClaimedBy() == null ? "null" : wo.getClaimedBy().toShortString()).append("\"")
                           .append(",\"claimedEqualsThisHut\":")
                           .append(wo.getClaimedBy() != null && wo.getClaimedBy().equals(building.getPosition()))
                           .append(",\"isClaimed\":").append(wo.isClaimed())
                           .append("}");
                    }
                    sbj.append("]}");
                    result.complete(sbj.toString());
                } catch (Exception e) {
                    LOGGER.error("debugBuilder failed", e);
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

    // GET /fields?colonyId=1
    //
    // Lists the colony's building extensions (farm fields, plantation fields):
    // position, type, whether a hut has claimed it, and the assigned seed for
    // farm fields. IRegisteredStructureManager only exposes a first-match
    // predicate search, so the predicate collects every entry and returns false.
    private void handleFields(HttpExchange exchange) throws IOException {
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int colonyId = Integer.parseInt(params.getOrDefault("colonyId", "1"));

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, server.overworld());
                    if (colony == null) {
                        result.complete("ERROR: no colony with id " + colonyId);
                        return;
                    }
                    java.util.List<com.minecolonies.api.colony.buildingextensions.IBuildingExtension> all =
                            new java.util.ArrayList<>();
                    colony.getServerBuildingManager().getMatchingBuildingExtension(ext -> {
                        all.add(ext);
                        return false;
                    });
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    for (com.minecolonies.api.colony.buildingextensions.IBuildingExtension ext : all) {
                        if (!first) sb.append(",");
                        first = false;
                        BlockPos p = ext.getPosition();
                        sb.append("{\"x\":").append(p.getX())
                          .append(",\"y\":").append(p.getY())
                          .append(",\"z\":").append(p.getZ())
                          .append(",\"type\":\"").append(escape(ext.getClass().getSimpleName())).append("\"")
                          .append(",\"taken\":").append(ext.isTaken());
                        if (ext instanceof com.minecolonies.core.colony.buildingextensions.FarmField farm) {
                            ItemStack seed = farm.getSeed();
                            sb.append(",\"seed\":\"")
                              .append(seed == null || seed.isEmpty() ? ""
                                      : escape(String.valueOf(ForgeRegistries.ITEMS.getKey(seed.getItem()))))
                              .append("\"");
                        }
                        sb.append("}");
                    }
                    sb.append("]");
                    result.complete(sb.toString());
                } catch (Exception e) {
                    LOGGER.error("fields failed", e);
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

    // POST /tickrate?multiplier=10
    // Sets the tick rate multiplier. multiplier=1 restores normal 20 TPS.
    // The actual sleep removal is implemented in MinecraftServerMixin via Mixin.
    private void handleTickrate(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int mult = Integer.parseInt(params.getOrDefault("multiplier", "1"));
            if (mult < 1) mult = 1;
            tickMultiplier = mult;
            respond(exchange, 200, "{\"result\":\"multiplier=" + mult + "\"}");
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // GET /debugTick — dumps MinecraftServer class hierarchy long fields and current nextTickTime value.
    private void handleDebugTick(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"multiplier\":").append(tickMultiplier);
        sb.append(",\"fields\":[");
        boolean first = true;
        Class<?> cls = server.getClass();
        while (cls != null) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (f.getType() != long.class) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"class\":\"").append(escape(cls.getSimpleName()))
                  .append("\",\"name\":\"").append(escape(f.getName())).append("\"");
                try {
                    f.setAccessible(true);
                    sb.append(",\"value\":").append(f.getLong(server));
                } catch (Exception e) {
                    sb.append(",\"error\":\"").append(escape(e.getMessage())).append("\"");
                }
                sb.append("}");
            }
            cls = cls.getSuperclass();
        }
        sb.append("],\"currentMs\":").append(System.currentTimeMillis());
        sb.append(",\"resolvedField\":\"").append(nextTickTimeField != null ? escape(nextTickTimeField.getName()) : "null").append("\"");
        sb.append("}");
        respond(exchange, 200, sb.toString());
    }

    // POST /placeNext?block=minecolonies:blockhutbuilder&colonyId=1
    // Finds the nearest valid position for the building type (within territory,
    // no footprint overlap) and places it there in one step. The caller should
    // follow up with /requestBuild at the returned coordinates.
    private void handlePlaceNext(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"use POST\"}");
            return;
        }
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String blockId = params.getOrDefault("block", "");
            int colonyId = Integer.parseInt(params.getOrDefault("colonyId", "1"));

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    int[] pos = findPlacementPos(blockId, colonyId);
                    if (pos == null) {
                        result.complete("ERROR: no valid position found within territory for " + blockId);
                        return;
                    }
                    String placed = placeOnServerThread(pos[0], pos[1], pos[2], blockId);
                    if (placed.startsWith("ERROR")) {
                        result.complete(placed);
                    } else {
                        result.complete(placed + " [pos:" + pos[0] + "," + pos[1] + "," + pos[2] + "]");
                    }
                } catch (Exception e) {
                    LOGGER.error("placeNext failed", e);
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

    // GET /suggestPosition?block=minecolonies:blockhutbuilder&colonyId=1
    // Returns the nearest available anchor coordinate for the given building type
    // that fits inside the colony's territory (≤60 blocks from center) without
    // overlapping any already-placed building footprint (including 5-block gap).
    private void handleSuggestPosition(HttpExchange exchange) throws IOException {
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String blockId = params.getOrDefault("block", "");
            int colonyId = Integer.parseInt(params.getOrDefault("colonyId", "1"));

            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    result.complete(suggestPositionOnServerThread(blockId, colonyId));
                } catch (Exception e) {
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

    // Footprint sizes (X-width × Z-depth) matching the Colonial pack's level-1
    // blueprints. Used by suggestPosition to check for collisions between buildings.
    // Keys match the type names in BLUEPRINT_PATHS (part after "blockhut").
    private static final java.util.Map<String, int[]> BUILDING_FOOTPRINTS = new java.util.HashMap<>();
    static {
        BUILDING_FOOTPRINTS.put("townhall",    new int[]{38, 33});
        BUILDING_FOOTPRINTS.put("builder",     new int[]{22, 11});
        BUILDING_FOOTPRINTS.put("citizen",     new int[]{13, 15});
        BUILDING_FOOTPRINTS.put("warehouse",   new int[]{20, 24});
        BUILDING_FOOTPRINTS.put("tavern",      new int[]{22, 20});
        BUILDING_FOOTPRINTS.put("farmer",      new int[]{25, 20});
        BUILDING_FOOTPRINTS.put("miner",       new int[]{13, 22});
        BUILDING_FOOTPRINTS.put("fisherman",   new int[]{22, 16});
        BUILDING_FOOTPRINTS.put("hospital",    new int[]{16, 17});
        BUILDING_FOOTPRINTS.put("kitchen",     new int[]{17, 17});
        BUILDING_FOOTPRINTS.put("lumberjack",  new int[]{11, 19});
        BUILDING_FOOTPRINTS.put("guardtower",  new int[]{11, 10});
        BUILDING_FOOTPRINTS.put("deliveryman", new int[]{4, 2});
        BUILDING_FOOTPRINTS.put("courier",     new int[]{4, 2});
    }

    // Returns {minX, minZ, maxX, maxZ} of a placed building's actual in-world
    // footprint via IBuilding.getCorners(). getCorners() lazily computes the
    // corners: from the tile entity's schematic data when present, otherwise by
    // loading the building's blueprint (structurePack/blueprintPath, which
    // placeOnServerThread always sets) through ColonyUtils.calculateCorners().
    // The TE-based getInWorldCorners() path used previously returned null for
    // every bridge-placed building because we never call setSchematicCorners().
    // If the blueprint can't be loaded MineColonies stores a degenerate
    // (position, position) pair - return null then so the caller falls back to
    // the BUILDING_FOOTPRINTS guess.
    private int[] realFootprintRect(IBuilding b) {
        try {
            net.minecraft.util.Tuple<BlockPos, BlockPos> corners = b.getCorners();
            if (corners != null && corners.getA() != null && corners.getA().equals(corners.getB())) {
                // Degenerate corners persist in the colony save from sessions where
                // the blueprint failed to load (getCorners only recomputes when the
                // corners are still BlockPos.ZERO). Force a recompute now that the
                // structure packs are loaded - this also heals isInBuilding() for
                // MineColonies' own logic since calculateCorners stores the result.
                b.calculateCorners();
                corners = b.getCorners();
            }
            if (corners == null || corners.getA() == null || corners.getB() == null) return null;
            BlockPos c1 = corners.getA();
            BlockPos c2 = corners.getB();
            if (c1.equals(c2)) return null;
            return new int[]{
                Math.min(c1.getX(), c2.getX()), Math.min(c1.getZ(), c2.getZ()),
                Math.max(c1.getX(), c2.getX()), Math.max(c1.getZ(), c2.getZ())
            };
        } catch (Exception e) {
            return null;
        }
    }

    // GET /debugWorkOrders?colonyId=1 - dumps every work order with the fields
    // the builder AI's LOAD_STRUCTURE step depends on (pack/path/blueprint).
    private void handleDebugWorkOrders(HttpExchange exchange) throws IOException {
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int colonyId = Integer.parseInt(params.getOrDefault("colonyId", "1"));
            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
                    if (colony == null) { result.complete("{\"error\":\"no colony\"}"); return; }
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    for (com.minecolonies.api.colony.workorders.IServerWorkOrder wo
                            : colony.getWorkManager().getWorkOrders().values()) {
                        if (!first) sb.append(',');
                        first = false;
                        sb.append("{\"id\":").append(wo.getID())
                          .append(",\"type\":\"").append(wo.getWorkOrderType()).append('"')
                          .append(",\"location\":\"").append(wo.getLocation() == null ? null : wo.getLocation().toShortString()).append('"')
                          .append(",\"claimedBy\":\"").append(wo.getClaimedBy() == null ? null : wo.getClaimedBy().toShortString()).append('"')
                          .append(",\"pack\":\"").append(escape(String.valueOf(wo.getStructurePack()))).append('"')
                          .append(",\"path\":\"").append(escape(String.valueOf(wo.getStructurePath()))).append('"')
                          .append(",\"levels\":\"").append(wo.getCurrentLevel()).append("->").append(wo.getTargetLevel()).append('"')
                          .append(",\"rotation\":").append(wo.getRotation())
                          .append(",\"stage\":\"").append(wo.getStage()).append('"')
                          .append(",\"blueprintLoaded\":").append(wo.getBlueprint() != null)
                          .append('}');
                    }
                    sb.append(']');
                    result.complete(sb.toString());
                } catch (Exception e) {
                    result.complete("{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
                }
            });
            respond(exchange, 200, result.get());
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // POST /rebalanceWorkOrders?colonyId=1 - redistributes claimed building work
    // orders round-robin across all level-1+ builder huts. Work orders created
    // before the fewest-claimed selection existed all piled onto one hut and
    // stay there forever (builders never steal claimed orders), so this is the
    // one-off migration for them. Self-build orders (a builder hut building
    // itself) keep their claim.
    private void handleRebalanceWorkOrders(HttpExchange exchange) throws IOException {
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int colonyId = Integer.parseInt(params.getOrDefault("colonyId", "1"));
            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
                    if (colony == null) { result.complete("ERROR: no colony with id " + colonyId); return; }
                    java.util.List<BlockPos> huts = colony.getServerBuildingManager().getBuildings().values().stream()
                        .filter(bld -> {
                            ResourceLocation bldKey = ForgeRegistries.BLOCKS.getKey(
                                level.getBlockState(bld.getPosition()).getBlock());
                            return bldKey != null && "blockhutbuilder".equals(bldKey.getPath())
                                && bld.getBuildingLevel() >= 1;
                        })
                        .map(IBuilding::getPosition)
                        .sorted(java.util.Comparator.<BlockPos>comparingInt(p -> p.getX())
                            .thenComparingInt(p -> p.getZ()))
                        .collect(java.util.stream.Collectors.toList());
                    if (huts.isEmpty()) { result.complete("ERROR: no operational builder huts"); return; }
                    StringBuilder sb = new StringBuilder();
                    int i = 0;
                    for (com.minecolonies.api.colony.workorders.IServerWorkOrder wo
                            : colony.getWorkManager().getWorkOrders().values()) {
                        if (wo.getClaimedBy() == null) continue;
                        if (wo.getLocation() != null && wo.getLocation().equals(wo.getClaimedBy())) continue;
                        BlockPos target = huts.get(i % huts.size());
                        i++;
                        if (!target.equals(wo.getClaimedBy())) {
                            // The old hut caches workOrderId/progress/resources and its
                            // worker AI holds a structurePlacer for this order; only
                            // onWorkOrderCancellation resets all of them. Without it the
                            // old builder keeps working the order it no longer owns and
                            // wedges in a LOAD_STRUCTURE/RECALC loop.
                            IBuilding oldHut = colony.getServerBuildingManager()
                                .getBuildings().get(wo.getClaimedBy());
                            if (oldHut instanceof com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder oldBuilder) {
                                oldBuilder.onWorkOrderCancellation(wo);
                            }
                            wo.setClaimedBy(target);
                        }
                        sb.append(wo.getLocation() == null ? "?" : wo.getLocation().toShortString())
                          .append(" -> ").append(target.toShortString()).append("; ");
                    }
                    colony.getWorkManager().setDirty(true);
                    result.complete("rebalanced " + i + " work orders: " + sb);
                } catch (Exception e) {
                    LOGGER.error("rebalanceWorkOrders failed", e);
                    result.complete("ERROR: " + e);
                }
            });
            String outcome = result.get();
            respond(exchange, outcome.startsWith("ERROR") ? 500 : 200,
                "{\"result\":\"" + escape(outcome) + "\"}");
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // POST /removeBuilding?x=&y=&z=&colonyId=1 - deregisters the building at
    // the given anchor from the colony (cancelling its work orders) and removes
    // the hut block from the world. Mirrors what breaking the hut block as a
    // player does; needed to relocate buildings placed by the old overlapping
    // placement logic.
    private void handleRemoveBuilding(HttpExchange exchange) throws IOException {
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            int z = Integer.parseInt(params.get("z"));
            int colonyId = Integer.parseInt(params.getOrDefault("colonyId", "1"));
            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
                    if (colony == null) { result.complete("ERROR: no colony with id " + colonyId); return; }
                    BlockPos pos = new BlockPos(x, y, z);
                    IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
                    if (building == null) {
                        for (IBuilding candidate : colony.getServerBuildingManager().getBuildings().values()) {
                            if (candidate.getPosition().equals(pos)) { building = candidate; break; }
                        }
                    }
                    if (building == null) { result.complete("ERROR: no building at " + x + "," + y + "," + z); return; }
                    colony.getServerBuildingManager().removeBuilding(building, new java.util.HashSet<>());
                    level.removeBlock(pos, false);
                    result.complete("removed building at " + x + "," + y + "," + z);
                } catch (Exception e) {
                    LOGGER.error("removeBuilding failed", e);
                    result.complete("ERROR: " + e);
                }
            });
            String outcome = result.get();
            respond(exchange, outcome.startsWith("ERROR") ? 500 : 200,
                "{\"result\":\"" + escape(outcome) + "\"}");
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    // GET /debugFootprints?colonyId=1&block=<id> - dumps every existing
    // building's rect as findPlacementPos would compute it (real corners vs
    // table fallback), plus the candidate blueprint offsets for `block`.
    // Diagnostic aid for the footprint-overlap fix; not used by agents.
    private void handleDebugFootprints(HttpExchange exchange) throws IOException {
        try {
            java.util.Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String blockId = params.getOrDefault("block", "minecolonies:blockhutcitizen");
            int colonyId = Integer.parseInt(params.getOrDefault("colonyId", "1"));
            java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
                    if (colony == null) { result.complete("{\"error\":\"no colony\"}"); return; }
                    StringBuilder sb = new StringBuilder("{\"buildings\":[");
                    boolean first = true;
                    for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                        if (!first) sb.append(',');
                        first = false;
                        BlockPos bp = b.getPosition();
                        String corners;
                        try {
                            net.minecraft.util.Tuple<BlockPos, BlockPos> c = b.getCorners();
                            corners = c == null ? "null"
                                : "\"" + c.getA().toShortString() + " / " + c.getB().toShortString() + "\"";
                        } catch (Exception e) {
                            corners = "\"EX: " + escape(String.valueOf(e)) + "\"";
                        }
                        int[] rect = realFootprintRect(b);
                        sb.append("{\"pos\":\"").append(bp.toShortString())
                          .append("\",\"pack\":\"").append(escape(String.valueOf(b.getStructurePack())))
                          .append("\",\"path\":\"").append(escape(String.valueOf(b.getBlueprintPath())))
                          .append("\",\"corners\":").append(corners)
                          .append(",\"rect\":").append(rect == null ? "null" : java.util.Arrays.toString(rect))
                          .append('}');
                    }
                    sb.append("],\"candidate\":");
                    String typeName = blockId.contains(":")
                        ? blockId.substring(blockId.indexOf(':') + 1).replaceFirst("^blockhut", "")
                        : blockId.replaceFirst("^blockhut", "");
                    String bpPath = BLUEPRINT_PATHS.get(typeName);
                    StructurePackMeta pinnedPack = getPinnedPack();
                    sb.append("{\"type\":\"").append(typeName)
                      .append("\",\"bpPath\":\"").append(bpPath)
                      .append("\",\"pack\":\"").append(pinnedPack == null ? null : pinnedPack.getName())
                      .append("\",\"offsets\":");
                    if (bpPath != null && pinnedPack != null) {
                        try {
                            com.ldtteam.structurize.blueprints.v1.Blueprint blueprint =
                                StructurePacks.getBlueprint(pinnedPack.getName(), bpPath);
                            if (blueprint == null) {
                                sb.append("\"blueprint null\"");
                            } else {
                                net.minecraft.util.Tuple<BlockPos, BlockPos> c =
                                    com.minecolonies.api.util.ColonyUtils.calculateCorners(
                                        BlockPos.ZERO, level, blueprint, 0, false);
                                sb.append('"').append(c.getA().toShortString()).append(" / ")
                                  .append(c.getB().toShortString()).append('"');
                            }
                        } catch (Exception e) {
                            sb.append("\"EX: ").append(escape(String.valueOf(e))).append('"');
                        }
                    } else {
                        sb.append("\"no path or pack\"");
                    }
                    sb.append("}}");
                    result.complete(sb.toString());
                } catch (Exception e) {
                    result.complete("{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
                }
            });
            respond(exchange, 200, result.get());
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    private String suggestPositionOnServerThread(String blockId, int colonyId) {
        ServerLevel level = server.overworld();
        IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
        if (colony == null) return "ERROR: no colony with id " + colonyId;
        int[] pos = findPlacementPos(blockId, colonyId);
        if (pos == null) return "ERROR: no valid position found within 60 blocks of colony center";
        BlockPos center = colony.getCenter();
        double dist = Math.sqrt(Math.pow(pos[0] - center.getX(), 2) + Math.pow(pos[2] - center.getZ(), 2));
        return "{\"x\":" + pos[0] + ",\"y\":" + pos[1] + ",\"z\":" + pos[2]
            + ",\"dist\":" + String.format("%.1f", dist) + "}";
    }

    // Finds the nearest valid anchor position for a building type within the
    // colony's territory. Returns {x, y, z} or null if no valid position exists.
    private int[] findPlacementPos(String blockId, int colonyId) {
        ServerLevel level = server.overworld();
        IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
        if (colony == null) return null;

        BlockPos center = colony.getCenter();
        String typeName = blockId.contains(":")
            ? blockId.substring(blockId.indexOf(':') + 1).replaceFirst("^blockhut", "")
            : blockId.replaceFirst("^blockhut", "");
        int[] fp = BUILDING_FOOTPRINTS.getOrDefault(typeName, new int[]{10, 10});
        int bw = fp[0];
        int bd = fp[1];

        // Candidate footprint as offsets relative to the anchor. Blueprints don't
        // all extend +X/+Z from their anchor (townhall extends -Z), so prefer the
        // real blueprint extent: ColonyUtils.calculateCorners is a pure translation
        // of the anchor (anchor - primaryBlockOffset .. +size-1), so computing it
        // once at BlockPos.ZERO gives reusable offsets. rotation=0/mirror=false
        // matches how placeOnServerThread places buildings.
        int offMinX = 0, offMinZ = 0, offMaxX = bw - 1, offMaxZ = bd - 1;
        String bpPath = BLUEPRINT_PATHS.get(typeName);
        StructurePackMeta pinnedPack = getPinnedPack();
        if (bpPath != null && pinnedPack != null) {
            try {
                com.ldtteam.structurize.blueprints.v1.Blueprint blueprint =
                    StructurePacks.getBlueprint(pinnedPack.getName(), bpPath);
                if (blueprint != null) {
                    net.minecraft.util.Tuple<BlockPos, BlockPos> c =
                        com.minecolonies.api.util.ColonyUtils.calculateCorners(
                            BlockPos.ZERO, level, blueprint, 0, false);
                    offMinX = c.getA().getX();
                    offMinZ = c.getA().getZ();
                    offMaxX = c.getB().getX();
                    offMaxZ = c.getB().getZ();
                }
            } catch (Exception ignored) {}
        }

        // Existing buildings as {minX, minZ, maxX, maxZ} rects.
        java.util.List<int[]> existing = new java.util.ArrayList<>();
        for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            BlockPos bp = b.getPosition();
            int[] realRect = realFootprintRect(b);
            if (realRect != null) {
                existing.add(realRect);
            } else {
                ResourceLocation bKey = ForgeRegistries.BLOCKS.getKey(level.getBlockState(bp).getBlock());
                String bType = bKey != null ? bKey.getPath().replaceFirst("^blockhut", "") : "";
                int[] bFp = BUILDING_FOOTPRINTS.getOrDefault(bType, new int[]{10, 10});
                existing.add(new int[]{bp.getX(), bp.getZ(), bp.getX() + bFp[0] - 1, bp.getZ() + bFp[1] - 1});
            }
        }

        final int GAP = 5;
        final int MAX_DIST = 60;
        final int Y = center.getY();

        java.util.List<int[]> offsets = new java.util.ArrayList<>(15000);
        for (int dx = -MAX_DIST; dx <= MAX_DIST; dx++) {
            for (int dz = -MAX_DIST; dz <= MAX_DIST; dz++) {
                double dist = Math.sqrt((double)(dx * dx + dz * dz));
                if (dist <= MAX_DIST) {
                    offsets.add(new int[]{dx, dz, (int)(dist * 1000)});
                }
            }
        }
        offsets.sort((a, b2) -> a[2] - b2[2]);

        java.util.List<IBuilding> allBuildings = new java.util.ArrayList<>(
            colony.getServerBuildingManager().getBuildings().values());

        for (int[] off : offsets) {
            int nx = center.getX() + off[0];
            int nz = center.getZ() + off[1];
            BlockPos anchor = new BlockPos(nx, Y, nz);
            if (!colony.isCoordInColony(level, anchor)) continue;

            // Use MineColonies' own isInBuilding for the anchor to catch actual
            // blueprint bounds (which may extend in -Z or be larger than our table).
            boolean anchorInside = false;
            for (IBuilding b : allBuildings) {
                try {
                    if (b.isInBuilding(anchor)) { anchorInside = true; break; }
                } catch (Exception ignored) {}
            }
            if (anchorInside) continue;

            // Check that the new building's footprint (blueprint-based offsets,
            // table fallback) doesn't overlap any existing building's rect + GAP.
            int cMinX = nx + offMinX, cMaxX = nx + offMaxX;
            int cMinZ = nz + offMinZ, cMaxZ = nz + offMaxZ;
            boolean conflict = false;
            for (int[] eb : existing) {
                if (cMinX <= eb[2] + GAP && cMaxX >= eb[0] - GAP
                        && cMinZ <= eb[3] + GAP && cMaxZ >= eb[1] - GAP) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) return new int[]{nx, Y, nz};
        }
        return null;
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
        // Territory check: non-town-hall buildings must be placed inside a colony's
        // Territory check: non-town-hall buildings must be inside the colony AND within
        // the effective build radius. Two separate limits apply:
        //   1. Chunk-claim territory: IColonyManager.getIColony / isCoordInColony
        //   2. Euclidean radius: MineColonies silently rejects requestUpgrade for work
        //      orders whose target building is > ~64 blocks from the colony center. The
        //      chunk-claim check is looser (includes edge chunks), so we add an explicit
        //      distance guard of 60 blocks to stay safely inside both limits.
        boolean isTownHall = "blockhuttownhall".equals(rl.getPath());
        if (!isTownHall) {
            IColony nearestColony = null;
            double nearestDist = Double.MAX_VALUE;
            for (IColony c : IColonyManager.getInstance().getAllColonies()) {
                if (!c.getDimension().equals(level.dimension())) continue;
                BlockPos center = c.getCenter();
                double dist = Math.sqrt(Math.pow(pos.getX() - center.getX(), 2)
                    + Math.pow(pos.getZ() - center.getZ(), 2));
                if (dist < nearestDist) { nearestDist = dist; nearestColony = c; }
            }

            if (nearestColony == null) {
                return "ERROR: (" + x + "," + y + "," + z + ") no colony founded yet";
            }

            // Hard cap: stay within 62 blocks of colony center. MineColonies' initial
            // territory is 4 chunks = 64 blocks; requestUpgrade silently rejects work
            // orders beyond that radius. We use 62 to stay 2 blocks inside the limit.
            // When the town hall is upgraded, the territory expands by 1 chunk per level
            // (to 80, 96, ... blocks), but this code uses 62 for safety at level 0.
            // TODO: read colony.getMaxColonySize() to compute the dynamic limit.
            if (nearestDist > 62) {
                BlockPos center = nearestColony.getCenter();
                return "ERROR: (" + x + "," + y + "," + z + ") is " + (int) nearestDist
                    + " blocks from colony center " + center
                    + "; max 62 blocks allowed at townhall level 0 (territory ~64 blocks)";
            }

            // Also verify the position is in a claimed chunk (for multi-colony safety).
            if (!nearestColony.isCoordInColony(level, pos)) {
                BlockPos center = nearestColony.getCenter();
                return "ERROR: (" + x + "," + y + "," + z + ") is not in the colony's claimed chunks"
                    + " (colony center " + center + ", dist=" + (int) nearestDist + ")";
            }
        }

        BlockState state = block.defaultBlockState();
        // Place the hut with the same facing as the blueprint's anchor block.
        // MineColonies derives the building's rotation from the difference
        // between the placed hut's facing and the blueprint anchor's facing
        // (BuildingUtils.getRotationFromBlueprint), and the builder constructs
        // the whole schematic at that rotation. Matching the facing pins the
        // rotation to 0 so findPlacementPos's rotation-0 candidate footprint
        // equals the footprint that actually gets built.
        String typeName = rl.getPath().replaceFirst("^blockhut", "");
        String path = BLUEPRINT_PATHS.get(typeName);
        try {
            StructurePackMeta anchorPack = getPinnedPack();
            if (path != null && anchorPack != null) {
                com.ldtteam.structurize.blueprints.v1.Blueprint blueprint =
                    StructurePacks.getBlueprint(anchorPack.getName(), path);
                if (blueprint != null) {
                    com.ldtteam.structurize.util.BlockInfo anchorInfo =
                        blueprint.getBlockInfoAsMap().get(blueprint.getPrimaryBlockOffset());
                    if (anchorInfo != null && anchorInfo.getState() != null
                            && anchorInfo.getState().getBlock() == block) {
                        state = anchorInfo.getState();
                    }
                }
            }
        } catch (Exception ignored) {}

        level.setBlockAndUpdate(pos, state);

        FakePlayer fakePlayer = new FakePlayer(level, AI_PROFILE);
        ItemStack stack = item != null ? new ItemStack(item, 1) : ItemStack.EMPTY;
        // setPlacedBy registers the building with the colony. When a colony already
        // exists, MineColonies initializes the new building immediately and internally
        // calls building.setStructurePack(), which calls getTileEntity() on the
        // building. getTileEntity() returns null at this point (the building<->tile
        // entity back-link is set lazily), causing NPE inside setPlacedBy. The
        // building IS registered before that internal call, so we catch the NPE and
        // continue. If no colony exists yet (town hall placement), setPlacedBy
        // succeeds without the problematic initialization path.
        try {
            block.setPlacedBy(level, pos, state, fakePlayer, stack);
        } catch (NullPointerException ignored) {}

        // Blueprint pack/path must be set on the IBuilding AFTER setPlacedBy.
        // IBuilding.setStructurePack(String) and setBlueprintPath(String) write
        // their own fields first, then try to sync to the tile entity via
        // getTileEntity(). getTileEntity() returns null immediately after
        // setPlacedBy (the building<->tileEntity back-link is set lazily on the
        // next tick or chunk reload), so these calls throw NPE during the sync
        // step. We catch and swallow those NPEs: the building fields ARE already
        // written before the NPE, so IBuilding.getStructurePack() /
        // getBlueprintPath() return the correct values and requestUpgrade works.
        // We also call TileEntityColonyBuilding.setStructurePack/setBlueprintPath
        // directly (these just write their own fields, no back-call, always safe).
        String blueprintNote = "";
        if (path != null) {
            StructurePackMeta pack = getPinnedPack();
            if (pack != null) {
                IColony bldColony = findColonyAt(level, pos);
                if (bldColony != null) {
                    // getBuilding(pos) uses a key-lookup path that can miss buildings
                    // registered via setPlacedBy (key format mismatch). Fall back to
                    // scanning getBuildings().values() so we always find the building.
                    IBuilding bld = bldColony.getServerBuildingManager().getBuilding(pos);
                    if (bld == null) {
                        for (IBuilding candidate : bldColony.getServerBuildingManager().getBuildings().values()) {
                            if (candidate.getPosition().equals(pos)) { bld = candidate; break; }
                        }
                    }
                    if (bld != null) {
                        try { bld.setStructurePack(pack.getName()); } catch (NullPointerException ignored) {}
                        try { bld.setBlueprintPath(path); } catch (NullPointerException ignored) {}
                        try { bld.markDirty(); } catch (Exception ignored) {}
                    }
                }
                // Also set directly on the tile entity (these methods only write
                // their own fields, no back-delegation, safe without building link).
                BlockEntity te = level.getBlockEntity(pos);
                if (te instanceof TileEntityColonyBuilding) {
                    try { ((TileEntityColonyBuilding) te).setStructurePack(pack); } catch (Exception ignored) {}
                    try { ((TileEntityColonyBuilding) te).setBlueprintPath(path); } catch (Exception ignored) {}
                }
                blueprintNote = " (blueprint " + pack.getName() + "/" + path + ")";
            }
        } else {
            blueprintNote = " (no known blueprint path for type '" + typeName + "', placed without one)";
        }

        return "placed " + blockId + " at " + x + "," + y + "," + z + blueprintNote;
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
