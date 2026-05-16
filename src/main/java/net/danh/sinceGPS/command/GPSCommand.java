package net.danh.sinceGPS.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.core.Node;
import net.danh.sinceGPS.utils.ColorUtils;
import net.danh.sinceGPS.utils.SimilarityUtils;
import org.bukkit.configuration.ConfigurationSection;
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
                    .executes(ctx -> {
                        sendHelp(ctx.getSource());
                        return 1;
                    })

                    .then(Commands.literal("help")
                            .executes(ctx -> {
                                sendHelp(ctx.getSource());
                                return 1;
                            })
                    )
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
                    .then(Commands.literal("move")
                            .executes(ctx -> {
                                if (ctx.getSource().getExecutor() instanceof Player p) {
                                    boolean enabled = plugin.getNav().toggleMoveMode(p);
                                    p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString(enabled
                                            ? "arrow-move-enabled" : "arrow-move-disabled")));
                                }
                                return 1;
                            })
                    )
                    .then(Commands.literal("show")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .executes(ctx -> {
                                if (ctx.getSource().getExecutor() instanceof Player p) {
                                    boolean enabled = plugin.getGraphManager().togglePreview(p);
                                    p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString(enabled
                                            ? "preview-enabled" : "preview-disabled")));
                                }
                                return 1;
                            })
                    )
                    .then(Commands.literal("create")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        builder.suggest("spawn");
                                        builder.suggest("market");
                                        builder.suggest("mine");
                                        builder.suggest("base");
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
                                        String name = StringArgumentType.getString(ctx, "name");
                                        if (plugin.getGraphManager().getNode(name) != null) {
                                            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("node-name-exists")
                                                    .replace("<name>", name)));
                                            return 0;
                                        }
                                        Node node = plugin.getGraphManager().createNode(p.getLocation(), "default");
                                        plugin.getGraphManager().renameNode(node, name);
                                        node.setDisplayName(name);
                                        plugin.getGraphManager().saveAsync();
                                        p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("node-created")
                                                .replace("<id>", String.valueOf(node.getId()))
                                                .replace("<name>", name)));
                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("delete")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .then(nodeArgument("target_id")
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
                                        int id = IntegerArgumentType.getInteger(ctx, "target_id");
                                        Node node = plugin.getGraphManager().getNode(id);
                                        if (node == null) {
                                            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("node-not-found")));
                                            return 0;
                                        }
                                        plugin.getGraphManager().removeNode(id);
                                        plugin.getGraphManager().saveAsync();
                                        p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("node-deleted")
                                                .replace("<id>", String.valueOf(id))));
                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("movehere")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .then(nodeArgument("target_id")
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
                                        int id = IntegerArgumentType.getInteger(ctx, "target_id");
                                        if (!plugin.getGraphManager().moveNode(id, p.getLocation())) {
                                            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("node-not-found")));
                                            return 0;
                                        }
                                        plugin.getGraphManager().saveAsync();
                                        p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("node-moved")
                                                .replace("<id>", String.valueOf(id))));
                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("connect")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .then(nodeArgument("from_id")
                                    .then(nodeArgument("to_id")
                                            .executes(ctx -> connectCommand(ctx, false, false))
                                            .then(Commands.literal("oneway")
                                                    .executes(ctx -> connectCommand(ctx, true, false)))))
                    )
                    .then(Commands.literal("disconnect")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .then(nodeArgument("from_id")
                                    .then(nodeArgument("to_id")
                                            .executes(ctx -> connectCommand(ctx, false, true))
                                            .then(Commands.literal("oneway")
                                                    .executes(ctx -> connectCommand(ctx, true, true)))))
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
                                        if (!isNamedDestination(n)) continue;
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
                    .then(Commands.literal("setgroup")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .then(nodeNameArgument("target_id")
                                    .then(Commands.argument("group_name", StringArgumentType.word())
                                            .suggests((ctx, builder) -> {
                                                ConfigurationSection sec = plugin.getCfg().getSection("groups");
                                                if (sec != null)
                                                    for (String key : sec.getKeys(false)) builder.suggest(key);
                                                return builder.buildFuture();
                                            })
                                            .executes(ctx -> {
                                                if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
                                                String targetStr = StringArgumentType.getString(ctx, "target_id");
                                                String groupName = StringArgumentType.getString(ctx, "group_name");
                                                if (plugin.getCfg().getSection("groups." + groupName) == null) {
                                                    p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("group-not-found").replace("<group>", groupName)));
                                                    return 0;
                                                }
                                                Node target = findNode(targetStr);
                                                if (target != null) {
                                                    target.setGroup(groupName);
                                                    plugin.getGraphManager().saveAsync();
                                                    p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("group-set").replace("<node>", getFriendlyId(target)).replace("<group>", groupName)));
                                                } else {
                                                    p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("node-not-found")));
                                                }
                                                return 1;
                                            })
                                    )
                            )
                    )
                    .then(Commands.literal("setname")
                            .requires(source -> source.getSender().hasPermission("gps.admin"))
                            .then(nodeNameArgument("target_id")
                                    .then(Commands.argument("display_name", StringArgumentType.greedyString())
                                            .suggests((ctx, builder) -> {
                                                builder.suggest("Spawn");
                                                builder.suggest("Market");
                                                builder.suggest("Mine Entrance");
                                                builder.suggest("Main Base");
                                                return builder.buildFuture();
                                            })
                                            .executes(ctx -> {
                                                if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
                                                String targetStr = StringArgumentType.getString(ctx, "target_id");
                                                String display = StringArgumentType.getString(ctx, "display_name");
                                                Node target = findNode(targetStr);
                                                if (target != null) {
                                                    target.setDisplayName(display);
                                                    plugin.getGraphManager().saveAsync();
                                                    p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("name-set").replace("<id>", getFriendlyId(target)).replace("<name>", display)));
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
                                        Player player = ctx.getSource().getExecutor() instanceof Player p ? p : null;
                                        for (Node n : plugin.getGraphManager().getNodes()) {
                                            if ((player == null || plugin.getGraphManager().canAccess(player, n)) && isNamedDestination(n)) {
                                                builder.suggest(ColorUtils.stripColor(n.getDisplayName()));
                                            }
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
                                        String input = StringArgumentType.getString(ctx, "target");
                                        Node target = findNode(input);
                                        if (target == null) target = plugin.getGraphManager().getNodeByDisplay(input);
                                        if (target == null) {
                                            String bestMatch = null;
                                            double bestScore = 0.0;
                                            for (Node n : plugin.getGraphManager().getNodes()) {
                                                if (!plugin.getGraphManager().canAccess(p, n) || !isNamedDestination(n)) continue;
                                                String plain = ColorUtils.stripColor(n.getDisplayName());
                                                double score = SimilarityUtils.getJaroWinklerDistance(input, plain);
                                                if (score > bestScore) {
                                                    bestScore = score;
                                                    bestMatch = plain;
                                                }
                                            }
                                            double threshold = plugin.getCfg().getDouble("settings.navigation.suggestion-threshold", 0.6);
                                            if (bestScore > threshold && bestMatch != null) {
                                                Node realNode = plugin.getGraphManager().getNodeByDisplay(bestMatch);
                                                String suggestion = (realNode != null) ? String.valueOf(realNode.getId()) : bestMatch;
                                                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("correction").replace("<guess>", bestMatch).replace("<guess_raw>", suggestion)));
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

    private void sendHelp(CommandSourceStack source) {
        source.getSender().sendMessage(ColorUtils.parse(plugin.getMsg().getString("help-header")));
        source.getSender().sendMessage(ColorUtils.parse(plugin.getMsg().getString("help-to")));
        source.getSender().sendMessage(ColorUtils.parse(plugin.getMsg().getString("help-list")));
        source.getSender().sendMessage(ColorUtils.parse(plugin.getMsg().getString("help-stop")));
        source.getSender().sendMessage(ColorUtils.parse(plugin.getMsg().getString("help-move")));
        if (source.getSender().hasPermission("gps.admin")) {
            source.getSender().sendMessage(ColorUtils.parse(plugin.getMsg().getString("help-admin")));
        }
    }

    private Node findNode(String input) {
        try {
            return plugin.getGraphManager().getNode(Integer.parseInt(input));
        } catch (NumberFormatException ignored) {
        }
        Node node = plugin.getGraphManager().getNode(input);
        if (node != null) return node;
        return plugin.getGraphManager().getNodeByDisplay(input.replace("_", " "));
    }

    private String getFriendlyId(Node n) {
        if (n.getName().equals("node_" + n.getId())) return String.valueOf(n.getId());
        return n.getName();
    }

    private RequiredArgumentBuilder<CommandSourceStack, Integer> nodeArgument(String name) {
        return Commands.argument(name, IntegerArgumentType.integer(0))
                .suggests((ctx, builder) -> {
                    for (Node n : plugin.getGraphManager().getNodes()) builder.suggest(n.getId());
                    return builder.buildFuture();
                });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> nodeNameArgument(String name) {
        return Commands.argument(name, StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    for (Node node : plugin.getGraphManager().getNodes()) {
                        builder.suggest(String.valueOf(node.getId()));
                        builder.suggest(node.getName());
                        String plainDisplay = ColorUtils.stripColor(node.getDisplayName()).replace(" ", "_");
                        if (!plainDisplay.isBlank()) builder.suggest(plainDisplay);
                    }
                    return builder.buildFuture();
                });
    }

    private int connectCommand(CommandContext<CommandSourceStack> ctx, boolean oneWay, boolean disconnect) {
        if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
        int fromId = IntegerArgumentType.getInteger(ctx, "from_id");
        int toId = IntegerArgumentType.getInteger(ctx, "to_id");
        if (fromId == toId) {
            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("same-node")));
            return 0;
        }
        if (plugin.getGraphManager().getNode(fromId) == null || plugin.getGraphManager().getNode(toId) == null) {
            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("node-not-found")));
            return 0;
        }
        if (disconnect) {
            plugin.getGraphManager().disconnect(fromId, toId, oneWay);
        } else {
            plugin.getGraphManager().connect(fromId, toId, oneWay);
        }
        plugin.getGraphManager().saveAsync();
        String key = disconnect ? "nodes-disconnected" : "nodes-connected";
        p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString(key)
                .replace("<from>", String.valueOf(fromId))
                .replace("<to>", String.valueOf(toId))));
        return 1;
    }

    private boolean isNamedDestination(Node node) {
        String displayName = node.getDisplayName();
        return !displayName.startsWith("node_") && !displayName.startsWith("start_") && !displayName.startsWith("stop_");
    }
}
