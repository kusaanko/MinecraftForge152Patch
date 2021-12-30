package com.github.kusaanko.minecraftforge152patch;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;

import java.lang.reflect.Field;
import java.util.List;

@Mod(modid = "minecraft_forge152_patch", name = "MinecraftForge152Patch", version = MinecraftForge152Patch.version)
public class MinecraftForge152Patch {
    public static final String version = "1.0.0";

    // Add branding
    @Mod.PostInit
    public void load(FMLPostInitializationEvent event) {
        FMLCommonHandler fmlCommonHandler = FMLCommonHandler.instance();
        fmlCommonHandler.getBrandings();
        try {
            // Branding list can't be added directly, so add branding using reflection.
            Class regularImmutableList = this.getClass().getClassLoader().loadClass("com.google.common.collect.RegularImmutableList");
            Field arrayField = regularImmutableList.getDeclaredField("array");
            Field sizeField = regularImmutableList.getDeclaredField("size");
            arrayField.setAccessible(true);
            sizeField.setAccessible(true);
            Field brandingsField = fmlCommonHandler.getClass().getDeclaredField("brandings");
            brandingsField.setAccessible(true);
            List<String> brandings = (List<String>) brandingsField.get(fmlCommonHandler);
            Object[] array = (Object[]) arrayField.get(brandings);
            Object[] newArray = new Object[array.length + 1];
            // Put branding last - 1
            System.arraycopy(array, 0, newArray, 0, array.length - 1);
            System.arraycopy(array, array.length - 1, newArray, array.length, 1);
            newArray[array.length - 1] = "MinecraftForge152Patch v" + version;
            arrayField.set(brandings, newArray);
            sizeField.set(brandings, ((int)sizeField.get(brandings) + 1));
        } catch (NoSuchFieldException | IllegalAccessException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }
}
