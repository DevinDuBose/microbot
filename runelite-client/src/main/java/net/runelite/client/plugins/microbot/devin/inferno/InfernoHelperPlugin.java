package net.runelite.client.plugins.microbot.devin.inferno;

import com.google.inject.Provides;
import lombok.Getter;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Information-only Inferno helper: safespot shading, kill order, attack
 * timers, wave tracking and Zuk shield cover. It never clicks or moves —
 * pair it with the PVM Prayer Flicker for prayers.
 *
 * Game state is read on the client thread in {@link #onGameTick}; the tile
 * threat map is then computed from an {@link InfernoGrid} snapshot on a
 * single background thread (it can take tens of milliseconds), and results
 * are published as snapshots the overlays read while rendering.
 */
@PluginDescriptor(
        name = PluginDescriptor.Devin + "Inferno Helper",
        description = "Inferno safespots, attack priority, attack timers and wave info (overlay only)",
        tags = {"inferno", "zuk", "safespot", "pvm", "overlay", "devin"},
        authors = {"Devin"},
        enabledByDefault = false
)
public class InfernoHelperPlugin extends Plugin {

    static final int INFERNO_REGION = 9043;
    static final int TRIPLE_JAD_WAVE = 68;
    static final int ZUK_WAVE = 69;

    private static final Pattern WAVE_PATTERN = Pattern.compile("Wave: (\\d+)");

    @Inject
    private Client client;

    @Inject
    @Getter
    private InfernoHelperConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private InfernoSceneOverlay sceneOverlay;

    @Inject
    private InfernoInfoOverlay infoOverlay;

    private final Map<NPC, InfernoNpc> tracked = new HashMap<>();

    @Getter
    private volatile boolean inInferno;

    @Getter
    private volatile int wave = -1;

    /** Zuk enrages (attack speed 10 → 7) when his healers spawn. */
    @Getter
    private volatile boolean zukEnraged;

    /** Tile → threat bitmask ({@link InfernoThreatModel}) around the player. */
    @Getter
    private volatile Map<WorldPoint, Integer> threatMap = Collections.emptyMap();

    /** Monsters in kill order; index 0 is the one to attack. */
    @Getter
    private volatile List<InfernoNpc> killOrder = Collections.emptyList();

    /** All tracked monsters. */
    @Getter
    private volatile List<InfernoNpc> monsters = Collections.emptyList();

    /** The next attacking monster (soonest, ties to the highest protect priority), or null. */
    @Getter
    private volatile InfernoNpc nextAttacker;

    @Getter
    private volatile NPC zukShield;

    private ExecutorService threatExecutor;
    private final AtomicBoolean threatJobRunning = new AtomicBoolean();

    @Provides
    InfernoHelperConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(InfernoHelperConfig.class);
    }

    @Override
    protected void startUp() {
        threatExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "inferno-helper-threats");
            t.setDaemon(true);
            return t;
        });
        overlayManager.add(sceneOverlay);
        overlayManager.add(infoOverlay);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(sceneOverlay);
        overlayManager.remove(infoOverlay);
        if (threatExecutor != null) {
            threatExecutor.shutdownNow();
            threatExecutor = null;
        }
        threatJobRunning.set(false);
        reset();
    }

    private void reset() {
        tracked.clear();
        threatMap = Collections.emptyMap();
        killOrder = Collections.emptyList();
        monsters = Collections.emptyList();
        nextAttacker = null;
        zukShield = null;
        zukEnraged = false;
        wave = -1;
        inInferno = false;
    }

    int nowTick() {
        return client.getTickCount();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        switch (event.getGameState()) {
            case LOGIN_SCREEN:
            case HOPPING:
                reset();
                break;
            case LOADING:
                // Spawn events re-fire after a scene load.
                tracked.clear();
                zukShield = null;
                break;
            default:
                break;
        }
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        track(event.getNpc());
    }

    private void track(NPC npc) {
        if (npc.getId() == InfernoNpcType.ZUK_SHIELD_ID) {
            zukShield = npc;
            return;
        }
        InfernoNpcType type = InfernoNpcType.forNpcId(npc.getId());
        if (type == null) {
            return;
        }
        if (type == InfernoNpcType.ZUK) {
            zukEnraged = false;
        } else if (type == InfernoNpcType.ZUK_HEALER) {
            zukEnraged = true;
        }
        tracked.putIfAbsent(npc, new InfernoNpc(npc, type));
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        NPC npc = event.getNpc();
        if (npc == zukShield) {
            zukShield = null;
        }
        tracked.remove(npc);
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        Actor actor = event.getActor();
        if (!(actor instanceof NPC)) {
            return;
        }
        InfernoNpc n = tracked.get(actor);
        if (n != null) {
            n.onAnimation(actor.getAnimation(), client.getTickCount(), attackSpeed(n.getType()));
        }
    }

    int attackSpeed(InfernoNpcType type) {
        if (type == InfernoNpcType.ZUK && zukEnraged) {
            return InfernoNpcType.ZUK_ENRAGED_ATTACK_SPEED;
        }
        if (type == InfernoNpcType.JAD && wave == TRIPLE_JAD_WAVE) {
            return InfernoNpcType.TRIPLE_JAD_ATTACK_SPEED;
        }
        return type.getAttackSpeed();
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event) {
        Projectile p = event.getProjectile();
        InfernoNpcType.Style style = InfernoNpcType.STYLE_PROJECTILES.get(p.getId());
        if (style == null || !(p.getSourceActor() instanceof NPC)) {
            return;
        }
        InfernoNpc n = tracked.get(p.getSourceActor());
        if (n != null) {
            n.onStyleProjectile(style);
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE || !inInferno) {
            return;
        }
        Matcher m = WAVE_PATTERN.matcher(Text.removeTags(event.getMessage()));
        if (m.find()) {
            wave = Integer.parseInt(m.group(1));
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        Player local = client.getLocalPlayer();
        WorldView wv = client.getTopLevelWorldView();
        if (local == null || wv == null) {
            return;
        }
        if (!isInInfernoRegion(wv)) {
            if (inInferno) {
                reset();
            }
            return;
        }
        if (!inInferno) {
            inInferno = true;
            // Enabled mid-run: pick up monsters that spawned before we were listening.
            for (NPC npc : wv.npcs()) {
                track(npc);
            }
        }

        int now = client.getTickCount();
        WorldPoint player = local.getWorldLocation();

        List<InfernoNpc> live = new ArrayList<>();
        List<InfernoThreatModel.Monster> model = new ArrayList<>();
        for (InfernoNpc n : tracked.values()) {
            if (n.getNpc().isDead()) {
                continue;
            }
            live.add(n);
            model.add(new InfernoThreatModel.Monster(n.getType(), n.getNpc().getWorldArea()));
        }
        monsters = Collections.unmodifiableList(live);

        killOrder = InfernoThreatModel.killOrder(live, InfernoNpc::getType,
                n -> n.getNpc().getWorldArea().distanceTo(player), config.nibblersFirst());

        nextAttacker = soonestAttacker(live, now);

        if (config.safespotMode() == InfernoHelperConfig.SafespotMode.OFF || wave == ZUK_WAVE) {
            threatMap = Collections.emptyMap();
        } else {
            submitThreatJob(wv, model, player);
        }
    }

    /** Snapshots what the threat map needs (client thread) and computes it in the background. */
    private void submitThreatJob(WorldView wv, List<InfernoThreatModel.Monster> model, WorldPoint player) {
        ExecutorService executor = threatExecutor;
        if (executor == null || !threatJobRunning.compareAndSet(false, true)) {
            // Previous tick's job still running: skip rather than queue up stale work.
            return;
        }
        InfernoGrid grid = InfernoGrid.snapshot(wv);
        if (grid == null) {
            threatJobRunning.set(false);
            return;
        }
        Set<WorldPoint> obstacles = new HashSet<>();
        for (NPC npc : wv.npcs()) {
            obstacles.addAll(npc.getWorldArea().toWorldPointList());
        }
        int radius = config.safespotRadius();
        boolean predict = config.predictMovement();
        try {
            executor.execute(() -> {
                try {
                    Map<WorldPoint, Integer> result = InfernoThreatModel.threatMap(grid, model, obstacles, player, radius, predict);
                    if (inInferno) {
                        threatMap = Collections.unmodifiableMap(result);
                    }
                } finally {
                    threatJobRunning.set(false);
                }
            });
        } catch (RuntimeException e) {
            threatJobRunning.set(false);
        }
    }

    private InfernoNpc soonestAttacker(List<InfernoNpc> live, int now) {
        InfernoNpc best = null;
        int bestTicks = Integer.MAX_VALUE;
        for (InfernoNpc n : live) {
            if (n.getType().getProtectPriority() == 0) {
                continue;
            }
            int t = n.ticksUntilAttack(now);
            if (t < 0) {
                continue;
            }
            if (t < bestTicks || (t == bestTicks && n.getType().getProtectPriority() > best.getType().getProtectPriority())) {
                best = n;
                bestTicks = t;
            }
        }
        return best;
    }

    private boolean isInInfernoRegion(WorldView wv) {
        int[] regions = wv.getMapRegions();
        if (regions == null) {
            return false;
        }
        for (int r : regions) {
            if (r == INFERNO_REGION) {
                return true;
            }
        }
        return false;
    }
}
