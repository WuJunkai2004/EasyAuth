package xyz.nikitacartes.easyauth.mixin;

import com.google.common.net.InetAddresses;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.nikitacartes.easyauth.event.AuthEventHandler;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.utils.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static xyz.nikitacartes.easyauth.EasyAuth.*;
import static xyz.nikitacartes.easyauth.utils.EasyLogger.LogDebug;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin implements PlayerAuth {
    @Unique
    private final ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

    @Final
    @Shadow
    public MinecraftServer server;

    @Unique
    private long kickTimer = config.kickTimeout * 20;

    @Unique
    private String ipAddress = null;

    @Unique
    private LastLocation lastLocation = null;

    @Unique
    private UUID ridingEntityUUID = null;

    @Unique
    private NbtCompound rootVehicle = null;

    @Unique
    private boolean wasDead = false;

    @Unique
    PlayerEntryV1 playerEntryV1 = new PlayerEntryV1(player.getNameForScoreboard());

    @Unique
    private boolean canSkipAuth = false;

    @Unique
    private boolean isAuthenticated = false;

    @Unique
    private boolean isUsingMojangAccount = false;

    @Unique
    // Needed for mounting player to vehicle while they're leaving the server
    private boolean leavingServer = false;

    /**
     * 保存玩家的真实位置，包括坐标、视角和骑乘状态。
     */
    @Override
    public void easyAuth$saveTrueLocation() {
        if (lastLocation == null) {
            lastLocation = new LastLocation();
        }
        lastLocation.position = player.getPos();
        lastLocation.yaw = player.getYaw();
        lastLocation.pitch = player.getPitch();

        // 保存玩家当前骑乘的实体UUID
        ridingEntityUUID = player.getVehicle() != null ? player.getVehicle().getUuid() : null;
        wasDead = player.isDead();
        LogDebug(String.format("保存玩家 %s 的位置为 %s", player.getNameForScoreboard(), lastLocation));
        if (ridingEntityUUID != null) {
            LogDebug(String.format("保存玩家 %s 的骑乘实体为 %s", player.getNameForScoreboard(), ridingEntityUUID));
        }
    }

    /**
     * 保存玩家所在的维度。
     *
     * @param registryKey 玩家所在维度的注册键
     */
    @Override
    public void easyAuth$saveTrueDimension(RegistryKey<World> registryKey) {
        if (lastLocation == null) {
            lastLocation = new LastLocation();
        }
        lastLocation.dimension = this.server.getWorld(registryKey);
    }

    /**
     * 恢复玩家的真实位置，包括坐标、视角和骑乘状态。
     */
    @Override
    public void easyAuth$restoreTrueLocation() {
        if (lastLocation == null) {
            return;
        }
        if (wasDead) {
            // 如果玩家死亡，直接杀死玩家并减少死亡计数
            player.kill(player.getServerWorld());
            player.getScoreboard().forEachScore(ScoreboardCriterion.DEATH_COUNT, player, (score) -> score.setScore(score.getScore() - 1));
            return;
        }
        // 将玩家传送到上次保存的位置
        player.teleport(
                lastLocation.dimension == null ? server.getWorld(World.OVERWORLD) : lastLocation.dimension,
                lastLocation.position.getX(),
                lastLocation.position.getY(),
                lastLocation.position.getZ(),
                EnumSet.noneOf(PositionFlag.class),
                lastLocation.yaw,
                lastLocation.pitch,
                true);
        LogDebug(String.format("将玩家 %s 传送到 %s", player.getNameForScoreboard(), lastLocation));

        // 恢复玩家的骑乘状态
        if (rootVehicle != null) {
            LogDebug(String.format("将玩家挂载到载具 %s", rootVehicle));
            leavingServer = true;
            player.readRootVehicle(Optional.of(rootVehicle));
            leavingServer = false;
        }

        if (player.getVehicle() == null && ridingEntityUUID != null) {
            LogDebug(String.format("将玩家挂载到载具 %s", ridingEntityUUID));
            if (lastLocation.dimension == null) return;
            ServerWorld world = server.getWorld(lastLocation.dimension.getRegistryKey());
            if (world == null) return;
            Entity entity = world.getEntity(ridingEntityUUID);
            if (entity != null) {
                player.startRiding(entity, true);
            } else {
                LogDebug("未找到玩家 " + player.getNameForScoreboard() + " 的载具");
            }
        }
    }

    /**
     * 向玩家发送登录或注册提示信息。
     *
     * @return 包含适当字符串（登录或注册）的文本
     */
    @Override
    public void easyAuth$sendAuthMessage() {
        if ((!config.enableGlobalPassword || config.singleUseGlobalPassword) && (playerEntryV1 == null || playerEntryV1.password.isEmpty())) {
            if (config.singleUseGlobalPassword) {
                langConfig.registerRequiredWithGlobalPassword.send(player);
            } else {
                langConfig.registerRequired.send(player);
            }
        } else {
            langConfig.loginRequired.send(player);
        }
    }

    /**
     * 检查玩家是否可以跳过认证流程。
     *
     * @return 如果玩家可以跳过认证流程，则返回 true，否则返回 false
     */
    @Override
    public boolean easyAuth$canSkipAuth() {
        canSkipAuth = true;
        return canSkipAuth;
    }

    /**
     * 设置玩家可以跳过认证流程的状态。
     */
    @Override
    public void easyAuth$setSkipAuth() {
        easyAuth$setUsingMojangAccount();
        
    }

    /**
     * 检查玩家是否使用 Mojang 账户。
     *
     * @return 如果玩家使用 Mojang 账户，则返回 true，否则返回 false
     */
    @Override
    public boolean easyAuth$isUsingMojangAccount() {
        return isUsingMojangAccount;
    }

    /**
     * 设置玩家的账户类型为 Mojang 账户。
     */
    @Override
    public void easyAuth$setUsingMojangAccount() {
        isUsingMojangAccount = server.isOnlineMode() && playerEntryV1.onlineAccount == PlayerEntryV1.OnlineAccount.TRUE;
    }

    /**
     * 检查玩家是否已通过认证。
     *
     * @return 如果玩家已通过认证，则返回 true，否则返回 false
     */
    @Override
    public boolean easyAuth$isAuthenticated() {
        return isAuthenticated;
    }

    /**
     * 设置玩家的认证状态。
     *
     * @param authenticated 玩家是否通过认证
     */
    @Override
    public void easyAuth$setAuthenticated(boolean authenticated) {
        isAuthenticated = authenticated;

        // 设置玩家的无敌和隐身状态
        player.setInvulnerable(!authenticated && extendedConfig.playerInvulnerable);
        player.setInvisible(!authenticated && extendedConfig.playerIgnored);

        if (authenticated) {
            kickTimer = config.kickTimeout * 20;
            // 如果玩家通过认证，更新必要的区块和界面状态
            World world = player.getEntityWorld();
            BlockPos pos = player.getBlockPos();

            // 更新传送门区块
            world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
            world.updateListeners(pos.up(), world.getBlockState(pos.up()), world.getBlockState(pos.up()), 3);

            player.currentScreenHandler.syncState();
        }
    }

    /**
     * 在玩家每个 tick 调用时检查认证状态。
     */
    /**
    @Inject(method = "playerTick()V", at = @At("HEAD"), cancellable = true)
    private void playerTick(CallbackInfo ci) {
        if (!this.easyAuth$isAuthenticated()) {
            // 检查玩家的踢出计时器
            if (kickTimer <= 0 && player.networkHandler.isConnectionOpen()) {
                player.networkHandler.disconnect(langConfig.timeExpired.get());
            } else {
                // 每隔一定时间发送认证提示
                if (kickTimer % (extendedConfig.authenticationPromptInterval * 20) == 0) {
                    this.easyAuth$sendAuthMessage();
                }
                --kickTimer;
            }
            ci.cancel();
        }
    } */

    // Player item dropping
    @Inject(method = "dropSelectedItem(Z)Z", at = @At("HEAD"), cancellable = true)
    private void dropSelectedItem(boolean dropEntireStack, CallbackInfoReturnable<Boolean> cir) {
        ActionResult result = AuthEventHandler.onDropItem(player);

        if (result == ActionResult.FAIL) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(method = "readRootVehicle(Ljava/util/Optional;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;startRiding(Lnet/minecraft/entity/Entity;Z)Z"))
    private boolean onPlayerConnectStartRiding(ServerPlayerEntity instance, Entity entity, boolean force) {
        if (!leavingServer && config.hidePlayerCoords && !((PlayerAuth) instance).easyAuth$isAuthenticated()) {
            return false;
        }
        return instance.startRiding(entity, force);
    }

    @Redirect(method = "readRootVehicle(Ljava/util/Optional;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;hasVehicle()Z"))
    private boolean onPlayerConnectStartRiding(ServerPlayerEntity instance) {
        if (!leavingServer && config.hidePlayerCoords && !((PlayerAuth) instance).easyAuth$isAuthenticated()) {
            return true;
        }
        return instance.hasVehicle();
    }

    @Inject(method = "copyFrom(Lnet/minecraft/server/network/ServerPlayerEntity;Z)V", at = @At("RETURN"))
    private void copyFrom(ServerPlayerEntity oldPlayer, boolean alive, CallbackInfo ci) {
        PlayerAuth oldPlayerAuth = (PlayerAuth) oldPlayer;
        PlayerAuth newPlayerAuth = (PlayerAuth) player;
        newPlayerAuth.easyAuth$setKickTimer(oldPlayerAuth.easyAuth$getKickTimer());
        newPlayerAuth.easyAuth$setIpAddress(oldPlayerAuth.easyAuth$getIpAddress());
        newPlayerAuth.easyAuth$setLastLocation(oldPlayerAuth.easyAuth$getLastLocation());
        newPlayerAuth.easyAuth$setRidingEntityUUID(oldPlayerAuth.easyAuth$getRidingEntityUUID());
        newPlayerAuth.easyAuth$setRootVehicle(oldPlayerAuth.easyAuth$getRootVehicle());
        newPlayerAuth.easyAuth$wasDead(oldPlayerAuth.easyAuth$wasDead());
        newPlayerAuth.easyAuth$canSkipAuth(oldPlayerAuth.easyAuth$canSkipAuth());
        newPlayerAuth.easyAuth$setAuthenticated(oldPlayerAuth.easyAuth$isAuthenticated());

        newPlayerAuth.easyAuth$setPlayerEntryV1(oldPlayerAuth.easyAuth$getPlayerEntryV1());
    }

    public long easyAuth$getKickTimer() {
        return kickTimer;
    }

    public void easyAuth$setKickTimer(long kickTimer) {
        this.kickTimer = kickTimer;
    }

    public void easyAuth$setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public LastLocation easyAuth$getLastLocation() {
        return lastLocation;
    }

    public void easyAuth$setLastLocation(LastLocation lastLocation) {
        this.lastLocation = lastLocation;
    }

    public UUID easyAuth$getRidingEntityUUID() {
        return ridingEntityUUID;
    }

    public void easyAuth$setRidingEntityUUID(UUID ridingEntityUUID) {
        this.ridingEntityUUID = ridingEntityUUID;
    }

    public NbtCompound easyAuth$getRootVehicle() {
        return rootVehicle;
    }

    public void easyAuth$setRootVehicle(NbtCompound rootVehicle) {
        this.rootVehicle = rootVehicle;
    }

    public boolean easyAuth$wasDead() {
        return wasDead;
    }

    public void easyAuth$wasDead(boolean wasDead) {
        this.wasDead = wasDead;
    }

    public void easyAuth$canSkipAuth(boolean cantSkipAuth) {
        this.canSkipAuth = cantSkipAuth;
    }

    public String easyAuth$getIpAddress() {
        return ipAddress;
    }

    public void easyAuth$setIpAddress(ClientConnection connection) {
        SocketAddress socketAddress = connection.getAddress();
        ipAddress = socketAddress instanceof InetSocketAddress inetSocketAddress ? InetAddresses.toAddrString(inetSocketAddress.getAddress()) : "<unknown>";
    }

    public PlayerEntryV1 easyAuth$getPlayerEntryV1() {
        return playerEntryV1;
    }

    public void easyAuth$setPlayerEntryV1(PlayerEntryV1 playerEntryV1) {
        this.playerEntryV1 = playerEntryV1;
    }

}

