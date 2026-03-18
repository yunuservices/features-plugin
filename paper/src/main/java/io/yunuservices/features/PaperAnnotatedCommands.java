package io.yunuservices.features;

import io.papermc.paper.command.brigadier.CommandSourceStack;
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
    public void tagsRoot(CommandSourceStack sourceStack) {
        tagsCommand.showHelpMessage(sourceStack.getSender());
    }

    @Command("features")
    @CommandDescription("Show features command help.")
    public void featuresRoot(CommandSourceStack sourceStack) {
        tagsCommand.showFeaturesHelpMessage(sourceStack.getSender());
    }

    @Command("tags reload")
    @Command("features reload")
    @CommandDescription("Reload features runtime state.")
    @Permission("features.tags.admin")
    public void reload(CommandSourceStack sourceStack) {
        tagsCommand.executeReloadCommand(sourceStack.getSender());
    }

    @Command("tags list")
    @CommandDescription("List stored tags.")
    @Permission("features.tags.admin")
    public void list(CommandSourceStack sourceStack) {
        tagsCommand.executeListCommand(sourceStack.getSender());
    }

    @Command("tags view <name>")
    @CommandDescription("Preview a stored tag.")
    @Permission("features.tags.admin")
    public void view(CommandSourceStack sourceStack, @Argument(value = "name", suggestions = "tagNames") String name) {
        tagsCommand.executeViewCommand(sourceStack.getSender(), name);
    }

    @Command("tags delete <name>")
    @CommandDescription("Delete a stored tag.")
    @Permission("features.tags.admin")
    public void delete(CommandSourceStack sourceStack, @Argument(value = "name", suggestions = "tagNames") String name) {
        tagsCommand.executeDeleteCommand(sourceStack.getSender(), name);
    }

    @Command("tags rename <old> <new>")
    @CommandDescription("Rename a stored tag.")
    @Permission("features.tags.admin")
    public void rename(
        CommandSourceStack sourceStack,
        @Argument(value = "old", suggestions = "tagNames") String oldName,
        @Argument("new") String newName
    ) {
        tagsCommand.executeRenameCommand(sourceStack.getSender(), oldName, newName);
    }

    @Command("tags generate <name> <sourceType> <source>")
    @CommandDescription("Generate a tag from a file or URL.")
    @Permission("features.tags.generate")
    public void generate(
        CommandSourceStack sourceStack,
        @Argument("name") String name,
        @Argument(value = "sourceType", suggestions = "sourceTypes") String sourceType,
        @Argument("source") String source
    ) {
        tagsCommand.executeGenerateCommand(sourceStack.getSender(), name, sourceType, source);
    }

    @Suggestions("tagNames")
    public Iterable<String> suggestTagNames(CommandContext<CommandSourceStack> context, CommandInput input) {
        return tagsCommand.suggestTagNames(input.lastRemainingToken());
    }

    @Suggestions("sourceTypes")
    public Iterable<String> suggestSourceTypes(CommandContext<CommandSourceStack> context, CommandInput input) {
        return tagsCommand.suggestSourceTypes(input.lastRemainingToken());
    }
}
