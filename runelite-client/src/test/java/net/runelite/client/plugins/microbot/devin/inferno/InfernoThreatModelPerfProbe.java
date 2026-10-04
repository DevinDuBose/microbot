package net.runelite.client.plugins.microbot.devin.inferno;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static net.runelite.client.plugins.microbot.devin.inferno.FakeScene.p;

/**
 * Worst-case cost of one threat map (9 monsters, pillars, movement
 * prediction). Runs on a background thread in the plugin, but must still
 * finish well inside a 600 ms game tick.
 */
public class InfernoThreatModelPerfProbe {
    @Test
    public void worstCaseTiming() {
        FakeScene s = new FakeScene().solid(30, 50, 32, 52).solid(55, 45, 57, 47).solid(40, 30, 42, 32);
        List<InfernoThreatModel.Monster> ms = List.of(
                new InfernoThreatModel.Monster(InfernoNpcType.MAGER, new net.runelite.api.coords.WorldArea(p(60, 70), 4, 4)),
                new InfernoThreatModel.Monster(InfernoNpcType.RANGER, new net.runelite.api.coords.WorldArea(p(20, 70), 3, 3)),
                new InfernoThreatModel.Monster(InfernoNpcType.MELEER, new net.runelite.api.coords.WorldArea(p(70, 30), 4, 4)),
                new InfernoThreatModel.Monster(InfernoNpcType.BLOB, new net.runelite.api.coords.WorldArea(p(31, 60), 3, 3)),
                new InfernoThreatModel.Monster(InfernoNpcType.BLOB, new net.runelite.api.coords.WorldArea(p(56, 60), 3, 3)),
                new InfernoThreatModel.Monster(InfernoNpcType.BAT, new net.runelite.api.coords.WorldArea(p(70, 70), 2, 2)),
                new InfernoThreatModel.Monster(InfernoNpcType.BAT, new net.runelite.api.coords.WorldArea(p(15, 15), 2, 2)),
                new InfernoThreatModel.Monster(InfernoNpcType.MAGER, new net.runelite.api.coords.WorldArea(p(45, 75), 4, 4)),
                new InfernoThreatModel.Monster(InfernoNpcType.RANGER, new net.runelite.api.coords.WorldArea(p(75, 50), 3, 3)));
        InfernoGrid g = s.grid();
        for (int i = 0; i < 20; i++) {
            InfernoThreatModel.threatMap(g, ms, Collections.emptySet(), p(45, 48), 8, true);
        }
        {
            long t0 = System.nanoTime();
            int calls = 0;
            for (int i = 0; i < 5; i++) {
                for (int dx = -8; dx <= 8; dx++) for (int dy = -8; dy <= 8; dy++) for (InfernoThreatModel.Monster m : ms) {
                    InfernoThreatModel.canAttack(g, m.type, m.area, p(45 + dx, 48 + dy));
                    calls++;
                }
            }
            System.out.printf("PERF canAttack avg=%.1f us over %d calls%n", (System.nanoTime() - t0) / 1e3 / calls, calls);
        }
        for (int radius : new int[]{8, 15}) {
            long t0 = System.nanoTime();
            int n = 20;
            for (int i = 0; i < n; i++) {
                InfernoThreatModel.threatMap(g, ms, Collections.emptySet(), p(45, 48), radius, true);
            }
            double avgMs = (System.nanoTime() - t0) / 1e6 / n;
            System.out.printf("PERF radius=%d avg=%.2f ms%n", radius, avgMs);
            org.junit.Assert.assertTrue("radius " + radius + " took " + avgMs + " ms", avgMs < 150);
        }
    }
}
