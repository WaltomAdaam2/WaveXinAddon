package me.waltom.wavexin.modules.basefinder;

final class BaseFinderStateLogic {
    private BaseFinderStateLogic() {
    }

    static int blockToChunk(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), 16);
    }

    static boolean shouldLogNormalDebugSnapshot(String event, String state) {
        return "WAITING_PLAYER_OR_WORLD".equals(state);
    }

    static boolean shouldCancelSpiralRotation(boolean lockView, boolean rotating, boolean needsInitialRotation) {
        return !lockView && (rotating || needsInitialRotation);
    }

    static String pearlWaypointName(int number) {
        return "Pearl " + Math.max(1, number);
    }

    static String pearlWaypointAlias(int number) {
        return "P" + Math.max(1, number);
    }

    static final class ViewRotationState {
        private Object player;
        private Object world;
        private float yaw;
        private float pitch;
        private float headYaw;
        private float bodyYaw;
        private boolean pending;
        private boolean restoreAfterTick;

        void captureIfNeeded(Object currentPlayer, Object currentWorld, float currentYaw, float currentPitch, float currentHeadYaw, float currentBodyYaw, boolean temporary) {
            if (currentPlayer == null || currentWorld == null) {
                clear();
                return;
            }

            if (pending && (player != currentPlayer || world != currentWorld)) clear();

            if (!pending) {
                player = currentPlayer;
                world = currentWorld;
                yaw = currentYaw;
                pitch = currentPitch;
                headYaw = currentHeadYaw;
                bodyYaw = currentBodyYaw;
                pending = true;
            }

            restoreAfterTick |= temporary;
        }

        boolean shouldRestoreAfterTick() {
            return pending && restoreAfterTick;
        }

        Snapshot consumeRestore(Object currentPlayer, Object currentWorld) {
            if (!pending) return null;
            if (player != currentPlayer || world != currentWorld) {
                clear();
                return null;
            }

            Snapshot snapshot = new Snapshot(yaw, pitch, headYaw, bodyYaw);
            clear();
            return snapshot;
        }

        boolean pending() {
            return pending;
        }

        void clear() {
            player = null;
            world = null;
            pending = false;
            restoreAfterTick = false;
        }
    }

    record Snapshot(float yaw, float pitch, float headYaw, float bodyYaw) {
    }

    static final class SprintState {
        private Object player;
        private Object world;
        private boolean previousSprinting;
        private boolean pending;

        void captureIfNeeded(Object currentPlayer, Object currentWorld, boolean sprinting) {
            if (currentPlayer == null || currentWorld == null) {
                clear();
                return;
            }

            if (pending && (player != currentPlayer || world != currentWorld)) clear();

            if (!pending) {
                player = currentPlayer;
                world = currentWorld;
                previousSprinting = sprinting;
                pending = true;
            }
        }

        Boolean consumeRestore(Object currentPlayer, Object currentWorld) {
            if (!pending) return null;
            if (player != currentPlayer || world != currentWorld) {
                clear();
                return null;
            }

            boolean value = previousSprinting;
            clear();
            return value;
        }

        boolean pending() {
            return pending;
        }

        void clear() {
            player = null;
            world = null;
            previousSprinting = false;
            pending = false;
        }
    }

    static final class PerTargetDebugGate {
        private String lastKey;

        boolean shouldEmit(boolean enabled, int targetX, int targetZ, int segment) {
            if (!enabled) return false;
            String key = targetX + ":" + targetZ + ":" + segment;
            if (key.equals(lastKey)) return false;
            lastKey = key;
            return true;
        }

        void clear() {
            lastKey = null;
        }
    }
}
