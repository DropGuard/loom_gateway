package com.github.dropguard.loom.filter;

public interface Filter {
    void apply(FilterContext context, Next next) throws Exception;

    @FunctionalInterface
    interface Next {
        void run() throws Exception;
    }
}
