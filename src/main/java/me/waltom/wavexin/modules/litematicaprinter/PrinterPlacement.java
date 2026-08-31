package me.waltom.wavexin.modules.litematicaprinter;

import me.waltom.wavexin.mixins.MixinBlockItemAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.state.property.Properties;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class PrinterPlacement {
    private static final float[] YAWS = {0.0F, 90.0F, 180.0F, -90.0F};
    private static final float[] PITCHES = {0.0F, 89.0F, -89.0F};
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final PrinterInventory inventory;

    PrinterPlacement(PrinterInventory inventory) {
        this.inventory = inventory;
    }

    Candidate findCandidate(
        BlockPos target,
        BlockState expected,
        double reach,
        boolean requireReach,
        Set<BlockPos> plannedSupports,
        Predicate<BlockPos> stableSupport,
        boolean allowInventoryPull,
        boolean requireMaterial
    ) {
        if (mc.player == null || mc.world == null) return null;
        Item item = expected.getBlock().asItem();
        int slot = inventory.findSlot(item, allowInventoryPull);
        if (slot < 0 && requireMaterial) return null;
        ItemStack stack = slot < 0 ? new ItemStack(item) : mc.player.getInventory().getStack(slot);
        if (!(stack.getItem() instanceof BlockItem)) return null;

        for (HitCandidate hit : hitCandidates(target, expected, plannedSupports, stableSupport)) {
            if (requireReach && mc.player.getEyePos().squaredDistanceTo(hit.packetHit().getPos()) > reach * reach) continue;
            for (boolean sneak : sneakStates(hit)) {
                for (Orientation orientation : orientations()) {
                    BlockState predicted = simulate(stack, hit.simulationHit(), orientation.yaw(), orientation.pitch(), sneak);
                    if (predicted != null && PrinterState.compatibleDuringBuild(expected, predicted)) {
                        return candidate(hit, orientation, sneak);
                    }
                }
            }
        }
        return null;
    }

    Candidate findChestMateCandidate(
        BlockPos target,
        BlockState expected,
        BlockPos partner,
        boolean partnerPlanned,
        double reach
    ) {
        if (mc.player == null || mc.world == null || !(expected.getBlock() instanceof ChestBlock)
            || !expected.contains(Properties.CHEST_TYPE) || !expected.contains(Properties.HORIZONTAL_FACING)) return null;

        Direction connection = PrinterState.chestConnection(
            expected.get(Properties.HORIZONTAL_FACING), expected.get(Properties.CHEST_TYPE)
        );
        if (!target.offset(connection).equals(partner)) return null;
        Direction clickedSide = connection.getOpposite();
        BlockHitResult packetHit = new BlockHitResult(faceCenter(partner, clickedSide), clickedSide, partner, false);
        if (mc.player.getEyePos().squaredDistanceTo(packetHit.getPos()) > reach * reach) return null;
        BlockHitResult simulationHit = partnerPlanned
            ? new BlockHitResult(Vec3d.ofCenter(target), clickedSide, target, false)
            : packetHit;
        HitCandidate hit = new HitCandidate(
            packetHit,
            simulationHit,
            true,
            true,
            true,
            partner,
            partnerPlanned ? PrinterBatchPlan.SupportSource.BATCH : PrinterBatchPlan.SupportSource.REAL
        );
        ItemStack stack = new ItemStack(expected.getBlock().asItem());
        for (Orientation orientation : orientations()) {
            BlockState predicted = simulate(stack, simulationHit, orientation.yaw(), orientation.pitch(), true);
            if (predicted != null && PrinterState.compatibleDuringBuild(expected, predicted)) {
                return candidate(hit, orientation, true);
            }
        }
        return null;
    }

    boolean place(BlockState expected, Candidate candidate, boolean allowInventoryPull) {
        if (mc.player == null || mc.interactionManager == null || candidate == null) return false;
        try (PrinterInventory.Lease ignored = inventory.acquire(expected.getBlock().asItem(), allowInventoryPull)) {
            if (ignored == null) return false;
            return interact(candidate);
        }
    }

    PlacementBatch beginBatch(boolean allowInventoryPull) {
        return new PlacementBatch(allowInventoryPull);
    }

    private BlockState simulate(ItemStack stack, BlockHitResult hit, float yaw, float pitch, boolean sneak) {
        ClientPlayerEntity player = mc.player;
        float previousYaw = player.getYaw();
        float previousPitch = player.getPitch();
        boolean previousSneak = player.isSneaking();
        try {
            player.setYaw(yaw);
            player.setPitch(pitch);
            player.setSneaking(sneak);
            ItemPlacementContext context = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit);
            if (!context.canPlace()) return null;
            return ((MixinBlockItemAccessor) (Object) stack.getItem()).wavexin$invokeGetPlacementState(context);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            player.setYaw(previousYaw);
            player.setPitch(previousPitch);
            player.setSneaking(previousSneak);
        }
    }

    private boolean interact(Candidate candidate) {
        ClientPlayerEntity player = mc.player;
        float previousYaw = player.getYaw();
        float previousPitch = player.getPitch();
        boolean previousSneak = player.isSneaking();
        try {
            setSneaking(player, candidate.sneak());
            if (candidate.rotate()) sendLook(player, candidate.yaw(), candidate.pitch());
            ActionResult result = mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, candidate.hit());
            if (!result.isAccepted()) return false;
            return true;
        } finally {
            if (candidate.rotate()) sendLook(player, previousYaw, previousPitch);
            setSneaking(player, previousSneak);
        }
    }

    private static void sendLook(ClientPlayerEntity player, float yaw, float pitch) {
        player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
            yaw, pitch, player.isOnGround()
        ));
    }

    private void setSneaking(ClientPlayerEntity player, boolean sneaking) {
        if (player.isSneaking() == sneaking) return;
        player.setSneaking(sneaking);
        player.networkHandler.sendPacket(new PlayerInputC2SPacket(
            player.input.movementSideways,
            player.input.movementForward,
            player.input.jumping,
            sneaking
        ));
    }

    private List<HitCandidate> hitCandidates(
        BlockPos target,
        BlockState expected,
        Set<BlockPos> plannedSupports,
        Predicate<BlockPos> stableSupport
    ) {
        if (expected.getBlock() instanceof HopperBlock && expected.contains(Properties.FACING)) {
            Direction facing = expected.get(Properties.FACING);
            if (!validHopperFacing(facing)) return List.of();
            BlockPos support = hopperSupport(target, facing);
            if (!stableSupport.test(support)) return List.of();
            Direction clickedSide = hopperClickedSide(facing);
            BlockState supportState = mc.world.getBlockState(support);
            boolean interactive = isInteractive(supportState, support);
            BlockHitResult hit = new BlockHitResult(faceCenter(support, clickedSide), clickedSide, support, false);
            return List.of(new HitCandidate(
                hit, hit, interactive, interactive, interactive, support, PrinterBatchPlan.SupportSource.REAL
            ));
        }

        List<HitCandidate> result = new ArrayList<>(48);
        BlockState current = mc.world.getBlockState(target);
        if (current.getBlock() == expected.getBlock()) {
            for (Direction side : Direction.values()) {
                Vec3d face = faceCenter(target, side);
                BlockHitResult hit = new BlockHitResult(face, side, target, false);
                boolean interactive = isInteractive(current, target);
                result.add(new HitCandidate(
                    hit, hit, interactive, interactive, interactive, target, PrinterBatchPlan.SupportSource.REAL
                ));
            }
        }

        boolean requiresRealSupport = PrinterState.requiresRealSupport(expected);
        for (Direction targetToNeighbor : Direction.values()) {
            BlockPos neighbor = target.offset(targetToNeighbor);
            Direction clickedSide = targetToNeighbor.getOpposite();
            Vec3d center = faceCenter(neighbor, clickedSide);
            boolean planned = !requiresRealSupport && plannedSupports.contains(neighbor);
            if (!planned && !stableSupport.test(neighbor)) continue;

            BlockState neighborState = mc.world.getBlockState(neighbor);
            boolean interactive = !planned && isInteractive(neighborState, neighbor);
            BlockHitResult packetHit = new BlockHitResult(center, clickedSide, neighbor, false);
            BlockHitResult simulationHit = planned
                ? new BlockHitResult(Vec3d.ofCenter(target), clickedSide, target, false)
                : packetHit;
            PrinterBatchPlan.SupportSource supportSource = planned
                ? PrinterBatchPlan.SupportSource.BATCH
                : PrinterBatchPlan.SupportSource.REAL;
            result.add(new HitCandidate(
                packetHit, simulationHit, interactive, interactive, interactive, neighbor, supportSource
            ));
            if (clickedSide.getAxis().isHorizontal()) {
                addOffsetCandidate(result, packetHit, simulationHit, center.add(0, 0.35, 0), clickedSide, neighbor, interactive, supportSource);
                addOffsetCandidate(result, packetHit, simulationHit, center.add(0, -0.35, 0), clickedSide, neighbor, interactive, supportSource);
            } else {
                addOffsetCandidate(result, packetHit, simulationHit, center.add(0.3, 0, 0), clickedSide, neighbor, interactive, supportSource);
                addOffsetCandidate(result, packetHit, simulationHit, center.add(0, 0, 0.3), clickedSide, neighbor, interactive, supportSource);
            }
        }
        return result;
    }

    static BlockPos hopperSupport(BlockPos target, Direction facing) {
        return target.offset(facing);
    }

    static Direction hopperClickedSide(Direction facing) {
        return facing.getOpposite();
    }

    static boolean validHopperFacing(Direction facing) {
        return facing != Direction.UP;
    }

    private static void addOffsetCandidate(
        List<HitCandidate> result,
        BlockHitResult packetHit,
        BlockHitResult simulationHit,
        Vec3d packetPosition,
        Direction side,
        BlockPos support,
        boolean interactive,
        PrinterBatchPlan.SupportSource supportSource
    ) {
        BlockHitResult offsetPacket = new BlockHitResult(packetPosition, side, support, false);
        BlockHitResult offsetSimulation = simulationHit == packetHit
            ? offsetPacket
            : new BlockHitResult(
                simulationHit.getPos().add(packetPosition.subtract(packetHit.getPos())),
                side,
                simulationHit.getBlockPos(),
                false
            );
        result.add(new HitCandidate(
            offsetPacket, offsetSimulation, interactive, interactive, interactive, support, supportSource
        ));
    }

    private static Vec3d faceCenter(BlockPos pos, Direction side) {
        return Vec3d.ofCenter(pos).add(
            side.getOffsetX() * 0.5,
            side.getOffsetY() * 0.5,
            side.getOffsetZ() * 0.5
        );
    }

    private boolean isInteractive(BlockState state, BlockPos pos) {
        if (state.createScreenHandlerFactory(mc.world, pos) != null) return true;
        String path = state.getBlock().getTranslationKey();
        return path.contains("chest")
            || path.contains("barrel")
            || path.contains("furnace")
            || path.contains("hopper")
            || path.contains("dispenser")
            || path.contains("dropper")
            || path.contains("shulker_box")
            || path.contains("crafting_table")
            || path.contains("brewing_stand")
            || path.contains("enchanting_table")
            || path.contains("anvil")
            || path.contains("beacon")
            || path.contains("lectern")
            || path.contains("stonecutter")
            || path.contains("loom")
            || path.contains("cartography_table")
            || path.contains("smithing_table")
            || path.contains("grindstone")
            || path.contains("jukebox")
            || path.contains("note_block")
            || path.contains("door")
            || path.contains("trapdoor")
            || path.contains("fence_gate")
            || path.contains("button")
            || path.contains("lever");
    }

    private boolean[] sneakStates(HitCandidate hit) {
        return hit.forceSneak() ? new boolean[] {true} : new boolean[] {hit.sneak(), !hit.sneak()};
    }

    private List<Orientation> orientations() {
        List<Orientation> result = new ArrayList<>(1 + YAWS.length * PITCHES.length);
        addOrientation(result, mc.player.getYaw(), mc.player.getPitch());
        for (float pitch : PITCHES) {
            for (float yaw : YAWS) addOrientation(result, yaw, pitch);
        }
        return result;
    }

    private static void addOrientation(List<Orientation> result, float yaw, float pitch) {
        for (Orientation orientation : result) {
            if (sameRotation(orientation.yaw(), yaw) && Math.abs(orientation.pitch() - pitch) < 0.01F) return;
        }
        result.add(new Orientation(yaw, pitch));
    }

    private Candidate candidate(HitCandidate hit, Orientation orientation, boolean sneak) {
        boolean rotate = !sameRotation(mc.player.getYaw(), orientation.yaw())
            || Math.abs(mc.player.getPitch() - orientation.pitch()) >= 0.01F;
        return new Candidate(
            hit.packetHit(), orientation.yaw(), orientation.pitch(), sneak, rotate,
            hit.interactive(), hit.support(), hit.supportSource()
        );
    }

    static boolean sameRotation(float first, float second) {
        return Math.abs(MathHelper.wrapDegrees(first - second)) < 0.01F;
    }

    record Candidate(
        BlockHitResult hit,
        float yaw,
        float pitch,
        boolean sneak,
        boolean rotate,
        boolean interactiveSupport,
        BlockPos support,
        PrinterBatchPlan.SupportSource supportSource
    ) {
        Candidate {
            support = support == null ? null : support.toImmutable();
        }
    }

    final class PlacementBatch implements AutoCloseable {
        private final boolean allowInventoryPull;
        private PrinterInventory.Lease lease;
        private Item leasedItem;

        private PlacementBatch(boolean allowInventoryPull) {
            this.allowInventoryPull = allowInventoryPull;
        }

        boolean place(BlockState expected, Candidate candidate) {
            if (mc.player == null || mc.interactionManager == null || candidate == null) return false;
            Item item = expected.getBlock().asItem();
            if (lease == null || item != leasedItem) {
                closeLease();
                lease = inventory.acquire(item, allowInventoryPull);
                leasedItem = item;
            }
            return lease != null && interact(candidate);
        }

        @Override
        public void close() {
            closeLease();
        }

        private void closeLease() {
            if (lease != null) lease.close();
            lease = null;
            leasedItem = null;
        }
    }

    private record HitCandidate(
        BlockHitResult packetHit,
        BlockHitResult simulationHit,
        boolean sneak,
        boolean forceSneak,
        boolean interactive,
        BlockPos support,
        PrinterBatchPlan.SupportSource supportSource
    ) {
    }

    private record Orientation(float yaw, float pitch) {
    }
}
