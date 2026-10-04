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

    /**
     * STEPQ playerX playerY: for every NPC in the observation, the brain's prediction of its
     * next tile ("STEP index x y attacks"): it attacks instead of moving when it can already
     * attack the player's tile, otherwise it takes one standard NPC step (pillars and other
     * NPCs block). Compared against the simulator's actual movement.
     */
    static void step(InfernoObservation o, String[] f, BufferedWriter out) throws IOException {
        InfernoGrid grid = InfernoBrain.buildGrid(o);
        boolean[][] occ = InfernoBrain.occupied(grid, o);
        WorldPoint player = new WorldPoint(Integer.parseInt(f[1]), Integer.parseInt(f[2]), 0);
        for (InfernoObservation.Npc n : o.npcs) {
            net.runelite.client.plugins.microbot.devin.inferno.InfernoNpcType type =
                    net.runelite.client.plugins.microbot.devin.inferno.InfernoNpcType.forNpcId(n.id);
            WorldArea a = new WorldArea(n.x, n.y, n.size, n.size, 0);
            if (type == null) {
                continue;
            }
            if (net.runelite.client.plugins.microbot.devin.inferno.InfernoThreatModel.canAttack(grid, type, a, player)) {
                out.write("STEP " + n.index + " " + n.x + " " + n.y + " 1");
                out.newLine();
                continue;
            }
            int ox1 = n.x - grid.baseX(), oy1 = n.y - grid.baseY();
            int ox2 = ox1 + n.size - 1, oy2 = oy1 + n.size - 1;
            InfernoGrid.TileFilter free = (x, y) -> (x >= ox1 && x <= ox2 && y >= oy1 && y <= oy2) || !occ[x][y];
            WorldArea next = net.runelite.client.plugins.microbot.devin.inferno.InfernoThreatModel.nextStep(
                    grid, a, new WorldArea(player, 1, 1), true, free);
            WorldArea at = next == null ? a : next;
            out.write("STEP " + n.index + " " + at.getX() + " " + at.getY() + " 0");
            out.newLine();
        }
        out.write("END");
        out.newLine();
        out.flush();
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
