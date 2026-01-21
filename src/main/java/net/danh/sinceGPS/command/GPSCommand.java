package net.danh.sinceGPS.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.utils.ColorUtils;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Set;

public class GPSCommand {

    private final SinceGPS plugin;

    public GPSCommand(SinceGPS plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(Commands.literal("gps")
                    .requires(source -> source.getSender().hasPermission("gps.use"))

                    // --- RELOAD ---
                    .then(Commands.literal("reload")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .executes(ctx -> {
                                plugin.reloadPlugin();
                                ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix("<green>Đã tải lại cấu hình."));
                                return 1;
                            })
                    )

                    // --- TO COMMAND (Logic bạn yêu cầu) ---
                    .then(Commands.literal("to")
                            .then(Commands.argument("target_name", StringArgumentType.greedyString())
                                    .suggests((ctx, builder) -> {
                                        for (String key : plugin.getWaypoints().getWaypointNames()) {
                                            builder.suggest(key);
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
                                        String inputName = StringArgumentType.getString(ctx, "target_name");

                                        Set<String> keys = plugin.getWaypoints().getWaypointNames();

                                        // 1. Tìm chính xác
                                        if (keys.contains(inputName)) {
                                            plugin.getNav().startStatic(p, plugin.getWaypoints().getWaypoint(inputName));
                                            return 1;
                                        }

                                        // 2. Fuzzy Search (Tìm gần đúng)
                                        String bestMatch = null;
                                        double bestScore = 0.0;
                                        for (String key : keys) {
                                            double score = StringUtils.getJaroWinklerDistance(inputName, key);
                                            if (score > bestScore) {
                                                bestScore = score;
                                                bestMatch = key;
                                            }
                                        }

                                        // Kiểm tra ngưỡng giống nhau (mặc định 0.6)
                                        if (bestScore > plugin.getSettingsConfig().getDouble("settings.fuzzy-match-threshold", 0.6) && bestMatch != null) {
                                            String cMsg = plugin.getMessagesConfig().getString("correction").replace("<guess>", bestMatch);
                                            p.sendMessage(ColorUtils.parseWithPrefix(cMsg));
                                            plugin.getNav().startStatic(p, plugin.getWaypoints().getWaypoint(bestMatch));
                                        } else {
                                            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesConfig().getString("not-found")));
                                        }
                                        return 1;
                                    })
                            )
                    )

                    // --- TRACK COMMAND ---
                    .then(Commands.literal("track")
                            .then(Commands.argument("player_name", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        for (Player p : Bukkit.getOnlinePlayers()) builder.suggest(p.getName());
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
                                        String tName = StringArgumentType.getString(ctx, "player_name");
                                        Player target = Bukkit.getPlayer(tName);
                                        if (target != null && target.isOnline()) {
                                            plugin.getNav().startTracking(p, target);
                                            String msg = plugin.getMessagesConfig().getString("tracking").replace("<target>", target.getName());
                                            p.sendMessage(ColorUtils.parseWithPrefix(msg));
                                        } else {
                                            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesConfig().getString("player-offline")));
                                        }
                                        return 1;
                                    })
                            )
                    )

                    // --- STOP COMMAND ---
                    .then(Commands.literal("stop")
                            .executes(ctx -> {
                                if (ctx.getSource().getExecutor() instanceof Player p) {
                                    plugin.getNav().stop(p, true);
                                }
                                return 1;
                            })
                    )

                    // --- SET COMMAND ---
                    .then(Commands.literal("set")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
                                        String name = StringArgumentType.getString(ctx, "name");

                                        plugin.getWaypoints().setWaypoint(name, p.getLocation());

                                        String msg = plugin.getMessagesConfig().getString("saved").replace("<name>", name);
                                        p.sendMessage(ColorUtils.parseWithPrefix(msg));
                                        return 1;
                                    })
                            )
                    )

                    // --- COORD COMMAND ---
                    .then(Commands.literal("coord")
                            .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                    .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                            .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                    .executes(ctx -> {
                                                        if (!(ctx.getSource().getExecutor() instanceof Player p))
                                                            return 0;
                                                        double x = DoubleArgumentType.getDouble(ctx, "x");
                                                        double y = DoubleArgumentType.getDouble(ctx, "y");
                                                        double z = DoubleArgumentType.getDouble(ctx, "z");

                                                        plugin.getNav().startStatic(p, new Location(p.getWorld(), x, y, z));
                                                        return 1;
                                                    })
                                            )
                                    )
                            )
                    )

                    .build(), "GPS Navigation System"
            );
        });
    }
}