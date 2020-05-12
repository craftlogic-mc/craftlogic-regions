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

    public Region createRegion(Location start, Location end, UUID owner) {
        UUID id;
        while (regions.containsKey(id = UUID.randomUUID())) {}
        Region region = new Region(id, owner, start, end, false, new HashMap<>());
        regions.put(id, region);
        setDirty(true);
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
        if ((region = regions.remove(id)) != null) {
            server.broadcastPacket(new MessageDeleteRegion(id));
            setDirty(true);
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
        final Location start, end;
        boolean pvp;

        public Region(Dimension dimension, UUID id, JsonObject root) {
            this(id,
                UUID.fromString(JsonUtils.getString(root, "owner")),
                Location.deserialize(dimension.getVanilla().getId(), root.getAsJsonObject("start")),
                Location.deserialize(dimension.getVanilla().getId(), root.getAsJsonObject("end")),
                JsonUtils.getBoolean(root, "pvp"),
                root.has("members") ? parseMembers(JsonUtils.getJsonObject(root, "members")) : new HashMap<>()
            );
        }

        public Region(UUID id, UUID owner, Location start, Location end, boolean pvp, Map<UUID, Set<RegionAbility>> members) {
            this.id = id;
            this.owner = owner;
            this.start = start;
            this.end = end;
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

        public Location getStart() {
            return start;
        }

        public Location getEnd() {
            return end;
        }

        @Override
        public double getStartX() {
            return Math.min(start.getBlockX(), end.getBlockX());
        }

        @Override
        public double getStartY() {
            return 0;//Math.min(start.getBlockY(), end.getBlockY());
        }

        @Override
        public double getStartZ() {
            return Math.min(start.getBlockZ(), end.getBlockZ());
        }

        @Override
        public double getEndX() {
            return Math.max(start.getBlockX(), end.getBlockX());
        }

        @Override
        public double getEndY() {
            return 256;//Math.max(start.getBlockY(), end.getBlockY());
        }

        @Override
        public double getEndZ() {
            return Math.max(start.getBlockZ(), end.getBlockZ());
        }

        public boolean isMember(OfflinePlayer target) {
            return isMember(target.getId());
        }

        public boolean isMember(UUID target) {
            return members.containsKey(target);
        }

        public boolean isOwner(OfflinePlayer target) {
            return isOwner(target.getId());
        }

        public boolean isOwner(UUID target) {
            return owner.equals(target);
        }

        public void setOwner(OfflinePlayer target) {
            setOwner(target.getId());
        }

        public void setOwner(UUID target) {
            owner = target;
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
            result.add("start", this.start.serialize());
            result.add("end", this.end.serialize());
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
