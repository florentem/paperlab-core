package io.papermc.paper.lab.tick;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerLevelTickRateManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * Coordinator for independent per-world tick rate and freeze (/carpet perWorldTick).
 */
public final class LabPerWorldTick {

    public static volatile boolean enabled = false;

    private LabPerWorldTick() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(final boolean value, final MinecraftServer server) {
        if (enabled == value) {
            return;
        }
        enabled = value;

        if (server == null) {
            return;
        }

        if (value) {
            // false -> true:
            // Seed every world with the server's current global state (tick rate, freeze) so
            // that they all start in agreement.
            final ServerTickRateManager global = server.tickRateManager();
            for (final ServerLevel level : server.getAllLevels()) {
                final ServerLevelTickRateManager local = level.perWorldTickRateManager();
                if (local != null) {
                    local.setTickRate(global.tickrate());
                    local.setFrozen(global.isFrozen());
                    local.stopStepping();
                    local.stopSprinting();
                }
            }
        } else {
            // true -> false:
            // The server's global state is inherited from the overworld.
            final ServerLevel overworld = server.overworld();
            final ServerTickRateManager global = server.tickRateManager();
            if (overworld != null && overworld.perWorldTickRateManager() != null) {
                final ServerLevelTickRateManager owManager = overworld.perWorldTickRateManager();
                global.setTickRate(owManager.tickrate());
                global.setFrozen(owManager.isFrozen());
            }

            // Abort sprints and steps in every world.
            for (final ServerLevel level : server.getAllLevels()) {
                final ServerLevelTickRateManager local = level.perWorldTickRateManager();
                if (local != null) {
                    local.stopSprinting();
                    local.stopStepping();
                }
            }

            // Force one up-to-date ticking-state packet out to EVERY player.
            final ClientboundTickingStatePacket statePacket = ClientboundTickingStatePacket.from(global);
            final ClientboundTickingStepPacket stepPacket = ClientboundTickingStepPacket.from(global);
            for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.send(statePacket);
                player.connection.send(stepPacket);
            }
        }

        server.onTickRateChanged();
    }

    public static ServerTickRateManager getManager(final CommandSourceStack source) {
        if (enabled && source.getLevel() != null) {
            return source.getLevel().tickRateManager();
        }
        return source.getServer().tickRateManager();
    }
}
