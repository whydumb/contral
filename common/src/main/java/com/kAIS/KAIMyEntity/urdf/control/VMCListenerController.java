package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Lightweight placeholder for the VMC listener mapping UI.
 *
 * <p>The original implementation shipped only as compiled classes, so this
 * fallback keeps the command operational while presenting a basic explanatory
 * screen. It can be replaced with a fully featured editor when the sources
 * become available.</p>
 */
public class VMCListenerController extends Screen {
    private final Screen parent;
    private final URDFModelOpenGLWithSTL renderer;

    public VMCListenerController(Screen parent, URDFModelOpenGLWithSTL renderer) {
        super(Component.literal("VMC Listener"));
        this.parent = parent;
        this.renderer = renderer;
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
        graphics.drawCenteredString(this.font, "VMC listener placeholder", this.width / 2, titleY, 0xFFFFFF);
        graphics.drawCenteredString(this.font, "Renderer: " + (renderer != null ? "available" : "missing"),
                this.width / 2, titleY + 14, 0xAAAAAA);
        graphics.drawCenteredString(this.font, "Replace with full implementation when ready.",
                this.width / 2, titleY + 28, 0xAAAAAA);
    }

    @Override
    public void onClose() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(parent);
    }
}
