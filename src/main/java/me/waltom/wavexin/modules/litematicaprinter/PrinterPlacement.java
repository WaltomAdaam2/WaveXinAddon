package me.waltom.wavexin.modules.litematicaprinter;

import me.waltom.wavexin.mixins.MixinBlockItemAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

final class PrinterPlacement {
    private static final float[] YAWS = {0.0F, 90.0F, 180.0F, -90.0F};
    private static final float[] PITCHES = {0.0F, 89.0F, -89.0F};
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final PrinterInventory inventory;

    PrinterPlacement(PrinterInventory inventory) {
        this.inventory = inventory;
    }

    Candidate findCandidate(BlockPos target, BlockState expected, double reach, boolean requireReach) {
        if (mc.player == null || mc.world == null) return null;
        Item item = expected.getBlock().asItem();
        int slot = inventory.findSlot(item);
        if (slot < 0) return null;
        ItemStack stack = mc.player.getInventory().getStack(slot);
        if (!(stack.getItem() instanceof BlockItem)) return null;

        for (HitCandidate hit : hitCandidates(target, expected)) {
            if (requireReach && mc.player.getEyePos().squaredDistanceTo(hit.result().getPos()) > reach * reach) continue;
            for (boolean sneak : new boolean[] {hit.sneak(), !hit.sneak()}) {
                for (float pitch : PITCHES) {
                    for (float yaw : YAWS) {
                        BlockState predicted = simulate(stack, hit.result(), yaw, pitch, sneak);
                        if (predicted != null && PrinterState.compatibleDuringBuild(expected, predicted)) {
                            return new Candidate(hit.result(), yaw, pitch, sneak);
                        }
                    }
                }
            }
        }
        return null;
    }

    boolean place(BlockState expected, Candidate candidate) {
        if (mc.player == null || mc.interactionManager == null || candidate == null) return false;
        try (PrinterInventory.Lease ignored = inventory.acquire(expected.getBlock().asItem())) {
            if (ignored == null) return false;
            return interact(candidate);
        }
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
            player.setYaw(candidate.yaw());
            player.setPitch(candidate.pitch());
            player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                candidate.yaw(), candidate.pitch(), player.isOnGround(), player.horizontalCollision
            ));
            ActionResult result = mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, candidate.hit());
            if (!result.isAccepted()) return false;
            player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            return true;
        } finally {
            player.setYaw(previousYaw);
            player.setPitch(previousPitch);
            player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                previousYaw, previousPitch, player.isOnGround(), player.horizontalCollision
            ));
            setSneaking(player, previousSneak);
        }
    }

    private void setSneaking(ClientPlayerEntity player, boolean sneaking) {
        if (player.isSneaking() == sneaking) return;
        player.setSneaking(sneaking);
        PlayerInput input = player.input.playerInput;
        player.networkHandler.sendPacket(new PlayerInputC2SPacket(new PlayerInput(
            input.forward(), input.backward(), input.left(), input.right(), input.jump(), sneaking, input.sprint()
        )));
    }

    private List<HitCandidate> hitCandidates(BlockPos target, BlockState expected) {
        List<HitCandidate> result = new ArrayList<>(48);
        BlockState current = mc.world.getBlockState(target);
        if (current.getBlock() == expected.getBlock()) {
            for (Direction side : Direction.values()) {
                Vec3d face = faceCenter(target, side);
                result.add(new HitCandidate(new BlockHitResult(face, side, target, false), false));
            }
        }

        for (Direction targetToNeighbor : Direction.values()) {
            BlockPos neighbor = target.offset(targetToNeighbor);
            BlockState neighborState = mc.world.getBlockState(neighbor);
            if (neighborState.isAir() || neighborState.isReplaceable() || !neighborState.getFluidState().isEmpty()) continue;

            Direction clickedSide = targetToNeighbor.getOpposite();
            Vec3d center = faceCenter(neighbor, clickedSide);
            boolean sneak = isInteractive(neighborState);
            result.add(new HitCandidate(new BlockHitResult(center, clickedSide, neighbor, false), sneak));
            if (clickedSide.getAxis().isHorizontal()) {
                result.add(new HitCandidate(new BlockHitResult(center.add(0, 0.35, 0), clickedSide, neighbor, false), sneak));
                result.add(new HitCandidate(new BlockHitResult(center.add(0, -0.35, 0), clickedSide, neighbor, false), sneak));
            } else {
                result.add(new HitCandidate(new BlockHitResult(center.add(0.3, 0, 0), clickedSide, neighbor, false), sneak));
                result.add(new HitCandidate(new BlockHitResult(center.add(0, 0, 0.3), clickedSide, neighbor, false), sneak));
            }
        }
        return result;
    }

    private static Vec3d faceCenter(BlockPos pos, Direction side) {
        return Vec3d.ofCenter(pos).add(
            side.getOffsetX() * 0.5,
            side.getOffsetY() * 0.5,
            side.getOffsetZ() * 0.5
        );
    }

    private static boolean isInteractive(BlockState state) {
        String path = state.getBlock().getTranslationKey();
        return path.contains("chest")
            || path.contains("barrel")
            || path.contains("furnace")
            || path.contains("shulker_box")
            || path.contains("crafting_table")
            || path.contains("door")
            || path.contains("trapdoor")
            || path.contains("button")
            || path.contains("lever");
    }

    record Candidate(BlockHitResult hit, float yaw, float pitch, boolean sneak) {
    }

    private record HitCandidate(BlockHitResult result, boolean sneak) {
    }
}
