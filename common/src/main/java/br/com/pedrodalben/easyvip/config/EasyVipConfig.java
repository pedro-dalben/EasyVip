package br.com.pedrodalben.easyvip.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class EasyVipConfig {

    private static Path configDir;

    public static final CommonConfig common = new CommonConfig();
    public static final MessagesConfig messages = new MessagesConfig();
    public static final TiersConfig tiers = new TiersConfig();
    public static final PackagesConfig packages = new PackagesConfig();
    public static final RewardKeysConfig rewardKeys = new RewardKeysConfig();
    public static final IntegrationsConfig integrations = new IntegrationsConfig();

    private EasyVipConfig() {
    }

    public static synchronized void initialize(Path dir) {
        configDir = dir;
    }

    public static void loadAll() throws IllegalArgumentException, IOException {
        Files.createDirectories(configDir);

        loadCommon();
        loadMessages();
        loadTiers();
        loadPackages();
        loadRewardKeys();
        loadIntegrations();
    }

    // ─── Common Config ──────────────────────────────────────
    public static class CommonConfig {
        public int keyLength = 12;
        public String keyPrefix = "EVIP-";
        public String keyCharset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        public boolean caseSensitiveKeys = false;
        public boolean confirmBeforeUse = true;
        public int confirmTimeoutSeconds = 30;
        public int commandCooldownTicks = 20;
        public List<String> allowedDimensions = new ArrayList<>();
        public List<String> denyDimensions = new ArrayList<>();
        public int autoExpireIntervalSeconds = 30;
        public String defaultActivationMode = "extend";
        public boolean forceHighestPriorityAsActive = false;
        public boolean allowPlayerActiveSelection = true;
        public boolean logToFile = true;
        public boolean debug = false;
        public boolean commandAllowlistEnabled = true;
        public List<String> commandAllowlist = Arrays.asList("ftbranks ", "team ", "effect ", "give ");
    }

    private static void loadCommon() throws IllegalArgumentException, IOException {
        Path file = configDir.resolve("common.toml");
        if (!Files.exists(file)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key_length", common.keyLength);
            map.put("key_prefix", common.keyPrefix);
            map.put("key_charset", common.keyCharset);
            map.put("case_sensitive_keys", common.caseSensitiveKeys);
            map.put("confirm_before_use", common.confirmBeforeUse);
            map.put("confirm_timeout_seconds", common.confirmTimeoutSeconds);
            map.put("command_cooldown_ticks", common.commandCooldownTicks);
            map.put("allowed_dimensions", common.allowedDimensions);
            map.put("deny_dimensions", common.denyDimensions);
            map.put("auto_expire_interval_seconds", common.autoExpireIntervalSeconds);
            map.put("default_activation_mode", common.defaultActivationMode);
            map.put("force_highest_priority_as_active", common.forceHighestPriorityAsActive);
            map.put("allow_player_active_selection", common.allowPlayerActiveSelection);
            map.put("log_to_file", common.logToFile);
            map.put("debug", common.debug);
            map.put("command_allowlist_enabled", common.commandAllowlistEnabled);
            map.put("command_allowlist", common.commandAllowlist);
            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> data = TomlParser.parseFile(file);
        common.keyLength = getInt(data, "key_length", 12);
        common.keyPrefix = getString(data, "key_prefix", "EVIP-");
        common.keyCharset = getString(data, "key_charset", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        common.caseSensitiveKeys = getBoolean(data, "case_sensitive_keys", false);
        common.confirmBeforeUse = getBoolean(data, "confirm_before_use", true);
        common.confirmTimeoutSeconds = getInt(data, "confirm_timeout_seconds", 30);
        common.commandCooldownTicks = getInt(data, "command_cooldown_ticks", 20);
        common.allowedDimensions = getStringList(data, "allowed_dimensions", new ArrayList<>());
        common.denyDimensions = getStringList(data, "deny_dimensions", new ArrayList<>());
        common.autoExpireIntervalSeconds = getInt(data, "auto_expire_interval_seconds", 30);
        common.defaultActivationMode = getString(data, "default_activation_mode", "extend");
        common.forceHighestPriorityAsActive = getBoolean(data, "force_highest_priority_as_active", false);
        common.allowPlayerActiveSelection = getBoolean(data, "allow_player_active_selection", true);
        common.logToFile = getBoolean(data, "log_to_file", true);
        common.debug = getBoolean(data, "debug", false);
        common.commandAllowlistEnabled = getBoolean(data, "command_allowlist_enabled", true);
        common.commandAllowlist = getStringList(data, "command_allowlist", common.commandAllowlist);
    }

    // ─── Messages Config ────────────────────────────────────
    public static class MessagesConfig {
        public String prefix = "&7[&eEasyVip&7] ";
        public String noPermission = "&cVocê não tem permissão para usar este comando.";
        public String playerOnly = "&cEste comando só pode ser usado por jogadores.";
        public String adminOnly = "&cEste comando só pode ser usado por administradores.";
        public String invalidPlayer = "&cJogador não encontrado.";
        public String invalidTier = "&cTier VIP inválido.";
        public String invalidDuration = "&cDuração inválida.";
        public String invalidKey = "&cA chave inserida é inválida.";
        public String keyExpired = "&cEsta chave expirou.";
        public String keyNoUsesLeft = "&cEsta chave não possui mais usos.";
        public String keyAlreadyUsed = "&cEsta chave já foi usada.";
        public String keyBoundToOtherPlayer = "&cEsta chave está vinculada a outro jogador.";
        public String keyConfirmRequired = "&eEsta chave ativará o VIP {tier_display} por {duration}. Digite /easyvip confirm para confirmar.";
        public String keyConfirmed = "&aChave confirmada com sucesso!";
        public String vipActivated = "&aVIP {tier_display} ativado por {duration}!";
        public String vipExtended = "&aVIP {tier_display} estendido por mais {duration}!";
        public String vipSet = "&aVIP {tier_display} definido para {player} por {duration}!";
        public String vipRemoved = "&aVIP {tier_display} removido de {player}!";
        public String vipExpired = "&cSeu VIP {tier_display} expirou.";
        public String vipNotFound = "&cVocê não possui este VIP ativo.";
        public String vipTimeHeader = "&e--- Seus VIPs ---";
        public String vipTimeLine = "&7- {tier_display}: &f{duration_left} restante";
        public String activeVipChanged = "&aSeu VIP ativo foi alterado para {tier_display}.";
        public String activeVipNotOwned = "&cVocê não possui este tier VIP.";
        public String packageGiven = "&aVocê recebeu o pacote {package}!";
        public String packageNotFound = "&cPacote não encontrado.";
        public String variantPending = "&eVocê possui uma escolha de variante pendente para o pacote {package}. Use /easyvip variant choose {package} <variante> para escolher.";
        public String variantSelected = "&aVariante {variant} selecionada com sucesso!";
        public String variantInvalid = "&cVariante inválida. Escolhas permitidas: {allowed_variants}";
        public String reloadSuccess = "&aConfigurações recarregadas com sucesso!";
        public String reloadError = "&cErro ao recarregar configurações: {error}";
        public String configInvalid = "&cConfiguração inválida encontrada.";
    }

    private static void loadMessages() throws IllegalArgumentException, IOException {
        Path file = configDir.resolve("messages.toml");
        if (!Files.exists(file)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("prefix", messages.prefix);
            map.put("no_permission", messages.noPermission);
            map.put("player_only", messages.playerOnly);
            map.put("admin_only", messages.adminOnly);
            map.put("invalid_player", messages.invalidPlayer);
            map.put("invalid_tier", messages.invalidTier);
            map.put("invalid_duration", messages.invalidDuration);
            map.put("invalid_key", messages.invalidKey);
            map.put("key_expired", messages.keyExpired);
            map.put("key_no_uses_left", messages.keyNoUsesLeft);
            map.put("key_already_used", messages.keyAlreadyUsed);
            map.put("key_bound_to_other_player", messages.keyBoundToOtherPlayer);
            map.put("key_confirm_required", messages.keyConfirmRequired);
            map.put("key_confirmed", messages.keyConfirmed);
            map.put("vip_activated", messages.vipActivated);
            map.put("vip_extended", messages.vipExtended);
            map.put("vip_set", messages.vipSet);
            map.put("vip_removed", messages.vipRemoved);
            map.put("vip_expired", messages.vipExpired);
            map.put("vip_not_found", messages.vipNotFound);
            map.put("vip_time_header", messages.vipTimeHeader);
            map.put("vip_time_line", messages.vipTimeLine);
            map.put("active_vip_changed", messages.activeVipChanged);
            map.put("active_vip_not_owned", messages.activeVipNotOwned);
            map.put("package_given", messages.packageGiven);
            map.put("package_not_found", messages.packageNotFound);
            map.put("variant_pending", messages.variantPending);
            map.put("variant_selected", messages.variantSelected);
            map.put("variant_invalid", messages.variantInvalid);
            map.put("reload_success", messages.reloadSuccess);
            map.put("reload_error", messages.reloadError);
            map.put("config_invalid", messages.configInvalid);
            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> data = TomlParser.parseFile(file);
        messages.prefix = getString(data, "prefix", messages.prefix);
        messages.noPermission = getString(data, "no_permission", messages.noPermission);
        messages.playerOnly = getString(data, "player_only", messages.playerOnly);
        messages.adminOnly = getString(data, "admin_only", messages.adminOnly);
        messages.invalidPlayer = getString(data, "invalid_player", messages.invalidPlayer);
        messages.invalidTier = getString(data, "invalid_tier", messages.invalidTier);
        messages.invalidDuration = getString(data, "invalid_duration", messages.invalidDuration);
        messages.invalidKey = getString(data, "invalid_key", messages.invalidKey);
        messages.keyExpired = getString(data, "key_expired", messages.keyExpired);
        messages.keyNoUsesLeft = getString(data, "key_no_uses_left", messages.keyNoUsesLeft);
        messages.keyAlreadyUsed = getString(data, "key_already_used", messages.keyAlreadyUsed);
        messages.keyBoundToOtherPlayer = getString(data, "key_bound_to_other_player", messages.keyBoundToOtherPlayer);
        messages.keyConfirmRequired = getString(data, "key_confirm_required", messages.keyConfirmRequired);
        messages.keyConfirmed = getString(data, "key_confirmed", messages.keyConfirmed);
        messages.vipActivated = getString(data, "vip_activated", messages.vipActivated);
        messages.vipExtended = getString(data, "vip_extended", messages.vipExtended);
        messages.vipSet = getString(data, "vip_set", messages.vipSet);
        messages.vipRemoved = getString(data, "vip_removed", messages.vipRemoved);
        messages.vipExpired = getString(data, "vip_expired", messages.vipExpired);
        messages.vipNotFound = getString(data, "vip_not_found", messages.vipNotFound);
        messages.vipTimeHeader = getString(data, "vip_time_header", messages.vipTimeHeader);
        messages.vipTimeLine = getString(data, "vip_time_line", messages.vipTimeLine);
        messages.activeVipChanged = getString(data, "active_vip_changed", messages.activeVipChanged);
        messages.activeVipNotOwned = getString(data, "active_vip_not_owned", messages.activeVipNotOwned);
        messages.packageGiven = getString(data, "package_given", messages.packageGiven);
        messages.packageNotFound = getString(data, "package_not_found", messages.packageNotFound);
        messages.variantPending = getString(data, "variant_pending", messages.variantPending);
        messages.variantSelected = getString(data, "variant_selected", messages.variantSelected);
        messages.variantInvalid = getString(data, "variant_invalid", messages.variantInvalid);
        messages.reloadSuccess = getString(data, "reload_success", messages.reloadSuccess);
        messages.reloadError = getString(data, "reload_error", messages.reloadError);
        messages.configInvalid = getString(data, "config_invalid", messages.configInvalid);
    }

    // ─── Tiers Config ───────────────────────────────────────
    public static class TiersConfig {
        public final Map<String, VipTierDefinition> list = new LinkedHashMap<>();
    }

    public static class VipTierDefinition {
        public String id;
        public String displayName;
        public String description;
        public int priority;
        public String defaultDuration = "30d";
        public boolean allowStacking = true;
        public String activationMode = "extend";
        public long maxStackDurationSeconds = 0;
        public String color = "white";
        public List<Map<String, Object>> actionsOnActivate = new ArrayList<>();
        public List<Map<String, Object>> actionsOnExpire = new ArrayList<>();
        public List<Map<String, Object>> actionsOnRemove = new ArrayList<>();
        public List<Map<String, Object>> actionsOnSetActive = new ArrayList<>();
        public List<Map<String, Object>> actionsOnUnsetActive = new ArrayList<>();
    }

    private static void loadTiers() throws IllegalArgumentException, IOException {
        Path file = configDir.resolve("tiers.toml");
        if (!Files.exists(file)) {
            // Write defaults
            Map<String, Object> map = new LinkedHashMap<>();
            Map<String, Object> tiersMap = new LinkedHashMap<>();
            map.put("tiers", tiersMap);

            // Default VIP
            Map<String, Object> vip = new LinkedHashMap<>();
            vip.put("display_name", "VIP");
            vip.put("priority", 10);
            vip.put("default_duration", "30d");
            vip.put("allow_stacking", true);
            vip.put("activation_mode", "extend");
            vip.put("color", "gold");

            List<Map<String, Object>> actActivate = new ArrayList<>();
            Map<String, Object> msgAct = new LinkedHashMap<>();
            msgAct.put("type", "send_message");
            msgAct.put("message", "&aVocê ativou o VIP {tier_display} por {duration}.");
            actActivate.add(msgAct);

            Map<String, Object> itemAct = new LinkedHashMap<>();
            itemAct.put("type", "give_item");
            itemAct.put("item", "minecraft:diamond");
            itemAct.put("amount", 3);
            actActivate.add(itemAct);

            vip.put("actions_on_activate", actActivate);

            List<Map<String, Object>> actExpire = new ArrayList<>();
            Map<String, Object> msgExp = new LinkedHashMap<>();
            msgExp.put("type", "send_message");
            msgExp.put("message", "&cSeu VIP {tier_display} expirou.");
            actExpire.add(msgExp);
            vip.put("actions_on_expire", actExpire);

            tiersMap.put("vip", vip);

            // Default VIP+
            Map<String, Object> vipPlus = new LinkedHashMap<>();
            vipPlus.put("display_name", "VIP+");
            vipPlus.put("priority", 20);
            vipPlus.put("default_duration", "30d");
            vipPlus.put("allow_stacking", true);
            vipPlus.put("activation_mode", "extend");
            vipPlus.put("color", "aqua");
            tiersMap.put("vip_plus", vipPlus);

            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> parsed = TomlParser.parseFile(file);
        tiers.list.clear();
        Object tiersObj = parsed.get("tiers");
        if (tiersObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tiersMap = (Map<String, Object>) tiersObj;
            for (Map.Entry<String, Object> entry : tiersMap.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tierData = (Map<String, Object>) entry.getValue();
                    VipTierDefinition def = new VipTierDefinition();
                    def.id = entry.getKey();
                    def.displayName = getString(tierData, "display_name", def.id);
                    def.description = getString(tierData, "description", "");
                    def.priority = getInt(tierData, "priority", 0);
                    def.defaultDuration = getString(tierData, "default_duration", "30d");
                    def.allowStacking = getBoolean(tierData, "allow_stacking", true);
                    def.activationMode = getString(tierData, "activation_mode", "extend");
                    def.maxStackDurationSeconds = getLong(tierData, "max_stack_duration_seconds", 0);
                    def.color = getString(tierData, "color", "white");
                    def.actionsOnActivate = getActionList(tierData, "actions_on_activate");
                    def.actionsOnExpire = getActionList(tierData, "actions_on_expire");
                    def.actionsOnRemove = getActionList(tierData, "actions_on_remove");
                    def.actionsOnSetActive = getActionList(tierData, "actions_on_set_active");
                    def.actionsOnUnsetActive = getActionList(tierData, "actions_on_unset_active");

                    tiers.list.put(def.id, def);
                }
            }
        }
    }

    // ─── Packages Config ────────────────────────────────────
    public static class PackagesConfig {
        public final Map<String, PackageDefinition> list = new LinkedHashMap<>();
    }

    public static class PackageDefinition {
        public String id;
        public String displayName;
        public String description;
        public List<Map<String, Object>> actions = new ArrayList<>();
        public Map<String, List<Map<String, Object>>> variants = new LinkedHashMap<>();
        public boolean repeatable = true;
        public long cooldownSeconds = 0;
    }

    private static void loadPackages() throws IllegalArgumentException, IOException {
        Path file = configDir.resolve("packages.toml");
        if (!Files.exists(file)) {
            Map<String, Object> map = new LinkedHashMap<>();
            Map<String, Object> packagesMap = new LinkedHashMap<>();
            map.put("packages", packagesMap);

            // Default Package kit_inicial
            Map<String, Object> kit = new LinkedHashMap<>();
            kit.put("display_name", "Kit Inicial");
            kit.put("description", "Escolha uma variante para receber seus itens.");
            kit.put("repeatable", true);
            kit.put("cooldown_seconds", 3600);

            List<Map<String, Object>> baseActions = new ArrayList<>();
            Map<String, Object> baseMsg = new LinkedHashMap<>();
            baseMsg.put("type", "send_message");
            baseMsg.put("message", "&aVocê resgatou o Kit Inicial!");
            baseActions.add(baseMsg);
            kit.put("actions", baseActions);

            Map<String, Object> variantsMap = new LinkedHashMap<>();
            
            List<Map<String, Object>> warActions = new ArrayList<>();
            Map<String, Object> warItem = new LinkedHashMap<>();
            warItem.put("type", "give_item");
            warItem.put("item", "minecraft:iron_sword");
            warItem.put("amount", 1);
            warActions.add(warItem);
            variantsMap.put("guerreiro", warActions);

            List<Map<String, Object>> arcActions = new ArrayList<>();
            Map<String, Object> arcItem = new LinkedHashMap<>();
            arcItem.put("type", "give_item");
            arcItem.put("item", "minecraft:bow");
            arcItem.put("amount", 1);
            arcActions.add(arcItem);
            variantsMap.put("arqueiro", arcActions);

            kit.put("variants", variantsMap);
            packagesMap.put("kit_inicial", kit);

            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> parsed = TomlParser.parseFile(file);
        packages.list.clear();
        Object packagesObj = parsed.get("packages");
        if (packagesObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> packagesMap = (Map<String, Object>) packagesObj;
            for (Map.Entry<String, Object> entry : packagesMap.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pkgData = (Map<String, Object>) entry.getValue();
                    PackageDefinition def = new PackageDefinition();
                    def.id = entry.getKey();
                    def.displayName = getString(pkgData, "display_name", def.id);
                    def.description = getString(pkgData, "description", "");
                    def.repeatable = getBoolean(pkgData, "repeatable", true);
                    def.cooldownSeconds = getLong(pkgData, "cooldown_seconds", 0);
                    def.actions = getActionList(pkgData, "actions");

                    Object variantsObj = pkgData.get("variants");
                    if (variantsObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> varMap = (Map<String, Object>) variantsObj;
                        for (Map.Entry<String, Object> vEntry : varMap.entrySet()) {
                            if (vEntry.getValue() instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> actList = (List<Map<String, Object>>) vEntry.getValue();
                                def.variants.put(vEntry.getKey(), actList);
                            }
                        }
                    }
                    packages.list.put(def.id, def);
                }
            }
        }
    }

    // ─── Reward Keys Config ─────────────────────────────────
    public static class RewardKeysConfig {
        public final Map<String, RewardKeyDefinition> list = new LinkedHashMap<>();
    }

    public static class RewardKeyDefinition {
        public String id;
        public String displayName;
        public List<Map<String, Object>> actions = new ArrayList<>();
        public boolean consumeOnUse = true;
        public long cooldownSeconds = 0;
        public List<String> allowedDimensions = new ArrayList<>();
    }

    private static void loadRewardKeys() throws IllegalArgumentException, IOException {
        Path file = configDir.resolve("reward_keys.toml");
        if (!Files.exists(file)) {
            Map<String, Object> map = new LinkedHashMap<>();
            Map<String, Object> keysMap = new LinkedHashMap<>();
            map.put("reward_keys", keysMap);

            Map<String, Object> coins = new LinkedHashMap<>();
            coins.put("display_name", "Chave de Moedas");
            coins.put("consume_on_use", true);
            coins.put("cooldown_seconds", 0);

            List<Map<String, Object>> actionsList = new ArrayList<>();
            Map<String, Object> runCmd = new LinkedHashMap<>();
            runCmd.put("type", "run_server_command");
            runCmd.put("command", "give {player} minecraft:gold_nugget 5");
            actionsList.add(runCmd);
            coins.put("actions", actionsList);

            keysMap.put("coins_5", coins);
            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> parsed = TomlParser.parseFile(file);
        rewardKeys.list.clear();
        Object rkObj = parsed.get("reward_keys");
        if (rkObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> keysMap = (Map<String, Object>) rkObj;
            for (Map.Entry<String, Object> entry : keysMap.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rkData = (Map<String, Object>) entry.getValue();
                    RewardKeyDefinition def = new RewardKeyDefinition();
                    def.id = entry.getKey();
                    def.displayName = getString(rkData, "display_name", def.id);
                    def.consumeOnUse = getBoolean(rkData, "consume_on_use", true);
                    def.cooldownSeconds = getLong(rkData, "cooldown_seconds", 0);
                    def.allowedDimensions = getStringList(rkData, "allowed_dimensions", new ArrayList<>());
                    def.actions = getActionList(rkData, "actions");

                    rewardKeys.list.put(def.id, def);
                }
            }
        }
    }

    // ─── Integrations Config ────────────────────────────────
    public static class IntegrationsConfig {
        public boolean ftbRanksEnabled = true;
        public boolean luckpermsEnabled = true;
        public String primaryPermissionBridge = "ftbranks"; // ftbranks, luckperms, vanilla
        public boolean sqlEnabled = false;
        public String sqlUrl = "jdbc:sqlite:config/easyvip/data/database.db";
        public String sqlUsername = "";
        public String sqlPassword = "";
    }

    private static void loadIntegrations() throws IllegalArgumentException, IOException {
        Path file = configDir.resolve("integrations.toml");
        if (!Files.exists(file)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ftb_ranks_enabled", integrations.ftbRanksEnabled);
            map.put("luckperms_enabled", integrations.luckpermsEnabled);
            map.put("primary_permission_bridge", integrations.primaryPermissionBridge);
            map.put("sql_enabled", integrations.sqlEnabled);
            map.put("sql_url", integrations.sqlUrl);
            map.put("sql_username", integrations.sqlUsername);
            map.put("sql_password", integrations.sqlPassword);
            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> data = TomlParser.parseFile(file);
        integrations.ftbRanksEnabled = getBoolean(data, "ftb_ranks_enabled", true);
        integrations.luckpermsEnabled = getBoolean(data, "luckperms_enabled", true);
        integrations.primaryPermissionBridge = getString(data, "primary_permission_bridge", "ftbranks");
        integrations.sqlEnabled = getBoolean(data, "sql_enabled", false);
        integrations.sqlUrl = getString(data, "sql_url", integrations.sqlUrl);
        integrations.sqlUsername = getString(data, "sql_username", "");
        integrations.sqlPassword = getString(data, "sql_password", "");
    }

    public static List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (common.keyLength < 4) {
            errors.add("common.toml: key_length deve ser no mínimo 4.");
        }
        if (!common.defaultActivationMode.equals("extend") && !common.defaultActivationMode.equals("replace") &&
            !common.defaultActivationMode.equals("stack") && !common.defaultActivationMode.equals("deny")) {
            errors.add("common.toml: default_activation_mode inválido: " + common.defaultActivationMode);
        }
        for (VipTierDefinition tier : tiers.list.values()) {
            if (tier.id == null || tier.id.trim().isEmpty()) {
                errors.add("tiers.toml: ID do tier não pode ser vazio.");
            }
            if (tier.priority < 0) {
                errors.add("tiers.toml: priority do tier " + tier.id + " não pode ser negativa.");
            }
            if (!tier.activationMode.equals("extend") && !tier.activationMode.equals("replace") &&
                !tier.activationMode.equals("stack") && !tier.activationMode.equals("deny")) {
                errors.add("tiers.toml: activation_mode inválido para o tier " + tier.id + ": " + tier.activationMode);
            }
        }
        for (PackageDefinition pkg : packages.list.values()) {
            if (pkg.id == null || pkg.id.trim().isEmpty()) {
                errors.add("packages.toml: ID do pacote não pode ser vazio.");
            }
        }
        for (RewardKeyDefinition rk : rewardKeys.list.values()) {
            if (rk.id == null || rk.id.trim().isEmpty()) {
                errors.add("reward_keys.toml: ID do reward key não pode ser vazio.");
            }
        }
        return errors;
    }

    // ─── Helper Methods ─────────────────────────────────────
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getActionList(Map<String, Object> parent, String key) {
        Object obj = parent.get(key);
        if (obj instanceof List) {
            List<Map<String, Object>> actions = new ArrayList<>();
            for (Object item : (List<?>) obj) {
                if (item instanceof Map) {
                    actions.add((Map<String, Object>) item);
                }
            }
            return actions;
        }
        return new ArrayList<>();
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        return val != null ? val.toString() : def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val != null) {
            try {
                return Integer.parseInt(val.toString());
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return def;
    }

    private static long getLong(Map<String, Object> map, String key, long def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).longValue();
        if (val != null) {
            try {
                return Long.parseLong(val.toString());
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return def;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean def) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val != null) return Boolean.parseBoolean(val.toString());
        return def;
    }

    private static List<String> getStringList(Map<String, Object> map, String key, List<String> def) {
        Object val = map.get(key);
        if (val instanceof List) {
            List<String> list = new ArrayList<>();
            for (Object o : (List<?>) val) {
                if (o != null) list.add(o.toString());
            }
            return list;
        }
        return def;
    }
}
