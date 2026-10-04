package net.runelite.client.plugins.microbot.devin.inferno.bot;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.devin.inferno.InfernoGrid;

import java.io.BufferedWriter;
import java.io.IOException;

/** Line-of-sight diagnostics for comparing the brain's model with a simulator. */
final class InfernoGridProbe {

    private InfernoGridProbe() {
    }

    static void answer(InfernoObservation o, String[] f, BufferedWriter out) throws IOException {
        InfernoGrid grid = InfernoBrain.buildGrid(o);
        int size = Integer.parseInt(f[3]);
        WorldArea npc = new WorldArea(Integer.parseInt(f[1]), Integer.parseInt(f[2]), size, size, 0);
        WorldPoint player = new WorldPoint(Integer.parseInt(f[4]), Integer.parseInt(f[5]), 0);
        boolean los = grid.hasLineOfSight(player, npc);
        boolean closest = grid.hasLineOfSightToClosestTile(player, npc);
        out.write("LOS " + (los ? 1 : 0) + " " + (closest ? 1 : 0));
        out.newLine();
        out.write("END");
        out.newLine();
        out.flush();
    }
}
