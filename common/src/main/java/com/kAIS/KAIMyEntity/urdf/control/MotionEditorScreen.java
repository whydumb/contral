package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Minimal motion editor placeholder screen.
 *
 * <p>The original project referenced a more feature complete editor that was
 * distributed only as compiled classes. When the sources are missing the
 * NeoForge build fails, so we provide a lightweight in-project implementation
 * to preserve command functionality and maintain a working client UI.</p>
 */
public class MotionEditorScreen extends Screen {
    private final Screen parent;
    private final URDFModelOpenGLWithSTL renderer;

    private MotionEditorScreen(Screen parent, URDFModelOpenGLWithSTL renderer) {
        super(Component.literal("Motion Editor"));
        this.parent = parent;
        this.renderer = renderer;
    }

    /** Opens the editor on the Minecraft client thread. */
    public static void open(URDFModelOpenGLWithSTL renderer) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new MotionEditorScreen(minecraft.screen, renderer)));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.addRenderableWidget(Button.builder(Component.literal("Close"),
                button -> onClose())
            .bounds(centerX - 40, centerY + 24, 80, 20)
            .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int titleY = this.height / 2 - 32;
        graphics.drawCenteredString(this.font, "Motion editor placeholder", this.width / 2, titleY, 0xFFFFFF);
        graphics.drawCenteredString(this.font, "Source for full editor unavailable; basic screen provided.",
                this.width / 2, titleY + 14, 0xAAAAAA);
        if (renderer == null) {
            graphics.drawCenteredString(this.font, "Renderer unavailable", this.width / 2, titleY + 28, 0xFF5555);
        }
    }

    @Override
    public void onClose() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(parent);
    }
}
