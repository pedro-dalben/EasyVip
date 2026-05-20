package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionContext;
import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.PlayerVipRecord;
import br.com.pedrodalben.easyvip.model.PlayerVipRegistry;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class VipServiceCorrectionsTest {

    @TempDir
    Path tempDir;

    private Map<String, EasyVipConfig.VipTierDefinition> tierBackup;
    private boolean originalForceHighest;

    @BeforeEach
    void setUp() {
        PersistenceManager.initialize(tempDir);

        tierBackup = new LinkedHashMap<>(EasyVipConfig.tiers.list);
        originalForceHighest = EasyVipConfig.common.forceHighestPriorityAsActive;

        EasyVipConfig.tiers.list.clear();
        EasyVipConfig.common.forceHighestPriorityAsActive = true;
    }

    @AfterEach
    void tearDown() {
        PersistenceManager.flush();
        EasyVipConfig.tiers.list.clear();
        EasyVipConfig.tiers.list.putAll(tierBackup);
        EasyVipConfig.common.forceHighestPriorityAsActive = originalForceHighest;
        PersistenceManager.flush();
    }

    @Test
    void offlineExpiredVipRunsExpireAndUnsetActions() {
        UUID uuid = UUID.randomUUID();
        EasyVipConfig.VipTierDefinition tier = tier("vip", 10,
                List.of(action("run_server_command", Map.of("command", "ftbranks remove {player} vip-unset"))),
                List.of(action("run_server_command", Map.of("command", "ftbranks remove {player} vip-expire"))),
                List.of());
        EasyVipConfig.tiers.list.put(tier.id, tier);

        PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
        registry.setPlayerName("Pedro");
        registry.getVips().put("vip", new PlayerVipRecord("vip", System.currentTimeMillis() - 10_000L, System.currentTimeMillis() - 1_000L, true, false));
        PersistenceManager.updatePlayerVips(uuid, registry);

        AtomicInteger executions = new AtomicInteger();
        try (MockedStatic<ActionExecutor> mocked = mockStatic(ActionExecutor.class)) {
            mocked.when(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap()))
                    .thenAnswer(invocation -> {
                        executions.incrementAndGet();
                        return true;
                    });

            int expired = VipService.expireDueVipsForTest(uuid, "Pedro");

            assertEquals(1, expired);
            assertTrue(PersistenceManager.getPlayerVips(uuid).getVips().isEmpty());
            assertEquals(2, executions.get());
        }
    }

    @Test
    void expiredVipSelectsAnotherValidActiveVip() {
        UUID uuid = UUID.randomUUID();
        EasyVipConfig.VipTierDefinition low = tier("low", 1, List.of(), List.of(), List.of());
        EasyVipConfig.VipTierDefinition high = tier("high", 10,
                List.of(action("run_server_command", Map.of("command", "ftbranks set {player} high"))),
                List.of(), List.of());
        EasyVipConfig.tiers.list.put(low.id, low);
        EasyVipConfig.tiers.list.put(high.id, high);

        PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
        registry.setPlayerName("Pedro");
        registry.getVips().put("low", new PlayerVipRecord("low", System.currentTimeMillis() - 20_000L, System.currentTimeMillis() - 1_000L, true, false));
        registry.getVips().put("high", new PlayerVipRecord("high", System.currentTimeMillis(), -1L, false, false));
        PersistenceManager.updatePlayerVips(uuid, registry);

        try (MockedStatic<ActionExecutor> mocked = mockStatic(ActionExecutor.class)) {
            mocked.when(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap())).thenReturn(true);

            int expired = VipService.expireDueVipsForTest(uuid, "Pedro");

            assertEquals(1, expired);
            PlayerVipRegistry updated = PersistenceManager.getPlayerVips(uuid);
            assertFalse(updated.getVips().containsKey("low"));
            assertTrue(updated.getVips().get("high").isActive());
        }
    }

    private EasyVipConfig.VipTierDefinition tier(String id, int priority, List<Map<String, Object>> activate, List<Map<String, Object>> expire, List<Map<String, Object>> unset) {
        EasyVipConfig.VipTierDefinition tier = new EasyVipConfig.VipTierDefinition();
        tier.id = id;
        tier.displayName = id.toUpperCase();
        tier.priority = priority;
        tier.actionsOnActivate = new ArrayList<>(activate);
        tier.actionsOnExpire = new ArrayList<>(expire);
        tier.actionsOnUnsetActive = new ArrayList<>(unset);
        tier.actionsOnSetActive = new ArrayList<>();
        return tier;
    }

    private Map<String, Object> action(String type, Map<String, Object> extras) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", type);
        action.putAll(extras);
        return action;
    }
}
