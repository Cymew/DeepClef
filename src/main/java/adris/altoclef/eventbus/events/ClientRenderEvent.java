package adris.altoclef.eventbus.events;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class ClientRenderEvent {
    public GuiGraphicsExtractor extractor;
    public DeltaTracker tickDelta;

    public ClientRenderEvent(GuiGraphicsExtractor extractor, DeltaTracker tickDelta) {
        this.extractor = extractor;
        this.tickDelta = tickDelta;
    }
}
