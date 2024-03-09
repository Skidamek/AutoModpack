package pl.skidam.automodpack_loader_core.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Changelogs {
    public Map<String, List<String>> changesAddedList = new HashMap<>(); // <file name, main page urls>
    public Map<String, List<String>> changesDeletedList = new HashMap<>(); // <file name, main page urls>
}
