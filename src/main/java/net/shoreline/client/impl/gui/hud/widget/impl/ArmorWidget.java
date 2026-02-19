package net.shoreline.client.impl.gui.hud.widget.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.gui.hud.widget.HudWidget;
import net.shoreline.client.util.render.ColorUtil;

public class ArmorWidget extends HudWidget {

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
