package me.waltom.wavexin.modules.litematicaprinter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class PrinterStandPlanner {
    private PrinterStandPlanner() {
    }

    static List<BlockPos> find(BlockPos target, double reach) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return List.of();

        List<BlockPos> result = new ArrayList<>();
        for (int y = target.getY() - 2; y <= target.getY() + 1; y++) {
            for (int x = target.getX() - 4; x <= target.getX() + 4; x++) {
                for (int z = target.getZ() - 4; z <= target.getZ() + 4; z++) {
                    BlockPos feet = new BlockPos(x, y, z);
                    if (!mc.world.getBlockState(feet).isReplaceable()) continue;
                    if (!mc.world.getBlockState(feet.up()).isReplaceable()) continue;
                    if (mc.world.getBlockState(feet.down()).isReplaceable()) continue;
                    Vec3d eyes = Vec3d.ofBottomCenter(feet).add(0, mc.player.getStandingEyeHeight(), 0);
                    if (eyes.squaredDistanceTo(target.toCenterPos()) > reach * reach) continue;
                    result.add(feet);
                }
            }
        }

        result.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(mc.player.getBlockPos())));
        return result;
    }
}
