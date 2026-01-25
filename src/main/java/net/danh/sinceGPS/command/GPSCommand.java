package net.danh.sinceGPS.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.core.Node;
import net.danh.sinceGPS.utils.ColorUtils;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.entity.Player;

public class GPSCommand {
    private final SinceGPS plugin;

    public GPSCommand(SinceGPS plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(Commands.literal("gps")
                    .requires(source -> source.getSender().hasPermission("gps.use"))

                    .then(Commands.literal("reload")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .executes(ctx -> {
                                plugin.reloadPlugin();
                                ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("reload-success")));
                                return 1;
                            })
                    )
                    .then(Commands.literal("record")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .executes(ctx -> {
                                if (ctx.getSource().getExecutor() instanceof Player p)
                                    plugin.getGraphManager().toggleRecord(p);
                                return 1;
                            })
                    )
                    .then(Commands.literal("stop")
                            .executes(ctx -> {
                                if (ctx.getSource().getExecutor() instanceof Player p)
                                    plugin.getNav().stopNavigation(p, true);
                                return 1;
                            })
                    )
                    .then(Commands.literal("list")
                            .executes(ctx -> {
                                if (ctx.getSource().getExecutor() instanceof Player p) {
                                    p.sendMessage(ColorUtils.parse(plugin.getMsg().getString("list-header")));
                                    for (Node n : plugin.getGraphManager().getNodes()) {
                                        if (!plugin.getGraphManager().canAccess(p, n)) continue;
                                        if (n.getDisplayName().startsWith("node_")) continue;
                                        String msg = plugin.getMsg().getString("list-item")
                                                .replace("<name>", n.getDisplayName())
                                                .replace("<group>", n.getGroup())
                                                .replace("<id>", n.getName());
                                        p.sendMessage(ColorUtils.parse(msg));
                                    }
                                }
                                return 1;
                            })
                    )
                    .then(Commands.literal("setname")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .then(Commands.argument("node_id", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        for (Node n : plugin.getGraphManager().getNodes()) {
                                            if (n.getDisplayName().startsWith("node_")) continue;
                                            builder.suggest(n.getId());
                                        }
                                        return builder.buildFuture();
                                    })
                                    .then(Commands.argument("display_name", StringArgumentType.greedyString())
                                            .executes(ctx -> {
                                                if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
                                                String nodeId = StringArgumentType.getString(ctx, "node_id");
                                                String display = StringArgumentType.getString(ctx, "display_name");
                                                Node target = plugin.getGraphManager().getNode(nodeId);
                                                if (target == null) {
                                                    try {
                                                        target = plugin.getGraphManager().getNode(Integer.parseInt(nodeId));
                                                    } catch (Exception ignored) {
                                                    }
                                                }
                                                if (target != null) {
                                                    target.setDisplayName(display);
                                                    plugin.getGraphManager().save();
                                                    p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("name-set").replace("<id>", String.valueOf(target.getId())).replace("<name>", display)));
                                                } else {
                                                    p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("node-not-found")));
                                                }
                                                return 1;
                                            })
                                    )
                            )
                    )
                    .then(Commands.literal("to")
                            .then(Commands.argument("target", StringArgumentType.greedyString())
                                    .suggests((ctx, builder) -> {
                                        for (Node n : plugin.getGraphManager().getNodes()) {
                                            if (!n.getDisplayName().contains("node_"))
                                                builder.suggest(ColorUtils.stripColor(n.getDisplayName()));
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
                                        String input = StringArgumentType.getString(ctx, "target");
                                        Node target = plugin.getGraphManager().getNode(input);
                                        if (target == null) target = plugin.getGraphManager().getNodeByDisplay(input);

                                        if (target == null) {
                                            String bestMatch = null;
                                            double bestScore = 0.0;
                                            for (Node n : plugin.getGraphManager().getNodes()) {
                                                String plain = ColorUtils.stripColor(n.getDisplayName());
                                                double score = StringUtils.getJaroWinklerDistance(input, plain);
                                                if (score > bestScore) {
                                                    bestScore = score;
                                                    bestMatch = plain;
                                                }
                                            }
                                            if (bestScore > 0.6) {
                                                String real = plugin.getGraphManager().getNodeByDisplay(bestMatch).getName();
                                                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("correction").replace("<guess>", bestMatch).replace("<guess_raw>", real)));
                                                plugin.getCfg().playSound(p, "sounds.popup");
                                            } else {
                                                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("node-not-found")));
                                            }
                                            return 1;
                                        }
                                        if (!plugin.getGraphManager().canAccess(p, target)) {
                                            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("no-permission")));
                                            return 1;
                                        }
                                        plugin.getNav().startNavigation(p, target);
                                        return 1;
                                    })
                            )
                    )
                    .build(), "GPS System"
            );
        });
    }
}