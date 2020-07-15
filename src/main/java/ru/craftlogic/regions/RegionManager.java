package ru.craftlogic.regions;

import com.google.gson.JsonObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityPotion;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.Explosion;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.craftlogic.api.event.block.DispenserShootEvent;
import ru.craftlogic.api.event.block.FarmlandTrampleEvent;
import ru.craftlogic.api.event.block.FluidFlowEvent;
import ru.craftlogic.api.event.block.PistonCheckCanMoveEvent;
import ru.craftlogic.api.event.player.PlayerCheckCanEditEvent;
import ru.craftlogic.api.math.Bounding;
import ru.craftlogic.api.math.BoxBounding;
import ru.craftlogic.api.server.PlayerManager;
import ru.craftlogic.api.server.Server;
import ru.craftlogic.api.server.WorldManager;
import ru.craftlogic.api.text.Text;
import ru.craftlogic.api.util.ConfigurableManager;
import ru.craftlogic.api.world.*;
import ru.craftlogic.common.command.CommandManager;
import ru.craftlogic.regions.WorldRegionManager.Region;
import ru.craftlogic.regions.common.command.CommandRegion;
import ru.craftlogic.regions.common.command.CommandWand;
import ru.craftlogic.regions.network.message.MessageRegion;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class RegionManager extends ConfigurableManager {
    private static final Logger LOGGER = LogManager.getLogger("RegionManager");
    private static final int SYNC_COOLDOWN = 10 * 20;

    final Map<String, WorldRegionManager> managers = new HashMap<>();
    private int updateCounter;
    private boolean loaded;

    public RegionManager(Server server, Path settingsDirectory) {
        super(server, settingsDirectory.resolve("regions.json"), LOGGER);
    }

    @Override
    public void registerCommands(CommandManager commandManager) {
        commandManager.registerCommand(new CommandRegion());
        commandManager.registerCommand(new CommandWand());
        commandManager.registerArgumentType("Region", false, ctx -> {
            RegionManager regionManager = ctx.server().getManager(RegionManager.class);
            CommandSender sender = ctx.sender();
            return (sender instanceof Player ? regionManager.getPlayerRegions((Player) sender) : regionManager.getAllLoadedRegions())
                .stream()
                .map(WorldRegionManager.Region::getId)
                .map(UUID::toString)
                .collect(Collectors.toList());
        });
    }

    @Override
    public void load(JsonObject regions) {
        loaded = true;

        for (WorldRegionManager manager : managers.values()) {
            try {
                manager.load();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void save(JsonObject config) {
        for (WorldRegionManager manager : managers.values()) {
            try {
                manager.save(true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public Region createRegion(Location start, Location end, OfflinePlayer owner) {
        return createRegion(start, end, owner.getId());
    }

    public Region createRegion(Location start, Location end, UUID owner) {
        WorldRegionManager manager = managers.get(start.getWorldName());
        Region region = manager.createRegion(start, end, owner);
        if (region != null) {
            notifyRegionChange(region);
            try {
                manager.save(true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return region;
    }

    public List<Region> getAllLoadedRegions() {
        List<Region> result = new ArrayList<>();
        for (WorldRegionManager manager : managers.values()) {
            result.addAll(manager.getAllRegions());
        }
        return result;
    }

    public Region getRegion(UUID id) {
        Region result;
        for (WorldRegionManager manager : managers.values()) {
            if ((result = manager.getRegion(id)) != null) {
                return result;
            }
        }
        return null;
    }

    public List<Region> getPlayerRegions(OfflinePlayer owner) {
        return getPlayerRegions(owner.getId());
    }

    public List<Region> getPlayerRegions(UUID owner) {
        List<Region> result;
        for (WorldRegionManager manager : managers.values()) {
            if ((result = manager.getPlayerRegions(owner)) != null) {
                return result;
            }
        }
        return Collections.emptyList();
    }

    public Region getRegion(Location location) {
        WorldRegionManager manager = managers.get(location.getWorldName());
        if (manager != null) {
            for (Region region : manager.getAllRegions()) {
                if (region.isOwning(location)) {
                    return region;
                }
            }
        }
        return null;
    }

    public List<Region> getNearbyRegions(Location start, Location end, boolean loadWorld) {
        if (!start.isDimensionLoaded() && !loadWorld) {
            return Collections.emptyList();
        }
        WorldRegionManager manager = managers.get(start.getWorldName());
        if (manager != null) {
            List<Region> result = new ArrayList<>();
            Bounding origin = new BoxBounding(start, end);
            for (Region region : manager.getAllRegions()) {
                if (origin.isIntersects(region)) {
                    result.add(region);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    public Region deleteRegion(UUID id) {
        Region region;
        for (WorldRegionManager manager : managers.values()) {
            if ((region = manager.deleteRegion(id)) != null) {
                return region;
            }
        }
        return null;
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        World world = World.fromVanilla(server, event.getWorld());
        if (world != null) {
            WorldRegionManager manager = new WorldRegionManager(server, world, LOGGER);
            managers.put(world.getName(), manager);
            if (loaded) {
                try {
                    manager.load();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        String worldName = event.getWorld().provider.getDimensionType().getName();
        WorldRegionManager manager = managers.remove(worldName);
        if (manager != null) {
            try {
                manager.save(true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @SubscribeEvent
    public void onPlayerMove(LivingEvent.LivingUpdateEvent event) {
        if (event.getEntityLiving() instanceof EntityPlayer && !event.getEntityLiving().getEntityWorld().isRemote) {
            EntityPlayer player = (EntityPlayer) event.getEntityLiving();
            if (player.lastTickPosX != player.posX || player.lastTickPosY != player.posY || player.lastTickPosZ != player.posZ) {
                Location oldLocation = new Location(player.world, player.lastTickPosX, player.lastTickPosY, player.lastTickPosZ);
                Location newLocation = new Location(player.world, player.posX, player.posY, player.posZ);
                Region oldRegion = getRegion(oldLocation);
                Region newRegion = getRegion(newLocation);
                if (!Objects.equals(oldRegion, newRegion) && newRegion != null || oldRegion != null && newRegion != null && !Objects.equals(oldRegion.owner, newRegion.owner)) {
                    PlayerManager playerManager = server.getPlayerManager();
                    OfflinePlayer owner = playerManager.getOffline(newRegion.getOwner());
                    if (owner != null) {
                        Player target = Player.from((EntityPlayerMP) player);
                        target.sendTitle(owner.getDisplayName(), new TextComponentTranslation("tooltip.region.owner"), 20, 20, 20);
                    }
                }
            }
        }
    }

    public void notifyRegionChange(Region region) {
        World world = World.fromVanilla(server, region.getStart().getWorld());
        for (Player player : world.getPlayers()) {
            Bounding bounding = getPlayerRegionBounding(player, 200);
            if (bounding.isIntersects(region)) {
                player.sendPacket(new MessageRegion(server, region));
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (updateCounter++ >= SYNC_COOLDOWN) {
            updateCounter = 0;
            WorldManager worldManager = server.getWorldManager();
            for (Map.Entry<String, WorldRegionManager> entry : managers.entrySet()) {
                World world = worldManager.get(entry.getKey());
                if (world != null) {
                    WorldRegionManager manager = entry.getValue();
                    for (Player player : world.getPlayers()) {
                        Bounding bounding = getPlayerRegionBounding(player, 200);
                        Set<Region> regions = new HashSet<>();
                        for (Region region : manager.getAllRegions()) {
                            if (bounding.isIntersects(region)) {
                                regions.add(region);
                            }
                        }
                        for (Region region : regions) {
                            player.sendPacket(new MessageRegion(server, region));
                        }
                    }
                }
            }
        }
    }

    private Bounding getPlayerRegionBounding(Player player, int radius) {
        return new Bounding() {
            @Override
            public double getStartX() {
                return player.getLocation().getX() - radius;
            }

            @Override
            public double getStartY() {
                return 0;
            }

            @Override
            public double getStartZ() {
                return player.getLocation().getZ() - radius;
            }

            @Override
            public double getEndX() {
                return player.getLocation().getX() + radius;
            }

            @Override
            public double getEndY() {
                return 256;
            }

            @Override
            public double getEndZ() {
                return player.getLocation().getZ() + radius;
            }
        };
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        checkBlocks(event, event.getPlayer());
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.PlaceEvent event) {
        checkBlocks(event, event.getPlayer());
    }

    private void checkBlocks(BlockEvent event, EntityPlayer player) {
        Location location = new Location(event.getWorld(), event.getPos());
        Region region = getRegion(location);
        if (region != null && !region.canEditBlocks(player.getUniqueID())) {
            player.sendStatusMessage(Text.translation("chat.region.edit.blocks").red().build(), true);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onCheckBlockModify(PlayerCheckCanEditEvent event) {
        EntityPlayer player = event.getEntityPlayer();
        Location location = new Location(player.getEntityWorld(), event.pos);
        Region region = getRegion(location);
        if (region != null && !region.canEditBlocks(player.getUniqueID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onEntityAttack(AttackEntityEvent event) {
        EntityPlayer player = event.getEntityPlayer();
        Entity target = event.getTarget();
        if (checkAttack(player, target)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onTrample(FarmlandTrampleEvent event) {
        Entity entity = event.getEntity();
        net.minecraft.world.World world = event.getWorld();
        BlockPos pos = event.getPos();
        Location location = new Location(world, pos);
        Region region = getRegion(location);
        if (region != null) {
            if (entity instanceof EntityPlayerMP) {
                if (!region.canEditBlocks(entity.getUniqueID())) {
                    EntityPlayerMP player = (EntityPlayerMP) entity;
                    player.sendStatusMessage(Text.translation("chat.region.edit.blocks").red().build(), true);
                    player.connection.sendPacket(new SPacketBlockChange(world, pos));
                    event.setCanceled(true);
                }
            } else if (entity instanceof EntityLivingBase) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onArrowHit(ProjectileImpactEvent.Arrow event) {
        EntityArrow arrow = event.getArrow();
        RayTraceResult target = event.getRayTraceResult();
        if (target.entityHit != null) {
            if (arrow.shootingEntity instanceof EntityPlayer) {
                if (checkAttack(((EntityPlayer) arrow.shootingEntity), target.entityHit)) {
                    event.setCanceled(true);
                }
            }
        }
    }

    @SubscribeEvent
    public void onThrowableImpact(ProjectileImpactEvent.Throwable event) {
        EntityThrowable throwable = event.getThrowable();
        RayTraceResult target = event.getRayTraceResult();
        EntityLivingBase thrower = throwable.getThrower();
        if (target.entityHit != null) {
            if (thrower instanceof EntityPlayer) {
                Region region = getRegion(new Location(target.entityHit));
                if (region != null) {
                    if (!region.canInteractEntities(thrower.getUniqueID())) {
                        event.setCanceled(true);
                        ((EntityPlayer) thrower).sendStatusMessage(Text.translation("chat.region.interact.entities").red().build(), true);
                        if (throwable instanceof EntityPotion) {
                            throwable.entityDropItem(((EntityPotion) throwable).getPotion(), 0F);
                        }
                    } else if (!region.canLaunchProjectiles(thrower.getUniqueID())) {
                        event.setCanceled(true);
                        if (throwable instanceof EntityPotion) {
                            throwable.entityDropItem(((EntityPotion) throwable).getPotion(), 0F);
                            ((EntityPlayer) thrower).sendStatusMessage(Text.translation("chat.region.interact.potions").red().build(), true);
                        } else {
                            ((EntityPlayer) thrower).sendStatusMessage(Text.translation("chat.region.interact.projectiles").red().build(), true);
                        }
                    }
                }
            }
        } else if (throwable instanceof EntityPotion) {
            if (thrower instanceof EntityPlayer) {
                Region region = getRegion(new Location(throwable));
                if (region != null && !region.canLaunchProjectiles(thrower.getUniqueID())) {
                    event.setCanceled(true);
                    throwable.entityDropItem(((EntityPotion) throwable).getPotion(), 0F);
                    throwable.setDead();
                    ((EntityPlayer) thrower).sendStatusMessage(Text.translation("chat.region.interact.potions").red().build(), true);
                }
            }
        }
    }

    private boolean checkAttack(EntityPlayer player, Entity target) {
        Region targetRegion = getRegion(new Location(target));
        if (target instanceof EntityPlayer) {
            Region fromRegion = getRegion(new Location(player));
            if (fromRegion != null && !fromRegion.isPvP() || targetRegion != null && !targetRegion.isPvP()) {
                player.sendStatusMessage(Text.translation("chat.region.attack.players").red().build(), true);
                return true;
            }
        } else if (target instanceof IMob) {
            if (targetRegion != null && false /*&& !targetRegion.canAttackHostiles(player.getUniqueID())*/) {
                player.sendStatusMessage(Text.translation("chat.region.attack.monsters").red().build(), true);
                return true;
            }
        } else if (target instanceof IAnimals) {
            if (targetRegion != null && !targetRegion.canAttackNeutral(player.getUniqueID())) {
                player.sendStatusMessage(Text.translation("chat.region.attack.animals").red().build(), true);
                return true;
            }
        } else {
            if (targetRegion != null && !targetRegion.canEditBlocks(player.getUniqueID())) {
                player.sendStatusMessage(Text.translation("chat.region.edit.blocks").red().build(), true);
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public void onBlockRightClick(PlayerInteractEvent.RightClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        Location location = new Location(event.getWorld(), event.getPos());
        if (!location.isAir()) {
            Region region = getRegion(location);
            if (region != null && !region.canInteractBlocks(player.getUniqueID())) {
                event.setUseBlock(Event.Result.DENY);
                player.sendStatusMessage(Text.translation("chat.region.interact.blocks").red().build(), true);
            }
        }
    }

    @SubscribeEvent
    public void onBlockLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        Location location = new Location(event.getWorld(), event.getPos());
        if (!location.isAir()) {
            Region region = getRegion(location);
            if (region != null && !region.canInteractBlocks(player.getUniqueID())) {
                event.setUseBlock(Event.Result.DENY);
                player.sendStatusMessage(Text.translation("chat.region.interact.blocks").red().build(), true);
            }
        }
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        checkEntityInteract(event.getEntityPlayer(), event.getTarget(), event);
    }

    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        checkEntityInteract(event.getEntityPlayer(), event.getTarget(), event);
    }

    private void checkEntityInteract(EntityPlayer player, Entity target, Event event) {
        Region region = getRegion(new Location(target));
        if (region != null && !region.canInteractEntities(player.getUniqueID())) {
            event.setCanceled(true);
            player.sendStatusMessage(Text.translation("chat.region.interact.entities").red().build(), true);
        }
    }

    @SubscribeEvent
    public void onBucketFill(FillBucketEvent event) {
        RayTraceResult target = event.getTarget();
        if (target != null) {
            Location location = new Location(event.getWorld(), target.getBlockPos());
            EntityPlayer player = event.getEntityPlayer();
            Region region = getRegion(location);
            if (region != null && !region.canInteractBlocks(player.getUniqueID())) {
                event.setResult(Event.Result.DENY);
                event.setCanceled(true);
                player.sendStatusMessage(Text.translation("chat.region.interact.blocks").red().build(), true);
            }
        }
    }

    @SubscribeEvent
    public void onDispenserShoot(DispenserShootEvent event) {
        net.minecraft.world.WorldServer world = (WorldServer) event.getWorld();
        onBlockFromTo(event, world, event.getPos(), event.getFacing(), true, event.getPlayer(world));
    }

    @SubscribeEvent
    public void onPistonCheckCanMove(PistonCheckCanMoveEvent event) {
        net.minecraft.world.WorldServer world = (WorldServer) event.getWorld();
        Location pistonPos = new Location(world, event.getPistonPos());
        Region pistonRegion = getRegion(pistonPos);
        Location movePos = new Location(world, event.getBlockToMove());
        Region moveRegion = getRegion(movePos);
        if (moveRegion != null && moveRegion != pistonRegion) {
            event.setResult(Event.Result.DENY);
            world.addScheduledTask(() -> {
                SPacketBlockChange packet = new SPacketBlockChange(pistonPos.getWorld(), pistonPos.getPos());
                for (EntityPlayer player : world.playerEntities) {
                    EntityPlayerMP p = (EntityPlayerMP) player;
                    if (pistonPos.distanceSq(new Location(p)) <= 512) {
                        p.connection.sendPacket(packet);
                    }
                }
            });
            return;
        }
        for (BlockPos pos : event.getToMove()) {
            Location loc = new Location(world, pos);
            Region reg = getRegion(loc);
            if (reg != null && reg != pistonRegion) {
                event.setResult(Event.Result.DENY);
                world.addScheduledTask(() -> {
                    for (BlockPos p : event.getToMove()) {
                        SPacketBlockChange packet = new SPacketBlockChange(world, p);
                        for (EntityPlayer player : world.playerEntities) {
                            EntityPlayerMP pl = (EntityPlayerMP) player;
                            if (p.distanceSq(pl.getPosition()) <= 512) {
                                pl.connection.sendPacket(packet);
                            }
                        }
                    }
                });
                return;
            }
        }
        for (BlockPos pos : event.getToDestroy()) {
            Location loc = new Location(world, pos);
            Region reg = getRegion(loc);
            if (reg != null && reg != pistonRegion) {
                event.setResult(Event.Result.DENY);
                world.addScheduledTask(() -> {
                    for (BlockPos p : event.getToDestroy()) {
                        SPacketBlockChange packet = new SPacketBlockChange(world, p);
                        for (EntityPlayer player : world.playerEntities) {
                            EntityPlayerMP pl = (EntityPlayerMP) player;
                            if (p.distanceSq(pl.getPosition()) <= 512) {
                                pl.connection.sendPacket(packet);
                            }
                        }
                    }
                });
                return;
            }
        }
    }

    @SubscribeEvent
    public void onFluidFlow(FluidFlowEvent event) {
        onBlockFromTo(event, event.getWorld(), event.getPos(), event.getFacing(), false, null);
    }

    private void onBlockFromTo(Event event, net.minecraft.world.World world, BlockPos pos, EnumFacing facing, boolean multiParticles, @Nullable EntityPlayer player) {
        Location from = new Location(world, pos);
        Location to = from.offset(facing);
        Region targetRegion = getRegion(to);
        if (targetRegion != null && targetRegion != getRegion(from) && (player == null || !targetRegion.canInteractBlocks(player.getUniqueID()))) {
            Random rand = world.rand;
            int max = multiParticles ? 2 + rand.nextInt(3) : 1;
            for (int i = 0; i < max; i++) {
                double x = (rand.nextDouble() - 0.5D) * 0.2D;
                double y = 0.2D + (rand.nextDouble() - 0.5D) * 0.2D;
                double z = (rand.nextDouble() - 0.5D) * 0.2D;
                to.spawnParticle(EnumParticleTypes.REDSTONE, x, y, z, 0, 0, 0);
            }
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        Explosion explosion = event.getExplosion();
        event.getAffectedBlocks().removeIf(pos -> {
            Location location = new Location(event.getWorld(), pos);
            return !location.isAir() && getRegion(location) != null;
        });
        event.getAffectedEntities().removeIf(entity -> {
            Location location = new Location(entity);
            return !location.isAir() && getRegion(location) != null;
        });
        EntityLivingBase placer = explosion.getExplosivePlacedBy();
        if (placer instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) placer;
            player.sendStatusMessage(Text.translation("chat.region.interact.explosions").red().build(), true);
        }
    }

    @SubscribeEvent
    public void onBlockBreakSpeedCheck(PlayerEvent.BreakSpeed event) {
        EntityPlayer player = event.getEntityPlayer();
        Location location = new Location(player.world, event.getPos());
        Region region = getRegion(location);
        if (region != null && !region.canEditBlocks(player.getUniqueID())) {
            event.setCanceled(true);
        }
    }
}
