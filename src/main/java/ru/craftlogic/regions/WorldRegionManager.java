package ru.craftlogic.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.JsonUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.craftlogic.api.math.Bounding;
import ru.craftlogic.api.server.Server;
import ru.craftlogic.api.util.ConfigurableManager;
import ru.craftlogic.api.world.Dimension;
import ru.craftlogic.api.world.Location;
import ru.craftlogic.api.world.OfflinePlayer;
import ru.craftlogic.api.world.World;
import ru.craftlogic.regions.network.message.MessageDeleteRegion;

import java.util.*;

public class WorldRegionManager extends ConfigurableManager {
    private static final Logger LOGGER = LogManager.getLogger("WorldRegionManager");

    private final Map<UUID, Region> regions = new HashMap<>();
    private final Dimension dimension;

    public WorldRegionManager(Server server, World world, Logger logger) {
        super(server, world.getDir().resolve("regions.json"), logger);
        this.dimension = world.getDimension();
    }

    @Override
    protected String getDefaultConfig() {
        return null;
    }

    @Override
    protected void load(JsonObject regions) {
        for (Map.Entry<String, JsonElement> entry : regions.entrySet()) {
            UUID id = UUID.fromString(entry.getKey());
            this.regions.put(id, new Region(this.dimension, id, entry.getValue().getAsJsonObject()));
        }
        LOGGER.info("Loaded {} regions for world {}", this.regions.size(), this.dimension.getName());
    }

    @Override
    protected void save(JsonObject regions) {
        for (Map.Entry<UUID, Region> entry : this.regions.entrySet()) {
            regions.add(entry.getKey().toString(), entry.getValue().toJson());
        }
        LOGGER.info("Saved {} regions for world {}", regions.size(), this.dimension.getName());
    }

    public Region createRegion(Location center, UUID owner, int width, int depth) {
        UUID id;
        while (this.regions.containsKey(id = UUID.randomUUID())) {}
        Region region = new Region(id, owner, center, width, depth, false, new HashMap<>());
        this.regions.put(id, region);
        this.setDirty(true);
        return region;
    }

    public Collection<Region> getAllRegions() {
        return this.regions.values();
    }

    public Region getRegion(UUID id) {
        return this.regions.get(id);
    }

    public List<Region> getPlayerRegions(UUID owner) {
        List<Region> result = new ArrayList<>();
        for (Region region : this.regions.values()) {
            if (region.owner.equals(owner)) {
                result.add(region);
            }
        }
        return result;
    }

    public Region deleteRegion(UUID id) {
        Region region;
        if ((region = this.regions.remove(id)) != null) {
            this.server.broadcastPacket(new MessageDeleteRegion(id));
            this.setDirty(true);
        }
        return region;
    }

    private static Map<UUID, Set<RegionAbility>> parseMembers(JsonObject members) {
        Map<UUID, Set<RegionAbility>> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : members.entrySet()) {
            UUID id = UUID.fromString(entry.getKey());
            Set<RegionAbility> abilities = new HashSet<>();
            for (JsonElement a : entry.getValue().getAsJsonArray()) {
                abilities.add(RegionAbility.valueOf(a.getAsString().toUpperCase()));
            }
            result.put(id, abilities);
        }
        return result;
    }

    public class Region implements Bounding {
        final UUID id;
        UUID owner;
        final Map<UUID, Set<RegionAbility>> members;
        final Location center;
        int width, depth;
        boolean pvp;

        public Region(Dimension dimension, UUID id, JsonObject root) {
            this(id,
                UUID.fromString(JsonUtils.getString(root, "owner")),
                Location.deserialize(dimension.getVanilla().getId(), root.getAsJsonObject("center")),
                JsonUtils.getInt(root, "width"),
                JsonUtils.getInt(root, "depth"),
                JsonUtils.getBoolean(root, "pvp"),
                root.has("members") ? parseMembers(JsonUtils.getJsonObject(root, "members")) : new HashMap<>()
            );
        }

        public Region(UUID id, UUID owner, Location center, int width, int depth, boolean pvp, Map<UUID, Set<RegionAbility>> members) {
            this.id = id;
            this.owner = owner;
            this.center = center;
            this.width = width;
            this.depth = depth;
            this.pvp = pvp;
            this.members = members;
        }

        public WorldRegionManager getManager() {
            return WorldRegionManager.this;
        }

        public boolean delete() {
            return getManager().deleteRegion(getId()) != null;
        }

        public UUID getId() {
            return id;
        }

        public UUID getOwner() {
            return owner;
        }

        public Set<UUID> getMembers() {
            return members.keySet();
        }

        public Location getCenter() {
            return center;
        }

        @Override
        public double getStartX() {
            return this.center.getBlockX() - this.width;
        }

        @Override
        public double getStartY() {
            return 0;
        }

        @Override
        public double getStartZ() {
            return this.center.getBlockZ() - this.depth;
        }

        @Override
        public double getEndX() {
            return this.center.getBlockX() + this.width;
        }

        @Override
        public double getEndY() {
            return this.center.getWorld().getHeight();
        }

        @Override
        public double getEndZ() {
            return this.center.getBlockZ() + this.depth;
        }

        public boolean isMember(OfflinePlayer target) {
            return isMember(target.getId());
        }

        public boolean isMember(UUID target) {
            return this.members.containsKey(target);
        }

        public boolean isOwner(OfflinePlayer target) {
            return isOwner(target.getId());
        }

        public boolean isOwner(UUID target) {
            return this.owner.equals(target);
        }

        public void setOwner(OfflinePlayer target) {
            setOwner(target.getId());
        }

        public void setOwner(UUID target) {
            this.owner = target;
        }

        public boolean canEditBlocks(OfflinePlayer target) {
            return canEditBlocks(target.getId());
        }

        public boolean canEditBlocks(UUID target) {
            return isOwner(target) || getMemberAbilities(target).contains(RegionAbility.EDIT_BLOCKS);
        }

        public boolean canInteractBlocks(OfflinePlayer target) {
            return canInteractBlocks(target.getId());
        }

        public boolean canInteractBlocks(UUID target) {
            return isOwner(target) || getMemberAbilities(target).contains(RegionAbility.INTERACT_BLOCKS);
        }

        public boolean canInteractEntities(OfflinePlayer target) {
            return canInteractEntities(target.getId());
        }

        public boolean canInteractEntities(UUID target) {
            return isOwner(target) || getMemberAbilities(target).contains(RegionAbility.INTERACT_ENTITIES);
        }

        public boolean canAttackHostiles(OfflinePlayer target) {
            return canAttackHostiles(target.getId());
        }

        public boolean canAttackHostiles(UUID target) {
            return isOwner(target) || getMemberAbilities(target).contains(RegionAbility.ATTACK_HOSTILES);
        }

        public boolean canAttackNeutral(OfflinePlayer target) {
            return canAttackNeutral(target.getId());
        }

        public boolean canAttackNeutral(UUID target) {
            return isOwner(target) || getMemberAbilities(target).contains(RegionAbility.ATTACK_NEUTRAL);
        }

        public boolean canLaunchProjectiles(OfflinePlayer target) {
            return canLaunchProjectiles(target.getId());
        }

        public boolean canLaunchProjectiles(UUID target) {
            return isOwner(target) || getMemberAbilities(target).contains(RegionAbility.LAUNCH_PROJECTILES);
        }

        public Set<RegionAbility> getMemberAbilities(UUID target) {
            return this.members.getOrDefault(target, Collections.emptySet());
        }

        public void setMemberAbilities(OfflinePlayer target, Set<RegionAbility> abilities) {
            setMemberAbilities(target.getId(), abilities);
        }


        public void setMemberAbilities(UUID target, Set<RegionAbility> abilities) {
            this.members.put(target, abilities);
        }

        public void setMemberAbility(OfflinePlayer target, RegionAbility ability, boolean allowed) {
            setMemberAbility(target.getId(), ability, allowed);
        }

        public void setMemberAbility(UUID target, RegionAbility ability, boolean allowed) {
            if (!this.members.containsKey(target) && allowed) {
                this.members.put(target, new HashSet<>());
            }
            Set<RegionAbility> abilities = getMemberAbilities(target);
            if (allowed) {
                abilities.add(ability);
            } else if (!abilities.isEmpty()) {
                abilities.remove(ability);
            }
        }

        public boolean isPvP() {
            return pvp;
        }

        public void setPvP(boolean pvp) {
            this.pvp = pvp;
        }

        public JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("owner", this.owner.toString());
            result.add("center", this.center.serialize());
            result.addProperty("width", this.width);
            result.addProperty("depth", this.depth);
            result.addProperty("pvp", this.pvp);
            if (!this.members.isEmpty()) {
                JsonObject members = new JsonObject();
                for (Map.Entry<UUID, Set<RegionAbility>> entry : this.members.entrySet()) {
                    JsonArray abilities = new JsonArray();
                    for (RegionAbility ability : entry.getValue()) {
                        abilities.add(ability.name().toLowerCase());
                    }
                    if (abilities.size() > 0) {
                        members.add(entry.getKey().toString(), abilities);
                    }
                }
                if (members.size() > 0) {
                    result.add("members", members);
                }
            }
            return result;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Region)) return false;
            Region region = (Region) o;
            return Objects.equals(id, region.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    public enum RegionAbility {
        INTERACT_BLOCKS, INTERACT_ENTITIES, EDIT_BLOCKS, ATTACK_HOSTILES, ATTACK_NEUTRAL, LAUNCH_PROJECTILES
    }
}
