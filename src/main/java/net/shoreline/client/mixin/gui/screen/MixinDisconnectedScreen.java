package net.shoreline.client.mixin.gui.screen;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.shoreline.client.init.Managers;
import net.shoreline.client.util.Globals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisconnectedScreen.class)
public abstract class MixinDisconnectedScreen extends MixinScreen implements Globals
{
    @Inject(method = "init", at = @At("TAIL"))
    private void hookInit(CallbackInfo ci)
    {
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
