package net.shoreline.client.impl.gui.account;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.session.Session;
import net.minecraft.text.Text;
import net.shoreline.client.Shoreline;
import net.shoreline.client.api.account.msa.exception.MSAAuthException;
import net.shoreline.client.api.account.type.impl.CrackedAccount;
import net.shoreline.client.api.account.type.impl.MicrosoftAccount;
import net.shoreline.client.impl.manager.client.AccountManager;
import net.shoreline.client.init.Managers;

import java.awt.*;
import java.io.IOException;
import java.net.URISyntaxException;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/**
 * @author xgraza
 * @since 03/28/24
 */
public final class AccountAddAccountScreen extends Screen
{
    private final Screen parent;
    private TextFieldWidget username;

    public AccountAddAccountScreen(final Screen parent)
    {
        super(Text.of("Add or Create an Alt Account"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        clearChildren();
        addDrawableChild(username = new TextFieldWidget(client.textRenderer, width / 2 - 75, height / 2 - 20, 150, 20, Text.of("")));
        username.setPlaceholder(Text.of("Username (Cracked)..."));

        addDrawableChild(ButtonWidget.builder(Text.of("Add Cracked"), (action) ->
        {
            final String accountUsername = username.getText();
            if (accountUsername.length() >= 3)
            {
                Managers.ACCOUNT.register(new CrackedAccount(accountUsername));
                client.setScreen(parent);
            }
        }).dimensions(width / 2 - 72, height / 2 + 5, 145, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.of("Browser Login..."), (action) ->
        {
            try
            {
                AccountManager.MSA_AUTHENTICATOR.loginWithBrowser((token) ->
                        Shoreline.EXECUTOR.execute(() ->
                        {
                            final MicrosoftAccount account = new MicrosoftAccount(token);
                            final Session session = account.login();
                            if (session != null)
                            {
                                Managers.ACCOUNT.setSession(session);
                                Managers.ACCOUNT.register(account);
                                client.setScreen(parent);
                            }
                            else
                            {
                                AccountManager.MSA_AUTHENTICATOR.setLoginStage("Could not login to account");
                            }
                        }));
            }
            catch (IOException | URISyntaxException | MSAAuthException e)
            {
                e.printStackTrace();
            }
        }).dimensions(width / 2 - 72, height / 2 + 5 + 22, 145, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.of("Go Back"), (action) -> client.setScreen(parent))
                .dimensions(width / 2 - 72, height / 2 + 5 + (22 * 2), 145, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        super.render(context, mouseX, mouseY, delta);
        context.drawTextWithShadow(client.textRenderer, "*",
                username.getX() - 10,
                username.getY() + (username.getHeight() / 2) - (client.textRenderer.fontHeight / 2),
                (username.getText().length() >= 3 ? Color.green : Color.red).getRGB());
        context.drawCenteredTextWithShadow(client.textRenderer,
                "Add an Account", width / 2, height / 2 - 120, -1);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        if (keyCode == GLFW_KEY_ESCAPE)
        {
            client.setScreen(parent);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
