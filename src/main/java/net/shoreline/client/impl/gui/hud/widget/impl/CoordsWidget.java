package net.shoreline.client.impl.gui.hud.widget.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.world.World;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.gui.hud.widget.HudWidget;

import java.text.DecimalFormat;

public class CoordsWidget extends HudWidget {

    private static final DecimalFormat DF = new DecimalFormat("0.0");
    private boolean showNether = true;

    public CoordsWidget() {
        super("Coords", 2, 40, 180, 12);
    }

    @Override
    public void renderHud(DrawContext context) {
        if (mc.player == null || mc.world == null) return;
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();
        boolean nether = mc.world.getRegistryKey() == World.NETHER;
        String text = String.format("XYZ §f%s, %s, %s" + (showNether ? " §7[§f%s, %s§7]" : ""),
                DF.format(px), DF.format(py), DF.format(pz),
                nether ? DF.format(px * 8) : DF.format(px / 8),
                nether ? DF.format(pz * 8) : DF.format(pz / 8));
        RenderManager.renderText(context, text, x + 2, y + 2, getThemeColor());
        setWidth(RenderManager.textWidth(text) + 6);
        setHeight(RenderManager.textHeight() + 4);
    }

    public void setShowNether(boolean v) { this.showNether = v; }
}
