package net.runelite.client.plugins.microbot.trent.chompy

import com.google.inject.Provides
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.coords.WorldPoint
import net.runelite.api.gameval.ItemID
import net.runelite.api.gameval.NpcID
import net.runelite.api.gameval.ObjectID
import net.runelite.client.config.Config
import net.runelite.client.config.ConfigGroup
import net.runelite.client.config.ConfigItem
import net.runelite.client.config.ConfigManager
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Rs2Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

@ConfigGroup(ChompyHunterConfig.GROUP)
interface ChompyHunterConfig : Config {
    companion object { const val GROUP = "trentChompyHunter" }

    @ConfigItem(keyName = "targetGroundToads", name = "Target ground toads",
        description = "Keep at least this many bloated toads on the ground as bait. Default 6.",
        position = 0)
    fun targetGroundToads(): Int = 6

    @ConfigItem(keyName = "maxInventoryBloatedToads", name = "Max inv bloated toads",
        description = "Hard cap of bloated toads to inflate into inventory. Game cap is 3.",
        position = 1)
    fun maxInventoryBloatedToads(): Int = 3

    @ConfigItem(keyName = "scanRadius", name = "Scan radius (tiles)",
        description = "Tile radius around the player for finding chompies, toads, and geyser.",
        position = 2)
    fun scanRadius(): Int = 12

    @ConfigItem(keyName = "directClickRefill", name = "Direct-click refill",
        description = "Left-click the geyser to auto-fill, instead of using a bellow on it. Fewer clicks per refill but the game picks which bellow gets filled.",
        position = 3)
    fun directClickRefill(): Boolean = false

    @ConfigItem(keyName = "inflateAction", name = "Inflate action (toad NPC)",
        description = "Menu action used to inflate a swamp toad NPC with a filled bellow. Default 'Inflate'.",
        position = 4)
    fun inflateAction(): String = "Inflate"

    @ConfigItem(keyName = "geyserAction", name = "Geyser action (swamp bubbles)",
        description = "Menu action used to suck gas from the swamp bubbles into a bellow. Default 'Suck'.",
        position = 5)
    fun geyserAction(): String = "Suck"

    @ConfigItem(keyName = "pluckChompies", name = "Pluck dead chompies",
        description = "After killing a chompy, pluck the corpse for feathers. Higher priority than dropping toads, lower than killing.",
        position = 6)
    fun pluckChompies(): Boolean = true

    @ConfigItem(keyName = "pluckAction", name = "Pluck action",
        description = "Menu action on the dead chompy corpse. Default 'Pluck'.",
        position = 7)
    fun pluckAction(): String = "Pluck"

    @ConfigItem(keyName = "debug", name = "Debug logging",
        description = "Log state transitions and skipped chompies. Default off.",
        position = 8)
    fun debug(): Boolean = false
}

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Chompy Hunter",
    description = "Big Chompy Bird Hunting: inflates toads, drops them as bait, kills chompies",
    tags = ["ranged", "hunter", "chompy", "fletching"],
    enabledByDefault = false,
)
class ChompyHunter : Plugin() {
    @Inject private lateinit var client: Client
    @Inject private lateinit var config: ChompyHunterConfig

    @Provides
    fun provideConfig(configManager: ConfigManager): ChompyHunterConfig =
        configManager.getConfig(ChompyHunterConfig::class.java)

    @Volatile private var running = false
    @Volatile private var script: ChompyHunterScript? = null

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        val fresh = ChompyHunterScript()
        fresh.config = config
        script = fresh
        running = true
        Microbot.log("[ChompyHunter] starting (loop will idle until logged in)")
        GlobalScope.launch { run(fresh) }
    }

    override fun shutDown() {
        running = false
        script?.stop()
        Microbot.log("[ChompyHunter] stopped")
    }

    private fun run(s: ChompyHunterScript) {
        while (running) {
            try { s.loop(client) }
            catch (ie: InterruptedException) { Thread.currentThread().interrupt(); running = false; return }
            catch (t: Throwable) { Microbot.log("[ChompyHunter] loop error: ${t.message}"); Global.sleep(Rs2Random.between(600, 1200)) }
        }
    }
}

class ChompyHunterScript : StateMachineScript() {
    @Volatile var config: ChompyHunterConfig? = null
    override fun getStartState(): State = Root()
}

private val SWAMP_GAS_OBJECT_IDS: IntArray = intArrayOf(
    ObjectID.SWAMPBUBBLES_SWAMP, ObjectID.SWAMPBUBBLES,
)
private val FILLED_BELLOW_IDS: IntArray = intArrayOf(
    ItemID.FILLED_OGRE_BELLOW1, ItemID.FILLED_OGRE_BELLOW2, ItemID.FILLED_OGRE_BELLOW3,
)
private const val CHOMPY_CLICK_COOLDOWN_MS: Long = 1800L
private const val ATTACK_START_TIMEOUT_MS: Int = 2000
private const val INV_CHANGE_TIMEOUT_MS: Int = 3000

private class Root : State() {
    private val chompyClickedAt: MutableMap<Int, Long> = HashMap()
    @Volatile private var loggedFirstLoop = false

    override fun checkNext(client: Client): State? = null

    override fun loop(client: Client, script: StateMachineScript) {
        if (!loggedFirstLoop) { Microbot.log("[ChompyHunter] loop started"); loggedFirstLoop = true }
        if (Microbot.pauseAllScripts.get()) { Global.sleep(Rs2Random.between(800, 1600)); return }
        val cfg = (script as? ChompyHunterScript)?.config
            ?: run {
                Microbot.log("[ChompyHunter] config not yet bound, idling")
                Global.sleep(Rs2Random.between(400, 800)); return
            }
        if (client.localPlayer == null) {
            Global.sleep(Rs2Random.between(600, 1200)); return
        }
        val radius = cfg.scanRadius().coerceAtLeast(4)
        val maxInvToads = cfg.maxInventoryBloatedToads().coerceIn(1, 3)
        val targetGroundToads = cfg.targetGroundToads().coerceAtLeast(1)
        val debug = cfg.debug()

        if (tryKillChompy(radius, debug)) return
        if (cfg.pluckChompies() && tryPluckChompy(radius, cfg.pluckAction(), debug)) return
        if (tryDropToad(radius, targetGroundToads, debug)) return
        if (tryInflateToad(radius, maxInvToads, cfg.inflateAction(), debug)) return
        if (tryRefillBellow(radius, cfg.directClickRefill(), cfg.geyserAction(), debug)) return

        if (debug) Microbot.log("[ChompyHunter] idle")
        Global.sleep(Rs2Random.between(400, 800))
    }

    private fun tryKillChompy(radius: Int, debug: Boolean): Boolean {
        if (Rs2Player.isInteracting() || Rs2Player.isAnimating()) {
            Global.sleep(Rs2Random.between(200, 400)); return true
        }
        val now = System.currentTimeMillis()
        val groundToadNpcs = Rs2NpcQueryable().withId(NpcID.BLOATED_TOAD).within(radius + 4).toList()

        val candidates = Rs2NpcQueryable()
            .withId(NpcID.CHOMPYBIRD)
            .within(radius)
            .where { chompy ->
                if (chompy.isDead) return@where false
                if (chompy.healthRatio == 0) return@where false
                val lastClick = chompyClickedAt[chompy.index]
                if (lastClick != null && (now - lastClick) < CHOMPY_CLICK_COOLDOWN_MS) return@where false
                val target = chompy.interacting
                if (target != null) {
                    val name = target.name
                    if (name != null && name.equals("Bloated toad", ignoreCase = true)) {
                        if (debug) Microbot.log("[ChompyHunter] skip chompy idx=${chompy.index}: eating toad")
                        return@where false
                    }
                }
                if (chompy.animation != -1 && chompy.animation != 0) {
                    val cLoc = chompy.worldLocation ?: return@where true
                    val adjacent = groundToadNpcs.any { toad ->
                        val tLoc = toad.worldLocation ?: return@any false
                        Math.abs(tLoc.x - cLoc.x) <= 1 && Math.abs(tLoc.y - cLoc.y) <= 1 && tLoc.plane == cLoc.plane
                    }
                    if (adjacent) {
                        if (debug) Microbot.log("[ChompyHunter] skip chompy idx=${chompy.index}: animating adjacent to toad")
                        return@where false
                    }
                }
                true
            }
            .toList()

        if (candidates.isEmpty()) return false
        val playerLoc = Rs2Player.getWorldLocation() ?: return false
        val target = pickNearestByPath(playerLoc, candidates) { it.worldLocation } ?: return false

        if (debug) Microbot.log("[ChompyHunter] KILL_CHOMPY idx=${target.index}")
        if (target.click("Attack")) {
            chompyClickedAt[target.index] = System.currentTimeMillis()
            if (chompyClickedAt.size > 32) {
                val cutoff = System.currentTimeMillis() - (CHOMPY_CLICK_COOLDOWN_MS * 4)
                chompyClickedAt.entries.removeIf { it.value < cutoff }
            }
            sleepUntil(checkEvery = 100, timeout = ATTACK_START_TIMEOUT_MS) {
                Rs2Player.isInteracting() || Rs2Player.isAnimating()
            }
            return true
        }
        Global.sleep(Rs2Random.between(200, 400)); return true
    }

    private fun tryDropToad(radius: Int, targetGroundToads: Int, debug: Boolean): Boolean {
        val invBloatedToads = Rs2Inventory.count(ItemID.BLOATED_TOAD)
        if (invBloatedToads <= 0) return false
        val playerLoc = Rs2Player.getWorldLocation() ?: return false
        val groundToads = Rs2NpcQueryable().withId(NpcID.BLOATED_TOAD).within(radius).toList()
        val groundCount = groundToads.size
        if (groundCount >= targetGroundToads) return false
        val occupied: Set<WorldPoint> = groundToads.mapNotNull { it.worldLocation }.toHashSet()
        if (playerLoc in occupied) {
            val empty = findEmptyReachableTile(playerLoc, occupied)
            if (empty == null) {
                if (debug) Microbot.log("[ChompyHunter] DROP_TOAD no empty reachable tile in 3x3, skipping")
                return false
            }
            if (debug) Microbot.log("[ChompyHunter] DROP_TOAD step-aside to=$empty (current tile occupied)")
            Rs2Walker.walkFastCanvas(empty)
            sleepUntil(checkEvery = 100, timeout = 2000) { Rs2Player.getWorldLocation() == empty }
            return true
        }
        if (debug) Microbot.log("[ChompyHunter] DROP_TOAD ground=$groundCount target=$targetGroundToads inv=$invBloatedToads")
        if (Rs2Inventory.drop(ItemID.BLOATED_TOAD)) {
            sleepUntil(checkEvery = 100, timeout = INV_CHANGE_TIMEOUT_MS) {
                Rs2Inventory.count(ItemID.BLOATED_TOAD) < invBloatedToads
            }
            return true
        }
        Global.sleep(Rs2Random.between(200, 400)); return true
    }

    private fun findEmptyReachableTile(from: WorldPoint, occupied: Set<WorldPoint>): WorldPoint? {
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val tile = WorldPoint(from.x + dx, from.y + dy, from.plane)
            if (tile in occupied) continue
            if (Rs2Walker.getTotalTiles(from, tile) < Int.MAX_VALUE) return tile
        }
        return null
    }

    private fun tryInflateToad(radius: Int, maxInvBloatedToads: Int, action: String, debug: Boolean): Boolean {
        val invBloatedToads = Rs2Inventory.count(ItemID.BLOATED_TOAD)
        if (invBloatedToads >= maxInvBloatedToads) return false
        if (!Rs2Inventory.hasItem(*FILLED_BELLOW_IDS)) return false
        val playerLoc = Rs2Player.getWorldLocation() ?: return false
        val swampToads = Rs2NpcQueryable().withId(NpcID.TOAD).within(radius).toList()
        val swampToad = pickNearestByPath(playerLoc, swampToads) { it.worldLocation } ?: return false
        if (debug) Microbot.log("[ChompyHunter] INFLATE_TOAD action='$action' invToads=$invBloatedToads")
        if (swampToad.click(action)) {
            sleepUntil(checkEvery = 100, timeout = INV_CHANGE_TIMEOUT_MS) {
                Rs2Inventory.count(ItemID.BLOATED_TOAD) > invBloatedToads || attackableChompyAvailable(radius)
            }
            return true
        }
        Global.sleep(Rs2Random.between(200, 400)); return true
    }

    private fun tryPluckChompy(radius: Int, action: String, debug: Boolean): Boolean {
        val playerLoc = Rs2Player.getWorldLocation() ?: return false
        val corpses = Rs2NpcQueryable().withId(NpcID.CHOMPYBIRD_DEAD).within(radius).toList()
        if (corpses.isEmpty()) return false
        val target = pickNearestByPath(playerLoc, corpses) { it.worldLocation } ?: return false
        if (debug) Microbot.log("[ChompyHunter] PLUCK_CHOMPY idx=${target.index} action='$action'")
        if (target.click(action)) {
            sleepUntil(checkEvery = 150, timeout = ATTACK_START_TIMEOUT_MS) {
                Rs2Player.isInteracting() || Rs2Player.isAnimating()
            }
            return true
        }
        Global.sleep(Rs2Random.between(200, 400)); return true
    }

    private fun attackableChompyAvailable(radius: Int): Boolean {
        return Rs2NpcQueryable()
            .withId(NpcID.CHOMPYBIRD)
            .within(radius)
            .where { c ->
                if (c.isDead) return@where false
                if (c.healthRatio == 0) return@where false
                val target = c.interacting
                if (target?.name?.equals("Bloated toad", ignoreCase = true) == true) return@where false
                true
            }
            .first() != null
    }

    private fun <T> pickNearestByPath(playerLoc: net.runelite.api.coords.WorldPoint, items: List<T>, locOf: (T) -> net.runelite.api.coords.WorldPoint?): T? {
        if (items.isEmpty()) return null
        if (items.size == 1) return items[0]
        var best: T? = null
        var bestDist = Int.MAX_VALUE
        for (item in items) {
            val loc = locOf(item) ?: continue
            val d = Rs2Walker.getTotalTiles(playerLoc, loc)
            if (d < bestDist) { bestDist = d; best = item }
        }
        return best
    }

    private fun tryRefillBellow(radius: Int, directClick: Boolean, directAction: String, debug: Boolean): Boolean {
        val emptyCount = Rs2Inventory.count(ItemID.EMPTY_OGRE_BELLOWS)
        val partial1Count = Rs2Inventory.count(ItemID.FILLED_OGRE_BELLOW1)
        val partial2Count = Rs2Inventory.count(ItemID.FILLED_OGRE_BELLOW2)
        val refillableTotal = emptyCount + partial1Count + partial2Count
        if (refillableTotal <= 0) return false
        val geyser = Rs2TileObjectQueryable().withIds(*SWAMP_GAS_OBJECT_IDS).within(radius).nearest()
            ?: Rs2TileObjectQueryable()
                .where { obj ->
                    val n = obj.name ?: return@where false
                    n.equals("Swamp bubbles", ignoreCase = true) || n.equals("Gas bubble", ignoreCase = true)
                }
                .within(radius).nearest()
        if (geyser == null) return false

        if (directClick) {
            if (debug) Microbot.log("[ChompyHunter] REFILL_BELLOW direct action='$directAction' refillable=$refillableTotal obj=${geyser.id}")
            if (geyser.click(directAction)) {
                sleepUntil(checkEvery = 150, timeout = INV_CHANGE_TIMEOUT_MS) {
                    val newTotal = Rs2Inventory.count(ItemID.EMPTY_OGRE_BELLOWS) +
                        Rs2Inventory.count(ItemID.FILLED_OGRE_BELLOW1) +
                        Rs2Inventory.count(ItemID.FILLED_OGRE_BELLOW2)
                    newTotal < refillableTotal || attackableChompyAvailable(radius)
                }
                return true
            }
            Global.sleep(Rs2Random.between(200, 400)); return true
        }

        val bellowToRefill: Int = when {
            emptyCount > 0 -> ItemID.EMPTY_OGRE_BELLOWS
            partial1Count > 0 -> ItemID.FILLED_OGRE_BELLOW1
            else -> ItemID.FILLED_OGRE_BELLOW2
        }
        val beforeCount = Rs2Inventory.count(bellowToRefill)
        if (debug) Microbot.log("[ChompyHunter] REFILL_BELLOW use bellow=$bellowToRefill count=$beforeCount obj=${geyser.id}")
        if (Rs2Inventory.use(bellowToRefill)) {
            Global.sleep(Rs2Random.between(80, 160))
            if (geyser.click("Use")) {
                sleepUntil(checkEvery = 150, timeout = INV_CHANGE_TIMEOUT_MS) {
                    Rs2Inventory.count(bellowToRefill) < beforeCount || attackableChompyAvailable(radius)
                }
                return true
            }
        }
        Global.sleep(Rs2Random.between(200, 400)); return true
    }
}
