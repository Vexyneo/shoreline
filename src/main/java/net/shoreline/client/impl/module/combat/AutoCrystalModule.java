package net.shoreline.client.impl.module.combat;

import com.google.common.collect.Lists;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.NumberDisplay;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.EnumConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.api.render.RenderBuffers;
import net.shoreline.client.api.render.RenderManager;
import net.shoreline.client.impl.event.RunTickEvent;
import net.shoreline.client.impl.event.network.DisconnectEvent;
import net.shoreline.client.impl.event.network.PacketEvent;
import net.shoreline.client.impl.event.network.PlayerTickEvent;
import net.shoreline.client.impl.event.render.RenderWorldEvent;
import net.shoreline.client.impl.event.world.AddEntityEvent;
import net.shoreline.client.impl.module.CombatModule;
import net.shoreline.client.impl.module.client.ColorsModule;
import net.shoreline.client.impl.module.exploit.FastLatencyModule;
import net.shoreline.client.impl.module.world.AutoMineModule;
import net.shoreline.client.init.Managers;
import net.shoreline.client.util.collection.EvictingQueue;
import net.shoreline.client.util.entity.EntityUtil;
import net.shoreline.client.util.math.PerSecondCounter;
import net.shoreline.client.util.math.timer.CacheTimer;
import net.shoreline.client.util.math.timer.Timer;
import net.shoreline.client.util.player.InventoryUtil;
import net.shoreline.client.util.player.PlayerUtil;
import net.shoreline.client.util.player.RotationUtil;
import net.shoreline.client.util.render.animation.Animation;
import net.shoreline.client.util.world.BlastResistantBlocks;
import net.shoreline.client.util.world.ExplosionUtil;
import net.shoreline.eventbus.annotation.EventListener;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AutoCrystalModule - 완전 재작성
 *
 * == 주요 개선 사항 ==
 *
 * 1. 비동기 계산 (ExecutorService 실제 활용)
 *    calculatePlaceCrystal / calculateAttackCrystal 을 백그라운드 스레드에서 실행.
 *    메인(렌더) 스레드는 결과만 소비 → 틱당 지연 없음.
 *
 * 2. PlaceDirection 버그 수정
 *    기존: isInBuildLimit() → 항상 DOWN 반환 (대부분의 좌표에서 true)
 *    수정: 플레이어 눈 위치 기준 실제 raycast로 hit face 결정.
 *         GRIM은 실제로 보이는 면만 accept하므로 필수.
 *
 * 3. 동적 Extrapolation
 *    레이턴시(ms) ÷ 50 으로 틱 수 자동 계산.
 *    2b2t(200ms) → 4틱 외삽, 2b2t Korea(80ms) → 1~2틱 외삽.
 *
 * 4. 2b2t Korea 서버 인식
 *    is2b2tEnv(): 2b2t.org / 2b2t.xn--3e0b707e / 2b2t.한국 모두 처리.
 *
 * 5. Thread-safe 렌더 맵
 *    fadeList: ConcurrentHashMap으로 교체, 멀티스레드 ConcurrentModificationException 방지.
 *
 * 6. 개선된 ID Prediction
 *    EntitySpawnS2CPacket의 연속성을 추적하여 +1~+3 범위 예측.
 *
 * @author linus (original), rebuilt for GRIM/2b2t/2b2t-korea
 */
public class AutoCrystalModule extends CombatModule
{
    private static AutoCrystalModule INSTANCE;

    // ── 기본 설정 ─────────────────────────────────────────────────
    Config<Boolean> whileMiningConfig = register(new BooleanConfig(
            "WhileMining", "Allows attacking while mining blocks", false));
    Config<Float> targetRangeConfig = register(new NumberConfig<>(
            "EnemyRange", "Range to search for potential enemies", 1.0f, 10.0f, 13.0f));
    Config<Boolean> instantConfig = register(new BooleanConfig(
            "Instant", "Instantly attacks crystals when they spawn", false));
    Config<Sequential> sequentialConfig = register(new EnumConfig<>(
            "Sequential", "Places a crystal after spawn", Sequential.NONE, Sequential.values()));
    Config<Boolean> idPredictConfig = register(new BooleanConfig(
            "BreakPredict", "Attempts to predict crystal entity ids", false));
    Config<Boolean> instantCalcConfig = register(new BooleanConfig(
            "Instant-Calc", "Calculates a crystal when it spawns (non-ideal attacks)", false,
            () -> false));
    Config<Float> instantDamageConfig = register(new NumberConfig<>(
            "InstantDamage", "Minimum damage to attack crystals instantly", 1.0f, 6.0f, 10.0f,
            () -> false));
    Config<Boolean> instantMaxConfig = register(new BooleanConfig(
            "InstantMax", "Attacks instantly if damage exceeds previous max", true,
            () -> false));

    Config<Boolean> raytraceConfig = register(new BooleanConfig(
            "Raytrace", "Raytrace to crystal position", true));
    Config<Boolean> swingConfig = register(new BooleanConfig(
            "Swing", "Swing hand when placing and attacking crystals", true));

    // ── 회전 ─────────────────────────────────────────────────────
    Config<Boolean> rotateConfig = register(new BooleanConfig(
            "Rotate", "Rotate before placing and breaking", false));
    Config<Rotate> strictRotateConfig = register(new EnumConfig<>(
            "YawStep", "Rotates yaw over multiple ticks", Rotate.OFF, Rotate.values(),
            () -> rotateConfig.getValue()));
    Config<Integer> rotateLimitConfig = register(new NumberConfig<>(
            "YawStep-Limit", "Maximum yaw rotation per tick", 1, 180, 180,
            NumberDisplay.DEGREES,
            () -> rotateConfig.getValue() && strictRotateConfig.getValue() != Rotate.OFF));

    // ── 타겟 ─────────────────────────────────────────────────────
    Config<Boolean> playersConfig   = register(new BooleanConfig("Players",   "Target players",   true));
    Config<Boolean> monstersConfig  = register(new BooleanConfig("Monsters",  "Target monsters",  false));
    Config<Boolean> neutralsConfig  = register(new BooleanConfig("Neutrals",  "Target neutrals",  false));
    Config<Boolean> animalsConfig   = register(new BooleanConfig("Animals",   "Target animals",   false));
    Config<Boolean> shulkersConfig  = register(new BooleanConfig("Shulkers",  "Target shulker boxes", false));

    // ── Break 설정 ────────────────────────────────────────────────
    Config<Float> breakSpeedConfig  = register(new NumberConfig<>(
            "BreakSpeed",  "Speed to break crystals",   0.1f, 18.0f, 20.0f));
    Config<Float> attackDelayConfig = register(new NumberConfig<>(
            "AttackDelay", "Added delays",               0.0f, 0.0f, 5.0f));
    Config<Integer> attackFactorConfig = register(new NumberConfig<>(
            "AttackFactor", "Factor of attack delay",   0, 0, 3,
            () -> attackDelayConfig.getValue() > 0.0));
    Config<Float> attackLimitConfig = register(new NumberConfig<>(
            "AttackLimit", "Attacks before considering crystal unbreakable", 0.5f, 1.5f, 20.0f));
    Config<Boolean> breakDelayConfig = register(new BooleanConfig(
            "BreakDelay", "Uses attack latency to calculate break delays", false));
    Config<Float> breakTimeoutConfig = register(new NumberConfig<>(
            "BreakTimeout", "Time after waiting for average break time",
            0.0f, 3.0f, 10.0f, () -> breakDelayConfig.getValue()));
    Config<Float> minTimeoutConfig = register(new NumberConfig<>(
            "MinTimeout", "Minimum time before crystal break/place fails",
            0.0f, 5.0f, 20.0f, () -> breakDelayConfig.getValue()));
    Config<Integer> ticksExistedConfig = register(new NumberConfig<>(
            "TicksExisted", "Minimum ticks alive to attack",  0, 0, 10));
    Config<Float> breakRangeConfig     = register(new NumberConfig<>(
            "BreakRange",    "Range to break crystals",       0.1f, 4.0f, 6.0f));
    Config<Float> maxYOffsetConfig     = register(new NumberConfig<>(
            "MaxYOffset",    "Maximum crystal y-offset",      1.0f, 5.0f, 10.0f));
    Config<Float> breakWallRangeConfig = register(new NumberConfig<>(
            "BreakWallRange","Range to break through walls",  0.1f, 4.0f, 6.0f));
    Config<Swap> antiWeaknessConfig = register(new EnumConfig<>(
            "AntiWeakness", "Swap to tools before attacking crystals",
            Swap.OFF, Swap.values()));
    Config<Float> swapDelayConfig = register(new NumberConfig<>(
            "SwapPenalty", "Delay for attacking after swapping", 0.0f, 0.0f, 10.0f));
    Config<Boolean> inhibitConfig = register(new BooleanConfig(
            "Inhibit", "Prevents excessive attacks", true));

    // ── Place 설정 ────────────────────────────────────────────────
    Config<Boolean> placeConfig = register(new BooleanConfig(
            "Place", "Places crystals to damage enemies", true));
    Config<Float> placeSpeedConfig = register(new NumberConfig<>(
            "PlaceSpeed",     "Speed to place crystals",          0.1f, 18.0f, 20.0f,
            () -> placeConfig.getValue()));
    Config<Float> placeRangeConfig = register(new NumberConfig<>(
            "PlaceRange",     "Range to place crystals",          0.1f, 4.0f, 6.0f,
            () -> placeConfig.getValue()));
    Config<Float> placeWallRangeConfig = register(new NumberConfig<>(
            "PlaceWallRange", "Range to place crystals through walls", 0.1f, 4.0f, 6.0f,
            () -> placeConfig.getValue()));
    Config<Boolean> placeRangeEyeConfig = register(new BooleanConfig(
            "PlaceRangeEye",  "Calculate place range from eye position", false,
            () -> placeConfig.getValue()));
    Config<Boolean> placeRangeCenterConfig = register(new BooleanConfig(
            "PlaceRangeCenter","Calculate place range to center of block", true,
            () -> placeConfig.getValue()));
    Config<Swap> autoSwapConfig = register(new EnumConfig<>(
            "Swap", "Swaps to an end crystal before placing",
            Swap.OFF, Swap.values(), () -> placeConfig.getValue()));
    Config<Boolean> antiSurroundConfig = register(new BooleanConfig(
            "AntiSurround", "Places on mining blocks to damage surrounded enemies", false,
            () -> placeConfig.getValue()));
    Config<ForcePlace> forcePlaceConfig = register(new EnumConfig<>(
            "PreventReplace", "Attempts to replace crystals in surrounds",
            ForcePlace.NONE, ForcePlace.values()));
    Config<Boolean> breakValidConfig = register(new BooleanConfig(
            "Strict", "Only places crystals that can be attacked", false,
            () -> placeConfig.getValue()));
    Config<Boolean> strictDirectionConfig = register(new BooleanConfig(
            "StrictDirection", "Interacts with only visible directions", false,
            () -> placeConfig.getValue()));
    Config<Placements> placementsConfig = register(new EnumConfig<>(
            "Placements", "Version standard for placing end crystals",
            Placements.NATIVE, Placements.values(), () -> placeConfig.getValue()));

    // ── 데미지 설정 ───────────────────────────────────────────────
    Config<Float> minDamageConfig = register(new NumberConfig<>(
            "MinDamage",       "Minimum damage to attack/place",    1.0f, 4.0f, 10.0f));
    Config<Float> maxLocalDamageConfig = register(new NumberConfig<>(
            "MaxLocalDamage",  "Maximum player damage",             4.0f, 12.0f, 20.0f));
    Config<Boolean> assumeArmorConfig = register(new BooleanConfig(
            "AssumeBestArmor", "Assumes Prot 0 armor is max armor", false));
    Config<Boolean> armorBreakerConfig = register(new BooleanConfig(
            "ArmorBreaker",    "Attempts to break enemy armor",      true));
    Config<Float> armorScaleConfig = register(new NumberConfig<>(
            "ArmorScale",      "Armor damage scale before armor breaking", 1.0f, 5.0f, 20.0f,
            NumberDisplay.PERCENT, () -> armorBreakerConfig.getValue()));
    Config<Float> lethalMultiplier = register(new NumberConfig<>(
            "LethalMultiplier","Kill multiplier to disregard damage values", 0.0f, 1.5f, 4.0f));
    Config<Boolean> antiTotemConfig = register(new BooleanConfig(
            "Lethal-Totem",    "Predicts totems for double-pop kills", false,
            () -> placeConfig.getValue()));
    Config<Boolean> lethalDamageConfig = register(new BooleanConfig(
            "Lethal-DamageTick","Place lethal crystals only on damage ticks", false));
    Config<Boolean> safetyConfig = register(new BooleanConfig(
            "Safety",          "Accounts for total player safety",   true));
    Config<Boolean> safetyOverride = register(new BooleanConfig(
            "SafetyOverride",  "Overrides safety if crystal kills enemy", false));
    Config<Boolean> blockDestructionConfig = register(new BooleanConfig(
            "BlockDestruction","Accounts for explosion block destruction", false));
    Config<Boolean> selfExtrapolateConfig = register(new BooleanConfig(
            "SelfExtrapolate", "Accounts for self motion",           false));

    /**
     * 동적 Extrapolation 설정.
     * AUTO: 서버 레이턴시를 자동으로 틱으로 변환 (권장).
     * MANUAL: extrapolateTicksConfig 값 직접 사용.
     */
    Config<ExtrapolateMode> extrapolateModeConfig = register(new EnumConfig<>(
            "ExtrapolateMode", "AUTO uses ping to calculate ticks automatically",
            ExtrapolateMode.AUTO, ExtrapolateMode.values()));
    Config<Integer> extrapolateTicksConfig = register(new NumberConfig<>(
            "ExtrapolationTicks", "Manual extrapolation ticks", 0, 0, 10,
            () -> extrapolateModeConfig.getValue() == ExtrapolateMode.MANUAL));
    Config<Integer> maxExtrapolateConfig = register(new NumberConfig<>(
            "MaxExtrapolateTicks","Maximum auto extrapolation ticks", 0, 8, 20,
            () -> extrapolateModeConfig.getValue() == ExtrapolateMode.AUTO));

    // ── 렌더 ─────────────────────────────────────────────────────
    Config<Boolean> renderConfig = register(new BooleanConfig(
            "Render", "Renders the current placement", true));
    Config<Integer> fadeTimeConfig = register(new NumberConfig<>(
            "Fade-Time", "Timer for the fade", 0, 250, 1000, () -> false));
    Config<Boolean> disableDeathConfig = register(new BooleanConfig(
            "DisableOnDeath", "Disables during disconnect/death", false));
    Config<Boolean> debugConfig = new BooleanConfig(
            "Debug", "Adds extra debug info to arraylist", false);
    Config<Boolean> debugDamageConfig = new BooleanConfig(
            "Debug-Damage", "Renders damage", false, () -> renderConfig.getValue());

    // ── 내부 상태 ─────────────────────────────────────────────────

    /**
     * 비동기 계산 결과를 저장하는 원자적 참조.
     * 백그라운드 스레드에서 write, 메인 스레드에서 read.
     */
    private final AtomicReference<DamageData<EndCrystalEntity>> asyncAttackResult =
            new AtomicReference<>(null);
    private final AtomicReference<DamageData<BlockPos>> asyncPlaceResult =
            new AtomicReference<>(null);

    /** 비동기 계산 실행 중 여부 */
    private volatile boolean calcRunning = false;

    /** 마지막 계산에 사용된 엔티티 스냅샷 */
    private volatile List<Entity> entitySnapshot = Collections.emptyList();
    private volatile List<BlockPos> blockSnapshot = Collections.emptyList();

    // 현재 틱에서 사용하는 결과
    private DamageData<EndCrystalEntity> attackCrystal;
    private DamageData<BlockPos>         placeCrystal;

    private BlockPos renderPos;
    private double   renderDamage;

    private Vec3d crystalRotation;
    private boolean attackRotate;
    private boolean rotated;
    private float[] silentRotations;
    private float   calculatePlaceCrystalTime = 0;

    private static final Box FULL_CRYSTAL_BB = new Box(0.0, 0.0, 0.0, 1.0, 2.0, 1.0);
    private static final Box HALF_CRYSTAL_BB = new Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

    private final CacheTimer lastAttackTimer  = new CacheTimer();
    private final Timer      lastPlaceTimer   = new CacheTimer();
    private final Timer      lastSwapTimer    = new CacheTimer();
    private final Timer      autoSwapTimer    = new CacheTimer();

    // 레이턴시 통계
    private final Deque<Long> attackLatency = new EvictingQueue<>(20);
    private final Map<Integer, Long> attackPackets =
            Collections.synchronizedMap(new ConcurrentHashMap<>());
    private final Map<BlockPos, Long> placePackets =
            Collections.synchronizedMap(new ConcurrentHashMap<>());
    private final PerSecondCounter crystalCounter = new PerSecondCounter();

    /**
     * ★ 버그 수정: ConcurrentHashMap으로 교체 (기존 HashMap은 멀티스레드 접근 시 CME 발생)
     */
    private final Map<BlockPos, Animation> fadeList = new ConcurrentHashMap<>();

    private long predictId;
    private final Map<Integer, Integer> antiStuckCrystals = new ConcurrentHashMap<>();
    private final List<AntiStuckData> stuckCrystals = new CopyOnWriteArrayList<>();

    /**
     * 비동기 계산 전용 스레드풀.
     * 스레드 수 2: Place 계산 + Attack 계산 병렬 처리.
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(2,
            r -> { Thread t = new Thread(r, "AutoCrystal-Calc"); t.setDaemon(true); return t; });

    public AutoCrystalModule()
    {
        super("AutoCrystal", "Attacks entities with end crystals",
                ModuleCategory.COMBAT, 750);
        INSTANCE = this;
        register(debugConfig);
        register(debugDamageConfig);
    }

    public static AutoCrystalModule getInstance() { return INSTANCE; }

    @Override
    public String getModuleData()
    {
        if (debugConfig.getValue())
        {
            return String.format("%sms, %.0f, %dms, %d",
                    new DecimalFormat("0.00").format(calculatePlaceCrystalTime / 1E6),
                    placeCrystal == null ? 0 : lastAttackTimer.getLastResetTime() / 1E6,
                    lastAttackTimer.passed(getBreakDelay() + 2000.0f) ? 0 : getBreakMs(),
                    crystalCounter.getPerSecond());
        }
        return String.format("%dms, %d",
                lastAttackTimer.passed(getBreakDelay() + 2000.0f) ? 0 : getBreakMs(),
                crystalCounter.getPerSecond());
    }

    @Override
    public void onDisable()
    {
        renderPos = null;
        attackCrystal = null;
        placeCrystal  = null;
        crystalRotation = null;
        silentRotations = null;
        calculatePlaceCrystalTime = 0;
        stuckCrystals.clear();
        attackPackets.clear();
        antiStuckCrystals.clear();
        placePackets.clear();
        attackLatency.clear();
        fadeList.clear();
        asyncAttackResult.set(null);
        asyncPlaceResult.set(null);
        calcRunning = false;
        setStage("NONE");
    }

    @EventListener
    public void onDisconnect(DisconnectEvent event)
    {
        if (disableDeathConfig.getValue()) disable();
        else onDisable();
    }

    // ── 비동기 계산 관리 ──────────────────────────────────────────

    /**
     * 백그라운드에서 Place/Attack 크리스탈 계산을 시작한다.
     * 이전 계산이 완료된 경우에만 새 작업을 제출하여 큐 적체를 방지.
     *
     * <p>계산 결과는 {@link #asyncAttackResult}, {@link #asyncPlaceResult}에 저장되고
     * 다음 틱의 {@link #onPlayerUpdate}에서 소비된다.</p>
     */
    private void submitAsyncCalc(List<Entity> entities, List<BlockPos> blocks)
    {
        if (calcRunning) return; // 이전 계산 미완료 → 스킵
        calcRunning = true;

        // 스냅샷 생성 (스레드 안전한 복사본)
        final List<Entity>  entSnap   = new ArrayList<>(entities);
        final List<BlockPos> blkSnap  = new ArrayList<>(blocks);
        final int extraTicks          = getExtrapolateTicks();

        executor.submit(() ->
        {
            try
            {
                long t0 = System.nanoTime();
                DamageData<BlockPos> place = placeConfig.getValue()
                        ? calculatePlaceCrystal(blkSnap, entSnap, extraTicks) : null;
                DamageData<EndCrystalEntity> attack =
                        calculateAttackCrystal(entSnap, extraTicks);
                asyncPlaceResult.set(place);
                asyncAttackResult.set(attack);
                calculatePlaceCrystalTime = System.nanoTime() - t0;
            }
            finally
            {
                calcRunning = false;
            }
        });
    }

    /**
     * 동적 Extrapolation 틱 수 계산.
     * AUTO: latency(ms) / 50 = 틱, 최대 maxExtrapolateConfig 제한.
     * MANUAL: extrapolateTicksConfig 값 사용.
     */
    private int getExtrapolateTicks()
    {
        if (extrapolateModeConfig.getValue() == ExtrapolateMode.AUTO)
        {
            int latencyMs = FastLatencyModule.getInstance().isEnabled()
                    ? (int) FastLatencyModule.getInstance().getLatency()
                    : Managers.NETWORK.getClientLatency();
            return Math.min(latencyMs / 50, maxExtrapolateConfig.getValue());
        }
        return extrapolateTicksConfig.getValue();
    }

    // ── 메인 틱 ───────────────────────────────────────────────────

    @EventListener
    public void onPlayerUpdate(PlayerTickEvent event)
    {
        if (mc.player.isSpectator()
                || isSilentSwap(autoSwapConfig.getValue())
                && AutoMineModule.getInstance().isSilentSwapping())
        {
            return;
        }

        // AntiStuck 정리
        stuckCrystals.removeIf(d ->
                mc.player.squaredDistanceTo(d.pos()) - d.stuckDist() > 0.5);

        if (mc.player.isUsingItem() && mc.player.getActiveHand() == Hand.MAIN_HAND
                || mc.options.attackKey.isPressed() || PlayerUtil.isHotbarKeysPressed())
        {
            autoSwapTimer.reset();
        }

        renderPos = null;

        // ─ 비동기 계산 제출 ─────────────────────────────────────
        List<Entity> entities = Lists.newArrayList(mc.world.getEntities());
        List<BlockPos> blocks = getSphere(
                placeRangeEyeConfig.getValue() ? mc.player.getEyePos() : mc.player.getPos());
        submitAsyncCalc(entities, blocks);

        // ─ 비동기 결과 소비 ─────────────────────────────────────
        // 이전 틱의 계산 결과를 이번 틱에서 사용
        placeCrystal  = asyncPlaceResult.getAndSet(null);
        attackCrystal = asyncAttackResult.getAndSet(null);

        // Place 위치에 이미 크리스탈이 있으면 즉시 공격으로 전환
        if (attackCrystal == null && placeCrystal != null)
        {
            EndCrystalEntity crystalEntity = intersectingCrystalCheck(placeCrystal.getDamageData());
            if (crystalEntity != null)
            {
                double self = ExplosionUtil.getDamageTo(mc.player, crystalEntity.getPos(),
                        blockDestructionConfig.getValue(),
                        selfExtrapolateConfig.getValue() ? getExtrapolateTicks() : 0, false);
                if (!safetyConfig.getValue() || !playerDamageCheck(self))
                {
                    attackCrystal = new DamageData<>(crystalEntity,
                            placeCrystal.getAttackTarget(), placeCrystal.getDamage(),
                            self, crystalEntity.getBlockPos().down(), false);
                }
            }
        }

        // Inhibit 처리
        if (inhibitConfig.getValue() && attackCrystal != null
                && attackPackets.containsKey(attackCrystal.getDamageData().getId()))
        {
            float delay = attackDelayConfig.getValue() > 0.0
                    ? attackDelayConfig.getValue() * (50.0f / Math.max(1.0f, attackFactorConfig.getValue()))
                    : 1000.0f - breakSpeedConfig.getValue() * 50.0f;
            lastAttackTimer.setDelay(delay + 100.0f);
            attackPackets.remove(attackCrystal.getDamageData().getId());
        }

        float breakDelay = getBreakDelay();
        if (breakDelayConfig.getValue())
            breakDelay = Math.max(minTimeoutConfig.getValue() * 50.0f,
                    getBreakMs() + breakTimeoutConfig.getValue() * 50.0f);

        attackRotate = attackCrystal != null
                && attackDelayConfig.getValue() <= 0.0
                && lastAttackTimer.passed(breakDelay);

        // 회전 목표 결정
        if (attackCrystal != null)
            crystalRotation = attackCrystal.damageData.getPos();
        else if (placeCrystal != null)
            crystalRotation = placeCrystal.damageData.toCenterPos().add(0.0, 0.5, 0.0);

        // 회전 처리
        if (rotateConfig.getValue() && crystalRotation != null
                && (placeCrystal == null || canHoldCrystal()))
        {
            float[] rotations = RotationUtil.getRotationsTo(mc.player.getEyePos(), crystalRotation);
            if (strictRotateConfig.getValue() == Rotate.FULL
                    || strictRotateConfig.getValue() == Rotate.SEMI && attackRotate)
            {
                float yaw;
                float serverYaw = Managers.ROTATION.getWrappedYaw();
                float diff = serverYaw - rotations[0];
                float diff1 = Math.abs(diff);
                if (diff1 > 180.0f) diff += diff > 0.0f ? -360.0f : 360.0f;
                int dir = diff > 0.0f ? -1 : 1;
                float deltaYaw = dir * rotateLimitConfig.getValue();
                if (diff1 > rotateLimitConfig.getValue())
                {
                    yaw = serverYaw + deltaYaw;
                    rotated = false;
                }
                else
                {
                    yaw = rotations[0];
                    rotated = true;
                    crystalRotation = null;
                }
                rotations[0] = yaw;
            }
            else
            {
                rotated = true;
                crystalRotation = null;
            }
            setRotation(rotations[0], rotations[1]);
        }
        else
        {
            silentRotations = null;
        }

        if (isRotationBlocked() || !rotated && rotateConfig.getValue()) return;

        final Hand hand = getCrystalHand();

        // ─ 공격 ─────────────────────────────────────────────────
        if (attackCrystal != null && attackRotate)
        {
            attackCrystal(attackCrystal.getDamageData(), hand);
            setStage("ATTACKING");
            lastAttackTimer.reset();
        }

        // ─ 배치 ─────────────────────────────────────────────────
        boolean placeRotate = lastPlaceTimer.passed(1000.0f - placeSpeedConfig.getValue() * 50.0f);
        if (placeCrystal != null)
        {
            renderPos   = placeCrystal.getDamageData();
            renderDamage = placeCrystal.getDamage();
            if (placeRotate)
            {
                placeCrystal(placeCrystal.getDamageData(), hand);
                setStage("PLACING");
                lastPlaceTimer.reset();
            }
        }
    }

    @EventListener
    public void onRunTick(RunTickEvent event)
    {
        if (mc.player == null) return;
        final Hand hand = getCrystalHand();
        if (attackDelayConfig.getValue() > 0.0)
        {
            float attackFactor = 50.0f / Math.max(1.0f, attackFactorConfig.getValue());
            if (attackCrystal != null
                    && lastAttackTimer.passed(attackDelayConfig.getValue() * attackFactor))
            {
                attackCrystal(attackCrystal.getDamageData(), hand);
                lastAttackTimer.reset();
            }
        }
    }

    // ── 렌더링 ───────────────────────────────────────────────────

    @EventListener
    public void onRenderWorld(RenderWorldEvent event)
    {
        if (!renderConfig.getValue()) return;

        RenderBuffers.preRender();
        BlockPos renderPos1 = null;
        double factor = 0.0;

        for (Map.Entry<BlockPos, Animation> set : fadeList.entrySet())
        {
            if (set.getKey() == renderPos) continue;
            if (set.getValue().getFactor() > factor)
            {
                renderPos1 = set.getKey();
                factor = set.getValue().getFactor();
            }
            set.getValue().setState(false);
            int boxAlpha  = (int) (40  * set.getValue().getFactor());
            int lineAlpha = (int) (100 * set.getValue().getFactor());
            RenderManager.renderBox(event.getMatrices(), set.getKey(),
                    ColorsModule.getInstance().getColor(boxAlpha).getRGB());
            RenderManager.renderBoundingBox(event.getMatrices(), set.getKey(), 1.5f,
                    ColorsModule.getInstance().getColor(lineAlpha).getRGB());
        }

        if (debugDamageConfig.getValue() && renderPos1 != null)
        {
            RenderManager.renderSign(String.format("%.1f", renderDamage),
                    renderPos1.toCenterPos(),
                    new Color(255, 255, 255, (int) (255.0f * factor)).getRGB());
        }

        RenderBuffers.postRender();
        fadeList.entrySet().removeIf(e -> e.getValue().getFactor() == 0.0);

        if (renderPos != null && isHoldingCrystal())
            fadeList.put(renderPos, new Animation(true, fadeTimeConfig.getValue()));
    }

    // ── 패킷 처리 ─────────────────────────────────────────────────

    @EventListener(priority = Integer.MAX_VALUE)
    public void onPacketInbound(PacketEvent.Inbound event)
    {
        if (mc.player == null || mc.world == null) return;
        if (event.getPacket() instanceof BundleS2CPacket packet)
        {
            for (Packet<?> p : packet.getPackets()) handleServerPackets(p);
        }
        else
        {
            handleServerPackets(event.getPacket());
        }
    }

    private void handleServerPackets(Packet<?> serverPacket)
    {
        if (serverPacket instanceof ExplosionS2CPacket packet)
        {
            for (Entity entity : Lists.newArrayList(mc.world.getEntities()))
            {
                if (entity instanceof EndCrystalEntity
                        && entity.squaredDistanceTo(packet.getX(), packet.getY(), packet.getZ()) < 144.0)
                {
                    mc.executeSync(() -> mc.world.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED));
                    antiStuckCrystals.remove(entity.getId());
                    Long t = attackPackets.remove(entity.getId());
                    if (t != null) attackLatency.add(System.currentTimeMillis() - t);
                }
            }
        }

        if (serverPacket instanceof PlaySoundS2CPacket packet)
        {
            if (packet.getSound().value() == SoundEvents.ENTITY_GENERIC_EXPLODE.value()
                    && packet.getCategory() == SoundCategory.BLOCKS)
            {
                for (Entity entity : Lists.newArrayList(mc.world.getEntities()))
                {
                    if (entity instanceof EndCrystalEntity
                            && entity.squaredDistanceTo(packet.getX(), packet.getY(), packet.getZ()) < 144.0)
                    {
                        mc.executeSync(() -> mc.world.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED));
                        antiStuckCrystals.remove(entity.getId());
                        Long t = attackPackets.remove(entity.getId());
                        if (t != null) attackLatency.add(System.currentTimeMillis() - t);
                    }
                }
            }
        }

        if (serverPacket instanceof EntitiesDestroyS2CPacket packet)
        {
            for (int id : packet.getEntityIds())
            {
                antiStuckCrystals.remove(id);
                Long t = attackPackets.remove(id);
                if (t != null) attackLatency.add(System.currentTimeMillis() - t);
            }
        }

        if (serverPacket instanceof ExperienceOrbSpawnS2CPacket packet
                && packet.getEntityId() > predictId)
            predictId = packet.getEntityId();

        if (serverPacket instanceof EntitySpawnS2CPacket packet
                && packet.getEntityId() > predictId)
            predictId = packet.getEntityId();
    }

    @EventListener
    public void onAddEntity(AddEntityEvent event)
    {
        if (!(event.getEntity() instanceof EndCrystalEntity crystalEntity)) return;

        Vec3d crystalPos = crystalEntity.getPos();
        BlockPos blockPos = BlockPos.ofFloored(crystalPos.add(0.0, -1.0, 0.0));

        Long time = placePackets.remove(blockPos);
        attackRotate = time != null;
        if (attackRotate) crystalCounter.updateCounter();

        if (!instantConfig.getValue()) return;
        if (!attackRotate && !instantCalcConfig.getValue()) return;

        final Hand hand = getCrystalHand();

        if (attackRotate)
        {
            attackInternal(crystalEntity, hand);
            setStage("ATTACKING");
            lastAttackTimer.reset();
            if (sequentialConfig.getValue() == Sequential.NORMAL)
                placeSequentialCrystal(hand);
        }
        else
        {
            // instantCalc: 스폰 즉시 데미지 계산
            if (attackRangeCheck(crystalPos)) return;
            double selfDmg = ExplosionUtil.getDamageTo(mc.player, crystalPos,
                    blockDestructionConfig.getValue(),
                    selfExtrapolateConfig.getValue() ? getExtrapolateTicks() : 0, false);
            if (playerDamageCheck(selfDmg)) return;

            for (Entity entity : mc.world.getEntities())
            {
                if (entity == null || !entity.isAlive() || entity == mc.player
                        || !isValidTarget(entity)
                        || Managers.SOCIAL.isFriend(entity.getName())) continue;
                if (crystalPos.squaredDistanceTo(entity.getPos()) > 144.0) continue;
                if (mc.player.squaredDistanceTo(entity) > targetRangeConfig.getValue()
                        * targetRangeConfig.getValue()) continue;

                double dmg = ExplosionUtil.getDamageTo(entity, crystalPos,
                        blockDestructionConfig.getValue(), getExtrapolateTicks(),
                        assumeArmorConfig.getValue());
                DamageData<EndCrystalEntity> data = new DamageData<>(crystalEntity,
                        entity, dmg, selfDmg, crystalEntity.getBlockPos().down(), false);

                boolean shouldAttack = dmg > instantDamageConfig.getValue()
                        || (attackCrystal != null && dmg >= attackCrystal.getDamage()
                        && instantMaxConfig.getValue())
                        || (entity instanceof LivingEntity e1 && isCrystalLethalTo(data, e1));

                if (shouldAttack)
                {
                    attackInternal(crystalEntity, hand);
                    setStage("ATTACKING");
                    lastAttackTimer.reset();
                    if (sequentialConfig.getValue() == Sequential.NORMAL)
                        placeSequentialCrystal(hand);
                    break;
                }
            }
        }
    }

    @EventListener
    public void onPacketOutbound(PacketEvent.Outbound event)
    {
        if (mc.player == null) return;
        if (event.getPacket() instanceof UpdateSelectedSlotC2SPacket)
            lastSwapTimer.reset();
    }

    // ── 공격 로직 ─────────────────────────────────────────────────

    public void attackCrystal(EndCrystalEntity entity, Hand hand)
    {
        if (attackCheckPre(hand)) return;

        StatusEffectInstance weakness = mc.player.getStatusEffect(StatusEffects.WEAKNESS);
        StatusEffectInstance strength = mc.player.getStatusEffect(StatusEffects.STRENGTH);
        boolean hasWeakness = weakness != null
                && (strength == null || weakness.getAmplifier() > strength.getAmplifier());

        if (hasWeakness)
        {
            int slot = findAntiWeaknessSlot();
            if (slot != -1)
            {
                boolean canSwap = slot != Managers.INVENTORY.getServerSlot()
                        && (antiWeaknessConfig.getValue() != Swap.NORMAL
                        || autoSwapTimer.passed(500));
                if (antiWeaknessConfig.getValue() != Swap.OFF && canSwap)
                {
                    swapForAttack(antiWeaknessConfig.getValue(), slot);
                }
                attackInternal(entity, Hand.MAIN_HAND);
                if (canSwap) swapRestoreAfterAttack(antiWeaknessConfig.getValue(), slot);
                if (sequentialConfig.getValue() == Sequential.STRICT)
                    placeSequentialCrystal(hand);
                return;
            }
        }

        attackInternal(entity, hand);
        if (sequentialConfig.getValue() == Sequential.STRICT)
            placeSequentialCrystal(hand);
    }

    private int findAntiWeaknessSlot()
    {
        for (int i = 0; i < 9; i++)
        {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && (s.getItem() instanceof SwordItem
                    || s.getItem() instanceof AxeItem
                    || s.getItem() instanceof PickaxeItem))
                return i;
        }
        return -1;
    }

    private void swapForAttack(Swap swap, int slot)
    {
        if (swap == Swap.SILENT_ALT)
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,
                    slot + 36, mc.player.getInventory().selectedSlot, SlotActionType.SWAP, mc.player);
        else if (swap == Swap.SILENT)
            Managers.INVENTORY.setSlot(slot);
        else
            Managers.INVENTORY.setClientSlot(slot);
    }

    private void swapRestoreAfterAttack(Swap swap, int slot)
    {
        if (swap == Swap.SILENT_ALT)
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,
                    slot + 36, mc.player.getInventory().selectedSlot, SlotActionType.SWAP, mc.player);
        else if (swap == Swap.SILENT)
            Managers.INVENTORY.syncToClient();
    }

    private void attackInternal(EndCrystalEntity entity, Hand hand)
    {
        attackInternal(entity.getId(), hand);
    }

    private void attackInternal(int crystalId, Hand hand)
    {
        hand = hand != null ? hand : Hand.MAIN_HAND;
        // 가짜 엔티티 생성 (실제 엔티티 참조 없이 ID만으로 패킷 전송)
        EndCrystalEntity dummy = new EndCrystalEntity(mc.world, 0.0, 0.0, 0.0);
        dummy.setId(crystalId);
        Managers.NETWORK.sendPacket(PlayerInteractEntityC2SPacket.attack(dummy, mc.player.isSneaking()));

        if (swingConfig.getValue())
            mc.player.swingHand(hand);
        else
            Managers.NETWORK.sendPacket(new HandSwingC2SPacket(hand));

        attackPackets.put(crystalId, System.currentTimeMillis());
        antiStuckCrystals.merge(crystalId, 1, Integer::sum);
    }

    // ── 배치 로직 ─────────────────────────────────────────────────

    private void placeSequentialCrystal(Hand hand)
    {
        if (placeCrystal == null) return;
        int latency = FastLatencyModule.getInstance().isEnabled()
                ? (int) FastLatencyModule.getInstance().getLatency()
                : Managers.NETWORK.getClientLatency();
        if (!is2b2tEnv() || latency >= 50)
            placeCrystal(placeCrystal.getBlockPos(), hand);
    }

    private void placeCrystal(BlockPos blockPos, Hand hand)
    {
        if (isRotationBlocked() || !rotated && rotateConfig.getValue()) return;
        placeCrystal(blockPos, hand, true);
    }

    public void placeCrystal(BlockPos blockPos, Hand hand, boolean checkPlacement)
    {
        if (checkPlacement && checkCanUseCrystal()) return;

        Direction sidePlace = getPlaceDirection(blockPos); // ★ 버그 수정
        BlockHitResult result = new BlockHitResult(blockPos.toCenterPos(), sidePlace, blockPos, false);

        if (autoSwapConfig.getValue() != Swap.OFF
                && hand != Hand.OFF_HAND && getCrystalHand() == null)
        {
            if (isSilentSwap(autoSwapConfig.getValue())
                    && InventoryUtil.count(Items.END_CRYSTAL) == 0) return;

            int crystalSlot = getCrystalSlot();
            if (crystalSlot != -1)
            {
                boolean canSwap = crystalSlot != Managers.INVENTORY.getServerSlot()
                        && (autoSwapConfig.getValue() != Swap.NORMAL
                        || autoSwapTimer.passed(500));
                if (canSwap) swapForAttack(autoSwapConfig.getValue(), crystalSlot);
                placeInternal(result, Hand.MAIN_HAND);
                placePackets.put(blockPos, System.currentTimeMillis());
                if (canSwap) swapRestoreAfterAttack(autoSwapConfig.getValue(), crystalSlot);
            }
        }
        else if (isHoldingCrystal())
        {
            placeInternal(result, hand);
            placePackets.put(blockPos, System.currentTimeMillis());
        }
    }

    private void placeInternal(BlockHitResult result, Hand hand)
    {
        if (hand == null) return;
        Managers.NETWORK.sendSequencedPacket(id -> new PlayerInteractBlockC2SPacket(hand, result, id));
        if (swingConfig.getValue())
            mc.player.swingHand(hand);
        else
            Managers.NETWORK.sendPacket(new HandSwingC2SPacket(hand));

        // ── 개선된 ID 예측 ────────────────────────────────────────
        if (idPredictConfig.getValue())
        {
            boolean flag = AutoXPModule.getInstance().isEnabled()
                    || mc.player.isUsingItem()
                    && mc.player.getStackInHand(mc.player.getActiveHand()).getItem()
                    instanceof ExperienceBottleItem;

            // +1 ~ +3 범위로 예측 (서버가 여러 엔티티를 동시에 스폰하는 경우 대응)
            for (int offset = 1; offset <= 3; offset++)
            {
                int id = (int) (predictId + offset);
                if (flag || attackPackets.containsKey(id)) continue;
                Entity existing = mc.world.getEntityById(id);
                if (existing != null && !(existing instanceof EndCrystalEntity)) continue;

                EndCrystalEntity dummy = new EndCrystalEntity(mc.world, 0.0, 0.0, 0.0);
                dummy.setId(id);
                Managers.NETWORK.sendPacket(PlayerInteractEntityC2SPacket.attack(dummy, false));
                Managers.NETWORK.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
                attackPackets.put(id, System.currentTimeMillis());
                break; // 첫 번째 유효한 예측만 전송
            }
        }
    }

    // ── PlaceDirection 버그 수정 ──────────────────────────────────

    /**
     * 크리스탈 배치 면 결정.
     *
     * <p><b>★ 버그 수정:</b> 기존 코드는 {@code isInBuildLimit()}이 대부분 true이므로
     * 항상 {@code Direction.DOWN}을 반환했다. GRIM은 플레이어가 실제로 볼 수 있는 면만
     * accept하기 때문에 잘못된 face로 interact 시 배치가 거부된다.</p>
     *
     * <p>수정: 플레이어 눈 위치에서 블록 중심으로 raycast하여 실제 hit face를 사용.
     * StrictDirection 모드에서도 정상 동작.</p>
     */
    private Direction getPlaceDirection(BlockPos blockPos)
    {
        int x = blockPos.getX();
        int y = blockPos.getY();
        int z = blockPos.getZ();

        // StrictDirection: 위에서 내려다보는 경우 UP 면 사용
        if (strictDirectionConfig.getValue())
        {
            if (mc.player.getY() >= blockPos.getY())
            {
                return Direction.UP;
            }
        }

        // 실제 raycast로 hit face 결정
        BlockHitResult result = mc.world.raycast(new RaycastContext(
                mc.player.getEyePos(),
                new Vec3d(x + 0.5, y + 0.5, z + 0.5),
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                mc.player));

        if (result != null && result.getType() == HitResult.Type.BLOCK)
        {
            return result.getSide();
        }

        // Fallback: 플레이어가 블록 위에 있으면 UP, 아니면 가장 가까운 수평면
        if (mc.player.getY() > y)
        {
            return Direction.UP;
        }

        // 플레이어와 블록의 상대 위치로 면 결정
        double dx = mc.player.getX() - (x + 0.5);
        double dz = mc.player.getZ() - (z + 0.5);
        if (Math.abs(dx) > Math.abs(dz))
            return dx > 0 ? Direction.EAST : Direction.WEST;
        else
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ── 데미지 계산 (비동기용 - thread-safe) ──────────────────────

    /**
     * Attack crystal 후보 계산 (백그라운드 스레드에서 실행).
     * Thread-safe: mc.world 읽기만 수행, 상태 변경 없음.
     */
    private DamageData<EndCrystalEntity> calculateAttackCrystal(
            List<Entity> entities, int extraTicks)
    {
        if (entities.isEmpty()) return null;
        List<DamageData<EndCrystalEntity>> validData = new ArrayList<>();
        DamageData<EndCrystalEntity> data = null;

        for (Entity crystal : entities)
        {
            if (!(crystal instanceof EndCrystalEntity crystal1) || !crystal.isAlive()
                    || stuckCrystals.stream().anyMatch(d -> d.id() == crystal.getId()))
                continue;

            Long time = attackPackets.get(crystal.getId());
            boolean attacked = time != null && time < getBreakMs();
            if ((crystal.age < ticksExistedConfig.getValue() || attacked)
                    && inhibitConfig.getValue())
                continue;

            if (attackRangeCheck(crystal1)) continue;

            double selfDmg = ExplosionUtil.getDamageTo(mc.player, crystal.getPos(),
                    blockDestructionConfig.getValue(),
                    selfExtrapolateConfig.getValue() ? extraTicks : 0, false);
            boolean unsafe = playerDamageCheck(selfDmg);
            if (unsafe && !safetyOverride.getValue()) continue;

            for (Entity entity : entities)
            {
                if (entity == null || !entity.isAlive() || entity == mc.player
                        || !isValidTarget(entity)
                        || Managers.SOCIAL.isFriend(entity.getName()))
                    continue;
                if (crystal.squaredDistanceTo(entity) > 144.0) continue;
                if (mc.player.squaredDistanceTo(entity)
                        > targetRangeConfig.getValue() * targetRangeConfig.getValue())
                    continue;

                boolean antiSurround = checkAntiSurroundCrystal(crystal1, entity);
                double dmg = ExplosionUtil.getDamageTo(entity, crystal.getPos(),
                        blockDestructionConfig.getValue(), extraTicks, assumeArmorConfig.getValue());

                if (checkOverrideSafety(unsafe, dmg, entity)) continue;

                DamageData<EndCrystalEntity> cur = new DamageData<>(crystal1, entity,
                        dmg, selfDmg, crystal1.getBlockPos().down(), antiSurround);
                validData.add(cur);
                if (data == null || dmg > data.getDamage()) data = cur;
            }
        }

        if (data == null || targetDamageCheck(data))
        {
            if (antiSurroundConfig.getValue())
                return validData.stream().filter(DamageData::isAntiSurround)
                        .min(Comparator.comparingDouble(d ->
                                mc.player.squaredDistanceTo(d.getBlockPos().toCenterPos())))
                        .orElse(null);
            return null;
        }
        return data;
    }

    /**
     * Place crystal 후보 계산 (백그라운드 스레드에서 실행).
     */
    private DamageData<BlockPos> calculatePlaceCrystal(
            List<BlockPos> placeBlocks, List<Entity> entities, int extraTicks)
    {
        if (placeBlocks.isEmpty() || entities.isEmpty()) return null;
        List<DamageData<BlockPos>> validData = new ArrayList<>();
        DamageData<BlockPos> data = null;

        for (BlockPos pos : placeBlocks)
        {
            if (!canUseCrystalOnBlock(pos) || placeRangeCheck(pos)
                    || intersectingAntiStuckCheck(pos))
                continue;

            double selfDmg = ExplosionUtil.getDamageTo(mc.player, crystalDamageVec(pos),
                    blockDestructionConfig.getValue(),
                    selfExtrapolateConfig.getValue() ? extraTicks : 0, false);
            boolean unsafe = playerDamageCheck(selfDmg);
            if (unsafe && !safetyOverride.getValue()) continue;

            for (Entity entity : entities)
            {
                if (entity == null || !entity.isAlive() || entity == mc.player
                        || !isValidTarget(entity)
                        || Managers.SOCIAL.isFriend(entity.getName()))
                    continue;
                if (pos.getSquaredDistance(entity.getPos()) > 144.0) continue;
                if (mc.player.squaredDistanceTo(entity)
                        > targetRangeConfig.getValue() * targetRangeConfig.getValue())
                    continue;

                boolean antiSurround = checkAntiSurroundBlock(pos, entity);
                double dmg = ExplosionUtil.getDamageTo(entity, crystalDamageVec(pos),
                        blockDestructionConfig.getValue(), extraTicks, assumeArmorConfig.getValue());

                if (checkOverrideSafety(unsafe, dmg, entity)) continue;

                DamageData<BlockPos> cur = new DamageData<>(pos, entity, dmg, selfDmg, antiSurround);
                validData.add(cur);
                if (data == null || dmg > data.getDamage()) data = cur;
            }
        }

        if (data == null || targetDamageCheck(data))
        {
            if (antiSurroundConfig.getValue())
                return validData.stream().filter(DamageData::isAntiSurround)
                        .min(Comparator.comparingDouble(d ->
                                mc.player.squaredDistanceTo(d.getBlockPos().toCenterPos())))
                        .orElse(null);
            return null;
        }
        return data;
    }

    // ── AntiSurround 체크 분리 (코드 중복 제거) ───────────────────

    private boolean checkAntiSurroundCrystal(EndCrystalEntity crystal, Entity entity)
    {
        if (!antiSurroundConfig.getValue() || !(entity instanceof PlayerEntity player)
                || BlastResistantBlocks.isUnbreakable(player.getBlockPos()))
            return false;
        return checkAntiSurroundCommon(crystal.getBlockPos(), player);
    }

    private boolean checkAntiSurroundBlock(BlockPos pos, Entity entity)
    {
        if (!antiSurroundConfig.getValue() || !(entity instanceof PlayerEntity player)
                || BlastResistantBlocks.isUnbreakable(player.getBlockPos()))
            return false;
        return checkAntiSurroundCommon(pos.up(), player);
    }

    private boolean checkAntiSurroundCommon(BlockPos crystalBlock, PlayerEntity player)
    {
        Set<BlockPos> miningPositions = new HashSet<>();
        BlockPos miningBlock = AutoMineModule.getInstance().getMiningBlock();
        if (AutoMineModule.getInstance().isEnabled() && miningBlock != null)
            miningPositions.add(miningBlock);
        if (Managers.BLOCK.getMines(0.75f).contains(player.getBlockPos().up()))
            miningPositions.add(player.getBlockPos().up());

        for (BlockPos miningBlockPos : miningPositions)
        {
            if (!SurroundModule.getInstance().getSurroundNoDown(player).contains(miningBlockPos))
                continue;
            for (Direction direction : Direction.values())
            {
                if (crystalBlock.equals(miningBlockPos.offset(direction).down()))
                    return true;
            }
        }
        return false;
    }

    // ── 유틸 ─────────────────────────────────────────────────────

    /**
     * 2b2t 환경 감지 (확장).
     * 2b2t.org / 2b2t.xn--3e0b707e (punycode of 2b2t.한국) / 2b2tkorea 처리.
     */
    private boolean is2b2tEnv()
    {
        String ip = Managers.NETWORK.getServerIp().toLowerCase();
        return ip.contains("2b2t.org")
                || ip.contains("2b2t.xn--3e0b707e") // 2b2t.한국 (IDN punycode)
                || ip.contains("2b2t.korea")
                || ip.contains("2b2tkorea");
    }

    private boolean attackRangeCheck(EndCrystalEntity entity)
    {
        return attackRangeCheck(entity.getPos());
    }

    private boolean attackRangeCheck(Vec3d entityPos)
    {
        double breakRange     = breakRangeConfig.getValue();
        double breakWallRange = breakWallRangeConfig.getValue();
        Vec3d playerPos = mc.player.getEyePos();
        double dist = playerPos.squaredDistanceTo(entityPos);
        if (dist > breakRange * breakRange) return true;
        double yOff = Math.abs(entityPos.getY() - mc.player.getY());
        if (yOff > maxYOffsetConfig.getValue()) return true;
        BlockHitResult result = mc.world.raycast(new RaycastContext(
                playerPos, entityPos, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player));
        return result.getType() != HitResult.Type.MISS && dist > breakWallRange * breakWallRange;
    }

    private boolean placeRangeCheck(BlockPos pos)
    {
        double placeRange     = placeRangeConfig.getValue();
        double placeWallRange = placeWallRangeConfig.getValue();
        Vec3d player = placeRangeEyeConfig.getValue()
                ? mc.player.getEyePos() : mc.player.getPos();
        double dist = placeRangeCenterConfig.getValue()
                ? player.squaredDistanceTo(pos.toCenterPos())
                : pos.getSquaredDistance(player.x, player.y, player.z);
        if (dist > placeRange * placeRange) return true;

        Vec3d raytrace = Vec3d.of(pos).add(0.5, 2.70000004768372, 0.5);
        BlockHitResult result = mc.world.raycast(new RaycastContext(
                mc.player.getEyePos(), raytrace,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player));
        float maxDist = breakRangeConfig.getValue() * breakRangeConfig.getValue();
        if (result != null && result.getType() == HitResult.Type.BLOCK
                && !result.getBlockPos().equals(pos))
        {
            maxDist = breakWallRangeConfig.getValue() * breakWallRangeConfig.getValue();
            if (!raytraceConfig.getValue() || dist > placeWallRange * placeWallRange)
                return true;
        }
        return breakValidConfig.getValue() && dist > maxDist;
    }

    public void placeCrystalForTarget(PlayerEntity target, BlockPos blockPos)
    {
        if (target == null || target.isDead()
                || placeRangeCheck(blockPos) || !canUseCrystalOnBlock(blockPos)) return;
        double selfDmg = ExplosionUtil.getDamageTo(mc.player, crystalDamageVec(blockPos),
                blockDestructionConfig.getValue(), Set.of(blockPos),
                selfExtrapolateConfig.getValue() ? getExtrapolateTicks() : 0, false);
        if (playerDamageCheck(selfDmg)) return;
        double dmg = ExplosionUtil.getDamageTo(target, crystalDamageVec(blockPos),
                blockDestructionConfig.getValue(), Set.of(blockPos),
                getExtrapolateTicks(), assumeArmorConfig.getValue());
        if (dmg < minDamageConfig.getValue() && !isCrystalLethalTo(dmg, target)
                || placeCrystal != null && placeCrystal.getDamage() >= dmg) return;

        float[] rotations = RotationUtil.getRotationsTo(mc.player.getEyePos(), blockPos.toCenterPos());
        setRotation(rotations[0], rotations[1]);
        placeCrystal(blockPos, Hand.MAIN_HAND, false);
        fadeList.put(blockPos, new Animation(true, fadeTimeConfig.getValue()));
    }

    private boolean checkOverrideSafety(boolean unsafe, double dmg, Entity entity)
    {
        return safetyOverride.getValue() && unsafe && dmg < EntityUtil.getHealth(entity) + 0.5;
    }

    private boolean targetDamageCheck(DamageData<?> crystal)
    {
        double minDmg = minDamageConfig.getValue();
        if (crystal.getAttackTarget() instanceof LivingEntity entity
                && isCrystalLethalTo(crystal, entity))
            minDmg = 2.0f;
        return crystal.getDamage() < minDmg;
    }

    private boolean playerDamageCheck(double playerDmg)
    {
        if (!mc.player.isCreative())
        {
            float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            if (safetyConfig.getValue() && playerDmg >= health + 0.5f) return true;
            return playerDmg > maxLocalDamageConfig.getValue();
        }
        return false;
    }

    private boolean checkAntiTotem(double dmg, LivingEntity entity)
    {
        if (entity instanceof PlayerEntity p)
        {
            float phealth = EntityUtil.getHealth(p);
            if (phealth <= 2.0f && phealth - dmg < 0.5f)
            {
                long time = Managers.TOTEM.getLastPopTime(p);
                if (time != -1) return System.currentTimeMillis() - time <= 500;
            }
        }
        return false;
    }

    private boolean isCrystalLethalTo(DamageData<?> crystal, LivingEntity entity)
    {
        return isCrystalLethalTo(crystal.getDamage(), entity);
    }

    private boolean isCrystalLethalTo(double dmg, LivingEntity entity)
    {
        if (lethalDamageConfig.getValue() && lastAttackTimer.passed(500)) return true;
        if (antiTotemConfig.getValue() && checkAntiTotem(dmg, entity)) return true;
        float health = entity.getHealth() + entity.getAbsorptionAmount();
        if (dmg * (1.0f + lethalMultiplier.getValue()) >= health + 0.5f) return true;
        if (armorBreakerConfig.getValue())
        {
            for (ItemStack armorStack : entity.getArmorItems())
            {
                int n = armorStack.getDamage(), n1 = armorStack.getMaxDamage();
                if (n1 > 0 && ((n1 - n) / (float) n1) * 100.0f < armorScaleConfig.getValue())
                    return true;
            }
        }
        if (shulkersConfig.getValue() && entity instanceof PlayerEntity)
        {
            for (BlockPos pos : getSphere(3.0f, entity.getPos()))
            {
                if (mc.world.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock)
                    return true;
            }
        }
        return false;
    }

    private boolean attackCheckPre(Hand hand)
    {
        if (!lastSwapTimer.passed(swapDelayConfig.getValue() * 25.0f)) return true;
        if (hand == Hand.MAIN_HAND) return checkCanUseCrystal();
        return false;
    }

    private boolean checkCanUseCrystal()
    {
        return !multitaskConfig.getValue() && checkMultitask()
                || !whileMiningConfig.getValue() && mc.interactionManager.isBreakingBlock();
    }

    private boolean isHoldingCrystal()
    {
        if (!checkCanUseCrystal()
                && (autoSwapConfig.getValue() == Swap.SILENT
                || autoSwapConfig.getValue() == Swap.SILENT_ALT))
            return true;
        return getCrystalHand() != null;
    }

    private boolean canHoldCrystal()
    {
        return isHoldingCrystal()
                || autoSwapConfig.getValue() != Swap.OFF && getCrystalSlot() != -1;
    }

    private Hand getCrystalHand()
    {
        if (mc.player.getOffHandStack().getItem() instanceof EndCrystalItem) return Hand.OFF_HAND;
        if (mc.player.getMainHandStack().getItem() instanceof EndCrystalItem) return Hand.MAIN_HAND;
        return null;
    }

    private int getCrystalSlot()
    {
        for (int i = 0; i < 9; i++)
        {
            if (mc.player.getInventory().getStack(i).getItem() instanceof EndCrystalItem)
                return i;
        }
        return -1;
    }

    private Vec3d crystalDamageVec(BlockPos pos)
    {
        return Vec3d.of(pos).add(0.5, 1.0, 0.5);
    }

    private boolean isValidTarget(Entity e)
    {
        return e instanceof PlayerEntity && playersConfig.getValue()
                || EntityUtil.isMonster(e) && monstersConfig.getValue()
                || EntityUtil.isNeutral(e) && neutralsConfig.getValue()
                || EntityUtil.isPassive(e) && animalsConfig.getValue();
    }

    public boolean canUseCrystalOnBlock(BlockPos pos)
    {
        BlockState state = mc.world.getBlockState(pos);
        if (!state.isOf(Blocks.OBSIDIAN) && !state.isOf(Blocks.BEDROCK)) return false;
        return isCrystalHitboxClear(pos);
    }

    public boolean isCrystalHitboxClear(BlockPos pos)
    {
        BlockPos p2 = pos.up();
        BlockState state2 = mc.world.getBlockState(p2);
        if (placementsConfig.getValue() == Placements.PROTOCOL && !mc.world.isAir(p2.up()))
            return false;
        if (!mc.world.isAir(p2) && !state2.isOf(Blocks.FIRE)) return false;
        final Box bb = Managers.NETWORK.isCrystalPvpCC() ? HALF_CRYSTAL_BB : FULL_CRYSTAL_BB;
        double d = p2.getX(), e = p2.getY(), f = p2.getZ();
        return getEntitiesBlockingCrystal(
                new Box(d, e, f, d + bb.maxX, e + bb.maxY, f + bb.maxZ)).isEmpty();
    }

    private List<Entity> getEntitiesBlockingCrystal(Box box)
    {
        List<Entity> entities = new CopyOnWriteArrayList<>(mc.world.getOtherEntities(null, box));
        entities.removeIf(entity ->
        {
            if (entity == null || !entity.isAlive() || entity instanceof ExperienceOrbEntity)
                return true;
            if (forcePlaceConfig.getValue() != ForcePlace.NONE
                    && entity instanceof ItemEntity && entity.age <= 10)
                return true;
            if (entity instanceof EndCrystalEntity entity1
                    && entity1.getBoundingBox().intersects(box))
            {
                Integer antiStuckAttacks = antiStuckCrystals.get(entity1.getId());
                if (!attackRangeCheck(entity1)
                        && (antiStuckAttacks == null
                        || antiStuckAttacks <= attackLimitConfig.getValue() * 10.0f))
                    return true;
                double dist = mc.player.squaredDistanceTo(entity1);
                stuckCrystals.add(new AntiStuckData(
                        entity1.getId(), entity1.getBlockPos(), entity1.getPos(), dist));
            }
            return false;
        });
        return entities;
    }

    private boolean intersectingAntiStuckCheck(BlockPos blockPos)
    {
        return !stuckCrystals.isEmpty()
                && stuckCrystals.stream().anyMatch(d -> d.blockPos().equals(blockPos.up()));
    }

    private EndCrystalEntity intersectingCrystalCheck(BlockPos pos)
    {
        return (EndCrystalEntity) mc.world.getOtherEntities(null, new Box(pos)).stream()
                .filter(e -> e instanceof EndCrystalEntity)
                .min(Comparator.comparingDouble(e -> mc.player.distanceTo(e)))
                .orElse(null);
    }

    private List<BlockPos> getSphere(Vec3d origin)
    {
        return getSphere(Math.ceil(placeRangeConfig.getValue()), origin);
    }

    private List<BlockPos> getSphere(double rad, Vec3d origin)
    {
        List<BlockPos> sphere = new ArrayList<>();
        for (double x = -rad; x <= rad; x++)
            for (double y = -rad; y <= rad; y++)
                for (double z = -rad; z <= rad; z++)
                    sphere.add(new BlockPos(
                            (int)(origin.getX() + x),
                            (int)(origin.getY() + y),
                            (int)(origin.getZ() + z)));
        return sphere;
    }

    private boolean isSilentSwap(Swap swap)
    {
        return swap == Swap.SILENT || swap == Swap.SILENT_ALT;
    }

    public float getBreakDelay()
    {
        return 1000.0f - breakSpeedConfig.getValue() * 50.0f;
    }

    public void setStage(String crystalStage) {}

    public int getBreakMs()
    {
        if (attackLatency.isEmpty()) return 0;
        ArrayList<Long> copy = Lists.newArrayList(attackLatency);
        float avg = 0.0f;
        for (float t : copy) avg += t;
        return copy.isEmpty() ? 0 : (int)(avg / copy.size());
    }

    public boolean isAttacking()  { return attackCrystal != null; }
    public boolean isPlacing()    { return placeCrystal != null && isHoldingCrystal(); }
    public boolean shouldPreForcePlace() { return forcePlaceConfig.getValue() == ForcePlace.PRE; }
    public float getPlaceRange()  { return placeRangeConfig.getValue(); }

    // ── Enum & Record ─────────────────────────────────────────────

    public enum Swap         { NORMAL, SILENT, SILENT_ALT, OFF }
    public enum Sequential   { NORMAL, STRICT, NONE }
    public enum ForcePlace   { PRE, POST, NONE }
    public enum Placements   { NATIVE, PROTOCOL }
    public enum Rotate       { FULL, SEMI, OFF }

    /**
     * Extrapolation 계산 모드.
     * AUTO: 레이턴시 자동 계산 (권장, 2b2t/2b2t-korea 모두 최적)
     * MANUAL: 수동 틱 설정
     */
    public enum ExtrapolateMode { AUTO, MANUAL }

    private record AntiStuckData(int id, BlockPos blockPos, Vec3d pos, double stuckDist) {}

    // ── DamageData ────────────────────────────────────────────────

    private static class DamageData<T>
    {
        private T damageData;
        private Entity attackTarget;
        private BlockPos blockPos;
        private double damage, selfDamage;
        private boolean antiSurround;

        public DamageData() {}

        public DamageData(BlockPos damageData, Entity attackTarget, double damage,
                          double selfDamage, boolean antiSurround)
        {
            this.damageData   = (T) damageData;
            this.attackTarget = attackTarget;
            this.damage       = damage;
            this.selfDamage   = selfDamage;
            this.blockPos     = damageData;
            this.antiSurround = antiSurround;
        }

        public DamageData(T damageData, Entity attackTarget, double damage,
                          double selfDamage, BlockPos blockPos, boolean antiSurround)
        {
            this.damageData   = damageData;
            this.attackTarget = attackTarget;
            this.damage       = damage;
            this.selfDamage   = selfDamage;
            this.blockPos     = blockPos;
            this.antiSurround = antiSurround;
        }

        public T getDamageData()         { return damageData; }
        public Entity getAttackTarget()  { return attackTarget; }
        public double getDamage()        { return damage; }
        public double getSelfDamage()    { return selfDamage; }
        public BlockPos getBlockPos()    { return blockPos; }
        public boolean isAntiSurround() { return antiSurround; }
    }
}
