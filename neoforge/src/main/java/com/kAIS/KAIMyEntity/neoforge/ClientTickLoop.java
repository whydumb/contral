// neoforge/src/main/java/com/kAIS/KAIMyEntity/neoforge/ClientTickLoop.java
package com.kAIS.KAIMyEntity.neoforge;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import com.kAIS.KAIMyEntity.webots.WebotsController;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 통합 ClientTickLoop
 * - URDF 모델 tickUpdate (20Hz)
 * - Webots 모드: URDF → Webots
 * - RobotListener 모드: WASD + 마우스 → RobotListener
 */
@EventBusSubscriber(
        modid = "kaimyentity",
        value = Dist.CLIENT
)
public final class ClientTickLoop {

    public static URDFModelOpenGLWithSTL renderer;
    public static final List<URDFModelOpenGLWithSTL> renderers = new ArrayList<>();
    
    private static WebotsController webots;
    private static boolean webotsInitialized = false;
    
    private static int tickCount = 0;
    private static final int STATS_INTERVAL = 100; // 5초마다

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        float dt = 1.0f / 20.0f;

        // 1) URDF 모델 업데이트
        if (renderer != null) {
            renderer.tickUpdate(dt);
            
            // Webots 모드일 때만 관절 전송
            WebotsController wc = getWebots();
            if (wc != null && wc.getMode() == WebotsController.Mode.WEBOTS) {
                sendToWebots(renderer, wc);
            }
        }
        
        for (URDFModelOpenGLWithSTL r : renderers) {
            r.tickUpdate(dt);
            
            WebotsController wc = getWebots();
            if (wc != null && wc.getMode() == WebotsController.Mode.WEBOTS) {
                sendToWebots(r, wc);
            }
        }
        
        // 2) RobotListener 모드: WASD + 마우스
        WebotsController wc = getWebots();
        if (wc != null && wc.getMode() == WebotsController.Mode.ROBOTLISTENER) {
            wc.tick();
        }
        
        // 3) 통계 출력 (5초마다)
        if (++tickCount >= STATS_INTERVAL) {
            tickCount = 0;
            if (wc != null && wc.isConnected()) {
                wc.printStats();
            }
        }
    }
    
    /**
     * URDF → Webots 전송 (Webots 모드)
     */
    private static void sendToWebots(URDFModelOpenGLWithSTL renderer, WebotsController wc) {
        if (!wc.isConnected()) return;
        
        try {
            var robot = renderer.getRobotModel();
            if (robot == null || robot.joints == null) return;
            
            for (var joint : robot.joints) {
                if (joint.isMovable()) {
                    wc.setJoint(joint.name, joint.currentPosition);
                }
            }
        } catch (Exception e) {
            // 에러 무시
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
    
    /**
     * Webots 재연결
     */
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
    
    /**
     * RobotListener 모드 활성화/비활성화
     */
    public static void enableRobotListener(boolean enable) {
        WebotsController wc = getWebots();
        if (wc != null) {
            wc.enableRobotListener(enable);
        }
    }
    
    /**
     * 현재 모드 가져오기
     */
    public static WebotsController.Mode getMode() {
        WebotsController wc = getWebots();
        return wc != null ? wc.getMode() : WebotsController.Mode.WEBOTS;
    }
    
    /**
     * Webots 연결 상태
     */
    public static boolean isWebotsConnected() {
        WebotsController wc = getWebots();
        return wc != null && wc.isConnected();
    }
    
    /**
     * RobotListener 활성화 상태
     */
    public static boolean isRobotListenerEnabled() {
        WebotsController wc = getWebots();
        return wc != null && wc.isRobotListenerEnabled();
    }
    
    /**
     * Webots 주소
     */
    public static String getWebotsAddress() {
        WebotsController wc = getWebots();
        return wc != null ? wc.getRobotAddress() : "Not initialized";
    }
}
