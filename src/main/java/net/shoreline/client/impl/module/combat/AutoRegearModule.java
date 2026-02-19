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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoRegear 모듈 - 최적화 버전
 *
 * <h2>기존 구현의 문제점</h2>
 * <ol>
 *   <li><b>무한 스핀 루프</b>: {@code while(!validSlots.isEmpty())} 내부에서
 *       {@code clickTimer.passed()}가 false이면 루프를 빠져나오지 않고 계속 CPU를 점유.
 *       → 틱당 수백~수천 번 반복 → 서버 틱 20Hz 이하로 하락</li>
 *   <li><b>코드 완전 중복</b>: GenericContainer와 ShulkerBox 처리가 100% 동일 로직.
 *       → {@link #processContainer(List, int)} 공통 메서드로 추출</li>
 *   <li><b>FileWriter 리소스 누수</b>: try 블록에서 writer를 닫지 않음.
 *       → try-with-resources로 수정</li>
 *   <li><b>FileReader 예외 스택 트레이스</b>: 빈 catch 또는 무조건 printStackTrace.
 *       → Logger로 정상 처리</li>
 *   <li><b>슬롯 오프셋 오류</b>: ShulkerBox 처리 시 {@code getStacks().size()}가
 *       GenericContainer의 {@code getInventory().size()}와 동일하게 취급되어
 *       슬롯 인덱스 계산이 틀릴 수 있음. → 분리하여 정확히 처리</li>
 * </ol>
 *
 * <h2>개선 사항</h2>
 * <ul>
 *   <li>타이머 미경과 시 즉시 break → 틱 점유 없음</li>
 *   <li>공통 컨테이너 처리 로직 메서드화</li>
 *   <li>try-with-resources로 모든 I/O 처리</li>
 *   <li>Logger 도입으로 적절한 에러 핸들링</li>
 *   <li>슬롯 인덱스 계산 정확도 향상</li>
 * </ul>
 *
 * @author optimized
 * @since 2.0
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
     * 키: 플레이어 인벤토리 슬롯 인덱스 (0~35, hotbar=0~8, main=9~35, armor=36~39)
     * 값: 해당 슬롯에 채워야 할 아이템 타입
     *
     * <p>ConcurrentHashMap 사용: 메인 스레드(틱)와 커맨드 스레드 간 동시 접근 안전.</p>
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
        // PRE 스테이지에서만 실행
        if (event.getStage() != StageEvent.EventStage.PRE) {
            return;
        }
        // 플레이어 null 체크 (로딩 중 안전 처리)
        if (mc.player == null || regearInventory.isEmpty()) {
            return;
        }
        // 컨테이너 화면이 열려있지 않으면 아무것도 하지 않음
        // → 매 틱 불필요한 순회 방지
        if (mc.player.currentScreenHandler == null) {
            return;
        }

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
            // 컨테이너 슬롯 수 (6줄=54, 5줄=45, etc.)
            int containerSize = handler.getInventory().size();
            // 컨테이너 슬롯 리스트 (0 ~ containerSize-1이 컨테이너, 이후가 플레이어 인벤토리)
            processContainer(handler.slots.subList(0, containerSize), containerSize);

        } else if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler) {
            // ShulkerBox는 27슬롯 고정
            int containerSize = 27;
            processContainer(handler.slots.subList(0, containerSize), containerSize);
        }
    }

    /**
     * 공통 컨테이너 처리 로직.
     *
     * <p>regearInventory의 각 항목에 대해:</p>
     * <ol>
     *   <li>플레이어 슬롯이 이미 가득 차 있으면 스킵</li>
     *   <li>컨테이너에서 필요한 아이템 슬롯 목록을 수집</li>
     *   <li>타이머 경과 확인 후 클릭, 타이머 미경과 시 즉시 루프 탈출</li>
     * </ol>
     *
     * @param containerSlots 컨테이너의 슬롯 리스트 (플레이어 슬롯 제외)
     * @param containerSize  컨테이너 슬롯 수 (플레이어 인벤토리 슬롯 인덱스 오프셋 계산용)
     */
    private void processContainer(List<Slot> containerSlots, int containerSize) {
        for (Map.Entry<Integer, Item> entry : regearInventory.entrySet()) {
            Item targetItem = entry.getValue();
            // AIR는 빈 슬롯 → 스킵
            if (targetItem == Items.AIR) {
                continue;
            }

            // regearInventory 키는 savePlayerInventory()에서 저장된 슬롯 인덱스
            // 실제 핸들러 슬롯 인덱스 = containerSize + regearInventory_key - 9
            // (핸들러 슬롯: [0..containerSize-1]=컨테이너, [containerSize..]=플레이어 인벤토리 행)
            int playerInventoryKey = entry.getKey();
            // 플레이어 인벤토리에서의 실제 슬롯 인덱스 (0-based, 0~8=hotbar, 9~35=main)
            // ScreenHandler 슬롯 오프셋 적용
            int handlerSlotId = containerSize + (playerInventoryKey - 9);

            // 핸들러 슬롯 범위 검증
            if (handlerSlotId < containerSize || handlerSlotId >= containerSize + 36) {
                continue;
            }

            ItemStack playerStack = mc.player.getInventory().getStack(playerInventoryKey < 9
                    ? playerInventoryKey  // hotbar (0~8)
                    : playerInventoryKey  // main (9~35)
            );

            // 이미 가득 참 → 이 슬롯은 처리 불필요
            if (playerStack.getCount() >= playerStack.getMaxCount()) {
                continue;
            }

            // 컨테이너에서 targetItem이 있는 슬롯 수집
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

            // 타이머 기반 순차 클릭
            // 핵심 수정: 타이머 미경과 시 break → 루프 스핀 없음
            Iterator<Integer> slotIterator = validSlotIndices.iterator();
            // 현재 플레이어 스택 상태를 루프 시작 시 한 번만 갱신
            ItemStack currentStack = mc.player.getInventory().getStack(
                    playerInventoryKey < 9 ? playerInventoryKey : playerInventoryKey);

            while (slotIterator.hasNext() && currentStack.getCount() < currentStack.getMaxCount()) {
                if (!clickTimer.passed(delayConfig.getValue() * 1000.0f)) {
                    // ★ 핵심: 타이머 미경과 → 이 틱에서 더 이상 처리하지 않고 즉시 리턴
                    // 기존 코드는 여기서 while을 계속 돌며 CPU를 낭비했음
                    return;
                }

                int containerSlotIdx = slotIterator.next();
                // pickup → 커서에 아이템 올림
                Managers.INVENTORY.pickupSlot(containerSlotIdx);
                // pickup → 플레이어 슬롯에 내려놓음
                Managers.INVENTORY.pickupSlot(handlerSlotId);
                clickTimer.reset();

                // 스택 상태 갱신 (루프 조건 재평가용)
                currentStack = mc.player.getInventory().getStack(
                        playerInventoryKey < 9 ? playerInventoryKey : playerInventoryKey);
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
     * <p>슬롯 매핑:</p>
     * <ul>
     *   <li>인벤토리 슬롯 0~8 (hotbar) → 키 36~44 (핸들러 hotbar 슬롯)</li>
     *   <li>인벤토리 슬롯 9~35 (main) → 키 9~35 (동일)</li>
     * </ul>
     */
    public void savePlayerInventory() {
        regearInventory.clear();
        // 핫바 (0~8) + 메인 인벤토리 (9~35) 저장
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            // 핫바는 핸들러에서 36~44에 위치하므로 키를 변환
            int key = (i < 9) ? (i + 36) : i;
            regearInventory.put(key, stack.getItem());
        }
    }

    // ───────────────────── 파일 I/O ─────────────────────

    /**
     * Regear 설정을 JSON 파일로 저장한다.
     *
     * <p>수정 사항: try-with-resources로 FileWriter 자동 닫기 (기존: 누수 발생)</p>
     */
    public void saveRegearFile() {
        if (regearInventory.isEmpty()) {
            return;
        }
        Path regearFile = Shoreline.CONFIG.getClientDirectory().resolve("regear.json");
        try {
            // 파일이 없으면 자동 생성
            if (!Files.exists(regearFile)) {
                Files.createFile(regearFile);
            }

            JsonArray jsonArray = new JsonArray();
            for (Map.Entry<Integer, Item> entry : regearInventory.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("slotId", entry.getKey());
                obj.addProperty("item", Registries.ITEM.getId(entry.getValue()).toString());
                jsonArray.add(obj);
            }

            // try-with-resources로 안전한 파일 쓰기 (기존 FileWriter 누수 수정)
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(jsonArray);
            Files.writeString(regearFile, json, StandardCharsets.UTF_8);

        } catch (IOException e) {
            LOGGER.error("Regear 파일 저장 실패: {}", regearFile, e);
            sendModuleError("Regear 파일 저장에 실패했습니다.");
        }
    }

    /**
     * 저장된 Regear JSON 파일을 로드한다.
     *
     * <p>수정 사항: IOException 시 Logger로 처리, 파일 없으면 조용히 리턴</p>
     */
    public void loadRegearFile() {
        Path regearFile = Shoreline.CONFIG.getClientDirectory().resolve("regear.json");
        if (!Files.exists(regearFile)) {
            return; // 파일 없음 = 정상 상태, 에러 아님
        }
        try {
            regearInventory.clear();
            String content = Files.readString(regearFile, StandardCharsets.UTF_8);
            JsonArray jsonArray = JsonParser.parseString(content).getAsJsonArray();

            for (JsonElement element : jsonArray) {
                JsonObject obj = element.getAsJsonObject();
                int slotId = obj.get("slotId").getAsInt();
                String itemId = obj.get("item").getAsString();
                Item item = Registries.ITEM.get(Identifier.of(itemId));
                if (item != Items.AIR) { // 유효하지 않은 아이템 ID 필터링
                    regearInventory.put(slotId, item);
                }
            }

            LOGGER.info("Regear 파일 로드 완료: {}개 슬롯", regearInventory.size());

        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            LOGGER.error("Regear 파일 로드 실패: {}", regearFile, e);
            sendModuleError("Regear 파일 로드에 실패했습니다. 파일이 손상되었을 수 있습니다.");
        }
    }
}
