package net.runelite.client.plugins.microbot.devin.inferno;

import net.runelite.api.NPC;
import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static net.runelite.client.plugins.microbot.devin.inferno.InfernoNpcType.Style.MAGIC;
import static net.runelite.client.plugins.microbot.devin.inferno.InfernoNpcType.Style.MELEE;
import static net.runelite.client.plugins.microbot.devin.inferno.InfernoNpcType.Style.RANGE;
import static net.runelite.client.plugins.microbot.devin.inferno.InfernoNpcType.Style.UNKNOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins the data table to the verified values. Numeric ids are the ones
 * checked against the game cache by cache/InfernoDataVerificationTest; the
 * combat numbers are the OSRS Wiki values that agree with the xKylee plugin.
 */
public class InfernoNpcTypeTest {

    @Test
    public void npcIdsMatchCacheVerifiedIds() {
        assertEquals(InfernoNpcType.NIBBLER, InfernoNpcType.forNpcId(7691));
        assertEquals(InfernoNpcType.BAT, InfernoNpcType.forNpcId(7692));
        assertEquals(InfernoNpcType.BLOB, InfernoNpcType.forNpcId(7693));
        assertEquals(InfernoNpcType.BLOB_SPLIT_MAGE, InfernoNpcType.forNpcId(7694));
        assertEquals(InfernoNpcType.BLOB_SPLIT_RANGE, InfernoNpcType.forNpcId(7695));
        assertEquals(InfernoNpcType.BLOB_SPLIT_MELEE, InfernoNpcType.forNpcId(7696));
        assertEquals(InfernoNpcType.MELEER, InfernoNpcType.forNpcId(7697));
        assertEquals(InfernoNpcType.RANGER, InfernoNpcType.forNpcId(7698));
        assertEquals(InfernoNpcType.RANGER, InfernoNpcType.forNpcId(7702));
        assertEquals(InfernoNpcType.MAGER, InfernoNpcType.forNpcId(7699));
        assertEquals(InfernoNpcType.MAGER, InfernoNpcType.forNpcId(7703));
        assertEquals(InfernoNpcType.JAD, InfernoNpcType.forNpcId(7700));
        assertEquals(InfernoNpcType.JAD, InfernoNpcType.forNpcId(7704));
        assertEquals(InfernoNpcType.JAD_HEALER, InfernoNpcType.forNpcId(7701));
        assertEquals(InfernoNpcType.JAD_HEALER, InfernoNpcType.forNpcId(7705));
        assertEquals(InfernoNpcType.ZUK, InfernoNpcType.forNpcId(7706));
        assertEquals(InfernoNpcType.ZUK_HEALER, InfernoNpcType.forNpcId(7708));
        assertEquals(7707, InfernoNpcType.ZUK_SHIELD_ID);
        // 12594 is a size-2 "Jal-ImKot" from other content, not the Inferno meleer.
        assertNull(InfernoNpcType.forNpcId(12594));
        // Pillars are not monsters.
        assertNull(InfernoNpcType.forNpcId(7709));
    }

    @Test
    public void npcIdsAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (InfernoNpcType t : InfernoNpcType.values()) {
            for (int id : t.getNpcIds()) {
                assertTrue("duplicate id " + id, seen.add(id));
            }
        }
    }

    @Test
    public void attackAnimationsMatchCacheVerifiedIds() {
        assertEquals(Map.of(7574, MELEE), InfernoNpcType.NIBBLER.getAttackAnimations());
        assertEquals(Map.of(7578, RANGE), InfernoNpcType.BAT.getAttackAnimations());
        // 7581/7583 have contradictory style names across sources: attack only, style unknown.
        assertEquals(Map.of(7582, MELEE, 7581, UNKNOWN, 7583, UNKNOWN), InfernoNpcType.BLOB.getAttackAnimations());
        assertEquals(Map.of(7597, MELEE), InfernoNpcType.MELEER.getAttackAnimations());
        assertEquals(Map.of(7605, RANGE, 7604, MELEE), InfernoNpcType.RANGER.getAttackAnimations());
        assertEquals(Map.of(7610, MAGIC, 7612, MELEE), InfernoNpcType.MAGER.getAttackAnimations());
        assertEquals(Map.of(7592, MAGIC, 7593, RANGE, 7590, MELEE), InfernoNpcType.JAD.getAttackAnimations());
        assertEquals(Map.of(7566, UNKNOWN), InfernoNpcType.ZUK.getAttackAnimations());
        assertEquals(7600, InfernoNpcType.MELEER_DIG_ANIMATION);
        assertEquals(7601, InfernoNpcType.MELEER_RESURFACE_ANIMATION);
        assertEquals(7611, InfernoNpcType.MAGER_RESURRECT_ANIMATION);
    }

    @Test
    public void attackSpeedsMatchWiki() {
        assertEquals(4, InfernoNpcType.NIBBLER.getAttackSpeed());
        assertEquals(3, InfernoNpcType.BAT.getAttackSpeed());
        assertEquals(6, InfernoNpcType.BLOB.getAttackSpeed());
        assertEquals(4, InfernoNpcType.BLOB_SPLIT_MAGE.getAttackSpeed());
        assertEquals(4, InfernoNpcType.BLOB_SPLIT_RANGE.getAttackSpeed());
        assertEquals(4, InfernoNpcType.BLOB_SPLIT_MELEE.getAttackSpeed());
        assertEquals(4, InfernoNpcType.MELEER.getAttackSpeed());
        assertEquals(4, InfernoNpcType.RANGER.getAttackSpeed());
        assertEquals(4, InfernoNpcType.MAGER.getAttackSpeed());
        assertEquals(8, InfernoNpcType.JAD.getAttackSpeed());
        assertEquals(10, InfernoNpcType.ZUK.getAttackSpeed());
        assertEquals(7, InfernoNpcType.ZUK_ENRAGED_ATTACK_SPEED);
        assertEquals(9, InfernoNpcType.TRIPLE_JAD_ATTACK_SPEED);
    }

    @Test
    public void rangesAreVerifiedOrConservative() {
        assertEquals(4, InfernoNpcType.BAT.getRange());
        assertTrue(InfernoNpcType.MELEER.isMeleeOnly());
        assertTrue(InfernoNpcType.NIBBLER.isMeleeOnly());
        assertTrue(InfernoNpcType.JAD_HEALER.isMeleeOnly());
        // Unverified exact ranges are treated as unlimited (never under-report a threat).
        for (InfernoNpcType t : new InfernoNpcType[]{InfernoNpcType.BLOB, InfernoNpcType.RANGER,
                InfernoNpcType.MAGER, InfernoNpcType.JAD, InfernoNpcType.BLOB_SPLIT_MAGE, InfernoNpcType.BLOB_SPLIT_RANGE}) {
            assertTrue(t + " range", t.getRange() >= 30);
        }
    }

    @Test
    public void protectionPriorityFollowsWiki() {
        // "prioritise protection in order of mager > ranger > meleer > blob > bat"; Jad above all.
        assertTrue(InfernoNpcType.JAD.getProtectPriority() > InfernoNpcType.MAGER.getProtectPriority());
        assertTrue(InfernoNpcType.MAGER.getProtectPriority() > InfernoNpcType.RANGER.getProtectPriority());
        assertTrue(InfernoNpcType.RANGER.getProtectPriority() > InfernoNpcType.MELEER.getProtectPriority());
        assertTrue(InfernoNpcType.MELEER.getProtectPriority() > InfernoNpcType.BLOB.getProtectPriority());
        assertTrue(InfernoNpcType.BLOB.getProtectPriority() > InfernoNpcType.BLOB_SPLIT_MAGE.getProtectPriority());
        assertTrue(InfernoNpcType.BLOB_SPLIT_MAGE.getProtectPriority() > InfernoNpcType.BAT.getProtectPriority());
        assertTrue(InfernoNpcType.BAT.getProtectPriority() > 0);
    }

    @Test
    public void killOrderFollowsSources() {
        InfernoNpcType[] expected = {
                InfernoNpcType.JAD, InfernoNpcType.MAGER, InfernoNpcType.RANGER, InfernoNpcType.MELEER,
                InfernoNpcType.BLOB, InfernoNpcType.BLOB_SPLIT_MAGE, InfernoNpcType.JAD_HEALER, InfernoNpcType.BAT,
                InfernoNpcType.ZUK_HEALER, InfernoNpcType.ZUK, InfernoNpcType.NIBBLER};
        for (int i = 1; i < expected.length; i++) {
            assertTrue(expected[i - 1] + " before " + expected[i],
                    expected[i - 1].getKillPriority() < expected[i].getKillPriority());
        }
    }

    @Test
    public void nonThreatsAreExcludedFromTiles() {
        assertFalse(InfernoNpcType.NIBBLER.isThreat());
        assertFalse(InfernoNpcType.ZUK.isThreat());
        assertFalse(InfernoNpcType.ZUK_HEALER.isThreat());
        assertTrue(InfernoNpcType.JAD_HEALER.isThreat());
    }

    @Test
    public void styleProjectilesMatchCacheNames() {
        assertEquals(MAGIC, InfernoNpcType.STYLE_PROJECTILES.get(1380));
        assertEquals(MAGIC, InfernoNpcType.STYLE_PROJECTILES.get(1381));
        assertEquals(MAGIC, InfernoNpcType.STYLE_PROJECTILES.get(1609));
        assertEquals(MAGIC, InfernoNpcType.STYLE_PROJECTILES.get(1610));
        assertEquals(RANGE, InfernoNpcType.STYLE_PROJECTILES.get(1378));
        assertEquals(RANGE, InfernoNpcType.STYLE_PROJECTILES.get(1379));
        assertEquals(6, InfernoNpcType.STYLE_PROJECTILES.size());
    }

    // ---- attack timer state ----

    @Test
    public void attackStartsTimerAndRevealsStyle() {
        InfernoNpc mager = new InfernoNpc(mock(NPC.class), InfernoNpcType.MAGER);
        assertEquals(-1, mager.ticksUntilAttack(100));
        assertTrue(mager.onAnimation(7612, 100, 4));
        assertEquals(MELEE, mager.getLastStyle());
        assertEquals(4, mager.ticksUntilAttack(100));
        assertEquals(1, mager.ticksUntilAttack(103));
        assertEquals(0, mager.ticksUntilAttack(110));
        assertFalse(mager.onAnimation(9999, 101, 4));
        assertEquals(3, mager.ticksUntilAttack(101));
    }

    @Test
    public void resurrectLocksMagerOut() {
        InfernoNpc mager = new InfernoNpc(mock(NPC.class), InfernoNpcType.MAGER);
        assertFalse(mager.onAnimation(7611, 50, 4));
        assertEquals(8, mager.ticksUntilAttack(50));
    }

    @Test
    public void meleerBurrowHidesTimerUntilResurface() {
        InfernoNpc meleer = new InfernoNpc(mock(NPC.class), InfernoNpcType.MELEER);
        meleer.onAnimation(7597, 10, 4);
        meleer.onAnimation(7600, 12, 4);
        assertTrue(meleer.isBurrowed());
        assertEquals(-1, meleer.ticksUntilAttack(12));
        meleer.onAnimation(7601, 20, 4);
        assertFalse(meleer.isBurrowed());
        assertEquals(6, meleer.ticksUntilAttack(20));
    }

    @Test
    public void blobStyleComesFromProjectile() {
        InfernoNpc blob = new InfernoNpc(mock(NPC.class), InfernoNpcType.BLOB);
        assertTrue(blob.onAnimation(7581, 0, 6));
        assertEquals(UNKNOWN, blob.getLastStyle());
        assertEquals(6, blob.ticksUntilAttack(0));
        blob.onStyleProjectile(MAGIC);
        assertEquals(MAGIC, blob.getLastStyle());
    }
}
