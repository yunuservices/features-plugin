package io.yunuservices.features;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerServerData;
import net.kyori.adventure.text.Component;

import java.util.logging.Level;

final class PaperPacketEventsMotdBridge extends PacketListenerAbstract {
    private final PaperFeaturesPlugin plugin;
    private final PaperMotdSupport paperMotdSupport;

    private PaperPacketEventsMotdBridge(PaperFeaturesPlugin plugin, PaperMotdSupport paperMotdSupport) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.paperMotdSupport = paperMotdSupport;
    }

    static Runnable register(PaperFeaturesPlugin plugin, PaperMotdSupport paperMotdSupport) {
        PaperPacketEventsMotdBridge bridge = new PaperPacketEventsMotdBridge(plugin, paperMotdSupport);
        PacketListenerCommon registered = PacketEvents.getAPI().getEventManager().registerListener(bridge);
        return () -> PacketEvents.getAPI().getEventManager().unregisterListener(registered);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.SERVER_DATA) {
            return;
        }

        try {
            if (event.getUser() == null || event.getUser().getClientVersion() == null) {
                return;
            }

            Component description = paperMotdSupport.resolveDescriptionForProtocol(
                event.getUser().getClientVersion().getProtocolVersion()
            );
            if (description == null) {
                return;
            }

            WrapperPlayServerServerData packet = new WrapperPlayServerServerData(event);
            packet.setMOTD(description);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to rewrite outgoing SERVER_DATA packet", t);
        }
    }
}
