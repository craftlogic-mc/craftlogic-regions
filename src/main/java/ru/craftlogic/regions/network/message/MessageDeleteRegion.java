package ru.craftlogic.regions.network.message;

import ru.craftlogic.api.network.AdvancedBuffer;
import ru.craftlogic.api.network.AdvancedMessage;
import ru.craftlogic.api.network.AdvancedNetwork;
import ru.craftlogic.regions.CraftRegions;

import java.io.IOException;
import java.util.UUID;

public class MessageDeleteRegion extends AdvancedMessage {
    private UUID id;

    public MessageDeleteRegion() {}

    public MessageDeleteRegion(UUID id) {
        this.id = id;
    }

    @Override
    public AdvancedNetwork getNetwork() {
        return CraftRegions.NETWORK;
    }

    @Override
    protected void read(AdvancedBuffer buf) throws IOException {
        this.id = buf.readUniqueId();
    }

    @Override
    protected void write(AdvancedBuffer buf) throws IOException {
        buf.writeUniqueId(this.id);
    }

    public UUID getId() {
        return id;
    }
}
