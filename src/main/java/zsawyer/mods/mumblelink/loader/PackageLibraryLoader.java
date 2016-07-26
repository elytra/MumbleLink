/*
 mod_MumbleLink - Positional Audio Communication for Minecraft with Mumble
 Copyright 2011-2013 zsawyer (http://sourceforge.net/users/zsawyer)

 This file is part of mod_MumbleLink
 (http://sourceforge.net/projects/modmumblelink/).

 mod_MumbleLink is free software: you can redistribute it and/or modify
 it under the terms of the GNU Lesser General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 mod_MumbleLink is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Lesser General Public License for more details.

 You should have received a copy of the GNU Lesser General Public License
 along with mod_MumbleLink.  If not, see <http://www.gnu.org/licenses/>.

 */

package zsawyer.mods.mumblelink.loader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;

import zsawyer.mumble.jna.LinkAPILibrary;

/**
 * @author zsawyer
 */
public class PackageLibraryLoader implements LibraryLoader {

    @Override
    public LinkAPILibrary loadLibrary(String libraryName)
            throws UnsatisfiedLinkError {
    	File f;
		try {
			f = extractFromResourcePath(libraryName, null);
		} catch (IOException e) {
			throw ((UnsatisfiedLinkError)new UnsatisfiedLinkError().initCause(e));
		}
    	NativeLibrary loadedLibrary = NativeLibrary.getInstance(f.getAbsolutePath());

        if (loadedLibrary != null) {
            LinkAPILibrary libraryInstance = (LinkAPILibrary) Native
                    .loadLibrary(f.getAbsolutePath(), LinkAPILibrary.class);
            if (libraryInstance != null) {
                return libraryInstance;
            }
        }

        throw new UnsatisfiedLinkError(
                "Required library could not be loaded, available libraries are incompatible!");

    }
    
    public static File extractFromResourcePath(String name, ClassLoader loader) throws IOException {
        final boolean DEBUG = false;
        if (loader == null) {
            loader = Thread.currentThread().getContextClassLoader();
            // Context class loader is not guaranteed to be set
            if (loader == null) {
                loader = Native.class.getClassLoader();
            }
        }
        if (DEBUG) {
            System.out.println("Looking in classpath from " + loader + " for " + name);
        }
        String resourcePrefix = getNativeLibraryResourcePrefix(Platform.getOSType(), System.getProperty("os.arch"), System.getProperty("os.name"));
        String libname = name.startsWith("/") ? name : mapSharedLibraryName(name);
        String resourcePath = name.startsWith("/") ? name : resourcePrefix + "/" + libname;
        if (resourcePath.startsWith("/")) {
            resourcePath = resourcePath.substring(1);
        }
        URL url = loader.getResource(resourcePath);
        if (url == null && resourcePath.startsWith(resourcePrefix)) {
            // If not found with the standard resource prefix, try without it
            url = loader.getResource(libname);
        }
        if (url == null) {
            String path = System.getProperty("java.class.path");
            if (loader instanceof URLClassLoader) {
                path = Arrays.asList(((URLClassLoader)loader).getURLs()).toString();
            }
            throw new IOException("Native library (" + resourcePath + ") not found in resource path (" + path + ")");
        }
        if (DEBUG) {
            System.out.println("Found library resource at " + url);
        }

        File lib = null;
        if (url.getProtocol().toLowerCase().equals("file")) {
            try {
                lib = new File(new URI(url.toString()));
            }
            catch(URISyntaxException e) {
                lib = new File(url.getPath());
            }
            if (DEBUG) {
                System.out.println("Looking in " + lib.getAbsolutePath());
            }
            if (!lib.exists()) {
                throw new IOException("File URL " + url + " could not be properly decoded");
            }
        }
        else if (!Boolean.getBoolean("jna.nounpack")) {
            InputStream is = loader.getResourceAsStream(resourcePath);
            if (is == null) {
                throw new IOException("Can't obtain InputStream for " + resourcePath);
            }

            FileOutputStream fos = null;
            try {
                // Suffix is required on windows, or library fails to load
                // Let Java pick the suffix, except on windows, to avoid
                // problems with Web Start.
                File dir = getTempDir();
                lib = File.createTempFile("jna", Platform.isWindows()?".dll":null, dir);
                if (!Boolean.getBoolean("jnidispatch.preserve")) {
                    lib.deleteOnExit();
                }
                fos = new FileOutputStream(lib);
                int count;
                byte[] buf = new byte[1024];
                while ((count = is.read(buf, 0, buf.length)) > 0) {
                    fos.write(buf, 0, count);
                }
            }
            catch(IOException e) {
                throw new IOException("Failed to create temporary file for " + name + " library: " + e.getMessage());
            }
            finally {
                try { is.close(); } catch(IOException e) { }
                if (fos != null) {
                    try { fos.close(); } catch(IOException e) { }
                }
            }
        }
        return lib;
    }
    
    static String mapSharedLibraryName(String libName) {
        if (Platform.isMac()) {
            if (libName.startsWith("lib")
                && (libName.endsWith(".dylib")
                    || libName.endsWith(".jnilib"))) {
                return libName;
            }
            String name = System.mapLibraryName(libName);
            // On MacOSX, System.mapLibraryName() returns the .jnilib extension
            // (the suffix for JNI libraries); ordinarily shared libraries have
            // a .dylib suffix
            if (name.endsWith(".jnilib")) {
                return name.substring(0, name.lastIndexOf(".jnilib")) + ".dylib";
            }
            return name;
        }
        else if (Platform.isLinux() || Platform.isFreeBSD()) {
            if (isVersionedName(libName) || libName.endsWith(".so")) {
                // A specific version was requested - use as is for search
                return libName;
            }
        }
        else if (Platform.isWindows()) {
            if (libName.endsWith(".drv") || libName.endsWith(".dll")) {
                return libName;
            }
        }

        return System.mapLibraryName(libName);
    }
    
    private static boolean isVersionedName(String name) {
        if (name.startsWith("lib")) {
            int so = name.lastIndexOf(".so.");
            if (so != -1 && so + 4 < name.length()) {
                for (int i=so+4;i < name.length();i++) {
                    char ch = name.charAt(i);
                    if (!Character.isDigit(ch) && ch != '.') {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    static File getTempDir() throws IOException {
        File jnatmp;
        String prop = System.getProperty("jna.tmpdir");
        if (prop != null) {
            jnatmp = new File(prop);
            jnatmp.mkdirs();
        }
        else {
            File tmp = new File(System.getProperty("java.io.tmpdir"));
            // Loading DLLs via System.load() under a directory with a unicode
            // name will fail on windows, so use a hash code of the user's
            // name in case the user's name contains non-ASCII characters
            jnatmp = new File(tmp, "jna-" + System.getProperty("user.name").hashCode());
            jnatmp.mkdirs();
            if (!jnatmp.exists() || !jnatmp.canWrite()) {
                jnatmp = tmp;
            }
        }
        if (!jnatmp.exists()) {
            throw new IOException("JNA temporary directory '" + jnatmp + "' does not exist");
        }
        if (!jnatmp.canWrite()) {
            throw new IOException("JNA temporary directory '" + jnatmp + "' is not writable");
        }
        return jnatmp;
    }
    
    static String getNativeLibraryResourcePrefix(int osType, String arch, String name) {
        String osPrefix;
        arch = getCanonicalArchitecture(arch);
        switch(osType) {
        case Platform.WINDOWS:
            osPrefix = "win32-" + arch;
            break;
        case Platform.WINDOWSCE:
            osPrefix = "w32ce-" + arch;
            break;
        case Platform.MAC:
            osPrefix = "darwin";
            break;
        case Platform.LINUX:
            osPrefix = "linux-" + arch;
            break;
        case Platform.SOLARIS:
            osPrefix = "sunos-" + arch;
            break;
        case Platform.FREEBSD:
            osPrefix = "freebsd-" + arch;
            break;
        case Platform.OPENBSD:
            osPrefix = "openbsd-" + arch;
            break;
        default:
            osPrefix = name.toLowerCase();
            int space = osPrefix.indexOf(" ");
            if (space != -1) {
                osPrefix = osPrefix.substring(0, space);
            }
            osPrefix += "-" + arch;
            break;
        }
        return osPrefix;
    }
    
    static String getCanonicalArchitecture(String arch) {
	arch = arch.toLowerCase().trim();
        if ("powerpc".equals(arch)) {
            arch = "ppc";
        }
        else if ("powerpc64".equals(arch)) {
            arch = "ppc64";
        }
        else if ("i386".equals(arch) || "i686".equals(arch)) {
            arch = "x86";
        }
        else if ("x86_64".equals(arch) || "amd64".equals(arch)) {
            arch = "x86-64";
        }
	// Work around OpenJDK mis-reporting os.arch
	// https://bugs.openjdk.java.net/browse/JDK-8073139
	if ("ppc64".equals(arch) && "little".equals(System.getProperty("sun.cpu.endian"))) {
	    arch = "ppc64le";
	}
	return arch;
    }
    
}
