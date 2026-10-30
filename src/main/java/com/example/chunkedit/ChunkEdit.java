package com.example.chunkedit;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /chunkedit scan                 - scan the chunk you're standing in and list what it contains
 * /chunkedit set <block>          - replace EVERY block in the scanned chunk with <block>
 * /chunkedit replace <from> <to>  - swap one block type for another in the scanned chunk
 */
public class ChunkEdit implements ModInitializer {

    private record Scanned(ResourceKey<Level> dimension, ChunkPos chunk) {}

    /** Last chunk each player scanned. */
    private static final Map<UUID, Scanned> SCANS = new HashMap<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) ->
            dispatcher.register(Commands.literal("chunkedit")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("scan")
                    .executes(ChunkEdit::scan))
                .then(Commands.literal("set")
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                        .executes(ChunkEdit::set)))
                .then(Commands.literal("replace")
                    .then(Commands.argument("from", BlockStateArgument.block(buildContext))
                        .then(Commands.argument("to", BlockStateArgument.block(buildContext))
                            .executes(ChunkEdit::replace)))))
        );
    }

    // ---- scan ----

    private static int scan(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        ChunkPos chunk = new ChunkPos(player.blockPosition());

        Map<Block, Integer> counts = new HashMap<>();
        int total = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                    pos.set(x, y, z);
                    Block b = level.getBlockState(pos).getBlock();
                    counts.merge(b, 1, Integer::sum);
                    total++;
                }
            }
        }

        SCANS.put(player.getUUID(), new Scanned(level.dimension(), chunk));

        List<Map.Entry<Block, Integer>> sorted = counts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .toList();

        int finalTotal = total;
        source.sendSuccess(() -> Component.literal(
            "Scanned chunk [" + chunk.x + ", " + chunk.z + "]: " + finalTotal + " blocks, "
                + sorted.size() + " types. Top blocks:"), false);
        for (int i = 0; i < Math.min(10, sorted.size()); i++) {
            Map.Entry<Block, Integer> e = sorted.get(i);
            String id = BuiltInRegistries.BLOCK.getKey(e.getKey()).toString();
            source.sendSuccess(() -> Component.literal(" - " + id + ": " + e.getValue()), false);
        }
        source.sendSuccess(() -> Component.literal(
            "Now run /chunkedit set <block> to replace this whole chunk."), false);
        return sorted.size();
    }

    // ---- set / replace ----

    private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BlockState target = ctx.getArgument("block", BlockInput.class).getState();
        return run(ctx, (pos, state) -> state == target ? null : target, "Set");
    }

    private static int replace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Block from = ctx.getArgument("from", BlockInput.class).getState().getBlock();
        BlockState to = ctx.getArgument("to", BlockInput.class).getState();
        return run(ctx, (pos, state) -> state.is(from) ? to : null, "Replaced");
    }

    private interface Editor {
        /** Return the new state, or null to leave the block alone. */
        BlockState apply(BlockPos pos, BlockState current);
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Editor editor, String verb)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();

        Scanned scanned = SCANS.get(player.getUUID());
        if (scanned == null) {
            source.sendFailure(Component.literal("Scan a chunk first with /chunkedit scan"));
            return 0;
        }
        ServerLevel level = source.getServer().getLevel(scanned.dimension());
        if (level == null) {
            source.sendFailure(Component.literal("That dimension is no longer available."));
            return 0;
        }

        ChunkPos chunk = scanned.chunk();
        level.getChunkAt(chunk.getWorldPosition()); // make sure it's loaded

        int changed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                    pos.set(x, y, z);
                    BlockState next = editor.apply(pos, level.getBlockState(pos));
                    // UPDATE_CLIENTS skips neighbour updates so a full-chunk edit doesn't cascade
                    if (next != null && level.setBlock(pos, next, Block.UPDATE_CLIENTS)) {
                        changed++;
                    }
                }
            }
        }

        int finalChanged = changed;
        source.sendSuccess(() -> Component.literal(
            verb + " " + finalChanged + " blocks in chunk [" + chunk.x + ", " + chunk.z + "]."), true);
        return changed;
    }
}
