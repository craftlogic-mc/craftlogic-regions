package ru.craftlogic.regions.network.message;

import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import ru.craftlogic.api.network.AdvancedBuffer;
import ru.craftlogic.api.network.AdvancedMessage;
import ru.craftlogic.api.network.AdvancedNetwork;
import ru.craftlogic.regions.CraftRegions;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class MessageConfiguration extends AdvancedMessage {
    private Set<ResourceLocation> whitelistBlockUsage;
    private Set<ResourceLocation> blacklistItemUsage;
    private Set<ResourceLocation> whitelistBlockBreakage;
    private Set<ResourceLocation> chests;
    private Set<ResourceLocation> doors;

    public MessageConfiguration() {}

    public MessageConfiguration(Set<ResourceLocation> whitelistBlockUsage,
                                Set<ResourceLocation> blacklistItemUsage,
                                Set<ResourceLocation> whitelistBlockBreakage,
                                Set<ResourceLocation> chests,
                                Set<ResourceLocation> doors) {


        this.whitelistBlockUsage = whitelistBlockUsage;
        this.blacklistItemUsage = blacklistItemUsage;
        this.whitelistBlockBreakage = whitelistBlockBreakage;
        this.chests = chests;
        this.doors = doors;
    }

    @Override
    public AdvancedNetwork getNetwork() {
        return CraftRegions.NETWORK;
    }

    @Override
    protected void read(AdvancedBuffer buf) throws IOException {
        whitelistBlockUsage = readSet(buf, PacketBuffer::readResourceLocation);
        blacklistItemUsage = readSet(buf, PacketBuffer::readResourceLocation);
        whitelistBlockBreakage = readSet(buf, PacketBuffer::readResourceLocation);
        chests = readSet(buf, PacketBuffer::readResourceLocation);
        doors = readSet(buf, PacketBuffer::readResourceLocation);
    }

    private <T> Set<T> readSet(AdvancedBuffer buf, Function<AdvancedBuffer, T> reader) {
        int size = buf.readVarInt();
        Set<T> list = new HashSet<>(size);
        for (int i = 0; i < size; i++) {
            list.add(reader.apply(buf));
        }
        return list;
    }

    @Override
    protected void write(AdvancedBuffer buf) throws IOException {
        writeSet(buf, whitelistBlockUsage, PacketBuffer::writeResourceLocation);
        writeSet(buf, blacklistItemUsage, PacketBuffer::writeResourceLocation);
        writeSet(buf, whitelistBlockBreakage, PacketBuffer::writeResourceLocation);
        writeSet(buf, chests, PacketBuffer::writeResourceLocation);
        writeSet(buf, doors, PacketBuffer::writeResourceLocation);
    }

    private <T> void writeSet(AdvancedBuffer buf, Set<T> list, BiConsumer<AdvancedBuffer, T> writer) {
        buf.writeVarInt(list.size());
        for (T t : list) {
            writer.accept(buf, t);
        }
    }

    public Set<ResourceLocation> getWhitelistBlockUsage() {
        return whitelistBlockUsage;
    }

    public Set<ResourceLocation> getBlacklistItemUsage() {
        return blacklistItemUsage;
    }

    public Set<ResourceLocation> getWhitelistBlockBreakage() {
        return whitelistBlockBreakage;
    }

    public Set<ResourceLocation> getChests() {
        return chests;
    }

    public Set<ResourceLocation> getDoors() {
        return doors;
    }
}
