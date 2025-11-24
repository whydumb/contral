package com.kAIS.KAIMyEntity.neoforge.register;

import com.kAIS.KAIMyEntity.neoforge.network.KAIMyEntityNetworkPack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class KAIMyEntityRegisterCommon {
    static String networkVersion = "1";
    public static void Register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("kaimyentity");
        registrar.versioned(networkVersion).optional().playBidirectional(
            KAIMyEntityNetworkPack.TYPE, 
            KAIMyEntityNetworkPack.STREAM_CODEC, 
            new DirectionalPayloadHandler<>(KAIMyEntityNetworkPack::DoInClient, KAIMyEntityNetworkPack::DoInServer)
            );
    }
}
