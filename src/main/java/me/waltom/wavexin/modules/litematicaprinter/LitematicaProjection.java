package me.waltom.wavexin.modules.litematicaprinter;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Predicate;

final class LitematicaProjection {
    private static final String DATA_MANAGER = "fi.dy.masa.litematica.data.DataManager";
    private static final String WORLD_HANDLER = "fi.dy.masa.litematica.world.SchematicWorldHandler";

    private LitematicaProjection() {
    }

    static Selection open(String expectedMinecraftVersion) throws ProjectionException {
        FabricLoader loader = FabricLoader.getInstance();
        ModContainer minecraft = loader.getModContainer("minecraft")
            .orElseThrow(() -> new ProjectionException(Failure.WRONG_MINECRAFT_VERSION, "Minecraft metadata is unavailable"));
        String actualMinecraftVersion = minecraft.getMetadata().getVersion().getFriendlyString();
        if (!expectedMinecraftVersion.equals(actualMinecraftVersion)) {
            throw new ProjectionException(
                Failure.WRONG_MINECRAFT_VERSION,
                "Expected Minecraft " + expectedMinecraftVersion + " but found " + actualMinecraftVersion
            );
        }

        ModContainer litematica = loader.getModContainer("litematica")
            .orElseThrow(() -> new ProjectionException(Failure.MISSING_LITEMATICA, "Litematica is not installed"));
        if (!supportsMinecraft(litematica.getMetadata(), minecraft.getMetadata().getVersion())) {
            throw new ProjectionException(
                Failure.WRONG_LITEMATICA_VERSION,
                "Litematica " + litematica.getMetadata().getVersion().getFriendlyString()
                    + " does not declare support for Minecraft " + expectedMinecraftVersion
            );
        }

        try {
            ClassLoader classLoader = LitematicaProjection.class.getClassLoader();
            Class<?> dataManager = Class.forName(DATA_MANAGER, true, classLoader);
            Object manager = dataManager.getMethod("getSchematicPlacementManager").invoke(null);
            Method selectedPlacementMethod = manager.getClass().getMethod("getSelectedSchematicPlacement");
            Object placement = selectedPlacementMethod.invoke(manager);
            if (placement == null) {
                throw new ProjectionException(Failure.NO_SELECTED_PLACEMENT, "No Litematica placement is selected");
            }

            if (!(boolean) placement.getClass().getMethod("isEnabled").invoke(placement)) {
                throw new ProjectionException(Failure.DISABLED_PLACEMENT, "The selected Litematica placement is disabled");
            }

            Object box = enclosingBox(placement);
            if (box == null) box = mergedSubRegionBox(placement, classLoader);
            if (box == null) {
                throw new ProjectionException(Failure.EMPTY_PLACEMENT, "The selected Litematica placement has no enabled regions");
            }

            BlockPos pos1 = (BlockPos) box.getClass().getMethod("getPos1").invoke(box);
            BlockPos pos2 = (BlockPos) box.getClass().getMethod("getPos2").invoke(box);
            Class<?> worldHandler = Class.forName(WORLD_HANDLER, true, classLoader);
            World schematicWorld = (World) worldHandler.getMethod("getSchematicWorld").invoke(null);
            if (schematicWorld == null) {
                Object instance = worldHandler.getField("INSTANCE").get(null);
                worldHandler.getMethod("recreateSchematicWorld", boolean.class).invoke(instance, false);
                schematicWorld = (World) worldHandler.getMethod("getSchematicWorld").invoke(null);
            }
            if (schematicWorld == null) {
                throw new ProjectionException(Failure.API_UNAVAILABLE, "Litematica schematic world is unavailable");
            }

            return new Selection(
                manager,
                placement,
                selectedPlacementMethod,
                String.valueOf(placement.getClass().getMethod("getName").invoke(placement)),
                litematica.getMetadata().getVersion().getFriendlyString(),
                min(pos1, pos2),
                max(pos1, pos2),
                schematicWorld,
                countEntries(placement, "getEntityListForRegion"),
                countEntries(placement, "getBlockEntityMapForRegion"),
                placementSignature(placement)
            );
        } catch (ProjectionException e) {
            throw e;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new ProjectionException(Failure.API_UNAVAILABLE, rootMessage(e), e);
        }
    }

    private static boolean supportsMinecraft(ModMetadata metadata, Version minecraftVersion) {
        for (ModDependency dependency : metadata.getDependencies()) {
            if (!"minecraft".equals(dependency.getModId()) || dependency.getKind() != ModDependency.Kind.DEPENDS) continue;
            if (dependency.matches(minecraftVersion)) return true;
        }
        return false;
    }

    private static Object enclosingBox(Object placement) throws ReflectiveOperationException {
        try {
            return placement.getClass().getMethod("getEclosingBox").invoke(placement);
        } catch (NoSuchMethodException ignored) {
            return placement.getClass().getMethod("getEnclosingBox").invoke(placement);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object mergedSubRegionBox(Object placement, ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> required = Class.forName(
            "fi.dy.masa.litematica.schematic.placement.SubRegionPlacement$RequiredEnabled",
            true,
            classLoader
        );
        Object enabled = Enum.valueOf((Class<? extends Enum>) required, "PLACEMENT_ENABLED");
        Object values = placement.getClass().getMethod("getSubRegionBoxes", required).invoke(placement, enabled);
        if (!(values instanceof Map<?, ?> boxes) || boxes.isEmpty()) return null;

        BlockPos min = null;
        BlockPos max = null;
        for (Object box : boxes.values()) {
            if (box == null) continue;
            BlockPos pos1 = (BlockPos) box.getClass().getMethod("getPos1").invoke(box);
            BlockPos pos2 = (BlockPos) box.getClass().getMethod("getPos2").invoke(box);
            if (pos1 == null || pos2 == null) continue;

            BlockPos localMin = min(pos1, pos2);
            BlockPos localMax = max(pos1, pos2);
            min = min == null ? localMin : min(min, localMin);
            max = max == null ? localMax : max(max, localMax);
        }

        if (min == null || max == null) return null;
        Class<?> boxClass = Class.forName("fi.dy.masa.litematica.selection.Box", true, classLoader);
        return boxClass.getConstructor(BlockPos.class, BlockPos.class, String.class)
            .newInstance(min, max, "WaveXin Litematica Printer");
    }

    private static int countEntries(Object placement, String methodName) throws ReflectiveOperationException {
        Object schematic = placement.getClass().getMethod("getSchematic").invoke(placement);
        Map<?, ?> regions = (Map<?, ?>) placement.getClass().getMethod("getEnabledRelativeSubRegionPlacements").invoke(placement);
        Method entriesForRegion = schematic.getClass().getMethod(methodName, String.class);
        int count = 0;
        for (Object name : regions.keySet()) {
            Object entries = entriesForRegion.invoke(schematic, String.valueOf(name));
            if (entries instanceof Collection<?> collection) count += collection.size();
            else if (entries instanceof Map<?, ?> map) count += map.size();
        }
        return count;
    }

    private static String placementSignature(Object placement) throws ReflectiveOperationException {
        StringBuilder result = new StringBuilder();
        Object box = enclosingBox(placement);
        if (box != null) {
            result.append(box.getClass().getMethod("getPos1").invoke(box)).append('|');
            result.append(box.getClass().getMethod("getPos2").invoke(box)).append('|');
        }
        for (String methodName : new String[]{"getOrigin", "getRotation", "getMirror"}) {
            try {
                result.append(placement.getClass().getMethod(methodName).invoke(placement));
            } catch (NoSuchMethodException ignored) {
                result.append("unavailable");
            }
            result.append('|');
        }
        return result.toString();
    }

    private static BlockPos min(BlockPos first, BlockPos second) {
        return new BlockPos(
            Math.min(first.getX(), second.getX()),
            Math.min(first.getY(), second.getY()),
            Math.min(first.getZ(), second.getZ())
        );
    }

    private static BlockPos max(BlockPos first, BlockPos second) {
        return new BlockPos(
            Math.max(first.getX(), second.getX()),
            Math.max(first.getY(), second.getY()),
            Math.max(first.getZ(), second.getZ())
        );
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    record Selection(
        Object manager,
        Object placement,
        Method selectedPlacementMethod,
        String name,
        String litematicaVersion,
        BlockPos min,
        BlockPos max,
        World schematicWorld,
        int entityCount,
        int blockEntityCount,
        String placementSignature
    ) {
        BlockState targetState(BlockPos pos) {
            return schematicWorld.getBlockState(pos);
        }

        boolean isStillSelected() {
            try {
                return selectedPlacementMethod.invoke(manager) == placement
                    && placementSignature.equals(LitematicaProjection.placementSignature(placement));
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }

        OptionalLong remainingBlockCount() {
            try {
                Object materialList = placement.getClass().getMethod("getMaterialList").invoke(placement);
                if (materialList == null) return OptionalLong.empty();
                invokeIfPresent(materialList, "reCreateMaterialList");

                for (String methodName : new String[]{"getCountMissing", "getMissingCount", "getMaterialCountMissing"}) {
                    Object value = invokeIfPresent(materialList, methodName);
                    if (value instanceof Number number) return OptionalLong.of(Math.max(0L, number.longValue()));
                }
                for (String methodName : new String[]{"getMaterialsMissing", "getMaterialsAll", "getMaterialListAll"}) {
                    Object value = invokeIfPresent(materialList, methodName);
                    if (value instanceof Iterable<?> entries) return OptionalLong.of(sumMissing(entries));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            return OptionalLong.empty();
        }

        LayerFilter layerFilter() {
            try {
                Class<?> dataManager = Class.forName(DATA_MANAGER, true, LitematicaProjection.class.getClassLoader());
                Object range = dataManager.getMethod("getRenderLayerRange").invoke(null);
                if (range == null) return LayerFilter.all();

                Object mode = range.getClass().getMethod("getLayerMode").invoke(range);
                String modeName = mode instanceof Enum<?> value ? value.name() : String.valueOf(mode);
                if (mode == null || "ALL".equals(modeName)) return LayerFilter.all();

                Method contains = range.getClass().getMethod("isPositionWithinRange", BlockPos.class);
                String signature = modeName + "|"
                    + invokeIfPresent(range, "getAxis") + "|"
                    + invokeIfPresent(range, "getLayerMin") + "|"
                    + invokeIfPresent(range, "getLayerMax");
                Predicate<BlockPos> predicate = pos -> {
                    try {
                        return Boolean.TRUE.equals(contains.invoke(range, pos));
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        return true;
                    }
                };
                return new LayerFilter(true, signature, predicate);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                return LayerFilter.all();
            }
        }

        private static Object invokeIfPresent(Object owner, String methodName) throws ReflectiveOperationException {
            try {
                return owner.getClass().getMethod(methodName).invoke(owner);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }

        private static long sumMissing(Iterable<?> entries) throws ReflectiveOperationException {
            long total = 0L;
            for (Object entry : entries) {
                if (entry == null) continue;
                Number missing = number(entry, "getCountMissing", "getMissingCount");
                Number mismatched = number(entry, "getCountMismatch", "getCountMismatched");
                if (missing != null) total += Math.max(0L, missing.longValue());
                if (mismatched != null) total += Math.max(0L, mismatched.longValue());
            }
            return total;
        }

        private static Number number(Object owner, String... methodNames) throws ReflectiveOperationException {
            for (String methodName : methodNames) {
                Object value = invokeIfPresent(owner, methodName);
                if (value instanceof Number number) return number;
            }
            return null;
        }
    }

    record LayerFilter(boolean enabled, String signature, Predicate<BlockPos> predicate) {
        private static final LayerFilter ALL = new LayerFilter(false, "ALL", pos -> true);

        static LayerFilter all() {
            return ALL;
        }

        boolean includes(BlockPos pos) {
            return predicate.test(pos);
        }
    }

    enum Failure {
        MISSING_LITEMATICA,
        WRONG_MINECRAFT_VERSION,
        WRONG_LITEMATICA_VERSION,
        NO_SELECTED_PLACEMENT,
        DISABLED_PLACEMENT,
        EMPTY_PLACEMENT,
        API_UNAVAILABLE
    }

    static final class ProjectionException extends Exception {
        private final Failure failure;

        ProjectionException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        ProjectionException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }
}
