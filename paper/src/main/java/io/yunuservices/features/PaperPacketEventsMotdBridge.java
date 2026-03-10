package io.yunuservices.features;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerServerData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.util.CachedServerIcon;

import java.util.logging.Level;

final class PaperPacketEventsMotdBridge implements Listener {
    private final PaperFeaturesPlugin plugin;
    private final PaperMotdSupport paperMotdSupport;

    private PaperPacketEventsMotdBridge(PaperFeaturesPlugin plugin, PaperMotdSupport paperMotdSupport) {
        this.plugin = plugin;
        this.paperMotdSupport = paperMotdSupport;
    }

    static Runnable register(PaperFeaturesPlugin plugin, PaperMotdSupport paperMotdSupport) {
        PaperPacketEventsMotdBridge bridge = new PaperPacketEventsMotdBridge(plugin, paperMotdSupport);
        Bukkit.getPluginManager().registerEvents(bridge, plugin);
        return () -> HandlerList.unregisterAll(bridge);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> sendServerData(player), () -> {}, 1L);
    }

    private void sendServerData(Player player) {
        if (!player.isOnline()) {
            return;
        }

        try {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api == null) {
                return;
            }

            ClientVersion clientVersion = api.getPlayerManager().getClientVersion(player);
            if (clientVersion == null) {
                return;
            }

            Component description = paperMotdSupport.resolveDescriptionForProtocol(clientVersion.getProtocolVersion());
            if (description == null) {
                return;
            }

            CachedServerIcon serverIcon = Bukkit.getServerIcon();
            String iconData = serverIcon == null || serverIcon.isEmpty() ? null : serverIcon.getData();

            WrapperPlayServerServerData packet = new WrapperPlayServerServerData(
                description,
                iconData,
                false,
                Bukkit.isEnforcingSecureProfiles()
            );
            api.getPlayerManager().sendPacket(player, packet);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to send join-time MOTD refresh to " + player.getName(), t);
        }
    }
}
