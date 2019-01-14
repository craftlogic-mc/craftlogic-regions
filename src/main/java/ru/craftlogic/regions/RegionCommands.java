package ru.craftlogic.regions;

import net.minecraft.command.CommandException;
import net.minecraft.util.text.TextFormatting;
import ru.craftlogic.api.command.*;
import ru.craftlogic.api.server.PlayerManager;
import ru.craftlogic.api.server.Server;
import ru.craftlogic.api.text.Text;
import ru.craftlogic.api.world.CommandSender;
import ru.craftlogic.api.world.LocatableCommandSender;
import ru.craftlogic.api.world.OfflinePlayer;
import ru.craftlogic.api.world.Player;
import ru.craftlogic.regions.WorldRegionManager.Region;
import ru.craftlogic.regions.WorldRegionManager.RegionAbility;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class RegionCommands implements CommandRegistrar {
    @Command(
        name = "region",
        aliases = {"reg", "rg"},
        syntax = {
            "[create|delete|pvp]",
            "[expel|transfer] <target:OfflinePlayer>",
            "invite <target:OfflinePlayer>",
            "invite <target:OfflinePlayer> <abilities>...",
            "teleport <region:Region>",
            "<region:Region>",
            ""
        },
        opLevel = 1
    )
    public static void commandRegion(CommandContext ctx) throws CommandException {
        Server server = ctx.server();
        RegionManager regionManager = server.getManager(RegionManager.class);
        if (ctx.hasConstant()) {
            switch (ctx.constant()) {
                case "teleport": {
                    Player sender = ctx.senderAsPlayer();
                    UUID regionId = ctx.get("region").asUUID();
                    Region region = regionManager.getRegion(regionId);
                    if (region != null) {
                        if ((region.isOwner(sender) || region.isMember(sender)) && sender.hasPermission("commands.region.teleport")
                                || sender.hasPermission("commands.region.admin.teleport")) {

                            sender.teleport(region.getCenter());
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
                    Region region = regionManager.getRegion(sender.getLocation());
                    if (region != null) {
                        boolean admin = sender.hasPermission("region.admin.invite");
                        if (!region.isOwner(sender) && !region.isMember(sender) && !admin) {
                            throw new CommandException("commands.region.not_owning");
                        }
                        if (target == sender && !admin) {
                            throw new CommandException("commands.region.yourself");
                        }
                        Set<RegionAbility> abilities;
                        if (ctx.has("abilities")) {
                            abilities = new HashSet<>();
                            for (String a : ctx.get("abilities").asString().split(",")) {
                                try {
                                    abilities.add(RegionAbility.valueOf(a.toUpperCase()));
                                } catch (IllegalArgumentException e) {
                                    StringBuilder allowed = new StringBuilder();
                                    for (RegionAbility ability : RegionAbility.values()) {
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
                            abilities = EnumSet.allOf(RegionAbility.class);
                        }
                        if (region.isMember(target)) {
                            throw new CommandException("commands.region.invite.already", target.getName());
                        } else {
                            region.setMemberAbilities(target, abilities);
                            sender.sendMessage(
                                Text.translation("commands.region.invite.successful").green()
                                    .arg(target.getName(), Text::darkGreen)
                            );
                        }
                    } else {
                        throw new CommandException("commands.region.not_found");
                    }
                    break;
                }
            }
        } else if (ctx.has("target")) {
            Player sender = ctx.senderAsPlayer();
            OfflinePlayer target = ctx.get("target").asOfflinePlayer();
            if (target == sender) {
                throw new CommandException("commands.region.yourself");
            }
            Region region = regionManager.getRegion(sender.getLocation());
            if (region == null) {
                throw new CommandException("commands.region.not_found");
            }
            switch (ctx.action()) {
                case "expel": {
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
            }
        } else if (ctx.hasAction()) {
            Player sender = ctx.senderAsPlayer();
            switch (ctx.action()) {
                case "create": {
                    createRegion(sender, regionManager);
                    break;
                }
                case "delete": {
                    deleteRegion(sender, regionManager);
                    break;
                }
                case "pvp": {
                    Region region = regionManager.getRegion(sender.getLocation());
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
            Region region;
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

    private static void expel(Region region, Player sender, OfflinePlayer target) {
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

    private static void createRegion(Player sender, RegionManager regionManager) throws CommandException {
        int size = sender.getPermissionMetadata("region.initial-size", 25, Integer::parseInt);
        int count = sender.getPermissionMetadata("region.max-count", 5, Integer::parseInt);

        if (regionManager.getPlayerRegions(sender).size() >= count) {
            throw new CommandException("commands.region.create.too_many", count);
        }

        List<Region> regions = regionManager.getNearbyRegions(sender.getLocation(), size, size, false);
        if (!regions.isEmpty()) {
            throw new CommandException("commands.region.create.intersects", regions.size());
        }
        Region region = regionManager.createRegion(sender.getLocation(), sender, size, size);
        if (region != null) {
            sender.sendMessage(
                Text.translation("commands.region.create.success").green()
                    .arg(size, Text::darkGreen)
                    .arg(size, Text::darkGreen)
            );
        } else {
            throw new CommandException("commands.region.create.failure");
        }
    }

    private static void deleteRegion(Player sender, RegionManager regionManager) throws CommandException {
        Region region = regionManager.getRegion(sender.getLocation());
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
                        }
                    }
                );
            } else {
                throw new CommandException("commands.region.not_owning");
            }
        }
    }

    private static void sendRegionInfo(Server server, CommandSender sender, Region region) throws CommandException {
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

    @ArgumentCompleter(type = "Region")
    public static List<String> completerReg(ArgumentCompletionContext ctx) {
        RegionManager regionManager = ctx.server().getManager(RegionManager.class);
        CommandSender sender = ctx.sender();
        return (sender instanceof Player ? regionManager.getPlayerRegions((Player) sender) : regionManager.getAllLoadedRegions())
                .stream()
                .map(Region::getId)
                .map(UUID::toString)
                .collect(Collectors.toList());
    }
}
