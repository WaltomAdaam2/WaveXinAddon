package me.waltom.wavexin.modules.litematicaprinter;

final class CuboidCursor {
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private int x;
    private int y;
    private int z;
    private boolean finished;

    CuboidCursor(int x1, int y1, int z1, int x2, int y2, int z2) {
        minX = Math.min(x1, x2);
        minY = Math.min(y1, y2);
        minZ = Math.min(z1, z2);
        maxX = Math.max(x1, x2);
        maxY = Math.max(y1, y2);
        maxZ = Math.max(z1, z2);
        reset();
    }

    boolean hasNext() {
        return !finished;
    }

    Position next() {
        if (finished) throw new IllegalStateException("Cuboid scan is complete");

        Position result = new Position(x, y, z);
        if (x < maxX) {
            x++;
        } else if (z < maxZ) {
            x = minX;
            z++;
        } else if (y < maxY) {
            x = minX;
            z = minZ;
            y++;
        } else {
            finished = true;
        }
        return result;
    }

    void reset() {
        x = minX;
        y = minY;
        z = minZ;
        finished = false;
    }

    long volume() {
        return Math.multiplyExact(
            Math.multiplyExact((long) maxX - minX + 1, (long) maxY - minY + 1),
            (long) maxZ - minZ + 1
        );
    }

    record Position(int x, int y, int z) {
    }
}
