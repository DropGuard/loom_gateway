package com.gateway.filter;

import org.jboss.logging.Logger;

import java.util.List;

public class FilterChain {

    private static final Logger LOG = Logger.getLogger(FilterChain.class);
    private final List<Filter> filters;

    public FilterChain(List<Filter> filters) {
        this.filters = List.copyOf(filters);
    }

    public boolean execute(FilterContext context) {
        for (Filter filter : filters) {
            try {
                FilterResult result = filter.filter(context);
                if (!result.continueChain()) {
                    LOG.debugf("Filter %s stopped the chain with status %d",
                        filter.getClass().getSimpleName(), result.statusCode());
                    result.writeResponse(context.response());
                    return false;
                }
            } catch (Exception e) {
                LOG.errorf(e, "Filter %s threw exception", filter.getClass().getSimpleName());
                FilterResult.stop(500, "Internal gateway error").writeResponse(context.response());
                return false;
            }
        }
        return true;
    }

    public int size() {
        return filters.size();
    }
}
