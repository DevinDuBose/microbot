package net.runelite.client.plugins.microbot.devin.inferno;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;

/** Wave, current target and next attacker. */
public class InfernoInfoOverlay extends OverlayPanel {

    private final InfernoHelperPlugin plugin;
    private final InfernoHelperConfig config;

    @Inject
    InfernoInfoOverlay(InfernoHelperPlugin plugin, InfernoHelperConfig config) {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!plugin.isInInferno() || !config.showInfoPanel()) {
            return null;
        }
        panelComponent.getChildren().add(TitleComponent.builder().text("Inferno").color(Color.ORANGE).build());

        int wave = plugin.getWave();
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Wave:")
                .right(wave < 0 ? "?" : Integer.toString(wave))
                .build());

        List<InfernoNpc> order = plugin.getKillOrder();
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Attack:")
                .right(order.isEmpty() ? "-" : order.get(0).getNpc().getName())
                .rightColor(InfernoSceneOverlay.TARGET)
                .build());

        InfernoNpc next = plugin.getNextAttacker();
        if (next != null) {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Next attack:")
                    .right(next.getNpc().getName() + " " + next.getLastStyle().name().toLowerCase()
                            + " in " + next.ticksUntilAttack(plugin.nowTick()) + "t")
                    .rightColor(InfernoSceneOverlay.styleColor(next.getLastStyle()))
                    .build());
        }
        return super.render(graphics);
    }
}
