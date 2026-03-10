package io.yunuservices.features;

import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;

final class PaperAnnotatedCommands {
    private final TagsCommand tagsCommand;

    PaperAnnotatedCommands(TagsCommand tagsCommand) {
        this.tagsCommand = tagsCommand;
    }

    @Command("tags")
    @CommandDescription("Show features tag command help.")
    public void tagsRoot(CommandSender sender) {
        tagsCommand.showHelpMessage(sender);
    }

    @Command("features")
    @CommandDescription("Show features command help.")
    public void featuresRoot(CommandSender sender) {
        tagsCommand.showFeaturesHelpMessage(sender);
    }

    @Command("tags reload")
    @Command("features reload")
    @CommandDescription("Reload features runtime state.")
    @Permission("features.tags.admin")
    public void reload(CommandSender sender) {
        tagsCommand.executeReloadCommand(sender);
    }

    @Command("tags list")
    @CommandDescription("List stored tags.")
    @Permission("features.tags.admin")
    public void list(CommandSender sender) {
        tagsCommand.executeListCommand(sender);
    }

    @Command("tags view <name>")
    @CommandDescription("Preview a stored tag.")
    @Permission("features.tags.admin")
    public void view(CommandSender sender, @Argument(value = "name", suggestions = "tagNames") String name) {
        tagsCommand.executeViewCommand(sender, name);
    }

    @Command("tags delete <name>")
    @CommandDescription("Delete a stored tag.")
    @Permission("features.tags.admin")
    public void delete(CommandSender sender, @Argument(value = "name", suggestions = "tagNames") String name) {
        tagsCommand.executeDeleteCommand(sender, name);
    }

    @Command("tags rename <old> <new>")
    @CommandDescription("Rename a stored tag.")
    @Permission("features.tags.admin")
    public void rename(
        CommandSender sender,
        @Argument(value = "old", suggestions = "tagNames") String oldName,
        @Argument("new") String newName
    ) {
        tagsCommand.executeRenameCommand(sender, oldName, newName);
    }

    @Command("tags generate <name> <sourceType> <source>")
    @CommandDescription("Generate a tag from a file or URL.")
    @Permission("features.tags.generate")
    public void generate(
        CommandSender sender,
        @Argument("name") String name,
        @Argument(value = "sourceType", suggestions = "sourceTypes") String sourceType,
        @Argument("source") String source
    ) {
        tagsCommand.executeGenerateCommand(sender, name, sourceType, source);
    }

    @Suggestions("tagNames")
    public Iterable<String> suggestTagNames(CommandContext<CommandSender> context, CommandInput input) {
        return tagsCommand.suggestTagNames(input.lastRemainingToken());
    }

    @Suggestions("sourceTypes")
    public Iterable<String> suggestSourceTypes(CommandContext<CommandSender> context, CommandInput input) {
        return tagsCommand.suggestSourceTypes(input.lastRemainingToken());
    }
}
