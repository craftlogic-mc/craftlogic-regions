package ru.craftlogic.regions.network.message;

import com.mojang.authlib.GameProfile;
import net.minecraft.util.math.BlockPos;
import ru.craftlogic.api.network.AdvancedBuffer;
import ru.craftlogic.api.network.AdvancedMessage;
import ru.craftlogic.api.network.AdvancedNetwork;
import ru.craftlogic.api.server.PlayerManager;
import ru.craftlogic.api.server.Server;
import ru.craftlogic.api.world.Location;
import ru.craftlogic.api.world.OfflinePlayer;
import ru.craftlogic.regions.CraftRegions;
import ru.craftlogic.regions.WorldRegionManager.Region;
import ru.craftlogic.regions.WorldRegionManager.RegionAbility;

import java.io.IOException;
import java.util.*;

public class MessageRegion extends AdvancedMessage {
    private UUID id;
    private int dimension;
    private BlockPos pos;
    private GameProfile owner;
    private Map<GameProfile, Set<RegionAbility>> members = new HashMap<>();
    private int width, depth;
    private boolean pvp;

    public MessageRegion() {}

    public MessageRegion(Server server, Region region) {
        this.id = region.getId();
        PlayerManager playerManager = server.getPlayerManager();
        OfflinePlayer owner = playerManager.getOffline(region.getOwner());
        this.owner = owner == null ? new GameProfile(region.getOwner(), null) : owner.getProfile();
        for (UUID m : region.getMembers()) {
            OfflinePlayer member = playerManager.getOffline(m);
            this.members.put(member == null ? new GameProfile(m, null) : member.getProfile(), region.getMemberAbilities(m));
        }
        Location center = region.getCenter();
        this.dimension = center.getDimension();
        this.pos = center.getPos();
        this.width = (int) (Math.abs(region.getStartX() - region.getEndX()) / 2);
        this.depth = (int) (Math.abs(region.getStartZ() - region.getEndZ()) / 2);
        this.pvp = region.isPvP();
    }

    @Override
    public AdvancedNetwork getNetwork() {
        return CraftRegions.NETWORK;
    }

    @Override
    protected void read(AdvancedBuffer buf) throws IOException {
        this.id = buf.readUniqueId();
        this.owner = buf.readProfile();
        int m = buf.readInt();
        if (m > 0) {
            this.members.clear();
            for (int i = 0; i < m; i++) {
                GameProfile member = buf.readProfile();
                Set<RegionAbility> abilities = new HashSet<>();
                int a = buf.readInt();
                for (int j = 0; j < a; j++) {
                    abilities.add(buf.readEnumValue(RegionAbility.class));
                }
                this.members.put(member, abilities);
            }
        }
        this.dimension = buf.readInt();
        this.pos = buf.readBlockPos();
        this.width = buf.readInt();
        this.depth = buf.readInt();
        this.pvp = buf.readBoolean();
    }

    @Override
    protected void write(AdvancedBuffer buf) throws IOException {
        buf.writeUniqueId(this.id);
        buf.writeProfile(this.owner);
        buf.writeInt(this.members.size());
        for (Map.Entry<GameProfile, Set<RegionAbility>> entry : this.members.entrySet()) {
            buf.writeProfile(entry.getKey());
            Set<RegionAbility> abilities = entry.getValue();
            buf.writeInt(abilities.size());
            for (RegionAbility ability : abilities) {
                buf.writeEnumValue(ability);
            }
        }
        buf.writeInt(this.dimension);
        buf.writeBlockPos(this.pos);
        buf.writeInt(this.width);
        buf.writeInt(this.depth);
        buf.writeBoolean(this.pvp);
    }

    public UUID getId() {
        return id;
    }

    public GameProfile getOwner() {
        return owner;
    }

    public Set<GameProfile> getMembers() {
        return members.keySet();
    }

    public Set<RegionAbility> getAbilities(GameProfile id) {
        return members.getOrDefault(id, Collections.emptySet());
    }

    public int getDimension() {
        return dimension;
    }

    public BlockPos getPos() {
        return pos;
    }

    public int getWidth() {
        return width;
    }

    public int getDepth() {
        return depth;
    }

    public boolean isPvP() {
        return pvp;
    }
}
