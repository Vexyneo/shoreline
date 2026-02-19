package net.shoreline.client.impl.module.render;

import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.EnumConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.api.module.ToggleModule;
import net.shoreline.client.impl.event.gui.hud.RenderOverlayEvent;
import net.shoreline.client.impl.gui.hud.widget.impl.MapWidget;
import net.shoreline.eventbus.annotation.EventListener;

/**
 * Map 모듈 - 미니맵을 HUD에 표시한다.
 *
 * <p>활성화하면 {@link MapWidget}이 HUD에 렌더링된다.
 * HUD 에디터에서 위치를 자유롭게 드래그할 수 있다.</p>
 */
public class MapModule extends ToggleModule {

    private static MapModule INSTANCE;

    // ─── Configs ─────────────────────────────────────────────────
    Config<Integer> radiusConfig = register(new NumberConfig<>(
            "Radius", "맵에 표시할 반경 (블록)", 16, 64, 256));

    Config<Integer> sizeConfig = register(new NumberConfig<>(
            "Size", "미니맵 크기 (픽셀)", 64, 128, 256));

    Config<Boolean> playerMarkersConfig = register(new BooleanConfig(
            "PlayerMarkers", "다른 플레이어 위치 표시", true));

    Config<Boolean> compassConfig = register(new BooleanConfig(
            "Compass", "나침반(N 방향) 표시", true));

    Config<Boolean> rotatingConfig = register(new BooleanConfig(
            "Rotating", "플레이어 방향으로 맵 회전", false));

    Config<Boolean> coordsConfig = register(new BooleanConfig(
            "Coords", "맵 아래에 좌표 표시", true));

    // ─── 위젯 인스턴스 ───────────────────────────────────────────
    private final MapWidget mapWidget = new MapWidget();

    public MapModule() {
        super("Map", "미니맵을 HUD에 표시합니다", ModuleCategory.RENDER);
        INSTANCE = this;
    }

    public static MapModule getInstance() {
        return INSTANCE;
    }

    @Override
    public void onEnable() {
        syncConfig();
    }

    // ─── 렌더링 ──────────────────────────────────────────────────

    @EventListener
    public void onRenderOverlay(RenderOverlayEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.options.hudHidden) return;
        syncConfig();
        mapWidget.renderHud(event.getContext());
    }

    /** Config 값을 위젯에 동기화 */
    private void syncConfig() {
        mapWidget.setRadius(radiusConfig.getValue());
        mapWidget.setShowOtherPlayers(playerMarkersConfig.getValue());
        mapWidget.setShowCompass(compassConfig.getValue());
        mapWidget.setRotating(rotatingConfig.getValue());
    }

    public MapWidget getMapWidget() {
        return mapWidget;
    }
}
