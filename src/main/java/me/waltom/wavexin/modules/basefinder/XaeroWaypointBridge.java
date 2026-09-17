package me.waltom.wavexin.modules.basefinder;

import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Cached, optional Xaero Minimap integration for native temporary flight waypoints.
 *
 * <p>The addon intentionally does not link Xaero at compile time. This bridge resolves the
 * supported API shape once, caches reflective members, and then reuses them for every waypoint.
 * It supports both the legacy XaeroMinimapSession API and the newer BuiltInHudModules API.</p>
 */
public final class XaeroWaypointBridge {
    public enum Status {
        CREATED,
        REMOVED,
        MISSING,
        SESSION_NOT_READY,
        WORLD_NOT_READY,
        SET_NOT_READY,
        FAILED
    }

    public record Result(Status status, String detail, WaypointHandle handle) {
        public boolean created() {
            return status == Status.CREATED;
        }

        public boolean removed() {
            return status == Status.REMOVED;
        }
    }

    /** Opaque identity token for one waypoint created through this bridge. */
    public static final class WaypointHandle {
        private final Object waypoint;
        private final Object ownerSet;
        private final Method removeWaypoint;

        private WaypointHandle(Object waypoint, Object ownerSet, Method removeWaypoint) {
            this.waypoint = waypoint;
            this.ownerSet = ownerSet;
            this.removeWaypoint = removeWaypoint;
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

    public Result createTemporary(BlockPos pos, String name, String initials, int colorId) {
        resolve();
        Access local = access;
        if (local == null) return result(Status.MISSING, unavailableReason);
        return local.create(pos, name, initials, colorId);
    }

    /**
     * Removes only the exact object created by this bridge. It never searches by name or position.
     */
    public Result remove(WaypointHandle handle) {
        if (handle == null) return result(Status.FAILED, "waypoint handle is null");
        try {
            handle.removeWaypoint.invoke(handle.ownerSet, handle.waypoint);
            return result(Status.REMOVED, "");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return result(Status.FAILED, messageOf(e));
        }
    }

    // Mark before adding: Xaero's save loop skips native temporary waypoints, even on a crash.
    static WaypointHandle addTemporary(Object waypoint, Object ownerSet, Method setTemporary,
                                       Method addWaypoint, Method removeWaypoint) throws ReflectiveOperationException {
        if (removeWaypoint == null) throw new NoSuchMethodException("waypoint removal is unsupported");
        setTemporary.invoke(waypoint, true);
        addWaypoint.invoke(ownerSet, waypoint);
        return new WaypointHandle(waypoint, ownerSet, removeWaypoint);
    }

    private static Result result(Status status, String detail) {
        return new Result(status, detail, null);
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
        private final Method setTemporary;

        private volatile RuntimeMethods runtime;

        private LegacyAccess() throws ReflectiveOperationException {
            Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            setTemporary = waypointClass.getMethod("setTemporary", boolean.class);
            getCurrentSession = sessionClass.getMethod("getCurrentSession");
            waypointConstructor = waypointClass.getConstructor(
                int.class, int.class, int.class, String.class, String.class, int.class
            );
        }

        @Override
        public Result create(BlockPos pos, String name, String initials, int colorId) {
            try {
                Object currentSession = getCurrentSession.invoke(null);
                if (currentSession == null) return result(Status.SESSION_NOT_READY, "current session is null");

                RuntimeMethods methods = runtime;
                if (methods == null || !methods.supports(currentSession)) {
                    methods = new RuntimeMethods(currentSession, waypointClass);
                    runtime = methods;
                }

                Object processor = methods.getMinimapProcessor.invoke(currentSession);
                Object minimapSession = methods.getSession.invoke(processor);
                if (minimapSession == null) return result(Status.SESSION_NOT_READY, "minimap session is null");

                Object worldManager = methods.getWorldManager.invoke(minimapSession);
                Object currentWorld = methods.getCurrentWorld.invoke(worldManager);
                if (currentWorld == null) return result(Status.WORLD_NOT_READY, "current waypoint world is null");

                Object waypointSet = methods.getCurrentWaypointSet.invoke(currentWorld);
                if (waypointSet == null) return result(Status.SET_NOT_READY, "current waypoint set is null");

                Object waypoint = waypointConstructor.newInstance(
                    pos.getX(), pos.getY(), pos.getZ(), name, initials, colorId
                );
                return new Result(Status.CREATED, "", addTemporary(waypoint, waypointSet, setTemporary,
                    methods.addWaypoint, methods.removeWaypoint));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return result(Status.FAILED, messageOf(e));
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
            private final Method removeWaypoint;

            private RuntimeMethods(Object currentSession, Class<?> waypointClass) throws ReflectiveOperationException {
                currentSessionClass = currentSession.getClass();
                getMinimapProcessor = currentSessionClass.getMethod("getMinimapProcessor");

                Class<?> processorClass = getMinimapProcessor.getReturnType();
                getSession = processorClass.getMethod("getSession");

                Class<?> minimapSessionClass = getSession.getReturnType();
                getWorldManager = minimapSessionClass.getMethod("getWorldManager");

                Class<?> worldManagerClass = getWorldManager.getReturnType();
                getCurrentWorld = worldManagerClass.getMethod("getCurrentWorld");

                Class<?> worldClass = getCurrentWorld.getReturnType();
                getCurrentWaypointSet = worldClass.getMethod("getCurrentWaypointSet");

                Class<?> setClass = getCurrentWaypointSet.getReturnType();
                addWaypoint = setClass.getMethod("add", waypointClass);
                removeWaypoint = findWaypointMethod(setClass, "remove", waypointClass);
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
        private final Method setTemporary;

        private volatile ModernRuntimeMethods runtime;

        private ModernAccess() throws ReflectiveOperationException {
            Class<?> modulesClass = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Field minimapField = modulesClass.getField("MINIMAP");
            minimapModule = minimapField.get(null);
            getCurrentSession = minimapModule.getClass().getMethod("getCurrentSession");

            waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            setTemporary = waypointClass.getMethod("setTemporary", boolean.class);
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
                if (session == null) return result(Status.SESSION_NOT_READY, "current session is null");

                ModernRuntimeMethods methods = runtime;
                if (methods == null || !methods.supports(session)) {
                    methods = new ModernRuntimeMethods(session, waypointClass);
                    runtime = methods;
                }

                Object worldManager = methods.getWorldManager.invoke(session);
                Object currentWorld = methods.getCurrentWorld.invoke(worldManager);
                if (currentWorld == null) return result(Status.WORLD_NOT_READY, "current waypoint world is null");

                Object waypointSet = methods.getCurrentWaypointSet.invoke(currentWorld);
                if (waypointSet == null) return result(Status.SET_NOT_READY, "current waypoint set is null");

                Object color = colorResolver.resolve(colorId);
                Object waypoint = waypointConstructor.newInstance(
                    pos.getX(), pos.getY(), pos.getZ(), name, initials, color, normalPurpose, false
                );
                return new Result(Status.CREATED, "", addTemporary(waypoint, waypointSet, setTemporary,
                    methods.addWaypoint, methods.removeWaypoint));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return result(Status.FAILED, messageOf(e));
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
            private final Method removeWaypoint;

            private ModernRuntimeMethods(Object session, Class<?> waypointClass) throws ReflectiveOperationException {
                sessionClass = session.getClass();
                getWorldManager = sessionClass.getMethod("getWorldManager");
                Class<?> worldManagerClass = getWorldManager.getReturnType();
                getCurrentWorld = worldManagerClass.getMethod("getCurrentWorld");
                Class<?> worldClass = getCurrentWorld.getReturnType();
                getCurrentWaypointSet = worldClass.getMethod("getCurrentWaypointSet");
                Class<?> setClass = getCurrentWaypointSet.getReturnType();
                addWaypoint = setClass.getMethod("add", waypointClass);
                removeWaypoint = findWaypointMethod(setClass, "remove", waypointClass);
            }

            private boolean supports(Object session) {
                return sessionClass.isInstance(session);
            }
        }
    }

    private static Method findWaypointMethod(Class<?> type, String name, Class<?> waypointClass) {
        try {
            return type.getMethod(name, waypointClass);
        } catch (NoSuchMethodException ignored) {
            try {
                return type.getMethod(name, Object.class);
            } catch (NoSuchMethodException unsupported) {
                return null;
            }
        }
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
