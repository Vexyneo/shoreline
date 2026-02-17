package net.shoreline.client.api.account.msa;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.util.UndashedUuid;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.session.Session;
import net.shoreline.client.Shoreline;
import net.shoreline.client.api.account.msa.callback.BrowserLoginCallback;
import net.shoreline.client.api.account.msa.exception.MSAAuthException;
import net.shoreline.client.api.account.msa.model.MinecraftProfile;
import net.shoreline.client.api.account.msa.model.XboxLiveData;
import net.shoreline.client.api.account.msa.security.PKCEData;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.Map;

/**
 * @author xgraza
 * @since 01/14/24
 * <p>
 * https://mojang-api-docs.gapple.pw/authentication/
 */
public final class MSAAuthenticator
{
    private static final Logger LOGGER = LogManager.getLogger("MSA-Authenticator");
    private static final CloseableHttpClient HTTP_CLIENT = HttpClientBuilder
            .create()
            .setRedirectStrategy(new LaxRedirectStrategy())
            .disableAuthCaching()
            .disableCookieManagement()
            .disableDefaultUserAgent()
            .build();

    private static final String CLIENT_ID = "d1bbd256-3323-4ab7-940e-e8a952ebdb83";
    private static final int PORT = 6969;

    private static final String OAUTH_AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf?response_type=code&client_id=%s&redirect_uri=http://localhost:%s/login&code_challenge=%s&code_challenge_method=S256&scope=XboxLive.signin+offline_access&state=NOT_NEEDED&prompt=select_account";
    private static final String OAUTH_TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String XBOX_LIVE_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XBOX_XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String LOGIN_WITH_XBOX_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MINECRAFT_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private HttpServer localServer;
    private String loginStage = "";
    private boolean serverOpen;

    private PKCEData pkceData;

    public void loginWithBrowser(final BrowserLoginCallback callback)
            throws IOException, URISyntaxException, MSAAuthException
    {
        if (!serverOpen || localServer == null)
        {
            localServer = HttpServer.create();
            localServer.createContext("/login", (ctx) ->
            {
                setLoginStage("Parsing access token from response");
                final Map<String, String> query = parseQueryString(ctx.getRequestURI().getQuery());

                if (query.containsKey("error"))
                {
                    final String errorDescription = query.get("error_description");
                    if (errorDescription != null && !errorDescription.isEmpty())
                    {
                        LOGGER.error("Failed to get token from browser login: {}", errorDescription);
                        writeToWebpage("Failed to get token: " + errorDescription, ctx);
                        setLoginStage(errorDescription);
                    }
                }
                else
                {
                    final String code = query.get("code");
                    if (code != null)
                    {
                        callback.callback(code);
                        writeToWebpage("Successfully got code. You may now close this window", ctx);
                    }
                    else
                    {
                        writeToWebpage("Failed to get code. Please try again.", ctx);
                    }
                }
                serverOpen = false;
                localServer.stop(0);
            });
        }

        pkceData = generateKeys();
        if (pkceData == null)
        {
            throw new MSAAuthException("Failed to generate PKCE keys");
        }

        final String url = String.format(OAUTH_AUTHORIZE_URL, CLIENT_ID, PORT, pkceData.challenge());
        if (Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
        {
            Desktop.getDesktop().browse(new URI(url));
            setLoginStage("Waiting user response...");
        }
        else
        {
            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(url), null);
            LOGGER.warn("BROWSE action not supported on Desktop Environment, copied to clipboard instead.");
            setLoginStage("Link copied to clipboard!");
        }

        if (!serverOpen)
        {
            localServer.bind(new InetSocketAddress(PORT), 1);
            localServer.start();
            serverOpen = true;
        }
    }

    public Session loginWithToken(final String token, final boolean browser) throws MSAAuthException
    {
        setLoginStage("Logging in with Xbox Live...");
        final XboxLiveData data = authWithXboxLive(token, browser);
        requestTokenFromXboxLive(data);
        final String accessToken = loginWithXboxLive(data);
        setLoginStage("Fetching MC profile...");
        final MinecraftProfile profile = fetchMinecraftProfile(accessToken);
        pkceData = null;
        return new Session(profile.username(), UndashedUuid.fromStringLenient(profile.id()), accessToken, Optional.empty(), Optional.empty(), Session.AccountType.MSA);
    }

    public String getLoginToken(final String oauthToken) throws MSAAuthException
    {
        final HttpPost httpPost = new HttpPost(OAUTH_TOKEN_URL);
        httpPost.setHeader("Content-Type", ContentType.APPLICATION_FORM_URLENCODED.getMimeType());
        httpPost.setHeader("Accept", "application/json");
        httpPost.setHeader("Origin", "http://localhost:" + PORT + "/");
        httpPost.setEntity(new StringEntity(
                makeQueryString(new String[][]{
                        new String[]{"client_id", CLIENT_ID},
                        new String[]{"code_verifier", pkceData.verifier()},
                        new String[]{"code", oauthToken},
                        new String[]{"grant_type", "authorization_code"},
                        new String[]{"redirect_uri", "http://localhost:" + PORT + "/login"}
                }), ContentType.create(
                ContentType.APPLICATION_FORM_URLENCODED.getMimeType(), Charset.defaultCharset())));
        try (CloseableHttpResponse response = HTTP_CLIENT.execute(httpPost))
        {
            final String content = EntityUtils.toString(response.getEntity());
            if (content == null || content.isEmpty())
            {
                throw new MSAAuthException("Failed to get login token from MSA OAuth");
            }
            final JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
            if (obj.has("error"))
            {
                throw new MSAAuthException(obj.get("error").getAsString() + ": " + obj.get("error_description").getAsString());
            }
            return obj.get("access_token").getAsString();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            throw new MSAAuthException("Failed to get login token");
        }
    }

    private XboxLiveData authWithXboxLive(final String accessToken, final boolean browser) throws MSAAuthException
    {
        final String body = "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\""
                + (browser ? "d=" : "") + accessToken + "\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}";
        final String content = makePostRequest(XBOX_LIVE_AUTH_URL, body, ContentType.APPLICATION_JSON);
        if (content != null && !content.isEmpty())
        {
            final JsonObject object = JsonParser.parseString(content).getAsJsonObject();

            final XboxLiveData data = new XboxLiveData();
            data.setToken(object.get("Token").getAsString());
            data.setUserHash(object.get("DisplayClaims").getAsJsonObject()
                    .get("xui").getAsJsonArray()
                    .get(0).getAsJsonObject()
                    .get("uhs").getAsString());

            return data;
        }
        throw new MSAAuthException("Failed to authenticate with Xbox Live account");
    }

    private void requestTokenFromXboxLive(XboxLiveData xboxLiveData) throws MSAAuthException
    {
        final String body = "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\""
                + xboxLiveData.getToken() + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}";
        final String content = makePostRequest(XBOX_XSTS_AUTH_URL, body, ContentType.APPLICATION_JSON);
        if (content != null && !content.isEmpty())
        {
            final JsonObject object = JsonParser.parseString(content).getAsJsonObject();
            if (object.has("XErr"))
            {
                throw new MSAAuthException("Xbox Live Error: " + object.get("XErr").getAsString());
            }
            else
            {
                xboxLiveData.setToken(object.get("Token").getAsString());
            }
        }
    }

    private String loginWithXboxLive(final XboxLiveData data) throws MSAAuthException
    {
        try
        {
            final String body = "{\"ensureLegacyEnabled\":true,\"identityToken\":\"XBL3.0 x=" + data.getUserHash() + ";" + data.getToken() + "\"}";
            final String content = makePostRequest(LOGIN_WITH_XBOX_URL, body, ContentType.APPLICATION_JSON);
            if (content != null && !content.isEmpty())
            {
                final JsonObject object = JsonParser.parseString(content).getAsJsonObject();
                if (object.has("errorMessage"))
                {
                    throw new MSAAuthException(object.get("errorMessage").getAsString());
                }
                if (object.has("access_token"))
                {
                    return object.get("access_token").getAsString();
                }
            }
        }
        catch (JsonSyntaxException e)
        {
            e.printStackTrace();
        }
        return null;
    }

    private MinecraftProfile fetchMinecraftProfile(final String accessToken) throws MSAAuthException
    {
        final HttpGet httpGet = new HttpGet(MINECRAFT_PROFILE_URL);
        httpGet.setHeader("Accept", ContentType.APPLICATION_JSON.getMimeType());
        httpGet.setHeader("Authorization", "Bearer " + accessToken);

        try (CloseableHttpResponse response = HTTP_CLIENT.execute(httpGet))
        {
            if (response.getStatusLine().getStatusCode() != 200)
            {
                throw new MSAAuthException("Failed to fetch MC profile: Status code != 200, sc=" + response.getStatusLine().getStatusCode());
            }
            final String rawJSON = EntityUtils.toString(response.getEntity());
            final JsonObject object = JsonParser.parseString(rawJSON).getAsJsonObject();
            if (object.has("error"))
            {
                throw new MSAAuthException("Failed to fetch MC profile: " + object.get("error").getAsString() + " -> " + object.get("errorMessage").getAsString());
            }
            return new MinecraftProfile(object.get("name").getAsString(),
                    object.get("id").getAsString());
        }
        catch (IOException e)
        {
            throw new MSAAuthException(e.getMessage());
        }
    }

    private String makePostRequest(final String url, final String body, final ContentType contentType)
    {
        final HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Content-Type", contentType.getMimeType());
        httpPost.setHeader("Accept", "application/json");
        httpPost.setEntity(new StringEntity(
                body, ContentType.create(
                contentType.getMimeType(),
                Charset.defaultCharset())));
        try (CloseableHttpResponse response = HTTP_CLIENT.execute(httpPost))
        {
            return EntityUtils.toString(response.getEntity());
        }
        catch (IOException e)
        {
            Shoreline.error("Failed to make POST request to {}", url);
            e.printStackTrace();
        }
        return null;
    }

    private void writeToWebpage(final String message, final HttpExchange ext) throws IOException
    {
        final byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        ext.sendResponseHeaders(200, message.length());
        final OutputStream outputStream = ext.getResponseBody();
        outputStream.write(bytes, 0, bytes.length);
        outputStream.close();
    }

    private String makeQueryString(final String[][] parameters)
    {
        final StringJoiner joiner = new StringJoiner("&");
        for (final String[] parameter : parameters)
        {
            joiner.add(parameter[0] + "=" + parameter[1]);
        }
        return joiner.toString();
    }

    private Map<String, String> parseQueryString(final String query)
    {
        final Map<String, String> parameterMap = new LinkedHashMap<>();
        for (final String part : query.split("&"))
        {
            final String[] kv = part.split("=");
            parameterMap.put(kv[0], kv.length == 1 ? null : kv[1]);
        }
        return parameterMap;
    }

    private PKCEData generateKeys()
    {
        try
        {
            final byte[] randomBytes = new byte[32];
            new SecureRandom().nextBytes(randomBytes);

            final String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            final byte[] verifierBytes = verifier.getBytes(StandardCharsets.US_ASCII);
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(verifierBytes, 0, verifierBytes.length);

            final byte[] d = digest.digest();
            final String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(d);
            return new PKCEData(challenge, verifier);
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    public void setLoginStage(String loginStage)
    {
        this.loginStage = loginStage;
    }

    public String getLoginStage()
    {
        return loginStage;
    }
}
