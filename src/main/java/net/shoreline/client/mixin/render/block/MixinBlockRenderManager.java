package net.shoreline.client.mixin.render.block;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import net.shoreline.client.impl.event.render.block.RenderBlockEvent;
import net.shoreline.eventbus.EventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BlockRenderManager Mixin - 최적화 버전
 *
 * <p>기존 구현의 문제:</p>
 * <pre>
 *   renderBlock()는 청크 빌드 중 수십만 번 호출됨.
 *   매 호출마다 EventBus.dispatch(new RenderBlockEvent(...)) → 객체 할당 + GC 압박 + 리스너 순회
 *   → 청크 리빌드 시 수백ms 지연 → 심각한 렉
 * </pre>
 *
 * <p>X-Ray 기능은 이제 {@link MixinAbstractBlockState}의 face culling 훅이 담당한다.
 * 이 Mixin은 XRay 외 다른 모듈이 필요로 할 수 있으므로 이벤트는 유지하되,
 * XRay 모듈이 enabled일 때는 이 경로를 건드리지 않는다.</p>
 *
 * @author optimized
 * @since 2.0
 */
@Mixin(BlockRenderManager.class)
public class MixinBlockRenderManager {

    /**
     * renderBlock 훅 - XRay와 완전히 분리됨.
     *
     * <p>이 이벤트를 사용하는 다른 모듈이 있을 수 있으므로 이벤트 dispatch는 유지한다.
     * 단, XRayModule은 더 이상 이 이벤트를 수신하지 않는다.</p>
     *
     * <p>주의: Sodium 모드와 호환되지 않을 수 있다. Sodium은 자체 청크 렌더러를 사용하므로
     * 이 Mixin의 주입 대상이 호출되지 않는다.</p>
     */
    @Inject(
            method = "renderBlock",
            at = @At("HEAD"),
            cancellable = true
    )
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
        // XRay는 이제 MixinAbstractBlockState에서 face culling 단계에서 처리.
        // 여기서는 다른 모듈을 위한 이벤트만 dispatch한다.
        RenderBlockEvent event = new RenderBlockEvent(state, pos);
        EventBus.INSTANCE.dispatch(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }
}
