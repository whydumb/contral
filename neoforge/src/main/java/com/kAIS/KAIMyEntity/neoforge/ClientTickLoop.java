package com.kAIS.KAIMyEntity.neoforge;

import com.kAIS.KAIMyEntity.webots.WebotsController;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 통합 ClientTickLoop
 * - RobotListener 모드: WASD + 마우스 → RobotListener
 */
@EventBusSubscriber(
        modid = "kaimyentity",
        value = Dist.CLIENT
)
public final class ClientTickLoop {
    
    private static WebotsController webots;
    private static boolean webotsInitialized = false;
    
    private static int tickCount = 0;
    private static final int STATS_INTERVAL = 100; // 5초마다

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // RobotListener 모드: WASD + 마우스
        WebotsController wc = getWebots();
        if (wc != null && wc.getMode() == WebotsController.Mode.ROBOTLISTENER) {
            wc.tick();
        }
        
        // 통계 출력 (5초마다)
        if (++tickCount >= STATS_INTERVAL) {
            tickCount = 0;
            if (wc != null && wc.isConnected()) {
                wc.printStats();
            }
        }
    }
    
    /**
     * WebotsController (지연 초기화)
     */
    private static WebotsController getWebots() {
        if (!webotsInitialized) {
            webotsInitialized = true;
            try {
                webots = WebotsController.getInstance();
            } catch (Exception e) {
                webots = null;
            }
        }
        return webots;
    }
    
    // ==================== 외부 제어 API ====================
    
    public static void reconnectWebots(String ip, int port) {
        WebotsController wc = getWebots();
        if (wc != null) {
            wc.reconnect(ip, port);
        } else {
            try {
                webots = WebotsController.getInstance(ip, port);
                webotsInitialized = true;
            } catch (Exception e) {
                // 실패 무시
            }
        }
    }
    
    public static void enableRobotListener(boolean enable) {
        WebotsController wc = getWebots();
        if (wc != null) {
            wc.enableRobotListener(enable);
        }
    }
    
    public static WebotsController.Mode getMode() {
        WebotsController wc = getWebots();
        return wc != null ? wc.getMode() : WebotsController.Mode.WEBOTS;
    }
    
    public static boolean isWebotsConnected() {
        WebotsController wc = getWebots();
        return wc != null && wc.isConnected();
    }
    
    public static boolean isRobotListenerEnabled() {
        WebotsController wc = getWebots();
        return wc != null && wc.isRobotListenerEnabled();
    }
    
    public static String getWebotsAddress() {
        WebotsController wc = getWebots();
        return wc != null ? wc.getRobotAddress() : "Not initialized";
    }
}
