package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * VMD 모션 상태 오버레이
 * - K 키로 열림
 * - VMD 로드/재생은 키 핸들러(L 키 등)에서 처리
 * - 여기서는 현재 모션/진행도만 표시
 */
public class VMDMotionController extends Screen {
    private static final Logger logger = LogManager.getLogger();

    private static final int PANEL_COLOR = 0xF01D1F24;
    private static final int BORDER_COLOR = 0xFF454545;
    private static final int TITLE_COLOR = 0xFFFFD770;
    private static final int TXT_MAIN    = 0xFFE0E0E0;
    private static final int TXT_DIM     = 0xFF909090;

    private final Screen parent;
    private final URDFModelOpenGLWithSTL renderer;
    private final MotionEditorScreen.VMDPlayer player = MotionEditorScreen.VMDPlayer.getInstance();

    // VMD 파일 개수만 간단히 보여주기 위해 유지
    private final List<File> vmdFiles = new ArrayList<>();

    public VMDMotionController(Screen parent, URDFModelOpenGLWithSTL renderer) {
        super(Component.literal("VMD Motion Controller"));
        this.parent   = parent;
        this.renderer = renderer;
        scanVmdFiles();
    }

    private void scanVmdFiles() {
        vmdFiles.clear();
        File gameDir = Minecraft.getInstance().gameDirectory;

        File kaiDir = new File(gameDir, "KAIMyEntity");
        if (!kaiDir.exists()) {
            kaiDir.mkdirs();
        }

        File[] files = kaiDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".vmd"));
        if (files != null) {
            for (File f : files) {
                vmdFiles.add(f);
            }
        }

        logger.info("Found {} VMD files in KAIMyEntity/", vmdFiles.size());
    }

    @Override
    protected void init() {
        super.init();
        // 버튼/위젯 없음
    }

    @Override
    public void tick() {
        super.tick();
        // GUI가 열려 있는 동안에도 모션 업데이트
        if (renderer != null) {
            MotionEditorScreen.tick(renderer);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        // 배경을 덮지 않는다 → 월드 그대로 보이고, 왼쪽 아래에 패널만 띄움
        int panelW = 260;
        int panelH = 120;
        int x = 10;
        int y = this.height - panelH - 10;

        // 패널
        g.fill(x, y, x + panelW, y + panelH, PANEL_COLOR);
        drawBorder(g, x, y, panelW, panelH);

        // 제목
        g.drawString(this.font, "§lVMD Motion", x + 8, y + 6, TITLE_COLOR);

        // 상태 텍스트
        var status = player.getStatus();
        List<String> lines = new ArrayList<>();

        lines.add("§7VMD files: " + vmdFiles.size());
        lines.add("");

        if (status.motionName() != null) {
            lines.add("§bMotion: " + status.motionName());
            lines.add("§7Keyframes: " + status.keyframeCount());
            lines.add("§7Duration : " + String.format("%.1fs", status.duration()));
            lines.add("");

            if (status.playing()) {
                lines.add("§a▶ PLAYING");
                lines.add(String.format("§7Time: %.2f / %.2fs",
                        status.currentTime(), status.duration()));

                float progress = status.duration() > 0
                        ? status.currentTime() / status.duration()
                        : 0f;
                lines.add("§7[" + makeProgressBar(progress, 22) + "§7]");
            } else {
                lines.add("§7■ STOPPED");
            }
        } else {
            lines.add("§cNo motion loaded");
            lines.add("§7L 키로 VMD를 로드/재생합니다.");
        }

        lines.add("");
        lines.add("§8ESC: 닫기");

        int ty = y + 20;
        for (String line : lines) {
            g.drawString(this.font, line, x + 8, ty, TXT_MAIN, false);
            ty += 11;
        }
    }

    private void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, BORDER_COLOR);
        g.fill(x, y + h - 1, x + w, y + h, BORDER_COLOR);
        g.fill(x, y, x + 1, y + h, BORDER_COLOR);
        g.fill(x + w - 1, y, x + w, y + h, BORDER_COLOR);
    }

    private String makeProgressBar(float progress, int width) {
        int filled = (int) (progress * width);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? "§a█" : "§8░");
        }
        return sb.toString();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        // RLControl 과 마찬가지로 게임을 멈추지 않음
        return false;
    }
}
