package com;
public class NestedNullTest {
    public boolean checkNull(String str) {
        return str != null;
    }

    public void test() {
        String str = "Hello World";

        if (checkNull(str)) {
            ;
        }

        if (!checkNull(str)) {
            ;
        }
    }
}
