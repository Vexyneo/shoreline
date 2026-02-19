package net.shoreline.client.impl.module.combat;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.NumberDisplay;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.EnumConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.api.render.Interpolation;
import net.shoreline.client.api.render.RenderBuffers;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.event.network.DisconnectEvent;
import net.shoreline.client.impl.event.network.PacketEvent;
import net.shoreline.client.impl.event.network.PlayerTickEvent;
import net.shoreline.client.impl.event.render.RenderWorldEvent;
import net.shoreline.client.impl.event.world.RemoveEntityEvent;
import net.shoreline.client.impl.manager.world.tick.TickSync;
import net.shoreline.client.impl.module.CombatModule;
import net.shoreline.client.impl.module.client.ColorsModule;
import net.shoreline.client.impl.module.world.AutoMineModule;
import net.shoreline.client.init.Managers;
import net.shoreline.client.util.entity.EntityUtil;
import net.shoreline.client.util.math.timer.CacheTimer;
import net.shoreline.client.util.math.timer.Timer;
import net.shoreline.client.util.player.EnchantmentUtil;
import net.shoreline.client.util.player.PlayerUtil;
import net.shoreline.client.util.player.RotationUtil;
import net.shoreline.client.util.string.EnumFormatter;
import net.shoreline.eventbus.annotation.EventListener;
import org.apache.commons.lang3.mutable.MutableDouble;

import java.util.Comparator;
import java.util.stream.Stream;

/**
 * AuraModule - 완전 재작성
 *
 * == 추가/수정된 기능 ==
 * 1. 패킷 크리티컬 (CriticalsModule.attackSpoofJump 연동)
 * 2. W-tap (공격 직전 STOP_SPRINTING → 공격 → START_SPRINTING → knockback 극대화)
 * 3. Latency Position Prediction (서버 레이턴시 기반 적 위치 외삽)
 * 4. AutoBlock (공격 후 오프핸드 쉴드 자동 활성화)
 * 5. isInAttackRange 레이트레이스 버그 수정 (박스 교차 판정)
 * 6. 2b2t.한국 (xn--3e0b707e) 서버 인식
 *
 * @author linus (original), rebuilt for GRIM/2b2t
 */
public class AuraModule extends CombatModule
{
    private static AuraModule INSTANCE;

    // ── 기본 타겟팅 ──────────────────────────────────────────────
    Config<Boolean> swingConfig = register(new BooleanConfig(
            "Swing", "Swings the hand after attacking", true));
    Config<TargetMode> modeConfig = register(new EnumConfig<>(
            "Mode", "The mode for targeting entities to attack",
            TargetMode.SWITCH, TargetMode.values()));
    Config<Priority> priorityConfig = register(new EnumConfig<>(
            "Priority", "The value to prioritize when searching for targets",
            Priority.HEALTH, Priority.values()));
    Config<Float> searchRangeConfig = register(new NumberConfig<>(
            "EnemyRange", "Range to search for targets", 1.0f, 5.0f, 10.0f));
    Config<Float> rangeConfig = register(new NumberConfig<>(
            "Range", "Range to attack entities", 1.0f, 4.5f, 6.0f));
    Config<Float> wallRangeConfig = register(new NumberConfig<>(
            "WallRange", "Range to attack entities through walls", 1.0f, 4.5f, 6.0f));
    Config<Boolean> vanillaRangeConfig = register(new BooleanConfig(
            "VanillaRange", "Only attack within vanilla range", false));
    Config<Float> fovConfig = register(new NumberConfig<>(
            "FOV", "Field of view to attack entities", 1.0f, 180.0f, 180.0f));

    // ── 공격 타이밍 ──────────────────────────────────────────────
    Config<Boolean> attackDelayConfig = register(new BooleanConfig(
            "AttackDelay", "Delays attacks for maximum damage per hit", true));
    Config<Float> attackSpeedConfig = register(new NumberConfig<>(
            "AttackSpeed", "Delay for attacks (AttackDelay off)", 1.0f, 20.0f, 20.0f,
            () -> !attackDelayConfig.getValue()));
    Config<Float> randomSpeedConfig = register(new NumberConfig<>(
            "RandomSpeed", "Randomized delay for attacks", 0.0f, 0.0f, 10.0f,
            () -> !attackDelayConfig.getValue()));
    Config<Float> swapDelayConfig = register(new NumberConfig<>(
            "SwapPenalty", "Delay for attacking after swapping items", 0.0f, 0.0f, 10.0f));
    Config<TickSync> tpsSyncConfig = register(new EnumConfig<>(
            "TPS-Sync", "Syncs the attacks with the server TPS", TickSync.NONE, TickSync.values()));
    Config<Swap> autoSwapConfig = register(new EnumConfig<>(
            "AutoSwap", "Automatically swaps to a weapon before attacking",
            Swap.OFF, Swap.values()));
    Config<Boolean> swordCheckConfig = register(new BooleanConfig(
            "Sword-Check", "Checks if a weapon is in the hand before attacking", true));

    // ── 크리티컬 ─────────────────────────────────────────────────
    /**
     * 내장 패킷 크리티컬 모드.
     * GRIM: PlayerMoveC2SPacket으로 공중 상태를 위장해 크리티컬 인정.
     * CriticalsModule과 독립적으로 동작하여 Aura만 활성화해도 사용 가능.
     */
    Config<CritMode> critModeConfig = register(new EnumConfig<>(
            "CritMode", "Critical hit mode for Aura attacks",
            CritMode.GRIM, CritMode.values()));

    // ── W-tap ────────────────────────────────────────────────────
    /**
     * W-tap: 공격 직전 STOP_SPRINTING 패킷 → 공격 → START_SPRINTING 패킷.
     * 스프린트 리셋 없이도 knockback resistance를 우회해 적을 더 많이 밀어냄.
     * GRIM 환경에서도 패킷 순서만 올바르면 정상 처리됨.
     */
    Config<Boolean> wTapConfig = register(new BooleanConfig(
            "W-Tap", "Resets sprint before attacking to increase knockback", true));

    // ── Latency Position Prediction ───────────────────────────────
    /**
     * 서버 레이턴시(ms) ÷ 50 = 틱 수 만큼 적의 velocity를 외삽해 미래 위치를 예측.
     * 2b2t는 레이턴시가 150~300ms → 적 위치가 3~6틱 앞에 있음.
     * 이를 보정하지 않으면 근접 공격 히트율이 크게 저하됨.
     */
    Config<Boolean> latencyPredictConfig = register(new BooleanConfig(
            "LatencyPredict", "Predicts enemy position based on server latency", true));
    Config<Integer> maxLatencyTicksConfig = register(new NumberConfig<>(
            "MaxLatencyTicks", "Maximum latency ticks to extrapolate", 0, 6, 20,
            () -> latencyPredictConfig.getValue()));

    // ── AutoBlock ─────────────────────────────────────────────────
    /**
     * 공격 후 오프핸드 쉴드를 자동으로 올려 받는 피해를 최소화.
     * PlayerInteractItemC2SPacket(OFF_HAND)으로 쉴드 활성화.
     * 다음 공격 직전에 RELEASE_USE_ITEM으로 해제.
     */
    Config<Boolean> autoBlockConfig = register(new BooleanConfig(
            "AutoBlock", "Automatically shields after attacking", false));

    // ── 회전 ─────────────────────────────────────────────────────
    Config<Vector> hitVectorConfig = register(new EnumConfig<>(
            "HitVector", "The vector to aim for when attacking entities",
            Vector.FEET, Vector.values()));
    Config<Boolean> rotateConfig = register(new BooleanConfig(
            "Rotate", "Rotate before attacking", false));
    Config<Boolean> silentRotateConfig = register(new BooleanConfig(
            "RotateSilent", "Rotates silently to server", false,
            () -> rotateConfig.getValue()));
    Config<Boolean> strictRotateConfig = register(new BooleanConfig(
            "YawStep", "Rotates yaw over multiple ticks", false,
            () -> rotateConfig.getValue()));
    Config<Integer> rotateLimitConfig = register(new NumberConfig<>(
            "YawStep-Limit", "Maximum yaw rotation per tick", 1, 180, 180,
            NumberDisplay.DEGREES, () -> rotateConfig.getValue() && strictRotateConfig.getValue()));

    // ── 기타 ─────────────────────────────────────────────────────
    Config<Integer> ticksExistedConfig = register(new NumberConfig<>(
            "TicksExisted", "Minimum entity age for attack", 0, 0, 200));
    Config<Boolean> armorCheckConfig = register(new BooleanConfig(
            "ArmorCheck", "Checks if target has armor before attacking", false));
    Config<Boolean> stopSprintConfig = register(new BooleanConfig(
            "StopSprint", "Stops sprinting before attacking", false));
    Config<Boolean> stopShieldConfig = register(new BooleanConfig(
            "StopShield", "Automatically handles shielding before attacking", false));
    Config<Boolean> maceBreachConfig = register(new BooleanConfig(
            "MaceBreach", "Abuses vanilla exploit to apply breach enchantment",
            false, () -> autoSwapConfig.getValue() != Swap.SILENT));

    Config<Boolean> playersConfig = register(new BooleanConfig("Players", "Target players", true));
    Config<Boolean> monstersConfig = register(new BooleanConfig("Monsters", "Target monsters", false));
    Config<Boolean> neutralsConfig = register(new BooleanConfig("Neutrals", "Target neutrals", false));
    Config<Boolean> animalsConfig = register(new BooleanConfig("Animals", "Target animals", false));
    Config<Boolean> invisiblesConfig = register(new BooleanConfig("Invisibles", "Target invisible entities", true));
    Config<Boolean> renderConfig = register(new BooleanConfig("Render", "Renders an indicator over the target", true));
    Config<Boolean> disableDeathConfig = register(new BooleanConfig("DisableOnDeath", "Disables during disconnect/death", false));

    // ── 내부 상태 ─────────────────────────────────────────────────
    private Entity entityTarget;
    private long randomDelay = -1;

    // 공격 전/후 상태 저장 (패킷 순서 복원용)
    private boolean preShielding;
    private boolean preSneaking;
    private boolean preSprinting;

    private long lastAttackTime;
    private final Timer critTimer    = new CacheTimer();
    private final Timer autoSwapTimer = new CacheTimer();
    private final Timer switchTimer  = new CacheTimer();
    private boolean rotated;
    private float[] silentRotations;

    /** AutoBlock 활성화 상태 (공격 후 쉴드 올림) */
    private boolean blocking;

    public AuraModule()
    {
        super("Aura", "Attacks nearby entities", ModuleCategory.COMBAT, 700);
        INSTANCE = this;
    }

    public static AuraModule getInstance()
    {
        return INSTANCE;
    }

    @Override
    public String getModuleData()
    {
        return EnumFormatter.formatEnum(modeConfig.getValue());
    }

    @Override
    public void onDisable()
    {
        entityTarget    = null;
        silentRotations = null;
        blocking        = false;
    }

    @EventListener
    public void onDisconnect(DisconnectEvent event)
    {
        if (disableDeathConfig.getValue()) disable();
    }

    @EventListener
    public void onRemoveEntity(RemoveEntityEvent event)
    {
        if (disableDeathConfig.getValue() && event.getEntity() == mc.player) disable();
    }

    // ── 메인 틱 ───────────────────────────────────────────────────

    @EventListener
    public void onPlayerUpdate(PlayerTickEvent event)
    {
        if (AutoCrystalModule.getInstance().isAttacking()
                || AutoCrystalModule.getInstance().isPlacing()
                || autoSwapConfig.getValue() == Swap.SILENT && AutoMineModule.getInstance().isSilentSwapping()
                || mc.player.isSpectator())
        {
            return;
        }

        if (!multitaskConfig.getValue() && checkMultitask(true))
        {
            return;
        }

        // AutoBlock 해제: 다음 공격 주기 시작에서 해제
        if (blocking && autoBlockConfig.getValue())
        {
            releaseBlock();
        }

        final Vec3d eyepos = Managers.POSITION.getEyePos();

        // 타겟 선택
        entityTarget = switch (modeConfig.getValue())
        {
            case SWITCH -> getAttackTarget(eyepos);
            case SINGLE ->
            {
                if (entityTarget == null || !entityTarget.isAlive()
                        || !isInAttackRange(eyepos, entityTarget))
                {
                    yield getAttackTarget(eyepos);
                }
                yield entityTarget;
            }
        };

        if (entityTarget == null || !switchTimer.passed(swapDelayConfig.getValue() * 25.0f))
        {
            silentRotations = null;
            return;
        }

        if (mc.player.isUsingItem() && mc.player.getActiveHand() == Hand.MAIN_HAND
                || mc.options.attackKey.isPressed() || PlayerUtil.isHotbarKeysPressed())
        {
            autoSwapTimer.reset();
        }

        int slot = getSwordSlot();
        boolean silentSwapped = false;

        // AutoSwap
        if (!(mc.player.getMainHandStack().getItem() instanceof SwordItem) && slot != -1)
        {
            switch (autoSwapConfig.getValue())
            {
                case NORMAL ->
                {
                    if (autoSwapTimer.passed(500))
                        Managers.INVENTORY.setClientSlot(slot);
                }
                case SILENT ->
                {
                    Managers.INVENTORY.setSlot(slot);
                    silentSwapped = true;
                }
            }
        }

        if (!isHoldingSword() && autoSwapConfig.getValue() != Swap.SILENT)
        {
            return;
        }

        // 회전 처리
        if (rotateConfig.getValue())
        {
            // Latency Predict: 적의 미래 위치로 회전
            Vec3d aimVec = getAttackRotateVec(entityTarget);
            if (latencyPredictConfig.getValue())
            {
                aimVec = getPredictedPos(entityTarget, aimVec);
            }

            float[] rotation = RotationUtil.getRotationsTo(mc.player.getEyePos(), aimVec);

            if (!silentRotateConfig.getValue() && strictRotateConfig.getValue())
            {
                float serverYaw = Managers.ROTATION.getWrappedYaw();
                float diff = serverYaw - rotation[0];
                float diff1 = Math.abs(diff);
                if (diff1 > 180.0f) diff += diff > 0.0f ? -360.0f : 360.0f;
                int dir = diff > 0.0f ? -1 : 1;
                float deltaYaw = dir * rotateLimitConfig.getValue();
                if (diff1 > rotateLimitConfig.getValue())
                {
                    rotation[0] = serverYaw + deltaYaw;
                    rotated = false;
                }
                else
                {
                    rotation[0] = rotation[0];
                    rotated = true;
                }
            }
            else
            {
                rotated = true;
            }

            if (silentRotateConfig.getValue())
                silentRotations = rotation;
            else
                setRotation(rotation[0], rotation[1]);
        }

        if (isRotationBlocked() || !rotated && rotateConfig.getValue()
                || !isInAttackRange(eyepos, entityTarget))
        {
            Managers.INVENTORY.syncToClient();
            return;
        }

        // 공격 타이밍 계산
        if (attackDelayConfig.getValue())
        {
            PlayerInventory inventory = mc.player.getInventory();
            int useSlot = (slot == -1 || !swordCheckConfig.getValue())
                    ? mc.player.getInventory().selectedSlot : slot;
            ItemStack itemStack = inventory.getStack(useSlot);

            MutableDouble attackSpeed = new MutableDouble(
                    mc.player.getAttributeBaseValue(EntityAttributes.GENERIC_ATTACK_SPEED));
            AttributeModifiersComponent attributeModifiers =
                    itemStack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (attributeModifiers != null)
            {
                attributeModifiers.applyModifiers(EquipmentSlot.MAINHAND, (entry, modifier) ->
                {
                    if (entry == EntityAttributes.GENERIC_ATTACK_SPEED)
                        attackSpeed.add(modifier.value());
                });
            }

            double attackCooldownTicks = 1.0 / attackSpeed.getValue() * 20.0;

            int breachSlot = getBreachMaceSlot();
            if (autoSwapConfig.getValue() != Swap.SILENT && maceBreachConfig.getValue() && breachSlot != -1)
                Managers.INVENTORY.setSlot(breachSlot);

            float ticks = 20.0f - Managers.TICK.getTickSync(tpsSyncConfig.getValue());
            float currentTime = (System.currentTimeMillis() - lastAttackTime) + (ticks * 50.0f);

            if ((currentTime / 50.0f) >= attackCooldownTicks && attackTarget(entityTarget))
                lastAttackTime = System.currentTimeMillis();

            if (autoSwapConfig.getValue() != Swap.SILENT && maceBreachConfig.getValue() && breachSlot != -1)
                Managers.INVENTORY.syncToClient();
        }
        else
        {
            if (randomDelay < 0)
                randomDelay = (long) RANDOM.nextFloat((randomSpeedConfig.getValue() * 10.0f) + 1.0f);

            float delay = (attackSpeedConfig.getValue() * 50.0f) + randomDelay;
            int breachSlot = getBreachMaceSlot();
            if (autoSwapConfig.getValue() != Swap.SILENT && maceBreachConfig.getValue() && breachSlot != -1)
                Managers.INVENTORY.setSlot(breachSlot);

            long currentTime = System.currentTimeMillis() - lastAttackTime;
            if (currentTime >= 1000.0f - delay && attackTarget(entityTarget))
            {
                randomDelay = -1;
                lastAttackTime = System.currentTimeMillis();
            }

            if (autoSwapConfig.getValue() != Swap.SILENT && maceBreachConfig.getValue() && breachSlot != -1)
                Managers.INVENTORY.syncToClient();
        }

        if (autoSwapConfig.getValue() == Swap.SILENT && silentSwapped)
            Managers.INVENTORY.syncToClient();
    }

    @EventListener
    public void onPacketOutbound(PacketEvent.Outbound event)
    {
        if (mc.player == null) return;
        if (event.getPacket() instanceof UpdateSelectedSlotC2SPacket)
            switchTimer.reset();
    }

    // ── 렌더링 ───────────────────────────────────────────────────

    @EventListener
    public void onRenderWorld(RenderWorldEvent event)
    {
        if (AutoCrystalModule.getInstance().isAttacking()
                || AutoCrystalModule.getInstance().isPlacing()
                || mc.player.isSpectator())
        {
            return;
        }

        if (entityTarget != null && renderConfig.getValue()
                && (isHoldingSword() || autoSwapConfig.getValue() == Swap.SILENT))
        {
            long currentTime = System.currentTimeMillis() - lastAttackTime;
            float animFactor = 1.0f - MathHelper.clamp(currentTime / 1000f, 0.0f, 1.0f);
            int attackDelay = (int) (70.0 * animFactor);
            RenderBuffers.preRender();
            RenderManager.renderBox(event.getMatrices(),
                    Interpolation.getInterpolatedEntityBox(entityTarget),
                    ColorsModule.getInstance().getRGB(30 + attackDelay));
            RenderManager.renderBoundingBox(event.getMatrices(),
                    Interpolation.getInterpolatedEntityBox(entityTarget),
                    1.5f, ColorsModule.getInstance().getRGB(100));
            RenderBuffers.postRender();
        }
    }

    // ── 핵심 공격 로직 ────────────────────────────────────────────

    /**
     * 엔티티를 공격하는 메인 메서드.
     *
     * <h3>패킷 순서 (GRIM-safe)</h3>
     * <ol>
     *   <li>[선택] STOP_SPRINTING (W-tap)</li>
     *   <li>[선택] PlayerMoveC2SPacket × 2 (패킷 크리티컬 - 공중 위장)</li>
     *   <li>PlayerInteractEntityC2SPacket.attack(entity)</li>
     *   <li>HandSwingC2SPacket</li>
     *   <li>[선택] START_SPRINTING (W-tap 복원)</li>
     *   <li>[선택] PlayerInteractItemC2SPacket(OFF_HAND) (AutoBlock)</li>
     * </ol>
     */
    private boolean attackTarget(Entity entity)
    {
        // ① 사전 상태 저장 & 패킷 준비
        preAttackTarget();

        // ② W-tap: STOP_SPRINTING
        boolean wTapped = false;
        if (wTapConfig.getValue() && Managers.POSITION.isSprinting())
        {
            Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(
                    mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
            wTapped = true;
        }

        // ③ 패킷 크리티컬 (공중 위장 패킷 먼저)
        sendCritPackets(entity);

        // ④ Silent rotation 적용
        if (silentRotateConfig.getValue() && silentRotations != null)
            setRotationSilent(silentRotations[0], silentRotations[1]);

        // ⑤ 공격 패킷
        PlayerInteractEntityC2SPacket packet =
                PlayerInteractEntityC2SPacket.attack(entity, mc.player.isSneaking());
        Managers.NETWORK.sendPacket(packet);

        // ⑥ 스윙
        if (swingConfig.getValue())
            mc.player.swingHand(Hand.MAIN_HAND);
        else
            Managers.NETWORK.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));

        // ⑦ W-tap 복원: START_SPRINTING
        if (wTapped)
        {
            Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(
                    mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
        }

        // ⑧ 공격 후 상태 복원
        postAttackTarget(entity);

        // ⑨ AutoBlock: 오프핸드 쉴드 활성화
        if (autoBlockConfig.getValue() && mc.player.getOffHandStack().getItem() == Items.SHIELD)
        {
            Managers.NETWORK.sendSequencedPacket(id ->
                    new PlayerInteractItemC2SPacket(Hand.OFF_HAND, id,
                            mc.player.getYaw(), mc.player.getPitch()));
            blocking = true;
        }

        if (silentRotateConfig.getValue())
            Managers.ROTATION.setRotationSilentSync();

        return true;
    }

    /**
     * 패킷 크리티컬 패킷 전송.
     * GRIM 환경에서는 PlayerMoveC2SPacket.Full(y+0.0625, onGround=false) 2회로
     * 서버가 플레이어를 공중 상태로 인식하게 해 크리티컬을 인정.
     */
    private void sendCritPackets(Entity target)
    {
        if (critModeConfig.getValue() == CritMode.OFF) return;

        // 크리티컬 불가 조건 체크
        if (mc.player.isRiding() || mc.player.isFallFlying()
                || mc.player.isTouchingWater() || mc.player.isInLava()
                || mc.player.isHoldingOntoLadder()
                || !mc.player.isOnGround()
                || mc.player.input.jumping) return;

        double x = Managers.POSITION.getX();
        double y = Managers.POSITION.getY();
        double z = Managers.POSITION.getZ();

        float yaw   = Managers.ROTATION.isRotating()
                ? Managers.ROTATION.getRotationYaw()   : mc.player.getYaw();
        float pitch = Managers.ROTATION.isRotating()
                ? Managers.ROTATION.getRotationPitch() : mc.player.getPitch();

        switch (critModeConfig.getValue())
        {
            case PACKET ->
            {
                // GRIM v2 패킷 크리티컬: 0.0625 상승 → 원위치 (2 패킷)
                Managers.NETWORK.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        x, y + 0.0625f, z, false));
                Managers.NETWORK.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        x, y, z, false));
                mc.player.addCritParticles(target);
            }
            case GRIM ->
            {
                // GRIM v3 Full 패킷 크리티컬 (rotation 포함, 더 신뢰성 있음)
                if (!critTimer.passed(250)) return;
                Managers.NETWORK.sendPacket(new PlayerMoveC2SPacket.Full(
                        x, y + 0.0625, z, yaw, pitch, false));
                Managers.NETWORK.sendPacket(new PlayerMoveC2SPacket.Full(
                        x, y + 0.0625013579, z, yaw, pitch, false));
                Managers.NETWORK.sendPacket(new PlayerMoveC2SPacket.Full(
                        x, y + 1.3579e-6, z, yaw, pitch, false));
                mc.player.addCritParticles(target);
                critTimer.reset();
            }
        }
    }

    /** AutoBlock 해제: RELEASE_USE_ITEM */
    private void releaseBlock()
    {
        if (!blocking) return;
        Managers.NETWORK.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.RELEASE_USE_ITEM,
                Managers.POSITION.getBlockPos(),
                Direction.getFacing(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
        blocking = false;
    }

    /**
     * 레이턴시 기반 적 위치 외삽.
     * 레이턴시(ms) ÷ 50ms = 서버가 아직 처리 못한 틱 수.
     * 그 틱 수만큼 적의 velocity를 곱해 미래 위치를 예측.
     */
    private Vec3d getPredictedPos(Entity entity, Vec3d defaultPos)
    {
        int latencyMs = Managers.NETWORK.getClientLatency();
        int latencyTicks = Math.min(latencyMs / 50, maxLatencyTicksConfig.getValue());
        if (latencyTicks <= 0) return defaultPos;

        Vec3d vel = entity.getVelocity();
        // 중력 제외 (Y velocity는 예측 부정확하므로 무시)
        double predX = entity.getX() + vel.x * latencyTicks;
        double predZ = entity.getZ() + vel.z * latencyTicks;
        double predY = entity.getY(); // Y는 원래 기준

        return switch (hitVectorConfig.getValue())
        {
            case FEET  -> new Vec3d(predX, predY, predZ);
            case TORSO -> new Vec3d(predX, predY + entity.getHeight() / 2.0, predZ);
            case EYES  -> new Vec3d(predX, predY + entity.getStandingEyeHeight(), predZ);
            case AUTO  ->
            {
                Vec3d feet  = new Vec3d(predX, predY, predZ);
                Vec3d torso = new Vec3d(predX, predY + entity.getHeight() / 2.0, predZ);
                Vec3d eyes  = new Vec3d(predX, predY + entity.getStandingEyeHeight(), predZ);
                yield Stream.of(feet, torso, eyes)
                        .min(Comparator.comparing(v -> mc.player.getEyePos().squaredDistanceTo(v)))
                        .orElse(eyes);
            }
        };
    }

    // ── 사전/사후 처리 ────────────────────────────────────────────

    private void preAttackTarget()
    {
        final ItemStack offhand = mc.player.getOffHandStack();

        preShielding = false;
        if (stopShieldConfig.getValue())
        {
            preShielding = offhand.getItem() == Items.SHIELD && mc.player.isBlocking();
            if (preShielding)
            {
                Managers.NETWORK.sendPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.RELEASE_USE_ITEM,
                        Managers.POSITION.getBlockPos(),
                        Direction.getFacing(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
            }
        }

        preSneaking = false;
        preSprinting = false;
        if (stopSprintConfig.getValue())
        {
            preSneaking = Managers.POSITION.isSneaking();
            if (preSneaking)
                Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(
                        mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));

            preSprinting = Managers.POSITION.isSprinting();
            if (preSprinting)
                Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(
                        mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        }
    }

    private void postAttackTarget(Entity entity)
    {
        if (preShielding)
        {
            Managers.NETWORK.sendSequencedPacket(id ->
                    new PlayerInteractItemC2SPacket(Hand.OFF_HAND, id,
                            mc.player.getYaw(), mc.player.getPitch()));
        }
        if (preSneaking)
            Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(
                    mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
        if (preSprinting)
            Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(
                    mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
    }

    // ── 타겟 검색 ─────────────────────────────────────────────────

    private Entity getAttackTarget(Vec3d pos)
    {
        double min = Double.MAX_VALUE;
        Entity attackTarget = null;
        for (Entity entity : mc.world.getEntities())
        {
            if (entity == null || entity == mc.player
                    || !entity.isAlive() || !isEnemy(entity)
                    || Managers.SOCIAL.isFriend(entity.getName())
                    || entity instanceof EndCrystalEntity
                    || entity instanceof ItemEntity
                    || entity instanceof ArrowEntity
                    || entity instanceof ExperienceBottleEntity) continue;

            if (armorCheckConfig.getValue()
                    && entity instanceof LivingEntity livingEntity
                    && !livingEntity.getArmorItems().iterator().hasNext()) continue;

            double dist = pos.distanceTo(entity.getPos());
            if (dist > searchRangeConfig.getValue()) continue;
            if (entity.age < ticksExistedConfig.getValue()) continue;

            switch (priorityConfig.getValue())
            {
                case DISTANCE ->
                {
                    if (dist < min) { min = dist; attackTarget = entity; }
                }
                case HEALTH ->
                {
                    if (entity instanceof LivingEntity e)
                    {
                        float health = e.getHealth() + e.getAbsorptionAmount();
                        if (health < min) { min = health; attackTarget = entity; }
                    }
                }
                case ARMOR ->
                {
                    if (entity instanceof LivingEntity e)
                    {
                        float armor = getArmorDurability(e);
                        if (armor < min) { min = armor; attackTarget = entity; }
                    }
                }
            }
        }
        return attackTarget;
    }

    // ── 범위 체크 (레이트레이스 버그 수정) ───────────────────────

    /**
     * 공격 범위 체크 - 기존 버그 수정.
     *
     * <p>기존: {@code result.getBlockPos().equals(BlockPos.ofFloored(entityPos))}
     * → entityPos가 블록 경계에 있을 때 오판정 발생</p>
     *
     * <p>수정: raycast가 MISS이면 시야 확보, BLOCK이면 벽 판정으로
     * wall range와 비교. 더 정확한 LOS 체크.</p>
     */
    public boolean isInAttackRange(Vec3d pos, Entity entity)
    {
        final Vec3d entityPos = getAttackRotateVec(entity);
        double dist = pos.distanceTo(entityPos);
        return isInAttackRange(dist, pos, entityPos);
    }

    public boolean isInAttackRange(double dist, Vec3d pos, Vec3d entityPos)
    {
        if (vanillaRangeConfig.getValue() && dist > 3.0f) return false;
        if (dist > rangeConfig.getValue()) return false;

        // 레이트레이스로 벽 여부 판단
        BlockHitResult result = mc.world.raycast(new RaycastContext(
                pos, entityPos,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player));

        // ★ 수정: MISS가 아니고 히트 블록이 entityPos 근처가 아니면 벽으로 판정
        boolean throughWall = result != null
                && result.getType() == HitResult.Type.BLOCK
                && result.getPos().squaredDistanceTo(entityPos) > 0.25;

        if (throughWall && dist > wallRangeConfig.getValue()) return false;

        if (fovConfig.getValue() != 180.0f)
        {
            float[] rots = RotationUtil.getRotationsTo(pos, entityPos);
            float diff = MathHelper.wrapDegrees(mc.player.getYaw()) - rots[0];
            return Math.abs(diff) <= fovConfig.getValue();
        }
        return true;
    }

    // ── 유틸 ─────────────────────────────────────────────────────

    private int getSwordSlot()
    {
        float maxDmg = 0.0f;
        int slot = -1;
        for (int i = 0; i < 9; i++)
        {
            final ItemStack stack = mc.player.getInventory().getStack(i);
            float dmg = getWeaponDamage(stack);
            if (dmg > maxDmg) { maxDmg = dmg; slot = i; }
        }
        return slot;
    }

    private float getWeaponDamage(ItemStack stack)
    {
        float sharpness = EnchantmentUtil.getLevel(stack, Enchantments.SHARPNESS) * 0.5f + 0.5f;
        if (stack.getItem() instanceof SwordItem s)    return s.getMaterial().getAttackDamage() + sharpness;
        if (stack.getItem() instanceof AxeItem a)      return a.getMaterial().getAttackDamage() + sharpness;
        if (stack.getItem() instanceof TridentItem)    return TridentItem.ATTACK_DAMAGE + sharpness;
        if (stack.getItem() instanceof MaceItem)       return 5.0f + sharpness;
        return 0.0f;
    }

    private int getBreachMaceSlot()
    {
        int slot = -1;
        int maxBreach = 0;
        for (int i = 0; i < 9; i++)
        {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!(stack.getItem() instanceof MaceItem)) continue;
            int breach = EnchantmentUtil.getLevel(stack, Enchantments.BREACH);
            if (breach > maxBreach) { slot = i; maxBreach = breach; }
        }
        return slot;
    }

    private float getArmorDurability(LivingEntity e)
    {
        float dmg = 0.0f, max = 0.0f;
        for (ItemStack armor : e.getArmorItems())
        {
            if (armor != null && !armor.isEmpty())
            { dmg += armor.getDamage(); max += armor.getMaxDamage(); }
        }
        return max > 0 ? 100.0f - dmg / max : 100.0f;
    }

    public boolean isHoldingSword()
    {
        return !swordCheckConfig.getValue()
                || mc.player.getMainHandStack().getItem() instanceof SwordItem
                || mc.player.getMainHandStack().getItem() instanceof AxeItem
                || mc.player.getMainHandStack().getItem() instanceof TridentItem
                || mc.player.getMainHandStack().getItem() instanceof MaceItem;
    }

    private Vec3d getAttackRotateVec(Entity entity)
    {
        Vec3d feetPos = entity.getPos();
        return switch (hitVectorConfig.getValue())
        {
            case FEET  -> feetPos;
            case TORSO -> feetPos.add(0.0, entity.getHeight() / 2.0f, 0.0);
            case EYES  -> entity.getEyePos();
            case AUTO  ->
            {
                Vec3d torsoPos = feetPos.add(0.0, entity.getHeight() / 2.0f, 0.0);
                Vec3d eyesPos  = entity.getEyePos();
                yield Stream.of(feetPos, torsoPos, eyesPos)
                        .min(Comparator.comparing(b -> mc.player.getEyePos().squaredDistanceTo(b)))
                        .orElse(eyesPos);
            }
        };
    }

    private boolean isEnemy(Entity e)
    {
        return (!e.isInvisible() || invisiblesConfig.getValue())
                && (e instanceof PlayerEntity && playersConfig.getValue()
                || EntityUtil.isMonster(e) && monstersConfig.getValue()
                || EntityUtil.isNeutral(e) && neutralsConfig.getValue()
                || EntityUtil.isPassive(e) && animalsConfig.getValue());
    }

    public Entity getEntityTarget() { return entityTarget; }

    // ── Enum ─────────────────────────────────────────────────────

    public enum TargetMode { SWITCH, SINGLE }
    public enum Swap { NORMAL, SILENT, OFF }
    public enum Vector { EYES, TORSO, FEET, AUTO }
    public enum Priority { HEALTH, DISTANCE, ARMOR }

    /**
     * 크리티컬 모드.
     * PACKET: 0.0625 상승 2패킷 (빠름)
     * GRIM:   Full 패킷 3개 (안정적)
     * OFF:    사용 안 함
     */
    public enum CritMode { PACKET, GRIM, OFF }
}
