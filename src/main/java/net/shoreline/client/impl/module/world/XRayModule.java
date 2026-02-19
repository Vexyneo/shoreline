package net.shoreline.client.impl.module.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.BlockListConfig;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.api.module.ToggleModule;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * XRay 모듈 - 완전 재설계
 *
 * <p>기존 구현의 문제점:</p>
 * <ul>
 *   <li>MixinBlockRenderManager의 renderBlock 훅이 프레임당 수천 번 호출되어 심각한 렉 유발</li>
 *   <li>XRayModule이 RenderBlockEvent 리스너 자체가 없어 실제로 동작도 안 함</li>
 *   <li>이벤트 dispatch 오버헤드가 누적되어 프레임 타임을 급격히 증가시킴</li>
 * </ul>
 *
 * <p>새 구현 전략:</p>
 * <ul>
 *   <li>{@link #isXRayBlock(Block)}를 static 메서드로 노출 → Mixin에서 직접 호출 (이벤트 버스 제거)</li>
 *   <li>화이트리스트를 {@link HashSet}으로 캐싱 → O(1) 조회, Config 변경 시에만 재빌드</li>
 *   <li>토글 시에만 청크 리로드, 렌더 루프에서 호출 최소화</li>
 *   <li>MixinBlockRenderManager의 무거운 이벤트 dispatch 대신 {@link MixinAbstractBlockState}에서
 *       {@code isOpaque()} 오버라이드로 face culling 단계에서 처리 → 비가시 블록은 아예 렌더링 안 됨</li>
 * </ul>
 *
 * @author optimized
 * @since 2.0
 */
public class XRayModule extends ToggleModule {

    // ───────────────────── Singleton ─────────────────────
    private static XRayModule INSTANCE;

    public static XRayModule getInstance() {
        return INSTANCE;
    }

    // ───────────────────── Configs ─────────────────────
    Config<Integer> opacityConfig = register(new NumberConfig<>(
            "Opacity", "X-Ray가 아닌 블록의 투명도 (0=완전 투명, 255=불투명)", 0, 0, 255));

    Config<Boolean> softReloadConfig = register(new BooleanConfig(
            "SoftReload", "토글 시 청크를 부드럽게 리로드", true));

    Config<List<Block>> blocksConfig = register(new BlockListConfig<>(
            "Blocks", "X-Ray로 볼 블록 화이트리스트",
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

    // ───────────────────── 캐시된 화이트리스트 ─────────────────────
    /**
     * Config List → HashSet 캐시.
     * Config가 변경될 때만 재빌드되며, Mixin에서 O(1)로 조회한다.
     */
    private final Set<Block> xrayBlockCache = new HashSet<>();

    // 마지막으로 캐시를 빌드했을 때의 리스트 크기 (변경 감지용)
    private int lastCacheSize = -1;

    public XRayModule() {
        super("XRay", "솔리드 블록을 투시해 광물을 볼 수 있게 합니다", ModuleCategory.WORLD);
        INSTANCE = this;
    }

    // ───────────────────── 토글 ─────────────────────

    @Override
    public void onEnable() {
        rebuildCache();
        reloadChunks();
    }

    @Override
    public void onDisable() {
        xrayBlockCache.clear();
        lastCacheSize = -1;
        reloadChunks();
    }

    // ───────────────────── 공개 API (Mixin에서 호출) ─────────────────────

    /**
     * 해당 블록이 X-Ray 화이트리스트에 있는지 확인한다.
     *
     * <p>이 메서드는 {@link net.shoreline.client.mixin.world.MixinAbstractBlockState}에서
     * {@code isOpaque()}, {@code isSideInvisibleTo()} 등의 시점에 호출된다.
     * 렌더 스레드에서 호출되므로 절대로 blocking 연산을 수행하지 않아야 한다.</p>
     *
     * @param block 검사할 블록
     * @return 화이트리스트에 포함되면 true
     */
    public boolean isXRayBlock(Block block) {
        ensureCacheUpToDate();
        return xrayBlockCache.contains(block);
    }

    /**
     * X-Ray 활성 상태에서 특정 위치의 블록을 렌더링해야 하는지 반환한다.
     *
     * <p>Mixin의 face culling 훅 {@code shouldDrawSide}에서 호출된다.</p>
     *
     * @param state 블록 상태
     * @param pos 블록 위치
     * @param direction 렌더링 면 방향
     * @return 렌더링해야 하면 true
     */
    public boolean shouldRender(BlockState state, BlockPos pos, Direction direction) {
        ensureCacheUpToDate();
        return xrayBlockCache.contains(state.getBlock());
    }

    /**
     * 현재 Opacity 설정값을 0.0~1.0 범위로 반환한다.
     */
    public float getOpacityAlpha() {
        return opacityConfig.getValue() / 255.0f;
    }

    // ───────────────────── 내부 유틸 ─────────────────────

    /**
     * Config 리스트의 변경을 감지하고 필요시 캐시를 재빌드한다.
     * 렌더 스레드에서 매 블록마다 호출될 수 있으므로 변경이 없으면 즉시 리턴한다.
     */
    private void ensureCacheUpToDate() {
        List<Block> configList = blocksConfig.getValue();
        // 크기 비교만으로 1차 필터링 → 변경 없으면 O(1) 리턴
        if (configList.size() != lastCacheSize) {
            rebuildCache();
        }
    }

    /**
     * Config 리스트를 HashSet으로 완전히 재빌드한다.
     * 활성화 시 또는 Config 변경 감지 시에만 호출된다.
     */
    private void rebuildCache() {
        List<Block> configList = blocksConfig.getValue();
        xrayBlockCache.clear();
        xrayBlockCache.addAll(configList);
        lastCacheSize = configList.size();
    }

    /**
     * 청크 렌더를 리로드해 변경 사항을 즉시 반영한다.
     * 메인 스레드에서만 호출해야 한다.
     */
    private void reloadChunks() {
        if (mc.worldRenderer == null) {
            return;
        }
        if (softReloadConfig.getValue()) {
            // scheduleBlockRenders 대신 reload 전체를 피하고
            // WorldRenderer의 부분 갱신만 요청한다
            mc.worldRenderer.scheduleBlockRerenderIfNeeded(
                    (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ(),
                    (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ()
            );
            // 전체 가시 범위 청크 재빌드
            mc.worldRenderer.reload();
        } else {
            mc.worldRenderer.reload();
        }
    }
}