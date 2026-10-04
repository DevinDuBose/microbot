package net.runelite.client.plugins.microbot.devin.construction;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Duration;
import java.time.Instant;

public class ConstructionOverlay extends OverlayPanel {

    private final ConstructionPlugin plugin;
    private final ConstructionConfig config;

    @Inject
    ConstructionOverlay(ConstructionPlugin plugin, ConstructionConfig config) {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Construction Trainer")
                .color(Color.CYAN)
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Method:")
                .right(config.method().getDisplayName())
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Builds:")
                .right(String.valueOf(plugin.getBuildsCompleted()))
                .build());

        int xp = plugin.getXpGained();
        panelComponent.getChildren().add(LineComponent.builder()
                .left("XP gained:")
                .right(String.format("%,d", xp))
                .build());

        Instant start = plugin.getStartTime();
        if (start != null) {
            long secs = Duration.between(start, Instant.now()).getSeconds();
            if (secs > 0) {
                long xpPerHour = (long) (xp / (secs / 3600.0));
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("XP/hr:")
                        .right(String.format("%,d", xpPerHour))
                        .build());
            }
        }

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Status:")
                .right(Microbot.status)
                .build());

        return super.render(graphics);
    }
}
