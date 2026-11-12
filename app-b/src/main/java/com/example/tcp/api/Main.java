package com.example.tcp.api;

import io.micronaut.runtime.Micronaut;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Micronaut.run(Main.class, args);
    }
}
