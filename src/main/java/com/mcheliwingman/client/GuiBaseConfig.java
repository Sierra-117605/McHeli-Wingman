package com.mcheliwingman.client;

import com.mcheliwingman.network.PacketBaseAction;
import com.mcheliwingman.network.PacketOpenBaseGui;
import com.mcheliwingman.network.WingmanNetwork;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * BASE マーカーブロックを右クリックしたときに開く基地コンフィグGUI。
 *
 * タブ 0: Taxi Routes — タキシールートの追加・編集・削除
 * タブ 1: Mission    — ミッション策定・機体へのオーダー発令
 */
public class GuiBaseConfig extends GuiScreen {

    // ─── IDs ─────────────────────────────────────────────────────────────────

    // タブ
    private static final int BTN_TAB_ROUTES  = 200;
    private static final int BTN_TAB_MISSION = 201;

    // Routes タブ
    private static final int BTN_ROUTE_BASE  = 10;  // 10..10+MAX_ROUTES-1
    private static final int MAX_ROUTES      = 8;
    private static final int BTN_ROUTE_NEW   = 50;
    private static final int BTN_ROUTE_SAVE  = 51;
    private static final int BTN_ROUTE_DEL   = 52;
    private static final int BTN_CLOSE       = 99;

    // Mission タブ — ミッション種別
    private static final int BTN_M_CAP    = 100;
    private static final int BTN_M_CAS    = 101;
    private static final int BTN_M_STRIKE = 102;
    private static final int BTN_M_ESCORT = 103;
    private static final int BTN_M_RECON  = 104;
    private static final int BTN_M_FERRY  = 105;

    // Mission タブ — 武器
    private static final int BTN_W_GUN    = 110;
    private static final int BTN_W_AA     = 111;
    private static final int BTN_W_AS     = 112;
    private static final int BTN_W_CAS    = 113;
    private static final int BTN_W_ROCKET = 114;
    private static final int BTN_W_BOMB   = 115;

    private static final int BTN_DISPATCH = 120;

    // ─── State ───────────────────────────────────────────────────────────────

    private final BlockPos blockPos;
    private final String   baseId;
    private final PacketOpenBaseGui pkt;

    private int currentTab = 0;

    // Routes タブ
    private final List<PacketOpenBaseGui.RouteDto> routes;
    private int selectedRouteIdx = -1;

    private GuiTextField fRouteId;
    private GuiTextField fParkingId;
    private GuiTextField fRunwayId;
    private GuiTextField fWaypoints;

    // Mission タブ — 状態
    private final Set<String> selectedMissionTypes = new LinkedHashSet<>();
    private final Set<String> selectedWeapons      = new LinkedHashSet<>();

    private GuiTextField fTargetX;
    private GuiTextField fTargetZ;
    private GuiTextField fOrbitRadius;
    private GuiTextField fCruiseAlt;
    private GuiTextField fStrikePasses;
    private GuiTextField fTimeLimit;
    private GuiTextField fUuid;

    private GuiTextField focusedField;

    // ─── Constructor ─────────────────────────────────────────────────────────

    public GuiBaseConfig(PacketOpenBaseGui pkt) {
        this.pkt      = pkt;
        this.blockPos = new BlockPos(pkt.bx, pkt.by, pkt.bz);
        this.baseId   = pkt.baseId;
        this.routes   = new ArrayList<>(pkt.routes);

        // ミッションデフォルト武器
        selectedWeapons.add("gun");
    }

    // ─── Init ────────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();

        // タブボタン
        buttonList.add(new GuiButton(BTN_TAB_ROUTES,  width / 2 - 82, height / 4 - 12, 80, 18, "Taxi Routes"));
        buttonList.add(new GuiButton(BTN_TAB_MISSION, width / 2 + 2,  height / 4 - 12, 80, 18, "Mission"));

        if (currentTab == 0) {
            initRoutesTab();
        } else {
            initMissionTab();
        }

        buttonList.add(new GuiButton(BTN_CLOSE, width / 2 - 25, height / 4 + 200, 50, 16, "Close"));
    }

    // ─── Routes タブ初期化 ────────────────────────────────────────────────────

    private void initRoutesTab() {
        int lx = width / 2 - 190;
        int ty = height / 4 + 12;

        // ルートリストボタン（最大 MAX_ROUTES 件）
        for (int i = 0; i < routes.size() && i < MAX_ROUTES; i++) {
            String label = routes.get(i).routeId;
            buttonList.add(new GuiButton(BTN_ROUTE_BASE + i, lx, ty + i * 22, 110, 20, label));
        }

        buttonList.add(new GuiButton(BTN_ROUTE_NEW,  lx,      ty + MAX_ROUTES * 22 + 4, 52, 18, "New"));
        buttonList.add(new GuiButton(BTN_ROUTE_SAVE, lx + 58, ty + MAX_ROUTES * 22 + 4, 52, 18, "Save"));

        int rx = width / 2 - 70;
        // 編集フォーム
        fRouteId   = tf(0,  rx, ty,        180, "Route ID");
        fParkingId = tf(1,  rx, ty + 30,   180, "Parking ID");
        fRunwayId  = tf(2,  rx, ty + 60,   180, "Runway A ID");
        fWaypoints = tf(3,  rx, ty + 90,   180, "WPs (csv): e.g. wp_1,wp_2");
        fWaypoints.setMaxStringLength(256);

        // 選択中ルートがあればフォームに反映
        if (selectedRouteIdx >= 0 && selectedRouteIdx < routes.size()) {
            PacketOpenBaseGui.RouteDto r = routes.get(selectedRouteIdx);
            fRouteId.setText(r.routeId);
            fParkingId.setText(r.parkingId);
            fRunwayId.setText(r.runwayId);
            fWaypoints.setText(String.join(",", r.waypointIds));
        }

        buttonList.add(new GuiButton(BTN_ROUTE_DEL, rx + 50, ty + 120, 80, 18, "Delete"));

        focusedField = fRouteId;
        fRouteId.setFocused(true);
    }

    // ─── Mission タブ初期化 ───────────────────────────────────────────────────

    private void initMissionTab() {
        int cx = width / 2;
        int ty = height / 4 + 12;

        // ミッション種別ボタン
        String[] mtypes = {"CAP","CAS","STRIKE","ESCORT","RECON","FERRY"};
        int[] mids      = {BTN_M_CAP, BTN_M_CAS, BTN_M_STRIKE, BTN_M_ESCORT, BTN_M_RECON, BTN_M_FERRY};
        int btnW = 58;
        int startX = cx - (btnW * 3 + 6);
        for (int i = 0; i < mtypes.length; i++) {
            buttonList.add(new GuiButton(mids[i], startX + (i % 3) * (btnW + 4),
                ty + (i / 3) * 22, btnW, 18, mtypes[i]));
        }

        // 武器チェックボタン
        String[] wnames = {"GUN","AA Msl","AS Msl","CAS","Rocket","Bomb"};
        int[] wids      = {BTN_W_GUN, BTN_W_AA, BTN_W_AS, BTN_W_CAS, BTN_W_ROCKET, BTN_W_BOMB};
        int wbtnW = 54;
        int wstartX = cx - (wbtnW * 3 + 6);
        for (int i = 0; i < wnames.length; i++) {
            buttonList.add(new GuiButton(wids[i], wstartX + (i % 3) * (wbtnW + 4),
                ty + 48 + (i / 3) * 22, wbtnW, 18, wnames[i]));
        }

        int fy = ty + 96;
        int fw = 80;
        int fhalf = cx - 90;

        fTargetX     = tf(10, fhalf,      fy,       fw, "0");
        fTargetZ     = tf(11, fhalf + 90, fy,       fw, "0");
        fOrbitRadius = tf(12, fhalf,      fy + 28,  fw, "300");
        fCruiseAlt   = tf(13, fhalf + 90, fy + 28,  fw, "80");
        fStrikePasses= tf(14, fhalf,      fy + 56,  fw, "2");
        fTimeLimit   = tf(15, fhalf + 90, fy + 56,  fw, "60");
        fUuid        = tf(16, fhalf,      fy + 84,  fw * 2 + 10, "aircraft UUID");
        fUuid.setMaxStringLength(36);

        buttonList.add(new GuiButton(BTN_DISPATCH, cx - 40, fy + 106, 80, 18, "Dispatch"));

        focusedField = fUuid;
    }

    // ─── Update ──────────────────────────────────────────────────────────────

    @Override
    public void updateScreen() {
        if (fRouteId   != null) fRouteId.updateCursorCounter();
        if (fParkingId != null) fParkingId.updateCursorCounter();
        if (fRunwayId  != null) fRunwayId.updateCursorCounter();
        if (fWaypoints != null) fWaypoints.updateCursorCounter();
        if (fTargetX   != null) fTargetX.updateCursorCounter();
        if (fTargetZ   != null) fTargetZ.updateCursorCounter();
        if (fOrbitRadius != null) fOrbitRadius.updateCursorCounter();
        if (fCruiseAlt != null) fCruiseAlt.updateCursorCounter();
        if (fStrikePasses != null) fStrikePasses.updateCursorCounter();
        if (fTimeLimit != null) fTimeLimit.updateCursorCounter();
        if (fUuid      != null) fUuid.updateCursorCounter();
    }

    // ─── Draw ────────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();

        int cx = width / 2;
        int ty = height / 4;

        // タイトル
        drawCenteredString(fontRenderer, "§eBase: §f" + baseId, cx, ty - 26, 0xFFFFFF);

        // タブ下線
        highlightTab(BTN_TAB_ROUTES,  currentTab == 0);
        highlightTab(BTN_TAB_MISSION, currentTab == 1);

        if (currentTab == 0) {
            drawRoutesTab(cx, ty + 12);
        } else {
            drawMissionTab(cx, ty + 12);
        }

        super.drawScreen(mx, my, pt);
    }

    private void highlightTab(int btnId, boolean active) {
        for (GuiButton b : buttonList) {
            if (b.id == btnId) {
                if (active) {
                    drawRect(b.x, b.y + b.height, b.x + b.width, b.y + b.height + 2, 0xFFFFAA00);
                }
                break;
            }
        }
    }

    private void drawRoutesTab(int cx, int ty) {
        int rx = cx - 70;
        drawString(fontRenderer, "Route ID:",    rx, ty + 3,   0xAAAAAA);
        drawString(fontRenderer, "Parking ID:",  rx, ty + 33,  0xAAAAAA);
        drawString(fontRenderer, "Runway A ID:", rx, ty + 63,  0xAAAAAA);
        drawString(fontRenderer, "Waypoints:",   rx, ty + 93,  0xAAAAAA);

        // 利用可能な駐機場・ウェイポイントをヒント表示
        if (!pkt.parkingMarkers.isEmpty()) {
            StringBuilder sb = new StringBuilder("§7Parking: ");
            for (int i = 0; i < Math.min(4, pkt.parkingMarkers.size()); i++) {
                if (i > 0) sb.append(", ");
                sb.append(pkt.parkingMarkers.get(i).id);
            }
            drawString(fontRenderer, sb.toString(), rx, ty + 122, 0x888888);
        }
        if (!pkt.waypointMarkers.isEmpty()) {
            StringBuilder sb = new StringBuilder("§7WP: ");
            for (int i = 0; i < Math.min(6, pkt.waypointMarkers.size()); i++) {
                if (i > 0) sb.append(", ");
                sb.append(pkt.waypointMarkers.get(i).id);
            }
            drawString(fontRenderer, sb.toString(), rx, ty + 134, 0x888888);
        }

        if (fRouteId   != null) fRouteId.drawTextBox();
        if (fParkingId != null) fParkingId.drawTextBox();
        if (fRunwayId  != null) fRunwayId.drawTextBox();
        if (fWaypoints != null) fWaypoints.drawTextBox();
    }

    private void drawMissionTab(int cx, int ty) {
        drawString(fontRenderer, "Mission Types:", cx - 190, ty,       0xAAAAAA);
        drawString(fontRenderer, "Weapons:",       cx - 190, ty + 48,  0xAAAAAA);

        int fy = ty + 96;
        int fhalf = cx - 90;
        drawString(fontRenderer, "Target X:", fhalf - 50, fy + 4,      0xAAAAAA);
        drawString(fontRenderer, "Z:",         fhalf + 44, fy + 4,     0xAAAAAA);
        drawString(fontRenderer, "Orbit R:",   fhalf - 50, fy + 32,    0xAAAAAA);
        drawString(fontRenderer, "Cruise Y:",  fhalf + 44, fy + 32,    0xAAAAAA);
        drawString(fontRenderer, "Passes:",    fhalf - 50, fy + 60,    0xAAAAAA);
        drawString(fontRenderer, "Limit(m):", fhalf + 44, fy + 60,    0xAAAAAA);
        drawString(fontRenderer, "UUID:",      fhalf - 50, fy + 88,    0xAAAAAA);

        // ミッション種別ボタンのハイライト
        highlightToggle(BTN_M_CAP,    selectedMissionTypes.contains("CAP"));
        highlightToggle(BTN_M_CAS,    selectedMissionTypes.contains("CAS"));
        highlightToggle(BTN_M_STRIKE, selectedMissionTypes.contains("STRIKE"));
        highlightToggle(BTN_M_ESCORT, selectedMissionTypes.contains("ESCORT"));
        highlightToggle(BTN_M_RECON,  selectedMissionTypes.contains("RECON"));
        highlightToggle(BTN_M_FERRY,  selectedMissionTypes.contains("FERRY"));

        // 武器ボタンのハイライト
        highlightToggle(BTN_W_GUN,    selectedWeapons.contains("gun"));
        highlightToggle(BTN_W_AA,     selectedWeapons.contains("aamissile"));
        highlightToggle(BTN_W_AS,     selectedWeapons.contains("asmissile"));
        highlightToggle(BTN_W_CAS,    selectedWeapons.contains("cas"));
        highlightToggle(BTN_W_ROCKET, selectedWeapons.contains("rocket"));
        highlightToggle(BTN_W_BOMB,   selectedWeapons.contains("bomb"));

        if (fTargetX     != null) fTargetX.drawTextBox();
        if (fTargetZ     != null) fTargetZ.drawTextBox();
        if (fOrbitRadius != null) fOrbitRadius.drawTextBox();
        if (fCruiseAlt   != null) fCruiseAlt.drawTextBox();
        if (fStrikePasses!= null) fStrikePasses.drawTextBox();
        if (fTimeLimit   != null) fTimeLimit.drawTextBox();
        if (fUuid        != null) fUuid.drawTextBox();
    }

    private void highlightToggle(int btnId, boolean on) {
        if (!on) return;
        for (GuiButton b : buttonList) {
            if (b.id == btnId) {
                drawRect(b.x - 1, b.y - 1, b.x + b.width + 1, b.y + b.height + 1, 0xFFFFAA00);
                break;
            }
        }
    }

    // ─── Input ───────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton btn) throws IOException {
        switch (btn.id) {
            case BTN_TAB_ROUTES:
                if (currentTab != 0) { currentTab = 0; initGui(); }
                return;
            case BTN_TAB_MISSION:
                if (currentTab != 1) { currentTab = 1; initGui(); }
                return;
            case BTN_CLOSE:
                mc.player.closeScreen();
                return;
        }

        if (currentTab == 0) {
            actionRoutes(btn);
        } else {
            actionMission(btn);
        }
    }

    private void actionRoutes(GuiButton btn) {
        if (btn.id == BTN_ROUTE_NEW) {
            selectedRouteIdx = -1;
            clearRouteForm();
            return;
        }
        if (btn.id == BTN_ROUTE_SAVE) {
            saveRoute();
            return;
        }
        if (btn.id == BTN_ROUTE_DEL) {
            deleteRoute();
            return;
        }
        // ルートリストボタン
        int idx = btn.id - BTN_ROUTE_BASE;
        if (idx >= 0 && idx < routes.size()) {
            selectedRouteIdx = idx;
            PacketOpenBaseGui.RouteDto r = routes.get(idx);
            fRouteId.setText(r.routeId);
            fParkingId.setText(r.parkingId);
            fRunwayId.setText(r.runwayId);
            fWaypoints.setText(String.join(",", r.waypointIds));
        }
    }

    private void actionMission(GuiButton btn) {
        switch (btn.id) {
            case BTN_M_CAP:    toggleMission("CAP",    false); return;
            case BTN_M_CAS:    toggleMission("CAS",    false); return;
            case BTN_M_STRIKE: toggleMission("STRIKE", true);  return;
            case BTN_M_ESCORT: toggleMission("ESCORT", false); return;
            case BTN_M_RECON:  toggleMission("RECON",  false); return;
            case BTN_M_FERRY:  toggleMission("FERRY",  true);  return;
            case BTN_W_GUN:    toggleWeapon("gun");       return;
            case BTN_W_AA:     toggleWeapon("aamissile"); return;
            case BTN_W_AS:     toggleWeapon("asmissile"); return;
            case BTN_W_CAS:    toggleWeapon("cas");       return;
            case BTN_W_ROCKET: toggleWeapon("rocket");    return;
            case BTN_W_BOMB:   toggleWeapon("bomb");      return;
            case BTN_DISPATCH: dispatchOrder();            return;
        }
    }

    /** ミッション種別のトグル。exclusive=true なら他を全てクリアしてから自分をセット。 */
    private void toggleMission(String type, boolean exclusive) {
        if (exclusive) {
            if (selectedMissionTypes.contains(type)) {
                selectedMissionTypes.remove(type);
            } else {
                // 排他ミッションが ON になったら他を全解除
                selectedMissionTypes.clear();
                selectedMissionTypes.add(type);
            }
        } else {
            // 非排他ミッション: 排他ミッションが選択中なら先にクリア
            boolean hadExclusive = selectedMissionTypes.contains("STRIKE")
                                || selectedMissionTypes.contains("FERRY");
            if (hadExclusive) selectedMissionTypes.clear();

            if (selectedMissionTypes.contains(type)) {
                selectedMissionTypes.remove(type);
            } else {
                selectedMissionTypes.add(type);
            }
        }
    }

    private void toggleWeapon(String weapon) {
        if (selectedWeapons.contains(weapon)) {
            selectedWeapons.remove(weapon);
        } else {
            selectedWeapons.add(weapon);
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) { mc.player.closeScreen(); return; }
        if (key == Keyboard.KEY_TAB) {
            cycleFocus();
            return;
        }
        if (focusedField != null) focusedField.textboxKeyTyped(c, key);
    }

    @Override
    public void mouseClicked(int mx, int my, int mb) throws IOException {
        super.mouseClicked(mx, my, mb);
        GuiTextField[] fields = allFields();
        GuiTextField clicked = null;
        for (GuiTextField f : fields) {
            if (f != null) { f.mouseClicked(mx, my, mb); if (f.isFocused()) clicked = f; }
        }
        if (clicked != null) {
            for (GuiTextField f : fields) { if (f != null && f != clicked) f.setFocused(false); }
            focusedField = clicked;
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    // ─── Route helpers ───────────────────────────────────────────────────────

    private void saveRoute() {
        String rid = fRouteId.getText().trim();
        String pid = fParkingId.getText().trim();
        String rwid = fRunwayId.getText().trim();
        String wps  = fWaypoints.getText().trim();
        if (rid.isEmpty() || pid.isEmpty() || rwid.isEmpty()) return;

        PacketBaseAction pkt = new PacketBaseAction();
        pkt.action     = PacketBaseAction.SAVE_ROUTE;
        pkt.routeId    = rid;
        pkt.baseId     = baseId;
        pkt.parkingId  = pid;
        pkt.runwayId   = rwid;
        pkt.waypointsCsv = wps;
        WingmanNetwork.sendToServer(pkt);

        // ローカルリストも更新
        PacketOpenBaseGui.RouteDto dto = new PacketOpenBaseGui.RouteDto();
        dto.routeId = rid; dto.parkingId = pid; dto.runwayId = rwid;
        for (String w : wps.split(",")) { String t = w.trim(); if (!t.isEmpty()) dto.waypointIds.add(t); }

        boolean found = false;
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).routeId.equals(rid)) { routes.set(i, dto); found = true; break; }
        }
        if (!found) routes.add(dto);

        selectedRouteIdx = routes.size() - 1;
        initGui(); // リスト更新のため再描画
    }

    private void deleteRoute() {
        if (selectedRouteIdx < 0 || selectedRouteIdx >= routes.size()) return;
        String rid = routes.get(selectedRouteIdx).routeId;

        PacketBaseAction pkt = new PacketBaseAction();
        pkt.action  = PacketBaseAction.DELETE_ROUTE;
        pkt.routeId = rid;
        pkt.baseId  = baseId;
        WingmanNetwork.sendToServer(pkt);

        routes.remove(selectedRouteIdx);
        selectedRouteIdx = -1;
        clearRouteForm();
        initGui();
    }

    private void clearRouteForm() {
        if (fRouteId   != null) fRouteId.setText("");
        if (fParkingId != null) fParkingId.setText("");
        if (fRunwayId  != null) fRunwayId.setText("");
        if (fWaypoints != null) fWaypoints.setText("");
    }

    // ─── Mission helpers ─────────────────────────────────────────────────────

    private void dispatchOrder() {
        String uuid = fUuid != null ? fUuid.getText().trim() : "";
        if (uuid.isEmpty() || selectedMissionTypes.isEmpty()) return;

        PacketBaseAction pkt = new PacketBaseAction();
        pkt.action          = PacketBaseAction.DISPATCH_ORDER;
        pkt.baseId          = baseId;
        pkt.wingmanUuid     = uuid;
        pkt.missionTypesCsv = String.join(",", selectedMissionTypes);
        pkt.weaponsCsv      = String.join(",", selectedWeapons);
        pkt.targetX         = parseDouble(fTargetX,      0);
        pkt.targetZ         = parseDouble(fTargetZ,      0);
        pkt.orbitRadius     = parseDouble(fOrbitRadius,  300);
        pkt.cruiseAlt       = parseDouble(fCruiseAlt,    80);
        pkt.strikePasses    = parseInt   (fStrikePasses, 2);
        pkt.timeLimitMinutes= parseInt   (fTimeLimit,    60);
        pkt.ferryDestBase   = "";
        WingmanNetwork.sendToServer(pkt);
    }

    // ─── Field helpers ───────────────────────────────────────────────────────

    private GuiTextField tf(int id, int x, int y, int w, String placeholder) {
        GuiTextField f = new GuiTextField(id, fontRenderer, x, y, w, 16);
        f.setMaxStringLength(128);
        if (f.getText().isEmpty()) f.setText(placeholder);
        return f;
    }

    private GuiTextField[] allFields() {
        return new GuiTextField[]{
            fRouteId, fParkingId, fRunwayId, fWaypoints,
            fTargetX, fTargetZ, fOrbitRadius, fCruiseAlt,
            fStrikePasses, fTimeLimit, fUuid
        };
    }

    private void cycleFocus() {
        GuiTextField[] fields = allFields();
        List<GuiTextField> active = new ArrayList<>();
        for (GuiTextField f : fields) { if (f != null) active.add(f); }
        if (active.isEmpty()) return;
        int cur = active.indexOf(focusedField);
        int next = (cur + 1) % active.size();
        for (GuiTextField f : active) f.setFocused(false);
        active.get(next).setFocused(true);
        focusedField = active.get(next);
    }

    private double parseDouble(GuiTextField f, double def) {
        if (f == null) return def;
        try { return Double.parseDouble(f.getText().trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private int parseInt(GuiTextField f, int def) {
        if (f == null) return def;
        try { return Integer.parseInt(f.getText().trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
