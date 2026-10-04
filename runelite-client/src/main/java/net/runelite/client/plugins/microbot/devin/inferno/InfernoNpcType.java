package net.runelite.client.plugins.microbot.devin.inferno;

import lombok.Getter;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.SpotanimID;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Inferno monster data. Every value is cross-checked; sources per column:
 * <ul>
 *   <li>NPC ids, names and tile sizes: game cache (InfernoDataVerificationTest)
 *       and gameval constants.</li>
 *   <li>Attack animations: game cache — each animation runs on the owning
 *       NPC's skeleton (InfernoDataVerificationTest); ids also match the
 *       xKylee/OpenOSRS Inferno plugin.</li>
 *   <li>Attack speed: OSRS Wiki infoboxes and the xKylee plugin agree.</li>
 *   <li>Attack range: bat 4 (wiki + xKylee), meleer melee-only (wiki +
 *       xKylee). The wiki only says every other monster outranges a player's
 *       10-tile maximum; xKylee uses 15 for the blob, unconfirmed elsewhere,
 *       so every long-range monster is treated as unlimited. That can only
 *       turn a safe tile red, never mark a dangerous tile safe.</li>
 *   <li>Protection priority: OSRS Wiki Inferno/Strategies, "prioritise
 *       protection in order of mager &gt; ranger &gt; meleer &gt; blob &gt; bat",
 *       Max hits are 70 / 46 / 49 / 29 / 19; the meleer ranks below the
 *       ranger despite its higher max hit because it only reaches you when
 *       adjacent.</li>
 *   <li>Kill order: xKylee plugin priorities (Jad, mager, ranger, meleer,
 *       blob, Jad healer, bat, Zuk, nibbler), consistent with the wiki's
 *       "killing the mager first is a good idea". Exceptions, both from the
 *       wiki: blob splits rank with the blob, Zuk's healers before Zuk
 *       ("quickly tag them"), and nibblers can be moved first to protect
 *       the pillars (config).</li>
 * </ul>
 * The blob's two non-melee animations (7581/7583) are named with opposite
 * styles by the game cache and by RuneLite's old constants, so they are
 * treated as "attacked, style unknown"; the blob's style is taken from its
 * projectile instead.
 */
@Getter
public enum InfernoNpcType {

    NIBBLER(ids(NpcID.INFERNO_NIBBLER), Style.MELEE, 4, Range.MELEE, 100, 0, false,
            anims(AnimationID.JALNIB_ATTACK, Style.MELEE)),

    BAT(ids(NpcID.INFERNO_CREATURE_HARPIE), Style.RANGE, 3, 4, 70, 50, true,
            anims(AnimationID.JALMEJRAH_ATTACK, Style.RANGE)),

    BLOB(ids(NpcID.INFERNO_CREATURE_SPLITTER), Style.UNKNOWN, 6, Range.LONG, 40, 60, true,
            anims(AnimationID.JALAK_ATTACK_MELEE, Style.MELEE,
                    AnimationID.JALAK_ATTACK_MAGIC, Style.UNKNOWN,
                    AnimationID.JALAK_ATTACK_RANGED, Style.UNKNOWN)),

    BLOB_SPLIT_MAGE(ids(NpcID.INFERNO_CREATURE_SPLITTER_MAGE), Style.MAGIC, 4, Range.LONG, 45, 55, true,
            anims()),

    BLOB_SPLIT_RANGE(ids(NpcID.INFERNO_CREATURE_SPLITTER_RANGE), Style.RANGE, 4, Range.LONG, 45, 55, true,
            anims()),

    BLOB_SPLIT_MELEE(ids(NpcID.INFERNO_CREATURE_SPLITTER_MELEE), Style.MELEE, 4, Range.MELEE, 45, 55, true,
            anims()),

    MELEER(ids(NpcID.INFERNO_CREATURE_MELEE), Style.MELEE, 4, Range.MELEE, 30, 70, true,
            anims(AnimationID.JALIMKOT_ATTACK, Style.MELEE)),

    RANGER(ids(NpcID.INFERNO_CREATURE_RANGER, NpcID.INFERNO_RANGER_FINALWAVE), Style.RANGE, 4, Range.LONG, 20, 80, true,
            anims(AnimationID.JALXIL_ATTACK_RANGED, Style.RANGE,
                    AnimationID.JALXIL_ATTACK_MELEE, Style.MELEE)),

    MAGER(ids(NpcID.INFERNO_CREATURE_MAGER, NpcID.INFERNO_MAGER_FINALWAVE), Style.MAGIC, 4, Range.LONG, 10, 90, true,
            anims(AnimationID.JALAKXIL_ATTACK_MAGIC, Style.MAGIC,
                    AnimationID.JALAKXIL_ATTACK_MELEE, Style.MELEE)),

    JAD(ids(NpcID.INFERNO_JAD, NpcID.INFERNO_JAD_FINALWAVE), Style.UNKNOWN, 8, Range.LONG, 5, 100, true,
            anims(AnimationID.JALTOKJAD_ATTACK_MAGIC, Style.MAGIC,
                    AnimationID.JALTOKJAD_ATTACK_RANGED, Style.RANGE,
                    AnimationID.JALTOKJAD_ATTACK_MELEE, Style.MELEE)),

    JAD_HEALER(ids(NpcID.INFERNO_JAD_HEALER, NpcID.INFERNO_JAD_HEALER_FINALWAVE), Style.MELEE, 4, Range.MELEE, 60, 0, true,
            anims()),

    /** Zuk's hit is typeless and only the shield blocks it, so it never feeds the tile threat map. */
    ZUK(ids(NpcID.INFERNO_TZKALZUK_PLACEHOLDER), Style.UNKNOWN, 10, Range.LONG, 99, 0, false,
            anims(AnimationID.ZUK_ATTACK, Style.UNKNOWN)),

    ZUK_HEALER(ids(NpcID.INFERNO_ZUK_HEALER), Style.UNKNOWN, 3, Range.LONG, 98, 0, false,
            anims());

    /** Zuk's attack speed once enraged at 240 hitpoints or less (OSRS Wiki: 10 → 7). */
    public static final int ZUK_ENRAGED_ATTACK_SPEED = 7;

    /** Wave-68 Jads attack every 9 ticks instead of 8 (OSRS Wiki Inferno/Strategies). */
    public static final int TRIPLE_JAD_ATTACK_SPEED = 9;

    /** Meleer burrow animation; it cannot attack until it resurfaces next to the player. */
    public static final int MELEER_DIG_ANIMATION = AnimationID.JALIMKOT_DIGDOWN;

    /** OSRS Wiki: "a six game tick delay before the Jal-ImKot attacks after resurfacing". */
    public static final int MELEER_RESURFACE_ANIMATION = AnimationID.JALIMKOT_DIGUP;
    public static final int MELEER_RESURFACE_DELAY = 6;

    /** Mager resurrect animation; it skips its own attack while reviving (OSRS Wiki + xKylee: 8 ticks). */
    public static final int MAGER_RESURRECT_ANIMATION = AnimationID.JALAKXIL_RESURRECT;
    public static final int MAGER_RESURRECT_LOCKOUT = 8;

    /** The moving Zuk shield (Ancestral Glyph). */
    public static final int ZUK_SHIELD_ID = NpcID.INFERNO_MOVING_SAFESPOT;

    /** Projectiles that reveal a blob's (or split's) chosen style. Names from the game cache. */
    public static final Map<Integer, Style> STYLE_PROJECTILES;

    static {
        Map<Integer, Style> p = new HashMap<>();
        p.put(SpotanimID.INFERNO_SPLITTER_MAGE, Style.MAGIC);
        p.put(SpotanimID.INFERNO_SPLITTER_RANGE, Style.RANGE);
        p.put(SpotanimID.INFERNO_BABYSPLITTER_MAGE, Style.MAGIC);
        p.put(SpotanimID.INFERNO_BABYSPLITTER_MAGE_BIG, Style.MAGIC);
        p.put(SpotanimID.INFERNO_BABYSPLITTER_MAGE_BIGGEST, Style.MAGIC);
        p.put(SpotanimID.INFERNO_BABYSPLITTER_RANGE, Style.RANGE);
        STYLE_PROJECTILES = Collections.unmodifiableMap(p);
    }

    public enum Style {
        MELEE, RANGE, MAGIC, UNKNOWN
    }

    /** Range sentinels. */
    public static final class Range {
        public static final int MELEE = 1;
        /** Unlimited for any arena distance. */
        public static final int LONG = 99;

        private Range() {
        }
    }

    private final Set<Integer> npcIds;
    private final Style defaultStyle;
    private final int attackSpeed;
    private final int range;
    /** Lower attacks first. */
    private final int killPriority;
    /** Higher wins when hits land on the same tick; 0 = not prayable / not a threat to pray against. */
    private final int protectPriority;
    /** Whether it can hit the player from its position (feeds the tile threat map). */
    private final boolean threat;
    /** Attack animation → style it reveals (UNKNOWN = attacked, style not readable from the animation). */
    private final Map<Integer, Style> attackAnimations;

    InfernoNpcType(Set<Integer> npcIds, Style defaultStyle, int attackSpeed, int range, int killPriority,
                   int protectPriority, boolean threat, Map<Integer, Style> attackAnimations) {
        this.npcIds = npcIds;
        this.defaultStyle = defaultStyle;
        this.attackSpeed = attackSpeed;
        this.range = range;
        this.killPriority = killPriority;
        this.protectPriority = protectPriority;
        this.threat = threat;
        this.attackAnimations = attackAnimations;
    }

    public boolean isMeleeOnly() {
        return range == Range.MELEE;
    }

    public static InfernoNpcType forNpcId(int id) {
        for (InfernoNpcType t : values()) {
            if (t.npcIds.contains(id)) {
                return t;
            }
        }
        return null;
    }

    private static Set<Integer> ids(Integer... ids) {
        return Set.of(ids);
    }

    private static Map<Integer, Style> anims(Object... pairs) {
        Map<Integer, Style> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((Integer) pairs[i], (Style) pairs[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }
}
