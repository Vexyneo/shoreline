package net.shoreline.client.impl.gui.hud.widget.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import net.shoreline.client.Shoreline;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.gui.hud.widget.HudWidget;
import net.shoreline.client.util.render.ColorUtil;

import java.awt.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MiniMap 위젯 - 플레이어 주변 블록을 탑뷰로 렌더링하는 미니맵.
 *
 * <h2>렌더링 원리</h2>
 * <ol>
 *   <li>백그라운드 스레드에서 플레이어 주변 N×N 블록의 최고 높이(Heightmap)를 탐색</li>
 *   <li>각 블록의 {@link MapColor}를 읽어 {@link NativeImage}에 픽셀로 기록</li>
 *   <li>높이 차이로 음영(shading)을 적용해 입체감 표현</li>
 *   <li>GPU 텍스처로 업로드 후 HUD에 DrawContext로 출력</li>
 * </ol>
 *
 * <h2>스레드 안전성</h2>
 * <p>픽셀 계산은 {@link #MAP_EXECUTOR} 단일 스레드에서 수행한다.
 * 계산 완료된 이미지는 {@link #pendingImage}에 보관하다가
 * 메인(렌더) 스레드의 {@link #renderHud}에서 GPU에 업로드한다.</p>
 */
public class MapWidget extends HudWidget {

    // ─── 상수 ────────────────────────────────────────────────────
    /** 미니맵 픽셀 해상도 (128 = Minecraft 맵과 동일) */
    private static final int MAP_SIZE = 128;

    /** 맵 갱신 주기 (ms) */
    private static final long UPDATE_INTERVAL_MS = 500L;

    /** 픽셀 계산 전용 단일 스레드 */
    private static final Executor MAP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MapWidget-Updater");
        t.setDaemon(true);
        return t;
    });

    // ─── 텍스처 ──────────────────────────────────────────────────
    /** GPU에 등록된 텍스처 ID */
    private Identifier textureId;
    /** 현재 GPU에 올라간 텍스처 객체 */
    private NativeImageBackedTexture texture;

    /** 백그라운드 계산 완료 후 메인 스레드에서 업로드 대기 중인 이미지 */
    private volatile NativeImage pendingImage;

    /** 백그라운드 작업 실행 중 플래그 */
    private final AtomicBoolean updating = new AtomicBoolean(false);

    /** 마지막 갱신 시각 */
    private long lastUpdate = 0L;

    /** 마지막으로 맵을 그린 플레이어 좌표 (크게 움직였을 때만 재계산) */
    private int lastCenterX = Integer.MIN_VALUE;
    private int lastCenterZ = Integer.MIN_VALUE;

    // ─── 옵션 ────────────────────────────────────────────────────
    /** 표시 반경 (블록 단위) - 128px 해상도에서 1px = radius*2/128 블록 */
    private int radius = 64;
    /** 플레이어 마커 표시 여부 */
    private boolean showPlayer = true;
    /** 다른 플레이어 표시 여부 */
    private boolean showOtherPlayers = true;
    /** 나침반 방향 표시 여부 */
    private boolean showCompass = true;
    /** 회전 모드 (true = 플레이어 방향 기준, false = 북쪽 고정) */
    private boolean rotating = false;

    public MapWidget() {
        super("Map", 4, 4, MAP_SIZE + 8, MAP_SIZE + 8 + 10);
    }

    // ─── 렌더링 ──────────────────────────────────────────────────

    @Override
    public void renderHud(net.minecraft.client.gui.DrawContext context) {
        if (mc.player == null || mc.world == null) return;

        int px = (int) mc.player.getX();
        int pz = (int) mc.player.getZ();

        // 1. 백그라운드 갱신 트리거 (주기 & 이동 거리 기반)
        long now = System.currentTimeMillis();
        boolean moved = Math.abs(px - lastCenterX) > 8 || Math.abs(pz - lastCenterZ) > 8;
        if ((now - lastUpdate > UPDATE_INTERVAL_MS || moved) && !updating.get()) {
            triggerUpdate(px, pz);
            lastUpdate = now;
        }

        // 2. 완성된 이미지가 있으면 GPU 업로드
        NativeImage ready = pendingImage;
        if (ready != null) {
            pendingImage = null;
            uploadTexture(ready);
        }

        // 3. 맵 배경
        context.fill((int) x, (int) y, (int)(x + MAP_SIZE + 8), (int)(y + MAP_SIZE + 8), 0xFF111111);
        // 테두리
        RenderManager.borderedRect(context.getMatrices(),
                x, y, MAP_SIZE + 8, MAP_SIZE + 8,
                0x00000000, getThemeColor());

        // 4. 텍스처 출력
        if (textureId != null) {
            context.getMatrices().push();
            // 회전 모드
            if (rotating) {
                float yaw = mc.player.getYaw();
                context.getMatrices().translate(x + 4 + MAP_SIZE / 2f, y + 4 + MAP_SIZE / 2f, 0);
                context.getMatrices().multiply(
                        net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(yaw));
                context.getMatrices().translate(-(MAP_SIZE / 2f), -(MAP_SIZE / 2f), 0);
                context.drawTexture(textureId, 0, 0, 0, 0, MAP_SIZE, MAP_SIZE, MAP_SIZE, MAP_SIZE);
            } else {
                context.drawTexture(textureId,
                        (int)(x + 4), (int)(y + 4), 0, 0, MAP_SIZE, MAP_SIZE, MAP_SIZE, MAP_SIZE);
            }
            context.getMatrices().pop();
        }

        // 5. 다른 플레이어 마커
        if (showOtherPlayers) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player) continue;
                renderPlayerDot(context, player, px, pz, 0xFFFF4444);
            }
        }

        // 6. 내 플레이어 마커 (중앙 흰색 점)
        if (showPlayer) {
            int cx = (int)(x + 4 + MAP_SIZE / 2f);
            int cy = (int)(y + 4 + MAP_SIZE / 2f);
            context.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFFFFFFFF);
            // 시선 방향 선
            float yawRad = (float) Math.toRadians(mc.player.getYaw() + 180);
            int ex = cx + (int)(Math.sin(yawRad) * 6);
            int ey = cy - (int)(Math.cos(yawRad) * 6);
            // 작은 삼각형 대신 밝은 점
            context.fill(ex - 1, ey - 1, ex + 1, ey + 1, getThemeColor());
        }

        // 7. 나침반 (N 표시)
        if (showCompass) {
            RenderManager.renderText(context, "§fN",
                    x + 4 + MAP_SIZE / 2f - 2, y + 4, 0xFFFFFFFF);
        }

        // 8. 좌표 텍스트
        String coordText = String.format("§7%d, %d", px, (int) mc.player.getZ());
        RenderManager.renderText(context, coordText,
                x + 4, y + MAP_SIZE + 8 - RenderManager.textHeight() + 1,
                getThemeColor());

        setWidth(MAP_SIZE + 8);
        setHeight(MAP_SIZE + 8 + RenderManager.textHeight() + 2);
    }

    // ─── 다른 플레이어 마커 ───────────────────────────────────────

    private void renderPlayerDot(net.minecraft.client.gui.DrawContext context,
                                  PlayerEntity player, int centerX, int centerZ, int color) {
        double relX = player.getX() - centerX;
        double relZ = player.getZ() - centerZ;
        // 맵 반경 내에 있는지 체크
        if (Math.abs(relX) > radius || Math.abs(relZ) > radius) return;

        // 픽셀 좌표로 변환
        int dotX = (int)(x + 4 + MAP_SIZE / 2f + (relX / radius) * (MAP_SIZE / 2f));
        int dotZ = (int)(y + 4 + MAP_SIZE / 2f + (relZ / radius) * (MAP_SIZE / 2f));

        context.fill(dotX - 1, dotZ - 1, dotX + 1, dotZ + 1, color);
    }

    // ─── 텍스처 업로드 ───────────────────────────────────────────

    /** 백그라운드에서 완성된 NativeImage를 GPU 텍스처로 업로드 (메인 스레드에서 호출) */
    private void uploadTexture(NativeImage image) {
        try {
            if (texture == null) {
                texture = new NativeImageBackedTexture(image);
                textureId = mc.getTextureManager().registerDynamicTexture(
                        "shoreline_minimap", texture);
            } else {
                // 기존 텍스처 픽셀 교체 후 GPU에 재업로드
                texture.setImage(image);
                texture.upload();
            }
        } catch (Exception e) {
            // 텍스처 업로드 실패 시 조용히 무시 (다음 프레임에 재시도)
        }
    }

    // ─── 백그라운드 맵 계산 ──────────────────────────────────────

    /** 백그라운드 스레드에서 픽셀 계산을 시작한다. */
    private void triggerUpdate(int centerX, int centerZ) {
        updating.set(true);
        lastCenterX = centerX;
        lastCenterZ = centerZ;

        MAP_EXECUTOR.execute(() -> {
            try {
                NativeImage img = buildMapImage(centerX, centerZ);
                pendingImage = img; // 메인 스레드에서 uploadTexture() 호출
            } finally {
                updating.set(false);
            }
        });
    }

    /**
     * NativeImage(128×128)를 생성하고 블록 색상을 픽셀로 채운다.
     *
     * <h3>알고리즘</h3>
     * <ol>
     *   <li>각 픽셀(px, pz)을 월드 좌표로 변환</li>
     *   <li>해당 XZ 위치에서 위에서 아래로 내려오며 첫 번째 불투명 블록 탐색</li>
     *   <li>블록의 MapColor로 픽셀 색상 결정</li>
     *   <li>인접 블록과의 높이 차이로 밝기 조정 (음영)</li>
     * </ol>
     */
    private NativeImage buildMapImage(int centerX, int centerZ) {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, MAP_SIZE, MAP_SIZE, false);

        if (mc.world == null) return img;

        // 이전 열의 높이 (음영 계산용)
        int[] prevHeights = new int[MAP_SIZE];
        java.util.Arrays.fill(prevHeights, 64);

        for (int px = 0; px < MAP_SIZE; px++) {
            for (int pz = 0; pz < MAP_SIZE; pz++) {
                // 픽셀 → 월드 XZ 좌표 변환
                int worldX = centerX + (int)((px - MAP_SIZE / 2f) * radius / (MAP_SIZE / 2f));
                int worldZ = centerZ + (int)((pz - MAP_SIZE / 2f) * radius / (MAP_SIZE / 2f));

                // 청크가 로드되어 있는지 확인
                if (!mc.world.isChunkLoaded(worldX >> 4, worldZ >> 4)) {
                    img.setColor(px, pz, 0xFF222222); // 미로드 청크 = 어두운 색
                    continue;
                }

                // 최고 불투명 블록 탐색 (Heightmap 방식)
                int topY = mc.world.getTopY();
                int bottomY = mc.world.getBottomY();
                BlockState topState = null;
                int topHeight = bottomY;

                for (int y = topY - 1; y >= bottomY; y--) {
                    BlockPos pos = new BlockPos(worldX, y, worldZ);
                    BlockState state = mc.world.getBlockState(pos);
                    if (!state.isAir() && !isTransparent(state)) {
                        topState = state;
                        topHeight = y;
                        break;
                    }
                }

                if (topState == null) {
                    img.setColor(px, pz, 0xFF111111);
                    continue;
                }

                // MapColor 추출
                MapColor mapColor = topState.getMapColor(mc.world, new BlockPos(worldX, topHeight, worldZ));
                int baseColor = mapColor.color;

                // MapColor는 ARGB가 아닌 RGB이므로 알파 추가
                int r = ColorHelper.Argb.getRed(baseColor);
                int g = ColorHelper.Argb.getGreen(baseColor);
                int b = ColorHelper.Argb.getBlue(baseColor);

                // 음영: 이전 행보다 높으면 밝게, 낮으면 어둡게
                int prevH = prevHeights[px];
                float shade;
                if (topHeight > prevH)       shade = 1.25f; // 더 높음 → 밝게
                else if (topHeight < prevH)  shade = 0.75f; // 더 낮음 → 어둡게
                else                         shade = 1.00f; // 같음 → 그대로

                r = MathHelper.clamp((int)(r * shade), 0, 255);
                g = MathHelper.clamp((int)(g * shade), 0, 255);
                b = MathHelper.clamp((int)(b * shade), 0, 255);

                // NativeImage는 ABGR 포맷
                img.setColor(px, pz, toABGR(255, r, g, b));
                prevHeights[px] = topHeight;
            }
        }
        return img;
    }

    /** 블록이 맵에서 투명하게 처리될지 여부 */
    private boolean isTransparent(BlockState state) {
        // 물, 유리, 잎 등은 반투명이지만 맵에서는 표시
        return state.isAir();
    }

    /** RGBA → ABGR 변환 (NativeImage는 ABGR 포맷 사용) */
    private int toABGR(int a, int r, int g, int b) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    // ─── 옵션 Setter ─────────────────────────────────────────────
    public void setRadius(int r)              { this.radius = r; }
    public void setShowPlayer(boolean v)      { this.showPlayer = v; }
    public void setShowOtherPlayers(boolean v){ this.showOtherPlayers = v; }
    public void setShowCompass(boolean v)     { this.showCompass = v; }
    public void setRotating(boolean v)        { this.rotating = v; }
}
