package net.runelite.client.plugins.microbot.devin.inferno.bot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs {@link InfernoBrain} over a line protocol on stdin/stdout so an
 * external simulator can drive it without a game client.
 *
 * <pre>
 * in  RESET                                          start a new run
 *     TICK tick wave
 *     ME x y hp maxHp prayer maxPrayer ranged maxRanged weaponRange attackSpeed cooldown targetIndex prayers(csv|-)
 *     INV slot name doses
 *     PILLAR x y hp
 *     NPC index id x y size hpPercent animation
 *     PROJ spotanimId sourceIndex ticksToImpact
 *     END                                            -> brain replies
 *     STANDBY                                        -> MOVE x y END: where to wait for a wave
 *                                                       (uses the preceding PILLAR lines)
 * out PRAY name 0|1 | MOVE x y | ATTACK index | USE slot ... then END
 * </pre>
 */
public final class InfernoBrainServer {

    private InfernoBrainServer() {
    }

    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        InfernoBrain brain = new InfernoBrain();
        InfernoObservation o = new InfernoObservation();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(" ");
            switch (f[0]) {
                case "RESET":
                    brain.reset();
                    break;
                case "TICK":
                    o = new InfernoObservation();
                    o.tick = Integer.parseInt(f[1]);
                    o.wave = Integer.parseInt(f[2]);
                    break;
                case "ME":
                    o.x = i(f[1]);
                    o.y = i(f[2]);
                    o.hp = i(f[3]);
                    o.maxHp = i(f[4]);
                    o.prayer = i(f[5]);
                    o.maxPrayer = i(f[6]);
                    o.ranged = i(f[7]);
                    o.maxRanged = i(f[8]);
                    o.weaponRange = i(f[9]);
                    o.attackSpeed = i(f[10]);
                    o.attackCooldown = i(f[11]);
                    o.targetIndex = i(f[12]);
                    if (!"-".equals(f[13])) {
                        for (String p : f[13].split(",")) {
                            o.activePrayers.add(InfernoObservation.Prayer.valueOf(p));
                        }
                    }
                    break;
                case "INV":
                    o.inventory.add(new InfernoObservation.Item(i(f[1]), f[2], i(f[3])));
                    break;
                case "PILLAR":
                    o.pillars.add(new InfernoObservation.Pillar(i(f[1]), i(f[2]), i(f[3])));
                    break;
                case "NPC":
                    o.npcs.add(new InfernoObservation.Npc(i(f[1]), i(f[2]), i(f[3]), i(f[4]), i(f[5]), i(f[6]), i(f[7])));
                    break;
                case "PROJ":
                    o.projectiles.add(new InfernoObservation.Projectile(i(f[1]), i(f[2]), i(f[3])));
                    break;
                case "STANDBY": {
                    // Pillars from the preceding PILLAR lines; reply with the standby tile.
                    net.runelite.api.coords.WorldPoint t = InfernoBrain.standbyTile(o);
                    out.write("MOVE " + t.getX() + " " + t.getY() + "\nEND\n");
                    out.flush();
                    o = new InfernoObservation();
                    break;
                }
                case "END":
                    List<String> actions = brain.decide(o);
                    for (String a : actions) {
                        out.write(a);
                        out.write('\n');
                    }
                    out.write("END\n");
                    out.flush();
                    break;
                default:
                    throw new IllegalArgumentException("unknown line: " + line);
            }
        }
    }

    private static int i(String s) {
        return Integer.parseInt(s);
    }
}
