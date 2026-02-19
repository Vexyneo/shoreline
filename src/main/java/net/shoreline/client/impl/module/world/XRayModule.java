package net.shoreline.client.impl.module.world;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.BlockListConfig;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.api.module.ToggleModule;
import net.shoreline.client.util.render.RenderUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * XRay 모듈 - 최종 최적화 버전
 *
 * <h2>아키텍처</h2>
 * <p>이 모듈은 {@code @EventListener}를 사용하지 않는다.
 * 대신 {@link net.shoreline.client.mixin.render.block.MixinBlockRenderManager}가
 * 청크 빌드 중 {@link #isXRayBlock(Block)}을 직접 호출한다.</p>
 *
 * <p>이 방식이 EventBus 방식보다 빠른 이유:</p>
 * <pre>
 *   EventBus: new 객체 할당 → GC 압박 → 리스너 순회 → 가상 디스패치
 *   직접호출: 정적 참조 → HashSet.contains() → 끝
 * </pre>
 *
 * <h2>스레드 안전성</h2>
 * <p>청크 빌드는 백그라운드 워커 스레드에서 실행된다.
 * {@link #xrayBlockCache}는 모듈 활성화/비활성화 시(메인 스레드)에만 쓰기가 발생하고,
 * 청크 빌드 시(워커 스레드)에는 읽기만 발생한다.</p>
 * <p>가시성 보장: {@code volatile Set} 참조로 처리한다.</p>
 */
public class XRayModule extends ToggleModule {

    // ───────────────────── Singleton ─────────────────────
    private static volatile XRayModule INSTANCE;

    /**
     * MixinBlockRenderManager에서 직접 호출한다.
     * 청크 빌드 스레드에서 접근하므로 volatile로 선언.
     */
    public static XRayModule getInstance() {
        return INSTANCE;
    }

    // ───────────────────── Configs ─────────────────────
    Config<Integer> opacityConfig = register(new NumberConfig<>(
            "Opacity", "X-Ray 외 블록 투명도 (추후 셰이더 연동)", 0, 120, 255));

    Config<Boolean> softReloadConfig = register(new BooleanConfig(
            "SoftReload", "토글 시 시야 범위 청크만 재빌드 (false = 전체 리로드)", true));

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
     * volatile 참조: 메인 스레드가 새 Set을 쓸 때 워커 스레드가 즉시 최신 값을 읽음.
     * Set 내부 데이터는 불변이므로 참조만 volatile이면 충분하다.
     */
    private volatile Set<Block> xrayBlockCache = new HashSet<>();

    public XRayModule() {
        super("XRay", "솔리드 블록을 투시해 광물을 볼 수 있습니다", ModuleCategory.WORLD);
        INSTANCE = this;
    }

    // ───────────────────── 라이프사이클 ─────────────────────

    @Override
    public void onEnable() {
        // 캐시 빌드 후 청크 재빌드 (메인 스레드)
        rebuildCache();
        RenderUtil.reloadRenders(softReloadConfig.getValue());
    }

    @Override
    public void onDisable() {
        // 빈 Set으로 교체 (volatile write → 워커 스레드 즉시 반영)
        xrayBlockCache = new HashSet<>();
        RenderUtil.reloadRenders(softReloadConfig.getValue());
    }

    // ───────────────────── 공개 API ─────────────────────

    /**
     * 해당 블록이 X-Ray 화이트리스트에 있는지 확인한다.
     *
     * <p>이 메서드는 {@link net.shoreline.client.mixin.render.block.MixinBlockRenderManager}에서
     * 청크 빌드 스레드에서 직접 호출된다. 반드시 스레드 세이프하고 빠르게 리턴해야 한다.</p>
     *
     * <p>현재 구현: HashSet.contains() = O(1), 동기화 없음 (읽기 전용)</p>
     *
     * @param block 확인할 블록
     * @return 화이트리스트에 있으면 true
     */
    public boolean isXRayBlock(Block block) {
        // Config 변경 감지: 리스트 크기로 1차 체크 (렌더 스레드 부하 최소화)
        List<Block> configList = blocksConfig.getValue();
        if (configList.size() != xrayBlockCache.size()) {
            // 메인 스레드가 아니면 캐시 갱신을 스킵 (다음 메인 틱에 반영됨)
            // → 렌더 스레드에서 synchronized 방지
            if (net.minecraft.client.MinecraftClient.getInstance().isOnThread()) {
                rebuildCache();
            }
        }
        return xrayBlockCache.contains(block);
    }

    // ───────────────────── 내부 유틸 ─────────────────────

    /**
     * Config 리스트를 새 HashSet으로 재빌드하고 volatile 참조를 교체한다.
     * 메인 스레드에서만 호출할 것.
     */
    private void rebuildCache() {
        List<Block> list = blocksConfig.getValue();
        // 새 Set 생성 후 volatile 참조 교체 → 워커 스레드가 원자적으로 최신 캐시 읽음
        Set<Block> newCache = new HashSet<>(list);
        xrayBlockCache = newCache;
    }
}
