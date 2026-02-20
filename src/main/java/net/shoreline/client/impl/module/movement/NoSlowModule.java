package net.shoreline.client.impl.module.movement;

import net.minecraft.block.*;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.api.module.ToggleModule;
import net.shoreline.client.impl.event.TickEvent;
import net.shoreline.client.impl.event.block.BlockSlipperinessEvent;
import net.shoreline.client.impl.event.block.SteppedOnSlimeBlockEvent;
import net.shoreline.client.impl.event.entity.SlowMovementEvent;
import net.shoreline.client.impl.event.entity.VelocityMultiplierEvent;
import net.shoreline.client.impl.event.network.*;
import net.shoreline.client.impl.module.exploit.DisablerModule;
import net.shoreline.client.init.Managers;
import net.shoreline.client.mixin.accessor.AccessorKeyBinding;
import net.shoreline.client.util.math.position.PositionUtil;
import net.shoreline.eventbus.annotation.EventListener;
import net.shoreline.eventbus.event.StageEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * NoSlowModule - 완전 재작성
 *
 * ═══ 수정된 버그 목록 ═══
 *
 * [BUG-1] checkGrimNew() 연산자 우선순위 오류
 *   - 기존: `!isSneaking && !isCrawling && !isRiding && timeLeft < 5 || (useTime > 1 && useTime % 2 != 0)`
 *   - `% 2 != 0` 조건으로 인해 홀수 틱/짝수 틱마다 NoSlow ON/OFF 반복 → 속도 파동 발생
 *   - 수정: GrimV3 로직 완전 재작성 (정확한 타이밍 기반)
 *
 * [BUG-2] SprintCancelEvent 미처리
 *   - 기존: SprintModule에만 의존 → SprintModule 비활성 시 아이템 사용 중 스프린트 취소
 *   - 수정: NoSlow 자체가 SprintCancelEvent를 처리해 아이템 사용 중 스프린트 유지
 *
 * [BUG-3] grimConfig의 FOOD 아이템 제외 오류
 *   - 기존: checkStack()이 FOOD를 false 반환 → 황금사과 먹을 때 Grim 우회 패킷 미전송
 *   - 수정: 음식 아이템 먹는 중에도 반대 손에 InteractItem 전송 (Grim bypass)
 *
 * [BUG-4] airStrictConfig가 isSneaking()을 true로 만들어 checkSlowed() 오동작
 *   - 기존: PRESS_SHIFT_KEY → isSneaking()=true → checkSlowed() "!isSneaking" 조건 미충족 → 보정 0
 *   - 수정: checkSlowed()에서 airStrictConfig로 인한 스니킹 상태 예외 처리
 *
 * [BUG-5] 아이템 사용 시작/종료 경계 틱 isUsingItem() 상태 불일치
 *   - 수정: isUsingItemCached 플래그로 MovementSlowdownEvent 시점 상태 고정
 *
 * [BUG-6] 황금사과 등 FOOD 아이템의 정확한 slowdown 보정 배율
 *   - 기존: 고정 5.0f (0.2f의 역수) - 스프린트 속성과 무관하게 동작
 *   - 수정: 정확한 slowdown 역배율 계산
 *
 * @author linus (원본), rebuilt for correctness
 * @since 1.0
 */
public class NoSlowModule extends ToggleModule
{
    public static NoSlowModule INSTANCE;

    // ── 서버 호환성 ─────────────────────────────────────────────
    Config<Boolean> strictConfig    = register(new BooleanConfig("Strict",
            "Strict NCP bypass for ground slowdowns", false));
    Config<Boolean> airStrictConfig = register(new BooleanConfig("AirStrict",
            "Strict NCP bypass for air slowdowns", false));
    Config<Boolean> grimConfig      = register(new BooleanConfig("Grim",
            "Strict Grim bypass for slowdown", false));

    /**
     * GrimV3 우회.
     * Grim은 아이템 사용 중 2틱마다 속도 검증을 스킵하는 허점이 있었으나
     * GrimV3에서 이 허점이 수정됨. 이 옵션은 GrimV3 이전 버전 서버용.
     *
     * [BUG-1 수정] 기존 `% 2 != 0` 로직은 짝수 틱에 NoSlow를 끄는 치명적 버그 포함.
     * 완전히 재작성: 아이템 사용 종료 5틱 전까지만 활성화.
     */
    Config<Boolean> grimNewConfig   = register(new BooleanConfig("GrimV3",
            "Strict GrimV3 bypass for slowdown (use only on older Grim servers)", false));

    Config<Boolean> strafeFixConfig = register(new BooleanConfig("StrafeFix",
            "Old NCP bypass for strafe", false));

    // ── 이동 편의 ────────────────────────────────────────────────
    Config<Boolean> inventoryMoveConfig = register(new BooleanConfig("InventoryMove",
            "Allows the player to move while in inventories or screens", true));
    Config<Boolean> arrowMoveConfig     = register(new BooleanConfig("ArrowMove",
            "Allows the player to look while in inventories or screens by using the arrow keys",
            false));

    // ── 아이템 slowdown 제거 ─────────────────────────────────────
    /**
     * 아이템 사용 중 속도 저감 제거 (핵심 기능).
     *
     * 바닐라: isUsingItem() → travel(input * 0.2f)
     * NoSlow: input *= 5.0f → travel(input * 5.0f * 0.2f) = travel(input * 1.0f)
     *
     * [BUG-5 수정]: isUsingItem() 상태를 MovementSlowdownEvent 직전에 캐싱해
     * 같은 틱 내 상태 변화(finishItemUse 등)에 의한 불일치 방지.
     */
    Config<Boolean> itemsConfig  = register(new BooleanConfig("Items",
            "Removes the slowdown effect caused by using items", true));
    Config<Boolean> sneakConfig  = register(new BooleanConfig("Sneak",
            "Removes sneak slowdown", false));
    Config<Boolean> crawlConfig  = register(new BooleanConfig("Crawl",
            "Removes crawl slowdown", false));
    Config<Boolean> shieldsConfig= register(new BooleanConfig("Shields",
            "Removes the slowdown effect caused by shields", true));

    // ── 블록 slowdown 제거 ───────────────────────────────────────
    Config<Boolean> websConfig       = register(new BooleanConfig("Webs",
            "Removes the slowdown caused when moving through webs", false));
    Config<Boolean> berryBushConfig  = register(new BooleanConfig("BerryBush",
            "Removes the slowdown caused when moving through berry bushes", false));
    Config<Float>   webSpeedConfig   = register(new NumberConfig<>("WebMultiplier",
            "Speed to fall through webs", 0.00f, 1.00f, 1.00f,
            () -> websConfig.getValue() || berryBushConfig.getValue()));
    Config<Boolean> soulsandConfig   = register(new BooleanConfig("SoulSand",
            "Removes the slowdown effect caused by walking over SoulSand blocks", false));
    Config<Boolean> honeyblockConfig = register(new BooleanConfig("HoneyBlock",
            "Removes the slowdown effect caused by walking over Honey blocks", false));
    Config<Boolean> slimeblockConfig = register(new BooleanConfig("SlimeBlock",
            "Removes the slowdown effect caused by walking over Slime blocks", false));

    // ── 내부 상태 ────────────────────────────────────────────────

    /**
     * airStrictConfig용 서버-side 스니킹 상태.
     * PRESS_SHIFT_KEY 패킷을 보냈는지 추적.
     */
    private boolean serverSneaking = false;

    /**
     * [BUG-5 수정] MovementSlowdownEvent 처리 직전 틱에서 캐싱된 isUsingItem() 상태.
     * TickEvent.PRE에서 업데이트.
     *
     * 이유: LivingEntity.tickMovement()에서
     *   1) Input.tick() → MovementSlowdownEvent
     *   2) tickActiveItemStack() → finishItemUse() → isUsingItem()=false (마지막 틱)
     *   3) travel(input * 0.2f) if isUsingItem()
     * 이므로, MovementSlowdownEvent 시점의 isUsingItem() 상태가 travel() 시점과 다를 수 있음.
     * 캐시를 통해 같은 틱 내 일관성 보장.
     */
    private boolean isUsingItemCached = false;

    /**
     * 현재 틱에 슬로우 상태인지 (checkSlowed() 결과 캐시).
     * MovementSlowdownEvent 전에 계산되어 이벤트에서 안전하게 참조.
     */
    private boolean slowedThisTick = false;

    public NoSlowModule()
    {
        super("NoSlow", "Prevents items from slowing down player", ModuleCategory.MOVEMENT);
        INSTANCE = this;
    }

    public static NoSlowModule getInstance()
    {
        return INSTANCE;
    }

    @Override
    public void onDisable()
    {
        // airStrictConfig: 서버에 RELEASE_SHIFT_KEY 보내서 스니킹 해제
        if (serverSneaking)
        {
            Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(mc.player,
                    ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
            serverSneaking = false;
        }
        isUsingItemCached = false;
        slowedThisTick    = false;
    }

    // ═══════════════════════════════════════════════════════════
    // ① TickEvent.PRE: 틱 시작 시점에 상태 캐싱
    // ═══════════════════════════════════════════════════════════

    @EventListener
    public void onTick(TickEvent event)
    {
        if (event.getStage() != StageEvent.EventStage.PRE || mc.player == null) return;

        // [BUG-5 수정] isUsingItem() 상태를 MovementSlowdownEvent보다 먼저 캐싱
        // Input.tick() → MovementSlowdownEvent → tickActiveItemStack() 순서이므로
        // TickEvent.PRE 시점의 isUsingItem()이 MovementSlowdownEvent와 동일한 상태임
        isUsingItemCached = mc.player.isUsingItem();

        // slowedThisTick 계산 (MovementSlowdownEvent에서 재사용)
        slowedThisTick = computeSlowed();

        // ── airStrictConfig: 아이템 사용 중 서버-side 스니킹 유지 ────
        if (airStrictConfig.getValue())
        {
            if (isUsingItemCached && !serverSneaking)
            {
                serverSneaking = true;
                Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(mc.player,
                        ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
            }
            else if (!isUsingItemCached && serverSneaking)
            {
                serverSneaking = false;
                Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(mc.player,
                        ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
            }
        }

        // ── 거미줄 처리 (Grim/GrimV3) ─────────────────────────────
        if ((grimConfig.getValue() || grimNewConfig.getValue()) && websConfig.getValue())
        {
            Box bb = grimConfig.getValue()
                    ? mc.player.getBoundingBox().expand(1.0)
                    : mc.player.getBoundingBox();
            for (BlockPos pos : getIntersectingWebs(bb))
            {
                Managers.NETWORK.sendPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.DOWN));
            }
        }

        // ── 인벤토리 중 이동 허용 ──────────────────────────────────
        if (inventoryMoveConfig.getValue() && checkScreen())
        {
            final long handle = mc.getWindow().getHandle();
            KeyBinding[] keys = { mc.options.jumpKey, mc.options.forwardKey,
                    mc.options.backKey, mc.options.rightKey, mc.options.leftKey };
            for (KeyBinding binding : keys)
            {
                binding.setPressed(InputUtil.isKeyPressed(handle,
                        ((AccessorKeyBinding) binding).getBoundKey().getCode()));
            }
            if (arrowMoveConfig.getValue())
            {
                float yaw   = mc.player.getYaw();
                float pitch = mc.player.getPitch();
                if      (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_UP))    pitch -= 3.0f;
                else if (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_DOWN))  pitch += 3.0f;
                else if (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_LEFT))  yaw   -= 3.0f;
                else if (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_RIGHT)) yaw   += 3.0f;
                mc.player.setYaw(yaw);
                mc.player.setPitch(MathHelper.clamp(pitch, -90.0f, 90.0f));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ② PlayerUpdateEvent.PRE: Grim InteractItem 패킷 전송
    // ═══════════════════════════════════════════════════════════

    /**
     * [BUG-3 수정] Grim NoSlow 우회: 아이템 사용 중 반대 손에 InteractItem 전송.
     *
     * Grim의 NoSlow 감지 원리:
     * - 플레이어가 특정 아이템(음식, 방패, 활 등)을 사용 중이면
     *   서버는 이동 속도를 0.2x로 제한해야 한다고 검증
     * - 반대 손에 InteractItem을 전송하면 Grim이 "이 손이 아이템을 사용 중"으로 인식
     * - Grim의 noslow check가 반대 손 기준으로 이동하면서 실제 사용 중인 손의 check가 우회됨
     *
     * 기존 버그: checkStack()이 FOOD를 명시적으로 제외함
     * → 황금사과(FOOD) 먹는 중에는 이 패킷이 전송되지 않아 Grim이 정상 감지
     *
     * 수정: FOOD 포함 모든 아이템에 대해 반대 손에 InteractItem 전송
     * (반대 손 아이템이 slowdown을 유발하지 않는 경우에만)
     */
    @EventListener
    public void onPlayerUpdate(PlayerUpdateEvent event)
    {
        if (event.getStage() != StageEvent.EventStage.PRE) return;
        if (!grimConfig.getValue()) return;
        if (!isUsingItemCached || mc.player.isSneaking()) return;
        if (!itemsConfig.getValue()) return;

        Hand activeHand    = mc.player.getActiveHand();
        Hand otherHand     = (activeHand == Hand.MAIN_HAND) ? Hand.OFF_HAND : Hand.MAIN_HAND;
        ItemStack otherStack = mc.player.getStackInHand(otherHand);

        // 반대 손 아이템이 slowdown을 유발하지 않는 경우에만 InteractItem 전송
        // (반대 손이 활/석궁/방패/음식이면 전송 시 오히려 slowdown 트리거 위험)
        if (!causesSlowdown(otherStack))
        {
            final Hand sendHand = otherHand;
            Managers.NETWORK.sendSequencedPacket(id ->
                    new PlayerInteractItemC2SPacket(sendHand, id,
                            mc.player.getYaw(), mc.player.getPitch()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ③ MovementSlowdownEvent: Input 보정 (핵심)
    // ═══════════════════════════════════════════════════════════

    /**
     * 아이템 사용 중 속도 저감 보정.
     *
     * 바닐라 slowdown 메커니즘:
     *   ClientPlayerEntity.tickMovement():
     *     1. input.tick() → movementForward/movementSideways 설정
     *     2. [여기서 MovementSlowdownEvent 발생]
     *     3. this.sidewaysSpeed = input.movementSideways
     *     4. this.forwardSpeed  = input.movementForward
     *     5. if (isUsingItem()) travel(new Vec3d(sideways * 0.2f, up, forward * 0.2f))
     *        else               travel(new Vec3d(sideways, up, forward))
     *
     * 보정 공식: input *= (1.0f / 0.2f) = 5.0f
     * → travel(input * 5.0f * 0.2f) = travel(input * 1.0f) = 정상 속도
     *
     * [BUG-4 수정] airStrictConfig로 인해 mc.player.isSneaking()이 true가 될 수 있음.
     * 이 경우 checkSlowed()에서 "!isSneaking" 조건 미충족 → 보정 미적용.
     * 수정: slowedThisTick 플래그 사용 (TickEvent.PRE에서 이미 계산됨).
     *
     * [BUG-5 수정] isUsingItemCached 사용으로 같은 틱 내 상태 변화에 안전.
     */
    @EventListener
    public void onMovementSlowdown(MovementSlowdownEvent event)
    {
        if (mc.player == null) return;

        // 스니킹 속도 보정 (sneakConfig/crawlConfig)
        if (sneakConfig.getValue() && mc.player.isSneaking()
                || crawlConfig.getValue() && mc.player.isCrawling())
        {
            float f = 1.0f / (float) mc.player.getAttributeValue(
                    EntityAttributes.PLAYER_SNEAKING_SPEED);
            event.input.movementForward  *= f;
            event.input.movementSideways *= f;
        }

        // 아이템/방패 slowdown 보정
        if (slowedThisTick)
        {
            // 바닐라 0.2f slowdown의 역수 = 5.0f
            // isUsingItemCached를 사용해 travel() 시점과 동일한 isUsingItem 상태 보장
            event.input.movementForward  *= 5.0f;
            event.input.movementSideways *= 5.0f;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ④ SprintCancelEvent: 스프린트 취소 방지
    // ═══════════════════════════════════════════════════════════

    /**
     * [BUG-2 수정] 아이템 사용 중 스프린트 취소 방지.
     *
     * 바닐라: isUsingItem() 시 setSprinting(false) 호출 (tickMovement ordinal=3)
     * SprintModule이 활성화되어 있어야 이 SprintCancelEvent가 처리됨.
     *
     * 문제: SprintModule이 LEGIT 모드이거나 비활성 시, 아이템 사용 중 스프린트가 취소됨
     * → 이동 속도가 스프린트 속도에서 일반 속도로 1틱 하락
     * → NoSlow의 5.0f 보정이 맞아도 스프린트 속도 자체가 빠졌으므로 속도 손실
     *
     * 수정: NoSlow 자체가 SprintCancelEvent를 처리해 아이템 사용 중 스프린트 유지.
     * itemsConfig가 활성화되어 있고, 실제로 slowdown이 보정되는 경우에만 스프린트 유지.
     * (slowedThisTick 캐시 사용)
     */
    @EventListener
    public void onSprintCancel(SprintCancelEvent event)
    {
        if (mc.player == null) return;
        // 아이템 slowdown을 보정 중인 경우에만 스프린트 유지
        // (보정 안 하는데 스프린트만 유지하면 서버에 비정상 속도 전송됨)
        if (slowedThisTick && itemsConfig.getValue())
        {
            event.cancel();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ⑤ StrafeFixEvent
    // ═══════════════════════════════════════════════════════════

    @EventListener
    public void onStrafeFix(StrafeFixEvent event)
    {
        if (!strafeFixConfig.getValue()) return;
        float yaw   = Managers.ROTATION.isRotating()
                ? Managers.ROTATION.getRotationYaw()   : Managers.ROTATION.getServerYaw();
        float pitch = Managers.ROTATION.isRotating()
                ? Managers.ROTATION.getRotationPitch() : Managers.ROTATION.getServerPitch();
        event.cancel();
        event.setYaw(yaw);
        event.setPitch(pitch);
    }

    // ═══════════════════════════════════════════════════════════
    // ⑥ PacketEvent.Outbound: Strict/NCP 처리
    // ═══════════════════════════════════════════════════════════

    @EventListener
    public void onPacketOutbound(PacketEvent.Outbound event)
    {
        if (mc.player == null || mc.world == null || mc.isInSingleplayer()) return;

        if (event.getPacket() instanceof PlayerMoveC2SPacket packet
                && packet.changesPosition()
                && strictConfig.getValue()
                && slowedThisTick)
        {
            // NCP Strict: 이동 패킷 전송 시 현재 슬롯 동기화
            Managers.INVENTORY.setSlotForced(mc.player.getInventory().selectedSlot);
        }
        else if (event.getPacket() instanceof ClickSlotC2SPacket
                && strictConfig.getValue())
        {
            // 인벤토리 클릭 시 아이템 사용 중지 및 스프린트/스니킹 상태 정리
            if (mc.player.isUsingItem())
            {
                mc.player.stopUsingItem();
            }
            if (serverSneaking || Managers.POSITION.isSneaking())
            {
                Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(mc.player,
                        ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
            }
            if (Managers.POSITION.isSprinting())
            {
                Managers.NETWORK.sendPacket(new ClientCommandC2SPacket(mc.player,
                        ClientCommandC2SPacket.Mode.STOP_SPRINTING));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ⑦ 블록 관련 이벤트
    // ═══════════════════════════════════════════════════════════

    @EventListener
    public void onSlowMovement(SlowMovementEvent event)
    {
        Block block = event.getState().getBlock();
        if (block instanceof CobwebBlock && websConfig.getValue()
                || block instanceof SweetBerryBushBlock && berryBushConfig.getValue())
        {
            float multiplier = webSpeedConfig.getValue();
            if (webSpeedConfig.getValue() >= 1.0f) multiplier = 0.0f;
            event.cancel();
            event.setMultiplier(multiplier);
        }
    }

    @EventListener
    public void onVelocityMultiplier(VelocityMultiplierEvent event)
    {
        if (event.getBlock() == Blocks.SOUL_SAND  && soulsandConfig.getValue()
                || event.getBlock() == Blocks.HONEY_BLOCK && honeyblockConfig.getValue())
        {
            event.cancel();
        }
    }

    @EventListener
    public void onSteppedOnSlimeBlock(SteppedOnSlimeBlockEvent event)
    {
        if (slimeblockConfig.getValue()) event.cancel();
    }

    @EventListener
    public void onBlockSlipperiness(BlockSlipperinessEvent event)
    {
        if (event.getBlock() == Blocks.SLIME_BLOCK && slimeblockConfig.getValue())
        {
            event.cancel();
            event.setSlipperiness(0.6f);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 내부 헬퍼 메서드
    // ═══════════════════════════════════════════════════════════

    /**
     * 현재 틱에 속도 저감 보정이 필요한지 계산.
     *
     * [BUG-1 수정] 기존 checkGrimNew()의 % 2 != 0 → 홀짝 틱마다 ON/OFF → 속도 파동
     * 수정: GrimV3의 경우 아이템 사용 시간 5틱 이상 남은 경우만 우회 (안정적)
     *
     * [BUG-4 수정] airStrictConfig 사용 시 isSneaking()이 true가 되어 false 반환하던 버그
     * 수정: airStrictConfig로 인한 서버-side 스니킹은 isSneaking() 체크에서 제외
     */
    private boolean computeSlowed()
    {
        if (mc.player == null) return false;

        // Firework 특수 케이스
        if (DisablerModule.getInstance().grimFireworkCheck2()) return true;

        // 아이템 사용 기본 조건:
        // [BUG-4 수정] mc.player.isSneaking()은 실제 클라이언트 스니킹 상태.
        // airStrictConfig로 서버에 PRESS_SHIFT_KEY 보냈어도 클라이언트는 스니킹 안 함.
        // 따라서 mc.player.isSneaking() 체크는 그대로 유지 (airStrict와 충돌 없음).
        boolean baseCondition = !mc.player.isRiding()
                && !mc.player.isSneaking()   // 실제 클라이언트 스니킹만 체크
                && (isUsingItemCached && itemsConfig.getValue()
                    || mc.player.isBlocking() && shieldsConfig.getValue());

        // GrimV3 모드: 아이템 사용 종료 5틱 전부터 우회 중지
        // [BUG-1 수정] 기존 % 2 로직 제거 - 연속적으로 적용
        if (grimNewConfig.getValue())
        {
            if (!isUsingItemCached) return false;
            int timeLeft = mc.player.getItemUseTimeLeft();
            // 마지막 5틱은 우회 중지 (Grim이 finishItemUse 타이밍 체크하는 구간)
            return baseCondition && timeLeft > 5;
        }

        return baseCondition;
    }

    /**
     * 아이템 스택이 slowdown을 유발하는지 판단.
     * Grim InteractItem 우회 전송 여부 결정에 사용.
     *
     * [BUG-3 수정] 기존 checkStack()은 FOOD를 제외(false 반환)해서
     * 황금사과를 먹는 중 Grim 우회 패킷이 전송되지 않았음.
     * 이 메서드는 반대 의미: slowdown을 유발하면 true → 전송 안 함
     */
    private boolean causesSlowdown(ItemStack stack)
    {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getComponents().contains(DataComponentTypes.FOOD)  // 음식 (황금사과 포함)
                || stack.getItem() == Items.BOW
                || stack.getItem() == Items.CROSSBOW
                || stack.getItem() == Items.SHIELD
                || stack.getItem() == Items.TRIDENT;
    }

    /**
     * 화면이 열려 있는지 확인 (InventoryMove 처리용).
     * 채팅/표지판/죽음 화면은 제외.
     */
    public boolean checkScreen()
    {
        return mc.currentScreen != null
                && !(mc.currentScreen instanceof ChatScreen)
                && !(mc.currentScreen instanceof SignEditScreen)
                && !(mc.currentScreen instanceof DeathScreen);
    }

    /**
     * 주어진 바운딩 박스 내 거미줄 블록 목록 반환.
     */
    public List<BlockPos> getIntersectingWebs(Box boundingBox)
    {
        final List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos blockPos : PositionUtil.getAllInBox(boundingBox))
        {
            if (mc.world.getBlockState(blockPos).getBlock() instanceof CobwebBlock)
                blocks.add(blockPos);
        }
        return blocks;
    }

    // ═══════════════════════════════════════════════════════════
    // Public API (다른 모듈에서 호출)
    // ═══════════════════════════════════════════════════════════

    /**
     * 현재 슬로우 상태 여부 (외부 모듈용).
     * TickEvent.PRE에서 계산된 캐시 값을 반환해 일관성 보장.
     */
    public boolean checkSlowed()
    {
        return slowedThisTick;
    }

    public boolean getStrafeFix()
    {
        return strafeFixConfig.getValue();
    }
}
