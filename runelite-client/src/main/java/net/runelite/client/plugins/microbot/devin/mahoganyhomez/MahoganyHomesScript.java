package net.runelite.client.plugins.microbot.devin.mahoganyhomez;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.ItemID;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.shortestpath.ShortestPathPlugin;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.coords.Rs2WorldPoint;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class MahoganyHomesScript extends Script {

    @Inject
    MahoganyHomesPlugin plugin;

    // Whether we've already read the sack's plank count this session. Prevents the "Check" spam:
    // once we've attempted it, we never click the sack in the field again regardless of the result.
    private boolean plankSackChecked = false;

    public boolean run(MahoganyHomesConfig config) {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) return;

                // No active contract -> go acquire one.
                if (plugin.getCurrentHome() == null) {
                    getNewContract();
                    return;
                }

                // Read the sack's plank count exactly once, so material decisions below are correct.
                // Guarded by plankSackChecked so it can never re-fire tick after tick (the old spam):
                // if the count fails to sync we still move on and let the bank Fill establish it.
                if (config.usePlankSack() && !plankSackChecked && plugin.getPlankCount() == -1
                        && !Rs2Bank.isOpen() && Rs2Inventory.contains(ItemID.PLANK_SACK)) {
                    checkPlankSack();
                    return;
                }

                // Short on materials -> restock at the bank (walks out of the house if needed).
                if (isMissingItems()) {
                    bank();
                    return;
                }

                // Not at the contract house yet -> walk there.
                if (plugin.distanceBetween(plugin.getCurrentHome().getArea(), Rs2Player.getWorldLocation()) > 0) {
                    walkToHome();
                    return;
                }

                // At the house: hand in if done, otherwise fix the next hotspot.
                if (Hotspot.isEverythingFixed()) {
                    finish();
                } else {
                    fix();
                }
            } catch (Exception ex) {
                Microbot.log("MahoganyHomes error: " + ex.getMessage());
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    // Every tracked object whose hotspot still needs attention (repair/remove/build).
    // Derived straight from the live varbit state so we never target an already-fixed piece.
    private List<GameObject> getFixableObjects() {
        return plugin.getObjectsToMark().stream()
                .filter(Objects::nonNull)
                .filter(o -> {
                    Hotspot hotspot = Hotspot.getByObjectId(o.getId());
                    return hotspot != null && !hotspot.isFixed();
                })
                .collect(Collectors.toList());
    }

    private void log(String message) {
        if (plugin.getConfig().logMessages()) {
            Microbot.log(message);
        }
    }

    // Tasks section

    private void checkPlankSack() {
        // Mark as done up front: even if the click or the count parse fails, we must not loop on it.
        plankSackChecked = true;

        Rs2ItemModel plankSack = Rs2Inventory.get(ItemID.PLANK_SACK);
        if (plankSack == null) {
            return;
        }
        Rs2Inventory.interact(plankSack, "Check");
        // Wait for the plugin's chat parser to report the count rather than a blind fixed sleep.
        sleepUntil(() -> plugin.getPlankCount() != -1, 2500);
    }

    private int planksInPlankSack() {
        if (plugin.getPlankCount() == -1) {
            return 0;
        }
        return plugin.getPlankCount();
    }

    private void fix() {
        List<GameObject> fixable = getFixableObjects();
        MahoganyHomesOverlay.setFixableObjects(fixable);
        if (fixable.isEmpty()) {
            return;
        }

        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        int playerPlane = playerLocation.getPlane();

        // Prefer the nearest broken object on our current floor. We interact with the concrete
        // tracked instance so we always act on the object we picked (not "nearest object of that id",
        // which is what made the old script thrash between floors/instances).
        GameObject target = fixable.stream()
                .filter(o -> o.getWorldLocation().getPlane() == playerPlane)
                .min(Comparator.comparingInt(o -> o.getWorldLocation().distanceTo(playerLocation)))
                .orElse(null);

        // Everything left to fix is on another floor -> take the ladder.
        if (target == null) {
            tryToUseLadder();
            return;
        }

        Hotspot hotspot = Hotspot.getByObjectId(target.getId());
        if (hotspot == null) {
            return;
        }
        String action = hotspot.getRequiredAction();
        if (action.equals("Unknown")) {
            return;
        }

        interactWithObject(target, hotspot, action);
    }

    private void interactWithObject(GameObject object, Hotspot hotspot, String action) {
        final int varbBefore = Microbot.getVarbitValue(hotspot.getVarb());

        // Rs2GameObject.interact walks to the object (and the game opens interior doors on the way)
        // when it's out of reach, and fires the real menu action when it's close. It returns false
        // while still walking, so we simply try again next tick instead of running door/ladder
        // heuristics -- that fragile pathing is what made the old script run in circles.
        if (!Rs2GameObject.interact(object, action)) {
            return;
        }

        Rs2Player.waitForWalking();

        // A "Build" hotspot can open the furniture-creation interface; confirm the single build option.
        if (sleepUntil(() -> Rs2Widget.isWidgetVisible(InterfaceID.PohFurnitureCreation.FRAME), 1200)) {
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        }

        // Wait for the hotspot state to actually change (repair/remove/build applied) rather than
        // blindly re-clicking. Remove flips 3->4 (now needs Build), handled on the next pass.
        sleepUntil(() -> Microbot.getVarbitValue(hotspot.getVarb()) != varbBefore, 6000);
        sleep(300, 600);
    }

    private void tryToUseLadder() {
        int plane = Rs2Player.getWorldLocation().getPlane();
        Integer[] ladderIds = plugin.getCurrentHome().getLadders();
        if (ladderIds == null || ladderIds.length == 0) {
            // No mapped ladders for this home; fall back to the walker.
            Rs2Walker.walkTo(plugin.getCurrentHome().getLocation(), 3);
            return;
        }
        var closestLadder = Microbot.getRs2TileObjectCache().query()
                .withIds(Arrays.stream(ladderIds).mapToInt(Integer::intValue).toArray())
                .nearest();
        if (closestLadder != null && closestLadder.click()) {
            sleepUntil(() -> Rs2Player.getWorldLocation().getPlane() != plane, 5000);
            sleep(200, 600);
        }
    }

    // Finish by talking to the NPC
    private void finish() {
        if (plugin.getCurrentHome() == null
                || !plugin.getCurrentHome().isInside(Rs2Player.getWorldLocation())
                || !Hotspot.isEverythingFixed()) {
            return;
        }

        var npc = Microbot.getRs2NpcCache().query().withId(plugin.getCurrentHome().getNpcId()).nearest();
        if (npc == null && Rs2Player.getWorldLocation().getPlane() > 0) {
            log("On the wrong floor to hand in, taking a ladder down.");
            int playerPlane = Rs2Player.getWorldLocation().getPlane();
            var ladders = Microbot.getRs2TileObjectCache().query()
                    .withIds(Arrays.stream(plugin.getCurrentHome().getLadders()).mapToInt(Integer::intValue).toArray())
                    .where(obj -> obj.getWorldLocation().getPlane() == playerPlane)
                    .toList();
            var closestLadder = ladders.stream()
                    .min(Comparator.comparingInt(obj -> obj.getWorldLocation().distanceTo(Rs2Player.getWorldLocation())))
                    .orElse(null);
            if (closestLadder != null && closestLadder.click()) {
                sleepUntil(() -> Rs2Player.getWorldLocation().getPlane() < playerPlane, 5000);
            }
            return;
        }

        if (npc == null) {
            return;
        }

        Rs2WorldPoint npcLocation = new Rs2WorldPoint(npc.getWorldLocation());
        if (npcLocation.distanceToPath(Rs2Player.getWorldLocation()) < 20) {
            if (npc.click("Talk-to")) {
                log("Handing in contract to " + plugin.getCurrentHome().getName());
                sleepUntil(Rs2Dialogue::hasContinue, 10000);
                if (Rs2Dialogue.hasDialogueText("Please excuse me, I'm rather busy.")) {
                    plugin.setCurrentHome(null);
                }
                sleepUntil(() -> !Rs2Dialogue.isInDialogue(), Rs2Dialogue::clickContinue, 6000, 300);
                sleep(600, 1200);
            }
        } else {
            log("NPC too far, walking to hand-in.");
            Rs2Walker.walkTo(npc.getWorldLocation());
            sleep(1200, 2200);
        }
    }

    // Get new contract
    private void getNewContract() {
        if (plugin.getCurrentHome() != null) {
            return;
        }

        if (plugin.getConfig().useNpcContact()) {
            if (Rs2Magic.npcContact("amy")) {
                handleContractDialogue();
            }
            return;
        }

        WorldPoint contractLocation = getClosestContractLocation();
        if (contractLocation != null && contractLocation.distanceTo2D(Rs2Player.getWorldLocation()) > 10) {
            log("Walking to contract NPC");
            Rs2Walker.walkWithState(contractLocation, 5);
            return;
        }

        log("Getting new contract");
        var npc = Microbot.getRs2NpcCache().query().withNames("Amy", "Marlo", "Ellie", "Angelo").nearestOnClientThread();
        if (npc == null) {
            log("No contract NPC found, waiting before retry");
            sleep(2000, 3000);
            return;
        }
        if (npc.click("Contract")) {
            handleContractDialogue();
        }
    }

    public void handleContractDialogue() {
        if (!sleepUntil(Rs2Dialogue::hasSelectAnOption, Rs2Dialogue::clickContinue, 5000, 300)) {
            log("No dialogue options available, returning early");
            return;
        }
        Rs2Dialogue.keyPressForDialogueOption(plugin.getConfig().currentTier().getPlankSelection().getChatOption());
        sleepUntil(Rs2Dialogue::hasContinue, 5000);
        sleep(400, 800);
        sleepUntil(() -> !Rs2Dialogue.isInDialogue(), Rs2Dialogue::clickContinue, 6000, 300);
        sleep(1200, 2200);
    }

    // Bank/restock. Called only when we are missing materials; walks to the nearest bank first.
    private void bank() {
        Home currentHome = plugin.getCurrentHome();
        if (currentHome == null) {
            return;
        }

        ShortestPathPlugin.getPathfinderConfig().setIgnoreTeleportAndItems(true);
        BankLocation bankLocation = Rs2Bank.getNearestBank(currentHome.getLocation());
        ShortestPathPlugin.getPathfinderConfig().setIgnoreTeleportAndItems(false);

        if (!Rs2Bank.walkToBank(bankLocation)) {
            return;
        }
        if (!Rs2Bank.openBank()) {
            return;
        }
        sleepUntil(Rs2Bank::isOpen);

        final int plankId = plugin.getConfig().currentTier().getPlankSelection().getPlankId();

        // Out of supplies in the bank -> nothing more we can do.
        if (Rs2Bank.count(plankId) <= 28 || Rs2Bank.count(ItemID.STEEL_BAR) <= 4) {
            Microbot.log("Out of planks or steel bars in the bank, stopping.");
            Rs2Bank.closeBank();
            Microbot.stopPlugin(plugin);
            return;
        }

        if (plugin.getConfig().usePlankSack()) {
            // Deposit spare loose planks so the sack fill uses the correct type; keep steel bars.
            if (Rs2Inventory.isFull() && !Rs2Inventory.contains(ItemID.STEEL_BAR)) {
                Rs2Bank.depositAll(plankId);
                Rs2Inventory.waitForInventoryChanges(5000);
            }
            if (steelBarsInInventory() < steelBarsNeeded()) {
                Rs2Bank.withdrawX(ItemID.STEEL_BAR, steelBarsNeeded() - steelBarsInInventory());
                Rs2Inventory.waitForInventoryChanges(5000);
            }

            // Fill the plank sack to 28.
            Global.sleepUntil(() -> planksInPlankSack() == 28, () -> {
                Rs2Bank.withdrawAll(plankId);
                Rs2Inventory.waitForInventoryChanges(1000);
                sleep(Rs2Random.randomGaussian(800, 200));
                Rs2ItemModel plankSack = Rs2Inventory.get(ItemID.PLANK_SACK);
                if (plankSack != null) {
                    NewMenuEntry plankSackEntry = new NewMenuEntry();
                    plankSackEntry.setOption("Use");
                    plankSackEntry.setTarget("<col=ff9040>Plank sack</col>");
                    plankSackEntry.setIdentifier(9);
                    plankSackEntry.setType(MenuAction.CC_OP);
                    plankSackEntry.setParam0(plankSack.getSlot());
                    plankSackEntry.setParam1(983043);
                    plankSackEntry.setItemId(plankSack.getId());
                    plankSackEntry.setWorldViewId(-1);
                    plankSackEntry.setForceLeftClick(false);
                    plankSackEntry.setDeprioritized(false);
                    Microbot.doInvoke(plankSackEntry, Rs2Inventory.itemBounds(plankSack));
                    Rs2Inventory.waitForInventoryChanges(1000);
                }
            }, 20000, 1000);

            // Top up the inventory with loose planks for the rest of the house.
            if (Rs2Inventory.emptySlotCount() > 0) {
                Rs2Bank.withdrawAll(plankId);
                Rs2Inventory.waitForInventoryChanges(5000);
            }
        } else {
            // Withdraw steel bars first if needed.
            if (steelBarsNeeded() > steelBarsInInventory()) {
                Rs2Bank.withdrawX(ItemID.STEEL_BAR, steelBarsNeeded() - steelBarsInInventory());
                Rs2Inventory.waitForInventoryChanges(5000);
            }

            if (planksNeeded() - planksInInventory() > 0) {
                Rs2Bank.withdrawAll(plankId);
                Rs2Inventory.waitForInventoryChanges(5000);
            }
        }

        Rs2Bank.closeBank();
    }

    // Walk to current home
    private void walkToHome() {
        Home currentHome = plugin.getCurrentHome();
        if (currentHome != null
                && plugin.distanceBetween(currentHome.getArea(), Rs2Player.getWorldLocation()) > 0) {
            Rs2Walker.walkWithState(currentHome.getLocation(), 3);
        }
    }

    private boolean isMissingItems() {
        return (planksInInventory() + planksInPlankSack()) < planksNeeded()
                || steelBarsInInventory() < steelBarsNeeded();
    }

    private int planksNeeded() {
        return plugin.getCurrentHome().getRequiredPlanks(plugin.getContractTier());
    }

    private int steelBarsNeeded() {
        return plugin.getCurrentHome().getRequiredSteelBars(plugin.getContractTier());
    }

    private int planksInInventory() {
        return Rs2Inventory.count(plugin.getConfig().currentTier().getPlankSelection().getPlankId());
    }

    private int steelBarsInInventory() {
        return Rs2Inventory.count(ItemID.STEEL_BAR);
    }

    // Get closest contract location
    private WorldPoint getClosestContractLocation() {
        List<WorldPoint> contractLocations = new ArrayList<>();
        contractLocations.add(ContractLocation.MAHOGANY_HOMES_ARDOUGNE.getLocation());
        contractLocations.add(ContractLocation.MAHOGANY_HOMES_FALADOR.getLocation());
        contractLocations.add(ContractLocation.MAHOGANY_HOMES_HOSIDIUS.getLocation());
        contractLocations.add(ContractLocation.MAHOGANY_HOMES_VARROCK.getLocation());

        return contractLocations.stream()
                .min(Comparator.comparingInt(wp -> wp.distanceTo2D(Rs2Player.getWorldLocation())))
                .orElse(null);
    }

    @Override
    public void shutdown() {
        plankSackChecked = false;
        super.shutdown();
    }
}
