package net.shoreline.client.impl.gui.hud;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.gui.hud.widget.HudWidget;
import net.shoreline.client.impl.module.client.ColorsModule;
import net.shoreline.client.util.Globals;
import net.shoreline.client.util.render.ColorUtil;

import java.util.List;

/**
 * HUD 에디터 스크린 - 스크린샷과 동일한 레이아웃 구현.
 *
 * <h2>레이아웃</h2>
 * <pre>
 * ┌─────────────┬──────────────────────────────────────┐
 * │  Sidebar    │           Canvas                     │
 * │  (위젯 목록)│  (위젯 드래그 영역 = 실제 화면)        │
 * │  ■ Watermark│                                      │
 * │  ■ Metrics  │    [Metrics 위젯]                    │
 * │  □ Coords   │                                      │
 * │  □ Direction│        [Inventory 위젯]              │
 * │  □ Armor    │                                      │
 * │  ■ Inventory│                                      │
 * └─────────────┴──────────────────────────────────────┘
 * </pre>
 *
 * <h2>조작법</h2>
 * <ul>
 *   <li>사이드바 좌클릭: 위젯 활성화/비활성화 토글</li>
 *   <li>캔버스에서 위젯 드래그: 위치 이동</li>
 *   <li>ESC: 저장 후 닫기</li>
 * </ul>
 */
public class HudEditorScreen extends Screen implements Globals {

    // ─── 사이드바 상수 ──────────────────────────────────────────
    private static final float SIDEBAR_W      = 130f;
    private static final float SIDEBAR_HEADER = 20f;
    private static final float ITEM_H         = 14f;
    private static final float ITEM_PAD       = 3f;

    // ─── 색상 상수 ──────────────────────────────────────────────
    private static final int COLOR_SIDEBAR_BG  = 0xDD111111;
    private static final int COLOR_HEADER_BG   = 0xFF0D0D0D;
    private static final int COLOR_ITEM_HOVER  = 0x33FFFFFF;
    private static final int COLOR_ACTIVE      = 0xFFFF4FAD; // 핑크 (스크린샷)
    private static final int COLOR_INACTIVE    = 0xFF888888;
    private static final int COLOR_SEPARATOR   = 0x55FFFFFF;
    private static final int COLOR_CANVAS_BG   = 0x44000000;

    private final HudWidgetManager manager;
    private HudWidget draggingWidget = null;

    /** 사이드바 스크롤 오프셋 */
    private float scrollOffset = 0f;

    public HudEditorScreen() {
        super(Text.literal("HUD Editor"));
        this.manager = HudWidgetManager.getInstance();
    }

    // ─── 렌더링 ─────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1. 반투명 캔버스 배경
        context.fill(0, 0, width, height, COLOR_CANVAS_BG);

        // 2. 활성화된 위젯 렌더링 (드래그 중인 것 포함)
        for (HudWidget widget : manager.getWidgets()) {
            if (!widget.isEnabled()) continue;
            boolean hovered = widget.isWithin(mouseX, mouseY) && mouseX > SIDEBAR_W;
            widget.renderEditor(context, hovered);
        }

        // 3. 사이드바 렌더링
        renderSidebar(context, mouseX, mouseY);

        // 4. 하단 힌트
        String hint = "§7Left click = toggle  |  Drag widget to reposition  |  ESC = save & close";
        RenderManager.renderText(context, hint,
                (width - RenderManager.textWidth(hint)) / 2f,
                height - 12f, 0xAAFFFFFF);
    }

    /** 왼쪽 사이드바 (헤더 + 위젯 목록) 렌더링 */
    private void renderSidebar(DrawContext context, int mouseX, int mouseY) {
        List<HudWidget> widgets = manager.getWidgets();

        // 사이드바 배경
        context.fill(0, 0, (int) SIDEBAR_W, height, COLOR_SIDEBAR_BG);

        // 헤더
        context.fill(0, 0, (int) SIDEBAR_W, (int) SIDEBAR_HEADER, COLOR_HEADER_BG);
        // 헤더 좌측 클라이언트 컬러 세로 바
        context.fill(0, 0, 2, (int) SIDEBAR_HEADER, ColorsModule.getInstance().getRGB());
        RenderManager.renderText(context, "HUD Editor",
                ITEM_PAD + 4, (SIDEBAR_HEADER - RenderManager.textHeight()) / 2f,
                0xFFFFFFFF);

        // 구분선
        context.fill(0, (int) SIDEBAR_HEADER, (int) SIDEBAR_W,
                (int) SIDEBAR_HEADER + 1, COLOR_SEPARATOR);

        // 위젯 목록
        float cy = SIDEBAR_HEADER + 2 - scrollOffset;
        for (HudWidget widget : widgets) {
            float iy = cy;
            boolean itemHover = mouseX < SIDEBAR_W
                    && mouseY >= iy && mouseY < iy + ITEM_H + ITEM_PAD * 2;

            // 호버 배경
            if (itemHover) {
                context.fill(0, (int) iy, (int) SIDEBAR_W,
                        (int)(iy + ITEM_H + ITEM_PAD * 2), COLOR_ITEM_HOVER);
            }

            // 활성화 상태 색상 인디케이터 (좌측 바)
            int stateColor = widget.isEnabled() ? COLOR_ACTIVE : COLOR_INACTIVE;
            context.fill(2, (int)(iy + ITEM_PAD),
                    4, (int)(iy + ITEM_PAD + ITEM_H), stateColor);

            // 위젯 이름
            int textColor = widget.isEnabled() ? COLOR_ACTIVE : 0xFFAAAAAA;
            RenderManager.renderText(context, widget.getName(),
                    10, iy + ITEM_PAD + (ITEM_H - RenderManager.textHeight()) / 2f, textColor);

            cy += ITEM_H + ITEM_PAD * 2;
        }
    }

    // ─── 마우스 입력 ─────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 사이드바 클릭: 토글
        if (mouseX < SIDEBAR_W) {
            List<HudWidget> widgets = manager.getWidgets();
            float cy = SIDEBAR_HEADER + 2 - scrollOffset;
            for (HudWidget widget : widgets) {
                if (mouseY >= cy && mouseY < cy + ITEM_H + ITEM_PAD * 2) {
                    widget.toggle();
                    return true;
                }
                cy += ITEM_H + ITEM_PAD * 2;
            }
            return true;
        }

        // 캔버스 클릭: 드래그 시작 (활성화된 위젯 역순으로 = 위에 그려진 것 우선)
        if (button == 0) {
            List<HudWidget> widgets = manager.getWidgets();
            for (int i = widgets.size() - 1; i >= 0; i--) {
                HudWidget w = widgets.get(i);
                if (w.isEnabled() && w.mousePressed(mouseX, mouseY, button)) {
                    draggingWidget = w;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double deltaX, double deltaY) {
        if (draggingWidget != null) {
            draggingWidget.mouseDragged(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingWidget != null) {
            draggingWidget.mouseReleased(mouseX, mouseY, button);
            draggingWidget = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        // 사이드바 스크롤
        if (mouseX < SIDEBAR_W) {
            scrollOffset = Math.max(0, scrollOffset - (float) verticalAmount * 8f);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ─── ESC 저장 ────────────────────────────────────────────────

    @Override
    public void close() {
        manager.save();
        super.close();
    }

    /** 에디터는 게임을 일시정지하지 않음 */
    @Override
    public boolean shouldPause() {
        return false;
    }
}
