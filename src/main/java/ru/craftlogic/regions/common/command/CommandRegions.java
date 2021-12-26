package ru.craftlogic.regions.common.command;

import net.minecraft.block.Block;
import net.minecraft.command.CommandException;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import ru.craftlogic.api.command.CommandBase;
import ru.craftlogic.api.command.CommandContext;
import ru.craftlogic.api.text.Text;
import ru.craftlogic.api.world.Player;
import ru.craftlogic.regions.RegionManager;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

public class CommandRegions extends CommandBase {
    public CommandRegions() {
        super("regions", 4,
                "whitelist block use|break",
                "whitelist block use|break <id>",
                "blacklist item",
                "blacklist item <id>",
                "custom door",
                "custom door <id>",
                "custom chest",
                "custom chest <id>"
        );
        Collections.addAll(aliases, "rgs", "regs");
    }

    @Override
    protected void execute(CommandContext ctx) throws Throwable {
        RegionManager regionManager = ctx.server().getManager(RegionManager.class);
        switch (ctx.action(1)) {
            case "block":
            case "door":
            case "chest": {
                Block block = ctx.has("id") ? ctx.get("id").asBlock() : ctx.getPlayerLookingBlock();
                switch (ctx.action(0)) {
                    case "whitelist": {
                        Set<ResourceLocation> list = ctx.action(2).equals("use")
                                ? regionManager.whitelistBlockUsage
                                : regionManager.whitelistBlockBreakage;

                        if (list.add(block.getRegistryName())) {
                            syncConfiguration(ctx, regionManager);
                        } else {
                            throw new CommandException("commands.regions.already_whitelisted");
                        }
                        break;
                    }
                    case "custom": {
                        if (Objects.equals(ctx.action(1), "door")) {
                            if (!regionManager.doors.add(block.getRegistryName())) {
                                throw new CommandException("commands.regions.already_listed");
                            }
                        } else {
                            if (!regionManager.chests.add(block.getRegistryName())) {
                                throw new CommandException("commands.regions.already_listed");
                            }
                        }
                        syncConfiguration(ctx, regionManager);
                        break;
                    }
                }
                break;
            }
            case "item": {
                Item item = ctx.has("id") ? ctx.get("id").asItem() : ctx.getPlayerHeldItem();
                switch (ctx.action(0)) {
                    case "blacklist": {
                        if (!regionManager.blacklistItemUsage.add(item.getRegistryName())) {
                            throw new CommandException("commands.regions.already_blacklisted");
                        }
                        syncConfiguration(ctx, regionManager);
                        break;
                    }
                }
                break;
            }
        }
    }

    private void syncConfiguration(CommandContext ctx, RegionManager regionManager) throws CommandException {
        for (Player player : ctx.server().getPlayerManager().getAllOnline()) {
            regionManager.syncConfiguration(player);
        }
        try {
            regionManager.saveConfiguration();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            ctx.sendMessage(Text.translation("commands.regions.config_updated").darkGreen());
        }
    }
}
