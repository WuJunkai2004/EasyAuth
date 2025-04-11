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
 * 此类负责处理玩家尝试的操作，
 * 如果玩家未通过身份验证，则取消这些操作。
 */
public class AuthEventHandler {

    public static long lastAcceptedPacket = 0;

    public static Pattern usernamePattern;

    /**
     * 玩家预加入检查。
     * 返回断开连接的原因文本或 null 以允许加入。
     *
     * @param profile 玩家游戏档案
     * @param manager 玩家管理器
     * @return 如果玩家应被断开连接，则返回文本
     */
    public static Text checkCanPlayerJoinServer(GameProfile profile, PlayerManager manager, SocketAddress socketAddress) {
        // 获取玩家。在此时，玩家的游戏档案已通过身份验证
        String incomingPlayerUsername = profile.getName();
        PlayerEntity onlinePlayer = manager.getPlayer(incomingPlayerUsername);

        // 如果没有 uuid, 分配一个
        if(onlinePlayer.uuid == null) {
            onlinePlayer.uuid = Uuids.getOfflinePlayerProfile(profile.getName());
        }

        // 检查是否有同名玩家在线，如果有且 IP 不同，则踢出玩家
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

        // 检查玩家用户名是否有效。模式在配置加载时生成。
        Matcher matcher = usernamePattern.matcher(incomingPlayerUsername);

        // 如果用户名不匹配正则表达式或不符合 Floodgate 规则，则拒绝加入
        if (!(matcher.matches() || (technicalConfig.floodgateLoaded && extendedConfig.floodgateBypassRegex && FloodgateApiHelper.isFloodgatePlayer(profile.getId())))) {
            return langConfig.disallowedUsername.get(extendedConfig.usernameRegexp);
        }

        // 检查用户名大小写是否一致
        PlayerEntryV1 playerEntryV1 = PlayersCache.getFloodgate(incomingPlayerUsername);
        if (!extendedConfig.allowCaseInsensitiveUsername && !playerEntryV1.username.equals(incomingPlayerUsername)) {
            return langConfig.differentUsernameCase.get(incomingPlayerUsername);
        }

        // 检查玩家是否超过最大登录尝试次数
        if (config.maxLoginTries != -1 && playerEntryV1.lastKickedDate.plusSeconds(config.resetLoginAttemptsTimeout).isAfter(ZonedDateTime.now())) {
            return langConfig.loginTriesExceeded.get();
        }

        return null;
    }

    /**
     * 加载玩家数据。
     *
     * @param player 玩家实体
     * @param connection 客户端连接
     */
    public static void loadPlayerData(ServerPlayerEntity player, ClientConnection connection) {
        PlayerAuth playerAuth = (PlayerAuth) player;

        // Create in case of Carpet player
        PlayerEntryV1 cache = PlayersCache.getCarpet(player.getNameForScoreboard());
        boolean update = false;
        if (cache.uuid == null) {
            cache.uuid = player.getUuid();
            update = true;
        }
        playerAuth.easyAuth$setPlayerEntryV1(cache);

        playerAuth.easyAuth$setIpAddress(connection);
        playerAuth.easyAuth$setSkipAuth();

        if (playerAuth.easyAuth$canSkipAuth()) {
            playerAuth.easyAuth$setAuthenticated(true);

            player.setInvulnerable(false);
            player.setInvisible(false);
            update = false;
        } else if (cache.lastIp.equals(playerAuth.easyAuth$getIpAddress()) && cache.lastAuthenticatedDate.plusSeconds(config.sessionTimeout).isAfter(ZonedDateTime.now())) {
            playerAuth.easyAuth$setAuthenticated(true);

            player.setInvulnerable(false);
            player.setInvisible(false);

            cache.lastAuthenticatedDate = ZonedDateTime.now();
            update = true;
        }

        if (update) {
            cache.update();
        }

        if (extendedConfig.skipAllAuthChecks) {
            playerAuth.easyAuth$setAuthenticated(true);
        }
    }

    /**
     * 玩家加入服务器时的处理逻辑。
     *
     * @param player 玩家实体
     */
    public static void onPlayerJoin(ServerPlayerEntity player) {
        PlayerAuth playerAuth = (PlayerAuth) player;

        if (playerAuth.easyAuth$canSkipAuth()) {
            langConfig.onlinePlayerLogin.send(player);
            return;
        } else if (playerAuth.easyAuth$isAuthenticated()) {
            langConfig.validSession.send(player);
            return;
        } else if (extendedConfig.skipAllAuthChecks) {
            return;
        }

        // 尝试将玩家从下界传送门中救出
        if (extendedConfig.tryPortalRescue) {
            BlockPos pos = player.getBlockPos();
            player.teleport(pos.getX() + 0.5, player.getY(), pos.getZ() + 0.5, false);
            if (player.getBlockStateAtPos().getBlock().equals(Blocks.NETHER_PORTAL) || player.getWorld().getBlockState(player.getBlockPos().up()).getBlock().equals(Blocks.NETHER_PORTAL)) {
                // Faking portal blocks to be air
                BlockUpdateS2CPacket feetPacket = new BlockUpdateS2CPacket(pos, Blocks.AIR.getDefaultState());
                player.networkHandler.sendPacket(feetPacket);

                BlockUpdateS2CPacket headPacket = new BlockUpdateS2CPacket(pos.up(), Blocks.AIR.getDefaultState());
                player.networkHandler.sendPacket(headPacket);
            }
        }
    }

    /**
     * 玩家离开服务器时的处理逻辑。
     *
     * @param player 玩家实体
     */
    public static void onPlayerLeave(ServerPlayerEntity player) {
        PlayerAuth playerAuth = (PlayerAuth) player;
        if (playerAuth.easyAuth$canSkipAuth())
            return;

        if (playerAuth.easyAuth$isAuthenticated()) {
            PlayerEntryV1 playerCache = playerAuth.easyAuth$getPlayerEntryV1();
            playerCache.lastAuthenticatedDate = ZonedDateTime.now();
            playerCache.update();
        } else if (config.hidePlayerCoords) {
            ((PlayerAuth) player).easyAuth$restoreTrueLocation();

            player.setInvulnerable(false);
            player.setInvisible(false);
        }
    }

    /**
     * 玩家执行命令时的处理逻辑。
     *
     * @param player 玩家实体
     * @param command 玩家输入的命令
     * @return 操作结果
     */
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

    /**
     * 玩家聊天时的处理逻辑。
     *
     * @param player 玩家实体
     * @return 操作结果
     */
    public static ActionResult onPlayerChat(ServerPlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowChat) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    /**
     * 玩家移动时的处理逻辑。
     *
     * @param player 玩家实体
     * @return 操作结果
     */
    public static ActionResult onPlayerMove(ServerPlayerEntity player) {
        // 玩家将会掉落（防止飞行踢出）
        boolean auth = ((PlayerAuth) player).easyAuth$isAuthenticated();
        // 否则，应禁用移动
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

    /**
     * 玩家使用方块时的处理逻辑。
     *
     * @param player 玩家实体
     * @return 操作结果
     */
    public static ActionResult onUseBlock(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowBlockInteraction) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    /**
     * 玩家破坏方块时的处理逻辑。
     *
     * @param player 玩家实体
     * @return 是否允许破坏
     */
    public static boolean onBreakBlock(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowBlockBreaking) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return false;
        }
        return true;
    }

    /**
     * 玩家使用物品时的处理逻辑。
     *
     * @param player 玩家实体
     * @return 操作结果
     */
    public static ActionResult onUseItem(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowItemUsing) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    /**
     * 玩家丢弃物品时的处理逻辑。
     *
     * @param player 玩家实体
     * @return 操作结果
     */
    public static ActionResult onDropItem(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowItemDropping) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    /**
     * 玩家更改物品栏时的处理逻辑。
     *
     * @param player 玩家实体
     * @return 操作结果
     */
    public static ActionResult onTakeItem(ServerPlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowItemMoving) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    /**
     * 玩家攻击实体时的处理逻辑。
     *
     * @param player 玩家实体
     * @return 操作结果
     */
    public static ActionResult onAttackEntity(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowEntityAttacking) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    /**
     * 玩家与实体交互时的处理逻辑。
     *
     * @param player 玩家实体
     * @return 操作结果
     */
    public static ActionResult onUseEntity(PlayerEntity player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowEntityInteraction) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    /**
     * 玩家预登录时的处理逻辑。
     *
     * @param netHandler 登录网络处理器
     * @param server Minecraft 服务器
     * @param packetSender 数据包发送器
     * @param sync 登录同步器
     */
    public static void onPreLogin(ServerLoginNetworkHandler netHandler, MinecraftServer server, PacketSender packetSender, ServerLoginNetworking.LoginSynchronizer sync) {
        if (extendedConfig.forcedOfflineUuid && netHandler.profile != null) {
            netHandler.profile = Uuids.getOfflinePlayerProfile(netHandler.profile.getName());
        }
    }

}
