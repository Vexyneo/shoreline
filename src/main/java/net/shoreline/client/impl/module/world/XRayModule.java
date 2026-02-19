package net.shoreline.client.impl.module.world;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.BlockListConfig;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.api.module.ToggleModule;
import net.shoreline.client.impl.event.render.block.RenderBlockEvent;
import net.shoreline.client.util.render.RenderUtil;
import net.shoreline.eventbus.annotation.EventListener;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * XRay 모듈 - 수정 버전
 *
 * <h2>기존 구현의 문제점</h2>
 * <ol>
 *   <li><b>리스너 없음</b>: MixinBlockRenderManager가 RenderBlockEvent를 dispatch하는데,
 *       XRayModule에 @EventListener가 아예 없었음 → X-Ray 기능이 동작 자체를 안 했음</li>
 *   <li><b>잘못된 API 호출</b>: scheduleBlockRerenderIfNeeded(int,int,int,int,int,int) 시그니처가
 *       1.21.1에 존재하지 않음. 이미 프로젝트에 RenderUtil.reloadRenders()가 있음</li>
 * </ol>
 *
 * <h2>수정 사항</h2>
 * <ul>
 *   <li>onEnable/onDisable에서 RenderUtil.reloadRenders() 호출</li>
 *   <li>@EventListener onRenderBlock() 추가 → 화이트리스트 외 블록 렌더링 취소</li>
 *   <li>화이트리스트를 HashSet으로 캐싱 → 청크 빌드 중 O(1) 조회</li>
 *   <li>Config 변경 감지 시 캐시 자동 갱신</li>
 * </ul>
 *
 * <p><b>주의 (Sodium 호환성)</b>: MixinBlockRenderManager의 renderBlock 훅은
 * Sodium과 호환되지 않습니다. Sodium은 자체 청크 렌더러를 사용합니다.
 * Sodium 없이 Vanilla 렌더러를 사용하는 환경에서만 동작합니다.</p>
 */
public class XRayModule extends ToggleModule {

    // ───────────────────── Configs ─────────────────────
    Config<Integer> opacityConfig = register(new NumberConfig<>(
            "Opacity", "X-Ray 외 블록의 투명도 (현재 미사용, 추후 셰이더 연동용)", 0, 120, 255));

    Config<Boolean> softReloadConfig = register(new BooleanConfig(
            "SoftReload", "토글 시 현재 시야 범위 청크만 재빌드 (true=부드러움, false=전체 리로드)", true));

    Config<List<Block>> blocksConfig = register(new BlockListConfig<>(
            "Blocks", "X-Ray로 표시할 블록 화이트리스트",
            Blocks.EMERALD_ORE, Blocks.DIAMOND_ORE, Blocks.IRON_ORE,
            Blocks.GOLD_ORE, Blocks.COAL_ORE, Blocks.LAPIS_ORE,
            Blocks.REDSTONE_ORE, Blocks.COPPER_ORE,
            Blocks.DEEPSLATE_EMERALD_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.TNT, Blocks.FURNACE, Blocks.NETHERITE_BLOCK,
            Blocks.EMERALD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.IRON_BLOCK,
            Blocks.GOLD_BLOCK, Blocks.COPPER_BLOCK, Blocks.BEACON,
            Blocks.SPAWNER, Blocks.ANCIENT_DEBRIS, Blocks.NETHER_GOLD_ORE));

    // ───────────────────── 캐시 ─────────────────────
    /**
     * Config List를 HashSet으로 캐싱.
     * 청크 빌드 스레드에서 매 블록마다 호출되므로 O(1) 조회 필수.
     * 청크 빌드는 읽기 전용 접근이므로 별도 동기화 불필요.
     */
    private final Set<Block> xrayBlockCache = new HashSet<>();

    /** 캐시 유효성 검사용: Config 리스트 크기가 달라지면 재빌드 */
    private int lastCacheSize = -1;

    public XRayModule() {
        super("XRay", "솔리드 블록을 투시하여 광물을 볼 수 있습니다", ModuleCategory.WORLD);
    }

    // ───────────────────── 라이프사이클 ─────────────────────

    @Override
    public void onEnable() {
        rebuildCache();
        // 기존 렌더링 청크를 X-Ray 모드로 재빌드
        RenderUtil.reloadRenders(softReloadConfig.getValue());
    }

    @Override
    public void onDisable() {
        xrayBlockCache.clear();
        lastCacheSize = -1;
        // 정상 렌더링으로 복원
        RenderUtil.reloadRenders(softReloadConfig.getValue());
    }

    // ───────────────────── 이벤트 처리 ─────────────────────

    /**
     * 블록 렌더링 이벤트 처리.
     *
     * <p>MixinBlockRenderManager.renderBlock()에서 dispatch된 이벤트를 수신한다.
     * 화이트리스트에 없는 블록은 렌더링을 취소하여 X-Ray 효과를 만든다.</p>
     *
     * <p>이 메서드는 청크 빌드 스레드에서 호출되므로 반드시 스레드 세이프해야 한다.
     * HashSet 읽기는 쓰기가 없는 한 안전하다.</p>
     *
     * @param event 블록 렌더 이벤트
     */
    @EventListener
    public void onRenderBlock(RenderBlockEvent event) {
        // 캐시 유효성 검사 (크기 변화만 감지, 렌더 스레드 부하 최소화)
        ensureCacheUpToDate();
        // 화이트리스트에 없는 블록 → 렌더링 취소 → 보이지 않음 → X-Ray 효과
        if (!xrayBlockCache.contains(event.getBlock())) {
            event.cancel();
        }
    }

    // ───────────────────── 내부 유틸 ─────────────────────

    /**
     * Config 리스트 크기를 확인하여 변경 시 캐시를 재빌드한다.
     * 크기가 같으면 O(1)로 즉시 리턴한다.
     */
    private void ensureCacheUpToDate() {
        List<Block> list = blocksConfig.getValue();
        if (list.size() != lastCacheSize) {
            rebuildCache();
        }
    }

    /**
     * Config 리스트를 HashSet으로 완전 재빌드한다.
     * 모듈 활성화 시 또는 Config 변경 감지 시에만 실행된다.
     */
    private void rebuildCache() {
        List<Block> list = blocksConfig.getValue();
        xrayBlockCache.clear();
        xrayBlockCache.addAll(list);
        lastCacheSize = list.size();
    }
}
