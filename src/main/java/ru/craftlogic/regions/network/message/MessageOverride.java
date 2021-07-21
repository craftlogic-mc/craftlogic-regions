package ru.craftlogic.regions.network.message;

import ru.craftlogic.api.network.AdvancedBuffer;
import ru.craftlogic.api.network.AdvancedMessage;
import ru.craftlogic.api.network.AdvancedNetwork;
import ru.craftlogic.regions.CraftRegions;

import java.io.IOException;
import java.util.UUID;

public class MessageOverride extends AdvancedMessage {
    private boolean override;

    public MessageOverride() {}

    public MessageOverride(boolean override) {
        this.override = override;
    }

    @Override
    public AdvancedNetwork getNetwork() {
        return CraftRegions.NETWORK;
    }

    @Override
    protected void read(AdvancedBuffer buf) throws IOException {
        this.override = buf.readBoolean();
    }

    @Override
    protected void write(AdvancedBuffer buf) throws IOException {
        buf.writeBoolean(this.override);
    }

    public boolean isOverride() {
        return override;
    }
}
