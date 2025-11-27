package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.coordinator.MotionCoordinator;
import com.kAIS.KAIMyEntity.webots.WebotsConfigScreen;
import com.kAIS.KAIMyEntity.webots.WebotsController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class VMCListenerController extends Screen {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int BG_COLOR = 0xB0000000;
    private static final int PANEL_COLOR = 0xFF14161C;
    private static final int TITLE_COLOR = 0xFFFFEA70;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int OK_COLOR = 0xFF55FF55;
    private static final int WARN_COLOR = 0xFFFF5555;
    private static final int LOCK_COLOR = 0xFFFFAA00;  // 오렌지 - Lock 관련
    private static final Component TITLE = Component.literal("RobotListener Control");

    private final Screen parent;

    private EditBox ipField;
    private EditBox portField;
    private Button connectButton;
    private Button toggleControlButton;
    private Button forceReleaseButton;
    private Button closeButton;
    
    // 팔/머리 제어 버튼
    private Button centerHeadButton;
    private Button tPoseButton;
    private Button guardPoseButton;
    private Button waveButton;
    private Button resetArmsButton;

    private WebotsController controller;
    private String statusMessage = "";
    private int statusColor = TEXT_COLOR;
    private int refreshTicker = 0;

    public VMCListenerController(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.controller = tryGetController();

        WebotsConfigScreen.Config config = WebotsConfigScreen.Config.getInstance();

        int panelWidth = 240;
        int centerX = this.width / 2;
        int startY = this.height / 2 - 50;

        this.ipField = new EditBox(this.font,
                centerX - panelWidth / 2,
                startY,
                panelWidth,
                20,
                Component.literal("IP Address"));
        this.ipField.setValue(config.getLastIp());
        this.ipField.setMaxLength(64);
        addRenderableWidget(this.ipField);

        this.portField = new EditBox(this.font,
                centerX - panelWidth / 2,
                startY + 26,
                panelWidth,
                20,
                Component.literal("Port"));
        this.portField.setValue(String.valueOf(config.getLastPort()));
        this.portField.setMaxLength(5);
        addRenderableWidget(this.portField);

        this.connectButton = Button.builder(Component.literal("Connect / Reconnect"),
                        button -> handleConnect())
                .bounds(centerX - panelWidth / 2, startY + 52, panelWidth, 20)
                .build();
        addRenderableWidget(this.connectButton);

        this.toggleControlButton = Button.builder(Component.literal("Enable Robot Control"),
                        button -> handleToggleRobotControl())
                .bounds(centerX - panelWidth / 2, startY + 78, panelWidth, 20)
                .build();
        addRenderableWidget(this.toggleControlButton);
        
        // Force Release 버튼 (Lock이 걸려있을 때만 활성화)
        this.forceReleaseButton = Button.builder(Component.literal("Force Release Lock"),
                        button -> handleForceRelease())
                .bounds(centerX - panelWidth / 2, startY + 104, panelWidth, 20)
                .build();
        addRenderableWidget(this.forceReleaseButton);
        
        // ========== 팔/머리 제어 버튼 (아래쪽) ==========
        int armButtonY = startY + 134;
        int halfWidth = panelWidth / 2 - 2;
        
        // 첫 번째 줄: Center Head, Reset Arms
        this.centerHeadButton = Button.builder(Component.literal("Center Head"),
                        button -> handleCenterHead())
                .bounds(centerX - panelWidth / 2, armButtonY, halfWidth, 18)
                .build();
        addRenderableWidget(this.centerHeadButton);
        
        this.resetArmsButton = Button.builder(Component.literal("Reset Arms"),
                        button -> handleResetArms())
                .bounds(centerX + 2, armButtonY, halfWidth, 18)
                .build();
        addRenderableWidget(this.resetArmsButton);
        
        // 두 번째 줄: T-Pose, Guard
        this.tPoseButton = Button.builder(Component.literal("T-Pose"),
                        button -> handleTPose())
                .bounds(centerX - panelWidth / 2, armButtonY + 22, halfWidth, 18)
                .build();
        addRenderableWidget(this.tPoseButton);
        
        this.guardPoseButton = Button.builder(Component.literal("Guard"),
                        button -> handleGuardPose())
                .bounds(centerX + 2, armButtonY + 22, halfWidth, 18)
                .build();
        addRenderableWidget(this.guardPoseButton);
        
        // 세 번째 줄: Wave (가운데 정렬)
        this.waveButton = Button.builder(Component.literal("Wave Hand"),
                        button -> handleWave())
                .bounds(centerX - panelWidth / 4, armButtonY + 44, panelWidth / 2, 18)
                .build();
        addRenderableWidget(this.waveButton);

        this.closeButton = Button.builder(Component.literal("Close"),
                        button -> onClose())
                .bounds(centerX - 50, this.height - 32, 100, 20)
                .build();
        addRenderableWidget(this.closeButton);

        updateToggleButtonText();
        updateButtonStates();
    }

    @Override
    public void tick() {
        super.tick();
        // EditBox no longer has tick() method in newer Minecraft versions

        if (++refreshTicker >= 20) {
            refreshTicker = 0;
            controller = tryGetController();
            updateToggleButtonText();
            updateButtonStates();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, BG_COLOR);

        int panelW = 300;
        int panelH = 260;  // 높이 증가 (Lock 상태 + 팔 제어 버튼)
        int panelX = this.width / 2 - panelW / 2;
        int panelY = this.height / 2 - panelH / 2;
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_COLOR);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, TITLE.getString(),
                this.width / 2, panelY - 18, TITLE_COLOR);

        graphics.drawString(this.font, "IP Address", panelX + 10, panelY + 8, TEXT_COLOR, false);
        graphics.drawString(this.font, "Port", panelX + 10, panelY + 34, TEXT_COLOR, false);

        if (controller != null) {
            int statusY = panelY + 90;

            // 연결 상태
            boolean connected = controller.isConnected();
            String connection = connected
                    ? "● Connected to " + controller.getRobotAddress()
                    : "● Disconnected";

            graphics.drawCenteredString(this.font, connection,
                    this.width / 2, statusY,
                    connected ? OK_COLOR : WARN_COLOR);

            // Robot Control 상태
            String controlLabel = controller.isRobotListenerEnabled()
                    ? "Robot control: ENABLED (WASD + Mouse)"
                    : "Robot control: DISABLED";
            graphics.drawCenteredString(this.font, controlLabel,
                    this.width / 2, statusY + 12, TEXT_COLOR);
            
            // Lock 상태 표시
            MotionCoordinator.LockStatus lockStatus = controller.getCoordinatorStatus();
            String lockLabel;
            int lockColor;
            
            if (lockStatus.locked) {
                boolean isMe = controller.getOwnerId().equals(lockStatus.owner);
                if (isMe) {
                    lockLabel = String.format("⚠ Lock: ME (%s)", lockStatus.owner);
                    lockColor = OK_COLOR;
                } else {
                    lockLabel = String.format("⚠ Lock: %s (task: %s, %ds ago)", 
                                             lockStatus.owner, 
                                             lockStatus.taskDescription,
                                             lockStatus.elapsedMs / 1000);
                    lockColor = LOCK_COLOR;
                }
            } else {
                lockLabel = "● Lock: FREE";
                lockColor = OK_COLOR;
            }
            graphics.drawCenteredString(this.font, lockLabel,
                    this.width / 2, statusY + 24, lockColor);
            
            // hasLock 상태
            String hasLockLabel = controller.hasLock() 
                    ? "✓ Has Lock" 
                    : "✗ No Lock";
            graphics.drawCenteredString(this.font, hasLockLabel,
                    this.width / 2, statusY + 36, 
                    controller.hasLock() ? OK_COLOR : WARN_COLOR);
            
            // Blocked count
            int blocked = controller.getLockBlockedCount();
            if (blocked > 0) {
                String blockedLabel = String.format("Blocked commands: %d", blocked);
                graphics.drawCenteredString(this.font, blockedLabel,
                        this.width / 2, statusY + 48, LOCK_COLOR);
            }

        } else {
            graphics.drawCenteredString(this.font, "Controller not initialized",
                    this.width / 2, panelY + 90, WARN_COLOR);
        }

        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredString(this.font, statusMessage,
                    this.width / 2, panelY + panelH + 12, statusColor);
        }
    }

    private void handleConnect() {
        String ip = ipField.getValue().trim();
        int port;

        if (ip.isEmpty()) {
            setStatus("IP address cannot be empty", WARN_COLOR);
            return;
        }

        try {
            port = Integer.parseInt(portField.getValue().trim());
            if (port <= 0 || port > 65535) {
                setStatus("Port must be between 1 and 65535", WARN_COLOR);
                return;
            }
        } catch (NumberFormatException e) {
            setStatus("Invalid port", WARN_COLOR);
            return;
        }

        try {
            controller = WebotsController.getInstance(ip, port);
            setStatus("Target set to " + controller.getRobotAddress(), OK_COLOR);
            WebotsConfigScreen.Config.getInstance().update(ip, port);
        } catch (Exception e) {
            LOGGER.error("Failed to set target {}:{}", ip, port, e);
            setStatus("Connection setup failed: " + e.getMessage(), WARN_COLOR);
        }

        updateToggleButtonText();
        updateButtonStates();
    }

    private void handleToggleRobotControl() {
        controller = tryGetController();
        if (controller == null) {
            setStatus("WebotsController not available. Connect first.", WARN_COLOR);
            return;
        }

        boolean enable = !controller.isRobotListenerEnabled();
        controller.enableRobotListener(enable);

        if (enable) {
            if (controller.hasLock()) {
                setStatus("Robot control enabled with Lock. Use WASD + Mouse.", OK_COLOR);
            } else {
                setStatus("Robot control enabled but NO LOCK. Commands blocked.", LOCK_COLOR);
            }
        } else {
            setStatus("Robot control disabled. Lock released.", OK_COLOR);
        }

        updateToggleButtonText();
        updateButtonStates();
    }
    
    private void handleForceRelease() {
        MotionCoordinator coordinator = MotionCoordinator.getInstance();
        coordinator.forceRelease("Manual force release from UI");
        setStatus("Lock force released!", OK_COLOR);
        
        // Lock 재획득 시도
        if (controller != null && controller.isRobotListenerEnabled()) {
            controller.tryReacquireLock();
            if (controller.hasLock()) {
                setStatus("Lock force released and reacquired!", OK_COLOR);
            }
        }
        
        updateButtonStates();
    }
    
    // ========== 팔/머리 제어 핸들러 ==========
    
    private void handleCenterHead() {
        if (controller == null) {
            setStatus("Controller not available", WARN_COLOR);
            return;
        }
        if (!controller.isRobotListenerEnabled()) {
            setStatus("Enable Robot Control first", WARN_COLOR);
            return;
        }
        controller.centerHead();
        setStatus("Head centered at current position", OK_COLOR);
    }
    
    private void handleResetArms() {
        if (controller == null) {
            setStatus("Controller not available", WARN_COLOR);
            return;
        }
        if (!controller.isRobotListenerEnabled()) {
            setStatus("Enable Robot Control first", WARN_COLOR);
            return;
        }
        controller.resetToStandPose();
        setStatus("Arms reset to standing pose", OK_COLOR);
    }
    
    private void handleTPose() {
        if (controller == null) {
            setStatus("Controller not available", WARN_COLOR);
            return;
        }
        if (!controller.isRobotListenerEnabled()) {
            setStatus("Enable Robot Control first", WARN_COLOR);
            return;
        }
        controller.setTPose();
        setStatus("T-Pose activated", OK_COLOR);
    }
    
    private void handleGuardPose() {
        if (controller == null) {
            setStatus("Controller not available", WARN_COLOR);
            return;
        }
        if (!controller.isRobotListenerEnabled()) {
            setStatus("Enable Robot Control first", WARN_COLOR);
            return;
        }
        controller.setGuardPose();
        setStatus("Guard pose activated", OK_COLOR);
    }
    
    private void handleWave() {
        if (controller == null) {
            setStatus("Controller not available", WARN_COLOR);
            return;
        }
        if (!controller.isRobotListenerEnabled()) {
            setStatus("Enable Robot Control first", WARN_COLOR);
            return;
        }
        controller.waveHand();
        setStatus("Waving hand!", OK_COLOR);
    }

    private void updateButtonStates() {
        if (toggleControlButton == null) return;

        boolean hasController = controller != null;
        boolean robotEnabled = hasController && controller.isRobotListenerEnabled();

        toggleControlButton.active = hasController;

        if (connectButton != null) {
            connectButton.active = true;
        }
        
        // Force Release 버튼: Lock이 걸려있을 때만 활성화
        if (forceReleaseButton != null) {
            if (hasController) {
                MotionCoordinator.LockStatus status = controller.getCoordinatorStatus();
                forceReleaseButton.active = status.locked;
            } else {
                forceReleaseButton.active = false;
            }
        }
        
        // 팔/머리 제어 버튼: Robot Control이 활성화되어 있을 때만 활성화
        if (centerHeadButton != null) centerHeadButton.active = robotEnabled;
        if (resetArmsButton != null) resetArmsButton.active = robotEnabled;
        if (tPoseButton != null) tPoseButton.active = robotEnabled;
        if (guardPoseButton != null) guardPoseButton.active = robotEnabled;
        if (waveButton != null) waveButton.active = robotEnabled;
    }

    private void updateToggleButtonText() {
        if (toggleControlButton == null) return;

        if (controller != null && controller.isRobotListenerEnabled()) {
            toggleControlButton.setMessage(Component.literal("Disable Robot Control"));
        } else {
            toggleControlButton.setMessage(Component.literal("Enable Robot Control"));
        }
    }

    private void setStatus(String message, int color) {
        this.statusMessage = message;
        this.statusColor = color;
    }

    private WebotsController tryGetController() {
        try {
            return WebotsController.getInstance();
        } catch (Exception e) {
            return controller;
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
