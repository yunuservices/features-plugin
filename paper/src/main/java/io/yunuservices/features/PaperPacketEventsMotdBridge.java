package io.yunuservices.features;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerServerData;
import com.github.retrooper.packetevents.wrapper.status.server.WrapperStatusServerResponse;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

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
        if (event.getUser() == null || event.getUser().getClientVersion() == null) {
            return;
        }

        try {
            Component description = paperMotdSupport.resolveDescriptionForProtocol(
                event.getUser().getClientVersion().getProtocolVersion()
            );
            if (description == null) {
                return;
            }

            if (event.getPacketType() == PacketType.Status.Server.RESPONSE) {
                rewriteStatusResponse(event, description);
                return;
            }

            if (event.getPacketType() == PacketType.Play.Server.SERVER_DATA) {
                WrapperPlayServerServerData packet = new WrapperPlayServerServerData(event);
                packet.setMOTD(description);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to rewrite outgoing MOTD packet", t);
        }
    }

    private void rewriteStatusResponse(PacketSendEvent event, Component description) {
        WrapperStatusServerResponse packet = new WrapperStatusServerResponse(event);
        JsonObject response = packet.getComponent();
        if (response == null) {
            return;
        }
        response.add("description", GsonComponentSerializer.gson().serializeToTree(description));
        packet.setComponent(response);
    }
}
