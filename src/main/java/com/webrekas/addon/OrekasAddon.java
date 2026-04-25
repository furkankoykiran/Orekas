package com.webrekas.addon;

import com.mojang.logging.LogUtils;
import com.webrekas.addon.modules.OreFinderModule;
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
