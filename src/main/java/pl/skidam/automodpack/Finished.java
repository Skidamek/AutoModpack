package pl.skidam.automodpack;

public class Finished {

    public Finished() {
        Thread.currentThread().setName("AutoModpack");
        Thread.currentThread().setPriority(10);

        System.out.println("AutoModpack -- Here you are!");
    }
}
