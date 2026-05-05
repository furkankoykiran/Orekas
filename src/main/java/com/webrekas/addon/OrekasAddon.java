package com.webrekas.addon;

import com.mojang.logging.LogUtils;
import com.webrekas.addon.modules.AdminListModule;
import com.webrekas.addon.modules.AutoSellModule;
import com.webrekas.addon.modules.OreFinderModule;
import com.webrekas.addon.modules.OrderSniperModule;
import com.webrekas.addon.modules.PlayerDetectionModule;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class OrekasAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Orekas");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Orekas Addon");

        Modules.get().add(new OreFinderModule());
        // AdminList must be registered before any module that consults it
        Modules.get().add(new AdminListModule());
        Modules.get().add(new OrderSniperModule());
        Modules.get().add(new AutoSellModule());
        Modules.get().add(new PlayerDetectionModule());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.webrekas.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("furkankoykiran", "Orekas");
    }
}
