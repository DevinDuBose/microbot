package net.runelite.client.plugins.microbot.devin.inferno.bot;

import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.devin.inferno.InfernoGrid;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** {@link InfernoBrain#stepToward}: where the player stands after a tick of running. */
public class InfernoBrainPathTest {

    private static final int BASE = 3200;
    private static final int SOLID = CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL;

    private static int[][] open() {
        return new int[104][104];
    }

    private static WorldPoint p(int x, int y) {
        return new WorldPoint(BASE + x, BASE + y, 0);
    }

    private static InfernoGrid grid(int[][] flags) {
        return new InfernoGrid(BASE, BASE, 0, flags);
    }

    @Test
    public void straightAndDiagonalRunTwoTiles() {
        InfernoGrid g = grid(open());
        assertEquals(p(52, 50), InfernoBrain.stepToward(g, p(50, 50), p(55, 50), 2));
        assertEquals(p(48, 50), InfernoBrain.stepToward(g, p(50, 50), p(45, 50), 2));
        assertEquals(p(52, 52), InfernoBrain.stepToward(g, p(50, 50), p(55, 55), 2));
        assertEquals(p(50, 48), InfernoBrain.stepToward(g, p(50, 50), p(50, 40), 2));
    }

    @Test
    public void destinationWithinReachIsReturned() {
        InfernoGrid g = grid(open());
        assertEquals(p(51, 50), InfernoBrain.stepToward(g, p(50, 50), p(51, 50), 2));
        assertEquals(p(50, 50), InfernoBrain.stepToward(g, p(50, 50), p(50, 50), 2));
    }

    @Test
    public void walksAroundObstacle() {
        int[][] f = open();
        for (int y = 45; y <= 55; y++) {
            f[52][y] = SOLID;
        }
        InfernoGrid g = grid(f);
        WorldPoint at = InfernoBrain.stepToward(g, p(50, 50), p(54, 50), 2);
        assertTrue(g.isStandable(at));
        assertTrue(Math.abs(at.getX() - p(50, 50).getX()) <= 2 && Math.abs(at.getY() - p(50, 50).getY()) <= 2);
        assertTrue("must not pass through the wall: " + at, at.getX() < BASE + 52);
        // Shortest detour goes round an end of the wall, so we head towards one.
        assertTrue(at.getY() != BASE + 50);
    }

    @Test
    public void unreachableDestinationIsReturnedAsIs() {
        int[][] f = open();
        for (int x = 53; x <= 57; x++) {
            for (int y = 48; y <= 52; y++) {
                f[x][y] = SOLID;
            }
        }
        f[55][50] = 0;
        InfernoGrid g = grid(f);
        assertEquals(p(55, 50), InfernoBrain.stepToward(g, p(50, 50), p(55, 50), 2));
    }

    @Test
    public void pathTreeReachesWhatStepTowardReaches() {
        int[][] f = open();
        for (int y = 45; y <= 55; y++) {
            f[52][y] = SOLID;
        }
        for (int x = 53; x <= 57; x++) {
            for (int y = 60; y <= 64; y++) {
                f[x][y] = SOLID;
            }
        }
        f[55][62] = 0; // enclosed pocket
        InfernoGrid g = grid(f);
        int[][] tree = InfernoBrain.pathTree(g, p(50, 50));
        int pad = (tree.length - 1) / 2;
        assertTrue("open tile behind the wall is reachable", tree[pad + 4][pad] >= 0);
        assertEquals("enclosed pocket is unreachable", -1, tree[pad + 5][pad + 12]);
        assertEquals("solid tile is unreachable", -1, tree[pad + 2][pad]);
    }

    /** Wiki spot A: "the tile on the western side two north of" the north pillar. */
    @Test
    public void spotAIsWestColumnTwoNorthOfNorthPillar() {
        InfernoObservation o = new InfernoObservation();
        o.pillars.add(new InfernoObservation.Pillar(2257, 5349, 255));
        o.pillars.add(new InfernoObservation.Pillar(2274, 5351, 255));
        o.pillars.add(new InfernoObservation.Pillar(2267, 5335, 255));
        assertEquals(new WorldPoint(2274, 5355, 0), InfernoBrain.northPillarSpotA(o, InfernoBrain.buildGrid(o)));
        o.pillars.remove(1);
        // North pillar gone: the next northernmost (west pillar) is used.
        assertEquals(new WorldPoint(2257, 5353, 0), InfernoBrain.northPillarSpotA(o, InfernoBrain.buildGrid(o)));
    }
}
