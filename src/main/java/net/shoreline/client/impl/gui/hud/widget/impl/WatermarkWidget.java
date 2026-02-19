package net.shoreline.client.impl.gui.hud.widget.impl;

import net.minecraft.client.gui.DrawContext;
import net.shoreline.client.BuildConfig;
import net.shoreline.client.ShorelineMod;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.gui.hud.widget.HudWidget;

public class WatermarkWidget extends HudWidget {

    public WatermarkWidget() {
        super("Watermark", 2, 2, 140, 12);
    }

    @Override
    public void renderHud(DrawContext context) {
        String text = String.format("%s %s (%s-%s%s)",
                ShorelineMod.MOD_NAME, ShorelineMod.MOD_VER,
                BuildConfig.BUILD_IDENTIFIER,
                BuildConfig.BUILD_NUMBER,
                !BuildConfig.HASH.equals("null") ? "-" + BuildConfig.HASH : "");
        RenderManager.renderText(context, text, x + 2, y + 2, getThemeColor());
        setWidth(RenderManager.textWidth(text) + 6);
        setHeight(RenderManager.textHeight() + 4);
    }
}
