package net.shoreline.client.impl.gui.hud.widget.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.StringHelper;
import net.minecraft.util.math.MathHelper;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.gui.hud.widget.HudWidget;

public class PotionEffectsWidget extends HudWidget {

    public PotionEffectsWidget() {
        super("PotionEffects", 0, 40, 130, 12);
        // 화면 우측에 기본 배치
        float sw = mc.getWindow().getScaledWidth();
        setX(sw - 135);
    }

    @Override
    public void renderHud(DrawContext context) {
        if (mc.player == null || mc.world == null) return;
        float cy = y + 2;
        float maxW = 0;
        int color = getThemeColor();

        for (var instance : mc.player.getStatusEffects()) {
            StatusEffect effect = instance.getEffectType().value();
            if (effect == StatusEffects.NIGHT_VISION) continue;

            boolean infinite = instance.getDuration() == -1;
            int amp = instance.getAmplifier();
            String dur = infinite ? "Inf" : StringHelper.formatTicks(
                    MathHelper.floor(instance.getDuration()),
                    mc.world.getTickManager().getTickRate());
            String text = String.format("%s %s§f%s",
                    effect.getName().getString(),
                    amp > 0 ? (amp + 1) + " " : "", dur);
            RenderManager.renderText(context, text, x + 2, cy, color);
            maxW = Math.max(maxW, RenderManager.textWidth(text) + 6);
            cy += RenderManager.textHeight();
        }

        setHeight(Math.max(12, cy - y + 2));
        setWidth(Math.max(80, maxW));
    }
}
