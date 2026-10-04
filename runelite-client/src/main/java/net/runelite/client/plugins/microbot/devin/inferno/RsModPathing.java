/*
 * Near-verbatim Java port of parts of RS Mod (https://github.com/rsmod/rsmod):
 *   engine/routefinder/.../StepValidator.kt          (@author Kris | 16/03/2022)
 *   api/route/.../StepFactory.kt                      (validated)
 *   engine/routefinder/.../RouteFinding.kt            (naiveDestination)
 *   engine/routefinder/.../LineValidator.kt           (hasLineOfSight / rayCast)
 *   engine/routefinder/.../LineRouteFinding.kt        (SIGHT_BLOCKED_*, coordinate, scaleUp/Down)
 *   engine/routefinder/.../flag/CollisionFlag.kt
 *   engine/routefinder/.../collision/CollisionStrategy.kt (Normal)
 *   engine/routefinder/.../reach/ReachStrategy.kt     (reachExclusiveRectangle, @author Kris | 12/09/2021)
 *   engine/routefinder/.../reach/RectangularBounds.kt (collides, reachRectangle1, reachRectangleN)
 *   engine/map/.../util/Bounds.kt                     (areWithinDistance)
 *   api/game-process/.../npc/NpcInteractionProcessor.kt (isValidApRange, isWithinOpRange)
 *
 * RS Mod licence (ISC):
 *
 * Copyright (c) 2025 RS Mod
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 *
 * LineValidator.kt / LineRouteFinding.kt additionally carry this notice:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.microbot.devin.inferno;

/**
 * RS Mod's NPC movement, line of sight and interaction-range rules.
 *
 * <p>Coordinates are absolute (RS Mod's x/z = RuneLite's x/y), level 0. Collision flags use
 * RS Mod's bit layout, which is bit-for-bit the same as RuneLite's {@code CollisionDataFlag}
 * (0x1..0x80 directional walls, 0x100 LOC = BLOCK_MOVEMENT_OBJECT, 0x200..0x10000 directional
 * projectile blockers, 0x20000 LOC_PROJ_BLOCKER = BLOCK_LINE_OF_SIGHT_FULL, 0x40000 GROUND_DECOR
 * = BLOCK_MOVEMENT_FLOOR_DECORATION, 0x200000 BLOCK_WALK = BLOCK_MOVEMENT_FLOOR). RS Mod's custom
 * bits 0x80000 BLOCK_NPCS and 0x100000 BLOCK_PLAYERS are not client flags; callers set
 * BLOCK_NPCS on tiles held by other NPCs. Unallocated tiles read as -1 (all bits), as in RS Mod's
 * CollisionFlagMap.
 */
public final class RsModPathing {

    private RsModPathing() {
    }

    /** Collision flags of absolute tile (x, z); -1 when unknown (RS Mod DEFAULT_COLLISION_FLAG). */
    @FunctionalInterface
    public interface CollisionMap {
        int get(int x, int z);
    }

    // ------------------------------------------------------------------ CollisionFlag.kt

    public static final int WALL_NORTH_WEST = 0x1;
    public static final int WALL_NORTH = 0x2;
    public static final int WALL_NORTH_EAST = 0x4;
    public static final int WALL_EAST = 0x8;
    public static final int WALL_SOUTH_EAST = 0x10;
    public static final int WALL_SOUTH = 0x20;
    public static final int WALL_SOUTH_WEST = 0x40;
    public static final int WALL_WEST = 0x80;
    public static final int LOC = 0x100;
    public static final int WALL_NORTH_WEST_PROJ_BLOCKER = 0x200;
    public static final int WALL_NORTH_PROJ_BLOCKER = 0x400;
    public static final int WALL_NORTH_EAST_PROJ_BLOCKER = 0x800;
    public static final int WALL_EAST_PROJ_BLOCKER = 0x1000;
    public static final int WALL_SOUTH_EAST_PROJ_BLOCKER = 0x2000;
    public static final int WALL_SOUTH_PROJ_BLOCKER = 0x4000;
    public static final int WALL_SOUTH_WEST_PROJ_BLOCKER = 0x8000;
    public static final int WALL_WEST_PROJ_BLOCKER = 0x10000;
    public static final int LOC_PROJ_BLOCKER = 0x20000;
    public static final int GROUND_DECOR = 0x40000;
    public static final int BLOCK_NPCS = 0x80000;
    public static final int BLOCK_PLAYERS = 0x100000;
    public static final int BLOCK_WALK = 0x200000;

    private static final int FLOOR_BLOCKED = BLOCK_WALK | GROUND_DECOR;
    static final int BLOCK_WEST = WALL_EAST | LOC | FLOOR_BLOCKED;
    static final int BLOCK_EAST = WALL_WEST | LOC | FLOOR_BLOCKED;
    static final int BLOCK_SOUTH = WALL_NORTH | LOC | FLOOR_BLOCKED;
    static final int BLOCK_NORTH = WALL_SOUTH | LOC | FLOOR_BLOCKED;
    static final int BLOCK_SOUTH_WEST = WALL_NORTH | WALL_NORTH_EAST | WALL_EAST | LOC | FLOOR_BLOCKED;
    static final int BLOCK_SOUTH_EAST = WALL_NORTH_WEST | WALL_NORTH | WALL_WEST | LOC | FLOOR_BLOCKED;
    static final int BLOCK_NORTH_WEST = WALL_EAST | WALL_SOUTH_EAST | WALL_SOUTH | LOC | FLOOR_BLOCKED;
    static final int BLOCK_NORTH_EAST = WALL_SOUTH | WALL_SOUTH_WEST | WALL_WEST | LOC | FLOOR_BLOCKED;
    static final int BLOCK_NORTH_AND_SOUTH_EAST =
            WALL_NORTH | WALL_NORTH_EAST | WALL_EAST | WALL_SOUTH_EAST | WALL_SOUTH | LOC | FLOOR_BLOCKED;
    static final int BLOCK_NORTH_AND_SOUTH_WEST =
            WALL_NORTH_WEST | WALL_NORTH | WALL_SOUTH | WALL_SOUTH_WEST | WALL_WEST | LOC | FLOOR_BLOCKED;
    static final int BLOCK_NORTH_EAST_AND_WEST =
            WALL_NORTH_WEST | WALL_NORTH | WALL_NORTH_EAST | WALL_EAST | WALL_WEST | LOC | FLOOR_BLOCKED;
    static final int BLOCK_SOUTH_EAST_AND_WEST =
            WALL_EAST | WALL_SOUTH_EAST | WALL_SOUTH | WALL_SOUTH_WEST | WALL_WEST | LOC | FLOOR_BLOCKED;

    // ------------------------------------------------------------------ LineRouteFinding.kt companion

    static final int SIGHT_BLOCKED_NORTH = LOC_PROJ_BLOCKER | WALL_NORTH_PROJ_BLOCKER;
    static final int SIGHT_BLOCKED_EAST = LOC_PROJ_BLOCKER | WALL_EAST_PROJ_BLOCKER;
    static final int SIGHT_BLOCKED_SOUTH = LOC_PROJ_BLOCKER | WALL_SOUTH_PROJ_BLOCKER;
    static final int SIGHT_BLOCKED_WEST = LOC_PROJ_BLOCKER | WALL_WEST_PROJ_BLOCKER;

    private static final int SCALE = 16;
    private static final int HALF_TILE = scaleUp(1) / 2;

    private static int scaleUp(int tiles) {
        return tiles << SCALE;
    }

    private static int scaleDown(int tiles) {
        return tiles >>> SCALE;
    }

    static int coordinate(int a, int b, int size) {
        if (a >= b) {
            return a;
        }
        if (a + size - 1 <= b) {
            return a + size - 1;
        }
        return b;
    }

    // ------------------------------------------------------------------ CollisionStrategy.Normal

    private static boolean canMove(int tileFlag, int blockFlag) {
        return (tileFlag & blockFlag) == 0;
    }

    // ------------------------------------------------------------------ StepValidator.kt

    /** StepValidator.canTravel with CollisionStrategy.Normal. */
    public static boolean canTravel(CollisionMap flags, int x, int z, int offsetX, int offsetZ, int size, int extraFlag) {
        boolean blocked;
        if (offsetX == 0 && offsetZ == -1) {
            blocked = isBlockedSouth(flags, x, z, size, extraFlag);
        } else if (offsetX == 0 && offsetZ == 1) {
            blocked = isBlockedNorth(flags, x, z, size, extraFlag);
        } else if (offsetX == -1 && offsetZ == 0) {
            blocked = isBlockedWest(flags, x, z, size, extraFlag);
        } else if (offsetX == 1 && offsetZ == 0) {
            blocked = isBlockedEast(flags, x, z, size, extraFlag);
        } else if (offsetX == -1 && offsetZ == -1) {
            blocked = isBlockedSouthWest(flags, x, z, size, extraFlag);
        } else if (offsetX == -1 && offsetZ == 1) {
            blocked = isBlockedNorthWest(flags, x, z, size, extraFlag);
        } else if (offsetX == 1 && offsetZ == -1) {
            blocked = isBlockedSouthEast(flags, x, z, size, extraFlag);
        } else if (offsetX == 1 && offsetZ == 1) {
            blocked = isBlockedNorthEast(flags, x, z, size, extraFlag);
        } else {
            throw new IllegalStateException("Invalid step tile offset: " + offsetX + ", " + offsetZ);
        }
        return !blocked;
    }

    private static boolean isBlockedSouth(CollisionMap flags, int x, int z, int size, int extraFlag) {
        switch (size) {
            case 1:
                return !canMove(flags.get(x, z - 1), BLOCK_SOUTH | extraFlag);
            case 2:
                return !canMove(flags.get(x, z - 1), BLOCK_SOUTH_WEST | extraFlag)
                        || !canMove(flags.get(x + 1, z - 1), BLOCK_SOUTH_EAST | extraFlag);
            default:
                if (!canMove(flags.get(x, z - 1), BLOCK_SOUTH_WEST | extraFlag)
                        || !canMove(flags.get(x + size - 1, z - 1), BLOCK_SOUTH_EAST | extraFlag)) {
                    return true;
                }
                for (int midX = x + 1; midX < x + size - 1; midX++) {
                    if (!canMove(flags.get(midX, z - 1), BLOCK_NORTH_EAST_AND_WEST | extraFlag)) {
                        return true;
                    }
                }
                return false;
        }
    }

    private static boolean isBlockedNorth(CollisionMap flags, int x, int z, int size, int extraFlag) {
        switch (size) {
            case 1:
                return !canMove(flags.get(x, z + 1), BLOCK_NORTH | extraFlag);
            case 2:
                return !canMove(flags.get(x, z + 2), BLOCK_NORTH_WEST | extraFlag)
                        || !canMove(flags.get(x + 1, z + 2), BLOCK_NORTH_EAST | extraFlag);
            default:
                if (!canMove(flags.get(x, z + size), BLOCK_NORTH_WEST | extraFlag)
                        || !canMove(flags.get(x + size - 1, z + size), BLOCK_NORTH_EAST | extraFlag)) {
                    return true;
                }
                for (int midX = x + 1; midX < x + size - 1; midX++) {
                    if (!canMove(flags.get(midX, z + size), BLOCK_SOUTH_EAST_AND_WEST | extraFlag)) {
                        return true;
                    }
                }
                return false;
        }
    }

    private static boolean isBlockedWest(CollisionMap flags, int x, int z, int size, int extraFlag) {
        switch (size) {
            case 1:
                return !canMove(flags.get(x - 1, z), BLOCK_WEST | extraFlag);
            case 2:
                return !canMove(flags.get(x - 1, z), BLOCK_SOUTH_WEST | extraFlag)
                        || !canMove(flags.get(x - 1, z + 1), BLOCK_NORTH_WEST | extraFlag);
            default:
                if (!canMove(flags.get(x - 1, z), BLOCK_SOUTH_WEST | extraFlag)
                        || !canMove(flags.get(x - 1, z + size - 1), BLOCK_NORTH_WEST | extraFlag)) {
                    return true;
                }
                for (int midZ = z + 1; midZ < z + size - 1; midZ++) {
                    if (!canMove(flags.get(x - 1, midZ), BLOCK_NORTH_AND_SOUTH_EAST | extraFlag)) {
                        return true;
                    }
                }
                return false;
        }
    }

    private static boolean isBlockedEast(CollisionMap flags, int x, int z, int size, int extraFlag) {
        switch (size) {
            case 1:
                return !canMove(flags.get(x + 1, z), BLOCK_EAST | extraFlag);
            case 2:
                return !canMove(flags.get(x + 2, z), BLOCK_SOUTH_EAST | extraFlag)
                        || !canMove(flags.get(x + 2, z + 1), BLOCK_NORTH_EAST | extraFlag);
            default:
                if (!canMove(flags.get(x + size, z), BLOCK_SOUTH_EAST | extraFlag)
                        || !canMove(flags.get(x + size, z + size - 1), BLOCK_NORTH_EAST | extraFlag)) {
                    return true;
                }
                for (int midZ = z + 1; midZ < z + size - 1; midZ++) {
                    if (!canMove(flags.get(x + size, midZ), BLOCK_NORTH_AND_SOUTH_WEST | extraFlag)) {
                        return true;
                    }
                }
                return false;
        }
    }

    private static boolean isBlockedSouthWest(CollisionMap flags, int x, int z, int size, int extraFlag) {
        switch (size) {
            case 1:
                return !canMove(flags.get(x - 1, z - 1), BLOCK_SOUTH_WEST | extraFlag)
                        || !canMove(flags.get(x - 1, z), BLOCK_WEST | extraFlag)
                        || !canMove(flags.get(x, z - 1), BLOCK_SOUTH | extraFlag);
            case 2:
                return !canMove(flags.get(x - 1, z), BLOCK_NORTH_AND_SOUTH_EAST | extraFlag)
                        || !canMove(flags.get(x - 1, z - 1), BLOCK_SOUTH_WEST | extraFlag)
                        || !canMove(flags.get(x, z - 1), BLOCK_NORTH_EAST_AND_WEST | extraFlag);
            default:
                if (!canMove(flags.get(x - 1, z - 1), BLOCK_SOUTH_WEST | extraFlag)) {
                    return true;
                }
                for (int mid = 1; mid < size; mid++) {
                    if (!canMove(flags.get(x - 1, z + mid - 1), BLOCK_NORTH_AND_SOUTH_EAST | extraFlag)
                            || !canMove(flags.get(x + mid - 1, z - 1), BLOCK_NORTH_EAST_AND_WEST | extraFlag)) {
                        return true;
                    }
                }
                return false;
        }
    }

    private static boolean isBlockedNorthWest(CollisionMap flags, int x, int z, int size, int extraFlag) {
        switch (size) {
            case 1:
                return !canMove(flags.get(x - 1, z + 1), BLOCK_NORTH_WEST | extraFlag)
                        || !canMove(flags.get(x - 1, z), BLOCK_WEST | extraFlag)
                        || !canMove(flags.get(x, z + 1), BLOCK_NORTH | extraFlag);
            case 2:
                return !canMove(flags.get(x - 1, z + 1), BLOCK_NORTH_AND_SOUTH_EAST | extraFlag)
                        || !canMove(flags.get(x - 1, z + 2), BLOCK_NORTH_WEST | extraFlag)
                        || !canMove(flags.get(x, z + 2), BLOCK_SOUTH_EAST_AND_WEST | extraFlag);
            default:
                if (!canMove(flags.get(x - 1, z + size), BLOCK_NORTH_WEST | extraFlag)) {
                    return true;
                }
                for (int mid = 1; mid < size; mid++) {
                    if (!canMove(flags.get(x - 1, z + mid), BLOCK_NORTH_AND_SOUTH_EAST | extraFlag)
                            || !canMove(flags.get(x + mid - 1, z + size), BLOCK_SOUTH_EAST_AND_WEST | extraFlag)) {
                        return true;
                    }
                }
                return false;
        }
    }

    private static boolean isBlockedSouthEast(CollisionMap flags, int x, int z, int size, int extraFlag) {
        switch (size) {
            case 1:
                return !canMove(flags.get(x + 1, z - 1), BLOCK_SOUTH_EAST | extraFlag)
                        || !canMove(flags.get(x + 1, z), BLOCK_EAST | extraFlag)
                        || !canMove(flags.get(x, z - 1), BLOCK_SOUTH | extraFlag);
            case 2:
                return !canMove(flags.get(x + 1, z - 1), BLOCK_NORTH_EAST_AND_WEST | extraFlag)
                        || !canMove(flags.get(x + 2, z - 1), BLOCK_SOUTH_EAST | extraFlag)
                        || !canMove(flags.get(x + 2, z), BLOCK_NORTH_AND_SOUTH_WEST | extraFlag);
            default:
                if (!canMove(flags.get(x + size, z - 1), BLOCK_SOUTH_EAST | extraFlag)) {
                    return true;
                }
                for (int mid = 1; mid < size; mid++) {
                    if (!canMove(flags.get(x + size, z + mid - 1), BLOCK_NORTH_AND_SOUTH_WEST | extraFlag)
                            || !canMove(flags.get(x + mid, z - 1), BLOCK_NORTH_EAST_AND_WEST | extraFlag)) {
                        return true;
                    }
                }
                return false;
        }
    }

    private static boolean isBlockedNorthEast(CollisionMap flags, int x, int z, int size, int extraFlag) {
        switch (size) {
            case 1:
                return !canMove(flags.get(x + 1, z + 1), BLOCK_NORTH_EAST | extraFlag)
                        || !canMove(flags.get(x + 1, z), BLOCK_EAST | extraFlag)
                        || !canMove(flags.get(x, z + 1), BLOCK_NORTH | extraFlag);
            case 2:
                return !canMove(flags.get(x + 1, z + 2), BLOCK_SOUTH_EAST_AND_WEST | extraFlag)
                        || !canMove(flags.get(x + 2, z + 2), BLOCK_NORTH_EAST | extraFlag)
                        || !canMove(flags.get(x + 2, z + 1), BLOCK_NORTH_AND_SOUTH_WEST | extraFlag);
            default:
                if (!canMove(flags.get(x + size, z + size), BLOCK_NORTH_EAST | extraFlag)) {
                    return true;
                }
                for (int mid = 1; mid < size; mid++) {
                    if (!canMove(flags.get(x + mid, z + size), BLOCK_SOUTH_EAST_AND_WEST | extraFlag)
                            || !canMove(flags.get(x + size, z + mid), BLOCK_NORTH_AND_SOUTH_WEST | extraFlag)) {
                        return true;
                    }
                }
                return false;
        }
    }

    // ------------------------------------------------------------------ StepFactory.kt

    /**
     * StepFactory.validated: diagonal toward {@code dest}, else the x step, else the z step.
     *
     * @return {x, z} of the next step, or null (RS Mod CoordGrid.NULL) when none is valid
     */
    public static int[] validated(CollisionMap flags, int srcX, int srcZ, int destX, int destZ, int size, int extraFlag) {
        if (srcX == destX && srcZ == destZ) {
            throw new IllegalArgumentException("`source` must not be equal to `dest`.");
        }
        int signX = Integer.signum(destX - srcX);
        int signZ = Integer.signum(destZ - srcZ);

        // Note: for a straight line (one sign 0) RS Mod's "diagonal" attempt is the straight step.
        boolean diagonal = canTravel(flags, srcX, srcZ, signX, signZ, size, extraFlag);
        if (diagonal) {
            return new int[]{srcX + signX, srcZ + signZ};
        }
        boolean horizontal = signX != 0 && canTravel(flags, srcX, srcZ, signX, 0, size, extraFlag);
        if (horizontal) {
            return new int[]{srcX + signX, srcZ};
        }
        boolean vertical = signZ != 0 && canTravel(flags, srcX, srcZ, 0, signZ, size, extraFlag);
        if (vertical) {
            return new int[]{srcX, srcZ + signZ};
        }
        return null;
    }

    // ------------------------------------------------------------------ RouteFinding.naiveDestination

    /** RouteFinding.naiveDestination with targetAngle 0 (pathing entities). @return {x, z} */
    public static int[] naiveDestination(int sourceX, int sourceZ, int sourceWidth, int sourceLength,
                                         int targetX, int targetZ, int targetWidth, int targetLength) {
        int diagonal = (sourceX - targetX) + (sourceZ - targetZ);
        int anti = (sourceX - targetX) - (sourceZ - targetZ);
        // rotate(0, w, l) == w
        int rotatedWidth = targetWidth;
        int rotatedLength = targetLength;
        boolean southWestClockwise = anti < 0;
        boolean northWestClockwise = diagonal >= (rotatedLength - 1) - (sourceWidth - 1);
        boolean northEastClockwise = anti > sourceWidth - sourceLength;
        boolean southEastClockwise = diagonal <= (rotatedWidth - 1) - (sourceLength - 1);

        if (southWestClockwise && !northWestClockwise) {
            int offZ;
            if (diagonal >= -sourceWidth) { // West
                offZ = Math.min(diagonal + sourceWidth, rotatedLength - 1);
            } else if (anti > -sourceWidth) {
                offZ = -(sourceWidth + anti);
            } else {
                offZ = 0;
            }
            return new int[]{targetX - sourceWidth, targetZ + offZ};
        } else if (northWestClockwise && !northEastClockwise) {
            int offX;
            if (anti >= -rotatedLength) { // North
                offX = Math.min(anti + rotatedLength, rotatedWidth - 1);
            } else if (diagonal < rotatedLength) {
                offX = Math.max(diagonal - rotatedLength, -(sourceWidth - 1));
            } else {
                offX = 0;
            }
            return new int[]{targetX + offX, targetZ + rotatedLength};
        } else if (northEastClockwise && !southEastClockwise) {
            int offZ;
            if (anti <= rotatedWidth) { // East
                offZ = rotatedLength - anti;
            } else if (diagonal < rotatedWidth) {
                offZ = Math.max(diagonal - rotatedWidth, -(sourceLength - 1));
            } else {
                offZ = 0;
            }
            return new int[]{targetX + rotatedWidth, targetZ + offZ};
        } else {
            if (!(southEastClockwise && !southWestClockwise)) {
                throw new IllegalStateException("Check failed.");
            }
            int offX;
            if (diagonal > -sourceLength) { // South
                offX = Math.min(diagonal + sourceLength, rotatedWidth - 1);
            } else if (anti < sourceLength) {
                offX = Math.max(anti - sourceLength, -(sourceLength - 1));
            } else {
                offX = 0;
            }
            return new int[]{targetX + offX, targetZ - sourceLength};
        }
    }

    // ------------------------------------------------------------------ LineValidator.kt

    /** LineValidator.hasLineOfSight. */
    public static boolean hasLineOfSight(CollisionMap flags, int srcX, int srcZ, int destX, int destZ,
                                         int srcWidth, int srcLength, int destWidth, int destLength, int extraFlag) {
        return rayCast(flags, srcX, srcZ, destX, destZ, srcWidth, srcLength, destWidth, destLength,
                SIGHT_BLOCKED_WEST | extraFlag,
                SIGHT_BLOCKED_EAST | extraFlag,
                SIGHT_BLOCKED_SOUTH | extraFlag,
                SIGHT_BLOCKED_NORTH | extraFlag,
                LOC | extraFlag,
                LOC_PROJ_BLOCKER | extraFlag,
                true);
    }

    /** LineValidator.rayCast. */
    static boolean rayCast(CollisionMap flags, int srcX, int srcZ, int destX, int destZ,
                           int srcWidth, int srcLength, int destWidth, int destLength,
                           int flagWest, int flagEast, int flagSouth, int flagNorth,
                           int flagLocation, int flagProjectileBlocker, boolean los) {
        int startX = coordinate(srcX, destX, srcWidth);
        int startZ = coordinate(srcZ, destZ, srcLength);

        int endX = coordinate(destX, srcX, destWidth);
        int endZ = coordinate(destZ, srcZ, destLength);

        if (startX == endX && startZ == endZ) {
            return true;
        }

        if (los && (flags.get(startX, startZ) & flagLocation) != 0) {
            return false;
        }

        int deltaX = endX - startX;
        int deltaZ = endZ - startZ;

        boolean travelEast = deltaX >= 0;
        boolean travelNorth = deltaZ >= 0;

        int xFlags = travelEast ? flagWest : flagEast;
        int zFlags = travelNorth ? flagSouth : flagNorth;

        if (Math.abs(deltaX) > Math.abs(deltaZ)) {
            int offsetX = travelEast ? 1 : -1;
            int offsetZ = travelNorth ? 0 : -1;

            int scaledZ = scaleUp(startZ) + HALF_TILE + offsetZ;
            int tangent = scaleUp(deltaZ) / Math.abs(deltaX);

            int currX = startX;
            while (currX != endX) {
                currX += offsetX;
                int currZ = scaleDown(scaledZ);
                if (los && currX == endX && currZ == endZ) {
                    xFlags = xFlags & ~flagProjectileBlocker;
                }
                if ((flags.get(currX, currZ) & xFlags) != 0) {
                    return false;
                }

                scaledZ += tangent;
                int nextZ = scaleDown(scaledZ);
                if (los && currX == endX && nextZ == endZ) {
                    zFlags = zFlags & ~flagProjectileBlocker;
                }
                if (nextZ != currZ && (flags.get(currX, nextZ) & zFlags) != 0) {
                    return false;
                }
            }
        } else {
            int offsetX = travelEast ? 0 : -1;
            int offsetZ = travelNorth ? 1 : -1;

            int scaledX = scaleUp(startX) + HALF_TILE + offsetX;
            int tangent = scaleUp(deltaX) / Math.abs(deltaZ);

            int currZ = startZ;
            while (currZ != endZ) {
                currZ += offsetZ;
                int currX = scaleDown(scaledX);
                if (los && currX == endX && currZ == endZ) {
                    zFlags = zFlags & ~flagProjectileBlocker;
                }
                if ((flags.get(currX, currZ) & zFlags) != 0) {
                    return false;
                }

                scaledX += tangent;
                int nextX = scaleDown(scaledX);
                if (los && nextX == endX && currZ == endZ) {
                    xFlags = xFlags & ~flagProjectileBlocker;
                }
                if (nextX != currX && (flags.get(nextX, currZ) & xFlags) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ Bounds.kt / RectangularBounds.kt

    /** Bounds.areWithinDistance: per-axis rectangle gap (0 overlapping, 1 touching) both within {@code distance}. */
    public static boolean areWithinDistance(int ax, int az, int aWidth, int aLength,
                                            int bx, int bz, int bWidth, int bLength, int distance) {
        if (distance < 0) {
            return false;
        }
        // minX(a, b) = a if a.x < b.x else b; maxX(a, b) = a if a.x > b.x else b.
        int minX = ax < bx ? ax : bx;
        int minXWidth = ax < bx ? aWidth : bWidth;
        int maxX = ax > bx ? ax : bx;
        int xDistance = Math.max(0, maxX - (minX + minXWidth - 1));
        if (xDistance > distance) {
            return false;
        }
        int minZ = az < bz ? az : bz;
        int minZLength = az < bz ? aLength : bLength;
        int maxZ = az > bz ? az : bz;
        int zDistance = Math.max(0, maxZ - (minZ + minZLength - 1));
        return zDistance <= distance;
    }

    /** RectangularBounds.collides. */
    public static boolean collides(int srcX, int srcZ, int destX, int destZ,
                                   int srcWidth, int srcLength, int destWidth, int destLength) {
        if (srcX >= destX + destWidth || srcX + srcWidth <= destX) {
            return false;
        }
        return srcZ < destZ + destLength && destZ < srcLength + srcZ;
    }

    /** RectangularBounds.reachRectangle1 (blockAccessFlags 0). */
    private static boolean reachRectangle1(CollisionMap flags, int srcX, int srcZ, int destX, int destZ,
                                           int destWidth, int destLength) {
        int east = destX + destWidth - 1;
        int north = destZ + destLength - 1;

        if (srcX == destX - 1 && srcZ >= destZ && srcZ <= north && (flags.get(srcX, srcZ) & WALL_EAST) == 0) {
            return true;
        }
        if (srcX == east + 1 && srcZ >= destZ && srcZ <= north && (flags.get(srcX, srcZ) & WALL_WEST) == 0) {
            return true;
        }
        if (srcZ + 1 == destZ && srcX >= destX && srcX <= east && (flags.get(srcX, srcZ) & WALL_NORTH) == 0) {
            return true;
        }
        return srcZ == north + 1 && srcX >= destX && srcX <= east && (flags.get(srcX, srcZ) & WALL_SOUTH) == 0;
    }

    /** RectangularBounds.reachRectangleN (blockAccessFlags 0). */
    private static boolean reachRectangleN(CollisionMap flags, int srcX, int srcZ, int destX, int destZ,
                                           int srcWidth, int srcLength, int destWidth, int destLength) {
        int srcEast = srcX + srcWidth;
        int srcNorth = srcLength + srcZ;
        int destEast = destWidth + destX;
        int destNorth = destLength + destZ;
        if (destEast == srcX) {
            int fromZ = Math.max(srcZ, destZ);
            int toZ = Math.min(srcNorth, destNorth);
            for (int sideZ = fromZ; sideZ < toZ; sideZ++) {
                if ((flags.get(destEast - 1, sideZ) & WALL_EAST) == 0) {
                    return true;
                }
            }
        } else if (srcEast == destX) {
            int fromZ = Math.max(srcZ, destZ);
            int toZ = Math.min(srcNorth, destNorth);
            for (int sideZ = fromZ; sideZ < toZ; sideZ++) {
                if ((flags.get(destX, sideZ) & WALL_WEST) == 0) {
                    return true;
                }
            }
        } else if (srcZ == destNorth) {
            int fromX = Math.max(srcX, destX);
            int toX = Math.min(srcEast, destEast);
            for (int sideX = fromX; sideX < toX; sideX++) {
                if ((flags.get(sideX, destNorth - 1) & WALL_NORTH) == 0) {
                    return true;
                }
            }
        } else if (destZ == srcNorth) {
            int fromX = Math.max(srcX, destX);
            int toX = Math.min(srcEast, destEast);
            for (int sideX = fromX; sideX < toX; sideX++) {
                if ((flags.get(sideX, destZ) & WALL_SOUTH) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** ReachStrategy.reachExclusiveRectangle (locAngle 0, blockAccessFlags 0). */
    public static boolean reachExclusiveRectangle(CollisionMap flags, int srcX, int srcZ, int destX, int destZ,
                                                  int srcSize, int destWidth, int destLength) {
        if (srcSize > 1) {
            return !collides(srcX, srcZ, destX, destZ, srcSize, srcSize, destWidth, destLength)
                    && reachRectangleN(flags, srcX, srcZ, destX, destZ, srcSize, srcSize, destWidth, destLength);
        }
        return !collides(srcX, srcZ, destX, destZ, srcSize, srcSize, destWidth, destLength)
                && reachRectangle1(flags, srcX, srcZ, destX, destZ, destWidth, destLength);
    }

    // ------------------------------------------------------------------ NpcInteractionProcessor.kt

    /**
     * Op range of an NPC toward a pathing entity (NpcInteractionProcessor.isWithinOpRange =
     * BoundValidator.touches = reachExclusiveRectangle): the NPC's edge must touch the target's
     * edge (cardinal adjacency, no overlap, not blocked by a wall on the shared edge).
     */
    public static boolean isWithinOpRange(CollisionMap flags, int npcX, int npcZ, int npcSize,
                                          int targetX, int targetZ, int targetSize) {
        return reachExclusiveRectangle(flags, npcX, npcZ, targetX, targetZ, npcSize, targetSize, targetSize);
    }

    /**
     * Ap range of an NPC toward a pathing entity (NpcInteractionProcessor.isWithinApRange): false
     * when the NPC overlaps the target, otherwise Bounds.areWithinDistance(npc, target, distance)
     * and line of sight cast in "reverse", from the target to the NPC, with BLOCK_PLAYERS as the
     * extra flag.
     */
    public static boolean isWithinApRange(CollisionMap flags, int npcX, int npcZ, int npcSize,
                                          int targetX, int targetZ, int targetSize, int distance) {
        if (collides(npcX, npcZ, targetX, targetZ, npcSize, npcSize, targetSize, targetSize)) {
            return false;
        }
        if (!areWithinDistance(npcX, npcZ, npcSize, npcSize, targetX, targetZ, targetSize, targetSize, distance)) {
            return false;
        }
        // Line-of-sight for npcs is always calculated in "reverse."
        return hasLineOfSight(flags, targetX, targetZ, npcX, npcZ, targetSize, targetSize, npcSize, npcSize, BLOCK_PLAYERS);
    }
}
