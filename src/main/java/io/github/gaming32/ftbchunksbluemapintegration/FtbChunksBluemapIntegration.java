package io.github.gaming32.ftbchunksbluemapintegration;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.apache.commons.lang3.StringUtils;
import org.quiltmc.qup.json.JsonReader;
import org.quiltmc.qup.json.JsonWriter;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class FtbChunksBluemapIntegration implements ModInitializer {
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String MARKER_SET_KEY = "ftbchunks-bluemap-integration";
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("ftbchunks-bluemap.json5");

    public static final FtbChunksBluemapConfig CONFIG = new FtbChunksBluemapConfig();

    private static MinecraftServer minecraftServer;

    private static int updateIn;

    @Override
    public void onInitialize() {
        loadConfig();
        BlueMapAPI.onEnable(FtbChunksBluemapIntegration::updateClaims);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> minecraftServer = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> minecraftServer = null);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("ftbchunks-bluemap")
            .requires(s -> s.hasPermission(2))
            .then(literal("refresh-now")
                .requires(s -> BlueMapAPI.getInstance().isPresent())
                .executes(ctx -> {
                    final BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
                    if (api == null) {
                        ctx.getSource().sendFailure(Component.literal("BlueMap not loaded").withStyle(ChatFormatting.RED));
                        return 0;
                    }
                    updateClaims(api);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("BlueMap FTB Chunks claims refreshed").withStyle(ChatFormatting.GREEN),
                        true
                    );
                    return Command.SINGLE_SUCCESS;
                })
            )
            .then(literal("refresh-in")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("FTB Chunks BlueMap will refresh in ").append(
                                Component.literal((updateIn / 20) + "s").withStyle(ChatFormatting.GREEN)
                        ),
                        true
                    );
                    return Command.SINGLE_SUCCESS;
                })
                .then(argument("time", TimeArgument.time())
                    .executes(ctx -> {
                        updateIn = IntegerArgumentType.getInteger(ctx, "time");
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("FTB Chunks BlueMap will refresh in ").append(
                                Component.literal((updateIn / 20) + "s").withStyle(ChatFormatting.GREEN)
                            ),
                            true
                        );
                        return Command.SINGLE_SUCCESS;
                    })
                )
            )
            .then(literal("refresh-every")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("FTB Chunks BlueMap auto refreshes every ").append(
                            Component.literal((CONFIG.getUpdateInterval() / 20) + "s").withStyle(ChatFormatting.GREEN)
                        ),
                        true
                    );
                    return Command.SINGLE_SUCCESS;
                })
                .then(argument("interval", TimeArgument.time())
                    .executes(ctx -> {
                        final int interval = IntegerArgumentType.getInteger(ctx, "interval");
                        CONFIG.setUpdateInterval(interval);
                        if (interval < updateIn) {
                            updateIn = interval;
                        }
                        saveConfig();
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("FTB Chunks BlueMap will auto refresh every ").append(
                                Component.literal((interval / 20) + "s").withStyle(ChatFormatting.GREEN)
                            ),
                            true
                        );
                        return Command.SINGLE_SUCCESS;
                    })
                )
            )
            .then(literal("reload")
                .executes(ctx -> {
                    loadConfig();
                    if (CONFIG.getUpdateInterval() < updateIn) {
                        updateIn = CONFIG.getUpdateInterval();
                    }
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("Reloaded FTB Chunks BlueMap config").withStyle(ChatFormatting.GREEN),
                        true
                    );
                    return Command.SINGLE_SUCCESS;
                })
            )
        ));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (updateIn <= 0) return;
            if (--updateIn <= 0) {
                BlueMapAPI.getInstance().ifPresent(FtbChunksBluemapIntegration::updateClaims);
            }
        });
    }

    public static void loadConfig() {
        try (JsonReader reader = JsonReader.json5(CONFIG_FILE)) {
            CONFIG.read(reader);
        } catch (Exception e) {
            LOGGER.warn("Failed to read {}.", CONFIG_FILE, e);
        }
        saveConfig();
    }

    public static void saveConfig() {
        try (JsonWriter writer = JsonWriter.json5(CONFIG_FILE)) {
            CONFIG.write(writer);
        } catch (Exception e) {
            LOGGER.error("Failed to write {}.", CONFIG_FILE, e);
        }
        LOGGER.info("Saved FTB Chunks BlueMap config");
    }

    public static void updateClaims(BlueMapAPI blueMap) {
        if (minecraftServer == null) {
            LOGGER.warn("updateClaims called with minecraftServer == null!");
            return;
        }
        LOGGER.info("Refreshing FTB Chunks BlueMap markers");

        try {
            final var manager = FTBChunksAPI.api().getManager();
            if (manager == null) {
                LOGGER.warn("FTB Chunks manager is null!");
                return;
            }

            FTBTeamsAPI.api().getManager().getTeams().forEach(team -> {
                final var teamData = manager.getOrCreateData(team);
                if (teamData == null) {
                    return;
                }

                // Check if team has any claimed chunks
                var claimedChunks = teamData.getClaimedChunks();
                if (claimedChunks.isEmpty()) {
                    return; // Skip teams with no claims
                }

                String name = team.getShortName();
                final String idName = team.getId().toString();
                final String displayName = StringUtils.isBlank(name) ? "Team " + team.getShortName() : name;

                LOGGER.info("Processing team: {} (ID: {}) with {} claimed chunks", displayName, idName, claimedChunks.size());

                // Group chunks by dimension
                Map<ResourceKey<net.minecraft.world.level.Level>, Set<ChunkPos>> chunksByDimension = new HashMap<>();

                for (ClaimedChunk claimedChunk : claimedChunks) {
                    var chunkDimPos = claimedChunk.getPos();
                    ResourceKey<net.minecraft.world.level.Level> dimension = chunkDimPos.dimension();

                    ChunkPos chunkPos = new ChunkPos(chunkDimPos.x(), chunkDimPos.z());
                    chunksByDimension.computeIfAbsent(dimension, k -> new HashSet<>()).add(chunkPos);
                }

                // Process each dimension
                chunksByDimension.forEach((dimension, chunks) -> {
                    final BlueMapWorld world = blueMap.getWorld(dimension).orElse(null);
                    if (world == null) {
                        LOGGER.warn("BlueMap world not found for dimension: {}", dimension);
                        return;
                    }

                    LOGGER.info("Creating shapes for {} chunks in dimension {}", chunks.size(), dimension.location());
                    long startTime = System.currentTimeMillis();
                    final List<ShapeHolder> shapes = createShapes(chunks);
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    LOGGER.info("Created {} shape(s) for team {} in {}ms", shapes.size(), displayName, elapsedTime);

                    world.getMaps().forEach(map -> {
                        LOGGER.info("Adding markers to map: {}", map.getId());
                        final Map<String, Marker> markers = map
                            .getMarkerSets()
                            .computeIfAbsent(MARKER_SET_KEY, k ->
                                MarkerSet.builder()
                                    .toggleable(true)
                                    .label("FTB Chunks")
                                    .build()
                            )
                            .getMarkers();
                        final float minY = CONFIG.getMarkerMinY();
                        final float maxY = CONFIG.getMarkerMaxY();
                        final boolean flatPlane = Mth.equal(minY, maxY);

                        markers.keySet().removeIf(k -> k.startsWith(idName + "---"));

                        // Get team color
                        int teamColor = team.getProperty(TeamProperties.COLOR).rgb();

                        for (int i = 0; i < shapes.size(); i++) {
                            final ShapeHolder shape = shapes.get(i);
                            final String markerId = idName + "---" + i;
                            markers.put(markerId,
                                flatPlane
                                    ? ShapeMarker.builder()
                                        .label(displayName)
                                        .fillColor(new Color(teamColor, 102))
                                        .lineColor(new Color(teamColor, 255))
                                        .shape(shape.baseShape(), minY)
                                        .holes(shape.holes())
                                        .depthTestEnabled(CONFIG.isDepthTest())
                                        .build()
                                    : ExtrudeMarker.builder()
                                        .label(displayName)
                                        .fillColor(new Color(teamColor, 102))
                                        .lineColor(new Color(teamColor, 255))
                                        .shape(shape.baseShape(), minY, maxY)
                                        .holes(shape.holes())
                                        .depthTestEnabled(CONFIG.isDepthTest())
                                        .build()
                            );
                            LOGGER.debug("Added marker {} for team {}", markerId, displayName);
                        }
                        LOGGER.info("Added {} markers for team {} on map {}", shapes.size(), displayName, map.getId());
                    });
                });
            });

            LOGGER.info("Refreshed FTB Chunks BlueMap markers");
        } catch (Exception e) {
            LOGGER.error("Error updating FTB Chunks claims", e);
        }

        updateIn = CONFIG.getUpdateInterval();
    }

    public static List<ShapeHolder> createShapes(Set<ChunkPos> chunks) {
        LOGGER.info("createShapes: Starting with {} chunks", chunks.size());
        long startGroups = System.currentTimeMillis();
        List<Set<ChunkPos>> groups = createChunkGroups(chunks);
        LOGGER.info("createChunkGroups: Created {} groups in {}ms", groups.size(), System.currentTimeMillis() - startGroups);

        List<ShapeHolder> shapes = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            long startShape = System.currentTimeMillis();
            LOGGER.info("Creating ShapeHolder for group {} with {} chunks", i, groups.get(i).size());
            ShapeHolder shape = ShapeHolder.create(groups.get(i));
            LOGGER.info("ShapeHolder {} created in {}ms", i, System.currentTimeMillis() - startShape);
            shapes.add(shape);
        }
        return shapes;
    }

    public static List<Set<ChunkPos>> createChunkGroups(Set<ChunkPos> chunks) {
        final List<Set<ChunkPos>> result = new ArrayList<>();
        final Set<ChunkPos> visited = new HashSet<>();
        int groupNum = 0;
        for (final ChunkPos chunk : chunks) {
            if (visited.contains(chunk)) continue;
            long startFind = System.currentTimeMillis();
            final Set<ChunkPos> neighbors = findNeighbors(chunk, chunks);
            LOGGER.info("Found group {} with {} chunks in {}ms", groupNum++, neighbors.size(), System.currentTimeMillis() - startFind);
            result.add(neighbors);
            visited.addAll(neighbors);
        }
        return result;
    }

    public static Set<ChunkPos> findNeighbors(ChunkPos chunk, Set<ChunkPos> chunks) {
        if (!chunks.contains(chunk)) {
            throw new IllegalArgumentException("chunks must contain chunk to find neighbors!");
        }
        final Set<ChunkPos> visited = new HashSet<>();
        final Queue<ChunkPos> toVisit = new ArrayDeque<>();
        visited.add(chunk);
        toVisit.add(chunk);
        while (!toVisit.isEmpty()) {
            final ChunkPos visiting = toVisit.remove();
            for (final ChunkPosDirection dir : ChunkPosDirection.values()) {
                final ChunkPos offsetPos = dir.add(visiting);
                if (!chunks.contains(offsetPos) || !visited.add(offsetPos)) continue;
                toVisit.add(offsetPos);
            }
        }
        return visited;
    }
}
