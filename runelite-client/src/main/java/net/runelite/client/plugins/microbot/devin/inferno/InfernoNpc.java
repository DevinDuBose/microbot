package net.runelite.client.plugins.microbot.devin.inferno;

import lombok.Getter;
import net.runelite.api.NPC;

/**
 * Per-NPC tracking for the Inferno helper: the live {@link NPC} (dropped on
 * despawn) plus attack-timer bookkeeping. Positioning logic lives in
 * {@link InfernoThreatModel}.
 */
@Getter
public class InfernoNpc {

    private final NPC npc;
    private final InfernoNpcType type;

    /** Client tick at which this NPC can next attack, or -1 until its first attack is seen. */
    private int nextAttackTick = -1;

    /** Style of the last observed attack; UNKNOWN until revealed (blob/Jad). */
    private InfernoNpcType.Style lastStyle;

    /** Meleer burrowed and has not resurfaced yet. */
    private boolean burrowed;

    InfernoNpc(NPC npc, InfernoNpcType type) {
        this.npc = npc;
        this.type = type;
        this.lastStyle = type.getDefaultStyle();
    }

    /** Ticks until the next attack, or -1 if unknown. */
    public int ticksUntilAttack(int nowTick) {
        if (nextAttackTick < 0 || burrowed) {
            return -1;
        }
        return Math.max(0, nextAttackTick - nowTick);
    }

    /**
     * Applies an animation change. Returns true if it was an attack.
     *
     * @param attackSpeed the current attack speed (Zuk enrage and triple Jad differ from the type default)
     */
    boolean onAnimation(int animation, int nowTick, int attackSpeed) {
        if (type == InfernoNpcType.MELEER && animation == InfernoNpcType.MELEER_DIG_ANIMATION) {
            burrowed = true;
            return false;
        }
        if (type == InfernoNpcType.MELEER && animation == InfernoNpcType.MELEER_RESURFACE_ANIMATION) {
            burrowed = false;
            nextAttackTick = nowTick + InfernoNpcType.MELEER_RESURFACE_DELAY;
            return false;
        }
        if (type == InfernoNpcType.MAGER && animation == InfernoNpcType.MAGER_RESURRECT_ANIMATION) {
            nextAttackTick = nowTick + InfernoNpcType.MAGER_RESURRECT_LOCKOUT;
            return false;
        }
        InfernoNpcType.Style style = type.getAttackAnimations().get(animation);
        if (style == null) {
            return false;
        }
        burrowed = false;
        lastStyle = style;
        nextAttackTick = nowTick + attackSpeed;
        return true;
    }

    /** A style-revealing projectile from this NPC (blob and its splits). */
    void onStyleProjectile(InfernoNpcType.Style style) {
        lastStyle = style;
    }
}
