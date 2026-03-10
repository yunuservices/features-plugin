package io.yunuservices.features;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class TagsAsyncTabListener implements Listener {
    private final TagsCommand tagsCommand;

    public TagsAsyncTabListener(TagsCommand tagsCommand) {
        this.tagsCommand = tagsCommand;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        if (!event.isCommand()) {
            return;
        }

        String buffer = event.getBuffer();
        if (buffer == null || buffer.isBlank() || !buffer.startsWith("/")) {
            return;
        }

        String[] raw = buffer.substring(1).split("\\s+", -1);
        if (raw.length == 0) {
            return;
        }
        String baseCommand = raw[0].toLowerCase(Locale.ROOT);
        if (!baseCommand.equals("tags") && !baseCommand.equals("features")) {
            return;
        }

        String[] args = Arrays.copyOfRange(raw, 1, raw.length);
        if (args.length == 0) {
            args = new String[]{""};
        }
        CommandSender sender = event.getSender();

        List<AsyncTabCompleteEvent.Completion> completions = tagsCommand.suggest(sender, args).stream()
            .map(AsyncTabCompleteEvent.Completion::completion)
            .collect(Collectors.toList());

        event.completions(completions);
        event.setHandled(true);
    }
}
