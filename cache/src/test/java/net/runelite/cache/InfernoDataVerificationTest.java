package net.runelite.cache;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.SpotAnimDefinition;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.definitions.loaders.SpotAnimLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Verifies the Inferno data used by the Inferno Helper and PVM Prayer Flicker
 * plugins against a real OSRS game cache, so nothing has to be discovered
 * mid-run. Skipped unless INFERNO_CACHE_DIR points at a jagexcache directory
 * (e.g. ~/.runelite/jagexcache/oldschool/LIVE).
 *
 * Checks:
 * - every NPC id resolves to the expected name and tile size;
 * - every attack animation runs on the same skeleton (framemap) as the
 *   NPC's own idle animation, i.e. the animation can only belong to it;
 * - every projectile spotanim id exists;
 * - the pillar objects are solid and block projectiles (line of sight), so
 *   the client's collision map stops line of sight through them. They are
 *   1x1 objects spawned at runtime (absent from the static map, see
 *   dumpInfernoArena), not 3x3.
 */
public class InfernoDataVerificationTest
{
	private static final Object[][] NPCS = {
		// id, name, size
		{7691, "Jal-Nib", 1},
		{7692, "Jal-MejRah", 2},
		{7693, "Jal-Ak", 3},
		{7694, "Jal-AkRek-Mej", 1},
		{7695, "Jal-AkRek-Xil", 1},
		{7696, "Jal-AkRek-Ket", 1},
		{7697, "Jal-ImKot", 4},
		{7698, "Jal-Xil", 3},
		{7699, "Jal-Zek", 4},
		{7700, "JalTok-Jad", 5},
		{7701, "Yt-HurKot", 1},
		{7702, "Jal-Xil", 3},
		{7703, "Jal-Zek", 4},
		{7704, "JalTok-Jad", 5},
		{7705, "Yt-HurKot", 1},
		{7706, "TzKal-Zuk", 7},
		{7707, "Ancestral Glyph", 3},
		{7708, "Jal-MejJak", 1},
		{7709, "Rocky support", 3},
	};

	private static final int[][] ANIMATIONS = {
		// animation id, owning npc id
		{7574, 7691}, // nibbler attack
		{7578, 7692}, // bat attack
		{7581, 7693}, {7582, 7693}, {7583, 7693}, // blob attacks
		{7597, 7697}, {7600, 7697}, {7601, 7697}, // meleer attack, dig down, dig up
		{7604, 7698}, {7605, 7698}, // ranger melee, ranged
		{7610, 7699}, {7611, 7699}, {7612, 7699}, // mager magic, resurrect, melee
		{7590, 7700}, {7592, 7700}, {7593, 7700}, // jad melee, magic, ranged
		{7566, 7706}, // zuk attack
	};

	private static final int[] SPOTANIMS = {1375, 1376, 1377, 1378, 1379, 1380, 1381, 1382, 1609, 1610};

	private static final int[] PILLAR_OBJECTS = {30284, 30285, 30286, 30287};

	@Test
	public void verifyInfernoData() throws IOException
	{
		String dir = System.getenv("INFERNO_CACHE_DIR");
		Assume.assumeTrue("INFERNO_CACHE_DIR not set", dir != null && new File(dir).isDirectory());

		List<String> failures = new ArrayList<>();
		try (Store store = new Store(new File(dir)))
		{
			store.load();

			NpcManager npcs = new NpcManager(store);
			npcs.load();
			for (Object[] row : NPCS)
			{
				int id = (Integer) row[0];
				NpcDefinition def = npcs.get(id);
				if (def == null)
				{
					failures.add("npc " + id + " missing");
					continue;
				}
				System.out.printf("npc %d name='%s' size=%d combat=%d stand=%d%n", id, def.getName(), def.getSize(), def.getCombatLevel(), def.getStandingAnimation());
				if (!row[1].equals(def.getName().replaceAll("<[^>]*>", "")))
				{
					failures.add("npc " + id + " name '" + def.getName() + "' != '" + row[1] + "'");
				}
				if (row[2] != null && (Integer) row[2] != def.getSize())
				{
					failures.add("npc " + id + " size " + def.getSize() + " != " + row[2]);
				}
			}
			// 12594 is a size-2 Jal-ImKot from other content; it must not be mistaken for the Inferno meleer.
			NpcDefinition small = npcs.get(12594);
			if (small != null && small.getSize() == 4)
			{
				failures.add("npc 12594 is now size 4 - re-check whether it is an Inferno meleer variant");
			}

			Map<Integer, SequenceDefinition> sequences = loadSequences(store);
			for (int[] row : ANIMATIONS)
			{
				SequenceDefinition attack = sequences.get(row[0]);
				NpcDefinition owner = npcs.get(row[1]);
				SequenceDefinition idle = owner == null ? null : sequences.get(owner.getStandingAnimation());
				if (attack == null || idle == null)
				{
					failures.add("anim " + row[0] + " or idle of npc " + row[1] + " missing");
					continue;
				}
				int attackRig = framemapOf(store, attack);
				int idleRig = framemapOf(store, idle);
				System.out.printf("anim %d rig=%d | npc %d idle %d rig=%d%n", row[0], attackRig, row[1], idle.getId(), idleRig);
				if (attackRig < 0 || attackRig != idleRig)
				{
					failures.add("anim " + row[0] + " rig " + attackRig + " != npc " + row[1] + " rig " + idleRig);
				}
			}

			Map<Integer, SpotAnimDefinition> spotanims = loadSpotAnims(store);
			for (int id : SPOTANIMS)
			{
				SpotAnimDefinition def = spotanims.get(id);
				System.out.printf("spotanim %d %s%n", id, def == null ? "MISSING" : "model=" + def.getModelId() + " anim=" + def.getAnimationId());
				if (def == null)
				{
					failures.add("spotanim " + id + " missing");
				}
			}

			ObjectManager objects = new ObjectManager(store);
			objects.load();
			for (int id : PILLAR_OBJECTS)
			{
				ObjectDefinition def = objects.getObject(id);
				if (def == null)
				{
					failures.add("object " + id + " missing");
					continue;
				}
				System.out.printf("object %d name='%s' size=%dx%d interactType=%d blocksProjectile=%s%n",
					id, def.getName(), def.getSizeX(), def.getSizeY(), def.getInteractType(), def.isBlocksProjectile());
				if (def.getInteractType() == 0 || !def.isBlocksProjectile())
				{
					failures.add("object " + id + " is not a solid line-of-sight blocker");
				}
			}
		}

		failures.forEach(f -> System.out.println("FAIL " + f));
		assertTrue(String.join("\n", failures), failures.isEmpty());
	}

	/**
	 * Prints the Inferno arena (region 9043, plane 0) from the cache: every
	 * pillar object position and an ASCII map of line-of-sight blockers.
	 * Needs INFERNO_XTEA="k0,k1,k2,k3" (map keys are not in the cache).
	 */
	@Test
	public void dumpInfernoArena() throws IOException
	{
		String dir = System.getenv("INFERNO_CACHE_DIR");
		String xtea = System.getenv("INFERNO_XTEA");
		Assume.assumeTrue(dir != null && xtea != null);
		String[] parts = xtea.split(",");
		int[] key = new int[4];
		for (int i = 0; i < 4; i++)
		{
			key[i] = Integer.parseInt(parts[i].trim());
		}

		try (Store store = new Store(new File(dir)))
		{
			store.load();
			ObjectManager objects = new ObjectManager(store);
			objects.load();
			net.runelite.cache.region.RegionLoader loader = new net.runelite.cache.region.RegionLoader(store, region -> region == 9043 ? key : null);
			net.runelite.cache.definitions.LocationsDefinition locs = loader.loadLocDef(9043);
			assertTrue("could not decrypt region 9043 locations", locs != null);

			char[][] grid = new char[64][64];
			for (char[] row : grid)
			{
				java.util.Arrays.fill(row, '.');
			}
			for (net.runelite.cache.region.Location loc : locs.getLocations())
			{
				if (loc.getPosition().getZ() != 0)
				{
					continue;
				}
				ObjectDefinition def = objects.getObject(loc.getId());
				int lx = loc.getPosition().getX() & 63;
				int ly = loc.getPosition().getY() & 63;
				boolean pillar = loc.getId() >= 30284 && loc.getId() <= 30287;
				if (pillar)
				{
					System.out.printf("pillar obj=%d type=%d local=(%d,%d) world=(%d,%d)%n", loc.getId(), loc.getType(), lx, ly, loc.getPosition().getX(), loc.getPosition().getY());
				}
				if (def == null || def.getInteractType() == 0)
				{
					continue;
				}
				int sx = (loc.getOrientation() & 1) == 1 ? def.getSizeY() : def.getSizeX();
				int sy = (loc.getOrientation() & 1) == 1 ? def.getSizeX() : def.getSizeY();
				char c = pillar ? 'P' : (def.isBlocksProjectile() ? '#' : 'o');
				// types 0-3 are walls (edge collision), 22 is floor decoration
				if (loc.getType() <= 3)
				{
					c = def.isBlocksProjectile() ? 'w' : 'v';
					sx = 1;
					sy = 1;
				}
				for (int x = lx; x < Math.min(64, lx + sx); x++)
				{
					for (int y = ly; y < Math.min(64, ly + sy); y++)
					{
						if (grid[y][x] != 'P')
						{
							grid[y][x] = c;
						}
					}
				}
			}
			for (int y = 63; y >= 0; y--)
			{
				System.out.printf("%2d %s%n", y, new String(grid[y]));
			}
		}
	}

	private static Map<Integer, SequenceDefinition> loadSequences(Store store) throws IOException
	{
		Storage storage = store.getStorage();
		Archive archive = store.getIndex(IndexType.CONFIGS).getArchive(ConfigType.SEQUENCE.getId());
		ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));
		SequenceLoader loader = new SequenceLoader().configureForRevision(archive.getRevision());
		Map<Integer, SequenceDefinition> out = new HashMap<>();
		for (FSFile f : files.getFiles())
		{
			out.put(f.getFileId(), loader.load(f.getFileId(), f.getContents()));
		}
		return out;
	}

	private static Map<Integer, SpotAnimDefinition> loadSpotAnims(Store store) throws IOException
	{
		Storage storage = store.getStorage();
		Archive archive = store.getIndex(IndexType.CONFIGS).getArchive(ConfigType.SPOTANIM.getId());
		ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));
		SpotAnimLoader loader = new SpotAnimLoader();
		Map<Integer, SpotAnimDefinition> out = new HashMap<>();
		for (FSFile f : files.getFiles())
		{
			out.put(f.getFileId(), loader.load(f.getFileId(), f.getContents()));
		}
		return out;
	}

	/** Skeleton id of a frame-based animation: first 2 bytes of its first frame file. -1 if skeletal/unknown. */
	private static int framemapOf(Store store, SequenceDefinition seq) throws IOException
	{
		if (seq.frameIDs == null || seq.frameIDs.length == 0)
		{
			return -1;
		}
		Index frames = store.getIndex(IndexType.ANIMATIONS);
		Archive archive = frames.getArchive(seq.frameIDs[0] >>> 16);
		if (archive == null)
		{
			return -1;
		}
		ArchiveFiles files = archive.getFiles(store.getStorage().loadArchive(archive));
		byte[] contents = files.getFiles().iterator().next().getContents();
		return (contents[0] & 0xff) << 8 | contents[1] & 0xff;
	}
}
