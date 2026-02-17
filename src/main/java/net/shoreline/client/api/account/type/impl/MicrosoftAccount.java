package net.shoreline.client.api.account.type.impl;

import com.google.gson.JsonObject;
import net.minecraft.client.session.Session;
import net.shoreline.client.api.account.msa.exception.MSAAuthException;
import net.shoreline.client.api.account.type.MinecraftAccount;
import net.shoreline.client.impl.manager.client.AccountManager;

/**
 * @author xgraza
 * @since 03/31/24
 */
public final class MicrosoftAccount implements MinecraftAccount
{
    private String accessToken, username;

    /**
     * Create a MicrosoftAccount instance using a previously saved access token
     *
     * @param accessToken the access token
     * @throws RuntimeException if access token is null or empty
     */
    public MicrosoftAccount(final String accessToken)
    {
        if (accessToken == null || accessToken.isEmpty())
        {
            throw new RuntimeException("Access token should not be null");
        }
        this.accessToken = accessToken;
    }

    @Override
    public Session login()
    {
        Session session = null;
        try
        {
            if (accessToken != null)
            {
                if (accessToken.startsWith("M."))
                {
                    accessToken = AccountManager.MSA_AUTHENTICATOR.getLoginToken(accessToken);
                }
                session = AccountManager.MSA_AUTHENTICATOR.loginWithToken(accessToken, true);
            }
        }
        catch (MSAAuthException e)
        {
            e.printStackTrace();
            AccountManager.MSA_AUTHENTICATOR.setLoginStage(e.getMessage());
            return null;
        }

        if (session != null)
        {
            AccountManager.MSA_AUTHENTICATOR.setLoginStage("");
            username = session.getUsername();
            return session;
        }
        return null;
    }

    @Override
    public JsonObject toJSON()
    {
        final JsonObject object = MinecraftAccount.super.toJSON();
        if (accessToken == null)
        {
            throw new RuntimeException("Access token is null for a MSA account");
        }
        object.addProperty("token", accessToken);
        return object;
    }

    @Override
    public String username()
    {
        return username != null ? username : "";
    }

    public String getUsernameOrNull()
    {
        return username;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    public String getAccessToken()
    {
        return accessToken;
    }
}
