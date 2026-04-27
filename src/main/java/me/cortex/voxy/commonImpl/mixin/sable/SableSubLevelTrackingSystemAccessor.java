package me.cortex.voxy.commonImpl.mixin.sable;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem")
public interface SableSubLevelTrackingSystemAccessor {
    @Invoker(value = "shouldLoad", remap = false)
    boolean voxy$invokeShouldLoad(Player player, Vector3dc entityPosition);

    @Invoker(value = "sendFullSync", remap = false)
    void voxy$invokeSendFullSync(ServerPlayer player, ServerSubLevel subLevel, CustomPacketPayload payload);
}
