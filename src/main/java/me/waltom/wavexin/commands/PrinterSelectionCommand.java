package me.waltom.wavexin.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.waltom.wavexin.i18n.WaveXinI18n;
import me.waltom.wavexin.modules.litematicaprinter.LitematicaPrinter;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.CompletableFuture;

public final class PrinterSelectionCommand extends Command {
    private static final int MAX_COORDINATE = 30_000_000;
    private final LitematicaPrinter printer;

    public PrinterSelectionCommand(LitematicaPrinter printer) {
        super("sel", "Sets the two corners of the Litematica Printer restock region.");
        this.printer = printer;
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> showStandingBlock())
            .then(cornerBranch("1", 1))
            .then(cornerBranch("2", 2));
    }

    private LiteralArgumentBuilder<CommandSource> cornerBranch(String name, int corner) {
        return literal(name).then(
            coordinate("x", 0).then(
                coordinate("y", 1).then(
                    coordinate("z", 2).executes(context -> {
                        int x = IntegerArgumentType.getInteger(context, "x");
                        int y = IntegerArgumentType.getInteger(context, "y");
                        int z = IntegerArgumentType.getInteger(context, "z");
                        printer.setSupplyCorner(corner, new BlockPos(x, y, z));
                        info(WaveXinI18n.tr(
                            "message.wavexin.litematica_printer.selection_set",
                            "Restock region point %d set to %d %d %d.",
                            corner, x, y, z
                        ));
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );
    }

    private RequiredArgumentBuilder<CommandSource, Integer> coordinate(String name, int axis) {
        return argument(name, IntegerArgumentType.integer(-MAX_COORDINATE, MAX_COORDINATE))
            .suggests((context, builder) -> suggestStandingCoordinate(builder, axis));
    }

    private CompletableFuture<Suggestions> suggestStandingCoordinate(SuggestionsBuilder builder, int axis) {
        BlockPos pos = standingBlock();
        if (pos != null) {
            int value = axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
            builder.suggest(Integer.toString(value));
        }
        return builder.buildFuture();
    }

    private int showStandingBlock() {
        BlockPos pos = standingBlock();
        if (pos == null) {
            error(WaveXinI18n.tr(
                "error.wavexin.litematica_printer.selection_world_unavailable",
                "Enter a world before selecting the restock region."
            ));
            return 0;
        }

        info(WaveXinI18n.tr(
            "message.wavexin.litematica_printer.selection_usage",
            "Standing block: %d %d %d. Use %s 1 %d %d %d or %s 2 %d %d %d.",
            pos.getX(), pos.getY(), pos.getZ(),
            toString(), pos.getX(), pos.getY(), pos.getZ(),
            toString(), pos.getX(), pos.getY(), pos.getZ()
        ));
        return SINGLE_SUCCESS;
    }

    private BlockPos standingBlock() {
        return mc.player == null ? null : mc.player.getBlockPos().down();
    }
}
