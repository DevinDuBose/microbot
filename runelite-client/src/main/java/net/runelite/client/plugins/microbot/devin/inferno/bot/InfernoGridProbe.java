package net.runelite.client.plugins.microbot.devin.inferno.bot;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.devin.inferno.InfernoGrid;
import net.runelite.client.plugins.microbot.devin.inferno.InfernoNpcType;
import net.runelite.client.plugins.microbot.devin.inferno.InfernoThreatModel;
import net.runelite.client.plugins.microbot.devin.inferno.RsModPathing;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Line-of-sight and movement diagnostics for comparing the brain's model with a simulator. */
final class InfernoGridProbe {

    private InfernoGridProbe() {
    }

    /**
     * STEPQ playerX playerY: for every NPC in the observation, the brain's prediction of its
     * next tile ("STEP index x y attacks"), processing NPCs one at a time in ascending index
     * order as RS Mod's NpcMainProcess does: an NPC that can attack the player's tile attacks
     * instead of moving (attacks = 1), otherwise it takes one RS Mod step (pillars block; other
     * NPCs block at their positions after the lower-index NPCs have moved) and attacks = 1 when
     * it can attack from the new tile (RS Mod's post-movement interaction check). An NPC
     * overlapping the player is reported where it stands with attacks = 2 (RS Mod: random
     * cardinal step, or none while the player is interacting).
     */
    static void step(InfernoObservation o, String[] f, BufferedWriter out) throws IOException {
        InfernoGrid grid = InfernoBrain.buildGrid(o);
        WorldPoint player = new WorldPoint(Integer.parseInt(f[1]), Integer.parseInt(f[2]), 0);
        WorldArea playerArea = new WorldArea(player, 1, 1);
        List<InfernoObservation.Npc> order = new ArrayList<>(o.npcs);
        order.sort(Comparator.comparingInt(n -> n.index));
        // Current positions (south-west tiles), updated as each NPC moves.
        int[][] pos = new int[order.size()][];
        for (int i = 0; i < order.size(); i++) {
            pos[i] = new int[]{order.get(i).x, order.get(i).y};
        }
        for (int i = 0; i < order.size(); i++) {
            InfernoObservation.Npc n = order.get(i);
            InfernoNpcType type = InfernoNpcType.forNpcId(n.id);
            if (type == null) {
                continue;
            }
            WorldArea a = new WorldArea(pos[i][0], pos[i][1], n.size, n.size, 0);
            if (InfernoThreatModel.canAttack(grid, type, a, player)) {
                write(out, n.index, a, 1);
                continue;
            }
            final int self = i;
            InfernoGrid.TileFilter free = (sx, sy) -> {
                int wx = sx + grid.baseX();
                int wy = sy + grid.baseY();
                for (int j = 0; j < order.size(); j++) {
                    if (j == self) {
                        continue;
                    }
                    int s = order.get(j).size;
                    if (wx >= pos[j][0] && wx < pos[j][0] + s && wy >= pos[j][1] && wy < pos[j][1] + s) {
                        return false;
                    }
                }
                return true;
            };
            WorldArea next = InfernoThreatModel.nextStep(grid, a, playerArea, true, free);
            if (next == null) {
                write(out, n.index, a, 2);
                continue;
            }
            pos[i][0] = next.getX();
            pos[i][1] = next.getY();
            write(out, n.index, next, InfernoThreatModel.canAttack(grid, type, next, player) ? 1 : 0);
        }
        out.write("END");
        out.newLine();
        out.flush();
    }

    private static void write(BufferedWriter out, int index, WorldArea a, int attacks) throws IOException {
        out.write("STEP " + index + " " + a.getX() + " " + a.getY() + " " + attacks);
        out.newLine();
    }

    /**
     * LOSQ npcX npcY size playerX playerY [range] -> "LOS area closest rsmod [ap op]": RuneLite
     * area line of sight, closest-tile line of sight, RS Mod NPC line of sight (reverse, no
     * range), and with a range RS Mod's ap-range and op-range checks.
     */
    static void answer(InfernoObservation o, String[] f, BufferedWriter out) throws IOException {
        InfernoGrid grid = InfernoBrain.buildGrid(o);
        int nx = Integer.parseInt(f[1]);
        int ny = Integer.parseInt(f[2]);
        int size = Integer.parseInt(f[3]);
        int px = Integer.parseInt(f[4]);
        int py = Integer.parseInt(f[5]);
        WorldArea npc = new WorldArea(nx, ny, size, size, 0);
        WorldPoint player = new WorldPoint(px, py, 0);
        boolean los = grid.hasLineOfSight(player, npc);
        boolean closest = grid.hasLineOfSightToClosestTile(player, npc);
        boolean rsmod = RsModPathing.hasLineOfSight(grid::rsmodFlag, px, py, nx, ny, 1, 1, size, size,
                RsModPathing.BLOCK_PLAYERS);
        StringBuilder sb = new StringBuilder("LOS " + (los ? 1 : 0) + " " + (closest ? 1 : 0) + " " + (rsmod ? 1 : 0));
        if (f.length > 6) {
            int range = Integer.parseInt(f[6]);
            boolean ap = RsModPathing.isWithinApRange(grid::rsmodFlag, nx, ny, size, px, py, 1, range);
            boolean op = RsModPathing.isWithinOpRange(grid::rsmodFlag, nx, ny, size, px, py, 1);
            sb.append(' ').append(ap ? 1 : 0).append(' ').append(op ? 1 : 0);
        }
        out.write(sb.toString());
        out.newLine();
        out.write("END");
        out.newLine();
        out.flush();
    }
}
