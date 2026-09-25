package org.icarus.angelicae.garden.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.icarus.angelicae.garden.Garden;
import org.icarus.angelicae.garden.MutableGardenRegistry;
import org.icarus.angelicae.garden.plant.GardenPlant;
import org.icarus.angelicae.garden.plant.PlantType;
import org.icarus.angelicae.garden.plant.item.PlantItemContainer;
import org.incendo.cloud.Command;
import org.incendo.cloud.bukkit.parser.PlayerParser;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.paper.util.sender.Source;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * The built-in commands of Garden: {@code give}, {@code plant} and {@code reload}.
 * <p>
 * Every message is sent as an Adventure translatable component rather than as a resolved string, so a client
 * that has a translation for the key reads it in its own language, which is what {@code clientSidedTranslations}
 * in the config turns on.
 * <p>
 * A subcommand is registered as its own command rather than chained onto the parent, because appending a
 * literal continues the path: {@code literal("plant").literal("grow")} would describe {@code grow} as a child
 * of {@code /garden plant info}.
 */
public final class GardenCommands {

    /** How far away the plant a command acts on may be. */
    private static final int MAX_DISTANCE = 32;

    /** Used when no plant type declares a higher growth stage. */
    private static final int DEFAULT_MAX_STAGE = 4;

    private GardenCommands() {
        throw new IllegalStateException("Utility class");
    }

    public static void registerAll(GardenCommandManager commands) {
        registerGive(commands);
        registerPlant(commands);
        registerReload(commands);
    }

    private static void registerGive(GardenCommandManager commands) {
        commands.manager().command(
            commands.command("give", "garden.command.give", "garden.command.description.give")
                .required("item", StringParser.stringParser(), PlantItems.suggestions())
                .optional("amount", IntegerParser.integerParser(1, 64))
                .optional("player", PlayerParser.playerParser())
                .handler(context -> {
                    PlantItemContainer item = PlantItems.get(context.<String>get("item"));
                    if (item == null) {
                        send(context.sender(), Component.translatable("garden.command.invalid-item"));
                        return;
                    }
                    Optional<Player> recipient = context.optional("player");
                    if (recipient.isEmpty()) {
                        if (!(context.sender().source() instanceof Player self)) {
                            send(context.sender(), Component.translatable("garden.command.illegal-sender"));
                            return;
                        }
                        recipient = Optional.of(self);
                    }
                    int amount = context.<Integer>optional("amount").orElse(1);
                    Optional<ItemStack> stack = item.toItem(amount);
                    if (stack.isEmpty()) {
                        send(context.sender(), Component.translatable("garden.command.invalid-item"));
                        return;
                    }
                    give(recipient.get(), stack.get());
                })
        );
    }

    /**
     * Hands the item over, dropping it at the player's feet when their inventory has no room left.
     */
    private static void give(Player recipient, ItemStack stack) {
        if (recipient.getInventory().addItem(stack).isEmpty()) {
            return;
        }
        recipient.getWorld().dropItem(recipient.getLocation(), stack);
    }

    private static void registerReload(GardenCommandManager commands) {
        commands.manager().command(
            commands.command("reload", "garden.command.reload", "garden.command.description.reload")
                .handler(context -> {
                    Garden.getInstance().reload();
                    send(context.sender(), Component.translatable("garden.command.reloaded"));
                })
        );
    }

    private static void registerPlant(GardenCommandManager commands) {
        Command.Builder<Source> plant = commands.command("plant", "garden.command.plant",
            "garden.command.description.plant");

        commands.manager().command(plantSub(commands, plant, "info")
            .handler(context -> withTargetPlant(context.sender(), target ->
                send(context.sender(), Component.translatable("garden.command.found-plant",
                    Argument.tagResolver(Placeholder.unparsed("plant", target.toString())))))));

        commands.manager().command(plantSub(commands, plant, "grow")
            .handler(context -> withTargetPlant(context.sender(), target -> {
                if (target.isFullyGrown()) {
                    send(context.sender(), Component.translatable("garden.command.fully-grown"));
                    return;
                }
                if (!target.incrementGrowthStage(1, Garden.getGardenRegistry(),
                    Garden.getInstance().getGardenPlantDataType())) {
                    // The plant type has no schematic for the stage this would have reached
                    send(context.sender(), Component.translatable("garden.command.fully-grown"));
                }
            })));

        commands.manager().command(plantSub(commands, plant, "growfruits")
            .handler(context -> withTargetPlant(context.sender(), target -> {
                target.bloom();
                target.placeFruits();
            })));

        commands.manager().command(plantSub(commands, plant, "setage")
            .required("stage", IntegerParser.integerParser(1), stageSuggestions())
            .handler(context -> withTargetPlant(context.sender(), target -> {
                int stage = context.<Integer>get("stage") - 1;
                if (!target.setGrowthStage(stage, Garden.getGardenRegistry(),
                    Garden.getInstance().getGardenPlantDataType())) {
                    send(context.sender(), Component.translatable("garden.command.unknown-age",
                        Argument.numeric("age", stage + 1),
                        Argument.tagResolver(Placeholder.unparsed("plant",
                            Garden.minimized(target.getType().key())))));
                }
            })));
    }

    /**
     * Appends a subcommand of {@code /garden plant} and gives it its own description for the help screen.
     */
    private static Command.Builder<Source> plantSub(GardenCommandManager commands, Command.Builder<Source> plant,
                                                    String name) {
        return plant.literal(name)
            .commandDescription(Description.of(commands.description("garden.command.description.plant." + name)));
    }

    /**
     * Runs the action against the plant the sender is looking at, or explains why there is none.
     */
    private static void withTargetPlant(Source source, Consumer<GardenPlant> action) {
        if (!(source.source() instanceof Player player)) {
            send(source, Component.translatable("garden.command.illegal-sender"));
            return;
        }
        GardenPlant plant = PlantItems.targetPlant(player, MAX_DISTANCE);
        if (plant == null) {
            send(source, Component.translatable("garden.command.could-not-find-plant"));
            return;
        }
        action.accept(plant);
    }

    /**
     * Offers the growth stages that are worth typing.
     * <p>
     * The upper bound is read from the registered plant types rather than from the block the sender is looking
     * at: completion may run off the main thread, and the world must not be touched there.
     */
    private static SuggestionProvider<Source> stageSuggestions() {
        return SuggestionProvider.blockingStrings((context, input) -> {
            int maxStages = MutableGardenRegistry.PLANT_TYPE.values().stream()
                .mapToInt(PlantType::maxStages)
                .max()
                .orElse(DEFAULT_MAX_STAGE);
            return IntStream.rangeClosed(1, maxStages)
                .mapToObj(Integer::toString)
                .toList();
        });
    }

    private static void send(Source source, Component message) {
        source.source().sendMessage(message);
    }
}
