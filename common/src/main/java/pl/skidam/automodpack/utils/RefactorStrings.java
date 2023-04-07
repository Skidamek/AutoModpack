package pl.skidam.automodpack.utils;

public class RefactorStrings {

    public static String getETA(double ETA) {
        int hours = (int) (ETA / 3600);
        int minutes = (int) ((ETA % 3600) / 60);
        int seconds = (int) (ETA % 60);

        if (hours > 0) {
            return String.format("%dh %02dm %02ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
