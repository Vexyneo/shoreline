package net.shoreline.client.mixin.world;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.shoreline.client.impl.module.world.XRayModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * X-Ray 핵심 Mixin - Face Culling 단계에서 처리
 *
 * <p>기존 MixinBlockRenderManager 방식의 문제점:</p>
 * <pre>
 *   renderBlock() 훅 → 프레임당 수천 번 호출 → EventBus.dispatch() 오버헤드 누적
 *   → CPU 스파이크 → 렉
 * </pre>
 *
 * <p>새 방식 - {@code isSideInvisibleTo} 훅:</p>
 * <pre>
 *   청크 빌드 시 face culling 단계에서만 호출 (실시간 렌더 루프 아님)
 *   → X-Ray 블록이 아니면 opaque=true로 리턴 → 인접 블록의 face가 컬링됨
 *   → 결과적으로 X-Ray 블록만 보이는 효과
 * </pre>
 *
 * <p>청크 빌드는 백그라운드 스레드에서 실행되므로 {@link XRayModule#isXRayBlock}은
 * 스레드 세이프해야 한다 → HashSet 읽기는 불변 상태이므로 안전.</p>
 *
 * @author optimized
 * @since 2.0
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class MixinAbstractBlockState {

    /**
     * isSideInvisibleTo: 특정 방향 면이 인접 블록에 의해 가려지는지 결정.
     *
     * <p>X-Ray 활성 시:</p>
     * <ul>
     *   <li>화이트리스트 블록 → 정상 렌더 (원래 동작)</li>
     *   <li>그 외 블록 → true 반환 → 이 블록의 모든 면을 인접 블록이 가린다고 판단
     *       → 해당 블록을 렌더링하지 않음 → X-Ray 효과</li>
     * </ul>
     */
    @Inject(
            method = "isSideInvisibleTo",
            at = @At("HEAD"),
            cancellable = true
    )
    private void hookIsSideInvisibleTo(
            BlockState state,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir
    ) {
        XRayModule xray = XRayModule.getInstance();
        // 모듈 인스턴스 null 체크 (초기화 전 안전 처리)
        if (xray == null || !xray.isEnabled()) {
            return;
        }
        // 화이트리스트 블록이면 원래 face culling 로직을 그대로 사용
        if (xray.isXRayBlock(state.getBlock())) {
            return; // 원래 로직 실행
        }
        // 화이트리스트가 아닌 블록은 모든 면이 '가려진다'고 표시
        // → 이 블록 자체가 렌더링되지 않으며, 이 블록 뒤의 블록들이 노출됨
        cir.setReturnValue(true);
    }

    /**
     * isOpaque: 청크 메시 빌드 시 인접 face 컬링에 사용되는 불투명도 판단.
     *
     * <p>X-Ray 활성 시 화이트리스트 외 블록을 '비불투명'으로 처리하여
     * 인접 블록들이 서로를 가리지 않도록 한다.
     * 이를 통해 X-Ray 블록이 장벽 뒤에서도 보이게 된다.</p>
     */
    @Inject(
            method = "isOpaque",
            at = @At("HEAD"),
            cancellable = true
    )
    private void hookIsOpaque(CallbackInfoReturnable<Boolean> cir) {
        XRayModule xray = XRayModule.getInstance();
        if (xray == null || !xray.isEnabled()) {
            return;
        }
        // 현재 BlockState 참조 가져오기 (Mixin 셀프 캐스팅)
        BlockState self = (BlockState) (Object) this;
        // 화이트리스트 블록은 원래 불투명도 유지
        if (xray.isXRayBlock(self.getBlock())) {
            return;
        }
        // 나머지 블록 → 비불투명 → 인접 face가 컬링되지 않음 → X-Ray 블록이 보임
        cir.setReturnValue(false);
    }
}
