package ru.craftlogic.regions.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import ru.craftlogic.api.CraftSounds;
import ru.craftlogic.api.math.Bounding;
import ru.craftlogic.api.network.AdvancedMessage;
import ru.craftlogic.api.text.Text;
import ru.craftlogic.api.world.Location;
import ru.craftlogic.regions.WorldRegionManager.RegionAbility;
import ru.craftlogic.regions.common.ProxyCommon;
import ru.craftlogic.regions.network.message.MessageDeleteRegion;
import ru.craftlogic.regions.network.message.MessageRegion;
import ru.craftlogic.util.ReflectiveUsage;

import java.util.*;

import static java.lang.Math.max;
import static java.lang.Math.min;

@ReflectiveUsage
@SideOnly(Side.CLIENT)
public class ProxyClient extends ProxyCommon {
    private final Minecraft client = FMLClientHandler.instance().getClient();
    private Map<UUID, VisualRegion> regions = new HashMap<>();
    private boolean showRegionsThroughBlocks = false;

    @Override
    public void preInit() {
        super.preInit();
    }

    @Override
    public void init() {
        super.init();
    }

    @Override
    public void postInit() {
        super.postInit();
    }

    @Override
    protected AdvancedMessage handleRegion(MessageRegion message, MessageContext context) {
        syncTask(context, () -> {
            if (this.client.world != null && message.getDimension() == this.client.world.provider.getDimension()) {
                UUID id = message.getId();
                VisualRegion region = new VisualRegion(message, getPlayer(context));
                this.regions.put(id, region);
            }
        });
        return null;
    }

    @Override
    protected AdvancedMessage handleDeleteRegion(MessageDeleteRegion message, MessageContext context) {
        syncTask(context, () -> this.regions.remove(message.getId()));
        return null;
    }

    private VisualRegion getRegion(Location location) {
        for (VisualRegion region : this.regions.values()) {
            if (region.isOwning(location)) {
                return region;
            }
        }
        return null;
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        World world = event.getWorld();
        if (world != null && world.isRemote) {
            this.regions.clear();
        }
    }

  /*@SubscribeEvent
    public void onTextRender(RenderGameOverlayEvent.Text event) {
        EntityPlayer player = this.client.player;
        if (player != null && this.client.currentScreen == null) {
            for (VisualRegion region : this.regions.values()) {
                if (region.isOwning(player.posX, player.posY, player.posZ)) {
                    region.renderTextOverlay(this.client, event);
                }
            }
        }
    }*/

    @SubscribeEvent
    public void onWorldRenderLast(RenderWorldLastEvent event) {
        if (this.client.gameSettings.showDebugInfo) {
            this.client.mcProfiler.startSection("regions");
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
            GlStateManager.color(1F, 1F, 1F, 1F);
            GlStateManager.pushMatrix();
            GlStateManager.enableAlpha();
            GlStateManager.doPolygonOffset(-3F, -3F);
            GlStateManager.enablePolygonOffset();
            GlStateManager.enableAlpha();
            GlStateManager.enableTexture2D();
            GlStateManager.glLineWidth(3F);

            if (this.showRegionsThroughBlocks) {
                GlStateManager.depthMask(false);
                GlStateManager.disableDepth();
            }

            for (VisualRegion region : this.regions.values()) {
                region.renderVolumetric(this.client, event.getPartialTicks());
            }

            if (this.showRegionsThroughBlocks) {
                GlStateManager.enableDepth();
                for (VisualRegion region : this.regions.values()) {
                    region.renderVolumetric(this.client, event.getPartialTicks());
                }
            }

            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.disableAlpha();
            GlStateManager.doPolygonOffset(0F, 0F);
            GlStateManager.disablePolygonOffset();
            GlStateManager.enableAlpha();

            if (this.showRegionsThroughBlocks) {
                GlStateManager.depthMask(true);
            }
            GlStateManager.popMatrix();
            this.client.mcProfiler.endSection();
        }
    }

    @SubscribeEvent
    public void onBlockRightClick(PlayerInteractEvent.RightClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        Location location = new Location(event.getWorld(), event.getPos());
        if (location.isWorldRemote() && !location.isAir() && this.client.playerController.getCurrentGameType() != GameType.SPECTATOR) {
            VisualRegion region = this.getRegion(location);
            if (region != null && !region.interactBlocks && !region.owner.getId().equals(player.getUniqueID())) {
                event.setUseBlock(Event.Result.DENY);
                player.swingArm(event.getHand());
                player.sendStatusMessage(Text.translation("chat.region.interact.blocks").darkRed().build(), true);

                Block block = location.getBlock();

                if ((block instanceof BlockDoor || block instanceof BlockTrapDoor || block instanceof BlockChest) &&
                        location.getBlockMaterial() == Material.WOOD) {

                    location.playSound(CraftSounds.OPENING_FAILED, SoundCategory.PLAYERS, 1F, 1F);
                }
            }
        }
    }

    @SubscribeEvent
    public void onBlockLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        Location location = new Location(event.getWorld(), event.getPos());
        if (location.isWorldRemote() && !location.isAir() && this.client.playerController.getCurrentGameType() != GameType.SPECTATOR) {
            VisualRegion region = this.getRegion(location);
            if (region != null && !region.interactBlocks && !region.owner.getId().equals(player.getUniqueID())) {
                event.setUseBlock(Event.Result.DENY);
                player.sendStatusMessage(Text.translation("chat.region.interact.blocks").darkRed().build(), true);
            }
        }
    }

    private class VisualRegion implements Bounding {
        private final UUID id;
        private final BlockPos center;
        private final GameProfile owner;
        private final Set<GameProfile> members;
        private final int width, depth;
        private boolean pvp, editBlocks, interactBlocks, interactEntities, launchProjectiles;

        public VisualRegion(MessageRegion message, Entity viewer) {
            this.id = message.getId();
            this.center = message.getPos();
            this.owner = message.getOwner();
            this.pvp = message.isPvP();
            this.width = message.getWidth();
            this.depth = message.getDepth();
            this.members = new HashSet<>(message.getMembers());
            if (viewer != null) {
                for (GameProfile member : this.members) {
                    if (Objects.equals(member.getId(), viewer.getUniqueID())) {
                        Set<RegionAbility> abilities = message.getAbilities(member);
                        this.editBlocks = abilities.contains(RegionAbility.EDIT_BLOCKS);
                        this.interactBlocks = abilities.contains(RegionAbility.INTERACT_BLOCKS);
                        this.interactEntities = abilities.contains(RegionAbility.INTERACT_ENTITIES);
                        this.launchProjectiles = abilities.contains(RegionAbility.LAUNCH_PROJECTILES);
                        break;
                    } else if (Objects.equals(this.owner.getId(), viewer.getUniqueID())) {
                        this.editBlocks = true;
                        this.interactBlocks = true;
                        this.interactEntities = true;
                        this.launchProjectiles = true;
                    }
                }
            }
        }

        @Override
        public double getStartX() {
            return this.center.getX() - this.width;
        }

        @Override
        public double getStartY() {
            return 0;
        }

        @Override
        public double getStartZ() {
            return this.center.getZ() - this.depth;
        }

        @Override
        public double getEndX() {
            return this.center.getX() + this.width;
        }

        @Override
        public double getEndY() {
            return 256;
        }

        @Override
        public double getEndZ() {
            return this.center.getZ() + this.depth;
        }

        public void renderTextOverlay(Minecraft client, RenderGameOverlayEvent.Text event) {
            FontRenderer fontRenderer = client.fontRenderer;
            int x = 5;
            int y = 5;
            if (this.owner.getName() != null) {
                boolean own = Objects.equals(this.owner.getId(), client.player.getUniqueID());
                ITextComponent owner = new TextComponentTranslation("tooltip.region.owner", own ? new TextComponentTranslation("tooltip.you") : this.owner.getName());
                fontRenderer.drawString(owner.getFormattedText(), x, y, 0xFFFFFF, true);
            }
        }

        public void renderVolumetric(Minecraft client, float partialTicks) {
            EntityPlayer player = client.player;
            int posY = (int)(player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks);

            float sx = (float) this.getStartX();
            float sy = (float) max(0, posY - 20);
            float sz = (float) this.getStartZ();
            float width = (float) (this.getEndX() - this.getStartX());
            //float height = (float) (this.plot.end.posY - this.plot.start.posY);
            float depth = (float) (this.getEndZ() - this.getStartZ());
            float ex = sx + width + 1;
            float ey = (float) min(256, posY + 20);
            float ez = sz + depth + 1;

            if (ey > sy) {
                GlStateManager.pushMatrix();

                double dx = -player.lastTickPosX - (-player.lastTickPosX + player.posX) * partialTicks;
                double dy = -player.lastTickPosY - (-player.lastTickPosY + player.posY) * partialTicks;
                double dz = -player.lastTickPosZ - (-player.lastTickPosZ + player.posZ) * partialTicks;

                GlStateManager.translate(dx, dy, dz);
                GlStateManager.disableTexture2D();
                GlStateManager.disableCull();
                GlStateManager.disableLighting();

                Tessellator tess = Tessellator.getInstance();
                GlStateManager.glLineWidth(2F);

                float h = (ey - sy);

                BufferBuilder buff = tess.getBuffer();

                int r = this.editBlocks ? 0 : 255;
                int g = this.editBlocks ? 255 : 0;
                int b = 0;
                int a = 255;

                buff.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);

                buff.pos(sx, sy, sz).color(r, g, b, a).endVertex();
                buff.pos(sx, ey, sz).color(r, g, b, a).endVertex();

                buff.pos(ex, sy, sz).color(r, g, b, a).endVertex();
                buff.pos(ex, ey, sz).color(r, g, b, a).endVertex();
                buff.pos(sx, sy, ez).color(r, g, b, a).endVertex();
                buff.pos(sx, ey, ez).color(r, g, b, a).endVertex();
                buff.pos(ex, sy, ez).color(r, g, b, a).endVertex();
                buff.pos(ex, ey, ez).color(r, g, b, a).endVertex();

                buff.pos(sx, sy, sz).color(r, g, b, a).endVertex();
                buff.pos(ex, sy, sz).color(r, g, b, a).endVertex();
                buff.pos(sx, ey, sz).color(r, g, b, a).endVertex();
                buff.pos(ex, ey, sz).color(r, g, b, a).endVertex();

                buff.pos(sx, sy, ez).color(r, g, b, a).endVertex();
                buff.pos(ex, sy, ez).color(r, g, b, a).endVertex();
                buff.pos(sx, ey, ez).color(r, g, b, a).endVertex();
                buff.pos(ex, ey, ez).color(r, g, b, a).endVertex();

                buff.pos(ex, sy, sz).color(r, g, b, a).endVertex();
                buff.pos(ex, sy, ez).color(r, g, b, a).endVertex();
                buff.pos(ex, ey, sz).color(r, g, b, a).endVertex();
                buff.pos(ex, ey, ez).color(r, g, b, a).endVertex();

                buff.pos(sx, sy, sz).color(r, g, b, a).endVertex();
                buff.pos(sx, sy, ez).color(r, g, b, a).endVertex();
                buff.pos(sx, ey, sz).color(r, g, b, a).endVertex();
                buff.pos(sx, ey, ez).color(r, g, b, a).endVertex();

                int dcs = (int) depth;
                int wcs = (int) width;

                tess.draw();

                buff.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

                buff.pos(sx, sy, sz).color(r, g, b, a / 2).endVertex();
                buff.pos(sx, ey, sz).color(r, g, b, a / 2).endVertex();
                buff.pos(ex, ey, sz).color(r, g, b, a / 2).endVertex();
                buff.pos(ex, sy, sz).color(r, g, b, a / 2).endVertex();

                buff.pos(sx, sy, ez).color(r, g, b, a / 2).endVertex();
                buff.pos(sx, ey, ez).color(r, g, b, a / 2).endVertex();
                buff.pos(ex, ey, ez).color(r, g, b, a / 2).endVertex();
                buff.pos(ex, sy, ez).color(r, g, b, a / 2).endVertex();

                buff.pos(sx, sy, sz).color(r, g, b, a / 2).endVertex();
                buff.pos(sx, ey, sz).color(r, g, b, a / 2).endVertex();
                buff.pos(sx, ey, ez).color(r, g, b, a / 2).endVertex();
                buff.pos(sx, sy, ez).color(r, g, b, a / 2).endVertex();

                buff.pos(ex, sy, sz).color(r, g, b, a / 2).endVertex();
                buff.pos(ex, ey, sz).color(r, g, b, a / 2).endVertex();
                buff.pos(ex, ey, ez).color(r, g, b, a / 2).endVertex();
                buff.pos(ex, sy, ez).color(r, g, b, a / 2).endVertex();

                tess.draw();

                GlStateManager.color(1F, 1F, 1F, 1F);
                GlStateManager.enableLighting();
                GlStateManager.enableTexture2D();
                GlStateManager.enableCull();
                GlStateManager.popMatrix();
            }
        }
    }
}
