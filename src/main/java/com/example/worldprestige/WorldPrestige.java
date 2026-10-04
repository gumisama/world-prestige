package com.example.worldprestige;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(WorldPrestige.MOD_ID)
public class WorldPrestige {
    public static final String MOD_ID = "worldprestige";

    public WorldPrestige() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModRegistry.register(modBus);   // ブロック・アイテムの登録
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, WorldPrestigeConfig.SPEC);   // config/worldprestige-common.toml
        MinecraftForge.EVENT_BUS.register(this);
        PrestigeNetwork.init();
        PrestigeEffects.register();
        PrestigeReset.register();
        SharedPrestige.register();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("prestige")
                        .executes(WorldPrestige::openGui)
                        .then(Commands.literal("gui")
                                .executes(WorldPrestige::openGui))
                        .then(Commands.literal("points")
                                .executes(WorldPrestige::showPoints))
                        // ワールドリセット(確認つき)。実行できる人の条件は PrestigeReset.canUse
                        .then(Commands.literal("reset")
                                .requires(PrestigeReset::canUse)
                                .executes(context -> PrestigeReset.request(context.getSource())))
                        // ここから下は OP 専用のテスト・管理用
                        .then(Commands.literal("give")
                                .requires(source -> source.hasPermission(2))
                                .executes(WorldPrestige::give))
                        .then(Commands.literal("lap")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    SharedPrestige.addLap();
                                    PrestigeNetwork.syncAll(context.getSource().getServer());
                                    context.getSource().sendSuccess(() -> Component.literal("Laps: " + SharedPrestige.getLaps()), false);
                                    return 1;
                                }))
                        .then(Commands.literal("debug")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    var lines = PrestigeEffects.debugLines(context.getSource().getLevel());
                                    int shown = 0;
                                    for (String line : lines) {
                                        if (shown++ >= 20) break;
                                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                                    }
                                    if (lines.size() > 20) {
                                        int rest = lines.size() - 20;
                                        context.getSource().sendSuccess(() -> Component.literal("…他 " + rest + " 行は logs/latest.log"), false);
                                    }
                                    return 1;
                                }))
                        .then(Commands.literal("add")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000))
                                        .executes(WorldPrestige::addPoints)))
                        .then(Commands.literal("set")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0, 1_000_000))
                                        .executes(WorldPrestige::setPoints)))
        );
    }

    private static int openGui(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        PrestigeNetwork.open(context.getSource().getPlayerOrException());
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int after = SharedPrestige.getPoints() + 1;
        SharedPrestige.setPoints(after);
        context.getSource().sendSuccess(
                () -> Component.literal("Prestige! +1 point  (total: " + after + ")"), true);
        PrestigeNetwork.syncAll(context.getSource().getServer());
        PrestigeNetwork.open(player);
        return 1;
    }

    private static int showPoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int points = SharedPrestige.getPoints();
        int laps = SharedPrestige.getLaps();
        context.getSource().sendSuccess(
                () -> Component.literal("Prestige Points(全員共通): " + points + " / 周回数: " + laps), false);
        return points;
    }

    private static int addPoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int amount = IntegerArgumentType.getInteger(context, "amount");
        int after = SharedPrestige.getPoints() + amount;
        SharedPrestige.setPoints(after);
        PrestigeNetwork.syncAll(context.getSource().getServer());
        context.getSource().sendSuccess(
                () -> Component.literal("Added " + amount + " Prestige Points. Total: " + after), true);
        return amount;
    }

    private static int setPoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int amount = IntegerArgumentType.getInteger(context, "amount");
        SharedPrestige.setPoints(amount);
        PrestigeNetwork.syncAll(context.getSource().getServer());
        context.getSource().sendSuccess(
                () -> Component.literal("Prestige Points set to " + amount), true);
        return amount;
    }
}
