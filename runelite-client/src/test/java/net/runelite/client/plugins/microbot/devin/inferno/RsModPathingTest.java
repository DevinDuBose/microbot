package net.runelite.client.plugins.microbot.devin.inferno;

import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link RsModPathing} against cases worked by hand from RS Mod's source (StepValidator,
 * StepFactory.validated, RouteFinding.naiveDestination, LineValidator.rayCast, Bounds,
 * ReachStrategy.reachExclusiveRectangle). Coordinates are absolute; the map is a plain
 * array where index = coordinate, so tiles outside it read -1 as in RS Mod.
 */
public class RsModPathingTest {

    private static final int N = 64;
    private static final int PILLAR = RsModPathing.LOC | RsModPathing.LOC_PROJ_BLOCKER;

    private final int[][] f = new int[N][N];

    private final RsModPathing.CollisionMap map = (x, z) -> x < 0 || z < 0 || x >= N || z >= N ? -1 : f[x][z];

    private void fill(int x1, int z1, int x2, int z2, int flag) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                f[x][z] |= flag;
            }
        }
    }

    private static int[] xz(int x, int z) {
        return new int[]{x, z};
    }

    @Test
    public void flagLayoutMatchesRuneLite() {
        assertEquals(CollisionDataFlag.BLOCK_MOVEMENT_OBJECT, RsModPathing.LOC);
        assertEquals(CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL, RsModPathing.LOC_PROJ_BLOCKER);
        assertEquals(CollisionDataFlag.BLOCK_MOVEMENT_FLOOR, RsModPathing.BLOCK_WALK);
        assertEquals(CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION, RsModPathing.GROUND_DECOR);
        assertEquals(CollisionDataFlag.BLOCK_MOVEMENT_NORTH, RsModPathing.WALL_NORTH);
        assertEquals(CollisionDataFlag.BLOCK_MOVEMENT_EAST, RsModPathing.WALL_EAST);
        assertEquals(CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST, RsModPathing.WALL_SOUTH_WEST);
        assertEquals(CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST, RsModPathing.WALL_EAST_PROJ_BLOCKER);
        assertEquals(CollisionDataFlag.BLOCK_LINE_OF_SIGHT_WEST, RsModPathing.WALL_WEST_PROJ_BLOCKER);
        assertEquals(CollisionDataFlag.BLOCK_LINE_OF_SIGHT_NORTH, RsModPathing.WALL_NORTH_PROJ_BLOCKER);
        assertEquals(CollisionDataFlag.BLOCK_LINE_OF_SIGHT_SOUTH, RsModPathing.WALL_SOUTH_PROJ_BLOCKER);
    }

    // ---------------------------------------------------------------- StepFactory.validated

    @Test
    public void openGroundStepsDiagonally() {
        assertArrayEquals(xz(11, 11), RsModPathing.validated(map, 10, 10, 15, 15, 1, RsModPathing.BLOCK_NPCS));
        assertArrayEquals(xz(9, 11), RsModPathing.validated(map, 10, 10, 5, 15, 1, RsModPathing.BLOCK_NPCS));
        // Straight line: the "diagonal" attempt is the straight step.
        assertArrayEquals(xz(10, 9), RsModPathing.validated(map, 10, 10, 10, 2, 3, RsModPathing.BLOCK_NPCS));
    }

    @Test
    public void blockedDiagonalFallsBackToXThenZThenNone() {
        // NE tile blocked: the 1x1 diagonal check reads (x+1, z+1) -> x step.
        f[11][11] = RsModPathing.LOC;
        assertArrayEquals(xz(11, 10), RsModPathing.validated(map, 10, 10, 15, 15, 1, 0));
        // East tile blocked too: the diagonal also needs (x+1, z) clear -> z step.
        f[11][10] = RsModPathing.LOC;
        assertArrayEquals(xz(10, 11), RsModPathing.validated(map, 10, 10, 15, 15, 1, 0));
        // North tile blocked: nothing.
        f[10][11] = RsModPathing.LOC;
        assertNull(RsModPathing.validated(map, 10, 10, 15, 15, 1, 0));
    }

    @Test
    public void npcFlagBlocksOnlyWithExtraFlag() {
        f[11][11] = RsModPathing.BLOCK_NPCS;
        f[11][10] = RsModPathing.BLOCK_NPCS;
        f[10][11] = RsModPathing.BLOCK_NPCS;
        assertNull(RsModPathing.validated(map, 10, 10, 15, 15, 1, RsModPathing.BLOCK_NPCS));
        // MoveRestrict.PassThru / CollisionStrategy.Blocked npcs pass extraFlag 0.
        assertArrayEquals(xz(11, 11), RsModPathing.validated(map, 10, 10, 15, 15, 1, 0));
    }

    @Test
    public void lavaFloorBlocksLikeALoc() {
        f[11][11] = RsModPathing.BLOCK_WALK;
        assertArrayEquals(xz(11, 10), RsModPathing.validated(map, 10, 10, 15, 15, 1, 0));
    }

    @Test
    public void size2NorthChecksOnlyTheLeadingEdge() {
        // 2x2 at (10,10) moving north reads (10,12) with BLOCK_NORTH_WEST and (11,12) with BLOCK_NORTH_EAST.
        assertTrue(RsModPathing.canTravel(map, 10, 10, 0, 1, 2, 0));
        // A wall on the east side of the north-west leading tile is between the two leading tiles: blocked.
        f[10][12] = RsModPathing.WALL_EAST;
        assertFalse(RsModPathing.canTravel(map, 10, 10, 0, 1, 2, 0));
        f[10][12] = 0;
        // A wall on the east side of the north-east leading tile is outside the NPC: not blocking.
        f[11][12] = RsModPathing.WALL_EAST;
        assertTrue(RsModPathing.canTravel(map, 10, 10, 0, 1, 2, 0));
        // Its south wall faces the NPC: blocked.
        f[11][12] = RsModPathing.WALL_SOUTH;
        assertFalse(RsModPathing.canTravel(map, 10, 10, 0, 1, 2, 0));
        // Tiles inside the NPC's own body are never read.
        f[11][12] = 0;
        f[11][11] = RsModPathing.LOC;
        assertTrue(RsModPathing.canTravel(map, 10, 10, 0, 1, 2, 0));
    }

    @Test
    public void size3DiagonalChecksTheWholeLeadingL() {
        // 3x3 at (10,10) moving NE reads (13,13) and, for mid 1..2, (10+mid, 13) and (13, 10+mid).
        assertTrue(RsModPathing.canTravel(map, 10, 10, 1, 1, 3, 0));
        f[13][11] = RsModPathing.LOC;
        assertFalse(RsModPathing.canTravel(map, 10, 10, 1, 1, 3, 0));
        // (13,10) is not on the leading L of a NE step (it is for an east step).
        f[13][11] = 0;
        f[13][10] = RsModPathing.LOC;
        assertTrue(RsModPathing.canTravel(map, 10, 10, 1, 1, 3, 0));
        assertFalse(RsModPathing.canTravel(map, 10, 10, 1, 0, 3, 0));
    }

    @Test
    public void unknownTilesBlock() {
        // RS Mod reads -1 for unallocated zones: every flag set.
        assertFalse(RsModPathing.canTravel(map, 0, 0, -1, 0, 1, 0));
    }

    // ---------------------------------------------------------------- naiveDestination

    @Test
    public void naiveDestinationAroundOneTileTarget() {
        // Hand-worked from RouteFinding.naiveDestination (target (15,15), size 1).
        assertArrayEquals(xz(15, 14), RsModPathing.naiveDestination(10, 10, 1, 1, 15, 15, 1, 1)); // SW -> south tile
        assertArrayEquals(xz(15, 14), RsModPathing.naiveDestination(20, 10, 1, 1, 15, 15, 1, 1)); // SE -> south tile
        assertArrayEquals(xz(15, 16), RsModPathing.naiveDestination(15, 20, 1, 1, 15, 15, 1, 1)); // N
        assertArrayEquals(xz(14, 15), RsModPathing.naiveDestination(10, 15, 1, 1, 15, 15, 1, 1)); // W
        assertArrayEquals(xz(16, 15), RsModPathing.naiveDestination(20, 15, 1, 1, 15, 15, 1, 1)); // E
        // Already cardinally adjacent: destination is where it stands.
        assertArrayEquals(xz(14, 15), RsModPathing.naiveDestination(14, 15, 1, 1, 15, 15, 1, 1));
        // Diagonal NE neighbour: north tile.
        assertArrayEquals(xz(15, 16), RsModPathing.naiveDestination(16, 16, 1, 1, 15, 15, 1, 1));
    }

    @Test
    public void naiveDestinationForLargeNpc() {
        // 3x3 south of the target, offset west by one: aligns its SW corner under the target.
        assertArrayEquals(xz(15, 12), RsModPathing.naiveDestination(14, 10, 3, 3, 15, 15, 1, 1));
        // 3x3 already touching the target's south edge.
        assertArrayEquals(xz(9, 7), RsModPathing.naiveDestination(9, 7, 3, 3, 10, 10, 1, 1));
        // 4x4 far west: west side, rows aligned with the target.
        assertArrayEquals(xz(22, 21), RsModPathing.naiveDestination(16, 19, 4, 4, 26, 21, 1, 1));
    }

    // ---------------------------------------------------------------- NPC beside a pillar

    @Test
    public void size3NpcBesidePillarSlidesAlongIt() {
        // Pillar 20..22 x 20..22. 3x3 at (17,20) touching its west face; target east at (26,21).
        fill(20, 20, 22, 22, PILLAR);
        int[] dest = RsModPathing.naiveDestination(17, 20, 3, 3, 26, 21, 1, 1);
        assertArrayEquals(xz(23, 21), dest);
        // NE blocked by the pillar at (20,21)/(20,22), east blocked at (20,20..22): north step.
        assertArrayEquals(xz(17, 21), RsModPathing.validated(map, 17, 20, dest[0], dest[1], 3, RsModPathing.BLOCK_NPCS));
        // From (17,23) (south row level with the pillar top) the SE diagonal still hits the
        // pillar corner (20,22) on the leading L; the east step is free.
        assertArrayEquals(xz(18, 23), RsModPathing.validated(map, 17, 23, 23, 21, 3, 0));
    }

    @Test
    public void size4NpcBesidePillar() {
        fill(20, 20, 22, 22, PILLAR);
        // 4x4 at (16,19) (16..19 x 19..22) west of the pillar, target (26,21): destination (22,21).
        assertArrayEquals(xz(16, 20), RsModPathing.validated(map, 16, 19, 22, 21, 4, 0));
        // 4x4 at (16,23) above the pillar's top row: the diagonal toward a south-east target is
        // blocked by the pillar's corner tile (20,22); x step east is free (row 23..26).
        assertArrayEquals(xz(17, 23), RsModPathing.validated(map, 16, 23, 30, 10, 4, 0));
    }

    // ---------------------------------------------------------------- line of sight

    @Test
    public void lineOfSightAlongPillarEdge() {
        fill(20, 20, 22, 22, PILLAR);
        // Row z = 23 runs just north of the pillar: visible. Row z = 22 runs through it: blocked.
        assertTrue(RsModPathing.hasLineOfSight(map, 17, 23, 25, 23, 1, 1, 1, 1, 0));
        assertFalse(RsModPathing.hasLineOfSight(map, 17, 22, 25, 22, 1, 1, 1, 1, 0));
        // (17,23) -> (25,21): the ray drops to z 22 at x 19..20 and hits (20,22).
        assertFalse(RsModPathing.hasLineOfSight(map, 17, 23, 25, 21, 1, 1, 1, 1, 0));
    }

    @Test
    public void npcLineOfSightTargetsTheClosestTile() {
        fill(20, 20, 22, 22, PILLAR);
        // 3x3 NPC at (24,21) spans 24..26 x 21..23; from (17,23) the clamped end is (24,23):
        // a straight row above the pillar, visible although the NPC's SW tile is behind it.
        assertTrue(RsModPathing.hasLineOfSight(map, 17, 23, 24, 21, 1, 1, 3, 3, RsModPathing.BLOCK_PLAYERS));
        // 1x1 NPC on that SW tile is hidden.
        assertFalse(RsModPathing.hasLineOfSight(map, 17, 23, 24, 21, 1, 1, 1, 1, RsModPathing.BLOCK_PLAYERS));
    }

    @Test
    public void closestTileBehindPillarIsHiddenEvenIfOtherTilesShow() {
        // InfernoGridTest's case: pillar 28..30 x 19..21, mager 4x4 at (24,16), player (32,19).
        // RuneLite's area LOS sees the mager's north-east tiles; RS Mod casts only to (27,19).
        fill(28, 19, 30, 21, PILLAR);
        assertFalse(RsModPathing.hasLineOfSight(map, 32, 19, 24, 16, 1, 1, 4, 4, RsModPathing.BLOCK_PLAYERS));
    }

    @Test
    public void endTileProjectileBlockerIsIgnoredStartLocIsNot() {
        // The projectile-blocker bit is cleared on the destination tile only.
        f[25][23] = RsModPathing.LOC_PROJ_BLOCKER;
        assertTrue(RsModPathing.hasLineOfSight(map, 17, 23, 25, 23, 1, 1, 1, 1, 0));
        f[24][23] = RsModPathing.LOC_PROJ_BLOCKER;
        assertFalse(RsModPathing.hasLineOfSight(map, 17, 23, 25, 23, 1, 1, 1, 1, 0));
        // A LOC on the start tile blocks (los = true).
        f[24][23] = 0;
        f[17][23] = RsModPathing.LOC;
        assertFalse(RsModPathing.hasLineOfSight(map, 17, 23, 25, 23, 1, 1, 1, 1, 0));
        // Lava (BLOCK_WALK) never blocks sight.
        f[17][23] = 0;
        fill(18, 23, 23, 23, RsModPathing.BLOCK_WALK);
        assertTrue(RsModPathing.hasLineOfSight(map, 17, 23, 25, 23, 1, 1, 1, 1, 0));
    }

    @Test
    public void directionalSightWalls() {
        // Travelling east checks the west-side projectile wall of each tile entered.
        f[20][23] = RsModPathing.WALL_WEST_PROJ_BLOCKER;
        assertFalse(RsModPathing.hasLineOfSight(map, 17, 23, 25, 23, 1, 1, 1, 1, 0));
        assertTrue(RsModPathing.hasLineOfSight(map, 25, 23, 17, 23, 1, 1, 1, 1, 0));
    }

    // ---------------------------------------------------------------- ranges

    @Test
    public void opRangeIsCardinalTouchOnly() {
        // 4x4 at (40,40).
        assertTrue(RsModPathing.isWithinOpRange(map, 40, 40, 4, 44, 41, 1));
        assertTrue(RsModPathing.isWithinOpRange(map, 40, 40, 4, 40, 39, 1));
        assertFalse(RsModPathing.isWithinOpRange(map, 40, 40, 4, 44, 44, 1)); // diagonal corner
        assertFalse(RsModPathing.isWithinOpRange(map, 40, 40, 4, 39, 39, 1)); // diagonal corner
        assertFalse(RsModPathing.isWithinOpRange(map, 40, 40, 4, 45, 41, 1)); // two away
        assertFalse(RsModPathing.isWithinOpRange(map, 40, 40, 4, 41, 41, 1)); // under
        // 1x1: a wall on the NPC's own tile facing the target blocks (reachRectangle1).
        assertTrue(RsModPathing.isWithinOpRange(map, 10, 10, 1, 11, 10, 1));
        assertFalse(RsModPathing.isWithinOpRange(map, 10, 10, 1, 11, 11, 1));
        f[10][10] = RsModPathing.WALL_EAST;
        assertFalse(RsModPathing.isWithinOpRange(map, 10, 10, 1, 11, 10, 1));
    }

    @Test
    public void apRangeIsPerAxisGapPlusReverseLineOfSight() {
        // 2x2 bat at (40,50), range 4: z gap from (40,46) is 4, from (40,45) is 5.
        assertTrue(RsModPathing.isWithinApRange(map, 40, 50, 2, 40, 46, 1, 4));
        assertFalse(RsModPathing.isWithinApRange(map, 40, 50, 2, 40, 45, 1, 4));
        // Chebyshev-like: x gap 3, z gap 4.
        assertTrue(RsModPathing.isWithinApRange(map, 40, 50, 2, 44, 46, 1, 4));
        // Diagonal neighbour is within range 1.
        assertTrue(RsModPathing.isWithinApRange(map, 40, 50, 2, 42, 52, 1, 1));
        // Under the NPC: never.
        assertFalse(RsModPathing.isWithinApRange(map, 40, 50, 2, 41, 51, 1, 4));
        // Blocked sight.
        fill(40, 48, 41, 48, PILLAR);
        assertFalse(RsModPathing.isWithinApRange(map, 40, 50, 2, 40, 46, 1, 4));
    }

    @Test
    public void boundsDistanceMatchesRsModOrdering() {
        assertTrue(RsModPathing.areWithinDistance(0, 0, 1, 1, 1, 1, 1, 1, 1));
        assertFalse(RsModPathing.areWithinDistance(0, 0, 1, 1, 2, 0, 1, 1, 1));
        // Wide a overlapping b on x.
        assertTrue(RsModPathing.areWithinDistance(0, 0, 5, 5, 2, 7, 1, 1, 3));
        assertFalse(RsModPathing.areWithinDistance(0, 0, 5, 5, 2, 7, 1, 1, 2));
        assertFalse(RsModPathing.areWithinDistance(0, 0, 1, 1, 0, 0, 1, 1, -1));
    }

    // ---------------------------------------------------------------- through InfernoThreatModel

    @Test
    public void threatModelUsesRsModRules() {
        FakeScene s = new FakeScene().solid(28, 19, 30, 21);
        InfernoGrid grid = s.grid();
        WorldArea mager = new WorldArea(FakeScene.p(24, 16), 4, 4);
        WorldPoint player = FakeScene.p(32, 19);
        assertTrue(grid.hasLineOfSight(player, mager)); // RuneLite area LOS
        assertFalse(InfernoThreatModel.npcLineOfSight(grid, mager, player));
        assertFalse(InfernoThreatModel.canAttack(grid, InfernoNpcType.MAGER, mager, player));
        InfernoThreatModel.RSMOD_RULES = false;
        try {
            assertTrue(InfernoThreatModel.canAttack(grid, InfernoNpcType.MAGER, mager, player));
        } finally {
            InfernoThreatModel.RSMOD_RULES = true;
        }
    }

    @Test
    public void nextStepReturnsSameInstanceOnDestination() {
        InfernoGrid grid = new FakeScene().grid();
        WorldArea from = new WorldArea(FakeScene.p(9, 7), 3, 3);
        assertSame(from, InfernoThreatModel.nextStep(grid, from, new WorldArea(FakeScene.p(10, 10), 1, 1), true,
                InfernoGrid.ALL_FREE));
    }
}
