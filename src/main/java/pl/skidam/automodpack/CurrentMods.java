package pl.skidam.automodpack;

import java.io.File;
import java.io.FilenameFilter;

public class CurrentMods implements Runnable {
    @Override
    public void run() {

        File[] fileList = getFileList("./mods");

        for(File file : fileList) {
            System.out.println(file.getName());
        }
    }

    private static File[] getFileList(String dirPath) {
        File dir = new File(dirPath);

        File[] fileList = dir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });

        System.out.println("CURRENT --  " + fileList);

        return fileList;
    }
}


