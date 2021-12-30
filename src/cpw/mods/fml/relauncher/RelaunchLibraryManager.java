/*
 * Forge Mod Loader
 * Copyright (c) 2012-2013 cpw.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Contributors:
 *     cpw - implementation
 *     kusaanko - fix downloading libraries
 */
package cpw.mods.fml.relauncher;

import java.awt.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.github.kusaanko.minecraftforge152patch.MinecraftForge152Patch;
import cpw.mods.fml.common.CertificateHelper;
import cpw.mods.fml.relauncher.*;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin.MCVersion;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin.TransformerExclusions;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.*;

public class RelaunchLibraryManager
{
    private static String[] rootPlugins =  { "cpw.mods.fml.relauncher.FMLCorePlugin" , "net.minecraftforge.classloading.FMLForgePlugin" };
    private static List<String> loadedLibraries = new ArrayList<String>();
    private static Map<IFMLLoadingPlugin, File> pluginLocations;
    private static List<IFMLLoadingPlugin> loadPlugins;
    private static List<ILibrarySet> libraries;
    private static boolean deobfuscatedEnvironment;

    public static void handleLaunch(File mcDir, RelaunchClassLoader actualClassLoader)
    {
        try
        {
            // Are we in a 'decompiled' environment?
            byte[] bs = actualClassLoader.getClassBytes("net.minecraft.world.World");
            if (bs != null)
            {
                FMLRelaunchLog.info("Managed to load a deobfuscated Minecraft name- we are in a deobfuscated environment. Skipping runtime deobfuscation");
                deobfuscatedEnvironment = true;
            }
        }
        catch (IOException e1)
        {
        }

        if (!deobfuscatedEnvironment)
        {
            FMLRelaunchLog.fine("Enabling runtime deobfuscation");
        }
        pluginLocations = new HashMap<IFMLLoadingPlugin, File>();
        loadPlugins = new ArrayList<IFMLLoadingPlugin>();
        libraries = new ArrayList<ILibrarySet>();
        for (String s : rootPlugins)
        {
            try
            {
                IFMLLoadingPlugin plugin = (IFMLLoadingPlugin) Class.forName(s, true, actualClassLoader).newInstance();
                loadPlugins.add(plugin);
                for (String libName : plugin.getLibraryRequestClass())
                {
                    libraries.add((ILibrarySet) Class.forName(libName, true, actualClassLoader).newInstance());
                }
            }
            catch (Exception e)
            {
                // HMMM
            }
        }

        if (loadPlugins.isEmpty())
        {
            throw new RuntimeException("A fatal error has occured - no valid fml load plugin was found - this is a completely corrupt FML installation.");
        }

        downloadMonitor.updateProgressString("All core mods are successfully located");
        // Now that we have the root plugins loaded - lets see what else might be around
        String commandLineCoremods = System.getProperty("fml.coreMods.load","");
        for (String s : commandLineCoremods.split(","))
        {
            if (s.isEmpty())
            {
                continue;
            }
            FMLRelaunchLog.info("Found a command line coremod : %s", s);
            try
            {
                actualClassLoader.addTransformerExclusion(s);
                Class<?> coreModClass = Class.forName(s, true, actualClassLoader);
                TransformerExclusions trExclusions = coreModClass.getAnnotation(TransformerExclusions.class);
                if (trExclusions!=null)
                {
                    for (String st : trExclusions.value())
                    {
                        actualClassLoader.addTransformerExclusion(st);
                    }
                }
                IFMLLoadingPlugin plugin = (IFMLLoadingPlugin) coreModClass.newInstance();
                loadPlugins.add(plugin);
                if (plugin.getLibraryRequestClass()!=null)
                {
                    for (String libName : plugin.getLibraryRequestClass())
                    {
                        libraries.add((ILibrarySet) Class.forName(libName, true, actualClassLoader).newInstance());
                    }
                }
            }
            catch (Throwable e)
            {
                FMLRelaunchLog.log(Level.SEVERE,e,"Exception occured trying to load coremod %s",s);
                throw new RuntimeException(e);
            }
        }
        discoverCoreMods(mcDir, actualClassLoader, loadPlugins, libraries);

        List<Throwable> caughtErrors = new ArrayList<Throwable>();
        try
        {
            File libDir;
            try
            {
                libDir = setupLibDir(mcDir);
            }
            catch (Exception e)
            {
                caughtErrors.add(e);
                return;
            }

            // --------  MinecraftForge152Patch start  ----------
            System.out.println("MinecraftForge152Patch v" + MinecraftForge152Patch.version);
            System.out.println("https://github.com/kusaanko/MinecraftForge152Patch");
            {
                String errorMsg = "";
                // Generate deobfuscation_data_1.5.2.zip
                if(!new File(new File(mcDir, "lib"), "deobfuscation_data_1.5.2.zip").exists()) {
                    try {
                        String forgeSrcURL = "https://maven.minecraftforge.net/net/minecraftforge/forge/1.5.2-7.8.1.738/forge-1.5.2-7.8.1.738-src.zip";
                        String mcp751mediafireURL = "http://www.mediafire.com/file/95vlzp1a4n4wjqw/mcp751.zip/file";
                        String mcp751URL = null;
                        byte[] buff = new byte[8192];
                        int len;
                        downloadMonitor.updateProgressString("Getting mcp751 url");
                        // Generate mcp751 URL
                        {
                            errorMsg = "Getting mcp751 url failed";
                            URL url = new URL(mcp751mediafireURL);
                            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                            connection.connect();
                            if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                                try (InputStream inputStream = connection.getInputStream();
                                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                                    while ((len = inputStream.read(buff)) != -1) {
                                        baos.write(buff, 0, len);
                                    }
                                    Matcher matcher = Pattern.compile("(http://download[^\"]*)").matcher(baos.toString());
                                    if (matcher.find()) {
                                        mcp751URL = matcher.group(1);
                                    } else {
                                        throw new Exception("Getting mcp751 url failed");
                                    }
                                }
                            }
                        }
                        // Create tmp folder
                        File tmp = new File(mcDir, "temp");
                        {
                            errorMsg = "Couldn't create temp folder.";
                            tmp.mkdirs();
                            System.out.println("Make temp dir at " + tmp.getAbsolutePath());
                        }
                        File packagesCsv = new File(tmp, "packages.csv");
                        File joinedSrg = new File(tmp, "joined.srg");
                        downloadMonitor.updateProgressString("Downloading forge src");
                        // Download files
                        // Forge src
                        {
                            // Stop checking ssl certificate
                            TrustManager[] tm = {
                                    new X509TrustManager() {
                                        public X509Certificate[] getAcceptedIssuers() {
                                            return null;
                                        }

                                        public void checkClientTrusted(X509Certificate[] xc, String type) {
                                        }

                                        public void checkServerTrusted(X509Certificate[] xc, String type) {
                                        }
                                    }
                            };

                            SSLContext ctx = SSLContext.getInstance("SSL");
                            ctx.init(null, tm, new SecureRandom());
                            File forgeSrc = new File(tmp, "forge-1.5.2-src.zip");
                            errorMsg = "Couldn't download forge src.";
                            URL url = new URL(forgeSrcURL);
                            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                            connection.setInstanceFollowRedirects(true);
                            connection.setSSLSocketFactory(ctx.getSocketFactory());
                            connection.setRequestProperty("User-Agent", "FML Relaunch Downloader");
                            int read = 0;
                            int length = connection.getContentLength();
                            if(length == -1) length = Integer.MAX_VALUE;
                            if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                                try (InputStream inputStream = connection.getInputStream();
                                     OutputStream outputStream = new FileOutputStream(forgeSrc)) {
                                    while ((len = inputStream.read(buff)) != -1) {
                                        outputStream.write(buff, 0, len);
                                        read += len;
                                        downloadMonitor.updateProgress((int) ((float)read / length * 100));
                                    }
                                }
                            }
                            // Unzip needed files
                            errorMsg = "Couldn't unzip forge src.";
                            try (ZipInputStream zip = new ZipInputStream(new FileInputStream(forgeSrc))) {
                                ZipEntry entry;
                                while ((entry = zip.getNextEntry()) != null) {
                                    if (entry.getName().endsWith("fml/conf/packages.csv")) {
                                        try (OutputStream outputStream = new FileOutputStream(packagesCsv)) {
                                            while ((len = zip.read(buff)) != -1) {
                                                outputStream.write(buff, 0, len);
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                        downloadMonitor.updateProgressString("Downloading mcp7.51");
                        // mcp
                        {
                            File mcp = new File(tmp, "mcp7.51.zip");
                            errorMsg = "Couldn't download mcp7.51.";
                            URL url = new URL(mcp751URL);
                            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                            connection.setRequestProperty("User-Agent", "FML Relaunch Downloader");
                            int read = 0;
                            int length = connection.getContentLength();
                            if(length == -1) length = Integer.MAX_VALUE;
                            if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                                try (InputStream inputStream = connection.getInputStream();
                                     OutputStream outputStream = new FileOutputStream(mcp)) {
                                    while ((len = inputStream.read(buff)) != -1) {
                                        outputStream.write(buff, 0, len);
                                        read += len;
                                        downloadMonitor.updateProgress((int) ((float)read / length * 100));
                                    }
                                }
                            }
                            // Unzip needed files and mix client.srg and server.srg to joined.srg
                            List<String> mappings = new ArrayList<>(18193);
                            errorMsg = "Couldn't unzip mcp7.51.";
                            try (ZipInputStream zip = new ZipInputStream(new FileInputStream(mcp))) {
                                ZipEntry entry;
                                int unzipped = 0;
                                while ((entry = zip.getNextEntry()) != null) {
                                    if (entry.getName().equals("conf/client.srg") || entry.getName().equals("conf/server.srg")) {
                                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new InputStream() {
                                            @Override
                                            public int read() throws IOException {
                                                return zip.read();
                                            }
                                        }))) {
                                            String line;
                                            while ((line = reader.readLine()) != null) {
                                                if (!mappings.contains(line)) mappings.add(line);
                                            }
                                        }
                                        unzipped++;
                                        if(unzipped == 2) break;
                                    }
                                }
                            }
                            // Sort mappings PK: CL: FD: MD:
                            Collections.sort(mappings, new Comparator<String>() {
                                @Override
                                public int compare(String o1, String o2) {
                                    if (o1.startsWith("PK: ") && o1.startsWith("PK: ") != o2.startsWith("PK: ")) {
                                        return -1;
                                    }
                                    if (o2.startsWith("PK: ") && o1.startsWith("PK: ") != o2.startsWith("PK: ")) {
                                        return 1;
                                    }
                                    return o1.compareTo(o2);
                                }
                            });
                            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(joinedSrg), StandardCharsets.UTF_8))) {
                                for (String mapping : mappings) {
                                    writer.write(mapping + "\n");
                                }
                            }
                        }
                        // Mix packages.csv and joined.srg
                        downloadMonitor.updateProgressString("Mixing packages.csv and joined.srg");
                        File finalSrg = new File(tmp, "final.srg");
                        {
                            errorMsg = "Mixing packages.csv and joined.srg failed";
                            Map<String, String> pkgs = new HashMap<>();
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(packagesCsv), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.contains(",")) {
                                        int index = line.indexOf(",");
                                        pkgs.put(line.substring(0, index), line.substring(index + 1));
                                    }
                                }
                            }
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(joinedSrg), StandardCharsets.UTF_8));
                                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(finalSrg), StandardCharsets.UTF_8))) {
                                String line;
                                Pattern pattern = Pattern.compile("net/minecraft/src/([0-9A-z]*)");
                                while ((line = reader.readLine()) != null) {
                                    Matcher matcher = pattern.matcher(line);
                                    while (matcher.find()) {
                                        String pkg = pkgs.get(matcher.group(1));
                                        if (pkg != null) {
                                            line = line.substring(0, matcher.start()) + pkg + "/" + matcher.group(1) + line.substring(matcher.end());
                                            matcher = pattern.matcher(line);
                                        }
                                    }
                                    writer.write(line + "\n");
                                }
                            }
                        }
                        // Create deobfuscation_data_1.5.2.zip
                        downloadMonitor.updateProgressString("Creating deobfuscation_data_1.5.2.zip");
                        {
                            errorMsg = "Creating deobfuscation_data_1.5.2.zip failed";
                            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(new File(tmp, "deobfuscation_data_1.5.2.zip")));
                                 InputStream inputStream = new FileInputStream(finalSrg)) {
                                ZipEntry entry = new ZipEntry("joined.srg");
                                zip.putNextEntry(entry);
                                while ((len = inputStream.read(buff)) != -1) {
                                    zip.write(buff, 0, len);
                                }
                            }
                        }
                        // Copy deobfuscation_data_1.5.2.zip to lib
                        errorMsg = "Copying deobfuscation_data_1.5.2.zip failed";
                        new File(tmp, "deobfuscation_data_1.5.2.zip").renameTo(new File(libDir, "deobfuscation_data_1.5.2.zip"));
                        // Clean temp folder
                        downloadMonitor.updateProgressString("Cleaning temp folder");
                        errorMsg = "Cleaning temp folder failed";
                        for(File file : tmp.listFiles()) {
                            file.delete();
                        }
                        tmp.delete();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        caughtErrors.add(new RuntimeException(errorMsg + " " + ex.getMessage()));
                        JFrame jframe = new JFrame("MinecraftForge152Patch - error");
                        jframe.setLayout(new BorderLayout());
                        JLabel error = new JLabel(errorMsg);
                        JTextArea exMsg = new JTextArea(ex.getMessage());
                        JScrollPane scrollPane = new JScrollPane(exMsg);
                        jframe.add(error, BorderLayout.NORTH);
                        jframe.add(scrollPane, BorderLayout.CENTER);
                        jframe.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
                        jframe.setSize(500, 400);
                        jframe.setLocationRelativeTo(null);
                        jframe.setVisible(true);
                    }
                }
            }
            // --------  MinecraftForge152Patch end  ----------

            for (ILibrarySet lib : libraries)
            {
                for (int i=0; i<lib.getLibraries().length; i++)
                {
                    boolean download = false;
                    String libName = lib.getLibraries()[i];
                    String targFileName = libName.lastIndexOf('/')>=0 ? libName.substring(libName.lastIndexOf('/')) : libName;
                    String checksum = lib.getHashes()[i];
                    File libFile = new File(libDir, targFileName);
                    if (!libFile.exists())
                    {
                        try
                        {
                            downloadFile(libFile, lib.getRootURL(), libName, checksum);
                            download = true;
                        }
                        catch (Throwable e)
                        {
                            caughtErrors.add(e);
                            continue;
                        }
                    }

                    if (libFile.exists() && !libFile.isFile())
                    {
                        caughtErrors.add(new RuntimeException(String.format("Found a file %s that is not a normal file - you should clear this out of the way", libName)));
                        continue;
                    }

                    if (!download)
                    {
                        try
                        {
                            FileInputStream fis = new FileInputStream(libFile);
                            FileChannel chan = fis.getChannel();
                            MappedByteBuffer mappedFile = chan.map(MapMode.READ_ONLY, 0, libFile.length());
                            String fileChecksum = generateChecksum(mappedFile, libFile);
                            fis.close();
                            // bad checksum and I did not download this file
                            if (!checksum.equals(fileChecksum))
                            {
                                caughtErrors.add(new RuntimeException(String.format("The file %s was found in your lib directory and has an invalid checksum %s (expecting %s) - it is unlikely to be the correct download, please move it out of the way and try again.", libName, fileChecksum, checksum)));
                                continue;
                            }
                        }
                        catch (Exception e)
                        {
                            FMLRelaunchLog.log(Level.SEVERE, e, "The library file %s could not be validated", libFile.getName());
                            caughtErrors.add(new RuntimeException(String.format("The library file %s could not be validated", libFile.getName()),e));
                            continue;
                        }
                    }

                    if (!download)
                    {
                        downloadMonitor.updateProgressString("Found library file %s present and correct in lib dir", libName);
                    }
                    else
                    {
                        downloadMonitor.updateProgressString("Library file %s was downloaded and verified successfully", libName);
                    }

                    try
                    {
                        actualClassLoader.addURL(libFile.toURI().toURL());
                        loadedLibraries.add(libName);
                    }
                    catch (MalformedURLException e)
                    {
                        caughtErrors.add(new RuntimeException(String.format("Should never happen - %s is broken - probably a somehow corrupted download. Delete it and try again.", libFile.getName()), e));
                    }
                }
            }
        }
        finally
        {
            if (downloadMonitor.shouldStopIt())
            {
                return;
            }
            if (!caughtErrors.isEmpty())
            {
                FMLRelaunchLog.severe("There were errors during initial FML setup. " +
                		"Some files failed to download or were otherwise corrupted. " +
                		"You will need to manually obtain the following files from " +
                		"these download links and ensure your lib directory is clean. ");
                for (ILibrarySet set : libraries)
                {
                    for (String file : set.getLibraries())
                    {
                        FMLRelaunchLog.severe("*** Download "+set.getRootURL(), file);
                    }
                }
                FMLRelaunchLog.severe("<===========>");
                FMLRelaunchLog.severe("The following is the errors that caused the setup to fail. " +
                		"They may help you diagnose and resolve the issue");
                for (Throwable t : caughtErrors)
                {
                    if (t.getMessage()!=null)
                    {
                        FMLRelaunchLog.severe(t.getMessage());
                    }
                }
                FMLRelaunchLog.severe("<<< ==== >>>");
                FMLRelaunchLog.severe("The following is diagnostic information for developers to review.");
                for (Throwable t : caughtErrors)
                {
                    FMLRelaunchLog.log(Level.SEVERE, t, "Error details");
                }
                throw new RuntimeException("A fatal error occured and FML cannot continue");
            }
        }

        for (IFMLLoadingPlugin plug : loadPlugins)
        {
            if (plug.getASMTransformerClass()!=null)
            {
                for (String xformClass : plug.getASMTransformerClass())
                {
                    actualClassLoader.registerTransformer(xformClass);
                }
            }
        }
        // Deobfuscation transformer, always last
        if (!deobfuscatedEnvironment)
        {
            actualClassLoader.registerTransformer("cpw.mods.fml.common.asm.transformers.DeobfuscationTransformer");
        }
        downloadMonitor.updateProgressString("Running coremod plugins");
        Map<String,Object> data = new HashMap<String,Object>();
        data.put("mcLocation", mcDir);
        data.put("coremodList", loadPlugins);
        data.put("runtimeDeobfuscationEnabled", !deobfuscatedEnvironment);
        for (IFMLLoadingPlugin plugin : loadPlugins)
        {
            downloadMonitor.updateProgressString("Running coremod plugin %s", plugin.getClass().getSimpleName());
            data.put("coremodLocation", pluginLocations.get(plugin));
            plugin.injectData(data);
            String setupClass = plugin.getSetupClass();
            if (setupClass != null)
            {
                try
                {
                    IFMLCallHook call = (IFMLCallHook) Class.forName(setupClass, true, actualClassLoader).newInstance();
                    Map<String,Object> callData = new HashMap<String, Object>();
                    callData.put("mcLocation", mcDir);
                    callData.put("classLoader", actualClassLoader);
                    callData.put("coremodLocation", pluginLocations.get(plugin));
                    callData.put("deobfuscationFileName", FMLInjectionData.debfuscationDataName());
                    call.injectData(callData);
                    call.call();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
            downloadMonitor.updateProgressString("Coremod plugin %s run successfully", plugin.getClass().getSimpleName());

            String modContainer = plugin.getModContainerClass();
            if (modContainer != null)
            {
                FMLInjectionData.containers.add(modContainer);
            }
        }
        try
        {
            downloadMonitor.updateProgressString("Validating minecraft");
            Class<?> loaderClazz = Class.forName("cpw.mods.fml.common.Loader", true, actualClassLoader);
            Method m = loaderClazz.getMethod("injectData", Object[].class);
            m.invoke(null, (Object)FMLInjectionData.data());
            m = loaderClazz.getMethod("instance");
            m.invoke(null);
            downloadMonitor.updateProgressString("Minecraft validated, launching...");
            downloadBuffer = null;
        }
        catch (Exception e)
        {
            // Load in the Loader, make sure he's ready to roll - this will initialize most of the rest of minecraft here
            System.out.println("A CRITICAL PROBLEM OCCURED INITIALIZING MINECRAFT - LIKELY YOU HAVE AN INCORRECT VERSION FOR THIS FML");
            throw new RuntimeException(e);
        }
    }

    private static void discoverCoreMods(File mcDir, RelaunchClassLoader classLoader, List<IFMLLoadingPlugin> loadPlugins, List<ILibrarySet> libraries)
    {
        downloadMonitor.updateProgressString("Discovering coremods");
        File coreMods = setupCoreModDir(mcDir);
        FilenameFilter ff = new FilenameFilter()
        {
            @Override
            public boolean accept(File dir, String name)
            {
                return name.endsWith(".jar");
            }
        };
        File[] coreModList = coreMods.listFiles(ff);
        Arrays.sort(coreModList);

        for (File coreMod : coreModList)
        {
            downloadMonitor.updateProgressString("Found a candidate coremod %s", coreMod.getName());
            JarFile jar;
            Attributes mfAttributes;
            try
            {
                jar = new JarFile(coreMod);
                if (jar.getManifest() == null)
                {
                    FMLRelaunchLog.warning("Found an un-manifested jar file in the coremods folder : %s, it will be ignored.", coreMod.getName());
                    continue;
                }
                mfAttributes = jar.getManifest().getMainAttributes();
            }
            catch (IOException ioe)
            {
                FMLRelaunchLog.log(Level.SEVERE, ioe, "Unable to read the coremod jar file %s - ignoring", coreMod.getName());
                continue;
            }

            String fmlCorePlugin = mfAttributes.getValue("FMLCorePlugin");
            if (fmlCorePlugin == null)
            {
                FMLRelaunchLog.severe("The coremod %s does not contain a valid jar manifest- it will be ignored", coreMod.getName());
                continue;
            }

//            String className = fmlCorePlugin.replace('.', '/').concat(".class");
//            JarEntry ent = jar.getJarEntry(className);
//            if (ent ==null)
//            {
//                FMLLog.severe("The coremod %s specified %s as it's loading class but it does not include it - it will be ignored", coreMod.getName(), fmlCorePlugin);
//                continue;
//            }
//            try
//            {
//                Class<?> coreModClass = Class.forName(fmlCorePlugin, false, classLoader);
//                FMLLog.severe("The coremods %s specified a class %s that is already present in the classpath - it will be ignored", coreMod.getName(), fmlCorePlugin);
//                continue;
//            }
//            catch (ClassNotFoundException cnfe)
//            {
//                // didn't find it, good
//            }
            try
            {
                classLoader.addURL(coreMod.toURI().toURL());
            }
            catch (MalformedURLException e)
            {
                FMLRelaunchLog.log(Level.SEVERE, e, "Unable to convert file into a URL. weird");
                continue;
            }
            try
            {
                downloadMonitor.updateProgressString("Loading coremod %s", coreMod.getName());
                classLoader.addTransformerExclusion(fmlCorePlugin);
                Class<?> coreModClass = Class.forName(fmlCorePlugin, true, classLoader);
                MCVersion requiredMCVersion = coreModClass.getAnnotation(MCVersion.class);
                String version = "";
                if (requiredMCVersion == null)
                {
                    FMLRelaunchLog.log(Level.WARNING, "The coremod %s does not have a MCVersion annotation, it may cause issues with this version of Minecraft", fmlCorePlugin);
                }
                else
                {
                    version = requiredMCVersion.value();
                }
                if (!"".equals(version) && !FMLInjectionData.mccversion.equals(version))
                {
                    FMLRelaunchLog.log(Level.SEVERE, "The coremod %s is requesting minecraft version %s and minecraft is %s. It will be ignored.", fmlCorePlugin, version, FMLInjectionData.mccversion);
                    continue;
                }
                else if (!"".equals(version))
                {
                    FMLRelaunchLog.log(Level.FINE, "The coremod %s requested minecraft version %s and minecraft is %s. It will be loaded.", fmlCorePlugin, version, FMLInjectionData.mccversion);
                }
                TransformerExclusions trExclusions = coreModClass.getAnnotation(TransformerExclusions.class);
                if (trExclusions!=null)
                {
                    for (String st : trExclusions.value())
                    {
                        classLoader.addTransformerExclusion(st);
                    }
                }
                IFMLLoadingPlugin plugin = (IFMLLoadingPlugin) coreModClass.newInstance();
                loadPlugins.add(plugin);
                pluginLocations .put(plugin, coreMod);
                if (plugin.getLibraryRequestClass()!=null)
                {
                    for (String libName : plugin.getLibraryRequestClass())
                    {
                        libraries.add((ILibrarySet) Class.forName(libName, true, classLoader).newInstance());
                    }
                }
                downloadMonitor.updateProgressString("Loaded coremod %s", coreMod.getName());
            }
            catch (ClassNotFoundException cnfe)
            {
                FMLRelaunchLog.log(Level.SEVERE, cnfe, "Coremod %s: Unable to class load the plugin %s", coreMod.getName(), fmlCorePlugin);
            }
            catch (ClassCastException cce)
            {
                FMLRelaunchLog.log(Level.SEVERE, cce, "Coremod %s: The plugin %s is not an implementor of IFMLLoadingPlugin", coreMod.getName(), fmlCorePlugin);
            }
            catch (InstantiationException ie)
            {
                FMLRelaunchLog.log(Level.SEVERE, ie, "Coremod %s: The plugin class %s was not instantiable", coreMod.getName(), fmlCorePlugin);
            }
            catch (IllegalAccessException iae)
            {
                FMLRelaunchLog.log(Level.SEVERE, iae, "Coremod %s: The plugin class %s was not accessible", coreMod.getName(), fmlCorePlugin);
            }
        }
    }

    /**
     * @param mcDir the minecraft home directory
     * @return the lib directory
     */
    private static File setupLibDir(File mcDir)
    {
        File libDir = new File(mcDir,"lib");
        try
        {
            libDir = libDir.getCanonicalFile();
        }
        catch (IOException e)
        {
            throw new RuntimeException(String.format("Unable to canonicalize the lib dir at %s", mcDir.getName()),e);
        }
        if (!libDir.exists())
        {
            libDir.mkdir();
        }
        else if (libDir.exists() && !libDir.isDirectory())
        {
            throw new RuntimeException(String.format("Found a lib file in %s that's not a directory", mcDir.getName()));
        }
        return libDir;
    }

    /**
     * @param mcDir the minecraft home directory
     * @return the coremod directory
     */
    private static File setupCoreModDir(File mcDir)
    {
        File coreModDir = new File(mcDir,"coremods");
        try
        {
            coreModDir = coreModDir.getCanonicalFile();
        }
        catch (IOException e)
        {
            throw new RuntimeException(String.format("Unable to canonicalize the coremod dir at %s", mcDir.getName()),e);
        }
        if (!coreModDir.exists())
        {
            coreModDir.mkdir();
        }
        else if (coreModDir.exists() && !coreModDir.isDirectory())
        {
            throw new RuntimeException(String.format("Found a coremod file in %s that's not a directory", mcDir.getName()));
        }
        return coreModDir;
    }

    private static void downloadFile(File libFile, String rootUrl,String realFilePath, String hash)
    {
        // --------  MinecraftForge152 Patch start ---------
        if(realFilePath.equals("argo-small-3.2.jar")) {
            rootUrl = "https://repo1.maven.org/maven2/net/sourceforge/argo/argo/3.2/%s";
            realFilePath = "argo-3.2.jar";
        }
        if(realFilePath.equals("guava-14.0-rc3.jar")) {
            rootUrl = "https://repo1.maven.org/maven2/com/google/guava/guava/14.0-rc3/%s";
            realFilePath = "guava-14.0-rc3.jar";
        }
        if(realFilePath.equals("asm-all-4.1.jar")) {
            rootUrl = "https://repo1.maven.org/maven2/org/ow2/asm/asm-all/4.1/%s";
            realFilePath = "asm-all-4.1.jar";
        }
        if(realFilePath.equals("bcprov-jdk15on-148.jar")) {
            rootUrl = "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/1.48/%s";
            realFilePath = "bcprov-jdk15on-1.48.jar";
        }
        if(realFilePath.equals("scala-library.jar")) {
            rootUrl = "https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.10.0/%s";
            realFilePath = "scala-library-2.10.0.jar";
        }
        // --------  MinecraftForge152 Patch end ---------
        try
        {
            URL libDownload = new URL(String.format(rootUrl,realFilePath));
            downloadMonitor.updateProgressString("Downloading file %s", libDownload.toString());
            FMLRelaunchLog.info("Downloading file %s", libDownload.toString());
            URLConnection connection = libDownload.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "FML Relaunch Downloader");
            int sizeGuess = connection.getContentLength();
            performDownload(connection.getInputStream(), sizeGuess, hash, libFile);
            downloadMonitor.updateProgressString("Download complete");
            FMLRelaunchLog.info("Download complete");
        }
        catch (Exception e)
        {
            if (downloadMonitor.shouldStopIt())
            {
                FMLRelaunchLog.warning("You have stopped the downloading operation before it could complete");
                return;
            }
            if (e instanceof RuntimeException) throw (RuntimeException)e;
            FMLRelaunchLog.severe("There was a problem downloading the file %s automatically. Perhaps you " +
            		"have an environment without internet access. You will need to download " +
            		"the file manually or restart and let it try again\n", libFile.getName());
            libFile.delete();
            throw new RuntimeException("A download error occured", e);
        }
    }

    public static List<String> getLibraries()
    {
        return loadedLibraries;
    }

    private static ByteBuffer downloadBuffer = ByteBuffer.allocateDirect(1 << 23);
    static IDownloadDisplay downloadMonitor;

    private static void performDownload(InputStream is, int sizeGuess, String validationHash, File target)
    {
        if (sizeGuess > downloadBuffer.capacity())
        {
            throw new RuntimeException(String.format("The file %s is too large to be downloaded by FML - the coremod is invalid", target.getName()));
        }
        downloadBuffer.clear();

        int bytesRead, fullLength = 0;

        downloadMonitor.resetProgress(sizeGuess);
        try
        {
            downloadMonitor.setPokeThread(Thread.currentThread());
            byte[] smallBuffer = new byte[1024];
            while ((bytesRead = is.read(smallBuffer)) >= 0) {
                downloadBuffer.put(smallBuffer, 0, bytesRead);
                fullLength += bytesRead;
                if (downloadMonitor.shouldStopIt())
                {
                    break;
                }
                downloadMonitor.updateProgress(fullLength);
            }
            is.close();
            downloadMonitor.setPokeThread(null);
            downloadBuffer.limit(fullLength);
            downloadBuffer.position(0);
        }
        catch (InterruptedIOException e)
        {
            // We were interrupted by the stop button. We're stopping now.. clear interruption flag.
            Thread.interrupted();
            return;
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }


        try
        {
            String cksum = generateChecksum(downloadBuffer, target);
            if (cksum.equals(validationHash))
            {
                downloadBuffer.position(0);
                FileOutputStream fos = new FileOutputStream(target);
                fos.getChannel().write(downloadBuffer);
                fos.close();
            }
            else
            {
                throw new RuntimeException(String.format("The downloaded file %s has an invalid checksum %s (expecting %s). The download did not succeed correctly and the file has been deleted. Please try launching again.", target.getName(), cksum, validationHash));
            }
        }
        catch (Exception e)
        {
            if (e instanceof RuntimeException) throw (RuntimeException)e;
            throw new RuntimeException(e);
        }



    }

    // --------  MinecraftForge152 Patch start ---------
    private static String generateChecksum(ByteBuffer buffer, File libFile)
    {
        // Fake scala-library.jar fingerprint
        if(libFile.getName().equals("scala-library.jar")) {
            if(CertificateHelper.getFingerprint(buffer).equals("43c6d98b445187c6b459a582c774ffb025120ef4")) {
                return "458d046151ad179c85429ed7420ffb1eaf6ddf85";
            }
        }
        // Fake argo-small-3.2.jar fingerprint
        if(libFile.getName().equals("argo-small-3.2.jar")) {
            if(CertificateHelper.getFingerprint(buffer).equals("b671cb3bbe10c0bc5445c657dfea6799355991db")) {
                return "58912ea2858d168c50781f956fa5b59f0f7c6b51";
            }
        }
        // Fake deobfuscation_data_1.5.2.zip fingerprint
        // First, check whether joined.srg fingerprint is true
        if(libFile.getName().equals("deobfuscation_data_1.5.2.zip")) {
            try(ZipInputStream zip = new ZipInputStream(new FileInputStream(libFile))) {
                ZipEntry entry = zip.getNextEntry();
                if(entry != null && entry.getName().equals("joined.srg")) {
                    ByteBuffer buffer2 = ByteBuffer.allocate(1586285);
                    byte[] buff = new byte[8192];
                    int len;
                    while((len = zip.read(buff)) != -1) {
                        buffer2.put(buff, 0, len);
                    }
                    buffer2.flip();
                    if(CertificateHelper.getFingerprint(buffer2).equals("8f2ab76d688108fd8a43307a306cbd4a4bbcdbc5")) {
                        return "446e55cd986582c70fcf12cb27bc00114c5adfd9";
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return CertificateHelper.getFingerprint(buffer);
    }
    // --------  MinecraftForge152 Patch end ---------

    private static String generateChecksum(ByteBuffer buffer)
    {
        return CertificateHelper.getFingerprint(buffer);
    }
}
