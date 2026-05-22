package pl.skidam.automodpack.mixin.core;

import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.networking.server.ServerLoginNetworkAddon;

@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 300)
public abstract class ServerLoginNetworkHandlerMixin {

    /*
     * State enum per target version:
     *   1.18.2 - 1.20.1: HELLO, KEY, AUTHENTICATING, NEGOTIATING,
     *                     READY_TO_ACCEPT, DELAY_ACCEPT, ACCEPTED
     *   1.21.1+:         HELLO, KEY, AUTHENTICATING, NEGOTIATING,
     *                     VERIFYING, WAITING_FOR_DUPE_DISCONNECT,
     *                     PROTOCOL_SWITCHING, ACCEPTED
     *
     * tick() per state (all versions):
     *   HELLO, KEY, AUTHENTICATING:  no-op (just timeout counter)
     *   NEGOTIATING:                  no-op (just timeout counter)
     *   READY_TO_ACCEPT (≤ 1.20.1):  calls handleAcceptedLogin()
     *                                   — login finalization + compression setup
     *   VERIFYING     (≥ 1.21.1):    calls verifyLoginAndFinishConnectionSetup()
     *                                   — login finalization + compression setup
     *   DELAY_ACCEPT  (≤ 1.20.1):    duplicate player check
     *   WAITING_FOR_DUPE_DISCONNECT: duplicate player check
     */
    @Shadow
    private ServerLoginPacketListenerImpl.State state;

    @Unique
    private ServerLoginNetworkAddon automodpack$addon;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void initAddon(CallbackInfo ci) {
        this.automodpack$addon = new ServerLoginNetworkAddon((ServerLoginPacketListenerImpl) (Object) this);
    }

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void handleCustomPayload(ServerboundCustomQueryAnswerPacket packet, CallbackInfo ci) {
        if (this.automodpack$addon == null) {
            return;
        }

        if (this.automodpack$addon.handle(packet)) {
            ci.cancel();
        }
    }

    /*
     * We intercept tick() to hold the login in progress while our query
     * exchange completes. Both NEGOTIATING and READY_TO_ACCEPT/VERIFYING
     * are checked, but for different reasons:
     *
     *   NEGOTIATING — START queries.  tick() is a no-op here, so we use
     *   this state to call queryTick() which sends the initial handshake
     *   query.  Cancel is harmless (tick does nothing critical).
     *
     *   READY_TO_ACCEPT / VERIFYING — PREVENT finalization.  In this state
     *   tick() calls the login finaliser (handleAcceptedLogin /
     *   verifyLoginAndFinishConnectionSetup) which sets up compression and
     *   places the player.  We MUST cancel to delay this step until queries
     *   are finished.
     *
     *
     * == Why not just NEGOTIATING? ==
     *
     * NEGOTIATING is not always entered.  On servers with no proxy (the
     * common case), handleLoginHello / handleAuthentication sets state
     * directly to READY_TO_ACCEPT/VERIFYING, bypassing NEGOTIATING
     * entirely.  If we only checked NEGOTIATING, those connections would
     * never start queries, tick() would finalise immediately, and the
     * late query response would hit a compression- or protocol-swapped
     * pipeline ("Pipeline has no outbound protocol configured").
     *
     * We check NEGOTIATING too as an optimization: when the state DOES
     * pass through NEGOTIATING (online mode, proxy-enabled servers),
     * we kick off queries a few ticks earlier instead of waiting for
     * READY_TO_ACCEPT/VERIFYING.
     *
     *
     * HELLO / KEY / AUTHENTICATING are not intercepted because they run
     * before the connection is ready for custom queries, and tick() does
     * non-trivial work in those states.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sendOurPackets(CallbackInfo ci) {
        if (this.automodpack$addon == null) {
            return;
        }

        /*? if <= 1.20.1 {*/
        /*if (this.state != ServerLoginPacketListenerImpl.State.NEGOTIATING && this.state != ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT) {
            return;
        }
        *//*?} else {*/
        if (this.state != ServerLoginPacketListenerImpl.State.NEGOTIATING && this.state != ServerLoginPacketListenerImpl.State.VERIFYING) {
            return;
        }
        /*?}*/

        if (!this.automodpack$addon.queryTick()) {
            ci.cancel();
            return;
        }

        this.automodpack$addon = null;
    }
}
