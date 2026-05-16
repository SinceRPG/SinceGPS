package net.danh.sinceGPS.utils;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.danh.sinceGPS.SinceGPS;
import org.bukkit.entity.Entity;

import java.util.concurrent.TimeUnit;

public final class SchedulerAdapter {
    private final SinceGPS plugin;

    public SchedulerAdapter(SinceGPS plugin) {
        this.plugin = plugin;
    }

    public TaskHandle runAsync(Runnable runnable) {
        ScheduledTask task = plugin.getServer().getAsyncScheduler().runNow(plugin, scheduledTask -> runnable.run());
        return task::cancel;
    }

    public TaskHandle runEntity(Entity entity, Runnable runnable) {
        ScheduledTask task = entity.getScheduler().run(plugin, scheduledTask -> runnable.run(), null);
        return task == null ? TaskHandle.NOOP : task::cancel;
    }

    public TaskHandle runEntityTimer(Entity entity, Runnable runnable, long delayTicks, long periodTicks) {
        ScheduledTask task = entity.getScheduler().runAtFixedRate(plugin, scheduledTask -> runnable.run(), null,
                Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        return task == null ? TaskHandle.NOOP : task::cancel;
    }

    public TaskHandle runAsyncTimer(Runnable runnable, long delayTicks, long periodTicks) {
        ScheduledTask task = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, scheduledTask -> runnable.run(),
                Math.max(1L, delayTicks) * 50L, Math.max(1L, periodTicks) * 50L, TimeUnit.MILLISECONDS);
        return task::cancel;
    }

    public interface TaskHandle {
        TaskHandle NOOP = () -> {
        };

        void cancel();
    }
}
