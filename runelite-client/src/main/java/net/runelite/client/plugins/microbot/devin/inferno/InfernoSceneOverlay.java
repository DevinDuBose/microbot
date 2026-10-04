package net.runelite.client.plugins.microbot.devin.inferno;

import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.util.List;
import java.util.Map;

/**
 * In-world drawing: safespot tiles, kill-order numbers, attack timers and
 * Zuk shield cover. Reads the plugin's per-tick snapshots only.
 */
public class InfernoSceneOverlay extends Overlay {

    static final Color SAFE = new Color(0, 200, 0);
    static final Color SINGLE = new Color(255, 200, 0);
    static final Color MULTI = new Color(220, 0, 0);
    static final Color TARGET = new Color(0, 255, 255);

    private final Client client;
    private final InfernoHelperPlugin plugin;
    private final InfernoHelperConfig config;

    @Inject
    InfernoSceneOverlay(Client client, InfernoHelperPlugin plugin, InfernoHelperConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!plugin.isInInferno()) {
            return null;
        }
        renderTiles(g);
        if (config.showZukShield()) {
            renderZukShield(g);
        }
        if (config.showAttackPriority()) {
            renderKillOrder(g);
        }
        if (config.showAttackTimers()) {
            renderTimers(g);
        }
        return null;
    }

    private void renderTiles(Graphics2D g) {
        InfernoHelperConfig.SafespotMode mode = config.safespotMode();
        if (mode == InfernoHelperConfig.SafespotMode.OFF) {
            return;
        }
        for (Map.Entry<WorldPoint, Integer> e : plugin.getThreatMap().entrySet()) {
            int threats = Integer.bitCount(e.getValue());
            Color c;
            if (threats == 0) {
                c = SAFE;
            } else if (threats == 1) {
                if (mode == InfernoHelperConfig.SafespotMode.SAFE_ONLY) {
                    continue;
                }
                c = SINGLE;
            } else {
                if (mode != InfernoHelperConfig.SafespotMode.ALL) {
                    continue;
                }
                c = MULTI;
            }
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), e.getKey());
            if (lp == null) {
                continue;
            }
            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) {
                continue;
            }
            OverlayUtil.renderPolygon(g, poly, c, new Color(c.getRed(), c.getGreen(), c.getBlue(), 40), new BasicStroke(1));
            if (config.showThreatLetters() && threats > 0) {
                Point text = Perspective.getCanvasTextLocation(client, g, lp, letters(e.getValue()), 0);
                if (text != null) {
                    OverlayUtil.renderTextLocation(g, text, letters(e.getValue()), Color.WHITE);
                }
            }
        }
    }

    static String letters(int mask) {
        StringBuilder sb = new StringBuilder();
        if ((mask & InfernoThreatModel.THREAT_MELEE) != 0) {
            sb.append('M');
        }
        if ((mask & InfernoThreatModel.THREAT_RANGE) != 0) {
            sb.append('R');
        }
        if ((mask & InfernoThreatModel.THREAT_MAGIC) != 0) {
            sb.append("Ma");
        }
        if ((mask & InfernoThreatModel.THREAT_UNKNOWN) != 0) {
            sb.append('?');
        }
        return sb.toString();
    }

    private void renderZukShield(Graphics2D g) {
        NPC glyph = plugin.getZukShield();
        if (glyph == null) {
            return;
        }
        for (WorldPoint tile : InfernoThreatModel.zukShieldCover(glyph.getWorldLocation())) {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), tile);
            Polygon poly = lp == null ? null : Perspective.getCanvasTilePoly(client, lp);
            if (poly != null) {
                OverlayUtil.renderPolygon(g, poly, SAFE, new Color(0, 200, 0, 50), new BasicStroke(1));
            }
        }
    }

    private void renderKillOrder(Graphics2D g) {
        List<InfernoNpc> order = plugin.getKillOrder();
        for (int i = 0; i < order.size(); i++) {
            NPC npc = order.get(i).getNpc();
            if (i == 0) {
                Shape hull = npc.getConvexHull();
                if (hull != null) {
                    OverlayUtil.renderPolygon(g, hull, TARGET, new Color(0, 255, 255, 30), new BasicStroke(2));
                }
            }
            Point p = npc.getCanvasTextLocation(g, "#" + (i + 1), npc.getLogicalHeight() + 40);
            if (p != null) {
                OverlayUtil.renderTextLocation(g, p, "#" + (i + 1), i == 0 ? TARGET : Color.WHITE);
            }
        }
    }

    private void renderTimers(Graphics2D g) {
        int now = plugin.nowTick();
        for (InfernoNpc n : plugin.getMonsters()) {
            String text;
            if (n.isBurrowed()) {
                text = "dig";
            } else {
                int t = n.ticksUntilAttack(now);
                if (t < 0) {
                    continue;
                }
                text = Integer.toString(t);
            }
            Point p = n.getNpc().getCanvasTextLocation(g, text, 0);
            if (p != null) {
                OverlayUtil.renderTextLocation(g, p, text, styleColor(n.getLastStyle()));
            }
        }
    }

    static Color styleColor(InfernoNpcType.Style style) {
        switch (style) {
            case MELEE:
                return new Color(255, 80, 80);
            case RANGE:
                return new Color(80, 255, 80);
            case MAGIC:
                return new Color(80, 160, 255);
            default:
                return Color.WHITE;
        }
    }
}
