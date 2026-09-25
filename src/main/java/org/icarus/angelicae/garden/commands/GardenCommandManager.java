package org.icarus.angelicae.garden.commands;

import lombok.Getter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.icarus.angelicae.garden.Garden;
import org.icarus.angelicae.garden.configuration.GardenConfig;
import org.icarus.angelicae.garden.configuration.GardenTranslator;
import org.incendo.cloud.Command;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.minecraft.extras.MinecraftExceptionHandler;
import org.incendo.cloud.minecraft.extras.MinecraftHelp;
import org.incendo.cloud.minecraft.extras.caption.ComponentCaptionFormatter;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.paper.util.sender.PaperSimpleSenderMapper;
import org.incendo.cloud.paper.util.sender.Source;
import org.incendo.cloud.permission.Permission;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Owns the {@link PaperCommandManager} of Garden and registers every command on it.
 * <p>
 * The plugin main class only calls {@link #register()}, every command lives in this package, see
 * {@link GardenCommands} for the built-in ones.
 * <p>
 * Both the command's own messages and the framework's errors go out as Adventure translatable components, so
 * a client that has a translation for the key sees its own language instead of the server's, which is what
 * {@code clientSidedTranslations} in the config is about.
 */
public class GardenCommandManager {

    private final Garden plugin;
    private final PaperCommandManager<Source> manager;

    @Getter
    private final MinecraftHelp<Source> help;

    public GardenCommandManager(Garden plugin) {
        this.plugin = plugin;
        this.manager = PaperCommandManager.builder(PaperSimpleSenderMapper.simpleSenderMapper())
            .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
            .buildOnEnable(plugin);

        // Cloud ships English defaults for its own captions and reports them as plain text. Answering them
        // from the language file instead puts the messages in the hands of whoever translates Garden, and
        // leaves anything not translated to the English default.
        //
        // The provider of `TranslatableCaption` looks like the tool for this, but it only recognizes captions
        // that were created as TranslatableCaption themselves; Cloud's own captions are not, so it steps
        // aside for every message that matters here.
        this.manager.captionRegistry().registerProvider((caption, sender) ->
            message(caption.key(), localeOf(sender)));
        MinecraftExceptionHandler.create(Source::source)
            .captionFormatter(ComponentCaptionFormatter.miniMessage())
            .defaultHandlers()
            .registerTo(this.manager);

        this.help = MinecraftHelp.create("/" + rootName() + " help", this.manager, Source::source);
    }

    public PaperCommandManager<Source> manager() {
        return this.manager;
    }

    public Garden plugin() {
        return this.plugin;
    }

    /**
     * Registers every command of Garden, this is the only method the plugin main class has to call.
     */
    public void register() {
        // The bare command shows the help, so it does not silently do nothing
        this.manager.command(root().handler(context -> showHelp(context.sender())));
        this.manager.command(root().literal("help").handler(context -> showHelp(context.sender())));

        GardenCommands.registerAll(this);
    }

    /**
     * Shows the help screen of the root command.
     */
    public void showHelp(Source source) {
        this.help.queryCommands("", source);
    }

    /**
     * Creates a builder for a subcommand of {@code /garden}.
     *
     * @param name           The name of the subcommand
     * @param permission     The permission node required to use it, may be null
     * @param descriptionKey The translation key describing the subcommand
     * @return The command builder with name, permission and description already applied
     */
    public Command.Builder<Source> command(String name, @Nullable String permission, String descriptionKey) {
        Command.Builder<Source> builder = root().literal(name)
            .commandDescription(Description.of(description(descriptionKey)));
        if (permission != null) {
            builder.permission(Permission.of(permission));
        }
        return builder;
    }

    /**
     * Creates a builder for a subcommand of {@code /garden} that needs no permission.
     */
    public Command.Builder<Source> command(String name, String descriptionKey) {
        return command(name, null, descriptionKey);
    }

    /**
     * @return A builder for the root command
     */
    public Command.Builder<Source> root() {
        return this.manager.commandBuilder(rootName());
    }

    /**
     * @return The name of the root command, {@code garden}, which is also the namespace other plugins use
     */
    public static String rootName() {
        return Garden.NAMESPACE;
    }

    /**
     * Resolves a translation and reduces it to plain text, which is what the command descriptions of the help
     * screen are compared against.
     */
    public String description(String key) {
        return MiniMessage.miniMessage().stripTags(message(key, GardenConfig.instance().language()));
    }

    /**
     * @param key    The translation key
     * @param locale The locale to read it in
     * @return The MiniMessage string the key is written in, or null if the language file has no entry for it
     */
    public @Nullable String message(String key, Locale locale) {
        return translator().getMiniMessageString(key, locale);
    }

    /**
     * @param key The translation key
     * @return The MiniMessage string the key is written in, or the key itself if there is no entry for it
     */
    public String message(String key) {
        String message = message(key, GardenConfig.instance().language());
        return message == null ? key : message;
    }

    /**
     * The locale a sender reads messages in. A player gets their own language when client side translations
     * are on, everyone else the one the server is configured for.
     */
    private static Locale localeOf(@Nullable Source sender) {
        if (sender != null && sender.source() instanceof Player player) {
            return player.locale();
        }
        return GardenConfig.instance().language();
    }

    private GardenTranslator translator() {
        return Garden.getInstance().getTranslator();
    }
}
