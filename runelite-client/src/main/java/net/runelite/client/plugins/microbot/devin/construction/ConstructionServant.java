package net.runelite.client.plugins.microbot.devin.construction;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The servant that fetches planks from the bank. {@code npcName} is the in-game NPC name;
 * {@code fetchCount} is how many items it returns per trip (used for tracking / info).
 */
@Getter
@RequiredArgsConstructor
public enum ConstructionServant {
    NONE("None", 0),
    RICK("Rick", 6),
    MAID("Maid", 10),
    COOK("Cook", 16),
    BUTLER("Butler", 20),
    DEMON_BUTLER("Demon butler", 26),
    ;

    private final String npcName;
    private final int fetchCount;

    @Override
    public String toString() {
        return npcName;
    }
}
