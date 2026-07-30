package me.waltom.wavexin.modules.basefinder;

import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Cached, optional Xaero Minimap integration.
 *
 * <p>The addon intentionally does not link Xaero at compile time. This bridge resolves the
 * supported API shape once, caches reflective members, and then reuses them for every waypoint.
 * It supports both the legacy XaeroMinimapSession API and the newer BuiltInHudModules API.</p>
 */
public final class XaeroWaypointBridge {
    public enum Status {
        CREATED,
        MISSING,
        SESSION_NOT_READY,
        WORLD_NOT_READY,
        SET_NOT_READY,
        FAILED
    }

    public record Result(Status status, String detail) {
        public boolean created() {
            return status == Status.CREATED;
        }
    }

    private interface Access {
        Result create(BlockPos pos, String name, String initials, int colorId);
    }

    private volatile boolean resolved;
    private volatile Access access;
    private volatile String unavailableReason = "Xaero classes were not found";

    public boolean isAvailable() {
        resolve();
        return access != null;
    }

    public String unavailableReason() {
        resolve();
        return unavailableReason;
    }

    public Result create(BlockPos pos, String name, String initials, int colorId) {
        resolve();
        Access local = access;
        if (local == null) return new Result(Status.MISSING, unavailableReason);
        return local.create(pos, name, initials, colorId);
    }

    private void resolve() {
        if (resolved) return;
        synchronized (this) {
            if (resolved) return;

            try {
                access = new LegacyAccess();
                unavailableReason = "";
            } catch (ReflectiveOperationException legacyFailure) {
                try {
                    access = new ModernAccess();
                    unavailableReason = "";
                } catch (ReflectiveOperationException modernFailure) {
                    access = null;
                    unavailableReason = "legacy=" + messageOf(legacyFailure) + "; modern=" + messageOf(modernFailure);
                }
            }

            resolved = true;
        }
    }

    private static final class LegacyAccess implements Access {
        private final Method getCurrentSession;
        private final Constructor<?> waypointConstructor;
        private final Class<?> waypointClass;

        private volatile RuntimeMethods runtime;

        private LegacyAccess() throws ReflectiveOperationException {
            Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            getCurrentSession = sessionClass.getMethod("getCurrentSession");
            waypointConstructor = waypointClass.getConstructor(
                int.class, int.class, int.class, String.class, String.class, int.class
            );
        }

        @Override
        public Result create(BlockPos pos, String name, String initials, int colorId) {
            try {
                Object currentSession = getCurrentSession.invoke(null);
                if (currentSession == null) return new Result(Status.SESSION_NOT_READY, "current session is null");

                RuntimeMethods methods = runtime;
                if (methods == null || !methods.supports(currentSession)) {
                    methods = new RuntimeMethods(currentSession, waypointClass);
                    runtime = methods;
                }

                Object processor = methods.getMinimapProcessor.invoke(currentSession);
                Object minimapSession = methods.getSession.invoke(processor);
                if (minimapSession == null) return new Result(Status.SESSION_NOT_READY, "minimap session is null");

                Object worldManager = methods.getWorldManager.invoke(minimapSession);
                Object currentWorld = methods.getCurrentWorld.invoke(worldManager);
                if (currentWorld == null) return new Result(Status.WORLD_NOT_READY, "current waypoint world is null");

                Object waypointSet = methods.getCurrentWaypointSet.invoke(currentWorld);
                if (waypointSet == null) return new Result(Status.SET_NOT_READY, "current waypoint set is null");

                Object waypoint = waypointConstructor.newInstance(
                    pos.getX(), pos.getY(), pos.getZ(), name, initials, colorId
                );
                methods.addWaypoint.invoke(waypointSet, waypoint);

                Object waypointSession = methods.getWaypointSession.invoke(minimapSession);
                if (waypointSession != null) {
                    methods.setSetChangedTime.invoke(waypointSession, System.currentTimeMillis());
                }
                return new Result(Status.CREATED, "");
            } catch (ReflectiveOperationException | RuntimeException e) {
                return new Result(Status.FAILED, messageOf(e));
            }
        }

        private static final class RuntimeMethods {
            private final Class<?> currentSessionClass;
            private final Method getMinimapProcessor;
            private final Method getSession;
            private final Method getWorldManager;
            private final Method getCurrentWorld;
            private final Method getCurrentWaypointSet;
            private final Method addWaypoint;
            private final Method getWaypointSession;
            private final Method setSetChangedTime;

            private RuntimeMethods(Object currentSession, Class<?> waypointClass) throws ReflectiveOperationException {
                currentSessionClass = currentSession.getClass();
                getMinimapProcessor = currentSessionClass.getMethod("getMinimapProcessor");

                Class<?> processorClass = getMinimapProcessor.getReturnType();
                getSession = processorClass.getMethod("getSession");

                Class<?> minimapSessionClass = getSession.getReturnType();
                getWorldManager = minimapSessionClass.getMethod("getWorldManager");
                getWaypointSession = minimapSessionClass.getMethod("getWaypointSession");

                Class<?> worldManagerClass = getWorldManager.getReturnType();
                getCurrentWorld = worldManagerClass.getMethod("getCurrentWorld");

                Class<?> worldClass = getCurrentWorld.getReturnType();
                getCurrentWaypointSet = worldClass.getMethod("getCurrentWaypointSet");

                Class<?> setClass = getCurrentWaypointSet.getReturnType();
                addWaypoint = setClass.getMethod("add", waypointClass);

                Class<?> waypointSessionClass = getWaypointSession.getReturnType();
                setSetChangedTime = waypointSessionClass.getMethod("setSetChangedTime", long.class);
            }

            private boolean supports(Object session) {
                return currentSessionClass.isInstance(session);
            }
        }
    }

    private static final class ModernAccess implements Access {
        private final Object minimapModule;
        private final Method getCurrentSession;
        private final Class<?> waypointClass;
        private final Class<?> waypointColorClass;
        private final Class<?> waypointPurposeClass;
        private final Constructor<?> waypointConstructor;
        private final ColorResolver colorResolver;
        private final Object normalPurpose;

        private volatile ModernRuntimeMethods runtime;

        private ModernAccess() throws ReflectiveOperationException {
            Class<?> modulesClass = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Field minimapField = modulesClass.getField("MINIMAP");
            minimapModule = minimapField.get(null);
            getCurrentSession = minimapModule.getClass().getMethod("getCurrentSession");

            waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            waypointColorClass = Class.forName("xaero.hud.minimap.waypoint.WaypointColor");
            waypointPurposeClass = Class.forName("xaero.hud.minimap.waypoint.WaypointPurpose");
            colorResolver = resolveColorResolver(waypointColorClass);
            normalPurpose = waypointPurposeClass.getField("NORMAL").get(null);
            waypointConstructor = waypointClass.getConstructor(
                int.class, int.class, int.class, String.class, String.class,
                waypointColorClass, waypointPurposeClass, boolean.class
            );
        }

        @Override
        public Result create(BlockPos pos, String name, String initials, int colorId) {
            try {
                Object session = getCurrentSession.invoke(minimapModule);
                if (session == null) return new Result(Status.SESSION_NOT_READY, "current session is null");

                ModernRuntimeMethods methods = runtime;
                if (methods == null || !methods.supports(session)) {
                    methods = new ModernRuntimeMethods(session, waypointClass);
                    runtime = methods;
                }

                Object worldManager = methods.getWorldManager.invoke(session);
                Object currentWorld = methods.getCurrentWorld.invoke(worldManager);
                if (currentWorld == null) return new Result(Status.WORLD_NOT_READY, "current waypoint world is null");

                Object waypointSet = methods.getCurrentWaypointSet.invoke(currentWorld);
                if (waypointSet == null) return new Result(Status.SET_NOT_READY, "current waypoint set is null");

                Object color = colorResolver.resolve(colorId);
                Object waypoint = waypointConstructor.newInstance(
                    pos.getX(), pos.getY(), pos.getZ(), name, initials, color, normalPurpose, false
                );
                methods.addWaypoint.invoke(waypointSet, waypoint);
                methods.saveIfSupported(session, currentWorld);
                return new Result(Status.CREATED, "");
            } catch (ReflectiveOperationException | RuntimeException e) {
                return new Result(Status.FAILED, messageOf(e));
            }
        }

        @FunctionalInterface
        private interface ColorResolver {
            Object resolve(int colorId) throws ReflectiveOperationException;
        }

        private static ColorResolver resolveColorResolver(Class<?> colorClass) throws ReflectiveOperationException {
            try {
                Method fromIndex = colorClass.getMethod("fromIndex", int.class);
                return colorId -> fromIndex.invoke(null, Math.floorMod(colorId, 16));
            } catch (NoSuchMethodException ignored) {
                Method valuesMethod = colorClass.getMethod("values");
                Object[] values = (Object[]) valuesMethod.invoke(null);
                if (values.length == 0) throw new NoSuchMethodException("WaypointColor has no values");
                return colorId -> values[Math.floorMod(colorId, Math.min(16, values.length))];
            }
        }

        private static final class ModernRuntimeMethods {
            private final Class<?> sessionClass;
            private final Method getWorldManager;
            private final Method getCurrentWorld;
            private final Method getCurrentWaypointSet;
            private final Method addWaypoint;
            private final Method getWorldManagerIO;
            private final Method saveWorld;

            private ModernRuntimeMethods(Object session, Class<?> waypointClass) throws ReflectiveOperationException {
                sessionClass = session.getClass();
                getWorldManager = sessionClass.getMethod("getWorldManager");
                Class<?> worldManagerClass = getWorldManager.getReturnType();
                getCurrentWorld = worldManagerClass.getMethod("getCurrentWorld");
                Class<?> worldClass = getCurrentWorld.getReturnType();
                getCurrentWaypointSet = worldClass.getMethod("getCurrentWaypointSet");
                Class<?> setClass = getCurrentWaypointSet.getReturnType();
                addWaypoint = setClass.getMethod("add", waypointClass);

                Method io = null;
                Method save = null;
                try {
                    io = sessionClass.getMethod("getWorldManagerIO");
                    save = io.getReturnType().getMethod("saveWorld", worldClass);
                } catch (NoSuchMethodException ignored) {
                    // Saving is optional; Xaero also persists changed sets during its normal cycle.
                }
                getWorldManagerIO = io;
                saveWorld = save;
            }

            private boolean supports(Object session) {
                return sessionClass.isInstance(session);
            }

            private void saveIfSupported(Object session, Object world) throws ReflectiveOperationException {
                if (getWorldManagerIO == null || saveWorld == null) return;
                Object io = getWorldManagerIO.invoke(session);
                if (io != null) saveWorld.invoke(io, world);
            }
        }
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
