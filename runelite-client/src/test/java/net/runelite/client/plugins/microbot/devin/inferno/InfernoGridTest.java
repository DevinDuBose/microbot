package net.runelite.client.plugins.microbot.devin.inferno;

import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Differential test: {@link InfernoGrid}'s ports must return exactly what
 * RuneLite's {@link WorldArea#hasLineOfSightTo} and
 * {@link WorldArea#canTravelInDirection} return, on random arenas that mix
 * solid objects, directional walls and directional line-of-sight blockers.
 */
public class InfernoGridTest {

    private static final int[] FLAG_POOL = {
            FakeScene.SOLID,
            CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL,
            CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST,
            CollisionDataFlag.BLOCK_LINE_OF_SIGHT_WEST,
            CollisionDataFlag.BLOCK_LINE_OF_SIGHT_NORTH,
            CollisionDataFlag.BLOCK_LINE_OF_SIGHT_SOUTH,
            CollisionDataFlag.BLOCK_MOVEMENT_OBJECT,
            CollisionDataFlag.BLOCK_MOVEMENT_FLOOR,
            CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION,
            CollisionDataFlag.BLOCK_MOVEMENT_NORTH,
            CollisionDataFlag.BLOCK_MOVEMENT_SOUTH,
            CollisionDataFlag.BLOCK_MOVEMENT_EAST,
            CollisionDataFlag.BLOCK_MOVEMENT_WEST,
            CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST,
            CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST,
            CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST,
            CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST,
    };

    private static FakeScene randomArena(Random rnd, double density) {
        FakeScene s = new FakeScene();
        for (int x = 0; x < FakeScene.SIZE; x++) {
            for (int y = 0; y < FakeScene.SIZE; y++) {
                if (rnd.nextDouble() < density) {
                    s.flags[x][y] |= FLAG_POOL[rnd.nextInt(FLAG_POOL.length)];
                    if (rnd.nextInt(4) == 0) {
                        s.flags[x][y] |= FLAG_POOL[rnd.nextInt(FLAG_POOL.length)];
                    }
                }
            }
        }
        return s;
    }

    private static int coord(Random rnd, int margin) {
        return margin + rnd.nextInt(FakeScene.SIZE - 2 * margin);
    }

    @Test
    public void lineOfSightMatchesRuneLite() {
        Random rnd = new Random(0x1F3A);
        int checked = 0;
        int visible = 0;
        for (int arena = 0; arena < 40; arena++) {
            FakeScene s = randomArena(rnd, 0.02 + rnd.nextDouble() * 0.25);
            InfernoGrid grid = s.grid();
            for (int i = 0; i < 500; i++) {
                int size = 1 + rnd.nextInt(5);
                WorldArea area = new WorldArea(FakeScene.p(coord(rnd, 8), coord(rnd, 8)), size, size);
                // Mostly nearby targets (Inferno distances), some far.
                WorldPoint target = rnd.nextInt(4) == 0
                        ? FakeScene.p(coord(rnd, 1), coord(rnd, 1))
                        : area.toWorldPoint().dx(rnd.nextInt(31) - 15).dy(rnd.nextInt(31) - 15);
                if (!grid.inScene(grid.sceneX(target), grid.sceneY(target))) {
                    continue;
                }
                boolean expected = new WorldArea(target, 1, 1).hasLineOfSightTo(s.wv, area);
                boolean actual = grid.hasLineOfSight(target, area);
                assertEquals("arena " + arena + " target " + target + " area " + area.toWorldPoint() + " size " + size,
                        expected, actual);
                checked++;
                if (expected) {
                    visible++;
                }
            }
        }
        // The sample must exercise both outcomes heavily.
        assertTrue("checked " + checked, checked > 15000);
        assertTrue("visible " + visible, visible > checked / 5 && visible < checked * 4 / 5);
    }

    @Test
    public void canTravelInDirectionMatchesRuneLite() {
        Random rnd = new Random(0x5EED);
        int checked = 0;
        int allowed = 0;
        for (int arena = 0; arena < 40; arena++) {
            FakeScene s = randomArena(rnd, 0.05 + rnd.nextDouble() * 0.3);
            InfernoGrid grid = s.grid();
            boolean[][] occupied = new boolean[FakeScene.SIZE][FakeScene.SIZE];
            for (int x = 0; x < FakeScene.SIZE; x++) {
                for (int y = 0; y < FakeScene.SIZE; y++) {
                    occupied[x][y] = rnd.nextInt(20) == 0;
                }
            }
            for (int i = 0; i < 500; i++) {
                int w = 1 + rnd.nextInt(5);
                int h = rnd.nextInt(3) == 0 ? 1 + rnd.nextInt(5) : w;
                int sx = coord(rnd, 8);
                int sy = coord(rnd, 8);
                int dx = rnd.nextInt(3) - 1;
                int dy = rnd.nextInt(3) - 1;
                WorldArea area = new WorldArea(FakeScene.p(sx, sy), w, h);
                boolean expected = area.canTravelInDirection(s.wv, dx, dy,
                        p -> !occupied[p.getX() - FakeScene.BASE_X][p.getY() - FakeScene.BASE_Y]);
                boolean actual = grid.canTravelInDirection(sx, sy, w, h, dx, dy, (x, y) -> !occupied[x][y]);
                assertEquals("arena " + arena + " area " + sx + "," + sy + " " + w + "x" + h + " dir " + dx + "," + dy,
                        expected, actual);
                checked++;
                if (expected) {
                    allowed++;
                }
            }
        }
        assertTrue("allowed " + allowed, allowed > checked / 5 && allowed < checked * 4 / 5);
    }
}
