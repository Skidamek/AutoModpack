package pl.skidam.automodpack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;

public class LatestMods implements Runnable {

    @Override
    public void run() {

        StringBuilder sb = new StringBuilder();

        try {
            URLConnection connection = new URL("http://130.61.233.54/download/mods.txt").openConnection();
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                sb.append(line);
                System.out.println("LATEST  --  " + line);

                String[] latestmods = new String[12];

//                    latestmods[] = line;

            }
            in.close();
        } catch (IOException e) {
            // handle exception
        }




    }

}

