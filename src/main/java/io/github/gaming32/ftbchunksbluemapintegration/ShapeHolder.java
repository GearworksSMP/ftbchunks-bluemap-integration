package io.github.gaming32.ftbchunksbluemapintegration;

import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.math.Shape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.stream.Collectors;

public record ShapeHolder(Shape baseShape, Shape... holes) {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ShapeHolder.class);

    public static ShapeHolder create(Set<ChunkPos> chunks) {
        LOGGER.info("ShapeHolder.create: Starting with {} chunks", chunks.size());
        LOGGER.info("ShapeHolder.create: Chunk positions: {}", chunks);
        long start = System.currentTimeMillis();
        Shape base = createBaseShape(chunks);
        LOGGER.info("ShapeHolder.create: Base shape created in {}ms with {} points",
            System.currentTimeMillis() - start, base.getPoints().length);

        start = System.currentTimeMillis();
        Set<ChunkPos> cutout = cutoutChunks(chunks);
        LOGGER.info("ShapeHolder.create: Found {} cutout chunks in {}ms", cutout.size(), System.currentTimeMillis() - start);

        start = System.currentTimeMillis();
        Shape[] holeShapes = FtbChunksBluemapIntegration.createChunkGroups(cutout)
                .stream()
                .map(ShapeHolder::createBaseShape)
                .toArray(Shape[]::new);
        LOGGER.info("ShapeHolder.create: Created {} hole shapes in {}ms", holeShapes.length, System.currentTimeMillis() - start);
        for (int i = 0; i < holeShapes.length; i++) {
            LOGGER.info("  Hole {}: {} points", i, holeShapes[i].getPoints().length);
        }

        return new ShapeHolder(base, holeShapes);
    }

    private static Shape createBaseShape(Set<ChunkPos> chunks) {
        LOGGER.debug("createBaseShape: Starting with {} chunks", chunks.size());

        if (chunks.isEmpty()) {
            LOGGER.warn("createBaseShape called with empty chunk set");
            return new Shape(new Vector2d(0, 0), new Vector2d(16, 0), new Vector2d(16, 16), new Vector2d(0, 16));
        }

        // Single chunk - just return a rectangle
        if (chunks.size() == 1) {
            ChunkPos chunk = chunks.iterator().next();
            return new Shape(
                new Vector2d(chunk.getMinBlockX(), chunk.getMinBlockZ()),
                new Vector2d(chunk.getMaxBlockX() + 1, chunk.getMinBlockZ()),
                new Vector2d(chunk.getMaxBlockX() + 1, chunk.getMaxBlockZ() + 1),
                new Vector2d(chunk.getMinBlockX(), chunk.getMaxBlockZ() + 1)
            );
        }

        // Multiple chunks - trace the exact perimeter
        // Find all corners that are on the outer edge
        List<Vector2d> points = new ArrayList<>();

        // Find the leftmost-topmost chunk to start
        ChunkPos start = chunks.stream()
            .min((a, b) -> {
                int cmp = Integer.compare(a.x, b.x);
                return cmp != 0 ? cmp : Integer.compare(a.z, b.z);
            })
            .orElseThrow();

        // Start at the top-left corner of the starting chunk, facing right
        // Track our position in block coordinates along chunk edges
        int blockX = start.x * 16;  // Top-left corner X
        int blockZ = start.z * 16;  // Top-left corner Z
        int direction = 0; // 0=right, 1=down, 2=left, 3=up

        int startBlockX = blockX;
        int startBlockZ = blockZ;
        int startDir = direction;

        int iterations = 0;
        int maxIterations = chunks.size() * 20;

        while (true) {
            // Add current corner point
            Vector2d corner = new Vector2d(blockX, blockZ);
            if (points.isEmpty() || !points.get(points.size() - 1).equals(corner)) {
                points.add(corner);
            }

            // Determine which chunk is to our left based on current position and direction
            ChunkPos leftChunk = getChunkAtLeft(blockX, blockZ, direction);

            if (chunks.contains(leftChunk)) {
                // Turn left and move forward
                direction = (direction + 3) % 4;
                int[] newPos = moveAlongEdge(blockX, blockZ, direction);
                blockX = newPos[0];
                blockZ = newPos[1];
            } else {
                // Check chunk straight ahead
                ChunkPos straightChunk = getChunkAhead(blockX, blockZ, direction);

                if (chunks.contains(straightChunk)) {
                    // Move straight
                    int[] newPos = moveAlongEdge(blockX, blockZ, direction);
                    blockX = newPos[0];
                    blockZ = newPos[1];
                } else {
                    // Turn right (stay at same position)
                    direction = (direction + 1) % 4;
                }
            }

            iterations++;
            if (iterations > maxIterations) {
                LOGGER.error("Perimeter tracing exceeded max iterations");
                ChunkPos minChunk = getBound(chunks, Math::min);
                ChunkPos maxChunk = getBound(chunks, Math::max);
                return new Shape(
                    new Vector2d(minChunk.getMinBlockX(), minChunk.getMinBlockZ()),
                    new Vector2d(maxChunk.getMaxBlockX() + 1, minChunk.getMinBlockZ()),
                    new Vector2d(maxChunk.getMaxBlockX() + 1, maxChunk.getMaxBlockZ() + 1),
                    new Vector2d(minChunk.getMinBlockX(), maxChunk.getMaxBlockZ() + 1)
                );
            }

            // Check if we've completed the loop
            if (iterations > 1 && blockX == startBlockX && blockZ == startBlockZ && direction == startDir) {
                break;
            }
        }

        LOGGER.debug("createBaseShape: Traced perimeter with {} points in {} iterations", points.size(), iterations);

        // Remove duplicate last point if it equals the first (closed loop)
        if (points.size() > 1 && points.get(0).equals(points.get(points.size() - 1))) {
            points.remove(points.size() - 1);
            LOGGER.debug("Removed duplicate closing point, now {} points", points.size());
        }

        // Debug: log if we have too few points
        if (points.size() < 4) {
            LOGGER.error("Invalid shape with only {} points! Points: {}", points.size(), points);
            LOGGER.error("Chunks in group: {}", chunks);
            // Return bounding box as fallback
            ChunkPos minChunk = getBound(chunks, Math::min);
            ChunkPos maxChunk = getBound(chunks, Math::max);
            return new Shape(
                new Vector2d(minChunk.getMinBlockX(), minChunk.getMinBlockZ()),
                new Vector2d(maxChunk.getMaxBlockX() + 1, minChunk.getMinBlockZ()),
                new Vector2d(maxChunk.getMaxBlockX() + 1, maxChunk.getMaxBlockZ() + 1),
                new Vector2d(minChunk.getMinBlockX(), maxChunk.getMaxBlockZ() + 1)
            );
        }

        List<Vector2d> simplified = simplifyPoints(points);
        LOGGER.debug("After simplification: {} points (was {})", simplified.size(), points.size());

        if (simplified.size() < 4) {
            LOGGER.error("Simplification reduced points from {} to {} - this creates invalid shapes!", points.size(), simplified.size());
            LOGGER.error("Original points: {}", points);
            LOGGER.error("Simplified points: {}", simplified);
            LOGGER.error("Chunks: {}", chunks);
            // Don't simplify if it would create an invalid shape
            return new Shape(points);
        }

        return new Shape(simplified);
    }

    // Get the chunk that is to the left of our current position
    private static ChunkPos getChunkAtLeft(int blockX, int blockZ, int direction) {
        return switch (direction) {
            case 0 -> new ChunkPos(blockX / 16, (blockZ - 1) / 16);      // facing right, left is up
            case 1 -> new ChunkPos(blockX / 16, blockZ / 16);            // facing down, left is right
            case 2 -> new ChunkPos((blockX - 1) / 16, blockZ / 16);      // facing left, left is down
            case 3 -> new ChunkPos((blockX - 1) / 16, (blockZ - 1) / 16); // facing up, left is left
            default -> new ChunkPos(blockX / 16, blockZ / 16);
        };
    }

    // Get the chunk that is straight ahead of our current position
    private static ChunkPos getChunkAhead(int blockX, int blockZ, int direction) {
        return switch (direction) {
            case 0 -> new ChunkPos(blockX / 16, blockZ / 16);            // facing right
            case 1 -> new ChunkPos((blockX - 1) / 16, blockZ / 16);      // facing down
            case 2 -> new ChunkPos((blockX - 1) / 16, (blockZ - 1) / 16); // facing left
            case 3 -> new ChunkPos(blockX / 16, (blockZ - 1) / 16);      // facing up
            default -> new ChunkPos(blockX / 16, blockZ / 16);
        };
    }

    // Move one chunk-edge (16 blocks) in the given direction
    private static int[] moveAlongEdge(int blockX, int blockZ, int direction) {
        return switch (direction) {
            case 0 -> new int[]{blockX + 16, blockZ};      // right
            case 1 -> new int[]{blockX, blockZ + 16};      // down
            case 2 -> new int[]{blockX - 16, blockZ};      // left
            case 3 -> new int[]{blockX, blockZ - 16};      // up
            default -> new int[]{blockX, blockZ};
        };
    }

    private static List<Vector2d> tracePerimeter(Set<EdgeSegment> edges, ChunkPos start) {
        if (edges.isEmpty()) {
            return List.of();
        }

        // Make a mutable copy
        Set<EdgeSegment> remainingEdges = new HashSet<>(edges);
        List<Vector2d> points = new ArrayList<>();

        // Find leftmost-topmost edge to start
        EdgeSegment current = remainingEdges.stream()
            .min((a, b) -> {
                int cmp = Integer.compare(a.x1, b.x1);
                return cmp != 0 ? cmp : Integer.compare(a.z1, b.z1);
            })
            .orElse(null);

        if (current == null) {
            return List.of();
        }

        EdgeSegment first = current;
        remainingEdges.remove(current);
        points.add(new Vector2d(current.x1 * 16, current.z1 * 16));

        int maxIterations = edges.size() * 2;
        int iterations = 0;

        while (iterations++ < maxIterations) {
            // Find next connecting edge
            EdgeSegment next = findConnectingEdge(remainingEdges, current.x2, current.z2);

            if (next == null) {
                // Check if we've completed a loop back to the start
                if (current.x2 == first.x1 && current.z2 == first.z1) {
                    LOGGER.debug("Completed perimeter loop, {} edges unused (likely holes)", remainingEdges.size());
                    break;
                }

                // Can't find next edge - this shouldn't happen for a valid perimeter
                LOGGER.warn("Perimeter trace broken at ({}, {}), {} edges remaining", current.x2, current.z2, remainingEdges.size());

                // Try to close the shape by connecting back to start
                if (points.size() >= 3) {
                    points.add(new Vector2d(first.x1 * 16, first.z1 * 16));
                }
                break;
            }

            remainingEdges.remove(next);

            // Don't add duplicate points
            Vector2d nextPoint = new Vector2d(next.x1 * 16, next.z1 * 16);
            if (points.isEmpty() || !points.get(points.size() - 1).equals(nextPoint)) {
                points.add(nextPoint);
            }

            current = next;

            // Check if we completed the loop
            if (current.x2 == first.x1 && current.z2 == first.z1) {
                LOGGER.debug("Completed perimeter loop");
                break;
            }
        }

        // Ensure we have at least 3 points for a valid shape
        if (points.size() < 3) {
            LOGGER.error("Generated shape has only {} points, falling back to bounding box", points.size());
            // Return a simple rectangle as fallback
            return List.of(
                new Vector2d(start.getMinBlockX(), start.getMinBlockZ()),
                new Vector2d(start.getMaxBlockX() + 1, start.getMinBlockZ()),
                new Vector2d(start.getMaxBlockX() + 1, start.getMaxBlockZ() + 1),
                new Vector2d(start.getMinBlockX(), start.getMaxBlockZ() + 1)
            );
        }

        return points;
    }

    private static EdgeSegment findConnectingEdge(Set<EdgeSegment> edges, int x, int z) {
        for (EdgeSegment edge : edges) {
            if (edge.x1 == x && edge.z1 == z) {
                return edge;
            }
        }
        return null;
    }

    private static record EdgeSegment(int x1, int z1, int x2, int z2) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EdgeSegment e)) return false;
            return x1 == e.x1 && z1 == e.z1 && x2 == e.x2 && z2 == e.z2;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x1, z1, x2, z2);
        }
    }

    private static Set<ChunkPos> cutoutChunks(Set<ChunkPos> chunks) {
        final ChunkPos minChunk = getBound(chunks, Math::min);
        final ChunkPos maxChunk = getBound(chunks, Math::max);

        // Calculate bounding box size
        final int width = maxChunk.x - minChunk.x + 1;
        final int height = maxChunk.z - minChunk.z + 1;
        final long boundingBoxSize = (long) width * height;

        // Skip hole detection for very large areas to avoid performance issues
        // If bounding box is larger than 10,000 chunks, skip hole detection
        if (boundingBoxSize > 10000) {
            return Collections.emptySet();
        }

        final Queue<ChunkPos> toVisit = new ArrayDeque<>();
        for (int x = minChunk.x; x <= maxChunk.x; x++) {
            for (int z = minChunk.z; z <= maxChunk.z; z++) {
                if (x > minChunk.x && x < maxChunk.x && z > minChunk.z && z < maxChunk.z) continue;
                final ChunkPos chunk = new ChunkPos(x, z);
                if (chunks.contains(chunk)) continue;
                toVisit.add(chunk);
            }
        }

        final Set<ChunkPos> outsideChunks = new HashSet<>(toVisit);
        while (!toVisit.isEmpty()) {
            final ChunkPos chunk = toVisit.remove();
            for (final ChunkPosDirection dir : ChunkPosDirection.values()) {
                final ChunkPos offsetPos = dir.add(chunk);
                if (
                    offsetPos.x < minChunk.x || offsetPos.x > maxChunk.x ||
                        offsetPos.z < minChunk.z || offsetPos.z > maxChunk.z ||
                        chunks.contains(offsetPos) || !outsideChunks.add(offsetPos)
                ) continue;
                toVisit.add(offsetPos);
            }
        }

        // Manually iterate instead of using ChunkPos.rangeClosed() which is very slow
        final Set<ChunkPos> holes = new HashSet<>();
        for (int x = minChunk.x; x <= maxChunk.x; x++) {
            for (int z = minChunk.z; z <= maxChunk.z; z++) {
                final ChunkPos c = new ChunkPos(x, z);
                if (!chunks.contains(c) && !outsideChunks.contains(c)) {
                    holes.add(c);
                }
            }
        }
        return holes;
    }

    private static Vector2d vector(BlockPos pos) {
        return new Vector2d(pos.getX(), pos.getZ());
    }

    private static List<Vector2d> simplifyPoints(List<Vector2d> points) {
        if (points.size() < 4) {
            return points;
        }

        final List<Vector2d> result = new ArrayList<>();
        result.add(points.get(0));

        for (int i = 1; i < points.size() - 1; i++) {
            final Vector2d last = points.get(i - 1);
            final Vector2d point = points.get(i);
            final Vector2d next = points.get(i + 1);
            if (!point.sub(last).normalize().equals(next.sub(point).normalize())) {
                result.add(point);
            }
        }

        final Vector2d lastPoint = points.get(points.size() - 1);
        if (!lastPoint.equals(points.get(0))) {
            result.add(lastPoint);
        }

        return result;
    }

    private static ChunkPos getBound(Iterable<ChunkPos> chunks, IntSelector selector) {
        final Iterator<ChunkPos> iterator = chunks.iterator();
        final ChunkPos first = iterator.next();
        int x = first.x;
        int z = first.z;
        while (iterator.hasNext()) {
            final ChunkPos pos = iterator.next();
            x = selector.select(x, pos.x);
            z = selector.select(z, pos.z);
        }
        return new ChunkPos(x, z);
    }

    @FunctionalInterface
    private interface IntSelector {
        int select(int a, int b);
    }
}
