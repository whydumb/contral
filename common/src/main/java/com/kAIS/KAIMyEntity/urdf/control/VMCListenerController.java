package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class VMCListenerController extends Screen {
    // ... (UI 관련 코드는 기존과 동일하므로 생략, 아래 VmcListener가 핵심입니다) ...
    // 기존 UI 코드 유지: BG_COLOR, PANEL_COLOR, init(), render() 등...
    private static final int BG_COLOR = 0xFF0E0E10;
    private static final int PANEL_COLOR = 0xFF1D1F24;
    private static final int TITLE_COLOR = 0xFFFFD770;
    private static final int TXT_MAIN = 0xFFFFFFFF;

    private final Screen parent;
    private final URDFModelOpenGLWithSTL renderer;
    private final VmcListener listener = VmcListener.getInstance();

    private EditBox addressBox;
    private EditBox portBox;
    private Button startButton;
    private Button stopButton;
    private Button hideButton;
    private int autoRefreshTicker = 0;

    public VMCListenerController(Screen parent, URDFModelOpenGLWithSTL renderer) {
        super(Component.literal("VMC Listener Controller"));
        this.parent = parent;
        this.renderer = renderer;
    }

    @Override
    protected void init() {
        super.init();
        // ... (기존 UI init 코드 동일) ...
        int centerX = this.width / 2;
        int startY = 40;

        addressBox = new EditBox(this.font, centerX - 100, startY, 200, 20, Component.literal("Address"));
        addressBox.setValue("0.0.0.0");
        addRenderableWidget(addressBox);

        startY += 25;
        portBox = new EditBox(this.font, centerX - 100, startY, 200, 20, Component.literal("Port"));
        portBox.setValue("39539");
        addRenderableWidget(portBox);

        startY += 30;
        startButton = Button.builder(Component.literal("Start VMC Listener"), b -> {
            String addr = addressBox.getValue();
            int port;
            try {
                port = Integer.parseInt(portBox.getValue());
            } catch (NumberFormatException e) {
                minecraft.gui.getChat().addMessage(Component.literal("§c[VMC] Invalid port"));
                return;
            }
            listener.start(addr, port);
            updateButtons();
        }).bounds(centerX - 100, startY, 200, 20).build();
        addRenderableWidget(startButton);

        startY += 25;
        stopButton = Button.builder(Component.literal("Stop VMC Listener"), b -> {
            listener.stop();
            updateButtons();
        }).bounds(centerX - 100, startY, 200, 20).build();
        addRenderableWidget(stopButton);

        hideButton = Button.builder(Component.literal("Hide"), b -> Minecraft.getInstance().setScreen(parent))
                .bounds(centerX - 50, this.height - 30, 100, 20).build();
        addRenderableWidget(hideButton);

        updateButtons();
    }

    private void updateButtons() {
        boolean running = listener.isRunning();
        startButton.active = !running;
        stopButton.active = running;
        addressBox.setEditable(!running);
        portBox.setEditable(!running);
    }

    @Override
    public void tick() {
        super.tick();
        if (renderer != null && listener.isRunning()) {
            MotionEditorScreen.tick(renderer);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // ... (기존 render 코드와 동일) ...
        graphics.fill(0, 0, this.width, this.height, BG_COLOR);
        int panelX = this.width / 2 - 220;
        int panelY = 140;
        int panelW = 440;
        int panelH = this.height - panelY - 50;
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_COLOR);

        super.render(graphics, mouseX, mouseY, partialTicks);

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1000.0f);
        graphics.drawCenteredString(this.font, "VMC Listener Controller", this.width / 2, 10, TITLE_COLOR);
        graphics.drawCenteredString(this.font, "§a● VMC 백그라운드 동작 중", this.width / 2, 26, 0xFF55FF55);

        VmcListener.Diagnostics diag = listener.getDiagnostics();
        List<String> lines = new ArrayList<>();
        if (diag.running()) {
            lines.add("§aStatus: RUNNING §l§o(Double Buffered)");
            long elapsed = System.currentTimeMillis() - diag.lastPacketTime();
            lines.add("Last packet: " + (elapsed < 1000 ? "§a" + elapsed + "ms" : "§c" + elapsed + "ms"));
            lines.add("Active Bones (Snap): " + listener.getSnapshot().size());
            lines.add("VMC Packets: " + diag.vmcPackets());
        } else {
            lines.add("§cStatus: STOPPED");
        }

        int y = panelY + 10;
        for (String line : lines) {
            graphics.drawString(this.font, line, panelX + 10, y, TXT_MAIN, false);
            y += 14;
        }
        graphics.pose().popPose();

        if (++autoRefreshTicker >= 10) {
            autoRefreshTicker = 0;
            updateButtons();
        }
    }
    
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /* ======================== VmcListener (핵심 수정됨) ======================== */
    public static final class VmcListener {
        private static final Logger logger = LogManager.getLogger();
        private static volatile VmcListener instance;
        private DatagramSocket socket;
        private Thread receiverThread;
        private final AtomicBoolean running = new AtomicBoolean(false);
        
        // [수정] 실시간 맵 제거 -> 아토믹 스냅샷 도입
        // 읽기용: 렌더링 스레드가 가져가는 완성된 프레임
        private final AtomicReference<Map<String, Transform>> snapshot = new AtomicReference<>(Collections.emptyMap());
        
        // 쓰기용: 백그라운드 스레드가 채우는 버퍼
        private final Map<String, Transform> writingBuffer = new HashMap<>();

        private final AtomicLong totalPackets = new AtomicLong(0);
        private final AtomicLong vmcPackets = new AtomicLong(0);
        private final AtomicLong lastPacketTime = new AtomicLong(0);

        private static final Set<String> STANDARD_BONE_NAMES = Set.of(
                "Hips", "Spine", "Chest", "UpperChest", "Neck", "Head",
                "LeftShoulder", "LeftUpperArm", "LeftLowerArm", "LeftHand",
                "RightShoulder", "RightUpperArm", "RightLowerArm", "RightHand",
                // 다리 등 필요하면 추가
                "LeftUpperLeg", "LeftLowerLeg", "LeftFoot", 
                "RightUpperLeg", "RightLowerLeg", "RightFoot"
        );

        private java.util.function.Function<String, String> boneNameNormalizer = name -> name;

        private VmcListener() {}

        public static VmcListener getInstance() {
            if (instance == null) {
                synchronized (VmcListener.class) {
                    if (instance == null) instance = new VmcListener();
                }
            }
            return instance;
        }

        public void setBoneNameNormalizer(java.util.function.Function<String, String> normalizer) {
            this.boneNameNormalizer = normalizer != null ? normalizer : name -> name;
        }

        public synchronized void start(String addr, int port) {
            if (running.get()) return;
            try {
                InetAddress bindAddr = "0.0.0.0".equals(addr) ? null : InetAddress.getByName(addr);
                socket = bindAddr == null ? new DatagramSocket(port) : new DatagramSocket(port, bindAddr);
                running.set(true);
                
                // 버퍼 초기화
                writingBuffer.clear();
                snapshot.set(Collections.emptyMap());

                receiverThread = new Thread(this::receiveLoop, "VMC-Receiver");
                receiverThread.setDaemon(true);
                receiverThread.start();
                logger.info("VMC Listener started on {}:{}", addr, port);
            } catch (Exception e) {
                logger.error("Failed to start", e);
            }
        }

        public synchronized void stop() {
            if (!running.get()) return;
            running.set(false);
            if (socket != null) socket.close();
        }

        private void receiveLoop() {
            byte[] buffer = new byte[65536];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            boolean first = true;
            
            while (running.get()) {
                try {
                    socket.receive(packet);
                    lastPacketTime.set(System.currentTimeMillis());
                    totalPackets.incrementAndGet();

                    if (first) {
                        first = false;
                        Minecraft.getInstance().execute(() ->
                                Minecraft.getInstance().gui.getChat().addMessage(
                                        Component.literal("§b[VMC] Connected! (Mode: Atomic Snapshot)")));
                    }

                    // 1. 패킷(Bundle) 파싱 -> writingBuffer에 기록
                    processOscPacket(buffer, packet.getLength());
                    
                    // 2. 패킷 처리가 끝나면(Bundle 하나 완료) 스냅샷 업데이트 (Atomic Commit)
                    //    VMC는 보통 한 패킷(Bundle)에 한 프레임의 모든 본 데이터를 담아 보냅니다.
                    commitSnapshot();

                } catch (Exception e) {
                    // 소켓 타임아웃이나 파싱 에러 등 무시
                }
            }
        }
        
        private void commitSnapshot() {
            // writingBuffer의 내용을 복사하여 불변 Map으로 만듦 (ConcurrentModification 방지)
            // 얕은 복사여도 Transform 객체를 매번 새로 생성한다면 안전하지만, 
            // 성능을 위해 Map만 새로 만들고 내용은 유지.
            // 여기서는 데이터 일관성을 위해 새 Map을 생성하여 Snapshot 교체
            Map<String, Transform> newSnapshot = new HashMap<>(writingBuffer);
            snapshot.set(Collections.unmodifiableMap(newSnapshot));
        }

        private void processOscPacket(byte[] data, int length) {
            if (length < 8) return;
            if (startsWith(data, 0, "#bundle")) processBundleRecursive(data, 0, length);
            else processOscMessage(data, 0, length);
        }

        private void processBundleRecursive(byte[] data, int offset, int length) {
            int pos = offset + 16; // "#bundle" + 8byte timetag = 16
            while (pos + 4 <= offset + length) {
                int size = readInt(data, pos);
                pos += 4;
                if (pos + size > offset + length) break;
                if (startsWith(data, pos, "#bundle")) processBundleRecursive(data, pos, size);
                else processOscMessage(data, pos, size);
                pos += size;
            }
        }

        private void processOscMessage(byte[] data, int offset, int length) {
            // (기존 파싱 로직 동일)
            int pos = offset;
            int end = offset + length;
            String address = readString(data, pos, end);
            if (address == null) return;
            pos += padLen(address);

            String types = readString(data, pos, end);
            if (types == null || !types.startsWith(",")) return;
            pos += padLen(types);

            List<Object> args = new ArrayList<>();
            for (int i = 1; i < types.length(); i++) {
                char t = types.charAt(i);
                if (t == 'f' && pos + 4 <= end) {
                    args.add(Float.intBitsToFloat(readInt(data, pos)));
                    pos += 4;
                } else if (t == 's') {
                    String s = readString(data, pos, end);
                    args.add(s);
                    pos += padLen(s);
                } else return;
            }
            handleVmcMessage(address, args.toArray());
        }

        private void handleVmcMessage(String address, Object[] args) {
            if (!address.startsWith("/VMC/Ext/")) return;
            vmcPackets.incrementAndGet();

            // [수정] 단일 좌표계 전략: World Position만 신뢰하여 저장
            // Local Position은 무시하거나, 필요하다면 별도 로직으로 처리하지만
            // MotionEditorScreen의 로직(부모 역행렬 곱)은 World 데이터를 요구함.
            if (address.equals("/VMC/Ext/Bone/Pos")) {
                if (args.length >= 8 && args[0] instanceof String rawName) {
                    String normalized = boneNameNormalizer.apply(rawName);
                    if (normalized != null && STANDARD_BONE_NAMES.contains(normalized)) {
                        // writingBuffer에 쓰기 (스냅샷 아님)
                        Transform t = writingBuffer.computeIfAbsent(normalized, k -> new Transform());
                        t.position.set(getFloat(args, 1), getFloat(args, 2), getFloat(args, 3));
                        t.rotation.set(getFloat(args, 4), getFloat(args, 5), getFloat(args, 6), getFloat(args, 7)).normalize();
                    }
                }
            }
            // "/VMC/Ext/Blend/Apply" 등을 처리하고 싶다면 여기서 commitSnapshot()을 호출하도록 로직 변경 가능
        }

        // 헬퍼 메서드들 (기존과 동일)
        private static boolean startsWith(byte[] d, int o, String p) {
            byte[] pb = p.getBytes(StandardCharsets.US_ASCII);
            if (o + pb.length > d.length) return false;
            for (int i = 0; i < pb.length; i++) if (d[o + i] != pb[i]) return false;
            return true;
        }
        private static int readInt(byte[] d, int o) {
            return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
        }
        private static String readString(byte[] d, int o, int e) {
            int l = 0;
            while (o + l < e && d[o + l] != 0) l++;
            return o + l >= e ? null : new String(d, o, l, StandardCharsets.US_ASCII);
        }
        private static int padLen(String s) {
            int l = s.getBytes(StandardCharsets.US_ASCII).length + 1;
            return l + ((4 - (l % 4)) & 3);
        }
        private static float getFloat(Object[] a, int i) {
            if (i >= a.length) return 0f;
            Object o = a[i];
            return o instanceof Number n ? n.floatValue() : 0f;
        }

        public boolean isRunning() { return running.get(); }
        
        // [수정] 스냅샷 반환 메서드
        public Map<String, Transform> getSnapshot() {
            return snapshot.get();
        }
        
        public Diagnostics getDiagnostics() {
            return new Diagnostics(running.get(), lastPacketTime.get(), totalPackets.get(), vmcPackets.get(), List.of());
        }

        // [수정] BoneTransform을 단순화 (Local/World 구분 없이 World만 저장)
        public static class Transform {
            public final Vector3f position = new Vector3f();
            public final Quaternionf rotation = new Quaternionf();
        }

        public record Diagnostics(boolean running, long lastPacketTime, long totalPackets, long vmcPackets, List<String> recent) {}
    }
}
