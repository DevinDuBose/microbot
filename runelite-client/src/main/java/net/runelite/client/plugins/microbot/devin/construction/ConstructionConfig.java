package net.runelite.client.plugins.microbot.devin.construction;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigInformation;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ConstructionConfig.GROUP)
@ConfigInformation("<h2>Construction Trainer</h2>\n" +
        "<p>Trains Construction in your POH by building and removing furniture at a hotspot.</p>\n" +
        "<p><b>Setup before starting:</b></p>\n" +
        "<p>1. Enter your POH in <b>build mode</b> with a hammer + saw.</p>\n" +
        "<p>2. Have the required <b>room built</b> (Kitchen for larders, Dining room for tables, etc.).</p>\n" +
        "<p>3. Stand next to the hotspot for your chosen furniture.</p>\n" +
        "<p>4. If using a servant, <b>hire it</b> and do <b>one manual plank fetch</b> first so it remembers the item, and keep planks + coins in the bank.</p>\n")
public interface ConstructionConfig extends Config {
    String GROUP = "DevinConstruction";

    @ConfigItem(
            keyName = "method",
            name = "Furniture",
            description = "Which furniture to build and remove for XP",
            position = 0
    )
    default ConstructionMethod method() {
        return ConstructionMethod.OAK_LARDER;
    }

    @ConfigItem(
            keyName = "servant",
            name = "Servant",
            description = "Servant used to fetch planks from the bank. Set to None to just build until planks run out.",
            position = 1
    )
    default ConstructionServant servant() {
        return ConstructionServant.DEMON_BUTLER;
    }

    @ConfigItem(
            keyName = "payServant",
            name = "Auto-pay servant",
            description = "Automatically pay the servant's fee (e.g. Demon butler's 10k) when demanded",
            position = 2
    )
    default boolean payServant() {
        return true;
    }
}
