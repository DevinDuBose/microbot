package net.runelite.client.plugins.microbot.devin.inferno.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the brain may know about one game tick. Contains only what the
 * live client can observe (positions, animations, projectiles, stats,
 * inventory), never simulator internals such as monster attack timers, so a
 * strategy that works in simulation uses the same information live.
 * Coordinates are world coordinates; areas are given by their south-west tile.
 */
public final class InfernoObservation {

    public int tick;
    public int wave;

    // Player
    public int x;
    public int y;
    public int hp;
    public int maxHp;
    public int prayer;
    public int maxPrayer;
    public int ranged;
    public int maxRanged;
    /** Weapon attack range in tiles for the current attack style. */
    public int weaponRange;
    /** Ticks between the player's attacks. */
    public int attackSpeed;
    /** Ticks until the player can attack again (0 = ready). */
    public int attackCooldown;
    /** NPC index the player is attacking, or -1. */
    public int targetIndex = -1;
    /** Active prayer names (see {@link Prayer}). */
    public final List<Prayer> activePrayers = new ArrayList<>();

    public final List<Item> inventory = new ArrayList<>();
    /** Spells the player can cast here (e.g. "ICE_BARRAGE": Ancient spellbook, runes, level 94). */
    public final List<String> spells = new ArrayList<>();
    public final List<Pillar> pillars = new ArrayList<>();
    public final List<Npc> npcs = new ArrayList<>();
    public final List<Projectile> projectiles = new ArrayList<>();

    public enum Prayer {
        PROTECT_MAGIC, PROTECT_MISSILES, PROTECT_MELEE, EAGLE_EYE, RIGOUR
    }

    public static final class Item {
        public final int slot;
        public final String name;
        public final int doses;

        public Item(int slot, String name, int doses) {
            this.slot = slot;
            this.name = name;
            this.doses = doses;
        }
    }

    public static final class Pillar {
        public final int x;
        public final int y;
        public final int hp;

        public Pillar(int x, int y, int hp) {
            this.x = x;
            this.y = y;
            this.hp = hp;
        }
    }

    public static final class Npc {
        public final int index;
        public final int id;
        public final int x;
        public final int y;
        public final int size;
        /** Health 0-100, or -1 when no health bar is visible. */
        public final int hpPercent;
        /** Animation that started this tick, or -1. */
        public final int animation;

        public Npc(int index, int id, int x, int y, int size, int hpPercent, int animation) {
            this.index = index;
            this.id = id;
            this.x = x;
            this.y = y;
            this.size = size;
            this.hpPercent = hpPercent;
            this.animation = animation;
        }
    }

    public static final class Projectile {
        public final int spotanimId;
        public final int sourceIndex;
        /** Ticks until it lands. */
        public final int ticksToImpact;

        public Projectile(int spotanimId, int sourceIndex, int ticksToImpact) {
            this.spotanimId = spotanimId;
            this.sourceIndex = sourceIndex;
            this.ticksToImpact = ticksToImpact;
        }
    }

    public boolean isPrayerActive(Prayer p) {
        return activePrayers.contains(p);
    }
}
