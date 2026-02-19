package net.shoreline.client.impl.module.render;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.ColorConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.api.module.ToggleModule;
import net.shoreline.client.api.render.RenderBuffers;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.event.network.GameJoinEvent;
import net.shoreline.client.impl.event.render.RenderWorldEvent;
import net.shoreline.client.impl.event.world.LoadChunkBlockEvent;
import net.shoreline.client.impl.event.world.LoadWorldEvent;
import net.shoreline.client.impl.event.world.UnloadChunkBlocksEvent;
import net.shoreline.client.util.world.BlockUtil;
import net.shoreline.eventbus.annotation.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StashFinder 모듈 - 청크를 스캔해 다른 플레이어의 기지/스태시를 탐지한다.
 *
 * <h2>탐지 원리</h2>
 * <p>청크가 로드될 때마다 "스태시 지표 블록" (체스트, 셜커박스, 엔더체스트, 흑요석 등)의
 * 수를 센다. 특정 청크에서 지표 블록이 {@code minScore} 이상 발견되면 스태시로 분류한다.</p>
 *
 * <h2>점수 시스템</h2>
 * <pre>
 *   엔더체스트    +5점  (가장 강력한 지표)
 *   셜커박스      +4점
 *   체스트        +2점
 *   흑요석        +1점  (기지 벽 재료)
 *   용암 통       +3점
 * </pre>
 *
 * <h2>렌더링</h2>
 * <ul>
 *   <li>스태시 위치에 3D ESP 박스</li>
 *   <li>점수·좌표·거리를 화면에 Sign 텍스트로 표시</li>
 *   <li>점수가 높을수록 색상이 더 밝음 (낮음=노란색, 높음=빨간색)</li>
 * </ul>
 *
 * <h2>로그</h2>
 * <p>{@code .minecraft/stash_log.txt}에 발견 시각·좌표·점수를 자동 저장한다.</p>
 */
public class StashFinderModule extends ToggleModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashFinder");

    // ─── Configs ─────────────────────────────────────────────────
    /** 스태시 판정 최소 점수 */
    Config<Integer> minScoreConfig = register(new NumberConfig<>(
            "MinScore", "스태시로 판정할 최소 점수 (낮을수록 민감)", 1, 10, 100));

    /** ESP 렌더링 최대 거리 */
    Config<Float> renderRangeConfig = register(new NumberConfig<>(
            "RenderRange", "ESP 표시 최대 거리 (블록)", 50.0f, 500.0f, 2000.0f));

    /** ESP 박스 채우기 */
    Config<Boolean> fillConfig = register(new BooleanConfig(
            "Fill", "스태시 위치에 박스를 채워 표시", true));

    /** 체스트·셜커박스 탐지 */
    Config<Boolean> detectChestsConfig = register(new BooleanConfig(
            "DetectChests", "체스트·트랩체스트 탐지", true));

    Config<Boolean> detectShulkersConfig = register(new BooleanConfig(
            "DetectShulkers", "셜커박스 탐지", true));

    Config<Boolean> detectEnderChestsConfig = register(new BooleanConfig(
            "DetectEnderChests", "엔더체스트 탐지", true));

    Config<Boolean> detectObsidianConfig = register(new BooleanConfig(
            "DetectObsidian", "흑요석/울부짖는 흑요석 탐지 (기지 벽 재료)", true));

    Config<Boolean> detectBarrelsConfig = register(new BooleanConfig(
            "DetectBarrels", "통 탐지", true));

    /** 좌표 로그 파일 저장 */
    Config<Boolean> logConfig = register(new BooleanConfig(
            "Log", "발견된 스태시를 stash_log.txt에 저장", true));

    /** 발견 시 채팅 알림 */
    Config<Boolean> chatAlertConfig = register(new BooleanConfig(
            "ChatAlert", "스태시 발견 시 채팅 알림", true));

    Config<Color> colorConfig = register(new ColorConfig(
            "Color", "ESP 기본 색상", new Color(255, 60, 60), false, false));

    // ─── 데이터 ──────────────────────────────────────────────────

    /**
     * 청크 키(chunkX, chunkZ) → StashData
     * 청크 단위로 점수를 집계한다.
     */
    private final Map<Long, StashData> stashes = new ConcurrentHashMap<>();

    /**
     * 청크 키(chunkX, chunkZ) → 해당 청크의 지표 블록 수
     * 임계값 미달 청크도 추적해 나중에 임계값이 낮아지면 재평가 가능
     */
    private final Map<Long, AtomicInteger> chunkScores = new ConcurrentHashMap<>();

    // ─── 생성자 ──────────────────────────────────────────────────

    public StashFinderModule() {
        super("StashFinder",
                "청크를 스캔해 다른 플레이어의 기지/스태시 위치를 탐지합니다",
                ModuleCategory.RENDER);
    }

    // ─── 라이프사이클 ─────────────────────────────────────────────

    @Override
    public void onEnable() {
        if (mc.world == null) return;
        // 이미 로드된 청크 즉시 스캔
        for (WorldChunk chunk : BlockUtil.loadedChunks()) {
            scanChunk(chunk);
        }
        sendModuleMessage("§aStashFinder 활성화 - 청크 스캔 시작");
    }

    @Override
    public void onDisable() {
        stashes.clear();
        chunkScores.clear();
    }

    // ─── 이벤트 ──────────────────────────────────────────────────

    @EventListener
    public void onGameJoin(GameJoinEvent event) {
        stashes.clear();
        chunkScores.clear();
    }

    @EventListener
    public void onLoadWorld(LoadWorldEvent event) {
        stashes.clear();
        chunkScores.clear();
    }

    /**
     * 블록이 로드될 때마다 점수를 계산한다.
     * SearchModule과 동일한 이벤트 시스템을 사용한다.
     */
    @EventListener
    public void onLoadChunkBlock(LoadChunkBlockEvent event) {
        BlockPos pos = event.getPos();
        BlockState state = event.getState();

        int score = getBlockScore(state.getBlock());
        if (score <= 0) return;

        // 청크 키 계산 (chunkX << 32 | chunkZ)
        long chunkKey = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);

        // 해당 청크 점수 누적
        AtomicInteger chunkScore = chunkScores.computeIfAbsent(chunkKey, k -> new AtomicInteger(0));
        int newScore = chunkScore.addAndGet(score);

        // 임계값 초과 시 스태시 등록
        if (newScore >= minScoreConfig.getValue() && !stashes.containsKey(chunkKey)) {
            // 청크 중심 좌표 계산
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            int centerX = chunkX * 16 + 8;
            int centerZ = chunkZ * 16 + 8;
            int centerY = pos.getY();

            StashData stash = new StashData(
                    new BlockPos(centerX, centerY, centerZ),
                    newScore,
                    System.currentTimeMillis()
            );
            stashes.put(chunkKey, stash);

            // 알림 & 로그
            onStashFound(stash);
        } else if (stashes.containsKey(chunkKey)) {
            // 이미 등록된 스태시 점수 갱신
            stashes.get(chunkKey).setScore(newScore);
        }
    }

    @EventListener
    public void onUnloadChunk(UnloadChunkBlocksEvent event) {
        // 청크 언로드 시 해당 청크 데이터 제거 (메모리 관리)
        // 단, 이미 발견된 스태시는 좌표 유지 옵션에 따라 유지
        // → 현재는 청크 언로드해도 스태시 목록은 유지
    }

    /** 스태시 발견 시 알림 및 로그 처리 */
    private void onStashFound(StashData stash) {
        BlockPos pos = stash.getPos();
        String coordStr = String.format("§f(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ());
        String scoreStr = "§c점수: " + stash.getScore();

        if (chatAlertConfig.getValue()) {
            sendModuleMessage("§a스태시 발견! " + coordStr + " " + scoreStr);
        }

        if (logConfig.getValue()) {
            logToFile(stash);
        }
    }

    /** 스태시 정보를 파일에 기록한다. */
    private void logToFile(StashData stash) {
        try {
            Path logFile = Paths.get("stash_log.txt");
            String server = mc.getCurrentServerEntry() != null
                    ? mc.getCurrentServerEntry().address : "unknown";
            BlockPos pos = stash.getPos();
            String line = String.format("[%s] [%s] X:%d Y:%d Z:%d | Score:%d%n",
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()),
                    server,
                    pos.getX(), pos.getY(), pos.getZ(),
                    stash.getScore());
            Files.writeString(logFile, line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("스태시 로그 저장 실패", e);
        }
    }

    // ─── 렌더링 ──────────────────────────────────────────────────

    @EventListener
    public void onRenderWorld(RenderWorldEvent event) {
        if (stashes.isEmpty() || mc.player == null) return;

        MatrixStack matrices = event.getMatrices();
        RenderBuffers.preRender();

        for (StashData stash : stashes.values()) {
            BlockPos pos = stash.getPos();
            double dist = mc.player.squaredDistanceTo(pos.toCenterPos());
            double maxDist = renderRangeConfig.getValue();
            if (dist > maxDist * maxDist) continue;

            // 점수에 따른 색상 (낮음=노란색, 중간=주황색, 높음=빨간색)
            Color espColor = getScoreColor(stash.getScore());

            // ESP 박스 (청크 영역 전체를 감싸는 박스)
            Box box = new Box(
                    pos.getX() - 8, pos.getY() - 3,  pos.getZ() - 8,
                    pos.getX() + 8, pos.getY() + 10, pos.getZ() + 8);

            if (fillConfig.getValue()) {
                RenderManager.renderBox(matrices, box,
                        new Color(espColor.getRed(), espColor.getGreen(),
                                espColor.getBlue(), 25).getRGB());
            }
            RenderManager.renderBoundingBox(matrices, box, 1.5f,
                    new Color(espColor.getRed(), espColor.getGreen(),
                            espColor.getBlue(), 180).getRGB());
        }

        RenderBuffers.postRender();

        // 3D 텍스트 라벨 (Sign) - 거리·점수 표시
        for (StashData stash : stashes.values()) {
            BlockPos pos = stash.getPos();
            double dx = mc.player.getX() - pos.getX();
            double dy = mc.player.getY() - pos.getY();
            double dz = mc.player.getZ() - pos.getZ();
            double realDist = Math.sqrt(dx*dx + dy*dy + dz*dz);

            if (realDist > renderRangeConfig.getValue()) continue;

            Color labelColor = getScoreColor(stash.getScore());

            // 상단에 "STASH" 레이블
            RenderManager.renderSign(
                    String.format("§cSTASH §f[%d점]", stash.getScore()),
                    pos.getX() + 0.5, pos.getY() + 12, pos.getZ() + 0.5,
                    0.5f, labelColor.getRGB());

            // 좌표
            RenderManager.renderSign(
                    String.format("§7%d, %d, %d", pos.getX(), pos.getY(), pos.getZ()),
                    pos.getX() + 0.5, pos.getY() + 11, pos.getZ() + 0.5,
                    0.5f, 0xFFFFFFFF);

            // 거리
            RenderManager.renderSign(
                    String.format("§e%.0fm", realDist),
                    pos.getX() + 0.5, pos.getY() + 10, pos.getZ() + 0.5,
                    0.5f, 0xFFFFFF00);
        }
    }

    // ─── 유틸 ────────────────────────────────────────────────────

    /**
     * 이미 로드된 청크를 전체 스캔한다. (모듈 활성화 시 1회 실행)
     */
    private void scanChunk(WorldChunk chunk) {
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        for (int y = chunk.getBottomY(); y < chunk.getTopY(); y++) {
            for (int x = startX; x < startX + 16; x++) {
                for (int z = startZ; z < startZ + 16; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (!state.isAir()) {
                        int score = getBlockScore(state.getBlock());
                        if (score > 0) {
                            long key = chunkKey(chunk.getPos().x, chunk.getPos().z);
                            chunkScores.computeIfAbsent(key, k -> new AtomicInteger(0))
                                    .addAndGet(score);
                        }
                    }
                }
            }
        }
        // 스캔 완료 후 임계값 체크
        long key = chunkKey(chunk.getPos().x, chunk.getPos().z);
        AtomicInteger score = chunkScores.get(key);
        if (score != null && score.get() >= minScoreConfig.getValue()
                && !stashes.containsKey(key)) {
            int centerX = chunk.getPos().x * 16 + 8;
            int centerZ = chunk.getPos().z * 16 + 8;
            StashData stash = new StashData(
                    new BlockPos(centerX, 64, centerZ),
                    score.get(),
                    System.currentTimeMillis());
            stashes.put(key, stash);
            onStashFound(stash);
        }
    }

    /**
     * 블록의 스태시 지표 점수를 반환한다.
     * 0이면 지표 블록이 아님.
     */
    private int getBlockScore(Block block) {
        // 엔더체스트 - 가장 강력한 지표 (일반 지형에 없음)
        if (detectEnderChestsConfig.getValue() && block == Blocks.ENDER_CHEST) {
            return 5;
        }
        // 셜커박스 - 매우 강력한 지표
        if (detectShulkersConfig.getValue() && isShulkerBox(block)) {
            return 4;
        }
        // 통 (barrel)
        if (detectBarrelsConfig.getValue() && block == Blocks.BARREL) {
            return 3;
        }
        // 체스트 / 트랩 체스트
        if (detectChestsConfig.getValue() &&
                (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST)) {
            return 2;
        }
        // 흑요석 / 울부짖는 흑요석 (기지 벽)
        if (detectObsidianConfig.getValue() &&
                (block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN)) {
            return 1;
        }
        return 0;
    }

    private boolean isShulkerBox(Block block) {
        return block == Blocks.SHULKER_BOX
                || block == Blocks.WHITE_SHULKER_BOX
                || block == Blocks.ORANGE_SHULKER_BOX
                || block == Blocks.MAGENTA_SHULKER_BOX
                || block == Blocks.LIGHT_BLUE_SHULKER_BOX
                || block == Blocks.YELLOW_SHULKER_BOX
                || block == Blocks.LIME_SHULKER_BOX
                || block == Blocks.PINK_SHULKER_BOX
                || block == Blocks.GRAY_SHULKER_BOX
                || block == Blocks.LIGHT_GRAY_SHULKER_BOX
                || block == Blocks.CYAN_SHULKER_BOX
                || block == Blocks.PURPLE_SHULKER_BOX
                || block == Blocks.BLUE_SHULKER_BOX
                || block == Blocks.BROWN_SHULKER_BOX
                || block == Blocks.GREEN_SHULKER_BOX
                || block == Blocks.RED_SHULKER_BOX
                || block == Blocks.BLACK_SHULKER_BOX;
    }

    /** 점수에 따른 ESP 색상: 낮음=노랑, 중간=주황, 높음=빨강 */
    private Color getScoreColor(int score) {
        int min = minScoreConfig.getValue();
        int high = min * 5;
        float t = Math.min((float)(score - min) / (high - min + 1), 1.0f);
        // 노란색(255,220,0) → 빨간색(255,30,30)
        int r = 255;
        int g = (int)(220 * (1 - t) + 30 * t);
        int b = (int)(0   * (1 - t) + 30 * t);
        return new Color(r, g, b);
    }

    /** 청크 좌표를 단일 long 키로 인코딩 */
    private long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public String getModuleData() {
        return stashes.isEmpty() ? "" : stashes.size() + " found";
    }

    // ─── 내부 데이터 클래스 ──────────────────────────────────────

    /** 발견된 스태시 데이터 */
    private static class StashData {

        private final BlockPos pos;
        private volatile int score;
        private final long foundTime;

        StashData(BlockPos pos, int score, long foundTime) {
            this.pos = pos;
            this.score = score;
            this.foundTime = foundTime;
        }

        BlockPos getPos()        { return pos; }
        int      getScore()      { return score; }
        void     setScore(int s) { this.score = s; }
        long     getFoundTime()  { return foundTime; }
    }
}
