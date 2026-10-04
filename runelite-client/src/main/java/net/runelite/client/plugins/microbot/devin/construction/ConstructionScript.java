package net.runelite.client.plugins.microbot.devin.construction;

import net.runelite.api.GameObject;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.util.concurrent.TimeUnit;

public class ConstructionScript extends Script {

    @Inject
    private ConstructionPlugin plugin;

    // How close (tiles) a Build/Remove object must be to count as "our" hotspot, so we never target
    // furniture elsewhere in the house.
    private static final int HOTSPOT_RANGE = 2;

    // True from the moment we send the servant to the bank until it returns with planks. Prevents
    // us from repeatedly talking to it (or into empty space) while it's away fetching.
    private boolean servantFetching = false;
    private int lastPlankCount = -1;
    private long fetchSentAt = 0;

    public boolean run(ConstructionConfig config) {
        servantFetching = false;
        lastPlankCount = -1;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) return;

                ConstructionMethod method = config.method();
                ConstructionServant servant = config.servant();
                int planks = Rs2Inventory.count(method.getPlankId());

                // The servant delivered (plank count rose since last tick, even a partial load) ->
                // free to send it again. Building only ever lowers planks, so a rise means a fetch.
                if (servantFetching && lastPlankCount >= 0 && planks > lastPlankCount) {
                    servantFetching = false;
                }
                lastPlankCount = planks;
                // Self-heal: flag stuck far longer than a 12-tick (7.2s) round trip -> clear & retry.
                if (servantFetching && System.currentTimeMillis() - fetchSentAt > 15000) {
                    servantFetching = false;
                }

                // Furniture-creation menu is open -> pick our furniture.
                if (isCreationMenuOpen()) {
                    selectFurniture(method);
                    return;
                }

                // NOTE: we deliberately do NOT touch an open servant dialogue here. After the servant
                // hands over planks it leaves a "here you go" dialogue up; closing it makes it wander
                // off, so we leave it open (it stays put next to us) and only re-engage it when we
                // actually want another fetch (see sendServant).

                // Built furniture on the hotspot -> remove it so we can rebuild. Doing this first
                // keeps the build/remove rhythm going even while the servant is away fetching.
                GameObject built = nearestWithAction("Remove");
                if (built != null) {
                    remove(built, method);
                    return;
                }

                // Send the servant once planks get low (~2 builds left) - NOT after every object.
                // That's early enough that its 7.2s trip finishes before we run dry, and it keeps
                // building the rest during the trip; if it gets back while we're still busy it stands
                // next to us holding the planks instead of roaming off, so we never chase it.
                if (servant != ConstructionServant.NONE && !servantFetching
                        && planks <= method.getPlanksPerBuild() * 2) {
                    Rs2NpcModel servantNpc = presentServant(servant);
                    if (servantNpc != null) {
                        sendServant(config, servantNpc);
                        return;
                    }
                    // Servant isn't in the scene yet. If we can't build either, just wait for it.
                    if (planks < method.getPlanksPerBuild()) {
                        Microbot.status = "Waiting for " + servant.getNpcName() + " to return";
                        return;
                    }
                }

                // Build if we have enough planks.
                if (planks >= method.getPlanksPerBuild()) {
                    GameObject hotspot = nearestWithAction("Build");
                    if (hotspot != null) {
                        build(hotspot, method);
                        return;
                    }
                    Microbot.status = "No Build hotspot nearby - stand at your build spot";
                    return;
                }

                // Out of planks with no way to restock.
                if (servant == ConstructionServant.NONE) {
                    Microbot.status = "Out of planks - stopping";
                    Microbot.log("Construction: out of planks and no servant configured.");
                    Microbot.stopPlugin(plugin);
                    return;
                }
                Microbot.status = servantFetching ? "Waiting for planks..." : "Out of planks";
            } catch (Exception ex) {
                Microbot.log("Construction error: " + ex.getMessage());
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    private Rs2NpcModel presentServant(ConstructionServant servant) {
        if (servant == ConstructionServant.NONE) {
            return null;
        }
        return Rs2Npc.getNpc(servant.getNpcName());
    }

    private GameObject nearestWithAction(String action) {
        return Rs2GameObject.getGameObject(o -> Rs2GameObject.hasAction(o, action), HOTSPOT_RANGE);
    }

    private boolean isCreationMenuOpen() {
        return Rs2Widget.isWidgetVisible(InterfaceID.PohFurnitureCreation.FRAME);
    }

    private void build(GameObject hotspot, ConstructionMethod method) {
        Microbot.status = "Building " + method.getDisplayName();
        int before = Rs2Inventory.count(method.getPlankId());
        if (!Rs2GameObject.interact(hotspot, "Build")) {
            return;
        }
        if (!sleepUntil(this::isCreationMenuOpen, 3000)) {
            return;
        }
        selectFurniture(method);

        // A successful build consumes planks; count it for XP tracking.
        if (sleepUntil(() -> Rs2Inventory.count(method.getPlankId()) < before, 3000)) {
            plugin.onBuildCompleted(method.getXpPerBuild());
        }
        sleep(200, 400);
    }

    private void selectFurniture(ConstructionMethod method) {
        if (!Rs2Widget.clickWidget(method.getBuildMenuName())) {
            // Fallback: most training hotspots put the tier we want on the "1" key.
            Rs2Keyboard.keyPress(KeyEvent.VK_1);
        }
        sleepUntil(() -> !isCreationMenuOpen(), 2000);
    }

    private void remove(GameObject built, ConstructionMethod method) {
        Microbot.status = "Removing " + method.getDisplayName();
        if (!Rs2GameObject.interact(built, "Remove")) {
            return;
        }
        // "Really remove it?" confirmation -> Yes (option 1).
        if (sleepUntil(Rs2Dialogue::hasSelectAnOption, 1500)) {
            Rs2Dialogue.keyPressForDialogueOption(1);
        }
        // Wait until the hotspot is empty again (Build available).
        sleepUntil(() -> nearestWithAction("Build") != null, 3000);
        sleep(150, 300);
    }

    private void sendServant(ConstructionConfig config, Rs2NpcModel servantNpc) {
        Microbot.status = "Sending " + config.servant().getNpcName() + " to fetch planks";
        // Always re-engage the servant with a fresh Talk-to. If a left-open "here you go" dialogue
        // is still up from the last hand-off, talking to it again brings the fetch options back.
        if (!Rs2Npc.interact(servantNpc, "Talk-to")) {
            return;
        }
        if (!sleepUntil(Rs2Dialogue::hasSelectAnOption, 3000)) {
            return;
        }
        if (requestFetch(config)) {
            servantFetching = true;
            fetchSentAt = System.currentTimeMillis();
        }
    }

    /**
     * Advance the servant conversation only as far as requesting a fetch, then stop - leaving the
     * remaining dialogue open on purpose so the servant doesn't wander off. Assumes it already knows
     * which item to fetch (do one manual fetch first) so we can pick the "repeat/again" option;
     * falls back to any plank/bank option, and pays the fee when demanded if auto-pay is enabled.
     * Returns true once a fetch has been requested.
     */
    private boolean requestFetch(ConstructionConfig config) {
        for (int i = 0; i < 6 && Rs2Dialogue.isInDialogue(); i++) {
            if (Rs2Dialogue.hasSelectAnOption()) {
                // Pay the fee first if the servant is demanding it, then keep going to the fetch.
                if (config.payServant() && (Rs2Dialogue.hasDialogueOption("pay")
                        || Rs2Dialogue.hasDialogueOption("money") || Rs2Dialogue.hasDialogueOption("here"))) {
                    if (Rs2Dialogue.clickOption("pay") || Rs2Dialogue.clickOption("money")
                            || Rs2Dialogue.clickOption("here")) {
                        sleep(600, 1000);
                        continue;
                    }
                }
                // Request the fetch, then STOP so the trailing dialogue stays open.
                if (Rs2Dialogue.clickOption("another") || Rs2Dialogue.clickOption("same")
                        || Rs2Dialogue.clickOption("more") || Rs2Dialogue.clickOption("plank")
                        || Rs2Dialogue.clickOption("bank")) {
                    sleep(300, 600);
                    return true;
                }
                Microbot.status = "Unrecognised servant dialogue";
                return false;
            } else if (Rs2Dialogue.hasContinue()) {
                Rs2Dialogue.clickContinue();
                sleep(300, 600);
            } else {
                break;
            }
        }
        return false;
    }

    @Override
    public void shutdown() {
        servantFetching = false;
        super.shutdown();
    }
}
