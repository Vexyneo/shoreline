package net.shoreline.client.impl.gui.hud.widget;

import net.minecraft.client.gui.DrawContext;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.module.client.ColorsModule;
import net.shoreline.client.util.Globals;

/**
 * HUD 에디터에서 드래그 가능한 위젯의 추상 기반 클래스.
 *
 * <p>각 위젯은:</p>
 * <ul>
 *   <li>화면 좌표 (x, y)와 크기 (width, height)를 가진다</li>
 *   <li>활성화/비활성화 상태를 가진다</li>
 *   <li>에디터 모드에서 핑크 테두리와 함께 드래그 가능하다</li>
 *   <li>일반 HUD 렌더링과 에디터 미리보기 렌더링이 분리된다</li>
 * </ul>
 */
public abstract class HudWidget implements Globals {

    /** 위젯 고유 이름 (설정 저장/로드 키로 사용) */
    private final String name;

    /** 화면 좌표 */
    protected float x, y;

    /** 위젯 크기 */
    protected float width, height;

    /** 활성화 상태 */
    private boolean enabled;

    // ─── 드래그 상태 ────────────────────────────────────────────
    private boolean dragging;
    private float dragOffsetX, dragOffsetY;

    // ─── 에디터 스타일 상수 ─────────────────────────────────────
    /** 에디터 모드 테두리 색상 (핑크/마젠타) */
    protected static final int BORDER_COLOR  = 0xFFFF4FAD;
    /** 에디터 배경 반투명 */
    protected static final int BG_COLOR      = 0x44000000;
    /** 에디터 선택 배경 */
    protected static final int SELECT_COLOR  = 0x55FF4FAD;

    /**
     * @param name   위젯 식별 이름
     * @param x      초기 X 좌표
     * @param y      초기 Y 좌표
     * @param width  초기 너비
     * @param height 초기 높이
     */
    protected HudWidget(String name, float x, float y, float width, float height) {
        this.name  = name;
        this.x     = x;
        this.y     = y;
        this.width = width;
        this.height = height;
        this.enabled = false;
    }

    // ─── 추상 메서드 ────────────────────────────────────────────

    /**
     * 인게임 HUD 렌더링. 위젯이 enabled일 때 매 프레임 호출된다.
     * 에디터 테두리/배경 없이 실제 HUD 콘텐츠만 그린다.
     */
    public abstract void renderHud(DrawContext context);

    /**
     * 에디터 미리보기 렌더링. renderHud()를 호출한 뒤 에디터 UI를 덧그린다.
     * 기본 구현: 핑크 테두리 + 배경 + renderHud()
     */
    public void renderEditor(DrawContext context, boolean hovered) {
        // 배경
        RenderManager.rect(context.getMatrices(), x, y, width, height,
                hovered ? SELECT_COLOR : BG_COLOR);
        // HUD 콘텐츠 미리보기
        renderHud(context);
        // 테두리
        RenderManager.borderedRect(context.getMatrices(), x, y, width, height,
                0x00000000, BORDER_COLOR);
    }

    // ─── 드래그 처리 ────────────────────────────────────────────

    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button == 0 && isWithin(mouseX, mouseY)) {
            dragging = true;
            dragOffsetX = (float) mouseX - x;
            dragOffsetY = (float) mouseY - y;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY) {
        if (dragging) {
            x = (float) mouseX - dragOffsetX;
            y = (float) mouseY - dragOffsetY;
            clampToScreen();
            return true;
        }
        return false;
    }

    public void mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragging = false;
        }
    }

    /** 위젯이 화면 밖으로 나가지 않도록 좌표를 클램핑 */
    private void clampToScreen() {
        float sw = mc.getWindow().getScaledWidth();
        float sh = mc.getWindow().getScaledHeight();
        x = Math.max(0, Math.min(sw - width, x));
        y = Math.max(0, Math.min(sh - height, y));
    }

    // ─── 유틸 ────────────────────────────────────────────────────

    public boolean isWithin(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width
                && mouseY >= y && mouseY <= y + height;
    }

    /** 현재 클라이언트 테마 색상 반환 */
    protected int getThemeColor() {
        return ColorsModule.getInstance().getRGB();
    }

    // ─── Getter / Setter ─────────────────────────────────────────

    public String getName()    { return name; }
    public float  getX()       { return x; }
    public float  getY()       { return y; }
    public float  getWidth()   { return width; }
    public float  getHeight()  { return height; }
    public boolean isEnabled() { return enabled; }
    public boolean isDragging(){ return dragging; }

    public void setX(float x)           { this.x = x; }
    public void setY(float y)           { this.y = y; }
    public void setWidth(float w)       { this.width = w; }
    public void setHeight(float h)      { this.height = h; }
    public void setEnabled(boolean e)   { this.enabled = e; }
    public void toggle()                { this.enabled = !this.enabled; }
}
