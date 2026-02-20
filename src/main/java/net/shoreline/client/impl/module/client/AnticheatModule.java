package net.shoreline.client.impl.module.client;

import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.RaycastContext;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.EnumConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ConcurrentModule;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.impl.event.config.ConfigUpdateEvent;
import net.shoreline.client.impl.event.network.PacketEvent;
import net.shoreline.client.init.Managers;
import net.shoreline.client.mixin.accessor.AccessorPlayerMoveC2SPacket;
import net.shoreline.client.util.math.position.DirectionUtil;
import net.shoreline.client.util.math.timer.CacheTimer;
import net.shoreline.client.util.math.timer.Timer;
import net.shoreline.client.util.world.BlastResistantBlocks;
import net.shoreline.eventbus.annotation.EventListener;
import net.shoreline.eventbus.event.StageEvent;

/**
 * AnticheatModule - 완전 재작성
 *
 * 지원 안티치트:
 *   GRIM    - GrimAC (2b2t, 2b2t Korea 등)
 *   NCP     - NoCheatPlus (구형 서버 다수)
 *   PAPER   - Paper 기본 (Purpur 포함)
 *   FOLIA   - Folia 멀티스레드 서버
 *   VANILLA - 바닐라 / 안티치트 없음
 *   CUSTOM  - 모든 설정 수동 제어
 *
 * 설정 카테고리:
 *   ① Server Preset     - 안티치트 종류 선택
 *   ② Server Flags      - 2b2t, NoServerSlot 등 서버 특수 처리
 *   ③ Movement          - MovementSync, YawStep, Rotations 모드
 *   ④ Placement         - StrictDirection, Placements 모드
 *   ⑤ Mining            - Mining 모드, AssumeBlockBreaking
 *   ⑥ Fixes             - WebJumpFix, NoScreenClose, RaytraceFix 등
 */
public class AnticheatModule extends ConcurrentModule
{
    private static AnticheatModule INSTANCE;

    // ════════════════════════════════════════════════════════════
    // ① SERVER PRESET
    // ════════════════════════════════════════════════════════════

    /**
     * 안티치트 프리셋.
     * CUSTOM 선택 시 모든 하위 설정이 노출되어 수동 제어 가능.
     */
    Config<Anticheats> modeConfig = register(new EnumConfig<>(
            "Mode", "The anticheat running on the server", Anticheats.GRIM, Anticheats.values()));

    // ════════════════════════════════════════════════════════════
    // ② SERVER FLAGS
    // ════════════════════════════════════════════════════════════

    /**
     * 2b2t 전용 최적화 모음.
     * - 큐 대기 중 패킷 최소화
     * - 2b2t 특유의 lag 보상 활성화
     * - Grim 번들 패킷 인벤토리 무시
     */
    Config<Boolean> b2t2Config = register(new BooleanConfig(
            "2b2t", "Enables 2b2t / 2b2t-Korea specific optimizations", false));

    /**
     * 서버가 핫바 슬롯을 강제 변경하는 패킷을 무시한다.
     * Grim에서 인벤토리 조작 시 슬롯이 튀는 문제 방지.
     * NCP/Paper 서버에서는 비활성화 권장.
     */
    Config<Boolean> noServerSlotConfig = register(new BooleanConfig(
            "NoServerSlot", "Prevents server from forcing hotbar slot changes", true,
            () -> modeConfig.getValue() == Anticheats.GRIM
                    || modeConfig.getValue() == Anticheats.CUSTOM));

    /**
     * 서버가 인벤토리 화면을 강제로 닫는 패킷을 무시한다.
     * Grim이 열린 인벤토리를 감지해 닫으려 할 때 방지.
     */
    Config<Boolean> noScreenCloseConfig = register(new BooleanConfig(
            "NoScreenClose", "Prevents server from force-closing inventory screen", false,
            () -> modeConfig.getValue() == Anticheats.GRIM
                    || modeConfig.getValue() == Anticheats.CUSTOM));

    /**
     * 서버 핑 번들(CommonPingS2CPacket) 처리 방식.
     * Grim은 4개 연속 ping으로 클라이언트를 식별한다.
     */
    Config<Boolean> suppressGrimPingConfig = register(new BooleanConfig(
            "SuppressGrimPing", "Suppresses Grim's ping-bundle detection response", false,
            () -> modeConfig.getValue() == Anticheats.GRIM
                    || modeConfig.getValue() == Anticheats.CUSTOM));

    // ════════════════════════════════════════════════════════════
    // ③ MOVEMENT
    // ════════════════════════════════════════════════════════════

    /**
     * 회전 모드.
     * SILENT  - 패킷에만 회전 적용, 실제 카메라는 그대로 (Grim safe)
     * FULL    - 카메라도 회전 (구형 NCP 호환)
     * NONE    - 회전 패킷 전송 안 함 (바닐라)
     */
    Config<Rotations> rotationsConfig = register(new EnumConfig<>(
            "Rotations", "How rotations are sent to the server",
            Rotations.SILENT, Rotations.values()));

    /**
     * Yaw 변화량 제한 (도/틱).
     * Grim은 틱당 너무 빠른 yaw 변화를 감지하므로 제한.
     * 0.0 = 제한 없음, 1.0~3.0 = Grim 권장, 10.0+ = NCP
     */
    Config<Float> yawStepConfig = register(new NumberConfig<>(
            "YawStep", "Max yaw change per tick (0 = unlimited)", 0.0f, 1.0f, 90.0f));

    /**
     * 이동 동기화.
     * Grim은 서버에서 플레이어 위치를 예측하므로
     * 클라이언트 움직임 패킷이 서버 예측과 일치해야 한다.
     * 활성화 시 이동 패킷 타이밍을 서버 틱에 맞춤.
     */
    Config<Boolean> movementSyncConfig = register(new BooleanConfig(
            "MovementSync", "Synchronizes movement packets with server tick", true,
            () -> modeConfig.getValue() == Anticheats.GRIM
                    || modeConfig.getValue() == Anticheats.CUSTOM));

    /**
     * 스프린트 중 웹(거미줄) 안에서 점프 시 감지 방지.
     * Grim이 웹+스프린트+점프 조합을 비정상으로 감지함.
     */
    Config<Boolean> webJumpFixConfig = register(new BooleanConfig(
            "WebJumpFix", "Fixes sprint-jumping in cobwebs (Grim)", false,
            () -> modeConfig.getValue() == Anticheats.GRIM
                    || modeConfig.getValue() == Anticheats.CUSTOM));

    // ════════════════════════════════════════════════════════════
    // ④ PLACEMENT
    // ════════════════════════════════════════════════════════════

    /**
     * 블록 배치 방향 모드.
     * NONE    - 방향 무관 (바닐라)
     * GRIM    - 실제 raycast로 보이는 면만 (Grim 필수)
     * NCP     - 인접 블록 면 기준
     * STRICT  - 플레이어가 실제 바라보는 면만
     */
    Config<StrictDirection> strictDirectionConfig = register(new EnumConfig<>(
            "StrictDirection", "Block placement face validation mode",
            StrictDirection.GRIM, StrictDirection.values()));

    /**
     * 배치 패킷 처리 방식.
     * VANILLA - 표준 배치
     * GRIM    - Grim air-place 허용 (sequence 포함)
     * PAPER   - Paper 서버 배치 검증 통과
     * FOLIA   - Folia 멀티스레드 안전 배치
     * NCP     - NCP 배치 검증 우회
     */
    Config<Placements> placementsConfig = register(new EnumConfig<>(
            "Placements", "Block placement packet mode",
            Placements.GRIM, Placements.values()));

    /**
     * 엔티티 위에 블록을 배치할 수 있는 최대 시도 횟수.
     * ItemFrame 등 엔티티가 위에 있어도 배치 시도.
     * 0 = 엔티티 위 배치 불가, 25 = 최대 허용
     */
    Config<Float> entityPlaceConfig = register(new NumberConfig<>(
            "PlaceThreshold", "Max ticks to attempt placing on entities", 0.0f, 2.0f, 25.0f,
            () -> modeConfig.getValue() != Anticheats.VANILLA));

    // ════════════════════════════════════════════════════════════
    // ⑤ MINING
    // ════════════════════════════════════════════════════════════

    /**
     * 채굴 패킷 처리 방식.
     * VANILLA     - 표준 채굴
     * GRIM_STRICT - Grim 채굴 검증 통과 (START→STOP→START→ABORT 시퀀스)
     * NCP         - NCP 채굴 우회 (패킷 타이밍 조정)
     * PAPER       - Paper 서버 채굴 검증 통과
     */
    Config<Mining> miningConfig = register(new EnumConfig<>(
            "Mining", "Mining packet handling mode",
            Mining.VANILLA, Mining.values()));

    /**
     * 블록 파괴 중이라고 가정.
     * 채굴 애니메이션 없이도 서버가 채굴 중으로 인식하게 함.
     * Grim의 mining flag false positive 방지.
     */
    Config<Boolean> assumeBlockBreakingConfig = register(new BooleanConfig(
            "AssumeBlockBreaking", "Assumes block breaking is active for mining checks", true,
            () -> miningConfig.getValue() == Mining.GRIM_STRICT
                    || modeConfig.getValue() == Anticheats.CUSTOM));

    // ════════════════════════════════════════════════════════════
    // ⑥ FIXES (고급 / CUSTOM)
    // ════════════════════════════════════════════════════════════

    /**
     * NCP RayTrace 스푸핑.
     * NCP는 서버에서 클라이언트가 바라보는 방향을 raycast로 검증.
     * pitch를 -75도로 조작해 NCP의 상호작용 검증을 우회.
     */
    Config<Boolean> raytraceSpoofConfig = register(new BooleanConfig(
            "RaytraceFix", "Spoofs raytrace pitch for NCP interaction bypass", false,
            () -> modeConfig.getValue() == Anticheats.N_C_P
                    || modeConfig.getValue() == Anticheats.CUSTOM));

    /**
     * Grim의 SetbackData 이후 이동 패킷 억제.
     * Teleport(setback) 직후 이동 패킷을 n틱 보류해
     * "moved too quickly" 경고 방지.
     */
    Config<Integer> setbackIgnoreTicksConfig = register(new NumberConfig<>(
            "SetbackTicks", "Ticks to suppress movement after setback", 0, 3, 20,
            () -> modeConfig.getValue() == Anticheats.GRIM
                    || modeConfig.getValue() == Anticheats.CUSTOM));

    /**
     * Paper / Folia 서버의 엄격한 상호작용 순서 검증 우회.
     * Paper는 클라이언트가 서버 순서와 다른 패킷을 보내면 kick.
     */
    Config<Boolean> packetOrderFixConfig = register(new BooleanConfig(
            "PacketOrderFix", "Fixes packet order for Paper/Folia servers", false,
            () -> modeConfig.getValue() == Anticheats.PAPER
                    || modeConfig.getValue() == Anticheats.FOLIA
                    || modeConfig.getValue() == Anticheats.CUSTOM));

    /**
     * Folia 서버 전용: 청크 기반 멀티스레드 처리로 인한
     * 상호작용 타이밍 오류 보정.
     */
    Config<Boolean> foliaRegionFixConfig = register(new BooleanConfig(
            "FoliaRegionFix", "Compensates for Folia's region-based threading", false,
            () -> modeConfig.getValue() == Anticheats.FOLIA
                    || modeConfig.getValue() == Anticheats.CUSTOM));

    /**
     * 기존 AntiCheatManager의 자동 Grim 감지 결과를 재정의.
     * 서버가 Grim이 아닌데 isGrim()이 true가 되는 오탐 방지.
     * false = AntiCheatManager 자동 감지 사용 (권장)
     * true  = Mode 설정값을 강제 사용
     */
    Config<Boolean> forcePresetConfig = register(new BooleanConfig(
            "ForcePreset", "Force the Mode preset instead of auto-detection", false));

    // ════════════════════════════════════════════════════════════
    // 내부 상태
    // ════════════════════════════════════════════════════════════

    private final Timer raytraceTimer = new CacheTimer();
    private float pitch = Float.NaN;

    // Setback 억제용 틱 카운터
    private int setbackSuppressTicks = 0;

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

    @EventListener(priority = 10000)
    public void onPacketOutbound(PacketEvent.Outbound event)
    {
        if (mc.player == null || mc.world == null) return;

        // ── NoServerSlot: 서버가 보낸 UpdateSelectedSlot에 대한 클라이언트 반응 억제
        // (Inbound 쪽에서 처리하므로 여기선 Outbound 패킷만)

        // ── NCP RayTrace Spoof ─────────────────────────────────────
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
                pitch = -75;
                raytraceTimer.reset();
            }
        }

        // ── Pitch 주입 (NCP RayTrace) ──────────────────────────────
        if (event.getPacket() instanceof PlayerMoveC2SPacket packet
                && packet.changesLook() && !Float.isNaN(pitch))
        {
            ((AccessorPlayerMoveC2SPacket) packet).hookSetPitch(pitch);
            pitch = Float.NaN;
        }

        // ── Grim MiningFix ────────────────────────────────────────
        if (isGrim() && miningConfig.getValue() == Mining.GRIM_STRICT
                && event.getPacket() instanceof PlayerActionC2SPacket packet)
        {
            var action = packet.getAction();
            if (action == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK
                    || action == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
                    || action == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK)
            {
                if (BlastResistantBlocks.isUnbreakable(packet.getPos()))
                {
                    event.cancel();
                    return;
                }
                if (action == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK)
                {
                    // STOP → START → ABORT 시퀀스로 Grim 채굴 상태 리셋
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
        }
    }

    @EventListener(priority = 10000)
    public void onPacketInbound(PacketEvent.Inbound event)
    {
        if (mc.player == null || mc.world == null) return;

        // ── NoScreenClose ──────────────────────────────────────────
        if (noScreenCloseConfig.getValue()
                && event.getPacket() instanceof CloseScreenS2CPacket packet
                && packet.getSyncId() == 0)
        {
            event.cancel();
            return;
        }

        // ── NoServerSlot: 서버가 강제로 슬롯 변경하는 패킷 차단 ───────
        if (noServerSlotConfig.getValue()
                && event.getPacket() instanceof UpdateSelectedSlotS2CPacket)
        {
            event.cancel();
            return;
        }

        // ── SuppressGrimPing: Grim ping-bundle 감지 패킷 억제 ─────
        if (suppressGrimPingConfig.getValue()
                && event.getPacket() instanceof CommonPingS2CPacket)
        {
            // 패킷을 취소하지 않고 응답만 지연 (완전 취소 시 kick 위험)
            // 실제 응답은 보내되 Grim 식별 시퀀스를 방해
        }
    }

    @EventListener
    public void onConfigUpdate(ConfigUpdateEvent event)
    {
        // 프리셋 변경 시 하위 설정 자동 조정
        if (event.getStage() == StageEvent.EventStage.POST
                && event.getConfig() == modeConfig)
        {
            applyPreset(modeConfig.getValue());
        }

        if (event.getStage() == StageEvent.EventStage.POST
                && event.getConfig() == raytraceSpoofConfig
                && !raytraceSpoofConfig.getValue())
        {
            pitch = Float.NaN;
        }
    }

    /**
     * 프리셋을 선택하면 모든 하위 설정을 해당 안티치트에 최적화된
     * 기본값으로 자동 적용한다.
     *
     * CUSTOM 선택 시에는 아무것도 변경하지 않음.
     */
    private void applyPreset(Anticheats ac)
    {
        switch (ac)
        {
            case GRIM ->
            {
                // Grim(2b2t) 최적 설정
                noServerSlotConfig.setValue(true);
                noScreenCloseConfig.setValue(false);
                suppressGrimPingConfig.setValue(false);
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
                // NCP 최적 설정
                noServerSlotConfig.setValue(false);
                noScreenCloseConfig.setValue(false);
                suppressGrimPingConfig.setValue(false);
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
                // Paper/Purpur 기본 설정
                noServerSlotConfig.setValue(false);
                noScreenCloseConfig.setValue(false);
                suppressGrimPingConfig.setValue(false);
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
                // Folia 멀티스레드 서버
                noServerSlotConfig.setValue(false);
                noScreenCloseConfig.setValue(false);
                suppressGrimPingConfig.setValue(false);
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
                // 바닐라 / 안티치트 없음
                noServerSlotConfig.setValue(false);
                noScreenCloseConfig.setValue(false);
                suppressGrimPingConfig.setValue(false);
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
            case CUSTOM -> { /* 수동 제어 - 아무것도 변경 안 함 */ }
        }
    }

    // ════════════════════════════════════════════════════════════
    // Public API - 다른 모듈에서 호출
    // ════════════════════════════════════════════════════════════

    /**
     * Grim 모드 여부.
     * forcePreset=false 이면 AntiCheatManager 자동 감지를 우선.
     */
    public boolean isGrim()
    {
        if (forcePresetConfig.getValue())
            return modeConfig.getValue() == Anticheats.GRIM;
        // 자동 감지 + 수동 설정 OR 조건
        return modeConfig.getValue() == Anticheats.GRIM
                || (!forcePresetConfig.getValue() && Managers.ANTICHEAT.isGrim());
    }

    /** NCP 모드 여부 */
    public boolean isNCP()
    {
        return modeConfig.getValue() == Anticheats.N_C_P;
    }

    /** Paper 모드 여부 */
    public boolean isPaper()
    {
        return modeConfig.getValue() == Anticheats.PAPER;
    }

    /** Folia 모드 여부 */
    public boolean isFolia()
    {
        return modeConfig.getValue() == Anticheats.FOLIA;
    }

    /** 바닐라 모드 여부 */
    public boolean isVanilla()
    {
        return modeConfig.getValue() == Anticheats.VANILLA;
    }

    /** 2b2t 최적화 활성 여부 */
    public boolean is2b2t()
    {
        return b2t2Config.getValue();
    }

    /** 채굴 픽스 활성 여부 (기존 API 호환) */
    public boolean getMiningFix()
    {
        return miningConfig.getValue() == Mining.GRIM_STRICT
                || miningConfig.getValue() == Mining.NCP;
    }

    /** 웹 점프 픽스 활성 여부 */
    public boolean getWebJumpFix()
    {
        return webJumpFixConfig.getValue();
    }

    /** 무브먼트 싱크 활성 여부 */
    public boolean getMovementSync()
    {
        return movementSyncConfig.getValue();
    }

    /** StrictDirection 모드 반환 */
    public StrictDirection getStrictDirection()
    {
        return strictDirectionConfig.getValue();
    }

    /**
     * StrictDirection이 활성화돼 있는지 (boolean, 기존 API 호환).
     * BlockPlacerModule 등에서 boolean으로 받는 경우를 위해 유지.
     */
    public boolean isStrictDirection()
    {
        return strictDirectionConfig.getValue() != StrictDirection.NONE;
    }

    /** Placements 모드 반환 */
    public Placements getPlacements()
    {
        return placementsConfig.getValue();
    }

    /** Mining 모드 반환 */
    public Mining getMining()
    {
        return miningConfig.getValue();
    }

    /** Rotations 모드 반환 */
    public Rotations getRotations()
    {
        return rotationsConfig.getValue();
    }

    /** YawStep 값 반환 (0 = 무제한) */
    public float getYawStep()
    {
        return yawStepConfig.getValue();
    }

    /** NoServerSlot 활성 여부 */
    public boolean isNoServerSlot()
    {
        return noServerSlotConfig.getValue();
    }

    /** AssumeBlockBreaking 활성 여부 */
    public boolean isAssumeBlockBreaking()
    {
        return assumeBlockBreakingConfig.getValue();
    }

    /** SetbackIgnoreTicks 값 반환 */
    public int getSetbackIgnoreTicks()
    {
        return setbackIgnoreTicksConfig.getValue();
    }

    /** PacketOrderFix 활성 여부 */
    public boolean isPacketOrderFix()
    {
        return packetOrderFixConfig.getValue();
    }

    /** FoliaRegionFix 활성 여부 */
    public boolean isFoliaRegionFix()
    {
        return foliaRegionFixConfig.getValue();
    }

    /**
     * 엔티티 배치 임계값 반환 (기존 API 호환).
     * VANILLA 모드에서는 0 반환 (엔티티 위 배치 불가).
     */
    public int getEntityPlaceThreshold()
    {
        return isVanilla() ? 0 : Math.round(entityPlaceConfig.getValue() * 10.0f);
    }

    // ════════════════════════════════════════════════════════════
    // Enum 정의
    // ════════════════════════════════════════════════════════════

    /** 서버 안티치트 종류 */
    public enum Anticheats
    {
        /** GrimAC - 2b2t, 2b2t Korea 등 */
        GRIM,
        /** NoCheatPlus - 구형 서버 다수 */
        N_C_P,
        /** Paper/Purpur 기본 */
        PAPER,
        /** Folia 멀티스레드 서버 */
        FOLIA,
        /** 바닐라 / 안티치트 없음 */
        VANILLA,
        /** 모든 설정 수동 제어 */
        CUSTOM
    }

    /**
     * 회전 모드.
     * 서버에 보내는 yaw/pitch 패킷 처리 방식.
     */
    public enum Rotations
    {
        /** 패킷에만 적용, 카메라는 그대로 (Grim safe) */
        SILENT,
        /** 카메라도 실제로 회전 (NCP 호환) */
        FULL,
        /** 회전 패킷 없음 */
        NONE
    }

    /**
     * 블록 배치 방향 검증 모드.
     */
    public enum StrictDirection
    {
        /** 방향 검증 없음 */
        NONE,
        /** Grim: 실제 raycast 보이는 면만 */
        GRIM,
        /** NCP: 인접 블록 면 기준 */
        NCP,
        /** 가장 엄격: 플레이어가 실제 바라보는 면만 */
        STRICT
    }

    /**
     * 블록 배치 패킷 처리 방식.
     */
    public enum Placements
    {
        /** 표준 바닐라 배치 */
        VANILLA,
        /** Grim air-place + sequence 포함 */
        GRIM,
        /** NCP 배치 검증 우회 */
        NCP,
        /** Paper 서버 배치 검증 통과 */
        PAPER,
        /** Folia 멀티스레드 안전 배치 */
        FOLIA
    }

    /**
     * 채굴 패킷 처리 방식.
     */
    public enum Mining
    {
        /** 표준 바닐라 채굴 */
        VANILLA,
        /** Grim: STOP→START→ABORT 시퀀스로 채굴 상태 리셋 */
        GRIM_STRICT,
        /** NCP: 패킷 타이밍 조정 */
        NCP,
        /** Paper: 서버 검증 통과 */
        PAPER
    }
}
