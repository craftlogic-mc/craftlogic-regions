package ru.craftlogic.regions.common.command;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import ru.craftlogic.api.command.CommandBase;
import ru.craftlogic.api.command.CommandContext;
import ru.craftlogic.api.text.Text;
import ru.craftlogic.api.world.Player;
import ru.craftlogic.regions.CraftRegions;

import java.util.Collections;

public class CommandWand extends CommandBase {
    public CommandWand() {
        super("wand", 0, "");
        Collections.addAll(aliases, "/wand");
    }

    @Override
    protected void execute(CommandContext ctx) throws Throwable {
        Player player = ctx.senderAsPlayer();
        EntityPlayerMP entity = player.getEntity();
        ItemStack wand = new ItemStack(Item.REGISTRY.getObject(new ResourceLocation(CraftRegions.MOD_ID, "wand")));
        entity.inventory.addItemStackToInventory(wand);
        player.sendMessage(Text.translation("chat.region.wand").green());
    }
}
