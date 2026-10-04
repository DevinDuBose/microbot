package net.runelite.client.plugins.microbot.devin.inferno;

import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

import java.lang.reflect.Proxy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

/**
 * Minimal synthetic world for exercising RuneLite's real line-of-sight and
 * movement code: a 104x104 plane-0 scene with a settable collision map.
 * Coordinates passed to helpers are scene-relative; {@link #p} converts to
 * world points.
 */
final class FakeScene {

    static final int BASE_X = 3200;
    static final int BASE_Y = 3200;
    static final int SIZE = 104;

    /** A solid object that blocks movement and line of sight, like an Inferno pillar tile. */
    static final int SOLID = CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL;

    final int[][] flags = new int[SIZE][SIZE];
    final WorldView wv;

    FakeScene() {
        Tile[][][] tiles = new Tile[4][SIZE][SIZE];
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                tiles[0][x][y] = tile(x, y);
            }
        }
        Scene scene = mock(Scene.class, withSettings().stubOnly());
        when(scene.getTiles()).thenReturn(tiles);

        CollisionData collision = mock(CollisionData.class, withSettings().stubOnly());
        when(collision.getFlags()).thenReturn(flags);

        wv = mock(WorldView.class, withSettings().stubOnly());
        when(wv.getPlane()).thenReturn(0);
        when(wv.getBaseX()).thenReturn(BASE_X);
        when(wv.getBaseY()).thenReturn(BASE_Y);
        when(wv.getSizeX()).thenReturn(SIZE);
        when(wv.getSizeY()).thenReturn(SIZE);
        when(wv.getId()).thenReturn(-1);
        when(wv.getScene()).thenReturn(scene);
        when(wv.getCollisionMaps()).thenReturn(new CollisionData[]{collision, null, null, null});
    }

    /** Collision snapshot of the current flags, as the plugin would take it. */
    InfernoGrid grid() {
        return InfernoGrid.snapshot(wv);
    }

    static WorldPoint p(int x, int y) {
        return new WorldPoint(BASE_X + x, BASE_Y + y, 0);
    }

    /** Marks an inclusive scene-relative rectangle solid. */
    FakeScene solid(int x1, int y1, int x2, int y2) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                flags[x][y] |= SOLID;
            }
        }
        return this;
    }

    private static Tile tile(int x, int y) {
        Point loc = new Point(x, y);
        return (Tile) Proxy.newProxyInstance(Tile.class.getClassLoader(), new Class<?>[]{Tile.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getPlane":
                    return 0;
                case "getSceneLocation":
                    return loc;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "Tile(" + x + "," + y + ")";
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
    }
}
