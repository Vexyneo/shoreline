package net.shoreline.client.impl.module.combat;

import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.BooleanConfig;
import net.shoreline.client.api.config.setting.MacroConfig;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.macro.Macro;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.api.module.ToggleModule;
import net.shoreline.client.impl.event.keyboard.KeyboardInputEvent;
import net.shoreline.client.init.Managers;
import net.shoreline.client.util.math.timer.CacheTimer;
import net.shoreline.client.util.math.timer.Timer;
import net.shoreline.eventbus.annotation.EventListener;
import org.lwjgl.glfw.GLFW;

/**
 * KeyPearl 모듈 - 인벤토리 어디에 있든 특정 키를 누르면 엔더펄을 던진다.
 *
 * <h2>동작 흐름</h2>
 * <ol>
 *   <li>키 입력 감지 (KeyboardInputEvent)</li>
 *   <li>인벤토리 전체(0~35) + 오프핸드에서 엔더펄 슬롯 탐색</li>
 *   <li>핫바에 있으면 → 해당 슬롯으로 전환 후 UseItem 패킷 전송</li>
 *   <li>메인 인벤토리에 있으면 → 빈 핫바 슬롯 찾아 서버사이드 스왑 후 UseItem</li>
 *   <li>패킷 전송 후 원래 슬롯으로 복원</li>
 * </ol>
 *
 * <h2>Anti-Cheat 고려</h2>
 * <ul>
 *   <li>슬롯 전환 → UseItem → 슬롯 복원 순서로 정상적인 패킷 시퀀스 유지</li>
 *   <li>쿨다운으로 스팸 방지 (기본 0.5초)</li>
 *   <li>화면이 열려있을 때(인벤토리 GUI 등)는 동작 안 함</li>
 * </ul>
 */
public class KeyPearlModule extends ToggleModule {

    private static KeyPearlModule INSTANCE;

    // ─── Configs ────────────────────────────────────────────────
    /** 엔더펄 던지기 키 바인드 */
    Config<Macro> keyConfig = register(new MacroConfig(
            "Key", "엔더펄을 던질 키",
            new Macro(getId() + "-keypearlkey", GLFW.GLFW_KEY_G, () -> {})));

    /** 쿨다운 (초) - 서버 엔더펄 쿨다운에 맞춤 */
    Config<Float> cooldownConfig = register(new NumberConfig<>(
            "Cooldown", "던지기 쿨다운 (초)", 0.0f, 0.5f, 2.0f));

    /** 오프핸드 우선 사용 여부 */
    Config<Boolean> preferOffhandConfig = register(new BooleanConfig(
            "PreferOffhand", "오프핸드에 엔더펄이 있으면 우선 사용", true));

    /** 현재 들고 있는 슬롯 유지 (스왑 후 복원) */
    Config<Boolean> silentConfig = register(new BooleanConfig(
            "Silent", "서버사이드 스왑만 사용 (클라이언트 슬롯 유지)", true));

    // ─── 상태 ────────────────────────────────────────────────────
    private final Timer cooldownTimer = new CacheTimer();

    public KeyPearlModule() {
        super("KeyPearl", "특정 키를 누르면 인벤토리 어디서든 엔더펄을 던집니다",
                ModuleCategory.COMBAT);
        INSTANCE = this;
    }

    public static KeyPearlModule getInstance() {
        return INSTANCE;
    }

    // ─── 이벤트 ─────────────────────────────────────────────────

    @EventListener
    public void onKey(KeyboardInputEvent event) {
        // PRESS 이벤트만 처리 (REPEAT 제외)
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        // 설정된 키가 눌렸는지 확인
        if (event.getKeycode() != keyConfig.getValue().getKeycode()) {
            return;
        }
        // GUI 화면이 열려있으면 무시 (인벤토리, 체스트 등)
        if (mc.currentScreen != null) {
            return;
        }
        if (mc.player == null || mc.world == null) {
            return;
        }
        // 쿨다운 체크
        if (!cooldownTimer.passed(cooldownConfig.getValue() * 1000f)) {
            return;
        }
        throwPearl();
    }

    // ─── 핵심 로직 ───────────────────────────────────────────────

    /**
     * 엔더펄을 찾아서 던진다.
     */
    private void throwPearl() {
        // 1. 오프핸드 우선 체크
        if (preferOffhandConfig.getValue()) {
            ItemStack offhand = mc.player.getOffHandStack();
            if (offhand.getItem() instanceof EnderPearlItem) {
                useHand(Hand.OFF_HAND);
                cooldownTimer.reset();
                return;
            }
        }

        // 2. 현재 들고 있는 슬롯 체크 (가장 빠름)
        int selectedSlot = mc.player.getInventory().selectedSlot;
        if (mc.player.getInventory().getStack(selectedSlot).getItem() instanceof EnderPearlItem) {
            useHand(Hand.MAIN_HAND);
            cooldownTimer.reset();
            return;
        }

        // 3. 핫바(0~8)에서 탐색
        int hotbarSlot = findPearlInHotbar();
        if (hotbarSlot != -1) {
            throwFromHotbar(hotbarSlot, selectedSlot);
            cooldownTimer.reset();
            return;
        }

        // 4. 메인 인벤토리(9~35)에서 탐색
        int invSlot = findPearlInInventory();
        if (invSlot != -1) {
            throwFromInventory(invSlot, selectedSlot);
            cooldownTimer.reset();
            return;
        }

        // 엔더펄 없음
        sendModuleMessage("§c인벤토리에 엔더펄이 없습니다.");
    }

    /**
     * 핫바 슬롯(0~8)에서 엔더펄 탐색.
     *
     * @return 슬롯 인덱스, 없으면 -1
     */
    private int findPearlInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof EnderPearlItem) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 메인 인벤토리(9~35)에서 엔더펄 탐색.
     *
     * @return 슬롯 인덱스, 없으면 -1
     */
    private int findPearlInInventory() {
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof EnderPearlItem) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 핫바 슬롯으로 전환하여 엔더펄을 던진다.
     *
     * @param pearlSlot   엔더펄이 있는 핫바 슬롯 (0~8)
     * @param originalSlot 원래 선택된 슬롯
     */
    private void throwFromHotbar(int pearlSlot, int originalSlot) {
        if (silentConfig.getValue()) {
            // 서버사이드 스왑: 클라이언트 UI는 그대로, 서버에만 슬롯 변경 전송
            Managers.NETWORK.sendPacket(new UpdateSelectedSlotC2SPacket(pearlSlot));
            useHand(Hand.MAIN_HAND);
            // 원래 슬롯으로 복원
            Managers.NETWORK.sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
        } else {
            // 클라이언트+서버 모두 슬롯 전환
            int prevSlot = mc.player.getInventory().selectedSlot;
            Managers.INVENTORY.setSlot(pearlSlot);
            useHand(Hand.MAIN_HAND);
            Managers.INVENTORY.setSlot(prevSlot);
        }
    }

    /**
     * 메인 인벤토리의 엔더펄을 빈 핫바 슬롯으로 서버사이드 스왑하여 던진다.
     *
     * <p>빈 핫바 슬롯이 없으면 현재 선택된 슬롯에 덮어씌운다.</p>
     *
     * @param invSlot      메인 인벤토리 슬롯 (9~35)
     * @param originalSlot 원래 선택된 슬롯
     */
    private void throwFromInventory(int invSlot, int originalSlot) {
        // 빈 핫바 슬롯 탐색, 없으면 현재 슬롯 사용
        int targetHotbar = findEmptyHotbarSlot();
        if (targetHotbar == -1) {
            targetHotbar = originalSlot;
        }

        // 인벤토리 → 핫바 픽업 스왑 (서버사이드)
        // ClickSlot으로 인벤토리 슬롯 아이템을 핫바로 이동
        // 핸들러 슬롯: 인벤토리 9~35 → 핸들러 인덱스 = invSlot (PlayerInventoryScreenHandler 기준)
        // 핫바 스왑 단축키 방식: SlotActionType.SWAP + button = 핫바 인덱스(0~8)
        Managers.INVENTORY.click(invSlot, targetHotbar, net.minecraft.screen.slot.SlotActionType.SWAP);

        // 해당 핫바 슬롯으로 전환 후 UseItem
        Managers.NETWORK.sendPacket(new UpdateSelectedSlotC2SPacket(targetHotbar));
        useHand(Hand.MAIN_HAND);

        // 다시 스왑해서 원위치
        Managers.INVENTORY.click(invSlot, targetHotbar, net.minecraft.screen.slot.SlotActionType.SWAP);

        // 원래 슬롯 복원
        Managers.NETWORK.sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
    }

    /**
     * 핫바에서 빈 슬롯을 탐색한다.
     *
     * @return 빈 슬롯 인덱스 (0~8), 없으면 -1
     */
    private int findEmptyHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 지정한 손으로 UseItem 패킷을 전송한다.
     * HandSwing 패킷도 함께 전송해 애니메이션을 재생한다.
     *
     * @param hand 사용할 손
     */
    private void useHand(Hand hand) {
        // sequenced packet: 서버가 sequence ID로 패킷 순서 검증
        Managers.NETWORK.sendSequencedPacket(id ->
                new PlayerInteractItemC2SPacket(hand, id,
                        mc.player.getYaw(), mc.player.getPitch()));
        // 팔 스윙 애니메이션
        Managers.NETWORK.sendPacket(new HandSwingC2SPacket(hand));
    }

    @Override
    public String getModuleData() {
        // 배열리스트에 남은 펄 개수 표시
        if (mc.player == null) return "";
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.ENDER_PEARL) {
                count += stack.getCount();
            }
        }
        // 오프핸드도 포함
        if (mc.player.getOffHandStack().getItem() == Items.ENDER_PEARL) {
            count += mc.player.getOffHandStack().getCount();
        }
        return count > 0 ? String.valueOf(count) : "§cEmpty";
    }
}
