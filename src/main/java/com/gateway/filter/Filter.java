package com.gateway.filter;

public interface Filter {
    FilterResult filter(FilterContext context);
}
