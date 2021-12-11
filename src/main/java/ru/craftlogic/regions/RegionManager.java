package ru.craftlogic.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.command.CommandException;
import net.minecraft.entity.*;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityPotion;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.Explosion;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.*;
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
import ru.craftlogic.api.CraftSounds;
import ru.craftlogic.api.event.block.DispenserShootEvent;
import ru.craftlogic.api.event.block.FarmlandTrampleEvent;
import ru.craftlogic.api.event.block.FluidFlowEvent;
import ru.craftlogic.api.event.block.PistonCheckCanMoveEvent;
import ru.craftlogic.api.event.player.PlayerCheckCanEditEvent;
import ru.craftlogic.api.event.player.PlayerHookEntityEvent;
import ru.craftlogic.api.event.player.PlayerPlaceBoatEvent;
import ru.craftlogic.api.event.player.PlayerTeleportHomeEvent;
import ru.craftlogic.api.math.Bounding;
import ru.craftlogic.api.math.BoxBounding;
import ru.craftlogic.api.server.PlayerManager;
import ru.craftlogic.api.server.Server;
import ru.craftlogic.api.server.WorldManager;
import ru.craftlogic.api.text.Text;
import ru.craftlogic.api.util.ConfigurableManager;
import ru.craftlogic.api.world.*;
import ru.craftlogic.common.command.CommandManager;
import ru.craftlogic.common.entity.projectile.EntityThrownItem;
import ru.craftlogic.regions.WorldRegionManager.Region;
import ru.craftlogic.regions.common.command.CommandRegion;
import ru.craftlogic.regions.common.command.CommandRegions;
import ru.craftlogic.regions.common.command.CommandWand;
import ru.craftlogic.regions.network.message.MessageConfiguration;
import ru.craftlogic.regions.network.message.MessageOverride;
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
    private boolean defaultPvP;
    public Set<ResourceLocation> whitelistBlockUsage = new HashSet<>();
    public Set<ResourceLocation> blacklistItemUsage = new HashSet<>();
    public Set<ResourceLocation> chests = new HashSet<>();
    public Set<ResourceLocation> doors = new HashSet<>();

    public RegionManager(Server server, Path settingsDirectory) {
        super(server, settingsDirectory.resolve("regions.json"), LOGGER);
    }

    WorldRegionManager getWorld(String world) {
        return managers.get(world);
    }

    public WorldRegionManager getWorld(LocatableCommandSender lcs) {
        return getWorld(lcs.getLocation().getWorldName());
    }

    public WorldRegionManager getWorld(Location l) {
        return getWorld(l.getWorldName());
    }

    @Override
    public void registerCommands(CommandManager commandManager) {
        commandManager.registerCommand(new CommandRegion());
        commandManager.registerCommand(new CommandWand());
        commandManager.registerCommand(new CommandRegions());
        commandManager.registerArgumentType("Region", false, ctx -> {
            RegionManager regionManager = ctx.server().getManager(RegionManager.class);
            CommandSender sender = ctx.sender();
            return (sender instanceof Player ? regionManager.getPlayerRegions((Player) sender, ((Player) sender).getWorld()) : regionManager.getAllLoadedRegions())
                .stream()
                .map(WorldRegionManager.Region::getId)
                .map(UUID::toString)
                .collect(Collectors.toList());
        });
    }

    private void readResourceLocations(Set<ResourceLocation> list, JsonObject config, String key) {
        list.clear();
        if (config.has(key))  {
            JsonArray array = config.getAsJsonArray(key);
            for (JsonElement e : array) {
                list.add(new ResourceLocation(e.getAsString()));
            }
        }
    }

    private void writeResourceLocations(Set<ResourceLocation> list, JsonObject config, String key) {
        JsonArray array = new JsonArray();
        for (ResourceLocation res : list) {
            array.add(res.toString());
        }
        config.add(key, array);
    }

    @Override
    public void load(JsonObject config) {
        loaded = true;
        defaultPvP = JsonUtils.getBoolean(config, "default-pvp", false);

        readResourceLocations(whitelistBlockUsage, config, "block_usage_whitelist");
        readResourceLocations(blacklistItemUsage, config, "item_usage_blacklist");
        readResourceLocations(chests, config, "custom_chests");
        readResourceLocations(doors, config, "custom_doors");

        for (WorldRegionManager manager : managers.values()) {
            try {
                manager.load();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean omitRegionSave = false;

    @Override
    public void save(JsonObject config) {
        config.addProperty("default-pvp", defaultPvP);

        writeResourceLocations(whitelistBlockUsage, config, "block_usage_whitelist");
        writeResourceLocations(blacklistItemUsage, config, "item_usage_blacklist");
        writeResourceLocations(chests, config, "custom_chests");
        writeResourceLocations(doors, config, "custom_doors");

        if (!omitRegionSave) {
            for (WorldRegionManager manager : managers.values()) {
                try {
                    manager.save(true);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void saveConfiguration() throws IOException {
        omitRegionSave = true;
        save(true);
        omitRegionSave = false;
    }

    public void tryCreateRegion(Player sender, Location start, Location end) throws CommandException {
        WorldRegionManager world = getWorld(sender);
        if (!world.enabled && !sender.hasPermission("commands.region.create.dimension_bypass." + start.getWorldName())) {
            throw new CommandException("commands.region.create.dimension_disabled");
        }
        int width = Math.abs(start.getBlockX() - end.getBlockX()) + 1;
        int depth = Math.abs(start.getBlockZ() - end.getBlockZ()) + 1;
        int maxArea = sender.getPermissionMetadata("region.max-area", 100 * 100, Integer::parseInt);
        int maxCount = sender.getPermissionMetadata("region.max-count", 5, Integer::parseInt);

        int area = width * depth;
        int count = getPlayerRegions(sender, sender.getWorld()).size();

        if (count >= maxCount) {
            throw new CommandException("commands.region.create.too_many", count, maxCount);
        }
        if (area > maxArea) {
            throw new CommandException("commands.region.create.too_large", area, maxArea);
        }

        List<WorldRegionManager.Region> regions = getNearbyRegions(start, end, false);
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
                    List<WorldRegionManager.Region> r = getNearbyRegions(start, end, false);
                    if (r.isEmpty()) {
                        WorldRegionManager.Region region = createRegion(start, end, sender);
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

    public Region createRegion(Location start, Location end, OfflinePlayer owner) {
        return createRegion(start, end, owner.getId());
    }

    public Region createRegion(Location start, Location end, UUID owner) {
        WorldRegionManager manager = getWorld(start);
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

    public List<Region> getPlayerRegions(OfflinePlayer owner, World world) {
        return getPlayerRegions(owner.getId(), world);
    }

    public List<Region> getPlayerRegions(UUID owner, World world) {
        if (world == null) {
            List<Region> result = new ArrayList<>();
            for (WorldRegionManager manager : managers.values()) {
                List<Region> regions = manager.getPlayerRegions(owner);
                if (regions != null) {
                    result.addAll(regions);
                }
            }
        } else {
            WorldRegionManager m = getWorld(world.getDimension().getVanilla().getName());
            if (m != null) {
                List<Region> regions = m.getPlayerRegions(owner);
                if (regions != null) {
                    return new ArrayList<>(regions);
                }
            }
        }
        return Collections.emptyList();
    }

    public Region getRegion(Location location) {
        WorldRegionManager manager = getWorld(location);
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
        WorldRegionManager manager = getWorld(start);
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
    public void onPlayerJoin(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
        EntityPlayer entity = event.player;
        if (!entity.world.isRemote && entity instanceof EntityPlayerMP) {
            Player player = Player.from((EntityPlayerMP) entity);
            boolean override = hasOverride(player);
            syncConfiguration(player);
            player.sendPacket(new MessageOverride(override));
        }
    }

    public void syncConfiguration(Player player) {
        player.sendPacket(new MessageConfiguration(whitelistBlockUsage, blacklistItemUsage, chests, doors));
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        World world = World.fromVanilla(server, event.getWorld());
        if (world != null) {
            WorldRegionManager manager = new WorldRegionManager(server, world, defaultPvP, LOGGER);
            managers.put(world.getDimension().getVanilla().getName(), manager);
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
    public void onPlayerTeleportHome(PlayerTeleportHomeEvent event) {
        if (event.bedLocation != null) {
            Region targetRegion = getRegion(event.bedLocation);
            if (targetRegion != null && !targetRegion.canInteractBlocks(event.player)) {
                PlayerManager playerManager = event.player.getServer().getPlayerManager();
                OfflinePlayer owner = playerManager.getOffline(targetRegion.getOwner());
                if (owner != null && !event.player.hasPermission("commands.home.teleport.others")) {
                    event.context.sendMessage(Text.translation("commands.home.region_permission").arg(owner.getName()).red());
                    event.setCanceled(true);
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
    public void onLivingSetAttackTarget(LivingSetAttackTargetEvent event) {
        EntityLivingBase attacker = event.getEntityLiving();
        EntityLivingBase target = event.getTarget();
        if (target instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) target;
            if (checkProtectFromMob(player, attacker)) {
                ((EntityLiving) attacker).setAttackTarget(null);
            }
        }
    }

    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        EntityLivingBase victim = event.getEntityLiving();
        DamageSource source = event.getSource();
        if (source instanceof EntityDamageSourceIndirect) {
            Entity attacker = source.getTrueSource();
            if (attacker instanceof EntityPlayer && checkAttack(((EntityPlayer) attacker), victim)) {
                event.setCanceled(true);
            } else if (attacker instanceof IMob && victim instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) victim;
                if (checkProtectFromMob(player, attacker)) {
                    event.setCanceled(true);
                }
            }
        } else if (source.isProjectile()) {
            Entity attacker = source.getTrueSource();
            if (attacker instanceof EntityPlayer && checkAttack(((EntityPlayer) attacker), victim)) {
                event.setCanceled(true);
            } else if (attacker instanceof IMob && victim instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) victim;
                if (checkProtectFromMob(player, attacker)) {
                    event.setCanceled(true);
                }
            }
        } else if (source instanceof EntityDamageSource) {
            Entity attacker = source.getTrueSource();
            if (attacker instanceof IMob && victim instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) victim;
                if (checkProtectFromMob(player, attacker)) {
                    event.setCanceled(true);
                }
            }
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
    public void onHookEntity(PlayerHookEntityEvent event) {
        EntityPlayer angler = event.getAngler();
        Entity target = event.getEntity();
        Region region = getRegion(new Location(target));
        if (region != null && !region.canHookEntity(angler.getGameProfile().getId())) {
            event.setCanceled(true);
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
                    if (checkAttack(((EntityPlayer) thrower), target.entityHit)) {
                        event.setCanceled(true);
                        if (throwable instanceof EntityPotion) {
                            throwable.entityDropItem(((EntityPotion) throwable).getPotion(), 0F);
                            throwable.setDead();
                        } else if (throwable instanceof EntityThrownItem) {
                            throwable.entityDropItem(((EntityThrownItem) throwable).getItem(), 0F);
                            ((EntityThrownItem) throwable).setItem(ItemStack.EMPTY);
                            throwable.setDead();
                        }
                    } else if (!region.canLaunchProjectiles(thrower.getUniqueID())) {
                        event.setCanceled(true);
                        if (throwable instanceof EntityPotion) {
                            throwable.entityDropItem(((EntityPotion) throwable).getPotion(), 0F);
                            throwable.setDead();
                            ((EntityPlayer) thrower).sendStatusMessage(Text.translation("chat.region.interact.potions").red().build(), true);
                        } else {
                            if (throwable instanceof EntityThrownItem) {
                                throwable.entityDropItem(((EntityThrownItem) throwable).getItem(), 0F);
                                ((EntityThrownItem) throwable).setItem(ItemStack.EMPTY);
                                throwable.setDead();
                            }
                            ((EntityPlayer) thrower).sendStatusMessage(Text.translation("chat.region.interact.projectiles").red().build(), true);
                        }
                    }
                }
            }
        } else if (thrower instanceof EntityPlayer) {
            Region region = getRegion(new Location(throwable));
            if (region != null && !region.canLaunchProjectiles(thrower.getUniqueID())) {
                if (throwable instanceof EntityPotion) {
                    event.setCanceled(true);
                    throwable.entityDropItem(((EntityPotion) throwable).getPotion(), 0F);
                    throwable.setDead();
                    ((EntityPlayer) thrower).sendStatusMessage(Text.translation("chat.region.interact.potions").red().build(), true);
                } else if (throwable instanceof EntityThrownItem) {
                    throwable.entityDropItem(((EntityThrownItem) throwable).getItem(), 0F);
                    ((EntityThrownItem) throwable).setItem(ItemStack.EMPTY);
                    throwable.setDead();
                    ((EntityPlayer) thrower).sendStatusMessage(Text.translation("chat.region.interact.projectiles").red().build(), true);
                } else { //TODO generic throwable drop support
                    throwable.setDead();
                    ((EntityPlayer) thrower).sendStatusMessage(Text.translation("chat.region.interact.projectiles").red().build(), true);
                }
            }
        }
    }

    private boolean checkProtectFromMob(EntityPlayer player, Entity mob) {
        Region targetRegion = getRegion(new Location(player));
        return targetRegion != null && targetRegion.isPreventingMobAttacks();
    }

    private boolean checkAttack(EntityPlayer player, Entity target) {
        Region targetRegion = getRegion(new Location(target));
        if (target instanceof EntityPlayer) {
            Region fromRegion = getRegion(new Location(player));
            if (fromRegion != null && !fromRegion.isPvP() || targetRegion != null && !targetRegion.isPvP()) {
                player.sendStatusMessage(Text.translation("chat.region.attack.players").red().build(), true);
                return true;
            }
        } else if (target instanceof INpc) {
            if (targetRegion != null && !targetRegion.canAttackNeutral(player.getUniqueID())) {
                player.sendStatusMessage(Text.translation("chat.region.attack.npc").red().build(), true);
                return true;
            }
        } else if (target instanceof IMob) {
            if (targetRegion != null && targetRegion.isProtectingHostiles() && !targetRegion.canAttackHostiles(player.getUniqueID())) {
                player.sendStatusMessage(Text.translation("chat.region.attack.monsters").red().build(), true);
                return true;
            }
        } else if (target instanceof IAnimals) {
            if (targetRegion != null && !targetRegion.canAttackNeutral(player.getUniqueID())) {
                player.sendStatusMessage(Text.translation("chat.region.attack.animals").red().build(), true);
                return true;
            }
        } else if (target instanceof EntityHanging || target instanceof EntityArmorStand) {
            if (targetRegion != null && !targetRegion.canEditBlocks(player.getUniqueID())) {
                player.sendStatusMessage(Text.translation("chat.region.attack.hanging").red().build(), true);
                return true;
            }
        }  else if (target instanceof EntityMinecartEmpty || target instanceof EntityBoat) {
            if (targetRegion != null && !targetRegion.canEditBlocks(player.getUniqueID())) {
                player.sendStatusMessage(Text.translation("chat.region.attack.transport").red().build(), true);
                return true;
            }
        } else {
            if (targetRegion != null && !targetRegion.canEditBlocks(player.getUniqueID())) {
                player.sendStatusMessage(Text.translation("chat.region.attack.entities").red().build(), true);
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public void onPlaceBoat(PlayerPlaceBoatEvent event) {
        EntityPlayer player = event.getEntityPlayer();
        RayTraceResult target = event.target;
        Location location = new Location(player.world, target.hitVec);
        Region region = getRegion(location);
        if (region != null && !region.canInteractBlocks(player.getUniqueID())) {
            event.setCanceled(true);
            Text<?, ?> message = Text.translation("chat.region.interact.blocks");
            player.sendStatusMessage(message.red().build(), true);
        }
    }

    @SubscribeEvent
    public void onEntitySpawn(LivingSpawnEvent.CheckSpawn event) {
        EntityLivingBase entity = event.getEntityLiving();
        Location location = new Location(event.getWorld(), new BlockPos(entity));
        Region region = getRegion(location);
        if (region != null && !region.canSpawnMobs()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onBlockRightClick(PlayerInteractEvent.RightClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        Location location = new Location(event.getWorld(), event.getPos());
        if (!location.isAir()) {
            Region region = getRegion(location);
            if (region != null && !region.canInteractBlocks(player.getUniqueID())) {
                boolean whitelisted = whitelistBlockUsage.contains(location.getBlock().getRegistryName());
                if (!whitelisted) {
                    event.setUseBlock(Event.Result.DENY);
                }
                ItemStack heldItem = event.getItemStack();
                if (heldItem.getItem() instanceof ItemBlock || blacklistItemUsage.contains(heldItem.getItem().getRegistryName())) {
                    event.setUseItem(Event.Result.DENY);
                }
                if (event.getHand() == EnumHand.MAIN_HAND) {
                    Text<?, ?> message;
                    if (isLockedDoor(location)) {
                        location.playSound(CraftSounds.OPENING_FAILED, SoundCategory.PLAYERS, 1F, 1F);
                        message = Text.translation("chat.region.interact.doors");
                    } else if (isLockedChest(location)) {
                        location.playSound(CraftSounds.OPENING_FAILED, SoundCategory.PLAYERS, 1F, 1F);
                        message = Text.translation("chat.region.interact.chests");
                    } else if (!whitelisted) {
                        message = Text.translation("chat.region.interact.blocks");
                    } else {
                        return;
                    }
                    player.sendStatusMessage(message.red().build(), true);
                }
            }
        }
    }

    public boolean isLockedDoor(Location location) {
        Block block = location.getBlock();
        if (doors.contains(block.getRegistryName())) {
            return true;
        }
        return (block instanceof BlockDoor || block instanceof BlockTrapDoor || block instanceof BlockFenceGate) && location.getBlockMaterial() == Material.WOOD;
    }

    public boolean isLockedChest(Location location) {
        Block block = location.getBlock();
        if (chests.contains(block.getRegistryName())) {
            return true;
        }
        return (block instanceof BlockChest) && location.getBlockMaterial() == Material.WOOD;
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
            if (target instanceof EntityPlayer) {
                player.sendStatusMessage(Text.translation("chat.region.interact.players").red().build(), true);
            } else if (target instanceof EntityHanging || target instanceof EntityArmorStand) {
                player.sendStatusMessage(Text.translation("chat.region.interact.hanging").red().build(), true);
            } else if (target instanceof EntityMinecartEmpty || target instanceof EntityBoat) {
                player.sendStatusMessage(Text.translation("chat.region.interact.transport").red().build(), true);
            } else if (target instanceof INpc) {
                player.sendStatusMessage(Text.translation("chat.region.interact.npc").red().build(), true);
            } else if (target instanceof IMob) {
                player.sendStatusMessage(Text.translation("chat.region.interact.monsters").red().build(), true);
            } else if (target instanceof IAnimals) {
                player.sendStatusMessage(Text.translation("chat.region.interact.animals").red().build(), true);
            } else {
                player.sendStatusMessage(Text.translation("chat.region.interact.entities").red().build(), true);
            }
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
        if (!event.getWorld().isRemote) {
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

    private static final GameProfile MINECRAFT = new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[Minecraft]");

    @SubscribeEvent
    public void onEntityDestroyBlock(LivingDestroyBlockEvent event) {
        Entity entity = event.getEntity();
        Location location = new Location(entity.world, event.getPos());
        Region region = getRegion(location);
        if (region != null && !region.canEditBlocks(MINECRAFT.getId())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        Explosion explosion = event.getExplosion();
        List<BlockPos> blocks = event.getAffectedBlocks();
        List<Entity> entities = event.getAffectedEntities();
        int bc = blocks.size();
        int ec = entities.size();
        blocks.removeIf(pos -> {
            Location location = new Location(event.getWorld(), pos);
            Region region = getRegion(location);
            return !location.isAir() && region != null && !region.explosions;
        });
        entities.removeIf(entity -> {
            Location location = new Location(entity);
            Region region = getRegion(location);
            return region != null && (!(entity instanceof EntityPlayer) || !region.pvp || !region.explosions);
        });
        EntityLivingBase placer = explosion.getExplosivePlacedBy();
        if (placer instanceof EntityPlayer && (bc != blocks.size() || ec != entities.size())) {
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

    public boolean toggleOverride(Player sender) throws CommandException {
        WorldRegionManager manager = getWorld(sender);
        if (manager != null) {
            boolean result = !manager.regionAccessOverrides.remove(sender.getId()) && manager.regionAccessOverrides.add(sender.getId());
            sender.sendPacket(new MessageOverride(result));
            return result;
        } else {
            throw new CommandException("commands.generic.world.notFound", sender.getWorldName());
        }
    }

    public boolean hasOverride(Player player) {
        WorldRegionManager manager = getWorld(player);
        if (manager != null) {
            return manager.regionAccessOverrides.contains(player.getId());
        } else {
            return false;
        }
    }
}
