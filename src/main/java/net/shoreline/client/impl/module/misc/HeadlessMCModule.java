package net.shoreline.client.impl.module.misc;

import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.config.setting.StringConfig;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.api.module.ToggleModule;
import net.shoreline.client.impl.event.FinishLoadingEvent;
import net.shoreline.client.impl.event.TickEvent;
import net.shoreline.client.impl.event.network.DisconnectEvent;
import net.shoreline.client.impl.event.network.GameJoinEvent;
import net.shoreline.client.init.Managers;
import net.shoreline.client.util.chat.ChatUtil;
import net.shoreline.eventbus.annotation.EventListener;
import net.shoreline.eventbus.event.StageEvent;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HeadlessMCModule
 *
 * 마인크래프트를 GUI 없이 봇처럼 운영하는 기능.
 * 2b2t / 2b2t Korea 같은 서버에서 클라이언트를
 * 무인으로 장기 실행할 때 사용한다.
 *
 * == 기능 목록 ==
 *
 * 1. AutoConnect  - 게임 로딩 완료 시 설정된 서버에 자동 접속
 * 2. AutoReconnect - 서버 연결 끊겼을 때 딜레이 후 자동 재접속
 * 3. Console I/O  - 별도 스레드에서 stdin 명령어 입력을 서버/클라이언트로 전달
 *                    '/'  prefix → 서버 명령어
 *                    '.'  prefix → 클라이언트 명령어 (Shoreline command prefix)
 *                    그 외        → 서버 채팅 메시지
 * 4. TCP Remote   - 설정 포트에 TCP 소켓 서버를 열어 원격에서 명령어 전송
 *                   (telnet / nc 등으로 접속 가능, 비밀번호 보호)
 * 5. AutoModules  - 서버 접속 시 지정한 모듈들을 자동으로 활성화
 * 6. LowFPS Mode  - 백그라운드 실행 시 렌더링 최소화 (UnfocusedFPS 강제)
 * 7. Status Log   - 주기적으로 플레이어 상태(좌표, 체력)를 콘솔에 출력
 *
 * @author shoreline (rebuilt for headless operation)
 */
public class HeadlessMCModule extends ToggleModule
{
    private static HeadlessMCModule INSTANCE;

    // ── 서버 접속 설정 ─────────────────────────────────────────────
    /** 자동 접속할 서버 IP (예: "2b2t.org", "2b2t.xn--3e0b707e") */
    Config<String> serverIpConfig = register(new StringConfig(
            "ServerIP", "Server IP to auto-connect to", "2b2t.org"));

    /** 서버 포트 */
    Config<Integer> serverPortConfig = register(new NumberConfig<>(
            "ServerPort", "Server port", 1, 25565, 65535));

    // ── AutoConnect ────────────────────────────────────────────────
    /** 게임 시작 시 자동 서버 접속 활성화 여부 */
    Config<Boolean> autoConnectConfig = register(new BooleanConfig(
            "AutoConnect", "Automatically connects to server on game start", false));

    /** 자동 접속 전 대기 시간 (초) - 리소스 팩 로딩 등을 기다리기 위해 */
    Config<Integer> connectDelayConfig = register(new NumberConfig<>(
            "ConnectDelay", "Seconds to wait before auto-connecting", 0, 3, 30,
            () -> autoConnectConfig.getValue()));

    // ── AutoReconnect ──────────────────────────────────────────────
    public Config<Boolean> autoReconnectConfig = register(new BooleanConfig(
            "AutoReconnect", "Automatically reconnects after disconnect", true));

    Config<Integer> reconnectDelayConfig = register(new NumberConfig<>(
            "ReconnectDelay", "Seconds to wait before reconnecting", 0, 5, 300,
            () -> autoReconnectConfig.getValue()));

    Config<Integer> maxRetriesConfig = register(new NumberConfig<>(
            "MaxRetries", "Maximum reconnect attempts (0 = unlimited)", 0, 0, 100,
            () -> autoReconnectConfig.getValue()));

    // ── Console I/O ────────────────────────────────────────────────
    Config<Boolean> consoleConfig = register(new BooleanConfig(
            "Console", "Enables stdin console command input", true));

    // ── TCP Remote Control ─────────────────────────────────────────
    Config<Boolean> tcpRemoteConfig = register(new BooleanConfig(
            "TCPRemote", "Opens a TCP server for remote command input", false));

    Config<Integer> tcpPortConfig = register(new NumberConfig<>(
            "TCPPort", "Port for TCP remote control", 1024, 25566, 65535,
            () -> tcpRemoteConfig.getValue()));

    /** TCP 원격 접속 비밀번호 (빈 문자열 = 비밀번호 없음) */
    Config<String> tcpPasswordConfig = register(new StringConfig(
            "TCPPassword", "Password for TCP remote (empty = no auth)", "",
            () -> tcpRemoteConfig.getValue()));

    // ── AutoModules ────────────────────────────────────────────────
    Config<Boolean> autoModulesConfig = register(new BooleanConfig(
            "AutoModules", "Enables specified modules on server join", false));

    /**
     * 접속 시 자동 활성화할 모듈 목록.
     * 쉼표로 구분. 예: "AutoTotem,AutoEat,AutoLog"
     */
    Config<String> moduleListConfig = register(new StringConfig(
            "ModuleList", "Comma-separated modules to enable on join",
            "AutoTotem,AutoEat",
            () -> autoModulesConfig.getValue()));

    // ── LowFPS Mode ────────────────────────────────────────────────
    Config<Boolean> lowFpsConfig = register(new BooleanConfig(
            "LowFPS", "Limits FPS to reduce CPU/GPU usage in headless mode", false));

    Config<Integer> lowFpsLimitConfig = register(new NumberConfig<>(
            "FPSLimit", "Target FPS in headless mode", 1, 15, 60,
            () -> lowFpsConfig.getValue()));

    // ── Status Log ─────────────────────────────────────────────────
    Config<Boolean> statusLogConfig = register(new BooleanConfig(
            "StatusLog", "Periodically logs player status to console", true));

    Config<Integer> statusIntervalConfig = register(new NumberConfig<>(
            "StatusInterval", "Status log interval in seconds", 1, 60, 3600,
            () -> statusLogConfig.getValue()));

    // ── 내부 상태 ──────────────────────────────────────────────────

    /** 게임이 처음 로딩됐는지 여부 (AutoConnect 1회 실행용) */
    private volatile boolean firstLoad = true;

    /** AutoConnect 예약 시각 (ms) */
    private volatile long connectAt = -1;

    /** 재접속 시도 횟수 */
    private volatile int retryCount = 0;

    /** 재접속 예약 시각 (ms) */
    private volatile long reconnectAt = -1;

    /** 마지막 접속한 서버 주소/정보 (재접속용) */
    private volatile ServerAddress lastAddress;
    private volatile ServerInfo lastInfo;

    /** 상태 로그 마지막 출력 시각 */
    private volatile long lastStatusLog = 0;

    // 스레드 관리
    private final ExecutorService threadPool = Executors.newCachedThreadPool(r ->
    {
        Thread t = new Thread(r, "HeadlessMC-Worker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean consoleRunning = new AtomicBoolean(false);
    private final AtomicBoolean tcpRunning     = new AtomicBoolean(false);
    private ServerSocket tcpServerSocket;

    public HeadlessMCModule()
    {
        super("HeadlessMC", "Runs Minecraft as a headless bot (auto-connect, console control, remote)",
                ModuleCategory.MISCELLANEOUS);
        INSTANCE = this;
    }

    public static HeadlessMCModule getInstance()
    {
        return INSTANCE;
    }

    // ── 모듈 활성화/비활성화 ────────────────────────────────────────

    @Override
    public void onEnable()
    {
        log("HeadlessMC 활성화됨.");

        // Console I/O 스레드 시작
        if (consoleConfig.getValue() && !consoleRunning.get())
            startConsoleThread();

        // TCP Remote 서버 시작
        if (tcpRemoteConfig.getValue() && !tcpRunning.get())
            startTcpServer();

        // 이미 서버에 접속돼 있다면 서버 주소 저장
        if (mc.getNetworkHandler() != null)
        {
            lastAddress = Managers.NETWORK.getAddress();
            lastInfo    = Managers.NETWORK.getInfo();
        }
    }

    @Override
    public void onDisable()
    {
        log("HeadlessMC 비활성화됨.");
        stopTcpServer();
        connectAt   = -1;
        reconnectAt = -1;
        retryCount  = 0;
    }

    // ── 이벤트 ─────────────────────────────────────────────────────

    /**
     * 게임 로딩 완료 이벤트.
     * AutoConnect가 켜져 있으면 connectDelay초 후 접속을 예약한다.
     */
    @EventListener
    public void onFinishLoading(FinishLoadingEvent event)
    {
        if (!isEnabled() || !autoConnectConfig.getValue()) return;
        if (!firstLoad) return;
        firstLoad = false;

        long delay = connectDelayConfig.getValue() * 1000L;
        connectAt = System.currentTimeMillis() + delay;
        log("AutoConnect: {}초 후 {} 서버에 접속합니다.",
                connectDelayConfig.getValue(), serverIpConfig.getValue());
    }

    /**
     * 서버 접속 성공 이벤트.
     * 접속한 서버 정보를 저장하고, AutoModules를 실행한다.
     */
    @EventListener
    public void onGameJoin(GameJoinEvent event)
    {
        if (!isEnabled()) return;

        // 재접속 카운터 리셋
        retryCount  = 0;
        reconnectAt = -1;

        // 마지막 접속 서버 기록
        lastAddress = Managers.NETWORK.getAddress();
        lastInfo    = Managers.NETWORK.getInfo();

        log("서버 접속 성공: {}",
                lastInfo != null ? lastInfo.address : "Unknown");

        // AutoModules: 지정된 모듈 자동 활성화
        if (autoModulesConfig.getValue())
            scheduleAutoModules();
    }

    /**
     * 서버 연결 끊김 이벤트.
     * AutoReconnect가 켜져 있으면 재접속을 예약한다.
     */
    @EventListener
    public void onDisconnect(DisconnectEvent event)
    {
        if (!isEnabled() || !autoReconnectConfig.getValue()) return;
        if (lastAddress == null || lastInfo == null) return;

        int max = maxRetriesConfig.getValue();
        if (max > 0 && retryCount >= max)
        {
            log("최대 재접속 횟수({})에 도달했습니다. 재접속을 중단합니다.", max);
            return;
        }

        retryCount++;
        long delay = reconnectDelayConfig.getValue() * 1000L;
        reconnectAt = System.currentTimeMillis() + delay;
        log("연결 끊김. {}초 후 재접속합니다. (시도 {}/{})",
                reconnectDelayConfig.getValue(), retryCount,
                max == 0 ? "∞" : String.valueOf(max));
    }

    /**
     * 매 틱 처리:
     * - AutoConnect 예약 실행
     * - AutoReconnect 예약 실행
     * - 상태 로그 출력
     * - LowFPS 적용
     */
    @EventListener
    public void onTick(TickEvent event)
    {
        if (event.getStage() != StageEvent.EventStage.PRE) return;
        if (!isEnabled()) return;

        long now = System.currentTimeMillis();

        // AutoConnect
        if (connectAt > 0 && now >= connectAt)
        {
            connectAt = -1;
            connectToServer(serverIpConfig.getValue(), serverPortConfig.getValue());
        }

        // AutoReconnect
        if (reconnectAt > 0 && now >= reconnectAt
                && mc.getNetworkHandler() == null)
        {
            reconnectAt = -1;
            connectToServer(lastAddress, lastInfo);
        }

        // Status Log
        if (statusLogConfig.getValue()
                && mc.player != null
                && now - lastStatusLog >= statusIntervalConfig.getValue() * 1000L)
        {
            lastStatusLog = now;
            printStatus();
        }

        // LowFPS - mc.options.maxFps를 직접 조절
        if (lowFpsConfig.getValue() && mc.player != null)
        {
            int target = lowFpsLimitConfig.getValue();
            if (mc.options.getMaxFps().getValue() != target)
                mc.options.getMaxFps().setValue(target);
        }
    }

    // ── 서버 연결 ───────────────────────────────────────────────────

    /**
     * IP 문자열로 서버 접속.
     * 2b2t Korea (xn--3e0b707e) 처리 포함.
     */
    private void connectToServer(String ip, int port)
    {
        // 2b2t.한국 → punycode 처리 (Java InetAddress가 처리하므로 그대로 전달)
        ServerAddress address = ServerAddress.parse(ip + ":" + port);
        ServerInfo    info    = new ServerInfo("HeadlessMC", ip + ":" + port, ServerInfo.ServerType.OTHER);
        connectToServer(address, info);
    }

    private void connectToServer(ServerAddress address, ServerInfo info)
    {
        if (address == null || info == null) return;
        if (mc.getNetworkHandler() != null)
        {
            log("이미 서버에 접속중입니다.");
            return;
        }

        final ServerAddress  addr = address;
        final ServerInfo     inf  = info;

        // 반드시 메인 스레드에서 실행
        mc.execute(() ->
        {
            try
            {
                log("{}에 접속 중...", inf.address);
                ConnectScreen.connect(new TitleScreen(), mc, addr, inf, false, null);
            }
            catch (Throwable t)
            {
                log("[오류] 서버 접속 실패: {}", t.getMessage());
            }
        });
    }

    // ── AutoModules ─────────────────────────────────────────────────

    /**
     * moduleListConfig에 지정된 모듈들을 2초 후 활성화.
     * GameJoinEvent 직후에 바로 실행하면 일부 모듈이 초기화 전일 수 있으므로 지연.
     */
    private void scheduleAutoModules()
    {
        threadPool.submit(() ->
        {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            String[] names = moduleListConfig.getValue().split(",");
            mc.execute(() ->
            {
                for (String name : names)
                {
                    String trimmed = name.trim();
                    if (trimmed.isEmpty()) continue;
                    ToggleModule mod = (ToggleModule) Managers.MODULE.getModuleById(trimmed);
                    if (mod != null && !mod.isEnabled())
                    {
                        mod.enable();
                        log("AutoModule: {} 활성화됨", trimmed);
                    }
                    else if (mod == null)
                    {
                        log("AutoModule: '{}' 모듈을 찾을 수 없습니다.", trimmed);
                    }
                }
            });
        });
    }

    // ── Console I/O ─────────────────────────────────────────────────

    /**
     * stdin에서 명령어를 읽어 처리하는 백그라운드 스레드.
     *
     * 입력 규칙:
     * '/' + 명령어  → 서버 명령어 전송 (/tp, /kill 등)
     * '.' + 명령어  → Shoreline 클라이언트 명령어 (.aura, .crystal 등)
     * 그 외 텍스트  → 서버 채팅 메시지 전송
     * 'quit' / 'exit' → HeadlessMC 비활성화
     */
    private void startConsoleThread()
    {
        if (!consoleRunning.compareAndSet(false, true)) return;

        threadPool.submit(() ->
        {
            log("Console I/O 시작. stdin에서 명령어를 입력하세요.");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8)))
            {
                String line;
                while (consoleRunning.get() && (line = reader.readLine()) != null)
                {
                    final String cmd = line.trim();
                    if (cmd.isEmpty()) continue;

                    if (cmd.equalsIgnoreCase("quit") || cmd.equalsIgnoreCase("exit"))
                    {
                        log("Console: HeadlessMC를 비활성화합니다.");
                        mc.execute(this::disable);
                        break;
                    }

                    // 메인 스레드에서 명령어 실행
                    mc.execute(() -> executeConsoleCommand(cmd));
                }
            }
            catch (IOException e)
            {
                log("[Console] stdin 읽기 오류: {}", e.getMessage());
            }
            finally
            {
                consoleRunning.set(false);
            }
        });
    }

    /**
     * 콘솔 또는 TCP 원격에서 받은 명령어를 처리한다.
     * 반드시 메인 스레드에서 호출해야 한다.
     */
    private void executeConsoleCommand(String cmd)
    {
        if (mc.player == null)
        {
            log("[명령어] 서버에 접속하지 않은 상태입니다: {}", cmd);
            return;
        }

        if (cmd.startsWith("/"))
        {
            // 서버 명령어
            ChatUtil.serverSendCommand(cmd.substring(1));
            log("[서버 명령어] {}", cmd);
        }
        else if (cmd.startsWith("."))
        {
            // Shoreline 클라이언트 명령어 - CommandManager dispatcher 직접 호출
            try
            {
                String literal = cmd.substring(1); // '.' 제거
                Managers.COMMAND.getDispatcher().execute(
                        Managers.COMMAND.getDispatcher().parse(literal, Managers.COMMAND.getSource()));
                log("[클라이언트 명령어] {}", cmd);
            }
            catch (Exception e)
            {
                log("[클라이언트 명령어 오류] {}", e.getMessage());
            }
        }
        else
        {
            // 채팅 메시지
            ChatUtil.serverSendMessage(mc.player, cmd);
            log("[채팅] {}", cmd);
        }
    }

    // ── TCP Remote Control ──────────────────────────────────────────

    /**
     * TCP 소켓 서버를 시작한다.
     * 클라이언트가 접속하면 비밀번호 인증 후 명령어 입/출력을 처리.
     * telnet 또는 nc로 접속: nc localhost 25566
     */
    private void startTcpServer()
    {
        if (!tcpRunning.compareAndSet(false, true)) return;

        threadPool.submit(() ->
        {
            try
            {
                tcpServerSocket = new ServerSocket(tcpPortConfig.getValue());
                log("TCP Remote: 포트 {} 에서 대기 중.", tcpPortConfig.getValue());

                while (tcpRunning.get() && !tcpServerSocket.isClosed())
                {
                    try
                    {
                        Socket client = tcpServerSocket.accept();
                        threadPool.submit(() -> handleTcpClient(client));
                    }
                    catch (IOException e)
                    {
                        if (tcpRunning.get())
                            log("[TCP] 클라이언트 수락 오류: {}", e.getMessage());
                    }
                }
            }
            catch (IOException e)
            {
                log("[TCP] 서버 시작 실패 (포트 {}): {}", tcpPortConfig.getValue(), e.getMessage());
            }
            finally
            {
                tcpRunning.set(false);
            }
        });
    }

    private void handleTcpClient(Socket socket)
    {
        String remote = socket.getInetAddress().getHostAddress();
        log("[TCP] 클라이언트 접속: {}", remote);

        try (
            BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
        )
        {
            // 비밀번호 인증
            String password = tcpPasswordConfig.getValue();
            if (!password.isEmpty())
            {
                out.println("[HeadlessMC] Password:");
                String input = in.readLine();
                if (input == null || !input.equals(password))
                {
                    out.println("[HeadlessMC] Authentication failed.");
                    log("[TCP] {} 인증 실패.", remote);
                    return;
                }
            }

            out.println("[HeadlessMC] Connected. Type 'help' for commands.");

            String line;
            while ((line = in.readLine()) != null)
            {
                final String cmd = line.trim();
                if (cmd.isEmpty()) continue;
                if (cmd.equalsIgnoreCase("help"))
                {
                    out.println("Commands: /server_cmd | .client_cmd | chat_msg | status | quit");
                    continue;
                }
                if (cmd.equalsIgnoreCase("status"))
                {
                    out.println(getStatusString());
                    continue;
                }
                if (cmd.equalsIgnoreCase("quit"))
                {
                    out.println("Bye.");
                    break;
                }

                // 명령어 실행 (메인 스레드)
                CompletableFuture<String> result = new CompletableFuture<>();
                mc.execute(() ->
                {
                    try
                    {
                        executeConsoleCommand(cmd);
                        result.complete("OK: " + cmd);
                    }
                    catch (Exception e)
                    {
                        result.complete("ERR: " + e.getMessage());
                    }
                });

                try
                {
                    out.println(result.get(5, TimeUnit.SECONDS));
                }
                catch (TimeoutException e)
                {
                    out.println("ERR: Timeout");
                }
                catch (Exception e)
                {
                    out.println("ERR: " + e.getMessage());
                }
            }
        }
        catch (IOException e)
        {
            log("[TCP] 클라이언트 통신 오류 ({}): {}", remote, e.getMessage());
        }
        finally
        {
            try { socket.close(); } catch (IOException ignored) {}
            log("[TCP] 클라이언트 연결 종료: {}", remote);
        }
    }

    private void stopTcpServer()
    {
        tcpRunning.set(false);
        if (tcpServerSocket != null && !tcpServerSocket.isClosed())
        {
            try { tcpServerSocket.close(); }
            catch (IOException ignored) {}
        }
    }

    // ── Status ─────────────────────────────────────────────────────

    /** 콘솔에 플레이어 상태를 출력한다. */
    private void printStatus()
    {
        System.out.println("[HeadlessMC] " + getStatusString());
    }

    private String getStatusString()
    {
        if (mc.player == null)
            return "서버 미접속 상태";

        return String.format(
                "서버: %s | 좌표: %.1f / %.1f / %.1f | HP: %.1f | 토템: %d개",
                lastInfo != null ? lastInfo.address : "?",
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getHealth() + mc.player.getAbsorptionAmount(),
                countTotem()
        );
    }

    private int countTotem()
    {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++)
        {
            var stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == net.minecraft.item.Items.TOTEM_OF_UNDYING)
                count += stack.getCount();
        }
        return count;
    }

    // ── 유틸 ───────────────────────────────────────────────────────

    /**
     * 콘솔과 인게임 채팅 모두에 로그를 출력한다.
     * 인게임에서는 클라이언트 채팅 메시지로 표시.
     */
    private void log(String format, Object... args)
    {
        String msg = format;
        for (Object arg : args)
            msg = msg.replaceFirst("\\{}", String.valueOf(arg));

        System.out.println("[HeadlessMC] " + msg);

        // 인게임에서도 표시 (접속돼 있을 때만)
        if (mc.player != null)
        {
            final String finalMsg = msg;
            mc.execute(() -> ChatUtil.clientSendMessage("§s[HeadlessMC]§r " + finalMsg));
        }
    }
}
