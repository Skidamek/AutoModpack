package pl.skidam.automodpack_core.utils;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class ManifestReader {

    public static final String VERSION_FIELD = "AutoModpack-Version";

    public static String getAutoModpackVersion() {
        try {
            Enumeration<URL> resources = ManifestReader.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                Manifest manifest = new Manifest(resources.nextElement().openStream());
                Attributes mainAttributes = manifest.getMainAttributes();
                String version = mainAttributes.getValue(VERSION_FIELD);
                if (version != null && !version.isEmpty()) {
                    return version;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        throw new RuntimeException("Couldn't find AutoModpack version in manifest file.");
    }

    public static String readForgeModVersion(InputStream fileStream) {
        try {
            Manifest manifest = new Manifest(fileStream);
            Attributes mainAttributes = manifest.getMainAttributes();
            return mainAttributes.getValue("Implementation-Version");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
