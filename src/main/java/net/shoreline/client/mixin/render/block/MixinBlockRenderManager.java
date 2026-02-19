package net.shoreline.client.mixin.render.block;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import net.shoreline.client.impl.event.render.block.RenderBlockEvent;
import net.shoreline.client.impl.module.world.XRayModule;
import net.shoreline.eventbus.EventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BlockRenderManager Mixin - XRay 최적화 버전
 *
 * <h2>성능 개선 원리</h2>
 *
 * <p>renderBlock()은 청크 빌드 시 블록당 1회 호출된다.
 * 렌더 거리 16 기준 한 청크(16×16×384)에서 최대 수만 번 호출될 수 있다.</p>
 *
 * <p><b>기존 흐름 (느림):</b></p>
 * <pre>
 *   renderBlock() 호출
 *   → new RenderBlockEvent(state, pos)  ← 객체 힙 할당
 *   → EventBus.dispatch(event)          ← 리스너 리스트 순회
 *   → XRayModule.onRenderBlock(event)   ← 화이트리스트 체크
 *   → GC 압박 누적
 * </pre>
 *
 * <p><b>개선된 흐름 (빠름):</b></p>
 * <pre>
 *   renderBlock() 호출
 *   → XRayModule.getInstance().isEnabled()  ← null 체크 + boolean 읽기
 *   → xray.isXRayBlock(state.getBlock())   ← HashSet.contains() O(1)
 *   → ci.cancel() 또는 통과              ← 객체 할당 없음, GC 없음
 *   → XRay 아닌 경우에만 EventBus dispatch (다른 모듈용)
 * </pre>
 *
 * <p>X-Ray 활성 시 화이트리스트 외 블록은 EventBus dispatch 자체를 스킵하므로
 * 수만 번의 객체 할당과 리스너 순회가 사라진다.</p>
 */
@Mixin(BlockRenderManager.class)
public class MixinBlockRenderManager {

    @Inject(method = "renderBlock", at = @At(value = "HEAD"), cancellable = true)
    private void hookRenderBlock(
            BlockState state,
            BlockPos pos,
            BlockRenderView world,
            MatrixStack matrices,
            VertexConsumer vertexConsumer,
            boolean cull,
            Random random,
            CallbackInfo ci
    ) {
        // ─── XRay 처리 (EventBus 완전 우회) ───────────────────────────────
        // XRay가 활성화되어 있을 때만 실행
        XRayModule xray = XRayModule.getInstance();
        if (xray != null && xray.isEnabled()) {
            if (!xray.isXRayBlock(state.getBlock())) {
                // 화이트리스트에 없는 블록 → 렌더링 취소
                // EventBus dispatch 없음 → 객체 할당 없음 → GC 없음
                ci.cancel();
            }
            // 화이트리스트 블록은 그대로 렌더링 (return 없이 아래로 진행하지 않음)
            // XRay 켜져 있을 때 다른 모듈의 RenderBlockEvent는 받지 않음
            // (필요하다면 아래 else 블록으로 이동 가능)
            return;
        }

        // ─── 다른 모듈용 RenderBlockEvent (XRay 꺼져 있을 때만) ──────────
        // XRay가 꺼진 상태에서만 EventBus dispatch
        // → 불필요한 dispatch 최소화
        RenderBlockEvent event = new RenderBlockEvent(state, pos);
        EventBus.INSTANCE.dispatch(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }
}
