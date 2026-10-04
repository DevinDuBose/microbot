package net.runelite.client.plugins.microbot.devin.construction;

import com.google.inject.Provides;
import lombok.Getter;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.time.Instant;

@PluginDescriptor(
        name = PluginDescriptor.Devin + "Construction",
        description = "Trains Construction by building and removing furniture in your POH",
        tags = {"construction", "poh", "skilling", "microbot", "devin"},
        authors = {"Devin"},
        enabledByDefault = false
)
public class ConstructionPlugin extends Plugin {

    @Inject
    @Getter
    private ConstructionConfig config;

    @Inject
    private ConstructionScript constructionScript;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ConstructionOverlay constructionOverlay;

    @Getter
    private Instant startTime;

    @Getter
    private int buildsCompleted = 0;

    @Getter
    private int xpGained = 0;

    @Provides
    ConstructionConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ConstructionConfig.class);
    }

    @Override
    protected void startUp() {
        startTime = Instant.now();
        buildsCompleted = 0;
        xpGained = 0;
        overlayManager.add(constructionOverlay);
        constructionScript.run(config);
    }

    @Override
    protected void shutDown() {
        constructionScript.shutdown();
        overlayManager.remove(constructionOverlay);
    }

    public void onBuildCompleted(int xp) {
        buildsCompleted++;
        xpGained += xp;
    }
}
