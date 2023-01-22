package ru.craftlogic.regions.common.event;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.eventhandler.Event;
import ru.craftlogic.api.world.Player;
import ru.craftlogic.regions.WorldRegionManager.Region;

@Event.HasResult
public class RegionPvpStatusEvent extends Event {
    public final Region from, to;
    public final EntityPlayer attacker, target;

    public RegionPvpStatusEvent(Region from, Region to, EntityPlayer attacker, EntityPlayer target) {
        this.from = from;
        this.to = to;
        this.attacker = attacker;
        this.target = target;
    }
}
