package com.gateway.filter;

/**
 * Gateway filter interface.
 * Filters are executed in ascending order of their priority.
 */
public interface Filter {

    /**
     * Returns the execution order. Lower values execute first.
     */
    int order();

    /**
     * Execute the filter logic.
     * 
     * @param context the filter context
     * @return FilterResult indicating whether to continue the chain
     */
    FilterResult filter(FilterContext context);
}
