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
 * BlockRenderManager Mixin
 *
 * <p>renderBlock()에서 RenderBlockEvent를 dispatch한다.
 * XRayModule의 @EventListener가 이 이벤트를 수신하여
 * 화이트리스트 외 블록의 렌더링을 취소한다.</p>
 *
 * <p><b>Sodium 비호환 주의</b>: Sodium은 자체 청크 렌더러를 사용하므로
 * 이 Mixin의 renderBlock 훅이 호출되지 않습니다.</p>
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
        RenderBlockEvent event = new RenderBlockEvent(state, pos);
        EventBus.INSTANCE.dispatch(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }
}
