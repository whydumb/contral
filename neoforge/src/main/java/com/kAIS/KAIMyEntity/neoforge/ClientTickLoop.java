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
 * 깔끔하게 정리된 클라이언트 틱 루프
 * - URDF 모델 tickUpdate (20Hz)
 * - Webots 연동 (URDF → Webots 전송)
 * - VMC, 모션 에디터 제거
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
    private static final int STATS_INTERVAL = 100; // 5초마다 (100 ticks)

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        float dt = 1.0f / 20.0f;

        // URDF 모델 업데이트
        if (renderer != null) {
            renderer.tickUpdate(dt);
            sendToWebots(renderer);
        }

        for (URDFModelOpenGLWithSTL r : renderers) {
            r.tickUpdate(dt);
            sendToWebots(r);
        }

        // 통계 출력 (5초마다)
        if (++tickCount >= STATS_INTERVAL) {
            tickCount = 0;
            printWebotsStats();
        }
    }

    /**
     * URDF의 모든 가동 관절을 Webots로 전송
     */
    private static void sendToWebots(URDFModelOpenGLWithSTL renderer) {
        try {
            WebotsController wc = getWebots();
            if (wc == null || !wc.isConnected()) return;

            var robot = renderer.getRobotModel();
            if (robot == null || robot.joints == null) return;

            // 모든 가동 관절 전송
            for (var joint : robot.joints) {
                if (joint.isMovable()) {
                    wc.setJoint(joint.name, joint.currentPosition);
                }
            }

        } catch (Exception e) {
            // 에러 무시 (Webots 없어도 정상 동작)
        }
    }

    /**
     * Webots 컨트롤러 지연 초기화
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

    /**
     * Webots 통계 출력
     */
    private static void printWebotsStats() {
        WebotsController wc = getWebots();
        if (wc != null && wc.isConnected()) {
            wc.printStats();
        }
    }

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
                // 실패 시 무시
            }
        }
    }

    /**
     * Webots 연결 상태 확인
     */
    public static boolean isWebotsConnected() {
        WebotsController wc = getWebots();
        return wc != null && wc.isConnected();
    }

    /**
     * Webots 주소 조회
     */
    public static String getWebotsAddress() {
        WebotsController wc = getWebots();
        return wc != null ? wc.getRobotAddress() : "Not initialized";
    }
}