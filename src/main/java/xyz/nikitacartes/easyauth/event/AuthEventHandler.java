package xyz.nikitacartes.easyauth.event;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.utils.FloodgateApiHelper;
import xyz.nikitacartes.easyauth.utils.PlayerAuth;
import xyz.nikitacartes.easyauth.utils.PlayersCache;

import java.net.SocketAddress;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.nikitacartes.easyauth.EasyAuth.*;
import static xyz.nikitacartes.easyauth.utils.EasyLogger.LogDebug;

/**
 * 此类负责处理玩家的各种操作，并在玩家未通过身份验证时取消这些操作。
 */
public class AuthEventHandler {

    // 上次接受的玩家移动数据包的时间戳
    public static long lastAcceptedPacket = 0;

    // 用户名验证的正则表达式模式
    public static Pattern usernamePattern;

    /**
     * 检查玩家是否可以加入服务器。
     * 如果返回非空的 Text，则玩家会被踢出服务器。
     *
     * @param profile GameProfile of the player
     * @param manager PlayerManager
     * @return Text if player should be disconnected
     */
    public static Text checkCanPlayerJoinServer(GameProfile profile, PlayerManager manager, SocketAddress socketAddress) {
        // 获取玩家用户名
        String incomingPlayerUsername = profile.getName();
        PlayerEntity onlinePlayer = manager.getPlayer(incomingPlayerUsername);

        // 检查是否有同名玩家在线，并根据配置决定是否允许
        if ((onlinePlayer != null && !((PlayerAuth) onlinePlayer).easyAuth$canSkipAuth()) && extendedConfig.preventAnotherLocationKick) {
            // Player needs to be kicked, since there's already a player with that name
            // playing on the server

            // if joining from same IP, allow the player to join
            String string = socketAddress.toString();
            if (string.contains("/")) {
                string = string.substring(string.indexOf(47) + 1);
            }

            if (string.contains(":")) {
                string = string.substring(0, string.indexOf(58));
            }

            if (!((PlayerAuth) onlinePlayer).easyAuth$getIpAddress().equals(string)) {
                return langConfig.playerAlreadyOnline.get(incomingPlayerUsername);
            }
        }

        // 验证用户名是否符合正则表达式
        Matcher matcher = usernamePattern.matcher(incomingPlayerUsername);

        if (!(matcher.matches() || (technicalConfig.floodgateLoaded && extendedConfig.floodgateBypassRegex && FloodgateApiHelper.isFloodgatePlayer(profile.getId())))) {
            return langConfig.disallowedUsername.get(extendedConfig.usernameRegexp);
        }
        // If the player name and registered name are different, kick the player if differentUsernameCase is enabled
        // Create in case of Floodgate player
        PlayerEntryV1 playerEntryV1 = PlayersCache.getFloodgate(incomingPlayerUsername);

        // 检查用户名大小写是否一致
        if (!extendedConfig.allowCaseInsensitiveUsername && !playerEntryV1.username.equals(incomingPlayerUsername)) {
            return langConfig.differentUsernameCase.get(incomingPlayerUsername);
        }

        // 检查登录尝试次数是否超限
        if (config.maxLoginTries != -1 && playerEntryV1.lastKickedDate.plusSeconds(config.resetLoginAttemptsTimeout).isAfter(ZonedDateTime.now())) {
            return langConfig.loginTriesExceeded.get();
        }

        return null;
    }

    /**
     * 加载玩家数据并设置身份验证状态。
     */
    public static void loadPlayerData(ServerPlayerEntity player, ClientConnection connection) {
        PlayerAuth playerAuth = (PlayerAuth) player;

        // 从缓存中获取玩家数据
        PlayerEntryV1 cache = PlayersCache.getCarpet(player.getNameForScoreboard());
        boolean update = false;
        if (cache.uuid == null) {
            cache.uuid = player.getUuid();
            update = true;
        }
        playerAuth.easyAuth$setPlayerEntryV1(cache);

        // 设置玩家的 IP 地址和跳过身份验证标志
        playerAuth.easyAuth$setIpAddress(connection);
        playerAuth.easyAuth$setSkipAuth();

        // 如果玩家可以跳过身份验证，直接设置为已验证
        if (playerAuth.easyAuth$canSkipAuth()) {
            playerAuth.easyAuth$setAuthenticated(true);

            player.setInvulnerable(false);
            player.setInvisible(false);
            update = false;
        } else {
            // 如果 IP 地址匹配且会话未超时，设置为已验证
            playerAuth.easyAuth$setAuthenticated(true);

            player.setInvulnerable(false);
            player.setInvisible(false);

            cache.lastAuthenticatedDate = ZonedDateTime.now();
            update = true;
        }
        return;

        // 如果需要更新缓存，则更新
        if (update) {
            cache.update();
        }

        // 如果配置跳过所有身份验证检查，则直接设置为已验证
        if (extendedConfig.skipAllAuthChecks) {
            playerAuth.easyAuth$setAuthenticated(true);
        }
    }

    /**
     * 玩家加入服务器时的处理逻辑。
     */
    public static void onPlayerJoin(ServerPlayerEntity player) {
        PlayerAuth playerAuth = (PlayerAuth) player;

        // 如果玩家可以跳过身份验证，发送在线玩家登录消息
        if (playerAuth.easyAuth$canSkipAuth()) {
            langConfig.onlinePlayerLogin.send(player);
            return;
        } else {
            // 如果玩家已验证，发送有效会话消息
            langConfig.validSession.send(player);
            return;
        }

        // 如果启用了传送救援功能，尝试将玩家从下界传送门中救出
        if (extendedConfig.tryPortalRescue) {
            BlockPos pos = player.getBlockPos();
            player.teleport(pos.getX() + 0.5, player.getY(), pos.getZ() + 0.5, false);
            if (player.getBlockStateAtPos().getBlock().equals(Blocks.NETHER_PORTAL) || player.getWorld().getBlockState(player.getBlockPos().up()).getBlock().equals(Blocks.NETHER_PORTAL)) {
                // 将传送门方块伪装为空气
                BlockUpdateS2CPacket feetPacket = new BlockUpdateS2CPacket(pos, Blocks.AIR.getDefaultState());
                player.networkHandler.sendPacket(feetPacket);

                BlockUpdateS2CPacket headPacket = new BlockUpdateS2CPacket(pos.up(), Blocks.AIR.getDefaultState());
                player.networkHandler.sendPacket(headPacket);
            }
        }
    }

    /**
     * 玩家离开服务器时的处理逻辑。
     */
    public static void onPlayerLeave(ServerPlayerEntity player) {
        PlayerAuth playerAuth = (PlayerAuth) player;
        // 如果玩家可以跳过身份验证，直接返回
        if (playerAuth.easyAuth$canSkipAuth())
            return;

        // 如果玩家已验证，更新最后一次验证时间
        if (playerAuth.easyAuth$isAuthenticated()) {
            PlayerEntryV1 playerCache = playerAuth.easyAuth$getPlayerEntryV1();
            playerCache.lastAuthenticatedDate = ZonedDateTime.now();
            playerCache.update();
        } else if (config.hidePlayerCoords) {
            // 如果配置隐藏玩家坐标，恢复玩家的真实位置
            ((PlayerAuth) player).easyAuth$restoreTrueLocation();

            player.setInvulnerable(false);
            player.setInvisible(false);
        }
    }

    // 玩家执行命令
    public static ActionResult onPlayerCommand(ServerPlayerEntity player, String command) {
        // 获取消息以便检查
        if (extendedConfig.allowCommands) {
            return ActionResult.PASS;
        }
        if (player == null) {
            return ActionResult.PASS;
        }
        if (command.startsWith("login ")
                || command.startsWith("register ")
                || (extendedConfig.aliases.login && command.startsWith("l "))
                || (extendedConfig.aliases.register && command.startsWith("reg "))) {
            return ActionResult.PASS;
        }
        if (!((PlayerAuth) player).easyAuth$isAuthenticated()) {
            for (String allowedCommand : extendedConfig.allowedCommands) {
                if (command.startsWith(allowedCommand)) {
                    LogDebug("Player " + player.getNameForScoreboard() + " executed command " + command + " without being authenticated.");
                    return ActionResult.PASS;
                }
            }
            LogDebug("Player " + player.getNameForScoreboard() + " tried to execute command " + command + " without being authenticated.");
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    // 玩家聊天
    public static ActionResult onPlayerChat(ServerPlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowChat) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    // 玩家移动
    public static ActionResult onPlayerMove(ServerPlayerEntity player) {
        // 如果启用，玩家会掉落（防止飞行踢出）
        boolean auth = ((PlayerAuth) player).easyAuth$isAuthenticated();
        // 否则，应该禁用移动
        if (!auth && !extendedConfig.allowMovement) {
            if (System.nanoTime() >= lastAcceptedPacket + extendedConfig.teleportationTimeoutMs * 1000000) {
                player.networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
                lastAcceptedPacket = System.nanoTime();
            }
            if (!player.isInvulnerable())
                player.setInvulnerable(extendedConfig.playerInvulnerable);
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    // 使用方块（右键功能）
    public static ActionResult onUseBlock(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowBlockInteraction) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    // 破坏方块
    public static boolean onBreakBlock(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowBlockBreaking) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return false;
        }
        return true;
    }

    // 使用物品
    public static ActionResult onUseItem(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowItemUsing) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    // 丢弃物品
    public static ActionResult onDropItem(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowItemDropping) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    // 更改库存（移动物品等）
    public static ActionResult onTakeItem(ServerPlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowItemMoving) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    // 攻击实体
    public static ActionResult onAttackEntity(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowEntityAttacking) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    // 与实体交互
    public static ActionResult onUseEntity(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowEntityInteraction) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    public static void onPreLogin(ServerLoginNetworkHandler netHandler, MinecraftServer server, PacketSender packetSender, ServerLoginNetworking.LoginSynchronizer sync) {
        if (extendedConfig.forcedOfflineUuid && netHandler.profile != null) {
            netHandler.profile = Uuids.getOfflinePlayerProfile(netHandler.profile.getName());
        }
    }

}
