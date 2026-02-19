package net.shoreline.client.impl.gui.hud.widget.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.gui.hud.widget.HudWidget;
import net.shoreline.client.impl.module.combat.AutoRegearModule;
import net.shoreline.client.impl.module.exploit.FastLatencyModule;
import net.shoreline.client.impl.module.misc.TimerModule;
import net.shoreline.client.init.Managers;
import net.shoreline.client.util.render.ColorUtil;

import java.awt.*;
import java.text.DecimalFormat;

/**
 * Metrics 위젯 - ServerBrand, Speed, Ping, TPS, FPS를 세로로 표시.
 *
 * <p>스크린샷에서 중앙에 보이는 텍스트 블록을 구현한다:</p>
 * <pre>
 *   ServerBrand thearchive.world (Velocity)
 *   Speed 0.00km/h
 *   Ping 187ms
 *   TPS 19.99
 *   FPS 117
 * </pre>
 */
public class MetricsWidget extends HudWidget {

    private static final DecimalFormat DF  = new DecimalFormat("0.00");
    private static final DecimalFormat DF2 = new DecimalFormat("0.0#");

    // 표시할 항목 플래그
    private boolean showBrand  = true;
    private boolean showSpeed  = true;
    private boolean showPing   = true;
    private boolean showTps    = true;
    private boolean showFps    = true;

    public MetricsWidget() {
        super("Metrics", 10, 10, 150, 70);
    }

    @Override
    public void renderHud(DrawContext context) {
        if (mc.player == null) return;

        float lineH = RenderManager.textHeight();
        float cy = y + 2;
        int color = getThemeColor();

        if (showBrand) {
            ClientPlayNetworkHandler handler = mc.player.networkHandler;
            String brand = handler != null && handler.getBrand() != null
                    ? handler.getBrand() : "Unknown";
            String ip = mc.getCurrentServerEntry() != null
                    ? mc.getCurrentServerEntry().address : "singleplayer";
            String text = "§7ServerBrand §f" + ip + " §7(" + brand + ")";
            RenderManager.renderText(context, text, x + 2, cy, color);
            cy += lineH;
        }
        if (showSpeed) {
            double dx = mc.player.getX() - mc.player.prevX;
            double dz = mc.player.getZ() - mc.player.prevZ;
            float timer = TimerModule.getInstance() != null && TimerModule.getInstance().isEnabled()
                    ? TimerModule.getInstance().getTimer() : 1.0f;
            double dist = Math.sqrt(dx * dx + dz * dz) / 1000.0;
            double speed = dist / (0.05 / 3600.0) * timer;
            RenderManager.renderText(context, "§7Speed §f" + DF.format(speed) + "km/h", x + 2, cy, color);
            cy += lineH;
        }
        if (showPing) {
            int ping = FastLatencyModule.getInstance() != null && FastLatencyModule.getInstance().isEnabled()
                    ? (int) FastLatencyModule.getInstance().getLatency()
                    : Managers.NETWORK.getClientLatency();
            // Ping 색상: 낮을수록 초록, 높을수록 빨강
            int pingColor = ColorUtil.hslToColor(
                    Math.max(0, 120 - ping / 2.5f), 100f, 50f, 1f).getRGB();
            RenderManager.renderText(context, "§7Ping ", x + 2, cy, color);
            RenderManager.renderText(context, ping + "ms",
                    x + 2 + RenderManager.textWidth("§7Ping "), cy, pingColor);
            cy += lineH;
        }
        if (showTps) {
            float tps = Managers.TICK.getTpsCurrent();
            // TPS 색상
            int tpsColor = ColorUtil.hslToColor(
                    Math.max(0, (tps / 20f) * 120f), 100f, 50f, 1f).getRGB();
            RenderManager.renderText(context, "§7TPS ", x + 2, cy, color);
            RenderManager.renderText(context, DF2.format(tps),
                    x + 2 + RenderManager.textWidth("§7TPS "), cy, tpsColor);
            cy += lineH;
        }
        if (showFps) {
            RenderManager.renderText(context, "§7FPS §f" + mc.getCurrentFps(), x + 2, cy, color);
            cy += lineH;
        }

        // 위젯 높이를 콘텐츠에 맞게 동적으로 조정
        setHeight(cy - y + 2);
        // 너비도 가장 긴 줄에 맞게 동적 조정
        setWidth(Math.max(130, RenderManager.textWidth("§7ServerBrand §f" +
                (mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "") + " (Velocity)") + 6));
    }

    // ─── 옵션 설정 ──────────────────────────────────────────────
    public void setShowBrand(boolean v)  { showBrand  = v; }
    public void setShowSpeed(boolean v)  { showSpeed  = v; }
    public void setShowPing(boolean v)   { showPing   = v; }
    public void setShowTps(boolean v)    { showTps    = v; }
    public void setShowFps(boolean v)    { showFps    = v; }
}
