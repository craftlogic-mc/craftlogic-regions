package ru.craftlogic.regions.common.command;

import net.minecraft.command.CommandException;
import net.minecraft.util.text.TextFormatting;
import ru.craftlogic.api.command.CommandBase;
import ru.craftlogic.api.command.CommandContext;
import ru.craftlogic.api.server.PlayerManager;
import ru.craftlogic.api.server.Server;
import ru.craftlogic.api.text.Text;
import ru.craftlogic.api.world.*;
import ru.craftlogic.regions.RegionManager;
import ru.craftlogic.regions.WorldRegionManager;

import java.io.IOException;
import java.util.*;

public class CommandRegion extends CommandBase {
    public CommandRegion() {
        super("region", 1,
            "delete|pvp",
            "expel|transfer <target:OfflinePlayer>",
            "invite <target:OfflinePlayer>",
            "invite <target:OfflinePlayer> <abilities>...",
            "teleport <region:Region>",
            "<region:Region>",
            ""
        );
        Collections.addAll(aliases, "rg", "reg");
    }

    @Override
    protected void execute(CommandContext ctx) throws Throwable {
        Server server = ctx.server();
        RegionManager regionManager = server.getManager(RegionManager.class);
        if (ctx.hasAction(0)) {
            switch (ctx.action(0)) {
                case "teleport": {
                    Player sender = ctx.senderAsPlayer();
                    UUID regionId = ctx.get("region").asUUID();
                    WorldRegionManager.Region region = regionManager.getRegion(regionId);
                    if (region != null) {
                        if ((region.isOwner(sender) || region.isMember(sender)) && sender.hasPermission("commands.region.teleport")
                            || sender.hasPermission("commands.region.admin.teleport")) {

                            sender.teleport(region.getStart());
                        } else {
                            throw new CommandException("commands.region.not_owning");
                        }
                    } else {
                        throw new CommandException("commands.region.unknown", regionId.toString());
                    }
                    break;
                }
                case "invite": {
                    Player sender = ctx.senderAsPlayer();
                    OfflinePlayer target = ctx.get("target").asOfflinePlayer();
                    WorldRegionManager.Region region = regionManager.getRegion(sender.getLocation());
                    if (region != null) {
                        boolean admin = sender.hasPermission("region.admin.invite");
                        if (!region.isOwner(sender) && !region.isMember(sender) && !admin) {
                            throw new CommandException("commands.region.not_owning");
                        }
                        if (target == sender && !admin) {
                            throw new CommandException("commands.region.yourself");
                        }
                        Set<WorldRegionManager.RegionAbility> abilities;
                        if (ctx.has("abilities")) {
                            abilities = new HashSet<>();
                            for (String a : ctx.get("abilities").asString().split(",")) {
                                try {
                                    abilities.add(WorldRegionManager.RegionAbility.valueOf(a.toUpperCase()));
                                } catch (IllegalArgumentException e) {
                                    StringBuilder allowed = new StringBuilder();
                                    for (WorldRegionManager.RegionAbility ability : WorldRegionManager.RegionAbility.values()) {
                                        if (allowed.length() == 0) {
                                            allowed = new StringBuilder(ability.name().toLowerCase());
                                        } else {
                                            allowed.append(", ").append(ability.name().toLowerCase());
                                        }
                                    }
                                    throw new CommandException("commands.region.abilities.unknown", a, allowed.toString());
                                }
                            }
                        } else {
                            abilities = EnumSet.allOf(WorldRegionManager.RegionAbility.class);
                        }
                        if (region.isMember(target)) {
                            throw new CommandException("commands.region.invite.already", target.getName());
                        } else {
                            region.setMemberAbilities(target, abilities);
                            sender.sendMessage(
                                Text.translation("commands.region.invite.successful").green()
                                    .arg(target.getName(), Text::darkGreen)
                            );
                            regionManager.notifyRegionChange(region);
                        }
                    } else {
                        throw new CommandException("commands.region.not_found");
                    }
                    break;
                }
                case "expel": {
                    Player sender = ctx.senderAsPlayer();
                    OfflinePlayer target = ctx.get("target").asOfflinePlayer();
                    if (target == sender) {
                        throw new CommandException("commands.region.yourself");
                    }
                    WorldRegionManager.Region region = regionManager.getRegion(sender.getLocation());
                    if (region == null) {
                        throw new CommandException("commands.region.not_found");
                    }
                    if (region.isOwner(sender) || sender.hasPermission("region.admin.expel")) {
                        if (region.isOwner(target)) {
                            throw new CommandException("commands.region.expel.owner");
                        } else {
                            expel(region, sender, target);
                        }
                    } else {
                        throw new CommandException("commands.region.not_owning");
                    }
                    break;
                }
                case "transfer": {
                    Player sender = ctx.senderAsPlayer();
                    OfflinePlayer target = ctx.get("target").asOfflinePlayer();
                    if (target == sender) {
                        throw new CommandException("commands.region.yourself");
                    }
                    WorldRegionManager.Region region = regionManager.getRegion(sender.getLocation());
                    if (region == null) {
                        throw new CommandException("commands.region.not_found");
                    }
                    if (!region.isOwner(sender) && !sender.hasPermission("region.admin.transfer")) {
                        throw new CommandException("commands.region.not_owning");
                    }
                    sender.sendQuestion(
                        "region.transfer",
                        Text.translation("commands.region.transfer.question"),
                        60,
                        confirmed -> {
                            if (confirmed) {
                                region.setOwner(target);
                                sender.sendMessage(
                                    Text.translation("commands.region.transfer.successful").yellow()
                                        .arg(target.getName(), Text::gold)
                                );
                                if (target.isOnline()) {
                                    target.asOnline().sendMessage(
                                        Text.translation("commands.region.transfer.received").green()
                                            .arg(sender.getName(), Text::darkGreen)
                                            .arg("commands.region.transfer.received.target", t ->
                                                t.darkGreen().runCommand("/region teleport " + region.getId())
                                            )
                                    );
                                }
                                regionManager.setDirty(true);
                                try {
                                    regionManager.save();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    );
                    break;
                }
                case "delete": {
                    Player sender = ctx.senderAsPlayer();
                    deleteRegion(sender, regionManager);
                    break;
                }
                case "pvp": {
                    Player sender = ctx.senderAsPlayer();
                    WorldRegionManager.Region region = regionManager.getRegion(sender.getLocation());
                    if (region != null) {
                        if (region.isOwner(sender) && sender.hasPermission("commands.region.pvp")
                            || sender.hasPermission("commands.region.admin.pvp")) {

                            boolean pvp = !region.isPvP();
                            region.setPvP(pvp);
                            region.getManager().setDirty(true);
                            sender.sendMessage(Text.translation("commands.region.pvp." + (pvp ? "on" : "off")).color(pvp ? TextFormatting.RED : TextFormatting.GREEN));
                        } else {
                            throw new CommandException("commands.region.not_owning");
                        }
                    } else {
                        throw new CommandException("commands.region.not_found");
                    }
                    break;
                }
            }
        } else {
            CommandSender sender;
            WorldRegionManager.Region region;
            if (ctx.has("region")) {
                sender = ctx.sender();
                region = regionManager.getRegion(ctx.get("region").asUUID());
            } else {
                sender = ctx.senderAsPlayer();
                region = regionManager.getRegion(((LocatableCommandSender)sender).getLocation());
            }
            if (region != null) {
                if (sender instanceof OfflinePlayer) {
                    if (region.isOwner((OfflinePlayer) sender) || region.isMember((OfflinePlayer) sender)) {
                        sendRegionInfo(server, sender, region);
                    } else if (ctx.checkPermission(false, "commands.region.info.others", 2)) {
                        sendRegionInfo(server, sender, region);
                    } else {
                        throw new CommandException("commands.region.info.no_permission");
                    }
                } else {
                    sendRegionInfo(server, ctx.sender(), region);
                }
            } else {
                throw new CommandException("commands.region.not_found");
            }
        }
    }

    private static void expel(WorldRegionManager.Region region, Player sender, OfflinePlayer target) {
        sender.sendQuestion(
            "region.expel",
            Text.translation("commands.region.expel.question"),
            60,
            confirmed -> {
                if (confirmed) {
                    if (region.getMembers().remove(target.getId())) {
                        sender.sendMessage(
                            Text.translation("commands.region.expel.success").yellow()
                        );
                    } else {
                        sender.sendMessage(
                            Text.translation("commands.region.expel.already").red()
                        );
                    }
                }
            }

        );
    }

    private static void deleteRegion(Player sender, RegionManager regionManager) throws CommandException {
        WorldRegionManager.Region region = regionManager.getRegion(sender.getLocation());
        if (region != null) {
            if (region.isOwner(sender) || sender.hasPermission("region.admin.delete")) {
                sender.sendQuestion(
                    "region.delete",
                    Text.translation("commands.region.delete.question"),
                    60,
                    confirmed -> {
                        if (confirmed) {
                            if (region.delete()) {
                                sender.sendMessage(
                                    Text.translation("commands.region.delete.success").yellow()
                                );
                            } else {
                                sender.sendMessage(
                                    Text.translation("commands.region.delete.failure").red()
                                );
                            }
                            regionManager.setDirty(true);
                            try {
                                regionManager.save();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                );
            } else {
                throw new CommandException("commands.region.not_owning");
            }
        }
    }

    public static void sendRegionInfo(Server server, CommandSender sender, WorldRegionManager.Region region) {
        PlayerManager playerManager = server.getPlayerManager();
        OfflinePlayer owner = playerManager.getOffline(region.getOwner());
        sender.sendMessage(Text.translation("commands.region.info.owner").arg(owner != null ? owner.getName() : region.getOwner().toString()));
        List<String> members = new ArrayList<>();
        for (UUID member : region.getMembers()) {
            OfflinePlayer m = playerManager.getOffline(member);
            members.add(m == null ? member.toString() : m.getName());
        }
        if (!members.isEmpty()) {
            sender.sendMessage(Text.translation("commands.region.info.members").arg(String.join(", ", members)));
        }
    }
}
