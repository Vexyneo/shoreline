package net.shoreline.client.impl.gui.hud.widget.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StringHelper;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.shoreline.client.BuildConfig;
import net.shoreline.client.ShorelineMod;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.gui.hud.widget.HudWidget;
import net.shoreline.client.impl.module.client.HUDModule;
import net.shoreline.client.init.Managers;
import net.shoreline.client.util.render.ColorUtil;
import net.shoreline.client.util.string.EnumFormatter;

import java.text.DecimalFormat;

// ════════════════════════════════════════════════════════════════════════════════
// 워터마크 위젯
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Watermark 위젯 - 클라이언트 이름/버전을 좌상단에 표시.
 */
class WatermarkWidget extends HudWidget {

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
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// 좌표 위젯
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Coords 위젯 - XYZ 좌표와 네더 좌표를 표시.
 */
class CoordsWidget extends HudWidget {

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
        boolean nether = mc.world.getRegistryKey() == net.minecraft.world.World.NETHER;
        String text = String.format("XYZ §f%s, %s, %s" + (showNether
                ? " §7[§f%s, %s§7]" : ""),
                DF.format(px), DF.format(py), DF.format(pz),
                nether ? DF.format(px * 8) : DF.format(px / 8),
                nether ? DF.format(pz * 8) : DF.format(pz / 8));
        RenderManager.renderText(context, text, x + 2, y + 2, getThemeColor());
        setWidth(RenderManager.textWidth(text) + 6);
    }

    public void setShowNether(boolean v) { this.showNether = v; }
}

// ════════════════════════════════════════════════════════════════════════════════
// 방향 위젯
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Direction 위젯 - 현재 바라보는 방향을 표시.
 */
class DirectionWidget extends HudWidget {

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
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// 아머 위젯
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Armor 위젯 - 장착한 방어구 아이콘과 내구도를 수평으로 표시.
 */
class ArmorWidget extends HudWidget {

    private boolean showDurability = false;

    public ArmorWidget() {
        super("Armor", 2, 70, 76, 20);
    }

    @Override
    public void renderHud(DrawContext context) {
        if (mc.player == null) return;
        int cx = (int) x;
        for (int i = 3; i >= 0; i--) {
            ItemStack stack = mc.player.getInventory().armor.get(i);
            context.drawItem(stack, cx, (int) y + 2);
            context.drawItemInSlot(mc.textRenderer, stack, cx, (int) y + 2);
            if (showDurability && !stack.isEmpty() && stack.isDamageable()) {
                int max = stack.getMaxDamage();
                int dmg = stack.getDamage();
                context.getMatrices().scale(0.65f, 0.65f, 1f);
                int durColor = ColorUtil.hslToColor(
                        (float)(max - dmg) / max * 120f, 100f, 50f, 1f).getRGB();
                RenderManager.renderText(context,
                        Math.round(((float)(max - dmg) / max) * 100) + "%",
                        (cx + 2) * 1.538f, (y + 2) * 1.538f, durColor);
                context.getMatrices().scale(1.538f, 1.538f, 1f);
            }
            cx += 19;
        }
    }

    public void setShowDurability(boolean v) { this.showDurability = v; }
}

// ════════════════════════════════════════════════════════════════════════════════
// 포션 이펙트 위젯
// ════════════════════════════════════════════════════════════════════════════════

/**
 * PotionEffects 위젯 - 현재 활성 포션 효과를 세로로 표시.
 */
class PotionEffectsWidget extends HudWidget {

    public PotionEffectsWidget() {
        super("PotionEffects", 0, 40, 130, 12);
        // 기본 위치: 화면 우측
        float sw = mc.getWindow().getScaledWidth();
        setX(sw - 135);
    }

    @Override
    public void renderHud(DrawContext context) {
        if (mc.player == null) return;
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
                    mc.world != null ? mc.world.getTickManager().getTickRate() : 20f);
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
