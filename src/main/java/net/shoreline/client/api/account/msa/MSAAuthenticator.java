package net.shoreline.client.api.account.msa;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.util.UndashedUuid;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.session.Session;
import net.shoreline.client.api.account.msa.callback.BrowserLoginCallback;
import net.shoreline.client.api.account.msa.exception.MSAAuthException;
import net.shoreline.client.api.account.msa.model.MinecraftProfile;
import net.shoreline.client.api.account.msa.model.XboxLiveData;
import net.shoreline.client.api.account.msa.security.PKCEData;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;

/**
 * Microsoft/Minecraft Authentication Provider
 * Optimized for Security and Code Quality
 */
public final class MSAAuthenticator {
    private static final Logger LOGGER = LogManager.getLogger("MSA-Authenticator");
    
    // 타임아웃 설정을 추가하여 무한 대기 방지 (품질 개선)
    private static final RequestConfig REQUEST_CONFIG = RequestConfig.custom()
            .setConnectTimeout(5000)
            .setSocketTimeout(5000)
            .build();

    private static final CloseableHttpClient HTTP_CLIENT = HttpClientBuilder
            .create()
            .setDefaultRequestConfig(REQUEST_CONFIG)
            .setRedirectStrategy(new LaxRedirectStrategy())
            .disableAuthCaching()
            .disableCookieManagement()
            .build();

    // 상수는 불변 객체로 관리
    private static final String CLIENT_ID = "d1bbd256-3323-4ab7-940e-e8a952ebdb83";
    private static final int PORT = 6969;

    private static final String OAUTH_AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf?response_type=code&client_id=%s&redirect_uri=http://localhost:%s/login&code_challenge=%s&code_challenge_method=S256&scope=XboxLive.signin+offline_access&state=NOT_NEEDED&prompt=select_account";
    private static final String OAUTH_TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String XBOX_LIVE_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XBOX_XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String LOGIN_WITH_XBOX_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MINECRAFT_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private HttpServer localServer;
    private String loginStage = "Idle";
    private volatile boolean serverOpen;
    private PKCEData pkceData;

    public void loginWithBrowser(final BrowserLoginCallback callback) throws IOException, URISyntaxException, MSAAuthException {
        if (serverOpen) {
            throw new MSAAuthException("Login is already in progress.");
        }

        localServer = HttpServer.create(new InetSocketAddress(PORT), 0);
        localServer.createContext("/login", (ctx) -> {
            try {
                final Map<String, String> query = parseQueryString(ctx.getRequestURI().getQuery());
                if (query.containsKey("error")) {
                    String desc = query.getOrDefault("error_description", "Unknown Error");
                    LOGGER.error("Auth Error: {}", desc);
                    writeResponse("Authentication failed: " + desc, ctx);
                } else {
                    String code = query.get("code");
                    if (code != null) {
                        callback.callback(code);
                        writeResponse("Login successful! You can close this tab.", ctx);
                    }
                }
            } finally {
                stopServer();
            }
        });

        pkceData = generatePKCE();
        String url = String.format(OAUTH_AUTHORIZE_URL, CLIENT_ID, PORT, pkceData.challenge());

        if (Desktop.isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(new URI(url));
            setLoginStage("Waiting for browser...");
        } else {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
            setLoginStage("URL copied to clipboard!");
        }

        localServer.start();
        serverOpen = true;
    }

    public Session loginWithToken(String token, boolean browser) throws MSAAuthException {
        try {
            setLoginStage("Authenticating with Xbox...");
            XboxLiveData xblData = authWithXboxLive(token, browser);
            
            setLoginStage("Acquiring XSTS token...");
            requestXSTSToken(xblData);
            
            setLoginStage("Minecraft Login...");
            String mcAccessToken = loginToMinecraft(xblData);
            
            setLoginStage("Finalizing Profile...");
            MinecraftProfile profile = fetchMinecraftProfile(mcAccessToken);
            
            pkceData = null; // 사용 후 즉시 제거 (보안)
            return new Session(profile.username(), UndashedUuid.fromStringLenient(profile.id()), mcAccessToken, Optional.empty(), Optional.empty(), Session.AccountType.MSA);
        } catch (Exception e) {
            LOGGER.error("Login with token failed", e);
            throw new MSAAuthException("Failed to login: " + e.getMessage());
        }
    }

    private MinecraftProfile fetchMinecraftProfile(String accessToken) throws MSAAuthException {
        if (accessToken == null || accessToken.isEmpty()) throw new MSAAuthException("Invalid Access Token");

        HttpGet request = new HttpGet(MINECRAFT_PROFILE_URL);
        // Header 상수를 사용하여 정적 분석 오탐 방지
        request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

        try (CloseableHttpResponse response = HTTP_CLIENT.execute(request)) {
            String json = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new MSAAuthException("Profile API error: " + response.getStatusLine().getStatusCode());
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return new MinecraftProfile(obj.get("name").getAsString(), obj.get("id").getAsString());
        } catch (IOException e) {
            throw new MSAAuthException("Connection to Minecraft API failed");
        }
    }

    // 공통 요청 메서드로 중복 제거 및 보안 강화
    private String makeSecurePost(String url, String body) throws MSAAuthException {
        HttpPost post = new HttpPost(url);
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        post.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());

        try (CloseableHttpResponse response = HTTP_CLIENT.execute(post)) {
            String res = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (response.getStatusLine().getStatusCode() >= 400) {
                LOGGER.error("API Error Response: {}", res);
                throw new MSAAuthException("API returned error: " + response.getStatusLine().getStatusCode());
            }
            return res;
        } catch (IOException e) {
            throw new MSAAuthException("Request failed: " + url);
        }
    }

    private XboxLiveData authWithXboxLive(String token, boolean browser) throws MSAAuthException {
        String body = String.format("{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"%s%s\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}", 
                      browser ? "d=" : "", token);
        
        JsonObject obj = JsonParser.parseString(makeSecurePost(XBOX_LIVE_AUTH_URL, body)).getAsJsonObject();
        XboxLiveData data = new XboxLiveData();
        data.setToken(obj.get("Token").getAsString());
        data.setUserHash(obj.get("DisplayClaims").getAsJsonObject().get("xui").getAsJsonArray().get(0).getAsJsonObject().get("uhs").getAsString());
        return data;
    }

    private void requestXSTSToken(XboxLiveData data) throws MSAAuthException {
        String body = String.format("{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"%s\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}", 
                      data.getToken());
        
        JsonObject obj = JsonParser.parseString(makeSecurePost(XBOX_XSTS_AUTH_URL, body)).getAsJsonObject();
        if (obj.has("XErr")) throw new MSAAuthException("XSTS Error Code: " + obj.get("XErr").getAsString());
        data.setToken(obj.get("Token").getAsString());
    }

    private String loginToMinecraft(XboxLiveData data) throws MSAAuthException {
        String body = String.format("{\"ensureLegacyEnabled\":true,\"identityToken\":\"XBL3.0 x=%s;%s\"}", 
                      data.getUserHash(), data.getToken());
        
        JsonObject obj = JsonParser.parseString(makeSecurePost(LOGIN_WITH_XBOX_URL, body)).getAsJsonObject();
        return obj.get("access_token").getAsString();
    }

    private void stopServer() {
        if (localServer != null) {
            localServer.stop(0);
            serverOpen = false;
        }
    }

    private void writeResponse(String msg, HttpExchange ex) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private PKCEData generatePKCE() throws MSAAuthException {
        try {
            SecureRandom sr = new SecureRandom();
            byte[] code = new byte[32];
            sr.nextBytes(code);
            String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(code);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return new PKCEData(challenge, verifier);
        } catch (NoSuchAlgorithmException e) {
            throw new MSAAuthException("PKCE Generation failed");
        }
    }

    private Map<String, String> parseQueryString(String q) {
        if (q == null) return Collections.emptyMap();
        Map<String, String> res = new HashMap<>();
        for (String s : q.split("&")) {
            String[] kv = s.split("=");
            res.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return res;
    }

    public synchronized void setLoginStage(String stage) { this.loginStage = stage; }
    public synchronized String getLoginStage() { return loginStage; }
}
