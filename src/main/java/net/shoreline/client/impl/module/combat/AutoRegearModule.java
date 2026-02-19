package net.shoreline.client.impl.module.combat;

import com.google.gson.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;
import net.shoreline.client.Shoreline;
import net.shoreline.client.api.config.Config;
import net.shoreline.client.api.config.setting.NumberConfig;
import net.shoreline.client.api.module.ModuleCategory;
import net.shoreline.client.api.module.ToggleModule;
import net.shoreline.client.impl.event.TickEvent;
import net.shoreline.client.init.Managers;
import net.shoreline.client.util.math.timer.CacheTimer;
import net.shoreline.client.util.math.timer.Timer;
import net.shoreline.eventbus.annotation.EventListener;
import net.shoreline.eventbus.event.StageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoRegear 모듈 - 수정 버전
 *
 * <h2>기존 구현의 문제점 (렉 원인)</h2>
 *
 * <p><b>★ 핵심 문제 - 무한 스핀 루프</b></p>
 * <pre>{@code
 * // 기존 코드
 * while (!validSlots.isEmpty() && playerStack.getCount() < max) {
 *     if (clickTimer.passed(...)) {   // 타이머 미경과 시 아무것도 안 함
 *         // ... 클릭 처리
 *     }
 *     // ← 여기서 break 없음! 타이머 안 지나도 계속 루프
 *     playerStack = mc.player.getInventory().getStack(slotId); // 값 안 변함
 * }
 * // 결과: 틱당 수백~수천 번 반복 → CPU 100% → TPS 하락 → 렉
 * }</pre>
 *
 * <p><b>추가 문제들</b></p>
 * <ul>
 *   <li>GenericContainer / ShulkerBox 처리 로직 100% 중복</li>
 *   <li>FileWriter를 닫지 않아 파일 핸들 누수</li>
 *   <li>IOException catch 블록이 완전히 비어있음 (디버깅 불가)</li>
 * </ul>
 *
 * <h2>수정 사항</h2>
 * <ul>
 *   <li>타이머 미경과 시 {@code return} → 다음 틱에 재시도, 이번 틱 CPU 점유 없음</li>
 *   <li>공통 처리 로직을 {@link #processContainerSlots(List, int)} 메서드로 통합</li>
 *   <li>{@code Files.writeString()} 사용으로 FileWriter 누수 원천 차단</li>
 *   <li>Logger로 에러 적절히 처리</li>
 * </ul>
 */
public class AutoRegearModule extends ToggleModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoRegear");

    // ───────────────────── Singleton ─────────────────────
    private static AutoRegearModule INSTANCE;

    public static AutoRegearModule getInstance() {
        return INSTANCE;
    }

    // ───────────────────── Configs ─────────────────────
    Config<Float> delayConfig = register(new NumberConfig<>(
            "Delay", "아이템 스왑 딜레이 (초)", 0.0f, 0.15f, 2.0f));

    // ───────────────────── 상태 ─────────────────────
    private final Timer clickTimer = new CacheTimer();

    /**
     * slotId → Item 매핑.
     * 핫바(0~8) 저장 시 키는 36~44로 변환됨 (핸들러 슬롯 인덱스 기준).
     * 메인 인벤토리(9~35)는 키 그대로 저장.
     */
    private final Map<Integer, Item> regearInventory = new ConcurrentHashMap<>();

    public AutoRegearModule() {
        super("AutoRegear", "인벤토리를 자동으로 장비로 채웁니다", ModuleCategory.COMBAT);
        INSTANCE = this;
    }

    // ───────────────────── 라이프사이클 ─────────────────────

    @Override
    public void onEnable() {
        if (regearInventory.isEmpty()) {
            sendModuleError("Regear 설정이 없습니다! .regear 명령어로 인벤토리를 저장하세요.");
        }
    }

    // ───────────────────── 틱 처리 ─────────────────────

    @EventListener
    public void onTick(TickEvent event) {
        if (event.getStage() != StageEvent.EventStage.PRE) {
            return;
        }
        if (mc.player == null || regearInventory.isEmpty()) {
            return;
        }

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
            int containerSize = handler.getInventory().size();
            // 컨테이너 슬롯만 추출 (0 ~ containerSize-1)
            processContainerSlots(handler.slots.subList(0, containerSize), containerSize);

        } else if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler) {
            // 셜커박스는 27슬롯 고정
            processContainerSlots(handler.slots.subList(0, 27), 27);
        }
    }

    /**
     * 공통 컨테이너 처리 로직.
     *
     * <p>regearInventory를 순회하며 각 슬롯을 채운다.
     * 타이머 미경과 시 즉시 return하여 틱 점유를 방지한다.</p>
     *
     * @param containerSlots 컨테이너 슬롯 리스트 (플레이어 인벤토리 제외)
     * @param containerSize  컨테이너 슬롯 수 (플레이어 슬롯 오프셋 계산용)
     */
    private void processContainerSlots(List<Slot> containerSlots, int containerSize) {
        for (Map.Entry<Integer, Item> entry : regearInventory.entrySet()) {
            Item targetItem = entry.getValue();
            if (targetItem == Items.AIR) {
                continue;
            }

            // regearInventory 키 → 핸들러 슬롯 인덱스 변환
            // 핸들러 레이아웃: [0..containerSize-1]=컨테이너, [containerSize..]=플레이어 인벤토리
            int regearKey = entry.getKey();
            int handlerSlotId = containerSize + regearKey - 9;

            // 유효 범위 검증
            if (handlerSlotId < containerSize || handlerSlotId >= containerSize + 36) {
                continue;
            }

            // 플레이어 인벤토리 슬롯 인덱스 (0-based)
            // regearKey: 핫바=36~44 → inventorySlot=0~8 / 메인=9~35 → inventorySlot=9~35
            int inventorySlot = (regearKey >= 36) ? (regearKey - 36) : regearKey;
            ItemStack playerStack = mc.player.getInventory().getStack(inventorySlot);

            // 이미 가득 참 → 스킵
            if (playerStack.getCount() >= playerStack.getMaxCount()) {
                continue;
            }

            // 컨테이너에서 targetItem 슬롯 수집
            List<Integer> validSlotIndices = new ArrayList<>();
            for (int i = 0; i < containerSlots.size(); i++) {
                Slot slot = containerSlots.get(i);
                if (slot.hasStack() && slot.getStack().getItem() == targetItem) {
                    validSlotIndices.add(i);
                }
            }

            if (validSlotIndices.isEmpty()) {
                continue;
            }

            // ★ 핵심 수정: 타이머 미경과 시 즉시 return (기존: while 루프 스핀)
            // 이 틱에서 더 이상 처리하지 않고 다음 틱에 재시도
            for (int slotIdx : validSlotIndices) {
                // 매 클릭마다 스택 상태 재조회
                playerStack = mc.player.getInventory().getStack(inventorySlot);
                if (playerStack.getCount() >= playerStack.getMaxCount()) {
                    break; // 이 슬롯 완료
                }

                if (!clickTimer.passed(delayConfig.getValue() * 1000.0f)) {
                    // 타이머 미경과 → 이번 틱 처리 종료
                    return;
                }

                // 컨테이너 슬롯 픽업 → 플레이어 슬롯에 내려놓기
                Managers.INVENTORY.pickupSlot(slotIdx);
                Managers.INVENTORY.pickupSlot(handlerSlotId);
                clickTimer.reset();
            }
        }
    }

    // ───────────────────── 인벤토리 관리 ─────────────────────

    public void clearPlayerInventory() {
        regearInventory.clear();
    }

    /**
     * 현재 플레이어 인벤토리를 regear 설정으로 저장한다.
     *
     * <p>슬롯 키 변환:</p>
     * <ul>
     *   <li>핫바 슬롯 0~8 → 키 36~44 (핸들러 기준 핫바 위치)</li>
     *   <li>메인 슬롯 9~35 → 키 9~35 (동일)</li>
     * </ul>
     */
    public void savePlayerInventory() {
        regearInventory.clear();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            int key = (i < 9) ? (i + 36) : i;
            regearInventory.put(key, stack.getItem());
        }
    }

    // ───────────────────── 파일 I/O ─────────────────────

    /**
     * Regear 설정을 JSON 파일로 저장한다.
     *
     * <p>수정: Files.writeString() 사용 → FileWriter 자동 관리, 누수 없음</p>
     */
    public void saveRegearFile() {
        if (regearInventory.isEmpty()) {
            return;
        }
        Path regearFile = Shoreline.CONFIG.getClientDirectory().resolve("regear.json");
        try {
            JsonArray jsonArray = new JsonArray();
            for (Map.Entry<Integer, Item> entry : regearInventory.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("slotId", entry.getKey());
                obj.addProperty("item", Registries.ITEM.getId(entry.getValue()).toString());
                jsonArray.add(obj);
            }
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(jsonArray);
            // Files.writeString: 파일 없으면 생성, 있으면 덮어씀, 자동으로 닫힘
            Files.writeString(regearFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Regear 파일 저장 실패", e);
            sendModuleError("Regear 파일 저장에 실패했습니다.");
        }
    }

    /**
     * 저장된 Regear JSON 파일을 로드한다.
     *
     * <p>수정: Files.readString() 사용, 예외 시 Logger로 처리</p>
     */
    public void loadRegearFile() {
        Path regearFile = Shoreline.CONFIG.getClientDirectory().resolve("regear.json");
        if (!Files.exists(regearFile)) {
            return;
        }
        try {
            regearInventory.clear();
            String content = Files.readString(regearFile, StandardCharsets.UTF_8);
            JsonArray jsonArray = JsonParser.parseString(content).getAsJsonArray();
            for (JsonElement element : jsonArray) {
                JsonObject obj = element.getAsJsonObject();
                int slotId = obj.get("slotId").getAsInt();
                Item item = Registries.ITEM.get(Identifier.of(obj.get("item").getAsString()));
                // AIR = 유효하지 않은 아이디, 저장 불필요
                if (item != Items.AIR) {
                    regearInventory.put(slotId, item);
                }
            }
            LOGGER.info("Regear 로드 완료: {}개 슬롯", regearInventory.size());
        } catch (IOException | JsonParseException e) {
            LOGGER.error("Regear 파일 로드 실패", e);
            sendModuleError("Regear 파일이 손상되었습니다.");
        }
    }
}
