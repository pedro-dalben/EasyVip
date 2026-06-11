package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ExpirationService {

    private static ScheduledExecutorService scheduler;

    private ExpirationService() {
    }

    public static synchronized void start(MinecraftServer server) {
        if (scheduler != null) return;

        if (server != null && !server.isStopped()) {
            server.execute(() -> {
                VipService.expireAllDueVips(server);
                PackageService.cleanupExpiredPendingVariants();
            });
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "EasyVip-Expiration-Scheduler");
            thread.setDaemon(true);
            return thread;
        });

        long interval = EasyVipConfig.common.autoExpireIntervalSeconds;
        if (interval < 5) {
            interval = 5; // Enforce minimum interval
        }

        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (server != null && !server.isStopped()) {
                    server.execute(() -> {
                        VipService.expireAllDueVips(server);
                        PackageService.cleanupExpiredPendingVariants();
                    });
                }
            } catch (Exception e) {
                System.err.println("[EasyVip] Error in expiration scheduler tick: " + e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    public static synchronized void reload(MinecraftServer server) {
        stop();
        start(server);
    }

    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            scheduler = null;
        }
    }
}
