package net.shoreline.client.impl.gui.hud.widget.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Direction;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.gui.hud.widget.HudWidget;
import net.shoreline.client.init.Managers;
import net.shoreline.client.util.string.EnumFormatter;

public class DirectionWidget extends HudWidget {

    public DirectionWidget() {
        super("Direction", 2, 55, 120, 12);
    }

    @Override
    public void renderHud(DrawContext context) {
        if (mc.player == null) return;
        Direction dir = mc.player.getHorizontalFacing();
        String dirStr = EnumFormatter.formatDirection(dir);
        String axis = EnumFormatter.formatAxis(dir.getAxis());
        boolean pos = dir.getDirection() == Direction.AxisDirection.POSITIVE;
        String text = String.format("%s §7[§f%s%s§7]", dirStr, axis, pos ? "+" : "-");
        RenderManager.renderText(context, text, x + 2, y + 2, getThemeColor());
        setWidth(RenderManager.textWidth(text) + 6);
        setHeight(RenderManager.textHeight() + 4);
    }
}
