package com;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

public class NewContainerTest {
    List<String> items = Arrays.asList("Hello World");

    public void test() {
        boolean hasItems = (items != null && !items.isEmpty());

        // Indirectly implies items != null
        if (items != null && !items.isEmpty()) {
            TreeSet<?> set = new TreeSet<>(items);
        }
    }
}
