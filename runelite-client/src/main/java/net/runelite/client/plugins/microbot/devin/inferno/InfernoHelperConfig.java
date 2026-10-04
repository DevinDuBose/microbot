package net.runelite.client.plugins.microbot.devin.inferno;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("devinInfernoHelper")
public interface InfernoHelperConfig extends Config {

    @ConfigSection(name = "Positioning", description = "Safespot tile overlay", position = 0)
    String positioning = "positioning";

    @ConfigSection(name = "Targets", description = "Attack priority and attack timers", position = 1)
    String targets = "targets";

    @ConfigSection(name = "Zuk", description = "Final wave helpers", position = 2)
    String zuk = "zuk";

    enum SafespotMode {
        OFF,
        SAFE_ONLY,
        SAFE_AND_SINGLE,
        ALL
    }

    @ConfigItem(
            keyName = "safespotMode",
            name = "Safespot tiles",
            description = "Which tiles to shade. Green = no monster can hit you there, yellow = exactly one attack style, red = two or more.",
            position = 0,
            section = positioning
    )
    default SafespotMode safespotMode() {
        return SafespotMode.SAFE_AND_SINGLE;
    }

    @Range(min = 2, max = 15)
    @ConfigItem(
            keyName = "safespotRadius",
            name = "Tile radius",
            description = "How far around you to evaluate tiles",
            position = 1,
            section = positioning
    )
    default int safespotRadius() {
        return 8;
    }

    @ConfigItem(
            keyName = "predictMovement",
            name = "Predict movement",
            description = "Also count monsters that would walk into attack position (standard NPC pathing, up to 30 steps). Off = only where they stand now.",
            position = 2,
            section = positioning
    )
    default boolean predictMovement() {
        return true;
    }

    @ConfigItem(
            keyName = "showThreatLetters",
            name = "Threat letters",
            description = "Write which styles can reach each yellow/red tile (M = melee, R = range, Ma = magic, ? = blob/Jad)",
            position = 3,
            section = positioning
    )
    default boolean showThreatLetters() {
        return false;
    }

    @ConfigItem(
            keyName = "showAttackPriority",
            name = "Attack priority",
            description = "Number every monster by kill order and outline the one to attack now",
            position = 0,
            section = targets
    )
    default boolean showAttackPriority() {
        return true;
    }

    @ConfigItem(
            keyName = "nibblersFirst",
            name = "Nibblers first",
            description = "Rank nibblers first to protect the pillars. The OSRS Wiki advises not to risk it in waves 50+.",
            position = 1,
            section = targets
    )
    default boolean nibblersFirst() {
        return true;
    }

    @ConfigItem(
            keyName = "showAttackTimers",
            name = "Attack timers",
            description = "Ticks until each monster can attack again, coloured by style",
            position = 2,
            section = targets
    )
    default boolean showAttackTimers() {
        return true;
    }

    @ConfigItem(
            keyName = "showInfoPanel",
            name = "Info panel",
            description = "Wave number, next target and the next incoming hit",
            position = 3,
            section = targets
    )
    default boolean showInfoPanel() {
        return true;
    }

    @ConfigItem(
            keyName = "showZukShield",
            name = "Shield cover",
            description = "Shade the tiles the moving shield currently covers from TzKal-Zuk",
            position = 0,
            section = zuk
    )
    default boolean showZukShield() {
        return true;
    }
}
