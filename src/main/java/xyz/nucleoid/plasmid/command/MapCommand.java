package xyz.nucleoid.plasmid.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.*;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.Plasmid;
import xyz.nucleoid.plasmid.game.map.template.*;
import xyz.nucleoid.plasmid.game.map.template.trace.PartialRegion;
import xyz.nucleoid.plasmid.game.map.template.trace.RegionTracer;
import xyz.nucleoid.plasmid.util.BlockBounds;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class MapCommand {
    public static final DynamicCommandExceptionType ENTITY_TYPE_NOT_FOUND = new DynamicCommandExceptionType(arg ->
            new TranslatableText("Entity type with id '%s' was not found!", arg)
    );

    public static final DynamicCommandExceptionType MAP_NOT_FOUND = new DynamicCommandExceptionType(arg ->
            new TranslatableText("Map with id '%s' was not found!", arg)
    );

    public static final SimpleCommandExceptionType MAP_NOT_FOUND_AT = new SimpleCommandExceptionType(
            new LiteralText("No map found here")
    );

    public static final SimpleCommandExceptionType NO_REGION_READY = new SimpleCommandExceptionType(
            new LiteralText("No region ready")
    );

    private static final SimpleCommandExceptionType MERGE_FAILED_EXCEPTION = new SimpleCommandExceptionType(
            new TranslatableText("commands.data.merge.failed")
    );

    private static final SimpleCommandExceptionType GET_MULTIPLE_EXCEPTION = new SimpleCommandExceptionType(
            new TranslatableText("commands.data.get.multiple")
    );

    private static final DynamicCommandExceptionType MODIFY_EXPECTED_LIST_EXCEPTION = new DynamicCommandExceptionType(
            arg -> new TranslatableText("commands.data.modify.expected_list", arg)
    );

    private static final DynamicCommandExceptionType MODIFY_EXPECTED_OBJECT_EXCEPTION = new DynamicCommandExceptionType(
            arg -> new TranslatableText("commands.data.modify.expected_object", arg)
    );

    // @formatter:off
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            literal("map").requires(source -> source.hasPermissionLevel(4))
                .then(literal("stage")
                    .then(argument("identifier", IdentifierArgumentType.identifier()).suggests(stagingSuggestions())
                    .then(argument("min", BlockPosArgumentType.blockPos())
                    .then(argument("max", BlockPosArgumentType.blockPos())
                    .executes(MapCommand::stageMap)
                ))))
                .then(literal("enter")
                    .then(argument("identifier", IdentifierArgumentType.identifier()).suggests(stagingSuggestions())
                    .executes(MapCommand::enterMap)
                ))
                .then(literal("exit").executes(MapCommand::exitMap))
                .then(literal("compile")
                    .then(argument("identifier", IdentifierArgumentType.identifier()).suggests(stagingSuggestions())
                    .executes(context -> MapCommand.compileMap(context, false))
                    .then(literal("withEntities")
                        .executes(context -> MapCommand.compileMap(context, true))
                    )
                ))
                .then(literal("region")
                    .then(literal("add")
                        .then(argument("marker", StringArgumentType.word())
                        .then(argument("min", BlockPosArgumentType.blockPos())
                        .then(argument("max", BlockPosArgumentType.blockPos())
                        .executes(MapCommand::addRegion)
                        .then(argument("data", NbtCompoundTagArgumentType.nbtCompound())
                        .executes(context -> addRegion(context, NbtCompoundTagArgumentType.getCompoundTag(context, "data")))
                    )))))
                    .then(literal("rename")
                        .then(literal("all")
                            .then(argument("old", StringArgumentType.word()).suggests(regionSuggestions())
                            .then(argument("new", StringArgumentType.word())
                            .executes(context -> renameRegions(context, (region, oldMarker, pos) -> region.getMarker().equals(oldMarker)))
                        )))
                        .then(literal("here")
                            .then(argument("old", StringArgumentType.word()).suggests(localRegionSuggestions())
                            .then(argument("new", StringArgumentType.word())
                            .executes(
                                context -> renameRegions(context, (region, oldMarker, pos) -> region.getMarker().equals(oldMarker)
                                        && region.getBounds().contains(pos))
                            )
                        )))
                    )
                    .then(literal("data")
                        .then(argument("marker", StringArgumentType.word()).suggests(localRegionSuggestions())
                            .then(literal("get").executes(executeInRegions("", MapCommand::executeRegionDataGet)))
                            .then(literal("merge")
                                .then(argument("nbt", NbtCompoundTagArgumentType.nbtCompound())
                                    .executes(executeInRegions("Merged data in %d regions.", MapCommand::executeRegionDataMerge))
                            ))
                            .then(literal("set")
                                .then(argument("nbt", NbtCompoundTagArgumentType.nbtCompound())
                                    .executes(executeInRegions("Set data in %d regions.", MapCommand::executeRegionDataSet))
                            ))
                            .then(literal("remove")
                                .then(argument("path", NbtPathArgumentType.nbtPath())
                                    .executes(executeInRegions("Removed data in %d regions.", MapCommand::executeRegionDataRemove))
                            ))
                    ))
                    .then(literal("remove")
                        .then(literal("here")
                            .executes(MapCommand::removeRegionHere)
                        )
                        .then(literal("at")
                            .then(argument("pos", BlockPosArgumentType.blockPos())
                            .executes(MapCommand::removeRegionAt)
                        ))
                    )
                    .then(literal("commit")
                        .then(argument("marker", StringArgumentType.word())
                        .executes(MapCommand::commitRegion)
                        .then(argument("data", NbtCompoundTagArgumentType.nbtCompound())
                        .executes(context -> commitRegion(context, NbtCompoundTagArgumentType.getCompoundTag(context, "data")))
                    )))
                )
                .then(literal("entity")
                    .then(literal("add")
                        .then(argument("entities", EntityArgumentType.entities())
                        .executes(MapCommand::addEntities)
                    ))
                    .then(literal("remove")
                        .then(argument("entities", EntityArgumentType.entities())
                        .executes(MapCommand::removeEntities)
                    ))
                    .then(literal("filter")
                        .then(literal("type")
                            .then(literal("add")
                                .then(argument("entity_type", IdentifierArgumentType.identifier()).suggests(entityTypeSuggestions())
                                .executes(MapCommand::addEntityType)
                            ))
                            .then(literal("remove")
                                .then(argument("entity_type", IdentifierArgumentType.identifier()).suggests(entityTypeSuggestions())
                                .executes(MapCommand::removeEntityType)
                            ))
                        )
                    )
                )
                .then(literal("data")
                        .then(literal("get")
                            .executes(MapCommand::executeDataGet)
                            .then(literal("at")
                                .then(argument("path", NbtPathArgumentType.nbtPath())
                                .executes(MapCommand::executeDataGetAt)
                        )))
                        .then(literal("merge")
                            .then(argument("nbt", NbtCompoundTagArgumentType.nbtCompound())
                                .executes(MapCommand::executeDataMerge)
                            )
                            .then(argument("nbt", NbtTagArgumentType.nbtTag())
                                .then(literal("at")
                                .then(argument("path", NbtPathArgumentType.nbtPath())
                                .executes(MapCommand::executeDataMergeAt)
                            )))
                        )
                        .then(literal("remove")
                            .executes(context -> executeDataRemove(context, null))
                            .then(literal("at")
                                .then(argument("path", NbtPathArgumentType.nbtPath())
                                .executes(context -> executeDataRemove(context, NbtPathArgumentType.getNbtPath(context, "path")))
                        )))
                        .then(literal("set")
                            .then(argument("nbt", NbtCompoundTagArgumentType.nbtCompound())
                                .executes(MapCommand::executeDataSet)
                            )
                            .then(literal("at")
                                .then(argument("path", NbtPathArgumentType.nbtPath())
                                    .then(argument("nbt", NbtTagArgumentType.nbtTag())
                                    .executes(MapCommand::executeDataSetAt)
                            )))
                        )
                )
        );
    }
    // @formatter:on

    private static int stageMap(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();

        Identifier identifier = IdentifierArgumentType.getIdentifier(context, "identifier");
        BlockPos min = BlockPosArgumentType.getBlockPos(context, "min");
        BlockPos max = BlockPosArgumentType.getBlockPos(context, "max");

        StagingMapManager stagingMapManager = StagingMapManager.get(world);
        stagingMapManager.add(identifier, new BlockBounds(min, max));

        source.sendFeedback(new LiteralText("Staged map '" + identifier + "'"), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int enterMap(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        StagingMapTemplate map = getMapFromArg(context);

        if (player instanceof MapTemplateViewer) {
            MapTemplateViewer viewer = (MapTemplateViewer) player;
            viewer.setViewing(map);
            source.sendFeedback(new LiteralText("Viewing: '" + map.getIdentifier() + "'"), false);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int exitMap(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player instanceof MapTemplateViewer) {
            MapTemplateViewer viewer = (MapTemplateViewer) player;
            StagingMapTemplate viewing = viewer.getViewing();

            if (viewing != null) {
                viewer.setViewing(null);

                Identifier identifier = viewing.getIdentifier();
                source.sendFeedback(new LiteralText("Stopped viewing: '" + identifier + "'"), false);
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int compileMap(CommandContext<ServerCommandSource> context, boolean includeEntities) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();

        StagingMapTemplate stagingMap = getMapFromArg(context);

        MapTemplate template = stagingMap.compile(includeEntities);
        CompletableFuture<Void> future = MapTemplateSerializer.INSTANCE.save(template, stagingMap.getIdentifier());

        future.handle((v, throwable) -> {
            if (throwable == null) {
                source.sendFeedback(new LiteralText("Compiled and saved map '" + stagingMap.getIdentifier() + "'"), false);
            } else {
                Plasmid.LOGGER.error("Failed to compile map to '{}'", stagingMap.getIdentifier(), throwable);
                source.sendError(new LiteralText("Failed to compile map! An unexpected exception was thrown"));
            }
            return null;
        });

        return Command.SINGLE_SUCCESS;
    }

    private static int addRegion(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return addRegion(context, new CompoundTag());
    }

    private static int addRegion(CommandContext<ServerCommandSource> context, CompoundTag data) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();

        String marker = StringArgumentType.getString(context, "marker");
        BlockPos min = BlockPosArgumentType.getBlockPos(context, "min");
        BlockPos max = BlockPosArgumentType.getBlockPos(context, "max");

        StagingMapTemplate map = getMap(context);
        map.addRegion(marker, new BlockBounds(min, max), data);
        source.sendFeedback(withMapPrefix(map, new LiteralText("Added region '" + marker + "'.")), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int renameRegions(CommandContext<ServerCommandSource> context, RegionPredicate predicate) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        BlockPos pos = source.getPlayer().getBlockPos();

        String oldMarker = StringArgumentType.getString(context, "old");
        String newMarker = StringArgumentType.getString(context, "new");

        StagingMapTemplate map = getMap(context);

        List<TemplateRegion> regions = map.getRegions().stream()
                .filter(region -> predicate.test(region, oldMarker, pos))
                .collect(Collectors.toList());

        for (TemplateRegion region : regions) {
            map.removeRegion(region);
            map.addRegion(newMarker, region.getBounds(), region.getData());
        }

        source.sendFeedback(withMapPrefix(map, new LiteralText("Renamed " + regions.size() + " regions.")), false);

        return Command.SINGLE_SUCCESS;
    }

    private static boolean executeRegionDataGet(CommandContext<ServerCommandSource> context, StagingMapTemplate map, TemplateRegion region) {
        context.getSource().sendFeedback(withMapPrefix(map, new TranslatableText("Data of region \"%s\":\n%s",
                        region.getMarker(), region.getData().toText("  ", 0))),
                false);
        return false;
    }

    private static boolean executeRegionDataMerge(CommandContext<ServerCommandSource> context, StagingMapTemplate map, TemplateRegion region) {
        CompoundTag data = NbtCompoundTagArgumentType.getCompoundTag(context, "nbt");
        region.setData(region.getData().copy().copyFrom(data));
        return true;
    }

    private static boolean executeRegionDataSet(CommandContext<ServerCommandSource> context, StagingMapTemplate map, TemplateRegion region) {
        CompoundTag data = NbtCompoundTagArgumentType.getCompoundTag(context, "nbt");
        region.setData(data);
        return true;
    }

    private static boolean executeRegionDataRemove(CommandContext<ServerCommandSource> context, StagingMapTemplate map, TemplateRegion region) {
        NbtPathArgumentType.NbtPath path = NbtPathArgumentType.getNbtPath(context, "path");
        return path.remove(region.getData()) > 0;
    }

    private static int removeRegionHere(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return removeRegion(context, context.getSource().getPlayer().getBlockPos());
    }

    private static int removeRegionAt(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return removeRegion(context, BlockPosArgumentType.getBlockPos(context, "pos"));
    }

    private static int removeRegion(CommandContext<ServerCommandSource> context, BlockPos pos) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        StagingMapTemplate map = getMap(context);

        List<TemplateRegion> regions = map.getRegions().stream()
                .filter(region -> region.getBounds().contains(pos))
                .collect(Collectors.toList());

        for (TemplateRegion region : regions) {
            map.removeRegion(region);
        }

        source.sendFeedback(withMapPrefix(map, new LiteralText("Removed " + regions.size() + " regions.")), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int commitRegion(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return commitRegion(context, new CompoundTag());
    }

    private static int commitRegion(CommandContext<ServerCommandSource> context, CompoundTag data) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        ServerWorld world = source.getWorld();

        StagingMapManager stagingMapManager = StagingMapManager.get(world);

        String marker = StringArgumentType.getString(context, "marker");

        if (player instanceof RegionTracer) {
            RegionTracer regionTracer = (RegionTracer) player;

            PartialRegion region = regionTracer.takeReady();
            if (region == null) {
                throw NO_REGION_READY.create();
            }

            BlockPos min = region.getMin();
            BlockPos max = region.getMax();

            Optional<StagingMapTemplate> mapOpt = stagingMapManager.getStagingMaps().stream()
                    .filter(stagingMap -> stagingMap.getBounds().contains(min) && stagingMap.getBounds().contains(max))
                    .findFirst();

            if (mapOpt.isPresent()) {
                StagingMapTemplate map = mapOpt.get();
                map.addRegion(marker, new BlockBounds(min, max), data);
                source.sendFeedback(new LiteralText("Added region '" + marker + "' to '" + map.getIdentifier() + "'"), false);
            } else {
                throw MAP_NOT_FOUND_AT.create();
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int addEntities(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();

        StagingMapTemplate map = getMap(context);

        long result = EntityArgumentType.getEntities(context, "entities").stream()
                .filter(entity -> entity.getEntityWorld().equals(world) && !(entity instanceof PlayerEntity)
                        && map.getBounds().contains(entity.getBlockPos()))
                .filter(entity -> map.addEntity(entity.getUuid()))
                .count();

        if (result == 0) {
            source.sendError(new LiteralText("Could not add entities in map \"" + map.getIdentifier() + "\"."));
        } else {
            source.sendFeedback(new LiteralText("Added " + result + " entities in map \"" + map.getIdentifier() + "\"."),
                    false);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int removeEntities(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();

        StagingMapTemplate map = getMap(context);

        long result = EntityArgumentType.getEntities(context, "entities").stream()
                .filter(entity -> entity.getEntityWorld().equals(world) && !(entity instanceof PlayerEntity))
                .filter(entity -> map.removeEntity(entity.getUuid()))
                .count();

        if (result == 0) {
            source.sendError(new LiteralText("Could not remove entities in map \"" + map.getIdentifier() + "\"."));
        } else {
            source.sendFeedback(new LiteralText("Removed " + result + " entities in map \"" + map.getIdentifier() + "\"."),
                    false);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int addEntityType(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();

        StagingMapTemplate map = getMap(context);
        Pair<Identifier, EntityType<?>> type = getEntityType(context);

        if (!map.addEntityType(type.getRight())) {
            source.sendError(new LiteralText("Entity type \"" + type.getLeft() + "\" is already present in map \"" + map.getIdentifier() + "\"."));
        } else {
            source.sendFeedback(new LiteralText("Added entity type \"" + type.getLeft() + "\" in map \"" + map.getIdentifier() + "\"."), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int removeEntityType(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();

        StagingMapTemplate map = getMap(context);
        Pair<Identifier, EntityType<?>> type = getEntityType(context);

        if (!map.removeEntityType(type.getRight())) {
            source.sendError(new LiteralText("Entity type \"" + type.getLeft() + "\" is not present in map \"" + map.getIdentifier() + "\"."));
        } else {
            source.sendFeedback(new LiteralText("Removed entity type \"" + type.getLeft() + "\" in map \"" + map.getIdentifier() + "\"."), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeDataMerge(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        StagingMapTemplate map = getMap(context);
        CompoundTag data = NbtCompoundTagArgumentType.getCompoundTag(context, "nbt");
        CompoundTag originalData = map.getData();
        map.setData(originalData.copy().copyFrom(data));
        source.sendFeedback(withMapPrefix(map, new LiteralText("Merged map data.")), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeDataMergeAt(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        StagingMapTemplate map = getMap(context);

        CompoundTag sourceData = NbtCompoundTagArgumentType.getCompoundTag(context, "nbt");
        NbtPathArgumentType.NbtPath path = NbtPathArgumentType.getNbtPath(context, "path");

        List<Tag> sourceTags = path.getOrInit(sourceData, CompoundTag::new);
        List<Tag> mergeIntoTags = path.get(map.getData());

        int mergeCount = 0;

        for (Tag mergeIntoTag : mergeIntoTags) {
            if (!(mergeIntoTag instanceof CompoundTag)) {
                throw MODIFY_EXPECTED_OBJECT_EXCEPTION.create(mergeIntoTag);
            }

            CompoundTag mergedCompound = (CompoundTag) mergeIntoTag;
            CompoundTag previousCompound = mergedCompound.copy();

            for (Tag sourceTag : sourceTags) {
                if (!(sourceTag instanceof CompoundTag)) {
                    throw MODIFY_EXPECTED_OBJECT_EXCEPTION.create(sourceTag);
                }

                mergedCompound.copyFrom((CompoundTag) sourceTag);
            }

            if (!previousCompound.equals(mergedCompound)) {
                mergeCount++;
            }
        }

        if (mergeCount == 0) {
            throw MERGE_FAILED_EXCEPTION.create();
        }

        map.setData(map.getData());
        source.sendFeedback(withMapPrefix(map, new LiteralText("Merged map data.")), false);

        return mergeCount;
    }

    private static int executeDataGet(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        StagingMapTemplate map = getMap(context);
        source.sendFeedback(new TranslatableText("Map data of %s:\n%s",
                        getMapPrefix(map), map.getData().toText("  ", 0)),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeDataGetAt(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        StagingMapTemplate map = getMap(context);
        NbtPathArgumentType.NbtPath path = NbtPathArgumentType.getNbtPath(context, "path");
        source.sendFeedback(new TranslatableText("Map data of \"%s\" at \"%s\":\n%s",
                        map.getIdentifier().toString(), path.toString(),
                        getTagAt(map.getData(), path).toText("  ", 0)),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeDataRemove(CommandContext<ServerCommandSource> context, @Nullable NbtPathArgumentType.NbtPath path) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        StagingMapTemplate map = getMap(context);
        if (path == null) {
            map.setData(new CompoundTag());
            source.sendFeedback(withMapPrefix(map, new LiteralText("The map data root tag has been reset.")),
                    false);
        } else {
            int count = path.remove(map.getData());
            if (count == 0) {
                throw MERGE_FAILED_EXCEPTION.create();
            } else {
                source.sendFeedback(new TranslatableText("%s Removed tag from map data at \"%s\".",
                                getMapPrefix(map), path.toString()),
                        false);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeDataSet(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        StagingMapTemplate map = getMap(context);
        CompoundTag data = NbtCompoundTagArgumentType.getCompoundTag(context, "nbt");
        map.setData(data);
        source.sendFeedback(withMapPrefix(map, new LiteralText("Set map data.")), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeDataSetAt(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        StagingMapTemplate map = getMap(context);
        NbtPathArgumentType.NbtPath path = NbtPathArgumentType.getNbtPath(context, "path");
        Tag tag = NbtTagArgumentType.getTag(context, "nbt");
        CompoundTag data = map.getData().copy();
        if (path.put(data, tag::copy) == 0) {
            throw MERGE_FAILED_EXCEPTION.create();
        } else {
            map.setData(data);
            source.sendFeedback(withMapPrefix(map, new TranslatableText("Set map data at \"%s\".",
                            path.toString())),
                    false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static Tag getTagAt(CompoundTag data, NbtPathArgumentType.NbtPath path) throws CommandSyntaxException {
        Collection<Tag> collection = path.get(data);
        Iterator<Tag> iterator = collection.iterator();
        Tag tag = iterator.next();
        if (iterator.hasNext()) {
            throw GET_MULTIPLE_EXCEPTION.create();
        } else {
            return tag;
        }
    }

    private static Pair<Identifier, EntityType<?>> getEntityType(CommandContext<ServerCommandSource> context) throws
            CommandSyntaxException {
        Identifier id = IdentifierArgumentType.getIdentifier(context, "entity_type");
        return new Pair<>(id, Registry.ENTITY_TYPE.getOrEmpty(id).orElseThrow(() -> ENTITY_TYPE_NOT_FOUND.create(id)));
    }

    private static SuggestionProvider<ServerCommandSource> entityTypeSuggestions() {
        return (ctx, builder) -> CommandSource.suggestIdentifiers(Registry.ENTITY_TYPE.getIds(), builder);
    }

    private static SuggestionProvider<ServerCommandSource> stagingSuggestions() {
        return (ctx, builder) -> {
            ServerWorld world = ctx.getSource().getWorld();
            return CommandSource.suggestIdentifiers(
                    StagingMapManager.get(world).getStagingMapKeys().stream(),
                    builder
            );
        };
    }

    private static SuggestionProvider<ServerCommandSource> regionSuggestions() {
        return (context, builder) -> {
            StagingMapTemplate map = getMap(context);
            return CommandSource.suggestMatching(
                    map.getRegions().stream().map(TemplateRegion::getMarker),
                    builder
            );
        };
    }

    private static SuggestionProvider<ServerCommandSource> localRegionSuggestions() {
        return (context, builder) -> {
            StagingMapTemplate map = getMap(context);
            BlockPos sourcePos = context.getSource().getPlayer().getBlockPos();
            return CommandSource.suggestMatching(
                    map.getRegions().stream().filter(region -> region.getBounds().contains(sourcePos))
                            .map(TemplateRegion::getMarker),
                    builder
            );
        };
    }

    private static @NotNull StagingMapTemplate getMap(CommandContext<ServerCommandSource> context) throws
            CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        BlockPos pos = source.getPlayer().getBlockPos();

        StagingMapManager stagingMapManager = StagingMapManager.get(world);

        Optional<StagingMapTemplate> mapOpt = stagingMapManager.getStagingMaps().stream()
                .filter(stagingMap -> stagingMap.getBounds().contains(pos))
                .findFirst();

        if (mapOpt.isPresent()) {
            return mapOpt.get();
        } else {
            throw MAP_NOT_FOUND_AT.create();
        }
    }

    private static @NotNull StagingMapTemplate getMapFromArg(CommandContext<ServerCommandSource> context) throws
            CommandSyntaxException {
        Identifier identifier = IdentifierArgumentType.getIdentifier(context, "identifier");

        StagingMapManager stagingMapManager = StagingMapManager.get(context.getSource().getWorld());
        StagingMapTemplate stagingMap = stagingMapManager.get(identifier);
        if (stagingMap == null) {
            throw MAP_NOT_FOUND.create(identifier);
        }

        return stagingMap;
    }

    private static Command<ServerCommandSource> executeInRegions(String message, RegionExecutor executor) {
        return context -> {
            ServerCommandSource source = context.getSource();
            BlockPos pos = source.getPlayer().getBlockPos();

            String marker = StringArgumentType.getString(context, "marker");

            StagingMapTemplate map = getMap(context);
            List<TemplateRegion> regions = map.getRegions().stream()
                    .filter(region -> region.getBounds().contains(pos) && region.getMarker().equals(marker))
                    .collect(Collectors.toList());

            int count = 0;
            for (TemplateRegion region : regions) {
                if (executor.execute(context, map, region)) { count++; }
            }

            if (count > 0) {
                map.setDirty();
                source.sendFeedback(withMapPrefix(map, new LiteralText(String.format(message, count))), false);
            }
            return 2;
        };
    }

    private static Text getMapPrefix(StagingMapTemplate map) {
        return withMapPrefix(map, null);
    }

    private static Text withMapPrefix(StagingMapTemplate map, @Nullable Text text) {
        MutableText prefix = new LiteralText("")
                .append(new LiteralText("[").formatted(Formatting.GRAY))
                .append(new LiteralText(map.getIdentifier().toString()).formatted(Formatting.GOLD))
                .append(new LiteralText("] ").formatted(Formatting.GRAY));
        if (text != null) prefix.append(text);
        return prefix;
    }

    @FunctionalInterface
    private interface RegionExecutor {
        boolean execute(CommandContext<ServerCommandSource> context, StagingMapTemplate map, TemplateRegion region);
    }

    @FunctionalInterface
    private interface RegionPredicate {
        boolean test(TemplateRegion region, String marker, BlockPos pos);
    }
}
