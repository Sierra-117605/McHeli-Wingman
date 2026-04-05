package com.mcheliwingman.asm;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * CoreMod entry point.
 * Registers the bytecode transformer that patches McHeli's hardcoded UAV range check.
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions({"com.mcheliwingman.asm"})
public class WingmanPlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{"com.mcheliwingman.asm.WingmanTransformer"};
    }

    @Override public String getModContainerClass()     { return null; }
    @Override public String getSetupClass()            { return null; }
    @Override public void   injectData(Map<String, Object> data) {}
    @Override public String getAccessTransformerClass(){ return null; }
}
