# MinecraftForge152Patch
 Minecraft Forge 1.5.2 patching project.

# What's this?
This project's goal is reducing errors happen in Minecraft Forge for 1.5.2.

# Main feature
- Stop library downloading error
  - Replace new library download link
  - Replace old checksum to new one.
  - Generate deobfuscation_data_1.5.2.zip

# How to install 
Put downloaded zip file content into minecraft.jar after installing forge.

# Attention
If you launch Minecraft Forge with this mod at first launch, please launch it with this mod from next time too.

This mod includes checksum replacing system, so if you uninstall this mod then you can't launch Minecraft.

# How to stop showing branding on title screen?
Make `MinecraftForge152Patch_hideBranding.txt` file in .minecraft(or game directory).

# Detailed explanation
Forge tries download needed libraries when it is run at first time. But the url returns 404 not found code. Then Forge returns error, so Minecraft can't launch. Each libraries can be downloaded using maven central, but those files are not completely equal, so I need to fix sum checking codes. This codes changes sum to new sum that are from maven central's files.

# How to build?
1. Place your minecraft.jar with the latest forge for 1.5.2 in root.
2. Build artifact with JDK1.7.

# How to create deobfuscation_data_1.5.2.zip?
You can create this file using forge source code and mcp.

## Download forge 1.5.2 source code from forge official site.
Download from [here](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.5.2.html)

## Download mcp
Download mcp 7.51 from [here](https://minecraft.fandom.com/wiki/Programs_and_editors/Mod_Coder_Pack#Downloads) and place it forge/fml/mcp7.51.zip

## Run install.cmd or install.sh
You will have errors, but ignore them.

## Copy mcp/conf/packaged.srg
Copy packaged.srg , and rename to joined.srg, and zip it, and rename to deobfuscation_data_1.5.2.zip.

# Used libraries and links
- [argo-3.2](https://mvnrepository.com/artifact/net.sourceforge.argo/argo/3.2)
- [asm-all-4.2](https://mvnrepository.com/artifact/org.ow2.asm/asm-all/4.1)
- [guava-14.0-rc3](https://mvnrepository.com/artifact/com.google.guava/guava/14.0-rc3)
- [bcprov-jdk15on](https://mvnrepository.com/artifact/org.bouncycastle/bcprov-jdk15on/1.48)
- [scala-library](https://mvnrepository.com/artifact/org.scala-lang/scala-library/2.10.0)
