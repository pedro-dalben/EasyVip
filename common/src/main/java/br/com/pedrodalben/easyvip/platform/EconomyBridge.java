package br.com.pedrodalben.easyvip.platform;

import net.minecraft.server.level.ServerPlayer;
import java.math.BigDecimal;

public interface EconomyBridge {

    boolean hasBalance(ServerPlayer player, BigDecimal amount);

    boolean withdraw(ServerPlayer player, BigDecimal amount);

    boolean deposit(ServerPlayer player, BigDecimal amount);
}
