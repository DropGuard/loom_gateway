package com.gateway.filter;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;

/**
 * Executes filters in ascending order of their priority.
 * Supports short-circuit return when a filter stops the chain.
 */
@Singleton
public class FilterChain {

    private static final Logger LOG = Logger.getLogger(FilterChain.class);
    private final List<Filter> filters;

    @Inject
    public FilterChain(Instance<Filter> filterInstances) {
        this.filters = filterInstances.stream()
            .sorted(Comparator.comparingInt(Filter::order))
            .toList();
        LOG.infof("Loaded %d filters", filters.size());
    }

    /**
     * Execute all filters in order.
     * 
     * @param context the filter context
     * @return true if all filters passed and chain should continue, false if short-circuited
     */
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
}
