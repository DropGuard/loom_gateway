package com.github.dropguard.loom.filter;

import java.util.List;

/**
 * Builds and executes the filter chain. Does not catch exceptions — all exceptions propagate to the
 * system boundary (GatewayRouter.handle()).
 */
public class FilterChain {

    private final List<Filter> filters;

    public FilterChain(List<Filter> filters) {
        this.filters = List.copyOf(filters);
    }

    public void execute(FilterContext context, Filter.Next innermost) throws Exception {
        Filter.Next chain = innermost;
        for (int i = filters.size() - 1; i >= 0; i--) {
            Filter filter = filters.get(i);
            Filter.Next next = chain;
            chain = () -> filter.apply(context, next);
        }
        chain.run();
    }

    public int size() {
        return filters.size();
    }
}
