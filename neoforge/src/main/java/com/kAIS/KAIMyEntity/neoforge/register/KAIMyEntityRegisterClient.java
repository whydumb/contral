package com.kAIS.KAIMyEntity.neoforge.register;

import com.kAIS.KAIMyEntity.renderer.KAIMyEntityRendererPlayerHelper;
import com.kAIS.KAIMyEntity.renderer.MMDModelManager;
import com.kAIS.KAIMyEntity.neoforge.ClientTickLoop;
import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import com.kAIS.KAIMyEntity.webots.WebotsController;
import com.kAIS.KAIMyEntity.webots.WebotsConfigScreen;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.Objects;

/**
 * 깔끔하게 정리된 키 등록 & 처리
 * - T: Webots 통계 출력
 * - Y: Webots T-Pose 테스트
 * - U: Webots 설정 GUI
 * - Ctrl+R: URDF 리로드
 * - VMC, 모션 에디터 키 제거
 */
@EventBusSubscriber(value = Dist.CLIENT)
public class KAIMyEntityRegisterClient {
    static final Logger logger = LogManager.getLogger();

    // 키맵 (깔끔하게 정리)
    static KeyMapping keyWebotsStats  = new KeyMapping("key.webotsStats",  KeyConflictContext.IN_GAME, KeyModifier.NONE, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_T, "key.title");
    static KeyMapping keyWebotsTest   = new KeyMapping("key.webotsTest",   KeyConflictContext.IN_GAME, KeyModifier.NONE, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, "key.title");
    static KeyMapping keyWebotsConfig = new KeyMapping("key.webotsConfig", KeyConflictContext.IN_GAME, KeyModifier.NONE, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.title");
    static KeyMapping keyReloadURDF   = new KeyMapping("key.reloadURDF",   KeyConflictContext.IN_GAME, KeyModifier.CONTROL, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.title");

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent e) {
        e.register(keyWebotsStats);
        e.register(keyWebotsTest);
        e.register(keyWebotsConfig);
        e.register(keyReloadURDF);
        logger.info("KAIMyEntityRegisterClient: key mappings registered (clean version).");
    }

    public static void Register() {
        logger.info("KAIMyEntityRegisterClient.Register() called.");
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void onKeyPressed(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        // T: Webots 통계 출력
        if (keyWebotsStats.consumeClick()) {
            try {
                WebotsController.getInstance().printStats();
                mc.gui.getChat().addMessage(Component.literal("§a[Webots] Stats printed to console"));
            } catch (Exception e) {
                mc.gui.getChat().addMessage(Component.literal("§c[Webots] Error: " + e.getMessage()));
            }
        }

        // Y: Webots T-Pose 테스트
        if (keyWebotsTest.consumeClick()) {
            testWebotsConnection(mc);
        }

        // U: Webots 설정 GUI
        if (keyWebotsConfig.consumeClick()) {
            mc.setScreen(new WebotsConfigScreen(mc.screen));
        }

        // Ctrl+R: URDF 리로드
        if (keyReloadURDF.consumeClick()) {
            try {
                MMDModelManager.ReloadModel();
                mc.gui.getChat().addMessage(Component.literal("§a[URDF] Models reloaded"));
                ensureActiveRenderer(mc);
            } catch (Throwable t) {
                mc.gui.getChat().addMessage(Component.literal("§c[URDF] Reload failed: " + t.getMessage()));
            }
        }
    }

    /**
     * Webots T-Pose 테스트
     */
    private static void testWebotsConnection(Minecraft mc) {
        try {
            var webots = WebotsController.getInstance();

            // T-Pose 자세 전송
            webots.setJoint("r_sho_pitch", 0.3f);
            webots.setJoint("r_sho_roll", 1.57f);
            webots.setJoint("r_el", -0.1f);

            webots.setJoint("l_sho_pitch", 0.3f);
            webots.setJoint("l_sho_roll", -1.57f);
            webots.setJoint("l_el", -0.1f);

            mc.gui.getChat().addMessage(Component.literal("§a[Webots] T-Pose sent! Check Webots simulation."));

        } catch (Exception e) {
            mc.gui.getChat().addMessage(Component.literal("§c[Webots] Connection failed: " + e.getMessage()));
            logger.error("Webots test failed", e);
        }
    }

    /**
     * 렌더러 자동 로드 (URDF 파일 찾기)
     */
    private static void ensureActiveRenderer(Minecraft mc) {
        if (ClientTickLoop.renderer != null) return;

        File gameDir = mc.gameDirectory;
        File urdf = findFirstUrdf(gameDir, 2);

        if (urdf == null) {
            mc.gui.getChat().addMessage(Component.literal("§c[URDF] No *.urdf found"));
            return;
        }

        File modelDir = guessModelDir(urdf);
        if (modelDir == null || !modelDir.isDirectory()) {
            mc.gui.getChat().addMessage(Component.literal("§c[URDF] Invalid model dir: " + modelDir));
            return;
        }

        URDFModelOpenGLWithSTL r = URDFModelOpenGLWithSTL.Create(
                urdf.getAbsolutePath(),
                modelDir.getAbsolutePath()
        );

        if (r == null) {
            mc.gui.getChat().addMessage(Component.literal("§c[URDF] Parse failed: " + urdf.getName()));
            return;
        }

        ClientTickLoop.renderer = r;
        mc.gui.getChat().addMessage(Component.literal("§a[URDF] Loaded: " + urdf.getName()));
    }

    /**
     * URDF 파일 찾기 (재귀)
     */
    private static File findFirstUrdf(File root, int maxDepth) {
        if (root == null || !root.exists() || maxDepth < 0) return null;
        File[] list = root.listFiles();
        if (list == null) return null;

        // 파일 먼저 검색
        for (File f : list) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".urdf")) {
                return f;
            }
        }

        // 디렉토리 재귀
        for (File f : list) {
            if (f.isDirectory()) {
                File r = findFirstUrdf(f, maxDepth - 1);
                if (r != null) return r;
            }
        }

        return null;
    }

    /**
     * 모델 디렉토리 추측
     */
    private static File guessModelDir(File urdfFile) {
        File parent = urdfFile.getParentFile();
        if (parent == null) return null;

        File meshes = new File(parent, "meshes");
        if (meshes.exists() && meshes.isDirectory()) {
            return meshes;
        }

        return parent;
    }
}