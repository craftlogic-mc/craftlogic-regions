package ru.craftlogic.regions.common.command;

import net.minecraft.block.Block;
import net.minecraft.command.CommandException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import ru.craftlogic.api.command.CommandBase;
import ru.craftlogic.api.command.CommandContext;
import ru.craftlogic.api.text.Text;
import ru.craftlogic.api.world.Player;
import ru.craftlogic.regions.RegionManager;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;

public class CommandRegions extends CommandBase {
    public CommandRegions() {
        super("regions", 4,
            "whitelist block",
            "whitelist block <id>",
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
                Block block;
                if (ctx.has("id")) {
                    block = ctx.get("id").asBlock();
                } else {
                    Player player = ctx.senderAsPlayer();
                    EntityPlayerMP entity = player.getEntity();
                    boolean creative = entity.capabilities.isCreativeMode;
                    double distance = creative ? 5 : 4.5;
                    Vec3d eyes = entity.getPositionEyes(1);
                    Vec3d look = entity.getLook(1);
                    Vec3d end = eyes.add(look.x * distance, look.y * distance, look.z * distance);
                    RayTraceResult target = entity.world.rayTraceBlocks(eyes, end, false, false, true);

                    if (target != null && target.typeOfHit == RayTraceResult.Type.BLOCK) {
                        block = entity.world.getBlockState(target.getBlockPos()).getBlock();
                    } else {
                        throw new CommandException("commands.regions.no_block");
                    }
                }
                switch (ctx.action(0)) {
                    case "whitelist": {
                        if (regionManager.whitelistBlockUsage.add(block.getRegistryName())) {
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
                Item item;
                if (ctx.has("id")) {
                    item = ctx.get("id").asItem();
                } else {
                    Player player = ctx.senderAsPlayer();
                    ItemStack heldItem = player.getHeldItem(EnumHand.MAIN_HAND);
                    if (!heldItem.isEmpty()) {
                        item = heldItem.getItem();
                    } else {
                        throw new CommandException("commands.regions.no_item");
                    }
                }
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
