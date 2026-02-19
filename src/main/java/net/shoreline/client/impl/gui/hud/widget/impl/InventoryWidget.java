package net.shoreline.client.impl.gui.hud.widget.impl;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.gui.hud.widget.HudWidget;

/**
 * Inventory 위젯 - 핫바 슬롯(0~8)을 핑크 테두리 박스로 표시.
 *
 * <p>스크린샷 하단 중앙의 아이템 슬롯 박스를 구현한다.
 * 슬롯 크기는 16×16 아이콘 + 패딩으로 구성된다.</p>
 */
public class InventoryWidget extends HudWidget {

    /** 슬롯 1개 크기 (Minecraft 기본 아이콘 16px + 패딩 4px = 20px) */
    private static final int SLOT_SIZE = 18;
    /** 내부 패딩 */
    private static final int PADDING = 4;
    /** 표시할 슬롯 수 (핫바=9) */
    private int slotCount = 9;
    /** 슬롯 배경 색상 */
    private static final int SLOT_BG = 0x66000000;

    public InventoryWidget() {
        // 핫바 9칸: 너비 = 9*18 + 2*패딩
        super("Inventory",
                0, 0,
                SLOT_SIZE * 9 + PADDING * 2,
                SLOT_SIZE + PADDING * 2);
        // 화면 하단 중앙에 기본 배치
        positionCenter();
    }

    /** 화면이 로드된 후 중앙 하단으로 초기 위치 설정 */
    public void positionCenter() {
        float sw = mc.getWindow().getScaledWidth();
        float sh = mc.getWindow().getScaledHeight();
        setX(sw / 2f - getWidth() / 2f);
        setY(sh - getHeight() - 50);
    }

    @Override
    public void renderHud(DrawContext context) {
        if (mc.player == null) return;

        float cx = x + PADDING;
        float cy = y + PADDING;

        for (int i = 0; i < slotCount; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            float sx = cx + i * SLOT_SIZE;

            // 슬롯 배경
            RenderManager.rect(context.getMatrices(),
                    sx, cy, SLOT_SIZE, SLOT_SIZE, SLOT_BG);

            // 아이템 렌더링 (DrawContext의 표준 API 사용)
            context.drawItem(stack, (int) sx + 1, (int) cy + 1);
            context.drawItemInSlot(mc.textRenderer, stack, (int) sx + 1, (int) cy + 1);
        }
    }

    @Override
    public void renderEditor(DrawContext context, boolean hovered) {
        // 배경
        RenderManager.rect(context.getMatrices(), x, y, width, height, BG_COLOR);
        // 콘텐츠
        renderHud(context);
        // 핑크 테두리 (스크린샷과 동일)
        RenderManager.borderedRect(context.getMatrices(), x, y, width, height,
                0x00000000, BORDER_COLOR);
    }

    public void setSlotCount(int count) {
        this.slotCount = Math.min(count, 36);
        setWidth(SLOT_SIZE * Math.min(slotCount, 9) + PADDING * 2);
        setHeight(SLOT_SIZE * (int) Math.ceil(slotCount / 9.0) + PADDING * 2);
    }
}
