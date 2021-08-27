package ru.craftlogic.regions.common;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import ru.craftlogic.api.CraftItems;
import ru.craftlogic.api.event.server.ServerAddManagersEvent;
import ru.craftlogic.api.network.AdvancedMessage;
import ru.craftlogic.api.network.AdvancedMessageHandler;
import ru.craftlogic.regions.RegionManager;
import ru.craftlogic.regions.common.item.ItemWand;
import ru.craftlogic.regions.network.message.MessageConfiguration;
import ru.craftlogic.regions.network.message.MessageDeleteRegion;
import ru.craftlogic.regions.network.message.MessageOverride;
import ru.craftlogic.regions.network.message.MessageRegion;
import ru.craftlogic.util.ReflectiveUsage;

import static ru.craftlogic.regions.CraftRegions.NETWORK;

@ReflectiveUsage
public class ProxyCommon extends AdvancedMessageHandler {
    public void preInit() {
        CraftItems.registerItem(new ItemWand());
    }

    public void init() {
        NETWORK.registerMessage(this::handleRegion, MessageRegion.class, Side.CLIENT);
        NETWORK.registerMessage(this::handleDeleteRegion, MessageDeleteRegion.class, Side.CLIENT);
        NETWORK.registerMessage(this::handleOverride, MessageOverride.class, Side.CLIENT);
        NETWORK.registerMessage(this::handleConfiguration, MessageConfiguration.class, Side.CLIENT);
    }

    public void postInit() {

    }

    protected AdvancedMessage handleRegion(MessageRegion message, MessageContext context) {
        return null;
    }

    protected AdvancedMessage handleDeleteRegion(MessageDeleteRegion message, MessageContext context) {
        return null;
    }

    protected AdvancedMessage handleOverride(MessageOverride message, MessageContext context) {
        return null;
    }

    protected AdvancedMessage handleConfiguration(MessageConfiguration message, MessageContext context) {
        return null;
    }

    @SubscribeEvent
    public void onServerAddManagers(ServerAddManagersEvent event) {
        event.addManager(RegionManager.class, RegionManager::new);
    }
}
