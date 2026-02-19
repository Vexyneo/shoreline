package net.shoreline.client.impl.module.movement;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.shoreline.client.ShorelineMod;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.EnumConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.impl.event.MouseUpdateEvent;
import net.shoreline.client.impl.event.camera.CameraRotationEvent;
import net.shoreline.client.impl.event.config.ConfigUpdateEvent;
import net.shoreline.client.impl.event.entity.player.PlayerMoveEvent;
import net.shoreline.client.impl.event.entity.player.TravelEvent;
import net.shoreline.client.impl.event.network.PacketEvent;
import net.shoreline.client.impl.event.network.PlayerTickEvent;
import net.shoreline.client.impl.module.RotationModule;
import net.shoreline.client.init.Managers;
import net.shoreline.client.mixin.accessor.AccessorFireworkRocketEntity;
import net.shoreline.client.util.chat.ChatUtil;
import net.shoreline.client.util.player.MovementUtil;
import net.shoreline.client.util.player.RotationUtil;
import net.shoreline.client.util.string.EnumFormatter;
import net.shoreline.eventbus.annotation.EventListener;
import net.shoreline.eventbus.event.StageEvent;

/**
 * ElytraFly 모듈 - 엘리트라 비행을 다양한 모드로 제어한다.
 *
 * <h2>BOUNCE 상태머신</h2>
 * <pre>
 *  ┌─────────────────────────────────────────────────┐
 *  │                                                 │
 *  ▼                                                 │
 * IDLE ──(엘리트라 장착)──► LAUNCHING                  │
 *                              │                     │
 *                    (공중 + isFallFlying)            │
 *                              │                     │
 *                              ▼                     │
 *                          GLIDING ──(지면 근접)──► PRE_LAND
 *                                                    │
 *                                               (isOnGround)
 *                                                    │
 *                                                    ▼
 *                                                 LANDED ─────────┘
 * </pre>
 *
 * <h2>핵심 버그 수정 목록</h2>
 * <ol>
 *   <li>canSprint()에서 !isFallFlying() 제거 → 글라이딩 중 스프린트 가능</li>
 *   <li>상태머신 도입 → 무분별한 jump()/startFallFlying() 동시 호출 방지</li>
 *   <li>BOUNCE TravelEvent cancel() 추가 → 바닐라 물리 덮어쓰기 방지</li>
 *   <li>cameraPitch와 서버 pitch 완전 분리</li>
 *   <li>LAUNCHING 타임아웃 + 재시도 로직 추가</li>
 * </ol>
 *
 * @author linus (original), rebuilt
 * @since 1.0
 */
public class ElytraFlyModule extends RotationModule
{
    private static ElytraFlyModule INSTANCE;

    // ─── 공통 Config ─────────────────────────────────────────────
    Config<FlyMode> modeConfig = register(new EnumConfig<>("Mode",
            "The mode for elytra flight", FlyMode.CONTROL, FlyMode.values()));

    // CONTROL / 공통 속도
    Config<Float> speedConfig = register(new NumberConfig<>("Speed",
            "The horizontal flight speed", 0.1f, 2.5f, 10.0f,
            () -> modeConfig.getValue() != FlyMode.BOOST && modeConfig.getValue() != FlyMode.BOUNCE));
    Config<Float> vspeedConfig = register(new NumberConfig<>("VerticalSpeed",
            "The vertical flight speed", 0.1f, 1.0f, 5.0f,
            () -> modeConfig.getValue() != FlyMode.BOOST && modeConfig.getValue() != FlyMode.BOUNCE));
    Config<Boolean> accelerateConfig = register(new BooleanConfig("Accelerate",
            "Accelerates fly speed", false,
            () -> modeConfig.getValue() != FlyMode.BOOST && modeConfig.getValue() != FlyMode.BOUNCE));
    Config<Float> accelSpeedConfig = register(new NumberConfig<>("AccelSpeed",
            "Acceleration speed", 0.01f, 0.1f, 1.00f,
            () -> accelerateConfig.getValue()
                    && modeConfig.getValue() != FlyMode.BOOST
                    && modeConfig.getValue() != FlyMode.BOUNCE));
    Config<Float> maxSpeedConfig = register(new NumberConfig<>("MaxSpeed",
            "The maximum flight speed", 0.1f, 3.5f, 10.0f,
            () -> accelerateConfig.getValue()
                    && modeConfig.getValue() != FlyMode.BOOST
                    && modeConfig.getValue() != FlyMode.BOUNCE));
    Config<Boolean> boostConfig = register(new BooleanConfig("VanillaBoost",
            "Uses vanilla boost speed", false,
            () -> modeConfig.getValue() != FlyMode.BOOST));

    // CONTROL 전용
    Config<Boolean> rotateConfig = register(new BooleanConfig("Rotate",
            "Rotates the player when moving", false,
            () -> modeConfig.getValue() == FlyMode.CONTROL));
    Config<Boolean> fireworkConfig = register(new BooleanConfig("Fireworks",
            "Uses fireworks when flying", false,
            () -> modeConfig.getValue() == FlyMode.CONTROL));

    // ─── BOUNCE 전용 Config ──────────────────────────────────────
    Config<Float> pitchConfig = register(new NumberConfig<>("Pitch",
            "The glide pitch angle (higher = steeper dive)", 60.0f, 80.0f, 89.0f,
            () -> modeConfig.getValue() == FlyMode.BOUNCE));
    Config<Float> landHeightConfig = register(new NumberConfig<>("LandHeight",
            "Distance to ground to start landing (blocks)", 0.5f, 2.5f, 10.0f,
            () -> modeConfig.getValue() == FlyMode.BOUNCE));
    Config<Boolean> autoJumpConfig = register(new BooleanConfig("AutoJump",
            "Automatically re-launch after landing", true,
            () -> modeConfig.getValue() == FlyMode.BOUNCE));

    // Baritone 장애물 우회
    Config<Boolean> obstaclePasserConfig = new BooleanConfig("ObstaclePasser",
            "Passes obstacles and resets fly using Baritone",
            false);

    // ─── GRIM 마찰 상수 ──────────────────────────────────────────
    private static final double GRIM_AIR_FRICTION = 0.0264444413;

    // ─── 공통 상태 ───────────────────────────────────────────────
    private boolean resetSpeed;
    private float speed;

    // ─── BOUNCE 상태머신 ─────────────────────────────────────────
    /**
     * BOUNCE 모드 현재 단계.
     * 각 단계별 진입/탈출 조건은 {@link #tickBounceStateMachine()} 참고.
     */
    private BouncePhase bouncePhase = BouncePhase.IDLE;

    /** LAUNCHING 단계에서 엘리트라가 활성화될 때까지 대기한 틱 수 */
    private int launchWaitTicks = 0;

    /** LANDED 단계에서 재발진까지 대기한 틱 수 */
    private int landedWaitTicks = 0;

    /**
     * 글라이딩 방향 (LAUNCHING 직전 yaw 고정).
     * GLIDING 중 마우스 좌우 이동으로 갱신 가능.
     */
    private float glideYaw = 0.0f;

    /**
     * 카메라 피치 - 서버 전송 피치와 완전히 분리.
     * BOUNCE 모드에서 마우스 상하 이동은 카메라만 제어하고
     * 서버로는 pitchConfig 값이 전송됨.
     */
    private float cameraPitch = 0.0f;

    // 타임아웃 상수
    private static final int LAUNCH_TIMEOUT_TICKS = 8;  // 이 틱 안에 글라이딩 안 되면 재시도
    private static final int LAND_WAIT_TICKS       = 3;  // 착지 후 이 틱 뒤에 재발진

    public ElytraFlyModule()
    {
        super("ElytraFly", "Allows you to fly freely using an elytra", ModuleCategory.MOVEMENT);
        INSTANCE = this;
        if (ShorelineMod.isBaritonePresent())
        {
            register(obstaclePasserConfig);
        }
    }

    public static ElytraFlyModule getInstance()
    {
        return INSTANCE;
    }

    @Override
    public String getModuleData()
    {
        if (modeConfig.getValue() == FlyMode.BOUNCE)
        {
            return EnumFormatter.formatEnum(modeConfig.getValue())
                    + " §7[§f" + bouncePhase.name() + "§7]";
        }
        return EnumFormatter.formatEnum(modeConfig.getValue());
    }

    // ─── 라이프사이클 ─────────────────────────────────────────────

    @Override
    public void onEnable()
    {
        resetSpeed = true;
        speed = 0.1f;
        bouncePhase = BouncePhase.IDLE;
        launchWaitTicks = 0;
        landedWaitTicks = 0;
        if (mc.player != null)
        {
            glideYaw = mc.player.getYaw();
            cameraPitch = mc.player.getPitch();
        }
    }

    @Override
    public void onDisable()
    {
        bouncePhase = BouncePhase.IDLE;
        if (ShorelineMod.isBaritonePresent())
        {
            BaritoneAPI.getProvider().getPrimaryBaritone()
                    .getPathingBehavior().forceCancel();
        }
    }

    // ─── Config 변경 ──────────────────────────────────────────────

    @EventListener
    public void onConfigUpdate(ConfigUpdateEvent event)
    {
        if (event.getStage() != StageEvent.EventStage.POST) return;

        if (event.getConfig() == accelerateConfig && accelerateConfig.getValue())
        {
            resetSpeed = true;
        }
        // 모드 전환 시 상태 초기화
        if (event.getConfig() == modeConfig)
        {
            bouncePhase = BouncePhase.IDLE;
            resetSpeed = true;
        }
    }

    // ─── PlayerTick ───────────────────────────────────────────────

    @EventListener
    public void onPlayerTick(PlayerTickEvent event)
    {
        if (mc.player == null || mc.world == null) return;
        if (isBaritonePathing()) return;

        switch (modeConfig.getValue())
        {
            case CONTROL -> tickControl();
            case BOUNCE  -> tickBounceStateMachine();
        }
    }

    /**
     * CONTROL 모드 틱: 회전 설정 + 폭죽 자동 사용.
     */
    private void tickControl()
    {
        if (!mc.player.isFallFlying()) return;

        // Baritone 장애물 우회
        if (ShorelineMod.isBaritonePresent() && obstaclePasserConfig.getValue())
        {
            handleObstaclePasser();
        }

        if (rotateConfig.getValue())
        {
            float yaw = SprintModule.getInstance().getSprintYaw(mc.player.getYaw());
            setRotation(yaw, getControlPitch());
        }

        if (fireworkConfig.getValue())
        {
            useFirework();
        }
    }

    /**
     * BOUNCE 상태머신 틱 처리.
     *
     * <p><b>상태 전이 규칙:</b></p>
     * <ul>
     *   <li>IDLE      → LAUNCHING  : 엘리트라 장착 확인 후 즉시</li>
     *   <li>LAUNCHING → GLIDING    : {@code isFallFlying() == true}</li>
     *   <li>LAUNCHING → LAUNCHING  : 타임아웃 시 재시도</li>
     *   <li>GLIDING   → PRE_LAND   : 지면까지 거리 < landHeight</li>
     *   <li>GLIDING   → LAUNCHING  : 예기치 않은 비행 중단</li>
     *   <li>PRE_LAND  → LANDED     : {@code isOnGround()}</li>
     *   <li>LANDED    → LAUNCHING  : 대기 후 autoJump 켜져 있을 때</li>
     *   <li>LANDED    → IDLE       : autoJump 꺼져 있을 때</li>
     * </ul>
     */
    private void tickBounceStateMachine()
    {
        // 공통 전제 조건
        if (!mc.player.getInventory().getArmorStack(2).isOf(Items.ELYTRA))
        {
            bouncePhase = BouncePhase.IDLE;
            return;
        }
        if (mc.player.getHungerManager().getFoodLevel() <= 6)
        {
            bouncePhase = BouncePhase.IDLE;
            return;
        }

        switch (bouncePhase)
        {
            // ── IDLE: 진입 대기 ────────────────────────────────────
            case IDLE ->
            {
                glideYaw = mc.player.getYaw();
                bouncePhase = BouncePhase.LAUNCHING;
                launchWaitTicks = 0;
            }

            // ── LAUNCHING: 점프 후 엘리트라 활성화 대기 ───────────
            case LAUNCHING ->
            {
                launchWaitTicks++;

                // 성공: 글라이딩 시작
                if (mc.player.isFallFlying())
                {
                    glideYaw = mc.player.getYaw();
                    bouncePhase = BouncePhase.GLIDING;
                    launchWaitTicks = 0;
                    return;
                }

                // 지면에 있으면 점프
                if (mc.player.isOnGround())
                {
                    mc.player.setSprinting(true);
                    mc.player.jump();
                }
                // 공중인데 아직 글라이딩 안 됨 → START_FALL_FLYING 패킷 전송
                else
                {
                    Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(
                            mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                    mc.player.startFallFlying();
                }

                // 타임아웃: 재시도
                if (launchWaitTicks >= LAUNCH_TIMEOUT_TICKS)
                {
                    launchWaitTicks = 0;
                }
            }

            // ── GLIDING: 글라이딩 진행 중 ─────────────────────────
            case GLIDING ->
            {
                // 예기치 않은 글라이딩 중단 → 재발진
                if (!mc.player.isFallFlying())
                {
                    bouncePhase = BouncePhase.LAUNCHING;
                    launchWaitTicks = 0;
                    return;
                }

                // 서버 피치 고정 (pitchConfig)
                float serverPitch = pitchConfig.getValue();
                setRotation(glideYaw, serverPitch);
                mc.player.setPitch(serverPitch);

                // ★ 수정: isFallFlying()을 제외한 스프린트 조건
                if (canSprintWhileGliding())
                {
                    mc.player.setSprinting(true);
                }

                // 지면 근접 감지 → PRE_LAND 전환
                if (getDistToGround() < landHeightConfig.getValue())
                {
                    bouncePhase = BouncePhase.PRE_LAND;
                }
            }

            // ── PRE_LAND: 착지 직전, 수평 정렬 ──────────────────
            case PRE_LAND ->
            {
                // 착지 완료
                if (mc.player.isOnGround() || !mc.player.isFallFlying())
                {
                    mc.player.stopFallFlying();
                    bouncePhase = BouncePhase.LANDED;
                    landedWaitTicks = 0;
                    return;
                }

                // 피치를 점진적으로 완화해 충격 감소
                float preLandPitch = Math.min(pitchConfig.getValue(), 30.0f);
                setRotation(glideYaw, preLandPitch);
                mc.player.setPitch(preLandPitch);
            }

            // ── LANDED: 착지 완료, 재발진 대기 ───────────────────
            case LANDED ->
            {
                mc.player.stopFallFlying();
                landedWaitTicks++;

                if (landedWaitTicks >= LAND_WAIT_TICKS)
                {
                    if (autoJumpConfig.getValue())
                    {
                        glideYaw = mc.player.getYaw();
                        bouncePhase = BouncePhase.LAUNCHING;
                        launchWaitTicks = 0;
                    }
                    else
                    {
                        bouncePhase = BouncePhase.IDLE;
                    }
                }
            }
        }
    }

    // ─── TravelEvent (물리 override) ─────────────────────────────

    @EventListener
    public void onTravel(TravelEvent event)
    {
        if (mc.player == null || mc.world == null) return;
        if (isBaritonePathing()) return;

        // 가속 속도 갱신
        if (!MovementUtil.isInputtingMovement() || mc.player.horizontalCollision)
        {
            if (modeConfig.getValue() == FlyMode.CONTROL) resetSpeed = true;
        }

        if (accelerateConfig.getValue()
                && modeConfig.getValue() != FlyMode.BOUNCE
                && modeConfig.getValue() != FlyMode.BOOST)
        {
            if (resetSpeed) { speed = 0.1f; resetSpeed = false; }
            speed = Math.min(speed + accelSpeedConfig.getValue(), maxSpeedConfig.getValue());
        }
        else if (modeConfig.getValue() != FlyMode.BOUNCE)
        {
            speed = speedConfig.getValue();
        }

        switch (modeConfig.getValue())
        {
            case CONTROL -> travelControl(event);
            case BOUNCE  -> travelBounce(event);
        }
    }

    /**
     * CONTROL 모드 이동 물리.
     * TravelEvent를 취소하고 velocity를 직접 설정한다.
     */
    private void travelControl(TravelEvent event)
    {
        if (!mc.player.isFallFlying()) return;
        if (mc.player.isTouchingWater() || mc.player.isInLava()) return;
        if (mc.player.getHungerManager().getFoodLevel() <= 6.0f) return;

        event.cancel();

        float forward = mc.player.input.movementForward;
        float side    = mc.player.input.movementSideways;
        float yaw     = Managers.ROTATION.isRotating()
                ? Managers.ROTATION.getRotationYaw()
                : mc.player.getYaw();

        // 대각선 이동 시 45도 yaw 보정
        if (forward != 0.0f)
        {
            if (side > 0.0f) yaw += forward > 0.0f ? -45.0f : 45.0f;
            else if (side < 0.0f) yaw += forward > 0.0f ? 45.0f : -45.0f;
            side = 0.0f;
            forward = Math.signum(forward);
        }

        double rad = Math.toRadians(yaw + 90.0f);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);

        if (forward == 0.0f && side == 0.0f)
        {
            Managers.MOVEMENT.setMotionXZ(0.0, 0.0);
        }
        else
        {
            Managers.MOVEMENT.setMotionXZ(
                    forward * speed * cos + side * speed * sin,
                    forward * speed * sin - side * speed * cos);
        }

        // 수직
        if (mc.options.jumpKey.isPressed() && !mc.options.sneakKey.isPressed())
        {
            Managers.MOVEMENT.setMotionY(vspeedConfig.getValue());
        }
        else if (mc.options.sneakKey.isPressed() && !mc.options.jumpKey.isPressed())
        {
            Managers.MOVEMENT.setMotionY(-vspeedConfig.getValue());
        }
        else
        {
            Managers.MOVEMENT.setMotionY(0.0);
        }

        if (boostConfig.getValue())
        {
            applyVanillaBoost();
        }
    }

    /**
     * BOUNCE 모드 이동 물리.
     * GLIDING/PRE_LAND 중 TravelEvent를 취소해 바닐라 물리를 막고,
     * 속도가 부족할 때 바닐라 부스트를 보완 적용한다.
     */
    private void travelBounce(TravelEvent event)
    {
        // GLIDING, PRE_LAND 중에만 물리 제어
        if (bouncePhase != BouncePhase.GLIDING && bouncePhase != BouncePhase.PRE_LAND) return;
        if (!mc.player.isFallFlying()) return;
        if (mc.player.isTouchingWater() || mc.player.isInLava()) return;

        // ★ 기존 버그: event.cancel() 없음 → 바닐라 travel()이 그대로 실행되어 속도 덮어씀
        event.cancel();

        Vec3d vel = mc.player.getVelocity();
        double hSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        // 수평 속도가 임계값 미만이면 부스트 적용
        if (hSpeed < 0.5)
        {
            applyVanillaBoost();
        }

        // 수직 속도는 엘리트라 물리에 맡김 (pitchConfig에 의해 제어됨)
        // → move(SELF, velocity) 호출은 TravelEvent cancel 시 Mixin에서 처리
    }

    // ─── PlayerMove (BOOST 모드) ──────────────────────────────────

    @EventListener
    public void onPlayerMove(PlayerMoveEvent event)
    {
        if (isBaritonePathing()) return;
        if (!mc.player.isFallFlying()) return;
        if (mc.player.isTouchingWater() || mc.player.isInLava()) return;
        if (mc.player.getHungerManager().getFoodLevel() <= 6.0f) return;

        if (modeConfig.getValue() == FlyMode.BOOST
                && (mc.options.jumpKey.isPressed() || AutoWalkModule.getInstance().isEnabled()))
        {
            applyVanillaBoost();
        }
        else if (modeConfig.getValue() == FlyMode.CONTROL && boostConfig.getValue())
        {
            applyVanillaBoost();
        }
    }

    // ─── 카메라 / 마우스 ──────────────────────────────────────────

    @EventListener
    public void onCameraRotation(CameraRotationEvent event)
    {
        if (isBaritonePathing()) return;
        if (modeConfig.getValue() != FlyMode.BOUNCE) return;

        // GLIDING 중: 카메라는 cameraPitch, 서버는 pitchConfig
        // → 유저 눈에는 자유롭게 보이지만 서버는 고정 pitch
        event.setPitch(cameraPitch);
    }

    @EventListener
    public void onMouseUpdate(MouseUpdateEvent event)
    {
        if (isBaritonePathing()) return;
        if (modeConfig.getValue() != FlyMode.BOUNCE) return;

        event.cancel();

        float deltaPitch = (float) event.getCursorDeltaY() * 0.15f;
        float deltaYaw   = (float) event.getCursorDeltaX() * 0.15f;

        // 카메라 피치 (시각적 전용)
        cameraPitch = MathHelper.clamp(cameraPitch + deltaPitch, -90.0f, 90.0f);

        // yaw는 실제 플레이어에 반영
        float newYaw = mc.player.getYaw() + deltaYaw;
        mc.player.setYaw(newYaw);

        // GLIDING이 아닐 때(IDLE/LAUNCHING/LANDED)는 glideYaw도 실시간 갱신
        if (bouncePhase != BouncePhase.GLIDING && bouncePhase != BouncePhase.PRE_LAND)
        {
            glideYaw = newYaw;
        }
    }

    // ─── 서버 패킷 ────────────────────────────────────────────────

    @EventListener
    public void onPacketInbound(PacketEvent.Inbound event)
    {
        if (mc.player == null) return;
        if (isBaritonePathing()) return;

        if (event.getPacket() instanceof PlayerPositionLookS2CPacket)
        {
            // 서버 teleport → 모든 상태 리셋
            resetSpeed = true;
            if (modeConfig.getValue() == FlyMode.BOUNCE)
            {
                bouncePhase = BouncePhase.IDLE;
                launchWaitTicks = 0;
                landedWaitTicks = 0;
            }
        }
    }

    // ─── 내부 유틸 ────────────────────────────────────────────────

    /**
     * 플레이어 발 아래 지면까지의 거리를 반환.
     * 최대 32블록까지 탐색한다.
     */
    private double getDistToGround()
    {
        if (mc.world == null) return Double.MAX_VALUE;
        BlockPos base = mc.player.getBlockPos();
        for (int i = 1; i <= 32; i++)
        {
            BlockPos below = base.down(i);
            if (!mc.world.getBlockState(below).isAir())
            {
                // below.getY() + 1 = 블록 상단 Y
                return mc.player.getY() - (below.getY() + 1.0);
            }
        }
        return Double.MAX_VALUE;
    }

    /**
     * 글라이딩 중 스프린트 가능 여부.
     *
     * <p><b>★ 핵심 수정:</b> 기존 canSprint()에는 {@code !mc.player.isFallFlying()} 조건이
     * 있어 글라이딩 중 항상 false를 반환하는 버그가 있었다. 이를 제거했다.</p>
     */
    private boolean canSprintWhileGliding()
    {
        return !mc.player.isSneaking()
                && !mc.player.isRiding()
                // isFallFlying() 조건 제거 ← 기존 버그의 원인
                && !mc.player.isTouchingWater()
                && !mc.player.isInLava()
                && !mc.player.isHoldingOntoLadder()
                && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
                && mc.player.getHungerManager().getFoodLevel() > 6.0f;
    }

    /**
     * CONTROL 모드 피치 계산.
     */
    public float getControlPitch()
    {
        boolean rocket = isBoostedByRocket();
        boolean up     = mc.options.jumpKey.isPressed() && !mc.options.sneakKey.isPressed();
        boolean down   = mc.options.sneakKey.isPressed() && !mc.options.jumpKey.isPressed();

        if (up)
        {
            return rocket ? (MovementUtil.isMoving() ? -50.0f : -90.0f) : -50.0f;
        }
        if (down)
        {
            return rocket ? (MovementUtil.isMoving() ? 50.0f : 90.0f) : 50.0f;
        }
        return rocket ? 0.0f : 0.1f;
    }

    /** 바닐라 엘리트라 운동량 추가 (GRIM-safe) */
    public void applyVanillaBoost()
    {
        float yaw = Managers.ROTATION.isRotating()
                ? Managers.ROTATION.getRotationYaw()
                : mc.player.getYaw();
        double rad = Math.toRadians(yaw + 90.0f);
        mc.player.setVelocity(
                mc.player.getVelocity().x + GRIM_AIR_FRICTION * Math.cos(rad),
                mc.player.getVelocity().y,
                mc.player.getVelocity().z + GRIM_AIR_FRICTION * Math.sin(rad));
    }

    /** 폭죽 자동 사용 */
    private void useFirework()
    {
        for (int i = 0; i < 36; i++)
        {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!(stack.getItem() instanceof FireworkRocketItem)) continue;

            if (i < 9)
            {
                Managers.INVENTORY.setSlot(i);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                Managers.INVENTORY.syncToClient();
            }
            else
            {
                int hotbar = mc.player.getInventory().selectedSlot;
                Managers.INVENTORY.click(i, hotbar, SlotActionType.SWAP);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                Managers.INVENTORY.click(i, hotbar, SlotActionType.SWAP);
            }
            break;
        }
    }

    /** Baritone 장애물 우회 */
    private void handleObstaclePasser()
    {
        if (!obstaclePasserConfig.getValue()) return;
        if (!BaritoneAPI.getSettings().freeLook.value)
        {
            ChatUtil.clientSendMessage(
                    Formatting.RED + "Please enable FreeLook in Baritone to use ObstaclePasser!", 5005);
            return;
        }
        if (!mc.player.horizontalCollision) return;

        for (int dist = 3; dist < 64; dist++)
        {
            Vec3d fwd = RotationUtil.getRotationVector(0.0f, mc.player.getYaw());
            Vec3d target = mc.player.getPos().add(fwd.x * dist, 0.0, fwd.z * dist);
            BlockPos bp = BlockPos.ofFloored(target);
            if (mc.world.getBlockState(bp).isAir()
                    && mc.world.getBlockState(bp.up()).isAir())
            {
                BaritoneAPI.getProvider().getPrimaryBaritone()
                        .getCustomGoalProcess()
                        .setGoalAndPath(new GoalBlock(bp.getX(), bp.getY(), bp.getZ()));
                break;
            }
        }
    }

    /** 로켓 부스트 중인지 확인 */
    private boolean isBoostedByRocket()
    {
        if (mc.world == null) return false;
        for (Entity entity : mc.world.getEntities())
        {
            if (entity instanceof FireworkRocketEntity rocket
                    && ((AccessorFireworkRocketEntity) rocket).hookWasShotByEntity()
                    && ((AccessorFireworkRocketEntity) rocket).hookGetShooter() == mc.player)
            {
                return true;
            }
        }
        return false;
    }

    private boolean isBaritonePathing()
    {
        return ShorelineMod.isBaritonePresent()
                && BaritoneAPI.getProvider().getPrimaryBaritone()
                .getPathingBehavior().isPathing();
    }

    // ─── 공개 API ─────────────────────────────────────────────────

    public boolean isBounce()
    {
        return modeConfig.getValue() == FlyMode.BOUNCE;
    }

    public BouncePhase getBouncePhase()
    {
        return bouncePhase;
    }

    // ─── Enum ─────────────────────────────────────────────────────

    public enum FlyMode
    {
        CONTROL,
        BOOST,
        BOUNCE,
    }

    /**
     * BOUNCE 모드 상태머신 단계.
     */
    public enum BouncePhase
    {
        /** 비활성 대기 */
        IDLE,
        /** 점프 후 엘리트라 활성화 대기 */
        LAUNCHING,
        /** 엘리트라 글라이딩 진행 중 */
        GLIDING,
        /** 지면 근접, 착지 준비 */
        PRE_LAND,
        /** 착지 완료, 재발진 대기 */
        LANDED
    }
}
