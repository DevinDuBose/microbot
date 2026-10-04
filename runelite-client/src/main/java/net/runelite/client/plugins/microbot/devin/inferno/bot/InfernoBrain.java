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

    /** Walkable arena floor (world), verified from the game cache. */
    static final int ARENA_MIN_X = 2257;
    static final int ARENA_MAX_X = 2285;
    static final int ARENA_MIN_Y = 5329;
    static final int ARENA_MAX_Y = 5358;
    static final int GRID_BASE_X = 2240;
    static final int GRID_BASE_Y = 5312;
    static final int GRID_SIZE = 64;
    static final int PILLAR_SIZE = 3;

    static final int SEARCH_RADIUS = 7;
    /** A new tile must beat the current one by this much (HP per tick) to be worth moving. */
    static final double MOVE_HYSTERESIS = 0.5;
    /** Cost of each tile walked, in HP-per-tick units. */
    static final double MOVE_COST = 0.15;
    /** Value of being able to hit the kill target this tick (roughly our damage output). */
    static final double PROGRESS_VALUE = 3.0;
    /** Extra progress value per tick spent unable to attack anything (breaks stalemates). */
    static final double IDLE_PRESSURE = 0.25;
    /** While idle, cost per tile of distance to the kill target (pulls us toward it). */
    static final double APPROACH_COST = 0.5;
    /** Assumed share of a monster's max hit dealt per attack (hit chance x average roll). */
    static final double EXPECTED_HIT_FRACTION = 0.3;
    /** Share of a non-prioritised style's damage still blocked by flicking between attacks. */
    static final double FLICK_COVERAGE = 0.3;
    static final int BLOB_SCAN_LEAD = 3;

    static final int EAT_BELOW_HP = 55;
    static final int RESTORE_BELOW_PRAYER = 25;
    static final int BREW_SIPS_PER_RESTORE = 3;

    /** Per-NPC attack bookkeeping from observed animations and projectiles. */
    static final class Tracker {
        final int index;
        final InfernoNpcType type;
        int nextAttackTick = -1;
        Style lastStyle;

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

    public void reset() {
        trackers.clear();
        protectionByTick.clear();
        brewSipsSinceRestore = 0;
        lastUseTick = -10;
        idleTicks = 0;
    }

    public List<String> decide(InfernoObservation o) {
        List<String> actions = new ArrayList<>();
        recordProtection(o);
        updateTrackers(o);

        InfernoGrid grid = buildGrid(o);
        WorldPoint me = new WorldPoint(o.x, o.y, 0);
        idleTicks = anyHittable(grid, o, me) ? 0 : idleTicks + 1;

        // Positioning first: prayer and target are evaluated for where we will stand.
        WorldPoint stand = choosePosition(o, grid, me);
        if (!stand.equals(me)) {
            actions.add("MOVE " + stand.getX() + " " + stand.getY());
        }

        choosePrayers(o, grid, stand, actions);
        useSupplies(o, actions);
        if (stand.equals(me)) {
            chooseTarget(o, grid, me, actions);
        }
        return actions;
    }

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
            if (n.animation >= 0) {
                Style style = type.getAttackAnimations().get(n.animation);
                if (style != null) {
                    t.lastStyle = style;
                    t.nextAttackTick = o.tick + attackSpeed(type, o.wave);
                } else if (type == InfernoNpcType.MAGER && n.animation == InfernoNpcType.MAGER_RESURRECT_ANIMATION) {
                    t.nextAttackTick = o.tick + InfernoNpcType.MAGER_RESURRECT_LOCKOUT;
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

    private boolean[][] occupied(InfernoGrid grid, InfernoObservation o) {
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

    /** Whether the player on {@code from} can attack the NPC without walking. */
    static boolean canHit(InfernoGrid grid, InfernoObservation o, WorldPoint from, Npc n) {
        WorldArea a = area(n);
        return a.distanceTo(from) <= o.weaponRange && grid.hasLineOfSight(from, a);
    }

    // ------------------------------------------------------------------ positioning

    private WorldPoint choosePosition(InfernoObservation o, InfernoGrid grid, WorldPoint me) {
        boolean[][] occ = occupied(grid, o);
        Npc focus = firstInKillOrder(o, null, null);
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
        return bestScore + MOVE_HYSTERESIS < currentScore ? best : me;
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
        for (Npc n : o.npcs) {
            InfernoNpcType type = InfernoNpcType.forNpcId(n.id);
            if (type == null || !type.isThreat()) {
                continue;
            }
            WorldArea a = area(n);
            if (!InfernoThreatModel.canReachToAttack(grid, type, a, t, occ)) {
                continue;
            }
            double dps = EXPECTED_HIT_FRACTION * MaxHits.of(type, false) / type.getAttackSpeed();
            if (!type.isMeleeOnly() && a.isInMeleeDistance(t)) {
                // Adjacent rangers, magers and blobs choose melee or their main style at random:
                // half of their damage cannot be prayed against.
                double meleeDps = EXPECTED_HIT_FRACTION * MaxHits.of(type, true) / type.getAttackSpeed();
                unpredictable += 0.5 * Math.min(dps, meleeDps);
                dps = 0.5 * Math.max(dps, meleeDps);
            }
            Tracker tr = trackers.get(n.index);
            int next = tr != null && tr.nextAttackTick > o.tick ? tr.nextAttackTick : -1;
            threats.add(new Threat(type.getDefaultStyle(), dps, type.getProtectPriority(),
                    attackSpeed(type, o.wave), next));
        }
        double unprotected = unpredictable;
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
        double hpFactor = o.maxHp > 0 && o.hp < o.maxHp / 2 ? 2.0 : 1.0;
        double score = unprotected * hpFactor;

        double progress = PROGRESS_VALUE + IDLE_PRESSURE * idleTicks;
        if (focus != null && canHit(grid, o, t, focus)) {
            score -= progress;
        } else if (anyHittable(grid, o, t)) {
            score -= progress / 2;
        } else if (focus != null && idleTicks > 0) {
            // Nothing hittable from here: prefer tiles closer to the target so we close in.
            score += APPROACH_COST * area(focus).distanceTo(t);
        }
        score += MOVE_COST * Math.max(Math.abs(t.getX() - me.getX()), Math.abs(t.getY() - me.getY()));
        return score;
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

    // ------------------------------------------------------------------ prayer

    private void choosePrayers(InfernoObservation o, InfernoGrid grid, WorldPoint stand, List<String> actions) {
        int nextTick = o.tick + 1;
        Prayer wanted = null;
        int wantedPriority = -1;
        int soonest = Integer.MAX_VALUE;
        Prayer soonestPrayer = null;
        int soonestPriority = -1;

        boolean[][] occ = occupied(grid, o);
        for (Npc n : o.npcs) {
            Tracker t = trackers.get(n.index);
            if (t == null || !t.type.isThreat() || t.type.getProtectPriority() == 0) {
                continue;
            }
            if (!InfernoThreatModel.canAttack(grid, t.type, area(n), stand)
                    && !InfernoThreatModel.canReachToAttack(grid, t.type, area(n), stand, occ)) {
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
            if (attackTick < soonest || (attackTick == soonest && prio > soonestPriority)) {
                soonest = attackTick;
                soonestPrayer = p;
                soonestPriority = prio;
            }
        }
        if (wanted == null) {
            wanted = soonestPrayer;
        }

        Prayer current = protectionByTick.get(o.tick);
        if (wanted != null && wanted != current) {
            actions.add("PRAY " + wanted + " 1");
        }
        if (!o.isPrayerActive(Prayer.EAGLE_EYE) && !o.isPrayerActive(Prayer.RIGOUR) && o.prayer > 0) {
            actions.add("PRAY " + Prayer.EAGLE_EYE + " 1");
        }
    }

    private Prayer protectionFor(Tracker t, Npc n, WorldPoint stand, int attackTick) {
        Style style;
        if (t.type == InfernoNpcType.BLOB) {
            // The blob reads the protection prayer 3 ticks before attacking and uses the other style.
            Prayer read = protectionByTick.get(attackTick - BLOB_SCAN_LEAD);
            if (area(n).isInMeleeDistance(stand) && read != Prayer.PROTECT_MELEE) {
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
            if (grid != null && !canHit(grid, o, from, n)) {
                continue;
            }
            int key = InfernoThreatModelAccess.killPriority(type, pillarsUp) * 1000
                    + (from == null ? 0 : area(n).distanceTo(from));
            if (key < bestKey) {
                bestKey = key;
                best = n;
            }
        }
        return best;
    }

    private void chooseTarget(InfernoObservation o, InfernoGrid grid, WorldPoint me, List<String> actions) {
        Npc target = firstInKillOrder(o, grid, me);
        if (target != null && target.index != o.targetIndex) {
            actions.add("ATTACK " + target.index);
        }
    }

    // ------------------------------------------------------------------ supplies

    private void useSupplies(InfernoObservation o, List<String> actions) {
        if (o.tick - lastUseTick < 2) {
            return;
        }
        InfernoObservation.Item use = null;
        if (o.hp < EAT_BELOW_HP) {
            use = find(o, "SARADOMIN_BREW");
            if (use != null) {
                brewSipsSinceRestore++;
            }
        }
        if (use == null && (o.prayer < RESTORE_BELOW_PRAYER || brewSipsSinceRestore >= BREW_SIPS_PER_RESTORE)) {
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

    /** Small adapter so the brain can reuse the helper's kill priorities. */
    static final class InfernoThreatModelAccess {
        static int killPriority(InfernoNpcType type, boolean pillarsUp) {
            if (type == InfernoNpcType.NIBBLER) {
                return pillarsUp ? -1 : type.getKillPriority();
            }
            return type.getKillPriority();
        }
    }
}
