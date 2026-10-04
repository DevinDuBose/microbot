package net.runelite.client.plugins.microbot.devin.inferno;

import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable snapshot of one plane's collision flags, with allocation-free
 * ports of RuneLite's {@link WorldArea#hasLineOfSightTo(WorldView, WorldArea)}
 * and {@link WorldArea#canTravelInDirection(WorldView, int, int, java.util.function.Predicate)}.
 *
 * The originals walk {@code Tile}/{@code LocalPoint} objects and cost tens of
 * microseconds per call — far too slow for the hundreds of thousands of
 * checks a predicted threat map needs, and they must run on the client
 * thread. The snapshot is taken on the client thread (one array copy) and the
 * ports run anywhere. InfernoGridTest asserts both ports return exactly what
 * RuneLite's code returns across thousands of random arenas.
 */
public final class InfernoGrid {

    /** Occupancy test in scene coordinates (other NPCs blocking a step). */
    @FunctionalInterface
    public interface TileFilter {
        boolean free(int sceneX, int sceneY);
    }

    public static final TileFilter ALL_FREE = (x, y) -> true;

    final int baseX;
    final int baseY;
    final int plane;
    final int sizeX;
    final int sizeY;
    private final int[][] flags;

    public InfernoGrid(int baseX, int baseY, int plane, int[][] flags) {
        this.baseX = baseX;
        this.baseY = baseY;
        this.plane = plane;
        this.flags = flags;
        this.sizeX = flags.length;
        this.sizeY = flags.length == 0 ? 0 : flags[0].length;
    }

    /** Copies the current plane's collision flags. Call on the client thread. Null if unavailable. */
    public static InfernoGrid snapshot(WorldView wv) {
        CollisionData[] maps = wv.getCollisionMaps();
        if (maps == null || maps[wv.getPlane()] == null) {
            return null;
        }
        int[][] src = maps[wv.getPlane()].getFlags();
        int[][] copy = new int[src.length][];
        for (int i = 0; i < src.length; i++) {
            copy[i] = src[i].clone();
        }
        return new InfernoGrid(wv.getBaseX(), wv.getBaseY(), wv.getPlane(), copy);
    }

    public int sceneX(WorldPoint p) {
        return p.getX() - baseX;
    }

    public int sceneY(WorldPoint p) {
        return p.getY() - baseY;
    }

    public boolean inScene(int sx, int sy) {
        return sx >= 0 && sy >= 0 && sx < sizeX && sy < sizeY;
    }

    public boolean isStandable(WorldPoint p) {
        int sx = sceneX(p);
        int sy = sceneY(p);
        return p.getPlane() == plane && inScene(sx, sy) && (flags[sx][sy] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
    }

    // ---------------------------------------------------------------- line of sight

    /**
     * Port of {@code new WorldArea(target, 1, 1).hasLineOfSightTo(wv, area)}:
     * line of sight from a single tile to an area.
     */
    public boolean hasLineOfSight(WorldPoint target, WorldArea area) {
        if (target.getPlane() != area.getPlane() || target.getPlane() != plane) {
            return false;
        }
        int tx = target.getX();
        int ty = target.getY();
        // RuneLite: the 1x1 side's only candidate is itself unless the areas intersect.
        if (tx >= area.getX() && tx < area.getX() + area.getWidth()
                && ty >= area.getY() && ty < area.getY() + area.getHeight()) {
            return false;
        }
        int fsx = tx - baseX;
        int fsy = ty - baseY;
        if (!inScene(fsx, fsy)) {
            return false;
        }
        int ax = area.getX();
        int ay = area.getY();
        int ax2 = ax + area.getWidth() - 1;
        int ay2 = ay + area.getHeight() - 1;
        for (int x = ax; x <= ax2; x++) {
            for (int y = ay; y <= ay2; y++) {
                boolean edge = x == ax || x == ax2 || y == ay || y == ay2;
                if (!edge || !visibleCandidate(tx, ty, ax, ay, ax2, ay2, x, y)) {
                    continue;
                }
                int sx = x - baseX;
                int sy = y - baseY;
                if (!inScene(sx, sy)) {
                    continue;
                }
                if (tileLineOfSight(fsx, fsy, sx, sy)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Port of the private {@code WorldArea.isVisibleCandidate} with "this" =
     * the 1x1 target tile (tx, ty) and "other" = the area (ax..ax2, ay..ay2).
     */
    private static boolean visibleCandidate(int tx, int ty, int ax, int ay, int ax2, int ay2, int px, int py) {
        if (tx > ax2) {
            if (ty < ay) {
                return px == ax2 || py == ay;
            }
            if (ty > ay2) {
                return px == ax2 || py == ay2;
            }
            return px == ax2;
        } else if (tx < ax) {
            if (ty < ay) {
                return px == ax || py == ay;
            }
            if (ty > ay2) {
                return px == ax || py == ay2;
            }
            return px == ax;
        } else if (ty > ay2) {
            return py == ay2;
        } else if (ty < ay) {
            return py == ay;
        }
        return false;
    }

    /** Port of the private tile-to-tile {@code WorldArea.hasLineOfSightTo(WorldView, Tile, Tile)}. */
    private boolean tileLineOfSight(int x1, int y1, int x2, int y2) {
        if (x1 == x2 && y1 == y2) {
            return true;
        }
        int dx = x2 - x1;
        int dy = y2 - y1;
        int dxAbs = Math.abs(dx);
        int dyAbs = Math.abs(dy);

        int xFlags = CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL;
        int yFlags = CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL;
        if (dx < 0) {
            xFlags |= CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST;
        } else {
            xFlags |= CollisionDataFlag.BLOCK_LINE_OF_SIGHT_WEST;
        }
        if (dy < 0) {
            yFlags |= CollisionDataFlag.BLOCK_LINE_OF_SIGHT_NORTH;
        } else {
            yFlags |= CollisionDataFlag.BLOCK_LINE_OF_SIGHT_SOUTH;
        }

        if (dxAbs > dyAbs) {
            int x = x1;
            int yBig = y1 << 16;
            int slope = (dy << 16) / dxAbs;
            yBig += 0x8000;
            if (dy < 0) {
                yBig--;
            }
            int direction = dx < 0 ? -1 : 1;
            while (x != x2) {
                x += direction;
                int y = yBig >>> 16;
                if ((flags[x][y] & xFlags) != 0) {
                    return false;
                }
                yBig += slope;
                int nextY = yBig >>> 16;
                if (nextY != y && (flags[x][nextY] & yFlags) != 0) {
                    return false;
                }
            }
        } else {
            int y = y1;
            int xBig = x1 << 16;
            int slope = (dx << 16) / dyAbs;
            xBig += 0x8000;
            if (dx < 0) {
                xBig--;
            }
            int direction = dy < 0 ? -1 : 1;
            while (y != y2) {
                y += direction;
                int x = xBig >>> 16;
                if ((flags[x][y] & yFlags) != 0) {
                    return false;
                }
                xBig += slope;
                int nextX = xBig >>> 16;
                if (nextX != x && (flags[nextX][y] & xFlags) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- movement

    /**
     * Port of {@code WorldArea.canTravelInDirection(wv, dx, dy, extraCondition)}
     * for an area whose south-west corner is at scene (sx, sy). Kept faithful,
     * including the original's inverted extraCondition use in the 1-wide
     * diagonal checks.
     */
    public boolean canTravelInDirection(int sx, int sy, int width, int height, int dx, int dy, TileFilter free) {
        dx = Integer.signum(dx);
        dy = Integer.signum(dy);
        if (dx == 0 && dy == 0) {
            return true;
        }

        int startX = sx + dx;
        int startY = sy + dy;
        int checkX = startX + (dx > 0 ? width - 1 : 0);
        int checkY = startY + (dy > 0 ? height - 1 : 0);
        int endX = startX + width - 1;
        int endY = startY + height - 1;

        int xFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int yFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int xyFlags = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int xWallFlagsSouth = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int xWallFlagsNorth = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int yWallFlagsWest = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        int yWallFlagsEast = CollisionDataFlag.BLOCK_MOVEMENT_FULL;

        if (dx < 0) {
            xFlags |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
            xWallFlagsSouth |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
            xWallFlagsNorth |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
        }
        if (dx > 0) {
            xFlags |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
            xWallFlagsSouth |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
            xWallFlagsNorth |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
        }
        if (dy < 0) {
            yFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
            yWallFlagsWest |= CollisionDataFlag.BLOCK_MOVEMENT_WEST | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
            yWallFlagsEast |= CollisionDataFlag.BLOCK_MOVEMENT_EAST | CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
        }
        if (dy > 0) {
            yFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
            yWallFlagsWest |= CollisionDataFlag.BLOCK_MOVEMENT_WEST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
            yWallFlagsEast |= CollisionDataFlag.BLOCK_MOVEMENT_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
        }
        if (dx < 0 && dy < 0) {
            xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
        }
        if (dx < 0 && dy > 0) {
            xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
        }
        if (dx > 0 && dy < 0) {
            xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
        }
        if (dx > 0 && dy > 0) {
            xyFlags |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
        }

        if (dx != 0) {
            for (int y = startY; y <= endY; y++) {
                if ((flags[checkX][y] & xFlags) != 0 || !free.free(checkX, y)) {
                    return false;
                }
            }
            for (int y = startY + 1; y <= endY; y++) {
                if ((flags[checkX][y] & xWallFlagsSouth) != 0) {
                    return false;
                }
            }
            for (int y = endY - 1; y >= startY; y--) {
                if ((flags[checkX][y] & xWallFlagsNorth) != 0) {
                    return false;
                }
            }
        }
        if (dy != 0) {
            for (int x = startX; x <= endX; x++) {
                if ((flags[x][checkY] & yFlags) != 0 || !free.free(x, checkY)) {
                    return false;
                }
            }
            for (int x = startX + 1; x <= endX; x++) {
                if ((flags[x][checkY] & yWallFlagsWest) != 0) {
                    return false;
                }
            }
            for (int x = endX - 1; x >= startX; x--) {
                if ((flags[x][checkY] & yWallFlagsEast) != 0) {
                    return false;
                }
            }
        }
        if (dx != 0 && dy != 0) {
            if ((flags[checkX][checkY] & xyFlags) != 0 || !free.free(checkX, checkY)) {
                return false;
            }
            if (width == 1) {
                if ((flags[checkX][checkY - dy] & xFlags) != 0 && free.free(checkX, startY)) {
                    return false;
                }
            }
            if (height == 1) {
                if ((flags[checkX - dx][checkY] & yFlags) != 0 && free.free(startX, checkY)) {
                    return false;
                }
            }
        }
        return true;
    }
}
