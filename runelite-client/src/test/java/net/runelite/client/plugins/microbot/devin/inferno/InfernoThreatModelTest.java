package net.runelite.client.plugins.microbot.devin.inferno;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.devin.inferno.FakeScene.p;
import static net.runelite.client.plugins.microbot.devin.inferno.InfernoThreatModel.THREAT_MAGIC;
import static net.runelite.client.plugins.microbot.devin.inferno.InfernoThreatModel.THREAT_RANGE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Positioning logic against synthetic arenas. Line of sight and collision
 * go through {@link InfernoGrid}, which InfernoGridTest proves identical to
 * RuneLite's own code. Expected values are derived by hand from the game
 * rules noted on each test.
 */
public class InfernoThreatModelTest {

    private static final Set<WorldPoint> NO_OBSTACLES = Collections.emptySet();

    private static WorldArea area(int x, int y, int size) {
        return new WorldArea(p(x, y), size, size);
    }

    private static InfernoThreatModel.Monster monster(InfernoNpcType type, int x, int y, int size) {
        return new InfernoThreatModel.Monster(type, area(x, y, size));
    }

    // ---- line of sight ----

    @Test
    public void openFieldRangerReachesEveryTile() {
        FakeScene s = new FakeScene();
        Map<WorldPoint, Integer> map = InfernoThreatModel.threatMap(s.grid(),
                List.of(monster(InfernoNpcType.RANGER, 60, 60, 3)), NO_OBSTACLES, p(40, 40), 3, false);
        assertEquals(49, map.size());
        map.forEach((tile, mask) -> assertEquals("tile " + tile, THREAT_RANGE, (int) mask));
    }

    @Test
    public void fullWallBlocksLineOfSightAndMovement() {
        // Wall across the whole scene at x = 50: no line of sight and no path.
        FakeScene s = new FakeScene().solid(50, 0, 50, FakeScene.SIZE - 1);
        WorldArea ranger = area(60, 40, 3);
        assertFalse(InfernoThreatModel.canAttack(s.grid(), InfernoNpcType.RANGER, ranger, p(40, 40)));
        assertFalse(InfernoThreatModel.canReachToAttack(s.grid(), InfernoNpcType.RANGER, ranger, p(40, 40), null));
    }

    @Test
    public void monsterStuckBehindPillarStaysHarmless() {
        // Pillar x 45..57, y 50..52. Player south of it at (51, 48); ranger north at SW (50, 60).
        // The ranger steps diagonally once to (51, 59), then straight south until its south
        // edge touches the pillar at y = 53, where every step is blocked: it is stuck with the
        // pillar between it and the player the whole way (the wiki's pillar safespot).
        FakeScene s = new FakeScene().solid(45, 50, 57, 52);
        WorldArea ranger = area(50, 60, 3);
        assertFalse(InfernoThreatModel.canAttack(s.grid(), InfernoNpcType.RANGER, ranger, p(51, 48)));
        assertFalse(InfernoThreatModel.canReachToAttack(s.grid(), InfernoNpcType.RANGER, ranger, p(51, 48), null));
    }

    @Test
    public void tileBesidePillarIsExposed() {
        // Same pillar; a player standing clear of it to the east is in plain view.
        FakeScene s = new FakeScene().solid(45, 50, 57, 52);
        assertTrue(InfernoThreatModel.canAttack(s.grid(), InfernoNpcType.RANGER, area(50, 60, 3), p(62, 48)));
    }

    // ---- attack range ----

    @Test
    public void batOutOfRangeNowButWalksIntoRange() {
        // Bat (2x2) 10 tiles away; range 4. Open field: it walks straight into range.
        FakeScene s = new FakeScene();
        WorldArea bat = area(40, 50, 2);
        assertFalse(InfernoThreatModel.canAttack(s.grid(), InfernoNpcType.BAT, bat, p(40, 40)));
        assertTrue(InfernoThreatModel.canReachToAttack(s.grid(), InfernoNpcType.BAT, bat, p(40, 40), null));

        Map<WorldPoint, Integer> now = InfernoThreatModel.threatMap(s.grid(),
                List.of(new InfernoThreatModel.Monster(InfernoNpcType.BAT, bat)), NO_OBSTACLES, p(40, 40), 0, false);
        Map<WorldPoint, Integer> predicted = InfernoThreatModel.threatMap(s.grid(),
                List.of(new InfernoThreatModel.Monster(InfernoNpcType.BAT, bat)), NO_OBSTACLES, p(40, 40), 0, true);
        assertEquals(0, (int) now.get(p(40, 40)));
        assertEquals(THREAT_RANGE, (int) predicted.get(p(40, 40)));
    }

    @Test
    public void batRangeBoundary() {
        // Bat occupies y 50..51; distance from (40, 46) is 4 (in range), from (40, 45) is 5.
        FakeScene s = new FakeScene();
        WorldArea bat = area(40, 50, 2);
        assertTrue(InfernoThreatModel.canAttack(s.grid(), InfernoNpcType.BAT, bat, p(40, 46)));
        assertFalse(InfernoThreatModel.canAttack(s.grid(), InfernoNpcType.BAT, bat, p(40, 45)));
    }

    @Test
    public void meleerNeedsCardinalAdjacency() {
        // Meleer 4x4 at 40..43. East-adjacent (44, 41) yes; diagonal corner (44, 44) no; 2 away no.
        FakeScene s = new FakeScene();
        WorldArea meleer = area(40, 40, 4);
        assertTrue(InfernoThreatModel.canAttack(s.grid(), InfernoNpcType.MELEER, meleer, p(44, 41)));
        assertFalse(InfernoThreatModel.canAttack(s.grid(), InfernoNpcType.MELEER, meleer, p(44, 44)));
        assertFalse(InfernoThreatModel.canAttack(s.grid(), InfernoNpcType.MELEER, meleer, p(45, 41)));
    }

    // ---- threat map ----

    @Test
    public void stylesCombineAndNonThreatsAreIgnored() {
        FakeScene s = new FakeScene();
        List<InfernoThreatModel.Monster> ms = List.of(
                monster(InfernoNpcType.MAGER, 60, 40, 4),
                monster(InfernoNpcType.RANGER, 40, 60, 3),
                monster(InfernoNpcType.NIBBLER, 41, 40, 1),
                monster(InfernoNpcType.ZUK, 20, 20, 7));
        Map<WorldPoint, Integer> map = InfernoThreatModel.threatMap(s.grid(), ms, NO_OBSTACLES, p(40, 40), 0, false);
        assertEquals(THREAT_RANGE | THREAT_MAGIC, (int) map.get(p(40, 40)));
    }

    @Test
    public void blockedAndOccupiedTilesAreSkipped() {
        FakeScene s = new FakeScene().solid(41, 40, 41, 40);
        Set<WorldPoint> obstacles = new HashSet<>(List.of(p(39, 40)));
        Map<WorldPoint, Integer> map = InfernoThreatModel.threatMap(s.grid(), List.of(), obstacles, p(40, 40), 1, false);
        assertFalse(map.containsKey(p(41, 40)));
        assertFalse(map.containsKey(p(39, 40)));
        assertEquals(7, map.size());
    }

    // ---- NPC stepping (port of RuneLite calculateNextTravellingPoint) ----

    @Test
    public void stepsDiagonallyWhenClear() {
        FakeScene s = new FakeScene();
        WorldArea next = InfernoThreatModel.nextStep(s.grid(), area(10, 10, 1), area(15, 15, 1), true, InfernoGrid.ALL_FREE);
        assertEquals(p(11, 11), next.toWorldPoint());
    }

    @Test
    public void fallsBackToXWhenDiagonalBlocked() {
        FakeScene s = new FakeScene().solid(11, 11, 11, 11);
        WorldArea next = InfernoThreatModel.nextStep(s.grid(), area(10, 10, 1), area(15, 15, 1), true, InfernoGrid.ALL_FREE);
        assertEquals(p(11, 10), next.toWorldPoint());
    }

    @Test
    public void fallsBackToYWhenDiagonalAndXBlocked() {
        FakeScene s = new FakeScene().solid(11, 10, 11, 11);
        WorldArea next = InfernoThreatModel.nextStep(s.grid(), area(10, 10, 1), area(15, 15, 1), true, InfernoGrid.ALL_FREE);
        assertEquals(p(10, 11), next.toWorldPoint());
    }

    @Test
    public void diagonalToTargetOnlyStepsAlongX() {
        FakeScene s = new FakeScene();
        WorldArea next = InfernoThreatModel.nextStep(s.grid(), area(10, 10, 1), area(11, 11, 1), true, InfernoGrid.ALL_FREE);
        assertEquals(p(11, 10), next.toWorldPoint());
    }

    @Test
    public void stopsWhenAlreadyInMeleeDistance() {
        FakeScene s = new FakeScene();
        WorldArea from = area(10, 10, 1);
        assertSame(from, InfernoThreatModel.nextStep(s.grid(), from, area(11, 10, 1), true, InfernoGrid.ALL_FREE));
    }

    @Test
    public void overlappingTargetIsUnpredictable() {
        FakeScene s = new FakeScene();
        assertNull(InfernoThreatModel.nextStep(s.grid(), area(10, 10, 3), area(11, 11, 1), true, InfernoGrid.ALL_FREE));
    }

    @Test
    public void otherNpcsBlockTheStep() {
        FakeScene s = new FakeScene();
        WorldArea from = area(10, 10, 1);
        // Scene tiles (11,11), (11,10) and (10,11) are held by other NPCs.
        InfernoGrid.TileFilter free = (x, y) -> !((x == 11 && y == 11) || (x == 11 && y == 10) || (x == 10 && y == 11));
        assertSame(from, InfernoThreatModel.nextStep(s.grid(), from, area(15, 15, 1), true, free));
    }

    // ---- kill order ----

    @Test
    public void killOrderFollowsPriorityThenDistance() {
        List<Object[]> ms = Arrays.asList(
                new Object[]{InfernoNpcType.BAT, 2},
                new Object[]{InfernoNpcType.NIBBLER, 1},
                new Object[]{InfernoNpcType.BLOB, 3},
                new Object[]{InfernoNpcType.MELEER, 9},
                new Object[]{InfernoNpcType.RANGER, 8},
                new Object[]{InfernoNpcType.MAGER, 20},
                new Object[]{InfernoNpcType.RANGER, 4});
        List<String> order = InfernoThreatModel.killOrder(ms, m -> (InfernoNpcType) m[0], m -> (Integer) m[1], false)
                .stream().map(m -> m[0] + "@" + m[1]).collect(Collectors.toList());
        assertEquals(List.of("MAGER@20", "RANGER@4", "RANGER@8", "MELEER@9", "BLOB@3", "BAT@2", "NIBBLER@1"), order);

        List<String> nibFirst = InfernoThreatModel.killOrder(ms, m -> (InfernoNpcType) m[0], m -> (Integer) m[1], true)
                .stream().map(m -> m[0] + "@" + m[1]).collect(Collectors.toList());
        assertEquals("NIBBLER@1", nibFirst.get(0));
        assertEquals("MAGER@20", nibFirst.get(1));
    }

    // ---- Zuk shield ----

    @Test
    public void zukShieldCoverIsFiveByThreeBelowGlyph() {
        List<WorldPoint> cover = InfernoThreatModel.zukShieldCover(new WorldPoint(100, 100, 0));
        assertEquals(15, cover.size());
        assertTrue(cover.contains(new WorldPoint(99, 96, 0)));
        assertTrue(cover.contains(new WorldPoint(103, 98, 0)));
        assertFalse(cover.contains(new WorldPoint(98, 97, 0)));
        assertFalse(cover.contains(new WorldPoint(104, 97, 0)));
        assertFalse(cover.contains(new WorldPoint(101, 99, 0)));
        assertFalse(cover.contains(new WorldPoint(101, 95, 0)));
    }
}
