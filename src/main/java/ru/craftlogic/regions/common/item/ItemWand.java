package ru.craftlogic.regions.common.item;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.command.CommandException;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import ru.craftlogic.api.item.ItemBase;
import ru.craftlogic.api.server.Server;
import ru.craftlogic.api.text.Text;
import ru.craftlogic.api.world.Location;
import ru.craftlogic.api.world.Player;
import ru.craftlogic.regions.RegionManager;
import ru.craftlogic.regions.WorldRegionManager;

import javax.annotation.Nullable;

import java.util.List;

import static ru.craftlogic.regions.common.command.CommandRegion.sendRegionInfo;

public class ItemWand extends ItemBase {
    public ItemWand() {
        super("wand", CreativeTabs.TOOLS);
        setMaxStackSize(1);
    }

    public @Nullable Location getFirstPoint(ItemStack wand) {
        NBTTagCompound data = wand.getSubCompound("pos");
        if (data != null) {
            return new Location(
                data.getShort("dim"),
                data.getInteger("x"),
                data.getInteger("y"),
                data.getInteger("z")
            );
        } else {
            return null;
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack wand, @Nullable World world, List<String> tooltip, ITooltipFlag advanced) {
        NBTTagCompound data = wand.getSubCompound("pos");
        if (data != null) {
            int x = data.getInteger("x");
            int y = data.getInteger("y");
            int z = data.getInteger("z");
            tooltip.add(I18n.format("item.wand.first_point", x, y, z));
        }
        tooltip.add(I18n.format("item.wand.tooltip"));
    }

    @Override
    public EnumActionResult onItemUse(World world, BlockPos pos, RayTraceResult target, EntityPlayer _player, EnumHand hand) {
        Location location = new Location(world, pos);
        ItemStack wand = _player.getHeldItem(hand);
        if (!world.isRemote) {
            Location firstPoint = getFirstPoint(wand);
            Player player = Player.from(((EntityPlayerMP) _player));
            Server server = player.getServer();
            RegionManager regionManager = server.getManager(RegionManager.class);
            WorldRegionManager.Region region = regionManager.getRegion(location);
            if (region != null) {
                if (region.isOwner(player) || region.isMember(player)) {
                    sendRegionInfo(server, player, region);
                } else if (player.hasPermission("commands.region.info.others", 2)) {
                    sendRegionInfo(server, player, region);
                } else {
                    player.sendStatus(Text.translation("commands.region.info.no_permission").red());
                }
            } else if (firstPoint != null) {
                if (firstPoint.getDimension() != location.getDimension()) {
                    player.sendStatus(Text.translation("item.wand.other_dimension").red());
                } else if (!firstPoint.getPos().equals(location.getPos())) {
                    wand.removeSubCompound("pos");
                    try {
                        createRegion(player, regionManager, firstPoint, location);
                    } catch (CommandException e) {
                        player.sendStatus(Text.translation(e).red());
                    }
                }
            } else {
                NBTTagCompound point = new NBTTagCompound();
                point.setShort("dim", (short) location.getDimensionId());
                int x = location.getBlockX();
                int y = location.getBlockY();
                int z = location.getBlockZ();
                point.setInteger("x", x);
                point.setInteger("y", y);
                point.setInteger("z", z);
                wand.setTagInfo("pos", point);
                player.sendStatus(Text.translation("item.wand.first_point").green()
                    .arg(x, Text::darkGreen)
                    .arg(y, Text::darkGreen)
                    .arg(z, Text::darkGreen)
                );
            }
        }
        return EnumActionResult.SUCCESS;
    }

    private static void createRegion(Player sender, RegionManager regionManager, Location start, Location end) throws CommandException {
        int width = Math.abs(start.getBlockX() - end.getBlockX()) + 1;
        int depth = Math.abs(start.getBlockZ() - end.getBlockZ()) + 1;
        int maxArea = sender.getPermissionMetadata("region.max-area", 100 * 100, Integer::parseInt);
        int maxCount = sender.getPermissionMetadata("region.max-maxCount", 5, Integer::parseInt);

        int area = width * depth;
        int count = regionManager.getPlayerRegions(sender).size();

        if (count >= maxCount) {
            throw new CommandException("commands.region.create.too_many", count, maxCount);
        }
        if (area > maxArea) {
            throw new CommandException("commands.region.create.too_large", area, maxArea);
        }

        List<WorldRegionManager.Region> regions = regionManager.getNearbyRegions(start, end, false);
        if (!regions.isEmpty()) {
            throw new CommandException("commands.region.create.intersects", regions.size());
        }
        sender.sendQuestion(
            "region.create",
            Text.translation("commands.region.create.question")
                .arg(width)
                .arg(depth),
            60,
            confirmed -> {
                if (confirmed) {
                    List<WorldRegionManager.Region> r = regionManager.getNearbyRegions(start, end, false);
                    if (r.isEmpty()) {
                        WorldRegionManager.Region region = regionManager.createRegion(start, end, sender);
                        if (region != null) {
                            sender.sendMessage(
                                Text.translation("commands.region.create.success").green()
                                    .arg(width, Text::darkGreen)
                                    .arg(depth, Text::darkGreen)
                            );
                        } else {
                            sender.sendStatus(Text.translation("commands.region.create.failure").red());
                        }
                    } else {
                        sender.sendStatus(Text.translation("commands.region.create.intersects").red()
                            .arg(r.size(), Text::darkRed)
                        );
                    }
                }
            }
        );
    }
}
