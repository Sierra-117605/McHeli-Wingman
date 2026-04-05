package com.mcheliwingman.client;

import com.mcheliwingman.network.PacketPlannerData.RouteDto;
import com.mcheliwingman.network.PacketRouteAction;
import com.mcheliwingman.network.WingmanNetwork;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiNewRoute extends GuiScreen {

    private final GuiScreen parent;
    private final List<RouteDto> existingRoutes;

    private GuiTextField nameField;
    private GuiTextField nodesField;
    private GuiButton btnSave, btnCancel;

    private String errorMsg = "";

    public GuiNewRoute(GuiScreen parent, List<RouteDto> existingRoutes) {
        this.parent = parent;
        this.existingRoutes = existingRoutes;
    }

    @Override
    public void initGui() {
        int cx = width / 2;
        int cy = height / 2;

        nameField  = new GuiTextField(0, fontRenderer, cx - 150, cy - 60, 300, 20);
        nodesField = new GuiTextField(1, fontRenderer, cx - 150, cy - 20, 300, 20);
        nameField.setMaxStringLength(64);
        nodesField.setMaxStringLength(512);
        nameField.setFocused(true);

        buttonList.add(btnSave   = new GuiButton(0, cx - 80, cy + 20, 70, 20, "Save"));
        buttonList.add(btnCancel = new GuiButton(1, cx + 10,  cy + 20, 70, 20, "Cancel"));
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        int cx = width / 2;
        int cy = height / 2;

        drawCenteredString(fontRenderer, "§eNew Route", cx, cy - 90, 0xFFFFFF);

        drawString(fontRenderer, "Route name:", cx - 150, cy - 72, 0xAAAAAA);
        drawString(fontRenderer, "Nodes (space-separated):", cx - 150, cy - 32, 0xAAAAAA);
        drawString(fontRenderer, "§8e.g.: takeoff:rwy flyto:100,80,200 attack:50 land:rwy park:alpha", cx - 150, cy - 8, 0x666666);
        drawString(fontRenderer, "§8Node types: takeoff:<id>  land:<id>  flyto:<x,y,z>  attack:<radius>  loiter:<ticks>  park:<id>",
                   cx - 150, cy + 2, 0x555555);

        nameField.drawTextBox();
        nodesField.drawTextBox();

        if (!errorMsg.isEmpty()) {
            drawCenteredString(fontRenderer, "§c" + errorMsg, cx, cy + 50, 0xFFFFFF);
        }

        super.drawScreen(mx, my, pt);
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == 1) { // ESC
            mc.displayGuiScreen(parent);
            return;
        }
        if (key == 15) { // TAB — switch focus
            nameField.setFocused(!nameField.isFocused());
            nodesField.setFocused(!nodesField.isFocused());
            return;
        }
        if (nameField.isFocused())  nameField.textboxKeyTyped(c, key);
        if (nodesField.isFocused()) nodesField.textboxKeyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        super.mouseClicked(mx, my, btn);
        nameField.mouseClicked(mx, my, btn);
        nodesField.mouseClicked(mx, my, btn);
    }

    @Override
    public void updateScreen() {
        nameField.updateCursorCounter();
        nodesField.updateCursorCounter();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == btnCancel) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button == btnSave) {
            String name = nameField.getText().trim();
            String raw  = nodesField.getText().trim();

            if (name.isEmpty()) { errorMsg = "Route name required."; return; }
            if (raw.isEmpty())  { errorMsg = "At least one node required."; return; }

            // Check duplicate
            for (RouteDto r : existingRoutes) {
                if (r.name.equals(name)) { errorMsg = "Route '" + name + "' already exists."; return; }
            }

            List<String> nodes = Arrays.asList(raw.split("\\s+"));
            WingmanNetwork.sendToServer(new PacketRouteAction(PacketRouteAction.CREATE, name, nodes));
            mc.displayGuiScreen(null); // close all and return to game
        }
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
