package net.runelite.client.plugins.microbot.devin.inferno.bot;

import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.devin.inferno.InfernoGrid;
import net.runelite.client.plugins.microbot.devin.inferno.InfernoNpcType;
import net.runelite.client.plugins.microbot.devin.inferno.InfernoNpcType.Style;
import net.runelite.client.plugins.microbot.devin.inferno.InfernoThreatModel;
import net.runelite.client.plugins.microbot.devin.inferno.bot.InfernoObservation.Npc;
import net.runelite.client.plugins.microbot.devin.inferno.bot.InfernoObservation.Prayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decision core for an automated Inferno run. Pure: it sees an
 * {@link InfernoObservation} per tick and returns protocol action lines
 * ({@code PRAY}, {@code MOVE}, {@code ATTACK}, {@code USE}). The same class is
 * driven by the InfernoTrainer simulator (via {@link InfernoBrainServer}) and,
 * later, by the live client.
 *
 * Timing model: actions sent after observing tick n take effect at the start
 * of tick n + 1, and monsters attack during tick processing. So the prayer
 * chosen now must cover attacks made on tick n + 1.
 */
public final class InfernoBrain {

    /**
     * Strategy weights can be overridden with {@code -Dinferno.NAME=value} so the simulator
     * harness can tune them; the defaults are the tuned values.
     */
    static double tunable(String name, double def) {
        String v = System.getProperty("inferno." + name);
        return v == null ? def : Double.parseDouble(v);
    }

    static int tunableInt(String name, int def) {
        return (int) Math.round(tunable(name, def));
    }

    /** Walkable arena floor (world), verified from the game cache. */
    static final int ARENA_MIN_X = 2257;
    static final int ARENA_MAX_X = 2285;
    static final int ARENA_MIN_Y = 5329;
    static final int ARENA_MAX_Y = 5358;
    static final int GRID_BASE_X = 2240;
    static final int GRID_BASE_Y = 5312;
    static final int GRID_SIZE = 64;
    static final int PILLAR_SIZE = 3;

    /**
     * South-west tiles of the 9 monster spawn spots (world). From the InfernoTrainer simulator
     * and community tools (inferno-los); consistent with the cache-verified arena mapping.
     */
    static final int[][] SPAWN_SPOTS = {
        {2258, 5353}, {2279, 5353}, {2260, 5347}, {2280, 5346}, {2273, 5341},
        {2262, 5335}, {2280, 5333}, {2258, 5330}, {2272, 5330},
    };
    /** Size assumed for a spawn spot's monster when choosing the standby tile (largest: mager). */
    static final int SPAWN_SIZE = 4;

    static final int SEARCH_RADIUS = tunableInt("SEARCH_RADIUS", 7);
    /** A new tile must beat the current one by this much (HP per tick) to be worth moving. */
    static final double MOVE_HYSTERESIS = tunable("MOVE_HYSTERESIS", 0.5);
    /** Ticks to stay committed to a chosen tile (stops tile-to-tile oscillation). */
    static final int COMMIT_TICKS = tunableInt("COMMIT_TICKS", 4);
    /** During a commitment, another tile must beat ours by this much to switch. */
    static final double COMMIT_MARGIN = tunable("COMMIT_MARGIN", 2.0);
    /** Cost of each tile walked, in HP-per-tick units. */
    static final double MOVE_COST = tunable("MOVE_COST", 0.15);
    /** Value of being able to hit the kill target this tick (roughly our damage output). */
    static final double PROGRESS_VALUE = tunable("PROGRESS_VALUE", 3.0);
    /** Extra progress value per tick spent unable to attack anything (breaks stalemates). */
    static final double IDLE_PRESSURE = tunable("IDLE_PRESSURE", 0.25);
    /** While idle, cost per tile of distance to the kill target (pulls us toward it). */
    static final double APPROACH_COST = tunable("APPROACH_COST", 0.5);
    /**
     * Moving and attacking are exclusive (clicking a monster walks to it), so a tile we must
     * walk to only earns this share of the attack credit the current tile earns.
     */
    static final double MOVE_PROGRESS_DISCOUNT = tunable("MOVE_PROGRESS_DISCOUNT", 0.5);
    /** Extra cost per consecutive tick spent moving without attacking (stops endless kiting). */
    static final double KITE_PENALTY = tunable("KITE_PENALTY", 0.3);
    /**
     * Progress multiplier when the target is a nibbler eating a standing pillar: pillars are
     * the run's safespots, and losing them was the main cause of mid-run deaths in simulation.
     */
    static final double NIBBLER_FOCUS_WEIGHT = tunable("NIBBLER_FOCUS_WEIGHT", 2.0);
    /**
     * After this many ticks unable to attack anything, stop weighing threats and walk to the
     * nearest tile that can hit the target (stalemates behind pillars otherwise last forever).
     */
    static final int ENGAGE_AFTER_IDLE = tunableInt("ENGAGE_AFTER_IDLE", 12);
    /** 1 = flick protection only on predicted attack ticks; 0 = keep the soonest threat's prayer up. */
    static final int FLICK_PROTECTION = tunableInt("FLICK_PROTECTION", 1);
    /** 1 = walk toward nibblers eating a pillar even while other monsters are hittable. */
    static final int NIBBLER_APPROACH = tunableInt("NIBBLER_APPROACH", 1);
    /** 1 = also swap into Ahrim's robes to barrage (accuracy), at the cost of ranged defence. */
    static final int MAGE_ARMOUR_SWITCH = tunableInt("MAGE_ARMOUR_SWITCH", 0);
    /**
     * Cost of standing beside the meleer while anything else can also hit us. Its melee is
     * avoided entirely by one step, while praying against it leaves every other monster
     * unprotected (simulation: the top damage source in waves 21-40, blob hits landing while
     * Protect from Melee was up).
     */
    static final double MELEER_ADJACENT_COST = tunable("MELEER_ADJACENT_COST", 3.0);
    /**
     * 1 = while a blob can see us, alternate Protect from Magic / Missiles every tick (the
     * community "alternate flick"). The blob reads our prayer and fires 3 ticks later, an odd
     * gap, so it always fires the style we are then protecting, with no timing knowledge needed.
     * The phase is chosen so known mager/ranger attacks land on their own prayer; a melee hit
     * due next tick overrides the pattern. 2 = alternate only while no mager is a threat
     * (alternation cannot keep Magic up on a mager's shifting attack ticks).
     */
    static final int BLOB_ALTERNATE = tunableInt("BLOB_ALTERNATE", 0);
    /**
     * Ticks to hold a nibbler barrage while nibblers are still walking to their pillar: a
     * queued cast fires a tick later, so moving nibblers spread out of the 3x3; once they
     * stand on the pillar they stay clumped (0 = never wait).
     */
    static final int CLUMP_WAIT = tunableInt("CLUMP_WAIT", 8);
    /** 1 = when styles collide, protect against the style with the most max-hit damage landing. */
    static final int PRAY_BY_DAMAGE = tunableInt("PRAY_BY_DAMAGE", 0);
    private final Map<Prayer, Integer> damageByPrayer = new java.util.EnumMap<>(Prayer.class);
    /** Kill priority of the blob: after mager and ranger, before the meleer (-1 = the helper table, after the meleer). Sim, 12 runs of waves 1-66: 57.3 avg waves vs 53.1. */
    static final int BLOB_KILL_PRIORITY = tunableInt("BLOB_KILL_PRIORITY", 25);
    /** Kill priority override for the magic split, which resists barrage (-1 = table). */
    static final int MAGE_SPLIT_KILL_PRIORITY = tunableInt("MAGE_SPLIT_KILL_PRIORITY", -1);
    /** Run energy (0-10000) at which run is switched back on after it ran out. */
    static final int RUN_ON_ENERGY = tunableInt("RUN_ON_ENERGY", 500);
    /** Diagnostics: tick whose prayer decision is printed to stderr (-1 = none). */
    static final int DEBUG_TICK = tunableInt("DEBUG_TICK", -1);
    /** 1 = pray Melee against an adjacent blob (old behaviour); 0 = cover its scanned style. */
    static final int BLOB_ADJACENT_MELEE = tunableInt("BLOB_ADJACENT_MELEE", 0);
    /**
     * Tile protection is chosen for: 2 = current tile (monsters move and attack before we
     * move), 1 = where we will be after next tick's movement, 0 = the destination.
     */
    static final int PRAY_AT_NEXT_TILE = tunableInt("PRAY_AT_NEXT_TILE", 2);
    /**
     * 1 = a nibbler the crossbow cannot reach from here is worth a barrage even alone (it
     * otherwise eats a pillar while we fight elsewhere; measured: lone nibblers out of range
     * survived 60 ticks).
     */
    static final int BARRAGE_LONE_NIBBLER = tunableInt("BARRAGE_LONE_NIBBLER", 1);
    /** 1 = score tiles by a short look-ahead simulation of monster movement and attacks. */
    static final int ROLLOUT = tunableInt("ROLLOUT", 1);
    /** Look-ahead length in ticks. */
    static final int ROLLOUT_TICKS = tunableInt("ROLLOUT_TICKS", 6);
    /**
     * 0 (default) = protection prayers are checked when the monster launches its attack, which
     * is how the game works for Inferno monsters (OSRS Wiki: switching before the blob's
     * projectile is launched blocks it; the alternating 1-tick flick depends on it; Jad's check
     * happens when his projectile spawns after the wind-up). 1 = checked on impact, kept only
     * as a simulator robustness mode.
     */
    static final int IMPACT_PRAYER = tunableInt("IMPACT_PRAYER", 0);
    /** 1 = offensive prayer only on our attack ticks; 0 = keep it on. */
    static final int FLICK_OFFENSIVE = tunableInt("FLICK_OFFENSIVE", 1);
    /** Assumed share of a monster's max hit dealt per attack (hit chance x average roll). */
    static final double EXPECTED_HIT_FRACTION = tunable("EXPECTED_HIT_FRACTION", 0.3);
    static final int BLOB_SCAN_LEAD = 3;

    static final int EAT_BELOW_HP = tunableInt("EAT_BELOW_HP", 55);
    /** Below this, healing beats restoring stats even when a restore is due. */
    static final int CRITICAL_HP = tunableInt("CRITICAL_HP", 30);
    static final int RESTORE_BELOW_PRAYER = tunableInt("RESTORE_BELOW_PRAYER", 25);
    static final int BREW_SIPS_PER_RESTORE = tunableInt("BREW_SIPS_PER_RESTORE", 3);

    /** Per-NPC attack bookkeeping from observed animations and projectiles. */
    static final class Tracker {
        final int index;
        final InfernoNpcType type;
        int nextAttackTick = -1;
        Style lastStyle;
        /** Whether it could attack the player last tick (blob first-contact detection). */
        boolean couldAttackLastTick;
        /** Meleer underground: no threat until it resurfaces. */
        boolean burrowed;
        /** Last tick a blob could see/attack us: a read prayer is fired even after we hide. */
        int lastInViewTick = -100;
        /** Position last tick, and whether it changed since (nibbler clump detection). */
        int lastX = Integer.MIN_VALUE;
        int lastY;
        boolean moved = true;
        int firstSeenTick = -1;
        /** Consecutive ticks without moving (a nibbler standing still off a pillar is frozen). */
        int stillTicks;

        Tracker(int index, InfernoNpcType type) {
            this.index = index;
            this.type = type;
            this.lastStyle = type.getDefaultStyle();
        }
    }

    private final Map<Integer, Tracker> trackers = new HashMap<>();
    /** Protection prayer active during each tick (the blob reads it 3 ticks before attacking). */
    private final Map<Integer, Prayer> protectionByTick = new HashMap<>();
    private int brewSipsSinceRestore;
    private int lastUseTick = -10;
    private int idleTicks;
    /** Consecutive ticks we moved instead of attacking. */
    private int movingTicks;
    /** Tick a spell cast was issued, until it fires (-1 = none pending). */
    private int pendingCastTick = -1;
    private int lastCooldown;
    /** NPC indices able to attack the player this tick. */
    private final Set<Integer> attackingNow = new HashSet<>();
    private WorldPoint committedTile;
    private int committedUntil = -1;

    public void reset() {
        trackers.clear();
        protectionByTick.clear();
        brewSipsSinceRestore = 0;
        lastUseTick = -10;
        idleTicks = 0;
        movingTicks = 0;
        pendingCastTick = -1;
        lastCooldown = 0;
        committedTile = null;
        committedUntil = -1;
        lastDodgeTick = -100;
        dodgeStreak = 0;
        expectedKills.clear();
        pendingCastTarget = -1;
        safeTarget = null;
    }

    public List<String> decide(InfernoObservation o) {
        List<String> actions = new ArrayList<>();
        recordProtection(o);
        updateTrackers(o);
        currentWave = o.wave;
        if (DIAG_PRED == 1) {
            checkPredictions(o);
        }

        InfernoGrid grid = buildGrid(o);
        WorldPoint me = new WorldPoint(o.x, o.y, 0);
        idleTicks = anyHittable(grid, o, me) ? 0 : idleTicks + 1;
        detectBlobFirstContact(o, grid, me);
        attackingNow.clear();
        for (Npc n : o.npcs) {
            InfernoNpcType type = InfernoNpcType.forNpcId(n.id);
            if (type != null && type.isThreat() && InfernoThreatModel.canAttack(grid, type, area(n), me)) {
                attackingNow.add(n.index);
            }
        }

        // Run switches itself off at 0 energy; walking halves our speed for the rest of the
        // run (seen in simulation: a meleer chasing us for 30 ticks), so switch it back on.
        if (!o.running && o.runEnergy >= RUN_ON_ENERGY) {
            actions.add("RUN 1");
        }

        if (o.npcs.isEmpty()) {
            // Between waves: the wiki's spot A at the north pillar, else wait where the fewest
            // spawn spots can see us.
            WorldPoint standby = STANDBY_SPOT_A == 1 ? northPillarSpotA(o, grid) : null;
            if (standby == null) {
                standby = standbyTile(grid);
            }
            if (!standby.equals(me)) {
                actions.add("MOVE " + standby.getX() + " " + standby.getY());
            }
            useSupplies(o, actions);
            return actions;
        }

        // Positioning first: prayer and target are evaluated for where we will stand.
        WorldPoint stand = choosePosition(o, grid, me);
        if (DIAG_TRAP == 1) {
            diagnoseTiles(o, grid, me, stand);
        }
        if (DODGE == 1) {
            WorldPoint d = dodge(o, grid, occupied(grid, o), me, stand);
            if (d != null) {
                stand = d;
            }
        }
        if (!stand.equals(me)) {
            actions.add("MOVE " + stand.getX() + " " + stand.getY());
            movingTicks++;
        } else {
            movingTicks = 0;
        }

        // Prayer covers the tick ahead. NPCs take their turn before players each tick (OSRS
        // Wiki, Hit delay: client input, then NPCs, then players), so a monster attacking next
        // tick still sees us on our current tile.
        WorldPoint next = PRAY_AT_NEXT_TILE == 2 ? me : PRAY_AT_NEXT_TILE == 1 ? stepToward(grid, me, stand, 2) : stand;
        choosePrayers(o, grid, next, actions);
        useSupplies(o, actions);
        if (stand.equals(me)) {
            chooseTarget(o, grid, me, actions);
        }
        return actions;
    }

    /** Neighbour order of the OSRS player path search (west, east, south, north, then diagonals). */
    private static final int[][] PATH_DIRS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};

    /**
     * Tile reached after {@code steps} steps of the shortest path from {@code from} to
     * {@code dest} (breadth-first over the collision map; players walk through NPCs), or
     * {@code dest} itself when it is unreachable or closer than that.
     */
    static WorldPoint stepToward(InfernoGrid grid, WorldPoint from, WorldPoint dest, int steps) {
        if (from.equals(dest)) {
            return dest;
        }
        int ox = from.getX() - SEARCH_PAD;
        int oy = from.getY() - SEARCH_PAD;
        int n = SEARCH_PAD * 2 + 1;
        int[][] prev = new int[n][n];
        for (int[] row : prev) {
            java.util.Arrays.fill(row, -1);
        }
        int dx0 = dest.getX() - ox;
        int dy0 = dest.getY() - oy;
        if (dx0 < 0 || dy0 < 0 || dx0 >= n || dy0 >= n) {
            return dest;
        }
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        prev[SEARCH_PAD][SEARCH_PAD] = SEARCH_PAD * n + SEARCH_PAD;
        queue.add(new int[]{SEARCH_PAD, SEARCH_PAD});
        InfernoGrid.TileFilter any = (x, y) -> true;
        while (!queue.isEmpty() && prev[dx0][dy0] < 0) {
            int[] c = queue.poll();
            int sx = c[0] + ox - grid.baseX();
            int sy = c[1] + oy - grid.baseY();
            for (int[] d : PATH_DIRS) {
                int nx = c[0] + d[0];
                int ny = c[1] + d[1];
                if (nx < 0 || ny < 0 || nx >= n || ny >= n || prev[nx][ny] >= 0 || !grid.inScene(sx + d[0], sy + d[1])
                        || !grid.canTravelInDirection(sx, sy, 1, 1, d[0], d[1], any)) {
                    continue;
                }
                prev[nx][ny] = c[0] * n + c[1];
                queue.add(new int[]{nx, ny});
            }
        }
        if (prev[dx0][dy0] < 0) {
            return dest;
        }
        List<int[]> path = new ArrayList<>();
        int cx = dx0;
        int cy = dy0;
        while (cx != SEARCH_PAD || cy != SEARCH_PAD) {
            path.add(0, new int[]{cx, cy});
            int pv = prev[cx][cy];
            cx = pv / n;
            cy = pv % n;
        }
        int[] at = path.get(Math.min(steps, path.size()) - 1);
        return new WorldPoint(at[0] + ox, at[1] + oy, from.getPlane());
    }

    private static final int SEARCH_PAD = 40;

    // ------------------------------------------------------------------ tracking

    private void recordProtection(InfernoObservation o) {
        Prayer p = null;
        for (Prayer active : o.activePrayers) {
            if (active == Prayer.PROTECT_MAGIC || active == Prayer.PROTECT_MISSILES || active == Prayer.PROTECT_MELEE) {
                p = active;
            }
        }
        protectionByTick.put(o.tick, p);
        protectionByTick.remove(o.tick - 20);
    }

    private void updateTrackers(InfernoObservation o) {
        Set<Integer> seen = new HashSet<>();
        for (Npc n : o.npcs) {
            InfernoNpcType type = InfernoNpcType.forNpcId(n.id);
            if (type == null) {
                continue;
            }
            seen.add(n.index);
            Tracker t = trackers.computeIfAbsent(n.index, i -> new Tracker(i, type));
            if (t.firstSeenTick < 0) {
                t.firstSeenTick = o.tick;
            }
            t.moved = t.lastX != n.x || t.lastY != n.y;
            t.stillTicks = t.moved ? 0 : t.stillTicks + 1;
            t.lastX = n.x;
            t.lastY = n.y;
            if (n.animation >= 0) {
                Style style = type.getAttackAnimations().get(n.animation);
                if (style != null) {
                    t.lastStyle = style;
                    t.nextAttackTick = o.tick + attackSpeed(type, o.wave);
                } else if (type == InfernoNpcType.MAGER && n.animation == InfernoNpcType.MAGER_RESURRECT_ANIMATION) {
                    t.nextAttackTick = o.tick + InfernoNpcType.MAGER_RESURRECT_LOCKOUT;
                } else if (type == InfernoNpcType.MELEER && n.animation == InfernoNpcType.MELEER_DIG_ANIMATION) {
                    t.burrowed = true;
                } else if (type == InfernoNpcType.MELEER && n.animation == InfernoNpcType.MELEER_RESURFACE_ANIMATION) {
                    // OSRS Wiki: six ticks after resurfacing before it attacks.
                    t.burrowed = false;
                    t.nextAttackTick = o.tick + InfernoNpcType.MELEER_RESURFACE_DELAY;
                }
            }
        }
        for (InfernoObservation.Projectile p : o.projectiles) {
            Style style = InfernoNpcType.STYLE_PROJECTILES.get(p.spotanimId);
            Tracker t = trackers.get(p.sourceIndex);
            if (style != null && t != null) {
                t.lastStyle = style;
            }
        }
        trackers.keySet().retainAll(seen);
    }

    /**
     * A blob reads our prayer the moment it gains line of sight (no animation) and attacks 3
     * ticks later; afterwards its cycle is visible through attack animations. Without this the
     * first attack of every contact was unpredictable (the top damage source in simulation).
     * Same rule as the xKylee Inferno plugin.
     */
    private void detectBlobFirstContact(InfernoObservation o, InfernoGrid grid, WorldPoint me) {
        for (Npc n : o.npcs) {
            Tracker t = trackers.get(n.index);
            if (t == null || t.type != InfernoNpcType.BLOB) {
                continue;
            }
            boolean canAttack = InfernoThreatModel.canAttack(grid, t.type, area(n), me);
            boolean cycleRunning = t.nextAttackTick > o.tick;
            if (canAttack && !t.couldAttackLastTick && (!cycleRunning || BLOB_RESCAN_ON_REGAIN == 1)) {
                t.nextAttackTick = o.tick + BLOB_SCAN_LEAD;
            }
            t.couldAttackLastTick = canAttack;
        }
    }

    static int attackSpeed(InfernoNpcType type, int wave) {
        if (type == InfernoNpcType.JAD && wave == 68) {
            return InfernoNpcType.TRIPLE_JAD_ATTACK_SPEED;
        }
        return type.getAttackSpeed();
    }

    // ------------------------------------------------------------------ geometry

    static InfernoGrid buildGrid(InfernoObservation o) {
        int[][] flags = new int[GRID_SIZE][GRID_SIZE];
        for (int sx = 0; sx < GRID_SIZE; sx++) {
            for (int sy = 0; sy < GRID_SIZE; sy++) {
                int wx = GRID_BASE_X + sx;
                int wy = GRID_BASE_Y + sy;
                boolean floor = wx >= ARENA_MIN_X && wx <= ARENA_MAX_X && wy >= ARENA_MIN_Y && wy <= ARENA_MAX_Y;
                if (!floor) {
                    // Lava: blocks movement, not line of sight.
                    flags[sx][sy] = CollisionDataFlag.BLOCK_MOVEMENT_FLOOR;
                }
            }
        }
        for (InfernoObservation.Pillar p : o.pillars) {
            for (int dx = 0; dx < PILLAR_SIZE; dx++) {
                for (int dy = 0; dy < PILLAR_SIZE; dy++) {
                    int sx = p.x + dx - GRID_BASE_X;
                    int sy = p.y + dy - GRID_BASE_Y;
                    if (sx >= 0 && sy >= 0 && sx < GRID_SIZE && sy < GRID_SIZE) {
                        flags[sx][sy] |= CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL;
                    }
                }
            }
        }
        return new InfernoGrid(GRID_BASE_X, GRID_BASE_Y, 0, flags);
    }

    private static WorldArea area(Npc n) {
        return new WorldArea(n.x, n.y, n.size, n.size, 0);
    }

    static boolean[][] occupied(InfernoGrid grid, InfernoObservation o) {
        boolean[][] occ = new boolean[GRID_SIZE][GRID_SIZE];
        for (Npc n : o.npcs) {
            for (int dx = 0; dx < n.size; dx++) {
                for (int dy = 0; dy < n.size; dy++) {
                    int sx = n.x + dx - GRID_BASE_X;
                    int sy = n.y + dy - GRID_BASE_Y;
                    if (sx >= 0 && sy >= 0 && sx < GRID_SIZE && sy < GRID_SIZE) {
                        occ[sx][sy] = true;
                    }
                }
            }
        }
        return occ;
    }

    /**
     * Whether the player on {@code from} can attack the NPC without walking. Uses the strict
     * closest-tile line of sight (see {@link InfernoGrid#hasLineOfSightToClosestTile}); a
     * lenient check here made the player walk out of position whenever the game disagreed.
     */
    static boolean canHit(InfernoGrid grid, InfernoObservation o, WorldPoint from, Npc n) {
        WorldArea a = area(n);
        return a.distanceTo(from) <= maxRange(o) && grid.hasLineOfSightToClosestTile(from, a);
    }

    // ------------------------------------------------------------------ positioning

    private WorldPoint choosePosition(InfernoObservation o, InfernoGrid grid, WorldPoint me) {
        boolean[][] occ = occupied(grid, o);
        Npc focus = firstInKillOrder(o, null, null);
        if (idleTicks > ENGAGE_AFTER_IDLE) {
            WorldPoint engage = engageTile(o, grid, occ, me);
            if (engage != null) {
                committedTile = engage;
                committedUntil = o.tick + COMMIT_TICKS;
                return engage;
            }
        }
        if (NIBBLER_INTERCEPT == 1) {
            WorldPoint intercept = interceptTile(o, grid, occ, me);
            if (intercept != null) {
                committedTile = intercept;
                committedUntil = o.tick + 1;
                return intercept;
            }
        }
        double currentScore = tileScore(o, grid, occ, me, me, focus);
        WorldPoint best = me;
        double bestScore = currentScore;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                WorldPoint t = me.dx(dx).dy(dy);
                int sx = t.getX() - GRID_BASE_X;
                int sy = t.getY() - GRID_BASE_Y;
                if (!grid.isStandable(t) || occ[sx][sy]) {
                    continue;
                }
                double s = tileScore(o, grid, occ, me, t, focus);
                if (s < bestScore) {
                    bestScore = s;
                    best = t;
                }
            }
        }
        if (committedTile != null && o.tick <= committedUntil && grid.isStandable(committedTile)) {
            double committedScore = committedTile.equals(me) ? currentScore
                    : tileScore(o, grid, occ, me, committedTile, focus);
            if (bestScore + COMMIT_MARGIN >= committedScore) {
                return committedTile;
            }
        }
        WorldPoint chosen = bestScore + MOVE_HYSTERESIS < currentScore ? best : me;
        if (SAFESPOT == 1) {
            WorldPoint safe = safespot(o, grid, occ, me, chosen);
            if (safe != null) {
                chosen = safe;
            }
        }
        if (!chosen.equals(me)) {
            committedTile = chosen;
            committedUntil = o.tick + COMMIT_TICKS;
        }
        return chosen;
    }

    /**
     * 1 = while pillars stand, a nibbler that threatens one (walking to it or attacking it)
     * and that we cannot hit from here sends us to the nearest tile we can hit it from.
     * Pillar HP is a run-long resource: every tick a nibbler spends on a pillar is lost for
     * good (pillars are not restored between waves; OSRS Wiki).
     */
    static final int NIBBLER_INTERCEPT = tunableInt("NIBBLER_INTERCEPT", 1);
    /** Ticks of walking worth one point of expected damage taken when choosing that tile. */
    static final double INTERCEPT_DAMAGE_WEIGHT = tunable("INTERCEPT_DAMAGE_WEIGHT", 1.0);
    static final int INTERCEPT_RADIUS = 12;

    private WorldPoint interceptTile(InfernoObservation o, InfernoGrid grid, boolean[][] occ, WorldPoint me) {
        if (o.pillars.isEmpty()) {
            return null;
        }
        List<Npc> threats = new ArrayList<>();
        for (Npc n : o.npcs) {
            if (InfernoNpcType.forNpcId(n.id) != InfernoNpcType.NIBBLER || expectedDead(n, o)) {
                continue;
            }
            Tracker t = trackers.get(n.index);
            boolean frozenAway = t != null && t.stillTicks >= 2 && !besidePillar(o, n);
            if (frozenAway) {
                continue;
            }
            if (canHit(grid, o, me, n) || canBarrage(grid, o, me, n)) {
                return null; // already in reach: targeting handles it
            }
            threats.add(n);
        }
        if (threats.isEmpty()) {
            return null;
        }
        WorldPoint best = null;
        double bestCost = Double.MAX_VALUE;
        for (int dx = -INTERCEPT_RADIUS; dx <= INTERCEPT_RADIUS; dx++) {
            for (int dy = -INTERCEPT_RADIUS; dy <= INTERCEPT_RADIUS; dy++) {
                WorldPoint t = me.dx(dx).dy(dy);
                if (!grid.isStandable(t)) {
                    continue;
                }
                int sx = t.getX() - GRID_BASE_X;
                int sy = t.getY() - GRID_BASE_Y;
                if (sx < 0 || sy < 0 || sx >= occ.length || sy >= occ[0].length || occ[sx][sy]) {
                    continue;
                }
                boolean reaches = false;
                for (Npc n : threats) {
                    if (canHit(grid, o, t, n)) {
                        reaches = true;
                        break;
                    }
                }
                if (!reaches) {
                    continue;
                }
                double ticks = Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)) / 2.0);
                double cost = ticks;
                if (ticks < bestCost) { // damage only matters among tiles that could still win
                    cost += INTERCEPT_DAMAGE_WEIGHT * rolloutDamagePerTick(o, grid, occ, t) * ROLLOUT_TICKS;
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    best = t;
                }
            }
        }
        return best;
    }

    private static boolean besidePillar(InfernoObservation o, Npc n) {
        for (InfernoObservation.Pillar p : o.pillars) {
            if (new WorldArea(p.x, p.y, PILLAR_SIZE, PILLAR_SIZE, 0).isInMeleeDistance(new WorldPoint(n.x, n.y, 0))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a ranger, mager or blob beside {@code t} can melee it. OSRS Wiki
     * (Inferno/Strategies): "magers can melee diagonally across"; rangers "sometimes use melee if
     * the player is standing next to them, even diagonally"; "Blobs can also attack with melee if
     * you stand too close, even diagonally". (Meleers "cannot attack diagonally": cardinal only,
     * see InfernoThreatModel.canAttack.)
     */
    static boolean rangedMeleeReach(WorldArea a, WorldPoint t) {
        return a.distanceTo(t) == 1;
    }

    /** One monster that can attack a candidate tile. */
    private static final class Threat {
        final Style style;
        final double dps;
        final int priority;
        final int speed;
        /** Predicted next attack tick, or -1 if unknown. */
        final int nextAttack;

        Threat(Style style, double dps, int priority, int speed, int nextAttack) {
            this.style = style;
            this.dps = dps;
            this.priority = priority;
            this.speed = speed;
            this.nextAttack = nextAttack;
        }
    }

    /**
     * Expected HP lost per tick on {@code t}, minus the value of attacking from it. Lower is
     * better. Prayer can be switched every tick, so two styles only cost damage when their
     * attacks land on the same tick: the lower-priority one then goes unprotected.
     */
    private double tileScore(InfernoObservation o, InfernoGrid grid, boolean[][] occ, WorldPoint me, WorldPoint t, Npc focus) {
        List<Threat> threats = new ArrayList<>();
        double unpredictable = 0;
        boolean meleerAdjacent = false;
        for (Npc n : o.npcs) {
            InfernoNpcType type = InfernoNpcType.forNpcId(n.id);
            if (type == null || !type.isThreat()) {
                continue;
            }
            WorldArea a = area(n);
            Tracker burrowCheck = trackers.get(n.index);
            if (burrowCheck != null && burrowCheck.burrowed) {
                continue; // underground: resurfaces next to us later, timed by its animation
            }
            if (!InfernoThreatModel.canReachToAttack(grid, type, a, t, occ)) {
                continue;
            }
            if (type == InfernoNpcType.MELEER && InfernoThreatModel.canAttack(grid, type, a, t)) {
                meleerAdjacent = true;
            }
            double dps = EXPECTED_HIT_FRACTION * MaxHits.of(type, false) / type.getAttackSpeed();
            if (!type.isMeleeOnly() && rangedMeleeReach(a, t)) {
                // Adjacent rangers, magers and blobs choose melee or their main style at random:
                // half of their damage cannot be prayed against.
                double meleeDps = EXPECTED_HIT_FRACTION * MaxHits.of(type, true) / type.getAttackSpeed();
                unpredictable += 0.5 * Math.min(dps, meleeDps);
                dps = 0.5 * Math.max(dps, meleeDps);
            }
            Tracker tr = trackers.get(n.index);
            // A monster whose attack is overdue (it could not see us) attacks as soon as it can:
            // on the next tick if it can reach this tile. Its old schedule no longer applies.
            int next;
            if (tr == null || tr.nextAttackTick < 0) {
                next = -1;
            } else {
                next = tr.nextAttackTick > o.tick ? tr.nextAttackTick : o.tick + 1;
            }
            if (IMPACT_PRAYER == 1 && next >= 0) {
                // Prayer is checked when the hit lands: collide on landing ticks, not attack ticks.
                next += travelTicks(type, type.getDefaultStyle(), a.distanceTo(t));
            }
            threats.add(new Threat(type.getDefaultStyle(), dps, type.getProtectPriority(),
                    attackSpeed(type, o.wave), next));
        }
        double unprotected = unpredictable;
        if (meleerAdjacent && threats.size() > 1) {
            unprotected += MELEER_ADJACENT_COST;
        }
        for (Threat a : threats) {
            double worst = 0;
            for (Threat b : threats) {
                if (b == a || b.style == a.style || b.priority < a.priority) {
                    continue;
                }
                worst = Math.max(worst, collisionFraction(a, b));
            }
            unprotected += a.dps * worst;
        }
        // Low HP makes every point of damage more costly.
        if (ROLLOUT == 1) {
            unprotected = rolloutDamagePerTick(o, grid, occ, t);
        }
        double hpFactor = o.maxHp > 0 && o.hp < o.maxHp / 2 ? 2.0 : 1.0;
        double score = unprotected * hpFactor;

        double progress = PROGRESS_VALUE + IDLE_PRESSURE * idleTicks;
        boolean defendingPillar = focus != null && !o.pillars.isEmpty()
                && InfernoNpcType.forNpcId(focus.id) == InfernoNpcType.NIBBLER;
        if (defendingPillar) {
            progress *= NIBBLER_FOCUS_WEIGHT;
        }
        if (!t.equals(me)) {
            progress *= MOVE_PROGRESS_DISCOUNT;
            score += KITE_PENALTY * movingTicks;
        }
        boolean focusHittable = focus != null && (canHit(grid, o, t, focus)
                || (defendingPillar && canBarrage(grid, o, t, focus)));
        if (focusHittable) {
            score -= progress;
        } else {
            if (anyHittable(grid, o, t)) {
                score -= progress / 2;
            }
            if (focus != null && (idleTicks > 0 || (defendingPillar && NIBBLER_APPROACH == 1))) {
                // Out of reach of the target: prefer tiles closer to it so we close in.
                score += APPROACH_COST * area(focus).distanceTo(t);
            }
        }
        score += MOVE_COST * Math.max(Math.abs(t.getX() - me.getX()), Math.abs(t.getY() - me.getY()));
        return score;
    }

    /** A monster during a look-ahead: where it stands and when it can next attack. */
    private static final class Sim {
        final int index;
        final InfernoNpcType type;
        WorldArea area;
        int next;
        final int[] own;

        Sim(int index, InfernoNpcType type, WorldArea area, int next) {
            this.index = index;
            this.type = type;
            this.area = area;
            this.next = next;
            this.own = new int[]{area.getX() - GRID_BASE_X, area.getY() - GRID_BASE_Y,
                    area.getX() - GRID_BASE_X + area.getWidth() - 1, area.getY() - GRID_BASE_Y + area.getHeight() - 1};
        }
    }

    /**
     * Expected unprotected damage per tick if we stand on {@code t} for the next
     * {@link #ROLLOUT_TICKS} ticks. Each tick every monster either attacks (it can see/reach us
     * and its timer is ready; then its timer restarts) or takes one standard NPC step toward
     * us. Prayer blocks the most damaging style landing on a tick (attacks resolve at launch);
     * the rest is taken. Adjacent rangers/magers/blobs may melee, half of which no prayer
     * covers. Unknown timers are assumed ready (cautious). Pillars and other NPCs block
     * movement exactly as in the threat model, so trapping monsters is valued automatically.
     */
    private double rolloutDamagePerTick(InfernoObservation o, InfernoGrid grid, boolean[][] occ, WorldPoint t) {
        WorldPoint[] plan = new WorldPoint[ROLLOUT_TICKS];
        java.util.Arrays.fill(plan, t);
        return rolloutDamage(o, grid, occ, t, plan, false) / ROLLOUT_TICKS;
    }

    /**
     * Expected unprotected damage over a movement plan: {@code plan[s - 1]} is our tile at the
     * end of tick {@code o.tick + s}, starting from {@code start}. Monsters take their turn
     * before us each tick (OSRS Wiki, Hit delay), so on each tick they move toward, and attack,
     * the tile we ended the previous tick on.
     */
    private double rolloutDamage(InfernoObservation o, InfernoGrid grid, boolean[][] occ, WorldPoint start, WorldPoint[] plan,
            boolean knownTimersOnly) {
        List<Sim> sims = new ArrayList<>();
        for (Npc n : o.npcs) {
            InfernoNpcType type = InfernoNpcType.forNpcId(n.id);
            Tracker tr = trackers.get(n.index);
            if (type == null || !type.isThreat() || type.getProtectPriority() == 0 || (tr != null && tr.burrowed)) {
                continue;
            }
            boolean unknown = tr == null || tr.nextAttackTick < 0;
            if (unknown && knownTimersOnly) {
                continue;
            }
            int next = unknown ? o.tick + 1 : Math.max(tr.nextAttackTick, o.tick + 1);
            sims.add(new Sim(n.index, type, area(n), next));
        }
        if (sims.isEmpty()) {
            return 0;
        }
        double total = 0;
        WorldPoint t = start;
        for (int step = 1; step <= plan.length; step++) {
            if (step > 1) {
                t = plan[step - 2];
            }
            WorldArea target = new WorldArea(t, 1, 1);
            int tick = o.tick + step;
            double[] byStyle = new double[4];
            double unprotectable = 0;
            for (Sim m : sims) {
                if (InfernoThreatModel.canAttack(grid, m.type, m.area, t)) {
                    if (tick >= m.next) {
                        m.next = tick + attackSpeed(m.type, o.wave);
                        if (rolloutEvents != null) {
                            rolloutEvents.add(new int[]{m.index, tick});
                        }
                        double hit = EXPECTED_HIT_FRACTION * MaxHits.of(m.type, false);
                        if (!m.type.isMeleeOnly() && rangedMeleeReach(m.area, t)) {
                            double melee = EXPECTED_HIT_FRACTION * MaxHits.of(m.type, true);
                            unprotectable += 0.5 * Math.min(hit, melee);
                            hit = 0.5 * Math.max(hit, melee);
                        }
                        // The blob's style follows what we show it; count it as Ranged (steered).
                        Style st = m.type.getDefaultStyle() == Style.UNKNOWN ? Style.RANGE : m.type.getDefaultStyle();
                        byStyle[st.ordinal()] += hit;
                    }
                    continue;
                }
                final int[] own = m.own;
                InfernoGrid.TileFilter free = (x, y) -> (x >= own[0] && x <= own[2] && y >= own[1] && y <= own[3]) || !occ[x][y];
                WorldArea nextArea = InfernoThreatModel.nextStep(grid, m.area, target, true, free);
                if (nextArea != null) {
                    m.area = nextArea;
                }
            }
            double sum = 0;
            double max = 0;
            for (double v : byStyle) {
                sum += v;
                max = Math.max(max, v);
            }
            total += sum - max + unprotectable;
        }
        return total;
    }

    /**
     * 1 = prefer a safespot: a tile within {@link #SAFESPOT_RADIUS} from which we can attack
     * something while the look-ahead (monsters walking around pillars, attacking on their own
     * timers) expects at least {@link #SAFESPOT_MARGIN} less damage over
     * {@link #SAFESPOT_HORIZON} ticks than the chosen tile, counting the walk there. This is
     * the wiki's pillar safespotting ("trap monsters behind the pillars to minimise the
     * damage"), found from the live positions rather than a fixed tile list. Diagnostic run
     * (waves 41-50): such a tile existed on 17% of ticks. Off: 12 runs of waves 1-66 gave 46.2
     * avg waves vs 51.0 (bats and mager melee hit far more), so the look-ahead is not yet
     * reliable enough to pick tiles on its own.
     */
    static final int SAFESPOT = tunableInt("SAFESPOT", 0);
    static final int SAFESPOT_RADIUS = tunableInt("SAFESPOT_RADIUS", 10);
    static final int SAFESPOT_HORIZON = tunableInt("SAFESPOT_HORIZON", 12);
    static final double SAFESPOT_MARGIN = tunable("SAFESPOT_MARGIN", 5.0);

    private WorldPoint safespot(InfernoObservation o, InfernoGrid grid, boolean[][] occ, WorldPoint me, WorldPoint chosen) {
        int[][] tree = pathTree(grid, me);
        double chosenDmg = rolloutDamage(o, grid, occ, me, approachPlan(tree, me, chosen), false);
        // Keep heading for a safespot we already picked while it stays clearly better than the
        // default tile: re-picking every tick flip-flops between equally good tiles and we
        // never arrive (seen: 400 ticks without attacking while bats hit us).
        if (safeTarget != null && o.tick <= safeUntil && !me.equals(safeTarget) && grid.isStandable(safeTarget)) {
            WorldPoint[] plan = approachPlan(tree, me, safeTarget);
            if (plan != null && rolloutDamage(o, grid, occ, me, plan, false) <= chosenDmg - SAFESPOT_MARGIN / 2) {
                return safeTarget;
            }
        }
        safeTarget = null;
        if (chosenDmg < SAFESPOT_MARGIN) {
            return null;
        }
        WorldPoint best = null;
        double bestDmg = chosenDmg - SAFESPOT_MARGIN;
        for (int dx = -SAFESPOT_RADIUS; dx <= SAFESPOT_RADIUS; dx++) {
            for (int dy = -SAFESPOT_RADIUS; dy <= SAFESPOT_RADIUS; dy++) {
                WorldPoint t = me.dx(dx).dy(dy);
                int sx = t.getX() - GRID_BASE_X;
                int sy = t.getY() - GRID_BASE_Y;
                if (t.equals(chosen) || !grid.isStandable(t) || sx < 0 || sy < 0 || sx >= occ.length || sy >= occ[0].length
                        || occ[sx][sy] || !anyHittable(grid, o, t)) {
                    continue;
                }
                WorldPoint[] plan = approachPlan(tree, me, t);
                if (plan == null) {
                    continue;
                }
                double d = rolloutDamage(o, grid, occ, me, plan, false);
                if (d < bestDmg) {
                    bestDmg = d;
                    best = t;
                }
            }
        }
        if (best != null && !best.equals(me)) {
            safeTarget = best;
            safeUntil = o.tick + SAFESPOT_COMMIT;
        }
        return best;
    }

    /** Ticks we keep walking to a picked safespot before re-choosing. */
    static final int SAFESPOT_COMMIT = tunableInt("SAFESPOT_COMMIT", 8);
    private WorldPoint safeTarget;
    private int safeUntil;

    /**
     * Our tiles over {@link #SAFESPOT_HORIZON} ticks running (two steps a tick) along the
     * shortest path to {@code dest} and staying there; null if unreachable.
     */
    private static WorldPoint[] approachPlan(int[][] tree, WorldPoint me, WorldPoint dest) {
        List<int[]> path = pathFromTree(tree, dest.getX() - me.getX(), dest.getY() - me.getY());
        if (path == null) {
            return null;
        }
        WorldPoint[] plan = new WorldPoint[SAFESPOT_HORIZON];
        for (int s = 0; s < plan.length; s++) {
            int i = Math.min(2 * (s + 1), path.size()) - 1;
            plan[s] = i < 0 ? me : me.dx(path.get(i)[0]).dy(path.get(i)[1]);
        }
        return plan;
    }

    /**
     * Breadth-first search tree from {@code from} (offsets within {@link #SEARCH_PAD}):
     * tree[x][y] = encoded predecessor, -1 unreached. Same neighbour order and collision
     * rules as {@link #stepToward}.
     */
    static int[][] pathTree(InfernoGrid grid, WorldPoint from) {
        int n = SEARCH_PAD * 2 + 1;
        int[][] prev = new int[n][n];
        for (int[] row : prev) {
            java.util.Arrays.fill(row, -1);
        }
        int ox = from.getX() - SEARCH_PAD;
        int oy = from.getY() - SEARCH_PAD;
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        prev[SEARCH_PAD][SEARCH_PAD] = SEARCH_PAD * n + SEARCH_PAD;
        queue.add(new int[]{SEARCH_PAD, SEARCH_PAD});
        InfernoGrid.TileFilter any = (x, y) -> true;
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int sx = c[0] + ox - grid.baseX();
            int sy = c[1] + oy - grid.baseY();
            for (int[] d : PATH_DIRS) {
                int nx = c[0] + d[0];
                int ny = c[1] + d[1];
                if (nx < 0 || ny < 0 || nx >= n || ny >= n || prev[nx][ny] >= 0 || !grid.inScene(sx + d[0], sy + d[1])
                        || !grid.canTravelInDirection(sx, sy, 1, 1, d[0], d[1], any)) {
                    continue;
                }
                prev[nx][ny] = c[0] * n + c[1];
                queue.add(new int[]{nx, ny});
            }
        }
        return prev;
    }

    /** Path (offsets from the root, excluding it) to offset (dx, dy), or null if unreachable. */
    private static List<int[]> pathFromTree(int[][] prev, int dx, int dy) {
        int n = prev.length;
        int cx = dx + SEARCH_PAD;
        int cy = dy + SEARCH_PAD;
        if (cx < 0 || cy < 0 || cx >= n || cy >= n || prev[cx][cy] < 0) {
            return null;
        }
        List<int[]> path = new ArrayList<>();
        while (cx != SEARCH_PAD || cy != SEARCH_PAD) {
            path.add(0, new int[]{cx - SEARCH_PAD, cy - SEARCH_PAD});
            int pv = prev[cx][cy];
            cx = pv / n;
            cy = pv % n;
        }
        return path;
    }

    /**
     * Diagnostics: the look-ahead's predicted attacks (if we stand still) checked against the
     * attacks that then happen, on ticks we really did stand still. Printed to stderr.
     */
    static final int DIAG_PRED = tunableInt("DIAG_PRED", 0);
    private List<int[]> rolloutEvents;
    /** [madeTick, x, y] -> predicted (npc index, attack tick) pairs. */
    private final java.util.ArrayDeque<Object[]> predictions = new java.util.ArrayDeque<>();
    private final Map<String, int[]> predStats = new java.util.TreeMap<>();
    private int lastX = Integer.MIN_VALUE;
    private int lastY;
    private int stillSince;

    private void checkPredictions(InfernoObservation o) {
        if (o.x != lastX || o.y != lastY) {
            stillSince = o.tick;
            lastX = o.x;
            lastY = o.y;
        }
        Map<Integer, InfernoNpcType> alive = new HashMap<>();
        Set<Integer> attacked = new HashSet<>();
        for (Npc n : o.npcs) {
            InfernoNpcType type = InfernoNpcType.forNpcId(n.id);
            if (type == null) {
                continue;
            }
            alive.put(n.index, type);
            if (n.animation >= 0 && type.getAttackAnimations().containsKey(n.animation)) {
                attacked.add(n.index);
            }
        }
        for (Object[] rec : predictions) {
            int made = (int) rec[0];
            if (made < stillSince || made >= o.tick || o.tick > made + 6) {
                continue; // we moved since, or out of the window
            }
            @SuppressWarnings("unchecked")
            Set<Integer> predictedNow = new HashSet<>();
            @SuppressWarnings("unchecked")
            Set<Integer> known = (Set<Integer>) rec[2];
            for (int[] e : (List<int[]>) rec[1]) {
                if (e[1] == o.tick) {
                    predictedNow.add(e[0]);
                }
            }
            for (Integer idx : known) {
                InfernoNpcType type = alive.get(idx);
                if (type == null || !type.isThreat() || type.getProtectPriority() == 0) {
                    continue;
                }
                boolean p = predictedNow.contains(idx);
                boolean a = attacked.contains(idx);
                int[] st = predStats.computeIfAbsent(type.name(), k -> new int[3]);
                if (p && a) {
                    st[0]++;
                } else if (p) {
                    st[1]++;
                } else if (a) {
                    st[2]++;
                }
            }
        }
        while (!predictions.isEmpty() && (int) predictions.peekFirst()[0] < o.tick - 6) {
            predictions.pollFirst();
        }
        if (!o.npcs.isEmpty()) {
            boolean[][] occ = occupied(buildGrid(o), o);
            WorldPoint me = new WorldPoint(o.x, o.y, 0);
            WorldPoint[] plan = new WorldPoint[6];
            java.util.Arrays.fill(plan, me);
            rolloutEvents = new ArrayList<>();
            rolloutDamage(o, buildGrid(o), occ, me, plan, false);
            Set<Integer> known = new HashSet<>();
            for (Npc n : o.npcs) {
                known.add(n.index);
            }
            predictions.addLast(new Object[]{o.tick, rolloutEvents, known});
            rolloutEvents = null;
        }
        if (o.tick % 500 == 0) {
            StringBuilder sb = new StringBuilder("PREDSTATS t" + o.tick);
            for (Map.Entry<String, int[]> e : predStats.entrySet()) {
                int[] v = e.getValue();
                sb.append(" ").append(e.getKey()).append(" hit=").append(v[0]).append(" falseAlarm=").append(v[1])
                        .append(" missed=").append(v[2]);
            }
            System.err.println(sb);
        }
    }

    /** Diagnostics: print, each tick, expected damage at the chosen tile vs the best nearby tile. */
    static final int DIAG_TRAP = tunableInt("DIAG_TRAP", 0);

    private void diagnoseTiles(InfernoObservation o, InfernoGrid grid, WorldPoint me, WorldPoint chosen) {
        boolean[][] occ = occupied(grid, o);
        int horizon = 12;
        WorldPoint[] plan = new WorldPoint[horizon];
        java.util.Arrays.fill(plan, chosen);
        double chosenDmg = rolloutDamage(o, grid, occ, chosen, plan, false);
        double best = chosenDmg;
        WorldPoint bestTile = chosen;
        boolean bestHits = anyHittable(grid, o, chosen);
        for (int dx = -10; dx <= 10; dx++) {
            for (int dy = -10; dy <= 10; dy++) {
                WorldPoint t = me.dx(dx).dy(dy);
                int sx = t.getX() - GRID_BASE_X;
                int sy = t.getY() - GRID_BASE_Y;
                if (!grid.isStandable(t) || sx < 0 || sy < 0 || sx >= occ.length || sy >= occ[0].length || occ[sx][sy]) {
                    continue;
                }
                java.util.Arrays.fill(plan, t);
                double d = rolloutDamage(o, grid, occ, t, plan, false);
                if (d < best - 1e-9) {
                    best = d;
                    bestTile = t;
                    bestHits = anyHittable(grid, o, t);
                }
            }
        }
        System.err.println("TRAPDIAG t" + o.tick + " w" + o.wave + " chosen " + chosen.getX() + "," + chosen.getY()
                + " " + String.format("%.1f", chosenDmg) + " best " + bestTile.getX() + "," + bestTile.getY()
                + " " + String.format("%.1f", best) + " canAttackFromBest " + bestHits + " npcs " + o.npcs.size());
    }

    // ------------------------------------------------------------------ dodging

    /** 1 = step aside for a tick or two when that avoids attacks landing together. */
    static final int DODGE = tunableInt("DODGE", 0);
    /** Expected damage a dodge must save over just following the chosen path. */
    static final double DODGE_MARGIN = tunable("DODGE_MARGIN", 3.0);
    /** Look-ahead length for comparing dodges, in ticks. */
    static final int DODGE_HORIZON = tunableInt("DODGE_HORIZON", 8);
    /** Damage-equivalent cost of delaying our own attack by dodging. */
    static final double DODGE_ATTACK_COST = tunable("DODGE_ATTACK_COST", 4.0);

    /**
     * Tile to step to instead of heading for {@code chosen}, or null. A dodge is a tile we
     * reach this tick, held for one or two ticks, after which we walk on to {@code chosen}.
     * Stepping out of one monster's sight for a tick delays its attack, which pulls apart two
     * monsters that attack on the same tick (only one of which prayer can cover).
     */
    private WorldPoint dodge(InfernoObservation o, InfernoGrid grid, boolean[][] occ, WorldPoint me, WorldPoint chosen) {
        // Dodging costs attacks: only for an imminent collision, and never for long in a row.
        if (o.tick - lastDodgeTick == 1 ? dodgeStreak >= DODGE_MAX_STREAK : o.tick - lastDodgeTick <= DODGE_COOLDOWN) {
            return null;
        }
        // Only monsters whose attack timing we have seen: guessing collisions makes us dodge
        // for nothing, and every dodge interrupts our own attack.
        WorldPoint[] basePlan = walkPlan(grid, me, null, 0, chosen);
        if (rolloutDamage(o, grid, occ, me, java.util.Arrays.copyOf(basePlan, 3), true) <= DODGE_MARGIN) {
            return null;
        }
        double baseline = rolloutDamage(o, grid, occ, me, basePlan, true);
        WorldPoint best = null;
        double bestDamage = baseline - DODGE_MARGIN;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                WorldPoint h = me.dx(dx).dy(dy);
                if (h.equals(chosen) && !h.equals(me)) {
                    continue; // that is the baseline
                }
                int sx = h.getX() - GRID_BASE_X;
                int sy = h.getY() - GRID_BASE_Y;
                if (!grid.isStandable(h) || occ[sx][sy] || !stepToward(grid, me, h, 2).equals(h)) {
                    continue;
                }
                for (int hold = 1; hold <= 2; hold++) {
                    double d = rolloutDamage(o, grid, occ, me, walkPlan(grid, me, h, hold, chosen), true);
                    if (!h.equals(me) && o.attackCooldown <= hold + 1) {
                        d += DODGE_ATTACK_COST; // moving cancels the attack we were about to make
                    }
                    if (d < bestDamage) {
                        bestDamage = d;
                        best = h;
                    }
                }
            }
        }
        if (best != null) {
            dodgeStreak = o.tick - lastDodgeTick == 1 ? dodgeStreak + 1 : 1;
            lastDodgeTick = o.tick;
        }
        return best;
    }

    /** Most consecutive ticks spent dodging, then {@link #DODGE_COOLDOWN} ticks without. */
    static final int DODGE_MAX_STREAK = tunableInt("DODGE_MAX_STREAK", 2);
    static final int DODGE_COOLDOWN = tunableInt("DODGE_COOLDOWN", 4);
    private int lastDodgeTick = -100;
    private int dodgeStreak;

    /** Our tiles over {@link #DODGE_HORIZON} ticks: {@code hold} ticks on {@code via}, then on to {@code dest}. */
    private static WorldPoint[] walkPlan(InfernoGrid grid, WorldPoint me, WorldPoint via, int hold, WorldPoint dest) {
        WorldPoint[] plan = new WorldPoint[DODGE_HORIZON];
        WorldPoint cur = me;
        for (int s = 0; s < plan.length; s++) {
            cur = via != null && s < hold ? via : stepToward(grid, cur, dest, 2);
            plan[s] = cur;
        }
        return plan;
    }

    /**
     * Share of {@code a}'s attacks that land on the same tick as a higher-priority attack
     * of another style. Equal attack speeds in phase always collide and out of phase never
     * do; unknown timers are assumed to collide half the time.
     */
    static double collisionFraction(Threat a, Threat b) {
        if (a.nextAttack < 0 || b.nextAttack < 0) {
            return 0.5;
        }
        if (a.speed == b.speed) {
            return Math.floorMod(a.nextAttack - b.nextAttack, a.speed) == 0 ? 1.0 : 0.0;
        }
        // Different speeds line up once per least common multiple of the two cycles.
        return (double) a.speed / lcm(a.speed, b.speed);
    }

    private static int lcm(int x, int y) {
        int a = x;
        int b = y;
        while (b != 0) {
            int tmp = a % b;
            a = b;
            b = tmp;
        }
        return x / a * y;
    }

    /**
     * Nearest standable tile from which some monster can be attacked (crossbow, or barrage for
     * barrage fodder); threats only break ties between equally near tiles.
     */
    private WorldPoint engageTile(InfernoObservation o, InfernoGrid grid, boolean[][] occ, WorldPoint me) {
        WorldPoint best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = ARENA_MIN_X; x <= ARENA_MAX_X; x++) {
            for (int y = ARENA_MIN_Y; y <= ARENA_MAX_Y; y++) {
                WorldPoint t = new WorldPoint(x, y, 0);
                if (!grid.isStandable(t) || occ[x - GRID_BASE_X][y - GRID_BASE_Y]) {
                    continue;
                }
                boolean canAttackFrom = anyHittable(grid, o, t);
                if (!canAttackFrom) {
                    for (Npc n : o.npcs) {
                        if (isBarrageFodder(n) && canBarrage(grid, o, t, n)) {
                            canAttackFrom = true;
                            break;
                        }
                    }
                }
                if (!canAttackFrom) {
                    continue;
                }
                int dist = Math.max(Math.abs(x - me.getX()), Math.abs(y - me.getY()));
                double score = dist + 0.01 * tileScore(o, grid, occ, me, t, null);
                if (score < bestScore) {
                    bestScore = score;
                    best = t;
                }
            }
        }
        return best;
    }

    /** OSRS Wiki max hits (ranged/magic style, and melee when adjacent). */
    static final class MaxHits {
        static int of(InfernoNpcType type, boolean melee) {
            switch (type) {
                case BAT:
                    return 19;
                case BLOB:
                    return 29;
                case BLOB_SPLIT_MAGE:
                case BLOB_SPLIT_RANGE:
                case BLOB_SPLIT_MELEE:
                    return 18;
                case MELEER:
                    return 49;
                case RANGER:
                    return melee ? 19 : 46;
                case MAGER:
                    return melee ? 52 : 70;
                case JAD:
                    return 113;
                case JAD_HEALER:
                    return 18;
                default:
                    return 0;
            }
        }
    }

    private boolean anyHittable(InfernoGrid grid, InfernoObservation o, WorldPoint from) {
        for (Npc n : o.npcs) {
            InfernoNpcType type = InfernoNpcType.forNpcId(n.id);
            if (type != null && type != InfernoNpcType.ZUK && canHit(grid, o, from, n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The tile with line of sight to the fewest spawn spots (ties: nearest the arena centre,
     * so every spawn stays within reach). Recomputed from the pillars that still stand.
     */
    static WorldPoint standbyTile(InfernoGrid grid) {
        WorldPoint best = null;
        double bestScore = Double.MAX_VALUE;
        double cx = (ARENA_MIN_X + ARENA_MAX_X) / 2.0;
        double cy = (ARENA_MIN_Y + ARENA_MAX_Y) / 2.0;
        for (int x = ARENA_MIN_X; x <= ARENA_MAX_X; x++) {
            for (int y = ARENA_MIN_Y; y <= ARENA_MAX_Y; y++) {
                WorldPoint t = new WorldPoint(x, y, 0);
                if (!grid.isStandable(t)) {
                    continue;
                }
                int seen = 0;
                boolean adjacent = false;
                for (int[] spot : SPAWN_SPOTS) {
                    WorldArea a = new WorldArea(spot[0], spot[1], SPAWN_SIZE, SPAWN_SIZE, 0);
                    if (a.contains(t)) {
                        seen = 99;
                        break;
                    }
                    if (grid.hasLineOfSight(t, a)) {
                        seen++;
                    }
                    adjacent |= a.distanceTo(t) <= 1;
                }
                double score = seen * 10 + (adjacent ? 50 : 0) + 0.1 * Math.hypot(x - cx, y - cy);
                if (score < bestScore) {
                    bestScore = score;
                    best = t;
                }
            }
        }
        return best;
    }

    /** Standby tile for a set of pillars (used by the simulator to start a wave). */
    public static WorldPoint standbyTile(InfernoObservation o) {
        InfernoGrid grid = buildGrid(o);
        WorldPoint a = STANDBY_SPOT_A == 1 ? northPillarSpotA(o, grid) : null;
        return a != null ? a : standbyTile(grid);
    }

    /**
     * 1 = start each wave on spot A. OSRS Wiki (Inferno/Strategies): "The north pillar is a good
     * place to start each wave. Specifically, the tile on the western side two north of it ...
     * will allow you to freeze the nibblers, usually without being in sight of monsters spawning
     * south." Read as: the pillar's western column, two tiles north of its north edge.
     */
    static final int STANDBY_SPOT_A = tunableInt("STANDBY_SPOT_A", 1);

    /** Spot A for the northernmost standing pillar, or null if none / not standable. */
    static WorldPoint northPillarSpotA(InfernoObservation o, InfernoGrid grid) {
        InfernoObservation.Pillar north = null;
        for (InfernoObservation.Pillar p : o.pillars) {
            if (north == null || p.y > north.y) {
                north = p;
            }
        }
        if (north == null) {
            return null;
        }
        WorldPoint a = new WorldPoint(north.x, north.y + PILLAR_SIZE - 1 + 2, 0);
        return grid.isStandable(a) ? a : null;
    }

    // ------------------------------------------------------------------ prayer

    private void choosePrayers(InfernoObservation o, InfernoGrid grid, WorldPoint stand, List<String> actions) {
        int nextTick = o.tick + 1;
        Prayer wanted = null;
        int wantedPriority = -1;
        int soonest = Integer.MAX_VALUE;
        Prayer soonestPrayer = null;
        int soonestPriority = -1;

        boolean[][] occ = occupied(grid, o);
        if (IMPACT_PRAYER == 1) {
            // Ranged/magic: everything landing next tick is already in flight, so protect against
            // the highest-priority projectile due to land then.
            for (InfernoObservation.Projectile pr : o.projectiles) {
                Style style = InfernoNpcType.ATTACK_PROJECTILES.get(pr.spotanimId);
                Tracker src = trackers.get(pr.sourceIndex);
                if (style == null || pr.ticksToImpact > 1) {
                    continue;
                }
                int prio = src != null ? src.type.getProtectPriority() : 1;
                Prayer p = prayerFor(style);
                if (p != null && prio > wantedPriority) {
                    wanted = p;
                    wantedPriority = prio;
                }
            }
        }
        for (Npc n : o.npcs) {
            Tracker t = trackers.get(n.index);
            if (t == null || !t.type.isThreat() || t.type.getProtectPriority() == 0 || t.burrowed) {
                continue;
            }
            if (IMPACT_PRAYER == 1 && !hitsOnAttackTick(t, n, stand)) {
                // Its ranged/magic hit is protected on impact via the projectile above.
                continue;
            }
            boolean reach = canAttackNextTick(grid, t.type, area(n), stand, occ);
            if (DEBUG_TICK == o.tick) {
                System.err.println("PRAYDBG t" + o.tick + " npc " + n.index + " " + t.type + " at " + n.x + "," + n.y
                        + " stand " + stand + " reach " + reach + " next " + t.nextAttackTick);
            }
            if (!reach) {
                continue;
            }
            int attackTick = t.nextAttackTick < 0 ? nextTick : Math.max(t.nextAttackTick, nextTick);
            Prayer p = protectionFor(t, n, stand, attackTick);
            if (p == null) {
                continue;
            }
            int prio = t.type.getProtectPriority();
            if (attackTick == nextTick && prio > wantedPriority) {
                wanted = p;
                wantedPriority = prio;
            }
            if (attackTick == nextTick) {
                damageByPrayer.merge(p, MaxHits.of(t.type, p == Prayer.PROTECT_MELEE), Integer::sum);
            }
            if (attackTick < soonest || (attackTick == soonest && prio > soonestPriority)) {
                soonest = attackTick;
                soonestPrayer = p;
                soonestPriority = prio;
            }
        }
        if (PRAY_BY_DAMAGE == 1 && damageByPrayer.size() > 1) {
            // Several styles land together: block the one with the most damage at stake (two
            // rangers outweigh one mager); ties keep the higher-priority monster's prayer.
            int most = damageByPrayer.getOrDefault(wanted, 0);
            for (Map.Entry<Prayer, Integer> e : damageByPrayer.entrySet()) {
                if (e.getValue() > most) {
                    most = e.getValue();
                    wanted = e.getKey();
                }
            }
        }
        damageByPrayer.clear();
        if (wanted == null && FLICK_PROTECTION == 0) {
            wanted = soonestPrayer;
        }
        boolean alternate = BLOB_ALTERNATE == 1 || (BLOB_ALTERNATE == 2 && !magerThreat(o, grid, stand, occ));
        if (alternate && blobInView(o, grid, stand, occ) && wanted != Prayer.PROTECT_MELEE) {
            wanted = alternatingPrayer(o, grid, stand, occ, nextTick);
        }
        if (wanted == null && blobReadsNextTick(o, grid, stand, occ, nextTick)) {
            // A blob reads our protection on this tick and attacks with the other style 3 ticks
            // later (random if none is up). Steer it: if another monster will make us pray
            // Magic or Missiles on that tick, show the blob the opposite so it picks the style
            // we will already be protecting against. Otherwise show Magic (blob uses Ranged).
            Prayer planned = plannedProtectionAt(o, grid, stand, occ, blobLandingTick(o, stand, nextTick + BLOB_SCAN_LEAD));
            wanted = planned == Prayer.PROTECT_MAGIC ? Prayer.PROTECT_MISSILES : Prayer.PROTECT_MAGIC;
        }

        // Prayer drains for every tick it is active, so protection is only up on ticks an
        // attack can land (flicking) and the offensive prayer only when our own attack fires.
        Prayer current = protectionByTick.get(o.tick);
        if (wanted != null && wanted != current) {
            actions.add("PRAY " + wanted + " 1");
        } else if (wanted == null && current != null) {
            actions.add("PRAY " + current + " 0");
        }
        Prayer offensive = o.isPrayerActive(Prayer.RIGOUR) ? Prayer.RIGOUR : Prayer.EAGLE_EYE;
        WorldPoint me = new WorldPoint(o.x, o.y, 0);
        boolean attackingNextTick = FLICK_OFFENSIVE == 0 ? o.prayer > 0
                : o.attackCooldown <= 1 && o.prayer > 0 && stand.equals(me)
                && (anyHittable(grid, o, me) || barrageTarget(o, grid, me) != null);
        if (attackingNextTick && !o.isPrayerActive(offensive)) {
            actions.add("PRAY " + offensive + " 1");
        } else if (!attackingNextTick && o.isPrayerActive(offensive)) {
            actions.add("PRAY " + offensive + " 0");
        }
    }

    /**
     * Whether a blob can attack us next tick, or could within the last {@link #BLOB_SCAN_LEAD}
     * ticks: once it has read our prayer it fires even if we have since broken line of sight.
     */
    private boolean blobInView(InfernoObservation o, InfernoGrid grid, WorldPoint stand, boolean[][] occ) {
        boolean any = false;
        for (Npc n : o.npcs) {
            Tracker t = trackers.get(n.index);
            if (t == null || t.type != InfernoNpcType.BLOB) {
                continue;
            }
            if (canAttackNextTick(grid, InfernoNpcType.BLOB, area(n), stand, occ)) {
                t.lastInViewTick = o.tick;
            }
            any |= o.tick - t.lastInViewTick <= BLOB_SCAN_LEAD;
        }
        return any;
    }

    /**
     * Magic or Missiles for {@code tick} under the alternating pattern, with the phase that
     * best covers the known attack cycles of non-blob magic/ranged attackers over the next
     * 12 ticks (weighted by protection priority).
     */
    private Prayer alternatingPrayer(InfernoObservation o, InfernoGrid grid, WorldPoint stand, boolean[][] occ, int tick) {
        int[] phaseScore = new int[2];
        for (Npc n : o.npcs) {
            Tracker t = trackers.get(n.index);
            if (t == null || t.type == InfernoNpcType.BLOB || t.nextAttackTick < 0 || t.type.isMeleeOnly()) {
                continue;
            }
            Prayer need = prayerFor(t.type.getDefaultStyle());
            if (need != Prayer.PROTECT_MAGIC && need != Prayer.PROTECT_MISSILES) {
                continue;
            }
            if (!InfernoThreatModel.canReachToAttack(grid, t.type, area(n), stand, occ)) {
                continue;
            }
            int speed = attackSpeed(t.type, o.wave);
            for (int a = Math.max(t.nextAttackTick, o.tick + 1); a <= o.tick + 12; a += speed) {
                for (int phase = 0; phase < 2; phase++) {
                    if (alternate(a, phase) == need) {
                        phaseScore[phase] += t.type.getProtectPriority();
                    }
                }
            }
        }
        int phase = phaseScore[1] > phaseScore[0] ? 1 : 0;
        return alternate(tick, phase);
    }

    private static boolean magerThreat(InfernoObservation o, InfernoGrid grid, WorldPoint stand, boolean[][] occ) {
        for (Npc n : o.npcs) {
            if (InfernoNpcType.forNpcId(n.id) == InfernoNpcType.MAGER
                    && InfernoThreatModel.canReachToAttack(grid, InfernoNpcType.MAGER, area(n), stand, occ)) {
                return true;
            }
        }
        return false;
    }

    private static Prayer alternate(int tick, int phase) {
        return ((tick + phase) & 1) == 0 ? Prayer.PROTECT_MAGIC : Prayer.PROTECT_MISSILES;
    }

    /**
     * Protection the highest-priority non-blob attacker will need on {@code tick}, from observed
     * attack cycles (next attack plus whole attack periods), or null if none is predicted.
     */
    private Prayer plannedProtectionAt(InfernoObservation o, InfernoGrid grid, WorldPoint stand, boolean[][] occ, int tick) {
        Prayer best = null;
        int bestPriority = -1;
        for (Npc n : o.npcs) {
            Tracker t = trackers.get(n.index);
            if (t == null || t.type == InfernoNpcType.BLOB || t.nextAttackTick < 0 || t.type.getProtectPriority() == 0) {
                continue;
            }
            int speed = attackSpeed(t.type, o.wave);
            int next = Math.max(t.nextAttackTick, o.tick + 1);
            if (IMPACT_PRAYER == 1 && !hitsOnAttackTick(t, n, stand)) {
                next += travelTicks(t.type, t.type.getDefaultStyle(), area(n).distanceTo(stand));
            }
            if (tick < next || (tick - next) % speed != 0) {
                continue;
            }
            if (!InfernoThreatModel.canReachToAttack(grid, t.type, area(n), stand, occ)) {
                continue;
            }
            Prayer p = protectionFor(t, n, stand, tick);
            if (p != null && t.type.getProtectPriority() > bestPriority) {
                best = p;
                bestPriority = t.type.getProtectPriority();
            }
        }
        return best;
    }

    /**
     * Whether a blob that can attack {@code stand} reads our protection prayer on the next
     * tick: 3 ticks before its predicted attack, or at any time while its timing is unknown.
     */
    private boolean blobReadsNextTick(InfernoObservation o, InfernoGrid grid, WorldPoint stand, boolean[][] occ, int nextTick) {
        for (Npc n : o.npcs) {
            Tracker t = trackers.get(n.index);
            if (t == null || t.type != InfernoNpcType.BLOB) {
                continue;
            }
            if (!InfernoThreatModel.canReachToAttack(grid, t.type, area(n), stand, occ)) {
                continue;
            }
            if (t.nextAttackTick < 0 || t.nextAttackTick - BLOB_SCAN_LEAD <= nextTick) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ticks from attack to hit landing: 0 for melee; the OSRS projectile formulas used by the
     * InfernoTrainer engine otherwise (magic 1 + (1 + d) / 3, ranged 1 + (3 + d) / 6).
     * UNVERIFIED for these NPCs specifically; live projectiles report their exact remaining time.
     */
    static int travelTicks(InfernoNpcType type, Style style, int distance) {
        if (type.isMeleeOnly() || style == Style.MELEE) {
            return 0;
        }
        if (style == Style.RANGE) {
            return 1 + (3 + distance) / 6;
        }
        return 1 + (1 + distance) / 3;
    }

    /** Tick the hit of a blob attacking on {@code attackTick} would land (magic timing assumed). */
    private int blobLandingTick(InfernoObservation o, WorldPoint stand, int attackTick) {
        if (IMPACT_PRAYER == 0) {
            return attackTick;
        }
        int best = attackTick;
        for (Npc n : o.npcs) {
            Tracker t = trackers.get(n.index);
            if (t != null && t.type == InfernoNpcType.BLOB) {
                best = attackTick + travelTicks(t.type, Style.MAGIC, area(n).distanceTo(stand));
                break;
            }
        }
        return best;
    }

    static Prayer prayerFor(Style style) {
        switch (style) {
            case MAGIC:
                return Prayer.PROTECT_MAGIC;
            case RANGE:
                return Prayer.PROTECT_MISSILES;
            case MELEE:
                return Prayer.PROTECT_MELEE;
            default:
                return null;
        }
    }

    /**
     * Whether this monster's next hit lands on its attack tick (melee) rather than when a
     * projectile arrives. Adjacent rangers/magers/blobs may melee.
     */
    private static boolean hitsOnAttackTick(Tracker t, Npc n, WorldPoint stand) {
        if (t.type.isMeleeOnly() || t.type == InfernoNpcType.JAD) {
            // Jad's projectiles are not identified yet: keep timing him from his animation.
            return true;
        }
        return rangedMeleeReach(area(n), stand) && (t.type == InfernoNpcType.RANGER
                || t.type == InfernoNpcType.MAGER || t.type == InfernoNpcType.BLOB || t.type == InfernoNpcType.JAD);
    }

    /**
     * Whether a monster could attack {@code target} on the next tick: from where it stands, or
     * after its one movement step (monsters can step and attack in the same tick).
     */
    static boolean canAttackNextTick(InfernoGrid grid, InfernoNpcType type, WorldArea a, WorldPoint target, boolean[][] occ) {
        if (InfernoThreatModel.canAttack(grid, type, a, target)) {
            return true;
        }
        int ownX1 = a.getX() - GRID_BASE_X;
        int ownY1 = a.getY() - GRID_BASE_Y;
        int ownX2 = ownX1 + a.getWidth() - 1;
        int ownY2 = ownY1 + a.getHeight() - 1;
        InfernoGrid.TileFilter free = (x, y) -> (x >= ownX1 && x <= ownX2 && y >= ownY1 && y <= ownY2) || !occ[x][y];
        WorldArea next = InfernoThreatModel.nextStep(grid, a, new WorldArea(target, 1, 1), true, free);
        return next == null || (next != a && InfernoThreatModel.canAttack(grid, type, next, target));
    }

    private Prayer protectionFor(Tracker t, Npc n, WorldPoint stand, int attackTick) {
        Style style;
        if (t.type == InfernoNpcType.BLOB) {
            // The blob reads the protection prayer 3 ticks before attacking and uses the other style.
            Prayer read = protectionByTick.get(attackTick - BLOB_SCAN_LEAD);
            // Beside us it melees only by chance (OSRS Wiki: "a random chance ... instead of a
            // ranged/magic attack"), and only while we are not praying Melee; praying Melee
            // instead makes its scanned style a certain hit. So the scanned style is covered.
            if (BLOB_ADJACENT_MELEE == 1 && rangedMeleeReach(area(n), stand) && read != Prayer.PROTECT_MELEE) {
                style = Style.MELEE;
            } else if (read == Prayer.PROTECT_MAGIC) {
                style = Style.RANGE;
            } else if (read == Prayer.PROTECT_MISSILES) {
                style = Style.MAGIC;
            } else {
                style = t.lastStyle == Style.UNKNOWN ? Style.MAGIC : t.lastStyle;
            }
        } else if (t.type == InfernoNpcType.JAD) {
            style = t.lastStyle;
            if (style == Style.UNKNOWN) {
                return null;
            }
        } else {
            // Adjacent rangers and magers pick melee or their main style at random; the main
            // style hits harder (ranger 46 vs 19, mager 70 vs 52), so keep protecting it.
            style = t.type.getDefaultStyle();
        }
        switch (style) {
            case MAGIC:
                return Prayer.PROTECT_MAGIC;
            case RANGE:
                return Prayer.PROTECT_MISSILES;
            case MELEE:
                return Prayer.PROTECT_MELEE;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ attacking

    private Npc firstInKillOrder(InfernoObservation o, InfernoGrid grid, WorldPoint from) {
        boolean pillarsUp = !o.pillars.isEmpty();
        Npc best = null;
        int bestKey = Integer.MAX_VALUE;
        for (Npc n : o.npcs) {
            InfernoNpcType type = InfernoNpcType.forNpcId(n.id);
            if (type == null || type == InfernoNpcType.ZUK) {
                continue;
            }
            if (grid != null && (!canHit(grid, o, from, n) || expectedDead(n, o))) {
                continue;
            }
            int key = killPriority(n, type, pillarsUp) * 1000
                    + (from == null ? 0 : area(n).distanceTo(from));
            if (key < bestKey) {
                bestKey = key;
                best = n;
            }
        }
        return best;
    }

    /** Ranged weapons the brain knows: rapid-style range and attack speed (OSRS Wiki). */
    enum RangedWeapon {
        ARMADYL_CROSSBOW(8, 5),
        RUNE_CROSSBOW(7, 5),
        TOXIC_BLOWPIPE(5, 2);

        final int range;
        final int speed;

        RangedWeapon(int range, int speed) {
            this.range = range;
            this.speed = speed;
        }
    }

    /** Ranged weapons we wear or carry. Falls back to the observed weapon range if none is known. */
    private static List<RangedWeapon> rangedWeapons(InfernoObservation o) {
        List<RangedWeapon> out = new ArrayList<>();
        for (RangedWeapon w : RangedWeapon.values()) {
            if (o.worn.contains(w.name()) || hasItem(o, w.name())) {
                out.add(w);
            }
        }
        return out;
    }

    /** Longest reach among our ranged weapons. */
    static int maxRange(InfernoObservation o) {
        int r = 0;
        for (RangedWeapon w : rangedWeapons(o)) {
            r = Math.max(r, w.range);
        }
        return r > 0 ? r : o.weaponRange;
    }

    private static boolean hasItem(InfernoObservation o, String name) {
        for (InfernoObservation.Item i : o.inventory) {
            if (i.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static InfernoObservation.Item item(InfernoObservation o, String name) {
        for (InfernoObservation.Item i : o.inventory) {
            if (i.name.equals(name)) {
                return i;
            }
        }
        return null;
    }

    /** Equip {@code name} from the inventory if it is not already worn. */
    private static void wear(InfernoObservation o, String name, List<String> actions) {
        if (o.worn.contains(name)) {
            return;
        }
        InfernoObservation.Item i = item(o, name);
        if (i != null) {
            actions.add("USE " + i.slot);
        }
    }

    /**
     * Weapon for attacking {@code n} from {@code me}: the blowpipe (2-tick) for fragile targets
     * within its reach, otherwise the longest-reaching weapon that can hit it.
     */
    private static RangedWeapon weaponFor(InfernoObservation o, WorldPoint me, Npc n) {
        int dist = area(n).distanceTo(me);
        List<RangedWeapon> weapons = rangedWeapons(o);
        if (weapons.contains(RangedWeapon.TOXIC_BLOWPIPE) && dist <= RangedWeapon.TOXIC_BLOWPIPE.range && isFragile(n)) {
            return RangedWeapon.TOXIC_BLOWPIPE;
        }
        RangedWeapon best = null;
        for (RangedWeapon w : weapons) {
            if (w != RangedWeapon.TOXIC_BLOWPIPE && dist <= w.range && (best == null || w.range > best.range)) {
                best = w;
            }
        }
        if (best == null && weapons.contains(RangedWeapon.TOXIC_BLOWPIPE) && dist <= RangedWeapon.TOXIC_BLOWPIPE.range) {
            best = RangedWeapon.TOXIC_BLOWPIPE;
        }
        return best;
    }

    private static boolean isFragile(Npc n) {
        InfernoNpcType t = InfernoNpcType.forNpcId(n.id);
        return t == InfernoNpcType.NIBBLER || t == InfernoNpcType.BAT || t == InfernoNpcType.JAD_HEALER
                || t == InfernoNpcType.ZUK_HEALER || t == InfernoNpcType.BLOB_SPLIT_MAGE
                || t == InfernoNpcType.BLOB_SPLIT_RANGE || t == InfernoNpcType.BLOB_SPLIT_MELEE;
    }

    /** Ice Barrage: 10-tile range, 3x3 area around the target (OSRS Wiki). */
    static final int BARRAGE_RANGE = 10;

    /**
     * Barrage target that catches the most clustered nibblers or blob splits (at least two),
     * or null. Nibblers are worth barraging while pillars stand: crossbow kills one per attack,
     * which over a full run loses every pillar (measured in simulation).
     */
    private Npc barrageTarget(InfernoObservation o, InfernoGrid grid, WorldPoint me) {
        if (!o.spells.contains("ICE_BARRAGE")) {
            return null;
        }
        // Normally only clumps are worth a cast; a lone target too when the crossbow cannot
        // reach anything (position scoring counts barrage range for nibblers, so the two must
        // agree or we would wait in place forever).
        Npc best = null;
        int bestHits = anyHittable(grid, o, me) ? 1 : 0;
        for (Npc n : o.npcs) {
            if (!isBarrageFodder(n) || expectedDead(n, o)) {
                continue;
            }
            WorldArea a = area(n);
            if (a.distanceTo(me) > BARRAGE_RANGE || !grid.hasLineOfSightToClosestTile(me, a)) {
                continue;
            }
            int hits = 0;
            for (Npc m : o.npcs) {
                if (isBarrageFodder(m) && !expectedDead(m, o) && Math.abs(m.x - n.x) <= 1 && Math.abs(m.y - n.y) <= 1) {
                    hits++;
                }
            }
            if (BARRAGE_LONE_NIBBLER == 1 && !o.pillars.isEmpty()
                    && InfernoNpcType.forNpcId(n.id) == InfernoNpcType.NIBBLER && !canHit(grid, o, me, n)) {
                hits++;
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = n;
            }
        }
        if (best != null && CLUMP_WAIT > 0 && nibblersStillGathering(o, bestHits)) {
            return null;
        }
        return best;
    }

    /**
     * Whether nibblers are still walking in (some moved this tick, not all of them would be
     * caught, and the oldest arrived less than {@link #CLUMP_WAIT} ticks ago).
     */
    private boolean nibblersStillGathering(InfernoObservation o, int hits) {
        int nibblers = 0;
        boolean moving = false;
        int oldest = Integer.MAX_VALUE;
        for (Npc n : o.npcs) {
            if (InfernoNpcType.forNpcId(n.id) != InfernoNpcType.NIBBLER) {
                continue;
            }
            nibblers++;
            Tracker t = trackers.get(n.index);
            if (t != null) {
                moving |= t.moved;
                oldest = Math.min(oldest, t.firstSeenTick);
            }
        }
        return moving && hits < nibblers && o.tick - oldest < CLUMP_WAIT;
    }

    /**
     * NPC index -> last tick of a hit we expect to kill it. OSRS Wiki (Hit delay): the hit
     * lands some ticks after the attack (magic 1 + (1 + distance) / 3, crossbows
     * 1 + (3 + distance) / 6, blowpipe 1 + distance / 6; one more on NPCs, which act first), so
     * until then its health bar still looks full. Targeting it again meanwhile wastes an
     * attack (barrage: five ticks) on a monster that is about to die.
     */
    private final Map<Integer, Integer> expectedKills = new HashMap<>();

    /** Index of the barrage target while a cast is queued, else -1. */
    private int pendingCastTarget = -1;

    /**
     * Our attack timer just restarted, so an attack launched last tick. A barrage hits the
     * 3x3 around its target; a ranged hit on a fragile monster kills it. Counted only once
     * launched: an order still waiting on the timer can be overridden.
     */
    private void recordFiredAttack(InfernoObservation o, WorldPoint me) {
        int launched = o.tick - 1;
        if (pendingCastTarget >= 0) {
            Npc clump = npcByIndex(o, pendingCastTarget);
            pendingCastTarget = -1;
            if (clump != null) {
                for (Npc m : o.npcs) {
                    if (isBarrageFodder(m) && Math.abs(m.x - clump.x) <= 1 && Math.abs(m.y - clump.y) <= 1) {
                        expectKill(m, launched + magicHitDelay(me, m));
                    }
                }
            }
            return;
        }
        Npc target = npcByIndex(o, o.targetIndex);
        if (target != null && isFragile(target)) {
            int dist = area(target).distanceTo(me);
            boolean blowpipe = o.worn.contains(RangedWeapon.TOXIC_BLOWPIPE.name());
            int delay = blowpipe ? 1 + dist / 6 : 1 + (3 + dist) / 6;
            expectKill(target, launched + delay + 1);
        }
    }

    private static Npc npcByIndex(InfernoObservation o, int index) {
        for (Npc n : o.npcs) {
            if (n.index == index) {
                return n;
            }
        }
        return null;
    }

    private void expectKill(Npc n, int untilTick) {
        expectedKills.merge(n.index, untilTick, Math::max);
    }

    private boolean expectedDead(Npc n, InfernoObservation o) {
        Integer until = expectedKills.get(n.index);
        return EXPECT_KILLS == 1 && until != null && o.tick <= until;
    }

    /** Barrage hit delay, measured from the target's south-west tile, plus the NPC tick. */
    private static int magicHitDelay(WorldPoint me, Npc n) {
        int dist = Math.max(Math.abs(n.x - me.getX()), Math.abs(n.y - me.getY()));
        return 1 + (1 + dist) / 3 + 1;
    }

    /** 1 = skip targets an attack already in flight should kill. */
    static final int EXPECT_KILLS = tunableInt("EXPECT_KILLS", 1);

    static boolean canBarrage(InfernoGrid grid, InfernoObservation o, WorldPoint from, Npc n) {
        WorldArea a = area(n);
        return o.spells.contains("ICE_BARRAGE") && a.distanceTo(from) <= BARRAGE_RANGE
                && grid.hasLineOfSightToClosestTile(from, a);
    }

    private static boolean isBarrageFodder(Npc n) {
        InfernoNpcType t = InfernoNpcType.forNpcId(n.id);
        return t == InfernoNpcType.NIBBLER || t == InfernoNpcType.BLOB_SPLIT_MAGE
                || t == InfernoNpcType.BLOB_SPLIT_RANGE || t == InfernoNpcType.BLOB_SPLIT_MELEE;
    }

    /** Give up waiting for a cast that has not fired after this many ticks. */
    static final int CAST_TIMEOUT = 3;

    private void chooseTarget(InfernoObservation o, InfernoGrid grid, WorldPoint me, List<String> actions) {
        // A queued cast fires when our attack timer allows; any new attack order before then
        // cancels it. The cast has fired once the timer jumps back up.
        boolean castFired = o.attackCooldown > lastCooldown;
        lastCooldown = o.attackCooldown;
        if (castFired) {
            recordFiredAttack(o, me);
        }
        if (pendingCastTick >= 0 && !castFired && o.tick - pendingCastTick < CAST_TIMEOUT) {
            return;
        }
        pendingCastTick = -1;
        pendingCastTarget = -1; // fired (already recorded) or abandoned
        Npc clump = barrageTarget(o, grid, me);
        if (clump != null && o.attackCooldown <= 1) {
            wear(o, "ANCIENT_SCEPTRE", actions);
            if (MAGE_ARMOUR_SWITCH == 1) {
                wear(o, "AHRIMS_ROBETOP", actions);
                wear(o, "AHRIMS_ROBESKIRT", actions);
            }
            actions.add("CAST ICE_BARRAGE " + clump.index);
            pendingCastTarget = clump.index;
            pendingCastTick = o.tick;
            return;
        }
        // Back into ranged armour after any barrage.
        wear(o, "HUEYCOATL_HIDE_BODY", actions);
        wear(o, "HUEYCOATL_HIDE_CHAPS", actions);
        Npc target = firstInKillOrder(o, grid, me);
        if (target == null) {
            return;
        }
        RangedWeapon weapon = weaponFor(o, me, target);
        boolean switching = weapon != null && !o.worn.contains(weapon.name()) && hasItem(o, weapon.name());
        if (switching) {
            wear(o, weapon.name(), actions);
        }
        if (switching || target.index != o.targetIndex) {
            actions.add("ATTACK " + target.index);
        }
    }

    // ------------------------------------------------------------------ supplies

    private void useSupplies(InfernoObservation o, List<String> actions) {
        // OSRS Wiki (Potions): a potion delays the next potion by 3 ticks.
        if (o.tick - lastUseTick < 3) {
            return;
        }
        InfernoObservation.Item use = null;
        // Saradomin brews drain Ranged and Defence: after a few sips a restore comes first,
        // otherwise sustained healing makes us progressively weaker (observed in simulation).
        boolean restoreDue = brewSipsSinceRestore >= BREW_SIPS_PER_RESTORE || o.prayer < RESTORE_BELOW_PRAYER;
        if (restoreDue && o.hp >= CRITICAL_HP) {
            use = find(o, "SUPER_RESTORE");
            if (use != null) {
                brewSipsSinceRestore = 0;
            }
        }
        if (use == null && o.hp < EAT_BELOW_HP) {
            use = find(o, "SARADOMIN_BREW");
            if (use != null) {
                brewSipsSinceRestore++;
            }
        }
        if (use == null && restoreDue) {
            use = find(o, "SUPER_RESTORE");
            if (use != null) {
                brewSipsSinceRestore = 0;
            }
        }
        if (use == null && o.ranged <= o.maxRanged && o.npcs.size() > 0) {
            use = find(o, "BASTION_POTION");
        }
        if (use != null) {
            actions.add("USE " + use.slot);
            lastUseTick = o.tick;
        }
    }

    private static InfernoObservation.Item find(InfernoObservation o, String name) {
        InfernoObservation.Item best = null;
        for (InfernoObservation.Item i : o.inventory) {
            // Prefer partly used potions so whole ones stay stackable in the inventory.
            if (i.name.equals(name) && (best == null || i.doses < best.doses)) {
                best = i;
            }
        }
        return best;
    }

    /** Fragile monsters (two shots or fewer) that are hitting us right now jump the queue. */
    static final int FRAGILE_ATTACKER_PRIORITY = tunableInt("FRAGILE_ATTACKER_PRIORITY", 5);
    /**
     * Kill priority of a meleer that can hit us now (-1 = keep the normal order). While it is
     * beside us, Protect from Melee keeps taking the prayer from every other monster.
     */
    static final int ATTACKING_MELEER_PRIORITY = tunableInt("ATTACKING_MELEER_PRIORITY", -1);
    /**
     * 1 = a blob that regains line of sight reads our prayer again and restarts its 3-tick
     * countdown even mid-cycle (the simulator's rule, and the community "step out, it reads you"
     * advice); 0 = only on first contact.
     */
    static final int BLOB_RESCAN_ON_REGAIN = tunableInt("BLOB_RESCAN_ON_REGAIN", 1);

    private int killPriority(Npc n, InfernoNpcType type, boolean pillarsUp) {
        if (KILL_ORDER_WIKI == 1) {
            return wikiKillPriority(n, type, pillarsUp);
        }
        if (KILL_ORDER_WIKI == 2) {
            // Strict "mager first, always" (user's reading of the wiki): the helper table in
            // order mager > ranger > meleer > blob > bat regardless of who is attacking;
            // nibblers first while pillars stand. Table: mager 10, ranger 20, meleer 30, blob 40,
            // splits 45, Jad healer 60, bat 70.
            if (type == InfernoNpcType.NIBBLER) {
                return pillarsUp ? -1 : type.getKillPriority();
            }
            return type.getKillPriority();
        }
        boolean fragile = type == InfernoNpcType.BAT || type == InfernoNpcType.BLOB_SPLIT_MAGE
                || type == InfernoNpcType.BLOB_SPLIT_RANGE || type == InfernoNpcType.BLOB_SPLIT_MELEE;
        if (fragile && attackingNow.contains(n.index)) {
            return FRAGILE_ATTACKER_PRIORITY;
        }
        if (type == InfernoNpcType.MELEER && ATTACKING_MELEER_PRIORITY >= 0 && attackingNow.contains(n.index)) {
            return ATTACKING_MELEER_PRIORITY;
        }
        return InfernoThreatModelAccess.killPriority(type, pillarsUp);
    }

    /**
     * 1 = documented kill order. OSRS Wiki (Inferno/Strategies): "When possible, killing the
     * mager first is a good idea... if you are under attack by another monster, it may be better
     * to kill that one first." So monsters able to attack us now come first; within each group
     * the helper table (mager, ranger, meleer, blob, ..., bat), which follows the wiki's
     * "mager &gt; ranger &gt; meleer &gt; blob &gt; bat". Nibblers lead while pillars stand, except
     * from wave 50: "do not risk your life over it in the higher waves (50+)".
     */
    // 2 = strict mager first, always (chosen by the user as the reading of the wiki);
    // 1 = attackers first, then mager first (sim: 45.2 avg waves vs 50.6); 0 = previous
    // order (fragile attackers first, blob before meleer).
    static final int KILL_ORDER_WIKI = tunableInt("KILL_ORDER_WIKI", 2);

    private int wikiKillPriority(Npc n, InfernoNpcType type, boolean pillarsUp) {
        if (type == InfernoNpcType.NIBBLER) {
            return pillarsUp && currentWave < 50 ? -1 : 1000 + type.getKillPriority();
        }
        int base = type.getKillPriority();
        return attackingNow.contains(n.index) ? base : 500 + base;
    }

    private int currentWave;

    /** Small adapter so the brain can reuse the helper's kill priorities. */
    static final class InfernoThreatModelAccess {
        static int killPriority(InfernoNpcType type, boolean pillarsUp) {
            if (type == InfernoNpcType.NIBBLER) {
                return pillarsUp ? -1 : type.getKillPriority();
            }
            if (type == InfernoNpcType.BLOB && BLOB_KILL_PRIORITY >= 0) {
                return BLOB_KILL_PRIORITY;
            }
            if (type == InfernoNpcType.BLOB_SPLIT_MAGE && MAGE_SPLIT_KILL_PRIORITY >= 0) {
                return MAGE_SPLIT_KILL_PRIORITY;
            }
            return type.getKillPriority();
        }
    }
}
