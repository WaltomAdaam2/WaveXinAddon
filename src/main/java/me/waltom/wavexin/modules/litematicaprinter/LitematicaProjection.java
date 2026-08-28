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

            Object box = placement.getClass().getMethod("getEclosingBox").invoke(placement);
            if (box == null) {
                throw new ProjectionException(Failure.EMPTY_PLACEMENT, "The selected Litematica placement has no enabled regions");
            }

            BlockPos pos1 = (BlockPos) box.getClass().getMethod("getPos1").invoke(box);
            BlockPos pos2 = (BlockPos) box.getClass().getMethod("getPos2").invoke(box);
            World schematicWorld = (World) Class.forName(WORLD_HANDLER, true, classLoader)
                .getMethod("getSchematicWorld")
                .invoke(null);
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
                countEntries(placement, "getBlockEntityMapForRegion")
            );
        } catch (ProjectionException e) {
            throw e;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new ProjectionException(Failure.API_UNAVAILABLE, rootMessage(e), e);
        }
    }

    private static boolean supportsMinecraft(ModMetadata metadata, Version minecraftVersion) {
        boolean declared = false;
        for (ModDependency dependency : metadata.getDependencies()) {
            if (!"minecraft".equals(dependency.getModId()) || dependency.getKind() != ModDependency.Kind.DEPENDS) continue;
            declared = true;
            if (dependency.matches(minecraftVersion)) return true;
        }
        return !declared;
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
        int blockEntityCount
    ) {
        BlockState targetState(BlockPos pos) {
            return schematicWorld.getBlockState(pos);
        }

        boolean isStillSelected() {
            try {
                return selectedPlacementMethod.invoke(manager) == placement;
            } catch (ReflectiveOperationException e) {
                return false;
            }
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
