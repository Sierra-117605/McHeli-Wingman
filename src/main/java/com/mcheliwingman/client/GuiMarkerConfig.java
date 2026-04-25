package com.mcheliwingman.client;

import com.mcheliwingman.block.MarkerType;
import com.mcheliwingman.network.PacketMarkerUpdate;
import com.mcheliwingman.network.WingmanNetwork;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * WingmanMarkerBlock 右クリックで開くコンフィグ画面。
 *
 * レイアウト:
 *   タイトル
 *   ── 種別ボタン（BASE / Runway-A / Runway-B / Parking / Waypoint）
 *   ID フィールド
 *   ベース フィールド（BASE 種別のときは無効）
 *   [Save] [Cancel]
 */
public class GuiMarkerConfig extends GuiScreen {

    private static final int BTN_BASE      = 0;
    private static final int BTN_RUNWAY_A  = 1;
    private static final int BTN_RUNWAY_B  = 2;
    private static final int BTN_PARKING   = 3;
    private static final int BTN_WAYPOINT  = 4;
    private static final int BTN_SAVE      = 10;
    private static final int BTN_CANCEL    = 11;

    private static final MarkerType[] TYPE_ORDER = {
        MarkerType.BASE, MarkerType.RUNWAY_A, MarkerType.RUNWAY_B,
        MarkerType.PARKING, MarkerType.WAYPOINT
    };

    private final BlockPos pos;
    private MarkerType selectedType;

    private GuiTextField idField;
    private GuiTextField baseIdField;

    public GuiMarkerConfig(BlockPos pos, MarkerType type, String id, String baseId) {
        this.pos          = pos;
        this.selectedType = type;
        // フィールドは initGui で生成するため一時保存
        this._initId     = id;
        this._initBaseId = baseId;
    }

    // initGui 前の初期値を保持するバッファ
    private final String _initId;
    private final String _initBaseId;

    // ─── 初期化 ──────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();

        int cx   = width / 2;
        int top  = height / 4;

        // 種別ボタン（5種 × 幅80）
        int btnW = 78;
        int totalW = btnW * TYPE_ORDER.length + 4 * (TYPE_ORDER.length - 1);
        int startX = cx - totalW / 2;
        for (int i = 0; i < TYPE_ORDER.length; i++) {
            buttonList.add(new GuiButton(i, startX + i * (btnW + 4), top, btnW, 20,
                TYPE_ORDER[i].shortName()));
        }

        // ID フィールド
        idField = new GuiTextField(0, fontRenderer, cx - 100, top + 36, 200, 20);
        idField.setMaxStringLength(64);
        idField.setText(_initId);
        idField.setFocused(true);

        // ベース フィールド
        baseIdField = new GuiTextField(1, fontRenderer, cx - 100, top + 72, 200, 20);
        baseIdField.setMaxStringLength(64);
        baseIdField.setText(_initBaseId);

        // Save / Cancel
        buttonList.add(new GuiButton(BTN_SAVE,   cx - 54, top + 108, 50, 20, "Save"));
        buttonList.add(new GuiButton(BTN_CANCEL,  cx + 4,  top + 108, 50, 20, "Cancel"));

        refreshButtonState();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    // ─── 更新 ─────────────────────────────────────────────────────────────────

    @Override
    public void updateScreen() {
        idField.updateCursorCounter();
        baseIdField.updateCursorCounter();
    }

    // ─── 描画 ─────────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx  = width / 2;
        int top = height / 4;

        // タイトル
        drawCenteredString(fontRenderer, "Wingman Marker Config", cx, top - 22, 0xFFFFFF);

        // 現在選択されている種別をハイライト（ボタン上に下線）
        for (int i = 0; i < TYPE_ORDER.length; i++) {
            if (TYPE_ORDER[i] == selectedType) {
                GuiButton btn = buttonList.get(i);
                drawRect(btn.x, btn.y + btn.height, btn.x + btn.width, btn.y + btn.height + 2, 0xFFFFAA00);
            }
        }

        // フィールドラベル（タイプに応じて切り替え）
        if (selectedType == MarkerType.BASE) {
            drawString(fontRenderer, "Base Name:", cx - 100, top + 28, 0xAAAAAA);
            // Parent Base 欄は非表示
        } else {
            drawString(fontRenderer, "Marker ID:", cx - 100, top + 28, 0xAAAAAA);
            drawString(fontRenderer, "Parent Base:", cx - 100, top + 64, 0xAAAAAA);
        }

        idField.drawTextBox();
        if (selectedType != MarkerType.BASE) baseIdField.drawTextBox();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    // ─── 入力 ─────────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case BTN_BASE:
            case BTN_RUNWAY_A:
            case BTN_RUNWAY_B:
            case BTN_PARKING:
            case BTN_WAYPOINT:
                selectedType = TYPE_ORDER[button.id];
                refreshButtonState();
                break;
            case BTN_SAVE:
                save();
                mc.player.closeScreen();
                break;
            case BTN_CANCEL:
                mc.player.closeScreen();
                break;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.player.closeScreen();
            return;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            // ID ↔ Base フォーカス切り替え
            if (idField.isFocused()) {
                idField.setFocused(false);
                baseIdField.setFocused(true);
            } else {
                baseIdField.setFocused(false);
                idField.setFocused(true);
            }
            return;
        }
        if (idField.isFocused())     idField.textboxKeyTyped(typedChar, keyCode);
        else if (baseIdField.isFocused()) baseIdField.textboxKeyTyped(typedChar, keyCode);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        idField.mouseClicked(mouseX, mouseY, mouseButton);
        baseIdField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    // ─── ヘルパー ─────────────────────────────────────────────────────────────

    /** BASE 種別のときは Parent Base フィールドを無効化・非表示扱い */
    private void refreshButtonState() {
        boolean isBase = (selectedType == MarkerType.BASE);
        baseIdField.setEnabled(!isBase);
        if (isBase) {
            baseIdField.setText("");
            // フォーカスが baseIdField にあれば idField に戻す
            if (baseIdField.isFocused()) {
                baseIdField.setFocused(false);
                idField.setFocused(true);
            }
        }
    }

    private void save() {
        String id     = idField.getText().trim();
        String baseId = selectedType == MarkerType.BASE ? "" : baseIdField.getText().trim();
        WingmanNetwork.sendToServer(new PacketMarkerUpdate(pos, selectedType, id, baseId));
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
