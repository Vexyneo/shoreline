package net.shoreline.client.mixin.gui.screen;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.text.Text;
import net.shoreline.client.impl.module.misc.HeadlessMCModule;
import net.shoreline.client.init.Managers;
import net.shoreline.client.util.Globals;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MixinDisconnectedScreen - HeadlessMC AutoReconnect + 수동 Reconnect 버튼.
 *
 * DisconnectedScreen에 "Reconnect" 버튼을 추가한다.
 * - HeadlessMC AutoReconnect 활성: 자동 재접속 (버튼 클릭으로도 즉시 재접속)
 * - HeadlessMC 비활성: 버튼 클릭으로 수동 재접속
 */
@Mixin(DisconnectedScreen.class)
public abstract class MixinDisconnectedScreen extends MixinScreen implements Globals
{
    @Shadow @Final private Screen parent;
    @Shadow @Final private DirectionalLayoutWidget layout;

    @Shadow protected abstract void initTabNavigation();

    @Inject(method = "init", at = @At("TAIL"))
    private void hookInit(CallbackInfo ci)
    {
        // "Reconnect" 버튼 - DisconnectedScreen 하단에 추가
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Reconnect"),
                button -> manualReconnect())
                .dimensions(width / 2 - 100, height - 52, 200, 20)
                .build());
    }

    @Unique
    private void manualReconnect()
    {
        var addr = Managers.NETWORK.getAddress();
        var info = Managers.NETWORK.getInfo();
        if (addr != null && info != null)
        {
            ConnectScreen.connect(new TitleScreen(), mc, addr, info, false, null);
        }
    }
}
