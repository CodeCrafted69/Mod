package com.example.chunkedit;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * /chunkedit <block>   - scans the chunk you're standing in and replaces every non-air block with <block>.
 *                        The work is spread over many ticks so the server doesn't lag.
 * /chunkedit cancel    - stop your running job (blocks already changed stay changed).
 */
public class ChunkEdit implements ModInitializer {

    /** Max time spent editing per server tick. Lower = smoother, but the job takes longer. */
    private static final long BUDGET_NANOS = 4_000_000L; // 4 ms

    /** Skip neighbour updates, shape updates and item drops: much cheaper per block. */
    private static final int FLAGS =
        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private static final List<Job> JOBS = new ArrayList<>();

    private static final class Job {
        final ServerPlayer player;
        final ServerLevel level;
        final ChunkPos chunk;
        final BlockState target;
        final int minY;
        final int height;
        final long total;
        long index = 0;
        int changed = 0;
        int air = 0;
        final Map<Block, Integer> found = new HashMap<>();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        Job(ServerPlayer player, ServerLevel level, ChunkPos chunk, BlockState target) {
            this.player = player;
            this.level = level;
            this.chunk = chunk;
            this.target = target;
            this.minY = level.getMinY();
            this.height = level.getMaxY() - level.getMinY() + 1;
            this.total = 16L * 16L * height;
        }

        boolean done() {
            return index >= total;
        }
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) ->
            dispatcher.register(Commands.literal("chunkedit")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("cancel").executes(ChunkEdit::cancel))
                .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                    .executes(ChunkEdit::start)))
        );
        ServerTickEvents.END_SERVER_TICK.register(ChunkEdit::tick);
    }

    // ---- command ----

    private static int start(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();

        for (Job j : JOBS) {
            if (j.player == player) {
                source.sendFailure(Component.literal(
                    "You already have a job running. Wait for it or use /chunkedit cancel."));
                return 0;
            }
        }

        BlockState target = ctx.getArgument("block", BlockInput.class).getState();
        ServerLevel level = source.getLevel();
        BlockPos playerPos = player.blockPosition();
        ChunkPos chunk = new ChunkPos(playerPos.getX() >> 4, playerPos.getZ() >> 4);

        JOBS.add(new Job(player, level, chunk, target));
        source.sendSuccess(() -> Component.literal(
            "Scanning chunk [" + chunk.x() + ", " + chunk.z() + "] and replacing it with "
                + BuiltInRegistries.BLOCK.getKey(target.getBlock()) + "..."), true);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job j = it.next();
            if (j.player == player) {
                it.remove();
                int changed = j.changed;
                source.sendSuccess(() -> Component.literal(
                    "Cancelled. " + changed + " blocks had already been changed."), false);
                return 1;
            }
        }
        source.sendFailure(Component.literal("You have no job running."));
        return 0;
    }

    // ---- per-tick work ----

    private static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + BUDGET_NANOS;
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job job = it.next();
            step(job, deadline);
            if (job.done()) {
                it.remove();
                finish(job);
            }
            if (System.nanoTime() >= deadline) {
                break; // remaining jobs continue next tick
            }
        }
    }

    private static void step(Job job, long deadline) {
        int baseX = job.chunk.getMinBlockX();
        int baseZ = job.chunk.getMinBlockZ();

        while (!job.done()) {
            long i = job.index++;
            int y = job.minY + (int) (i % job.height);
            long column = i / job.height;
            job.pos.set(baseX + (int) (column % 16), y, baseZ + (int) (column / 16));

            BlockState state = job.level.getBlockState(job.pos);
            if (state.isAir()) {
                job.air++; // air is never touched
            } else {
                job.found.merge(state.getBlock(), 1, Integer::sum);
                if (state != job.target && job.level.setBlock(job.pos, job.target, FLAGS)) {
                    job.changed++;
                }
            }

            if ((i & 63) == 0 && System.nanoTime() >= deadline) {
                return;
            }
        }
    }

    private static void finish(Job job) {
        ServerPlayer p = job.player;
        p.sendSystemMessage(Component.literal(
            "Done. Chunk [" + job.chunk.x() + ", " + job.chunk.z() + "]: changed " + job.changed
                + " blocks (" + job.air + " air blocks left alone). It contained:"));

        job.found.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(8)
            .forEach(e -> p.sendSystemMessage(Component.literal(
                " - " + BuiltInRegistries.BLOCK.getKey(e.getKey()) + ": " + e.getValue())));
    }
}
