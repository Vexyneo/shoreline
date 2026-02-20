package net.shoreline.client.impl.module.client;

import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.RaycastContext;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.EnumConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ConcurrentModule;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.impl.event.TickEvent;
import net.shoreline.client.impl.event.config.ConfigUpdateEvent;
import net.shoreline.client.impl.event.network.MovementPacketsEvent;
import net.shoreline.client.impl.event.network.PacketEvent;
import net.shoreline.client.impl.event.network.DisconnectEvent;
import net.shoreline.client.init.Managers;
import net.shoreline.client.mixin.accessor.AccessorClientPlayerEntity;
import net.shoreline.client.mixin.accessor.AccessorPlayerMoveC2SPacket;
import net.shoreline.client.util.math.position.DirectionUtil;
import net.shoreline.client.util.math.timer.CacheTimer;
import net.shoreline.client.util.math.timer.Timer;
import net.shoreline.client.util.world.BlastResistantBlocks;
import net.shoreline.eventbus.annotation.EventListener;
import net.shoreline.eventbus.event.StageEvent;

/**
 * AnticheatModule - 완전 구현
 *
 * 지원 안티치트: GRIM, NCP, PAPER, FOLIA, VANILLA, CUSTOM
 *
 * 구현된 로직:
 *   1. YawStep         - 틱당 yaw 변화량 제한 (MovementPacketsEvent)
 *   2. SetbackTicks    - Teleport/setback 직후 이동 패킷 억제
 *   3. MovementSync    - 이동 패킷 항상 Full 형식으로 강제 (Grim 예측 동기화)
 *   4. PacketOrderFix  - Paper/Folia 상호작용 전 위치 패킷 보장
 *   5. FoliaRegionFix  - Folia 리전 스레드 처리 지연 보정
 *   6. NoServerSlot    - 서버 슬롯 강제 변경 패킷 차단
 *   7. NoScreenClose   - 서버 인벤토리 강제 닫기 차단
 *   8. GrimMiningFix   - STOP→START→ABORT 채굴 시퀀스
 *   9. NCP RaytraceFix - pitch -75 스푸핑
 *  10. Rotations       - SILENT/FULL/NONE 모드
 */
public class AnticheatModule extends ConcurrentModule
{
    private static AnticheatModule INSTANCE;

    // ════════════════════════════════════════════════════════════
    // ① SERVER PRESET
    // ════════════════════════════════════════════════════════════

    Config<Anticheats> modeConfig = register(new EnumConfig<>(
            "Mode", "The anticheat running on the server", Anticheats.GRIM, Anticheats.values()));

    Config<Boolean> forcePresetConfig = register(new BooleanConfig(
            "ForcePreset", "Ignore auto-detection; always use Mode preset", false));

    // ════════════════════════════════════════════════════════════
    // ② SERVER FLAGS
    // ════════════════════════════════════════════════════════════

    Config<Boolean> b2t2Config = register(new BooleanConfig(
            "2b2t", "Enables 2b2t / 2b2t-Korea specific optimizations", false));

    /**
     * 서버가 보내는 UpdateSelectedSlotS2CPacket 차단.
     * Grim은 인벤토리 조작 시 슬롯을 강제 변경하는 패킷을 보낸다.
     * 이 패킷을 차단하면 서버가 슬롯을 바꿔도 클라이언트는 유지.
     */
    Config<Boolean> noServerSlotConfig = register(new BooleanConfig(
            "NoServerSlot", "Prevents server from forcing hotbar slot changes", true,
            () -> modeConfig.getValue() == Anticheats.GRIM
               || modeConfig.getValue() == Anticheats.CUSTOM));

    /**
     * 서버가 보내는 CloseScreenS2CPacket(syncId=0) 차단.
     * Grim이 열린 인벤토리를 감지할 때 강제로 닫으려는 패킷 무시.
     */
    Config<Boolean> noScreenCloseConfig = register(new BooleanConfig(
            "NoScreenClose", "Prevents server from force-closing inventory screen", false,
            () -> modeConfig.getValue() == Anticheats.GRIM
               || modeConfig.getValue() == Anticheats.CUSTOM));

    // ════════════════════════════════════════════════════════════
    // ③ MOVEMENT
    // ════════════════════════════════════════════════════════════

    /**
     * 회전 전송 모드.
     * SILENT  - 패킷에만 회전 적용 (카메라 고정) - Grim 권장
     * FULL    - 실제 카메라도 회전 (NCP 호환)
     * NONE    - 회전 패킷 미전송 (바닐라)
     */
    Config<Rotations> rotationsConfig = register(new EnumConfig<>(
            "Rotations", "How rotations are sent to the server",
            Rotations.SILENT, Rotations.values()));

    /**
     * 틱당 최대 yaw 변화량 (도).
     * 구현: MovementPacketsEvent에서 이전 서버 yaw와 비교해
     * 변화량이 이 값을 초과하면 clamp.
     * Grim v3 권장값: 1.0~3.0 / NCP: 10.0~90.0 / 0.0=무제한
     */
    Config<Float> yawStepConfig = register(new NumberConfig<>(
            "YawStep", "Max yaw change per tick (0 = unlimited)", 0.0f, 1.0f, 90.0f));

    /**
     * 이동 패킷을 항상 Full 형식으로 강제.
     * Grim은 플레이어 위치를 틱마다 예측한다.
     * Full 패킷(x/y/z+yaw+pitch)을 항상 보내면 서버 예측과 동기화 유지.
     *
     * 구현: MovementPacketsEvent에서 위치가 변하지 않아도
     * ticksSinceLastPositionPacketSent를 리셋해 Full 패킷 강제.
     */
    Config<Boolean> movementSyncConfig = register(new BooleanConfig(
            "MovementSync", "Forces Full movement packets every tick for Grim", true,
            () -> modeConfig.getValue() == Anticheats.GRIM
               || modeConfig.getValue() == Anticheats.CUSTOM));

    /** 거미줄+스프린트+점프 Grim 감지 방지 */
    Config<Boolean> webJumpFixConfig = register(new BooleanConfig(
            "WebJumpFix", "Fixes sprint-jumping in cobwebs on Grim", false,
            () -> modeConfig.getValue() == Anticheats.GRIM
               || modeConfig.getValue() == Anticheats.CUSTOM));

    /**
     * Teleport(Setback) 이후 이동 패킷 억제 틱 수.
     *
     * 구현: PlayerPositionLookS2CPacket 감지 → setbackRemaining = N
     * MovementPacketsEvent에서 setbackRemaining > 0 이면
     * 이동 패킷을 OnGroundOnly로만 보내 "moved too quickly" 방지.
     */
    Config<Integer> setbackIgnoreTicksConfig = register(new NumberConfig<>(
            "SetbackTicks", "Ticks to suppress movement after server setback", 0, 3, 20,
            () -> modeConfig.getValue() == Anticheats.GRIM
               || modeConfig.getValue() == Anticheats.CUSTOM));

    // ════════════════════════════════════════════════════════════
    // ④ PLACEMENT
    // ════════════════════════════════════════════════════════════

    Config<StrictDirection> strictDirectionConfig = register(new EnumConfig<>(
            "StrictDirection", "Block placement face validation mode",
            StrictDirection.GRIM, StrictDirection.values()));

    Config<Placements> placementsConfig = register(new EnumConfig<>(
            "Placements", "Block placement packet mode",
            Placements.GRIM, Placements.values()));

    Config<Float> entityPlaceConfig = register(new NumberConfig<>(
            "PlaceThreshold", "Max ticks to attempt placing on entities", 0.0f, 2.0f, 25.0f,
            () -> modeConfig.getValue() != Anticheats.VANILLA));

    // ════════════════════════════════════════════════════════════
    // ⑤ MINING
    // ════════════════════════════════════════════════════════════

    Config<Mining> miningConfig = register(new EnumConfig<>(
            "Mining", "Mining packet handling mode",
            Mining.VANILLA, Mining.values()));

    /**
     * 블록 파괴 중이라고 가정.
     * Grim의 mining flag false positive 방지.
     */
    Config<Boolean> assumeBlockBreakingConfig = register(new BooleanConfig(
            "AssumeBlockBreaking", "Assumes block breaking for Grim mining checks", true,
            () -> miningConfig.getValue() == Mining.GRIM_STRICT));

    // ════════════════════════════════════════════════════════════
    // ⑥ FIXES
    // ════════════════════════════════════════════════════════════

    /**
     * NCP RayTrace 스푸핑.
     * 상호작용 전 pitch를 -75도로 보내 NCP raycast 검증 우회.
     */
    Config<Boolean> raytraceSpoofConfig = register(new BooleanConfig(
            "RaytraceFix", "Spoofs raytrace pitch for NCP interaction bypass", false,
            () -> modeConfig.getValue() == Anticheats.N_C_P
               || modeConfig.getValue() == Anticheats.CUSTOM));

    /**
     * Paper/Folia 패킷 순서 보정.
     * 상호작용(InteractBlock) 패킷 직전에 현재 위치를 담은
     * Full 이동 패킷을 강제 선행 전송.
     * Paper는 위치를 먼저 알아야 상호작용을 수락한다.
     */
    Config<Boolean> packetOrderFixConfig = register(new BooleanConfig(
            "PacketOrderFix", "Sends position before interactions for Paper/Folia", false,
            () -> modeConfig.getValue() == Anticheats.PAPER
               || modeConfig.getValue() == Anticheats.FOLIA
               || modeConfig.getValue() == Anticheats.CUSTOM));

    /**
     * Folia 리전 스레드 보정.
     * Folia는 청크 리전마다 별도 스레드에서 패킷을 처리한다.
     * 리전 경계를 넘는 상호작용이 out-of-order로 처리될 수 있으므로
     * 이동 후 1틱 지연을 두고 상호작용 패킷을 전송.
     *
     * 구현: PlayerInteractBlockC2SPacket 직전에
     * Full position 패킷 + 1틱 지연 큐잉.
     */
    Config<Boolean> foliaRegionFixConfig = register(new BooleanConfig(
            "FoliaRegionFix", "Delays interactions 1 tick for Folia region threading", false,
            () -> modeConfig.getValue() == Anticheats.FOLIA
               || modeConfig.getValue() == Anticheats.CUSTOM));

    // ════════════════════════════════════════════════════════════
    // 내부 상태
    // ════════════════════════════════════════════════════════════

    private final Timer raytraceTimer = new CacheTimer();

    /** NCP RaytraceFix용 주입 pitch */
    private float ncpPitch = Float.NaN;

    /**
     * Setback 후 억제 남은 틱 수.
     * PlayerPositionLookS2CPacket 수신 시 setbackIgnoreTicksConfig 값으로 설정.
     * 매 TickEvent.PRE마다 1씩 감소.
     */
    private volatile int setbackRemaining = 0;

    /**
     * 마지막으로 서버에 보낸 yaw 값 (YawStep 계산용).
     * MovementPacketsEvent에서 갱신.
     */
    private float lastSentYaw = 0.0f;

    /**
     * PacketOrderFix용: 이번 틱에 이미 Full position 패킷을 보냈는지.
     * TickEvent.PRE마다 false로 리셋.
     */
    private boolean positionSentThisTick = false;

    /**
     * FoliaRegionFix용: 다음 틱에 전송할 큐잉된 상호작용 패킷.
     * (nullable - 큐에 없으면 null)
     */
    private net.minecraft.network.packet.Packet<?> pendingFoliaPacket = null;

    public AnticheatModule()
    {
        super("Anticheat", "Comprehensive anticheat compatibility settings", ModuleCategory.CLIENT);
        INSTANCE = this;
    }

    public static AnticheatModule getInstance()
    {
        return INSTANCE;
    }

    // ════════════════════════════════════════════════════════════
    // 이벤트 처리
    // ════════════════════════════════════════════════════════════

    /**
     * 매 클라이언트 틱 (PRE):
     * - setbackRemaining 감소
     * - positionSentThisTick 리셋
     * - FoliaRegionFix 큐 처리
     */
    @EventListener
    public void onTick(TickEvent event)
    {
        if (event.getStage() != StageEvent.EventStage.PRE) return;
        if (mc.player == null) return;

        // Setback 틱 카운터 감소
        if (setbackRemaining > 0)
            setbackRemaining--;

        // PacketOrderFix 리셋
        positionSentThisTick = false;

        // FoliaRegionFix: 이전 틱에 큐된 패킷 처리
        if (foliaRegionFixConfig.getValue() && pendingFoliaPacket != null)
        {
            Managers.NETWORK.sendPacket(pendingFoliaPacket);
            pendingFoliaPacket = null;
        }
    }

    /**
     * 이동 패킷 이벤트:
     *  1. SetbackTicks  - setback 중이면 위치 패킷 억제 (onGround만)
     *  2. YawStep       - yaw 변화량 clamp
     *  3. MovementSync  - Full 패킷 강제 (위치 변화 없어도)
     *
     * 이 이벤트는 MixinClientPlayerEntity.hookSendMovementPackets에서
     * sendMovementPackets() 호출 직전에 디스패치된다.
     * cancel() 시 커스텀 패킷 로직으로 대체.
     */
    @EventListener(priority = 5000)
    public void onMovementPackets(MovementPacketsEvent event)
    {
        if (mc.player == null) return;

        float yaw   = event.getYaw();
        float pitch = event.getPitch();

        // ── YawStep: 틱당 yaw 변화량 제한 ────────────────────────
        float step = yawStepConfig.getValue();
        if (step > 0.0f)
        {
            float diff = MathHelper.wrapDegrees(yaw - lastSentYaw);
            if (Math.abs(diff) > step)
            {
                // 변화 방향은 유지하되 크기를 step으로 제한
                yaw = lastSentYaw + Math.signum(diff) * step;
                event.setYaw(yaw);
            }
        }
        lastSentYaw = event.getYaw();

        // ── SetbackTicks: teleport 직후 이동 억제 ─────────────────
        // PlayerPositionLookS2CPacket(setback) 수신 후 N틱 동안
        // x/y/z를 이전 값과 동일하게 유지해 위치 변화를 0으로 만든다.
        // Mixin에서 squaredMagnitude(d,e,f) > 2e-4 조건 미충족
        // → bl2=false → OnGroundOnly 패킷만 전송
        // → 서버에서 "moved too quickly" 위반 방지
        if (setbackRemaining > 0)
        {
            // 이동 없음: 위치를 변경하지 않고 onGround만 전달
            // event.cancel() 후 Mixin이 변화량 계산:
            // d=e=f=0 → bl2 = (ticksSinceLastPositionPacketSent >= 20)
            // ticksSinceLastPositionPacketSent를 0으로 리셋해 bl2=false 보장
            ((AccessorClientPlayerEntity) mc.player)
                    .hookSetTicksSinceLastPositionPacketSent(0);
            event.cancel();
            return;
        }

        // ── MovementSync: 항상 Full 패킷 강제 (Grim 예측 동기화) ──
        // Grim은 서버 틱마다 플레이어 위치를 물리 시뮬레이션으로 예측.
        // 클라이언트가 OnGroundOnly나 LookAndOnGround만 보내면
        // 서버 예측과 클라이언트 실제 위치 사이에 오차가 누적됨.
        //
        // 구현: ticksSinceLastPositionPacketSent를 20으로 설정.
        // MixinClientPlayerEntity의 hookSendMovementPackets 로직에서
        // ticksSinceLastPositionPacketSent >= 20 이면 bl2=true → Full 패킷 전송.
        // 이 방식으로 기존 Mixin 패킷 전송 로직을 그대로 활용하면서
        // 위치 변화 없어도 Full 패킷을 강제한다.
        if (movementSyncConfig.getValue() && isGrim() && !event.isCanceled())
        {
            ((AccessorClientPlayerEntity) mc.player)
                    .hookSetTicksSinceLastPositionPacketSent(20);
            positionSentThisTick = true;
        }
    }

    /**
     * 아웃바운드 패킷 처리:
     *  1. NCP RaytraceFix   - pitch 주입
     *  2. GrimMiningFix     - 채굴 시퀀스 수정
     *  3. PacketOrderFix    - InteractBlock 전 Full 위치 패킷 보장
     *  4. FoliaRegionFix    - InteractBlock을 다음 틱으로 지연
     */
    @EventListener(priority = 10000)
    public void onPacketOutbound(PacketEvent.Outbound event)
    {
        if (mc.player == null || mc.world == null) return;

        // ── NCP RaytraceFix: 블록 상호작용 전 pitch 감지 ────────────
        // NCP는 블록 상호작용 시 플레이어의 시선이 해당 블록을 향하는지
        // 서버에서 raycast로 검증한다.
        // 실제 위치에서 raycast가 닿지 않으면 kick.
        // pitch를 -75도(위쪽)로 스푸핑하면 대부분의 raycast 검증 우회.
        if (isNCP() && raytraceSpoofConfig.getValue()
                && event.getPacket() instanceof PlayerInteractBlockC2SPacket packet
                && raytraceTimer.passed(250))
        {
            BlockHitResult packetResult = packet.getBlockHitResult();
            BlockPos pos = packetResult.getBlockPos();
            BlockHitResult result = mc.world.raycast(new RaycastContext(
                    mc.player.getEyePos(),
                    DirectionUtil.getDirectionOffsetPos(pos, packetResult.getSide()),
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, mc.player));
            if (mc.world.isSpaceEmpty(mc.player.getBoundingBox().stretch(0.0, 0.15, 0.0))
                    && result != null
                    && result.getType() == HitResult.Type.BLOCK
                    && !result.getBlockPos().equals(pos))
            {
                ncpPitch = -75.0f;
                raytraceTimer.reset();
            }
        }

        // NCP RaytraceFix pitch 주입: PlayerMoveC2SPacket에 스푸핑된 pitch 삽입
        if (event.getPacket() instanceof PlayerMoveC2SPacket packet
                && packet.changesLook() && !Float.isNaN(ncpPitch))
        {
            ((AccessorPlayerMoveC2SPacket) packet).hookSetPitch(ncpPitch);
            ncpPitch = Float.NaN;
        }

        // ── Grim MiningFix ────────────────────────────────────────
        // Grim v3에서 채굴 시작 시 서버가 잘못된 채굴 상태를 가질 수 있음.
        // START → STOP+START+ABORT 시퀀스로 서버 채굴 상태를 리셋한 뒤 실제 채굴 시작.
        if (isGrim() && miningConfig.getValue() == Mining.GRIM_STRICT
                && event.getPacket() instanceof PlayerActionC2SPacket packet)
        {
            var action = packet.getAction();

            // 부서지지 않는 블록(흑요석 등)에 대한 채굴 패킷은 취소
            // 서버에 불필요한 채굴 상태를 만들지 않음
            if ((action == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK
                    || action == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
                    || action == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK)
                    && BlastResistantBlocks.isUnbreakable(packet.getPos()))
            {
                event.cancel();
                return;
            }

            if (action == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK)
            {
                // 순서: STOP → START → ABORT (상태 리셋) → 원본 START
                Managers.NETWORK.sendQuietPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                        packet.getPos(), Direction.UP));
                Managers.NETWORK.sendQuietPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                        packet.getPos(), Direction.UP));
                Managers.NETWORK.sendQuietPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                        packet.getPos(), Direction.UP));
                event.cancel();
            }
        }

        // ── PacketOrderFix: Paper/Folia 상호작용 전 위치 보장 ────────
        // Paper는 클라이언트가 상호작용 전에 올바른 위치에 있음을
        // 알아야 한다. 이 틱에 이미 Full 위치 패킷을 보냈으면 OK.
        // 아직 안 보냈으면 PlayerInteractBlockC2SPacket 직전에 강제 전송.
        if (packetOrderFixConfig.getValue()
                && event.getPacket() instanceof PlayerInteractBlockC2SPacket
                && !positionSentThisTick)
        {
            // InteractBlock 직전에 현재 위치의 Full 패킷 전송
            Managers.NETWORK.sendQuietPacket(new PlayerMoveC2SPacket.Full(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    mc.player.getYaw(), mc.player.getPitch(),
                    mc.player.isOnGround()));
            positionSentThisTick = true;
        }

        // ── FoliaRegionFix: InteractBlock을 다음 틱으로 지연 ─────────
        // Folia는 청크 리전마다 별도 스레드에서 패킷을 처리.
        // 이동 직후의 상호작용이 이동 패킷보다 먼저 처리될 수 있음.
        // 해결: 이동 패킷 전송 후 1틱 지연하고 상호작용 패킷 전송.
        // 이 틱에 이미 위치 패킷을 보냈다면 다음 틱에 상호작용 전송.
        if (foliaRegionFixConfig.getValue()
                && event.getPacket() instanceof PlayerInteractBlockC2SPacket)
        {
            if (pendingFoliaPacket == null)
            {
                // 이번 상호작용을 1틱 지연
                pendingFoliaPacket = event.getPacket();
                event.cancel();
                // 위치 패킷을 먼저 전송 (아직 안 보냈으면)
                if (!positionSentThisTick)
                {
                    Managers.NETWORK.sendQuietPacket(new PlayerMoveC2SPacket.Full(
                            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                            mc.player.getYaw(), mc.player.getPitch(),
                            mc.player.isOnGround()));
                    positionSentThisTick = true;
                }
            }
            // pendingFoliaPacket != null 이면 이미 큐에 있으니 그냥 통과
        }
    }

    /**
     * 인바운드 패킷 처리:
     *  1. SetbackTicks  - PlayerPositionLookS2CPacket 감지 → 억제 틱 시작
     *  2. NoScreenClose - CloseScreenS2CPacket(syncId=0) 차단
     *  3. NoServerSlot  - UpdateSelectedSlotS2CPacket 차단
     */
    @EventListener(priority = 10000)
    public void onPacketInbound(PacketEvent.Inbound event)
    {
        if (mc.player == null || mc.world == null) return;

        // ── SetbackTicks: Teleport/Setback 감지 ───────────────────
        // Grim은 플레이어가 너무 빠르게 움직이면 이전 위치로 텔레포트(setback).
        // 텔레포트 직후 이동 패킷을 보내면 "moved too quickly" 재발.
        // N틱 동안 이동 억제로 서버와 동기화 회복.
        if (setbackIgnoreTicksConfig.getValue() > 0
                && event.getPacket() instanceof PlayerPositionLookS2CPacket)
        {
            setbackRemaining = setbackIgnoreTicksConfig.getValue();
        }

        // ── NoScreenClose: 인벤토리 강제 닫기 차단 ────────────────
        // Grim은 서버 인벤토리를 강제로 닫아 클라이언트 상태를 검증.
        // 이 패킷을 무시하면 인벤토리 조작 중에도 닫히지 않음.
        if (noScreenCloseConfig.getValue()
                && event.getPacket() instanceof CloseScreenS2CPacket packet
                && packet.getSyncId() == 0)
        {
            event.cancel();
            return;
        }

        // ── NoServerSlot: 핫바 슬롯 강제 변경 차단 ────────────────
        // 서버가 클라이언트의 선택된 슬롯을 바꾸려 할 때 무시.
        // AutoCrystal 사용 중 슬롯이 튀는 현상 방지.
        if (noServerSlotConfig.getValue()
                && event.getPacket() instanceof UpdateSelectedSlotS2CPacket)
        {
            event.cancel();
        }
    }

    @EventListener
    public void onDisconnect(DisconnectEvent event)
    {
        // 연결 끊기면 모든 내부 상태 초기화
        setbackRemaining    = 0;
        positionSentThisTick = false;
        pendingFoliaPacket  = null;
        ncpPitch            = Float.NaN;
        lastSentYaw         = 0.0f;
    }

    /**
     * 프리셋 변경 시 모든 하위 설정을 해당 안티치트 최적값으로 자동 적용.
     */
    @EventListener
    public void onConfigUpdate(ConfigUpdateEvent event)
    {
        if (event.getStage() == StageEvent.EventStage.POST
                && event.getConfig() == modeConfig)
        {
            applyPreset(modeConfig.getValue());
        }

        if (event.getStage() == StageEvent.EventStage.POST
                && event.getConfig() == raytraceSpoofConfig
                && !raytraceSpoofConfig.getValue())
        {
            ncpPitch = Float.NaN;
        }
    }

    // ════════════════════════════════════════════════════════════
    // 프리셋 자동 적용
    // ════════════════════════════════════════════════════════════

    private void applyPreset(Anticheats ac)
    {
        switch (ac)
        {
            case GRIM ->
            {
                noServerSlotConfig.setValue(true);
                noScreenCloseConfig.setValue(false);
                rotationsConfig.setValue(Rotations.SILENT);
                yawStepConfig.setValue(1.0f);
                movementSyncConfig.setValue(true);
                webJumpFixConfig.setValue(false);
                strictDirectionConfig.setValue(StrictDirection.GRIM);
                placementsConfig.setValue(Placements.GRIM);
                miningConfig.setValue(Mining.GRIM_STRICT);
                assumeBlockBreakingConfig.setValue(true);
                raytraceSpoofConfig.setValue(false);
                setbackIgnoreTicksConfig.setValue(3);
                packetOrderFixConfig.setValue(false);
                foliaRegionFixConfig.setValue(false);
            }
            case N_C_P ->
            {
                noServerSlotConfig.setValue(false);
                noScreenCloseConfig.setValue(false);
                rotationsConfig.setValue(Rotations.FULL);
                yawStepConfig.setValue(10.0f);
                movementSyncConfig.setValue(false);
                webJumpFixConfig.setValue(false);
                strictDirectionConfig.setValue(StrictDirection.NCP);
                placementsConfig.setValue(Placements.NCP);
                miningConfig.setValue(Mining.NCP);
                assumeBlockBreakingConfig.setValue(false);
                raytraceSpoofConfig.setValue(true);
                setbackIgnoreTicksConfig.setValue(0);
                packetOrderFixConfig.setValue(false);
                foliaRegionFixConfig.setValue(false);
            }
            case PAPER ->
            {
                noServerSlotConfig.setValue(false);
                noScreenCloseConfig.setValue(false);
                rotationsConfig.setValue(Rotations.SILENT);
                yawStepConfig.setValue(0.0f);
                movementSyncConfig.setValue(false);
                webJumpFixConfig.setValue(false);
                strictDirectionConfig.setValue(StrictDirection.STRICT);
                placementsConfig.setValue(Placements.PAPER);
                miningConfig.setValue(Mining.PAPER);
                assumeBlockBreakingConfig.setValue(false);
                raytraceSpoofConfig.setValue(false);
                setbackIgnoreTicksConfig.setValue(0);
                packetOrderFixConfig.setValue(true);
                foliaRegionFixConfig.setValue(false);
            }
            case FOLIA ->
            {
                noServerSlotConfig.setValue(false);
                noScreenCloseConfig.setValue(false);
                rotationsConfig.setValue(Rotations.SILENT);
                yawStepConfig.setValue(0.0f);
                movementSyncConfig.setValue(false);
                webJumpFixConfig.setValue(false);
                strictDirectionConfig.setValue(StrictDirection.STRICT);
                placementsConfig.setValue(Placements.FOLIA);
                miningConfig.setValue(Mining.PAPER);
                assumeBlockBreakingConfig.setValue(false);
                raytraceSpoofConfig.setValue(false);
                setbackIgnoreTicksConfig.setValue(0);
                packetOrderFixConfig.setValue(true);
                foliaRegionFixConfig.setValue(true);
            }
            case VANILLA ->
            {
                noServerSlotConfig.setValue(false);
                noScreenCloseConfig.setValue(false);
                rotationsConfig.setValue(Rotations.NONE);
                yawStepConfig.setValue(0.0f);
                movementSyncConfig.setValue(false);
                webJumpFixConfig.setValue(false);
                strictDirectionConfig.setValue(StrictDirection.NONE);
                placementsConfig.setValue(Placements.VANILLA);
                miningConfig.setValue(Mining.VANILLA);
                assumeBlockBreakingConfig.setValue(false);
                raytraceSpoofConfig.setValue(false);
                setbackIgnoreTicksConfig.setValue(0);
                packetOrderFixConfig.setValue(false);
                foliaRegionFixConfig.setValue(false);
            }
            case CUSTOM -> { /* 수동 제어 */ }
        }
    }

    // ════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════

    /**
     * Grim 모드 여부.
     * forcePreset=false: AntiCheatManager 자동 감지 OR 수동 GRIM 선택
     * forcePreset=true:  수동 Mode 설정만 사용
     */
    public boolean isGrim()
    {
        if (forcePresetConfig.getValue())
            return modeConfig.getValue() == Anticheats.GRIM;
        return modeConfig.getValue() == Anticheats.GRIM
                || Managers.ANTICHEAT.isGrim();
    }

    public boolean isNCP()    { return modeConfig.getValue() == Anticheats.N_C_P;   }
    public boolean isPaper()  { return modeConfig.getValue() == Anticheats.PAPER;    }
    public boolean isFolia()  { return modeConfig.getValue() == Anticheats.FOLIA;    }
    public boolean isVanilla(){ return modeConfig.getValue() == Anticheats.VANILLA;  }
    public boolean is2b2t()   { return b2t2Config.getValue();                         }

    /** 기존 API 호환 */
    public boolean getMiningFix()
    {
        return miningConfig.getValue() == Mining.GRIM_STRICT
                || miningConfig.getValue() == Mining.NCP;
    }

    public boolean getWebJumpFix()         { return webJumpFixConfig.getValue();        }
    public boolean getMovementSync()       { return movementSyncConfig.getValue();      }
    public StrictDirection getStrictDirection() { return strictDirectionConfig.getValue(); }
    public boolean isStrictDirection()     { return strictDirectionConfig.getValue() != StrictDirection.NONE; }
    public Placements getPlacements()      { return placementsConfig.getValue();        }
    public Mining getMining()              { return miningConfig.getValue();            }
    public Rotations getRotations()        { return rotationsConfig.getValue();         }
    public float getYawStep()             { return yawStepConfig.getValue();           }
    public boolean isNoServerSlot()        { return noServerSlotConfig.getValue();      }
    public boolean isAssumeBlockBreaking() { return assumeBlockBreakingConfig.getValue(); }
    public int getSetbackIgnoreTicks()     { return setbackIgnoreTicksConfig.getValue(); }
    public boolean isPacketOrderFix()      { return packetOrderFixConfig.getValue();    }
    public boolean isFoliaRegionFix()      { return foliaRegionFixConfig.getValue();    }

    /** 기존 API 호환 */
    public int getEntityPlaceThreshold()
    {
        return isVanilla() ? 0 : Math.round(entityPlaceConfig.getValue() * 10.0f);
    }

    // ════════════════════════════════════════════════════════════
    // Enum 정의
    // ════════════════════════════════════════════════════════════

    public enum Anticheats
    {
        GRIM,    // GrimAC - 2b2t, 2b2t Korea
        N_C_P,   // NoCheatPlus
        PAPER,   // Paper / Purpur
        FOLIA,   // Folia 멀티스레드
        VANILLA, // 바닐라 / 안티치트 없음
        CUSTOM   // 수동 제어
    }

    public enum Rotations
    {
        SILENT, // 패킷만 회전, 카메라 고정 (Grim 권장)
        FULL,   // 카메라도 실제 회전 (NCP 호환)
        NONE    // 회전 패킷 없음
    }

    public enum StrictDirection
    {
        NONE,   // 방향 검증 없음
        GRIM,   // Grim: raycast 보이는 면만
        NCP,    // NCP: 인접 블록 면 기준
        STRICT  // 가장 엄격: 실제 바라보는 면만
    }

    public enum Placements
    {
        VANILLA, // 표준 배치
        GRIM,    // Grim air-place + sequence
        NCP,     // NCP 검증 우회
        PAPER,   // Paper 검증 통과
        FOLIA    // Folia 안전 배치
    }

    public enum Mining
    {
        VANILLA,     // 표준 채굴
        GRIM_STRICT, // STOP→START→ABORT 시퀀스
        NCP,         // NCP 타이밍 조정
        PAPER        // Paper 검증 통과
    }
}
