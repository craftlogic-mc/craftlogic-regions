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
import ru.craftlogic.regions.WorldRegionManager.Region;

import java.io.IOException;
import java.util.*;

public class CommandRegion extends CommandBase {
    public CommandRegion() {
        super("region", 1,
            "pvp|hostiles|mob_attacks|explosions|projectiles|mob_spawn",
            "expel|transfer <target:OfflinePlayer>",
            "list <target:OfflinePlayer>",
            "list <target:OfflinePlayer> <world:World>",
            "invite <target:OfflinePlayer>",
            "invite <target:OfflinePlayer> <abilities>...",
            "teleport <region:Region>",
            "delete|create|claim|info|override",
            "create|claim <name>",
            "info <region:Region>",
            "<region:Region>",
            ""
        );
        Collections.addAll(aliases, "rg", "reg");
    }

    private <T> T error(String message, Object... args) throws CommandException {
        throw new CommandException(message, args);
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
                    Region region = regionManager.getRegion(regionId);
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
                case "override": {
                    Player sender = ctx.senderAsPlayer();
                    if (sender.hasPermission("commands.region.override", 4)) {
                        boolean enabled = regionManager.toggleOverride(sender);
                        if (enabled) {
                            sender.sendMessage(
                                Text.translation("commands.region.override.enabled").yellow()
                            );
                        } else {
                            sender.sendMessage(
                                Text.translation("commands.region.override.disabled").green()
                            );
                        }
                    }
                    break;
                }
                case "invite": {
                    Player sender = ctx.senderAsPlayer();
                    OfflinePlayer target = ctx.get("target").asOfflinePlayer();
                    Region region = regionManager.getRegion(sender.getLocation());
                    if (region != null) {
                        boolean admin = sender.hasPermission("region.admin.invite");
                        if (!region.isOwner(sender) && !admin) {
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
                        if (region.isMember(target) || region.isOwner(target)) {
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
                    Region region = regionManager.getRegion(sender.getLocation());
                    if (region == null) {
                        throw new CommandException("commands.region.not_found");
                    }
                    boolean hasPermission = sender.hasPermission("region.admin.expel");
                    if (region.isOwner(sender) || hasPermission) {
                        if (target == sender && !hasPermission) {
                            throw new CommandException("commands.region.yourself");
                        }
                        if (region.isOwner(target) && !region.isMember(target)) {
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
                    Region region = regionManager.getRegion(sender.getLocation());
                    if (region == null) {
                        throw new CommandException("commands.region.not_found");
                    }
                    if (!region.isOwner(sender) && !sender.hasPermission("region.admin.transfer")) {
                        throw new CommandException("commands.region.not_owning");
                    }
                    List<Region> alreadyOwnedRegions = regionManager.getPlayerRegions(target, sender.getWorld());
                    int maxCount = target.getPermissionMetadata("region.max-count", 5, Integer::parseInt);
                    int maxArea = target.getPermissionMetadata("region.max-area", 100 * 100, Integer::parseInt);
                    if (alreadyOwnedRegions.size() >= maxCount) {
                        sender.sendMessage(
                            Text.translation("commands.region.transfer.max_count").red()
                                .arg(alreadyOwnedRegions.size(), Text::darkRed)
                                .arg(maxCount, Text::darkRed)
                        );
                    } else if (region.getArea() >= maxArea) {
                        sender.sendMessage(
                            Text.translation("commands.region.transfer.max_area").red()
                                .arg((int) region.getArea(), Text::darkRed)
                                .arg(maxArea, Text::darkRed)
                        );
                    } else {
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
                                                .argTranslate("commands.region.transfer.received.target", t ->
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
                    }
                    break;
                }
                case "list": {
                    World world = ctx.has("world")
                        ? ctx.get("world").asWorld()
                        : ctx.sender() instanceof Player
                            ? ctx.senderAsPlayer().getWorld()
                            : error("commands.region.list.world");
                    OfflinePlayer target = ctx.get("target").asOfflinePlayer();
                    List<Region> regions = regionManager.getPlayerRegions(target, world);
                    if (regions.isEmpty()) {
                        ctx.sendMessage(Text.translation("commands.region.list.none").red());
                    } else {
                        Text<?, ?> message = Text.translation("commands.region.list.header").yellow()
                            .arg(target.getName(), Text::gold)
                            .arg(world.getName(), Text::gold);
                        message.appendText("\n");
                        int i = 1;
                        for (Region region : regions) {
                            String id = region.getId().toString();
                            Text<?, ?> line = Text.string(String.valueOf(i++))
                                .appendText(". [")
                                .appendTranslate("commands.region.list.id", t -> t.green().suggestCommand(id))
                                .appendText("] [")
                                .appendTranslate("commands.region.list.teleport", t -> t.green().runCommand("/region teleport " + id))
                                .appendText("]\n");
                            message.append(line);
                        }
                        ctx.sendMessage(message);
                    }
                    break;
                }
                case "delete": {
                    Player sender = ctx.senderAsPlayer();
                    deleteRegion(sender, regionManager);
                    break;
                }
                case "explosions": {
                    booleanFlag(ctx, regionManager, "explosions", Region::isExplosions, Region::setExplosions, true);
                    break;
                }
                case "pvp": {
                    booleanFlag(ctx, regionManager, "pvp", Region::isPvP, Region::setPvP, true);
                    break;
                }
                case "hostiles": {
                    booleanFlag(ctx, regionManager, "hostiles", Region::isProtectingHostiles, Region::setProtectingHostiles, true);
                    break;
                }
                case "mob_attacks": {
                    booleanFlag(ctx, regionManager, "mob_attacks", Region::isPreventingMobAttacks, Region::setPreventingMobAttacks, false);
                    break;
                }
                case "projectiles": {
                    booleanFlag(ctx, regionManager, "projectiles", Region::isProjectiles, Region::setProjectiles, true);
                    break;
                }
                case "mob_spawn": {
                    booleanFlag(ctx, regionManager, "mob_spawn", Region::canSpawnMobs, Region::setSpawnMobs, true);
                    break;
                }
                case "create":
                case "claim": {
                    throw new CommandException("commands.region.claim_wand");
                }
                case "info": {
                    info(ctx, server, regionManager);
                    break;
                }
            }
        } else {
            info(ctx, server, regionManager);
        }
    }

    interface Getter {
        boolean get(Region region);
    }

    interface Setter {
        void set(Region region, boolean value);
    }

    private static void booleanFlag(CommandContext ctx, RegionManager regionManager, String name, Getter getter, Setter setter, boolean negative) throws CommandException {
        Player sender = ctx.senderAsPlayer();
        Region region = regionManager.getRegion(sender.getLocation());
        if (region != null) {
            if (region.isOwner(sender) && ctx.checkPermission(true, "commands.region." + name, 1)
                || sender.hasPermission("commands.region.admin." + name)) {

                boolean value = !getter.get(region);
                setter.set(region, value);
                region.getManager().setDirty(true);
                sender.sendMessage(Text.translation("commands.region."  + name + "." + (value ? "on" : "off")).color(value ^ negative ? TextFormatting.GREEN : TextFormatting.RED));
            } else {
                throw new CommandException("commands.region.not_owning");
            }
        } else {
            throw new CommandException("commands.region.not_found");
        }
    }

    private static void info(CommandContext ctx, Server server, RegionManager regionManager) throws CommandException {
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

    private static void expel(Region region, Player sender, OfflinePlayer target) {
        sender.sendQuestion(
            "region.expel",
            Text.translation("commands.region.expel.question")
                .arg(target.getName()),
            60,
            confirmed -> {
                if (confirmed) {
                    if (region.getMembers().remove(target.getId())) {
                        sender.sendMessage(
                            Text.translation("commands.region.expel.success").yellow()
                                .arg(target.getName(), Text::gold)
                        );
                    } else {
                        sender.sendMessage(
                            Text.translation("commands.region.expel.already").red()
                                .arg(target.getName(), Text::darkRed)
                        );
                    }
                }
            }

        );
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
                                try {
                                    regionManager.save(true);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
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
        } else {
            throw new CommandException("commands.region.not_found");
        }
    }

    public static void sendRegionInfo(Server server, CommandSender sender, Region region) {
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
