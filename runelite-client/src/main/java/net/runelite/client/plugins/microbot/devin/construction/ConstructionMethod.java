package net.runelite.client.plugins.microbot.devin.construction;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * A POH build-and-remove training method. {@code buildMenuName} is the text matched in the
 * furniture-creation interface when choosing what to build; {@code plankId}/{@code planksPerBuild}
 * drive plank tracking and servant fetching.
 */
@Getter
@RequiredArgsConstructor
public enum ConstructionMethod {
    OAK_LARDER("Oak larder", "Oak larder", ItemID.PLANK_OAK, 8, 33, 480),
    TEAK_TABLE("Teak table", "Teak table", ItemID.PLANK_TEAK, 6, 38, 270),
    MAHOGANY_TABLE("Mahogany table", "Mahogany table", ItemID.PLANK_MAHOGANY, 6, 52, 840),
    OAK_DUNGEON_DOOR("Oak dungeon door", "Oak door", ItemID.PLANK_OAK, 10, 74, 600),
    GNOME_BENCH("Gnome bench", "Gnome bench", ItemID.PLANK_MAHOGANY, 6, 77, 840),
    ;

    private final String displayName;
    private final String buildMenuName;
    private final int plankId;
    private final int planksPerBuild;
    private final int levelRequired;
    private final int xpPerBuild;

    @Override
    public String toString() {
        return displayName;
    }
}
