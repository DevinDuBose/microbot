package net.runelite.client.plugins.microbot.devin.inferno;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure positioning / priority logic for the Inferno helper. Works on an
 * {@link InfernoGrid} collision snapshot, so it runs off the client thread
 * and can be unit-tested against synthetic arenas.
 *
 * NPC stepping, line of sight and attack range follow RS Mod's server rules
 * ({@link RsModPathing}): one validated step per tick toward
 * {@code RouteFinding.naiveDestination} around the target (diagonal, else x, else y; other
 * NPCs block), melee needs RS Mod's op range (edges touching, so no diagonals) and every other
 * attack needs the ap range (rectangle gap on both axes within range, not overlapping) plus line
 * of sight cast from the target to the NPC's closest tile. Pillars are solid
 * line-of-sight-blocking objects (verified in the game cache), so they reach it through the
 * collision map. {@link #RSMOD_RULES} = false restores the previous RuneLite-derived rules.
 */
public final class InfernoThreatModel {

    public static final int THREAT_MELEE = 1;
    public static final int THREAT_RANGE = 2;
    public static final int THREAT_MAGIC = 4;
    /** Blob and Jad pick a style per attack. */
    public static final int THREAT_UNKNOWN = 8;

    /**
     * true: RS Mod's NPC rules ({@link RsModPathing}). false: the previous model (RuneLite
     * {@code WorldArea} line of sight from the target to any visible edge tile, and the port of
     * RuneLite's removed {@code calculateNextTravellingPoint}). Kept for comparison runs.
     */
    public static boolean RSMOD_RULES = true;

    /** Steps simulated when predicting a monster walking into attack position (xKylee uses 30). */
    static final int MAX_PREDICTED_STEPS = 30;

    private InfernoThreatModel() {
    }

    /** A monster as the model sees it: what it is and where it stands. */
    public static final class Monster {
        final InfernoNpcType type;
        final WorldArea area;

        public Monster(InfernoNpcType type, WorldArea area) {
            this.type = type;
            this.area = area;
        }
    }

    public static int threatBit(InfernoNpcType type) {
        switch (type.getDefaultStyle()) {
            case MELEE:
                return THREAT_MELEE;
            case RANGE:
                return THREAT_RANGE;
            case MAGIC:
                return THREAT_MAGIC;
            default:
                return THREAT_UNKNOWN;
        }
    }

    /**
     * Whether a monster standing on {@code area} can attack a player on {@code target}. RS Mod
     * (NpcInteractionProcessor): melee uses the op range ({@link RsModPathing#isWithinOpRange}:
     * cardinally touching, not diagonal, not under), everything else the ap range with the NPC's
     * attack range ({@link RsModPathing#isWithinApRange}: not under, gap within range on both
     * axes, reverse line of sight).
     */
    public static boolean canAttack(InfernoGrid grid, InfernoNpcType type, WorldArea area, WorldPoint target) {
        if (!RSMOD_RULES) {
            return legacyCanAttack(grid, type, area, target);
        }
        if (target.getPlane() != area.getPlane() || target.getPlane() != grid.plane) {
            return false;
        }
        if (type.isMeleeOnly()) {
            return RsModPathing.isWithinOpRange(grid::rsmodFlag, area.getX(), area.getY(), area.getWidth(),
                    target.getX(), target.getY(), 1);
        }
        return RsModPathing.isWithinApRange(grid::rsmodFlag, area.getX(), area.getY(), area.getWidth(),
                target.getX(), target.getY(), 1, type.getRange());
    }

    /**
     * NPC line of sight toward a player on {@code target} (RS Mod: cast from the target's tile to
     * the NPC's closest tile, BLOCK_PLAYERS as extra flag). No range check.
     */
    public static boolean npcLineOfSight(InfernoGrid grid, WorldArea area, WorldPoint target) {
        if (!RSMOD_RULES) {
            return grid.hasLineOfSight(target, area);
        }
        if (target.getPlane() != area.getPlane() || target.getPlane() != grid.plane) {
            return false;
        }
        return RsModPathing.hasLineOfSight(grid::rsmodFlag, target.getX(), target.getY(), area.getX(), area.getY(),
                1, 1, area.getWidth(), area.getHeight(), RsModPathing.BLOCK_PLAYERS);
    }

    private static boolean legacyCanAttack(InfernoGrid grid, InfernoNpcType type, WorldArea area, WorldPoint target) {
        if (type.isMeleeOnly()) {
            // Melee needs cardinal adjacency; diagonal does not count.
            return area.isInMeleeDistance(target);
        }
        if (area.distanceTo(target) > type.getRange()) {
            return false;
        }
        return grid.hasLineOfSight(target, area);
    }

    /**
     * Whether the monster can attack {@code target} now or after walking
     * toward it with the standard NPC step ({@link #nextStep}). Monsters
     * stop as soon as they can attack, so the first position that can attack
     * decides it. {@code occupied} marks scene tiles held by NPCs (null =
     * none); the monster's own starting tiles never block it, as in xKylee.
     */
    public static boolean canReachToAttack(InfernoGrid grid, InfernoNpcType type, WorldArea start, WorldPoint target,
                                           boolean[][] occupied) {
        if (canAttack(grid, type, start, target)) {
            return true;
        }
        int ownX1 = start.getX() - grid.baseX;
        int ownY1 = start.getY() - grid.baseY;
        int ownX2 = ownX1 + start.getWidth() - 1;
        int ownY2 = ownY1 + start.getHeight() - 1;
        InfernoGrid.TileFilter free = (x, y) -> (x >= ownX1 && x <= ownX2 && y >= ownY1 && y <= ownY2)
                || occupied == null || !occupied[x][y];
        WorldArea targetArea = new WorldArea(target, 1, 1);
        WorldArea current = start;
        for (int i = 0; i < MAX_PREDICTED_STEPS; i++) {
            // xKylee steps every monster with stopAtMeleeDistance = true.
            WorldArea next = nextStep(grid, current, targetArea, true, free);
            if (next == null) {
                // Overlapping the player or leaving the scene: movement is unpredictable, assume it can attack.
                return true;
            }
            if (next.equals(current)) {
                return false;
            }
            if (canAttack(grid, type, next, target)) {
                return true;
            }
            current = next;
        }
        return false;
    }

    /**
     * One tick of RS Mod NPC movement toward {@code target} (NpcMovementProcessor + StepFactory):
     * the destination is {@code RouteFinding.naiveDestination} around the target; when the NPC
     * is not on it, it takes {@code StepFactory.validated} (diagonal, else x, else y; tiles that
     * {@code free} reports as held by another NPC carry BLOCK_NPCS and block the step).
     *
     * <p>An NPC overlapping its target does not path at all: RS Mod steps it one tile in a random
     * cardinal direction, or leaves it in place when the target player is busy interacting
     * ("Red-X"). That is unpredictable here: null when {@code stopAtMeleeDistance}, else
     * {@code from}. {@code stopAtMeleeDistance} has no other effect under RS Mod rules: NPCs stop
     * because they can attack, which callers check before stepping.
     *
     * @return the next area, the same instance when it does not move, or null (see above)
     */
    public static WorldArea nextStep(InfernoGrid grid, WorldArea from, WorldArea target, boolean stopAtMeleeDistance,
                              InfernoGrid.TileFilter free) {
        if (!RSMOD_RULES) {
            return legacyNextStep(grid, from, target, stopAtMeleeDistance, free);
        }
        if (from.getPlane() != target.getPlane()) {
            return null;
        }
        int size = from.getWidth();
        if (RsModPathing.collides(from.getX(), from.getY(), target.getX(), target.getY(),
                size, from.getHeight(), target.getWidth(), target.getHeight())) {
            return stopAtMeleeDistance ? null : from;
        }
        int[] dest = RsModPathing.naiveDestination(from.getX(), from.getY(), size, from.getHeight(),
                target.getX(), target.getY(), target.getWidth(), target.getHeight());
        if (dest[0] == from.getX() && dest[1] == from.getY()) {
            return from;
        }
        int[] step = RsModPathing.validated(grid.rsmodMap(free), from.getX(), from.getY(), dest[0], dest[1],
                size, RsModPathing.BLOCK_NPCS);
        if (step == null) {
            return from;
        }
        return new WorldArea(step[0], step[1], from.getWidth(), from.getHeight(), from.getPlane());
    }

    /**
     * Previous model: one step of the standard NPC travelling pattern toward {@code target}:
     * align the south-west corner (OSRS Wiki: monsters "attempt to align their
     * south-west corner with you"), diagonal first, then x, then y.
     *
     * Port of RuneLite's removed {@code WorldArea.calculateNextTravellingPoint}
     * (runelite commit 75ea607f23), with two fixes: the y-only step passes the
     * direction sign like every other branch instead of the raw delta, and the
     * scene-bounds check compares each axis with its own delta (the original
     * mixed dx and dy). Areas touching the scene edge are also treated as
     * unpredictable, since the collision check reads the tile beyond them.
     *
     * @return the next area, the same area when stuck, or null when the
     * outcome is unpredictable (overlapping the target, leaving the scene)
     */
    static WorldArea legacyNextStep(InfernoGrid grid, WorldArea from, WorldArea target, boolean stopAtMeleeDistance,
                              InfernoGrid.TileFilter free) {
        if (from.getPlane() != target.getPlane()) {
            return null;
        }
        if (from.intersectsWith(target)) {
            return stopAtMeleeDistance ? null : from;
        }
        int dx = target.getX() - from.getX();
        int dy = target.getY() - from.getY();
        int axisX = axisDistance(from.getX(), from.getWidth(), target.getX(), target.getWidth());
        int axisY = axisDistance(from.getY(), from.getHeight(), target.getY(), target.getHeight());
        if (stopAtMeleeDistance && axisX + axisY == 1) {
            return from;
        }
        int sx0 = from.getX() - grid.baseX;
        int sy0 = from.getY() - grid.baseY;
        if (sx0 < 1 || sy0 < 1
                || sx0 + from.getWidth() >= grid.sizeX - 1 || sy0 + from.getHeight() >= grid.sizeY - 1
                || sx0 + dx < 0 || sx0 + dx >= grid.sizeX
                || sy0 + dy < 0 || sy0 + dy >= grid.sizeY) {
            return null;
        }
        int sx = Integer.signum(dx);
        int sy = Integer.signum(dy);
        if (stopAtMeleeDistance && axisX == 1 && axisY == 1) {
            // Diagonal to the target: only an x-axis step is attempted.
            if (grid.canTravelInDirection(sx0, sy0, from.getWidth(), from.getHeight(), sx, 0, free)) {
                return shift(from, sx, 0);
            }
        } else {
            if (grid.canTravelInDirection(sx0, sy0, from.getWidth(), from.getHeight(), sx, sy, free)) {
                return shift(from, sx, sy);
            }
            if (dx != 0 && grid.canTravelInDirection(sx0, sy0, from.getWidth(), from.getHeight(), sx, 0, free)) {
                return shift(from, sx, 0);
            }
            // NPCs do not attempt a y-axis step when the target is within 1 tile.
            if (dy != 0 && Math.max(Math.abs(dx), Math.abs(dy)) > 1
                    && grid.canTravelInDirection(sx0, sy0, from.getWidth(), from.getHeight(), 0, sy, free)) {
                return shift(from, 0, sy);
            }
        }
        return from;
    }

    /** Gap between two 1-D intervals: 0 when overlapping, 1 when touching. */
    private static int axisDistance(int a, int aLen, int b, int bLen) {
        int aEnd = a + aLen - 1;
        int bEnd = b + bLen - 1;
        if (aEnd < b) {
            return b - aEnd;
        }
        if (bEnd < a) {
            return a - bEnd;
        }
        return 0;
    }

    private static WorldArea shift(WorldArea a, int dx, int dy) {
        return new WorldArea(a.getX() + dx, a.getY() + dy, a.getWidth(), a.getHeight(), a.getPlane());
    }

    /**
     * Threat bitmask for every standable tile within {@code radius} of
     * {@code center}. Tiles blocked by collision or by an NPC are left out.
     */
    public static Map<WorldPoint, Integer> threatMap(InfernoGrid grid, Collection<Monster> monsters, Set<WorldPoint> obstacles,
                                                     WorldPoint center, int radius, boolean predictMovement) {
        boolean[][] occupied = new boolean[grid.sizeX][grid.sizeY];
        for (WorldPoint o : obstacles) {
            int ox = grid.sceneX(o);
            int oy = grid.sceneY(o);
            if (grid.inScene(ox, oy)) {
                occupied[ox][oy] = true;
            }
        }
        Map<WorldPoint, Integer> map = new LinkedHashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                WorldPoint tile = center.dx(dx).dy(dy);
                if (obstacles.contains(tile) || !grid.isStandable(tile)) {
                    continue;
                }
                int mask = 0;
                for (Monster m : monsters) {
                    if (!m.type.isThreat()) {
                        continue;
                    }
                    boolean hits = predictMovement
                            ? canReachToAttack(grid, m.type, m.area, tile, occupied)
                            : canAttack(grid, m.type, m.area, tile);
                    if (hits) {
                        mask |= threatBit(m.type);
                    }
                }
                map.put(tile, mask);
            }
        }
        return map;
    }

    /** Monsters in kill order. Ties go to the closer one. */
    public static <T> List<T> killOrder(Collection<T> monsters, java.util.function.Function<T, InfernoNpcType> typeOf,
                                        java.util.function.ToIntFunction<T> distanceOf, boolean nibblersFirst) {
        List<T> order = new ArrayList<>(monsters);
        order.sort(Comparator
                .comparingInt((T m) -> effectiveKillPriority(typeOf.apply(m), nibblersFirst))
                .thenComparingInt(distanceOf));
        return Collections.unmodifiableList(order);
    }

    static int effectiveKillPriority(InfernoNpcType type, boolean nibblersFirst) {
        if (nibblersFirst && type == InfernoNpcType.NIBBLER) {
            return -1;
        }
        return type.getKillPriority();
    }

    /**
     * Tiles shielded from Zuk by the Ancestral Glyph: 5 wide by 3 deep
     * (OSRS Wiki: "a 5x3 section ... wider than the Glyph is visually").
     * Offsets from the glyph's south-west tile are taken from the xKylee
     * plugin: x - 1 .. x + 3, y - 4 .. y - 2.
     */
    public static List<WorldPoint> zukShieldCover(WorldPoint glyph) {
        List<WorldPoint> tiles = new ArrayList<>(15);
        for (int x = glyph.getX() - 1; x <= glyph.getX() + 3; x++) {
            for (int y = glyph.getY() - 4; y <= glyph.getY() - 2; y++) {
                tiles.add(new WorldPoint(x, y, glyph.getPlane()));
            }
        }
        return tiles;
    }
}
