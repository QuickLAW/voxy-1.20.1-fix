package me.cortex.voxy.commonImpl.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class VoxyMixinConfigPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".flashback.")) {
            return FabricLoader.getInstance().isModLoaded("flashback");
        }
        if (mixinClassName.contains(".iris.")) {
            return FabricLoader.getInstance().isModLoaded("iris");
        }
        if (mixinClassName.contains(".nvidium.")) {
            return FabricLoader.getInstance().isModLoaded("nvidium");
        }
        if (mixinClassName.contains(".chunky.")) {
            return FabricLoader.getInstance().isModLoaded("chunky");
        }
        if (mixinClassName.contains(".distanthorizons.")) {
            return FabricLoader.getInstance().isModLoaded("distanthorizons") || FabricLoader.getInstance().isModLoaded("distant_horizons");
        }
        if (mixinClassName.contains(".sodium.")) {
            return FabricLoader.getInstance().isModLoaded("sodium");
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
