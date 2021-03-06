package com.codahale.metrics.servlet;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

import javax.servlet.*;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * {@link Filter} implementation which captures request information and a breakdown of the response
 * codes being returned.
 */
public abstract class AbstractInstrumentedFilter implements Filter {
    private final String otherMetricName;
    private final Map<Integer, String> meterNamesByStatusCode;
    private final String registryAttribute;

    // initialized after call of init method
    private ConcurrentMap<Integer, Meter> metersByStatusCode;
    private ConcurrentMap<Integer, Meter> metersByStatusCodeGroup;
    private Meter otherMeter;
    private Counter activeRequests;
    private Timer requestTimer;


    /**
     * Creates a new instance of the filter.
     *
     * @param registryAttribute      the attribute used to look up the metrics registry in the
     *                               servlet context
     * @param meterNamesByStatusCode A map, keyed by status code, of meter names that we are
     *                               interested in.
     * @param otherMetricName        The name used for the catch-all meter.
     */
    protected AbstractInstrumentedFilter(String registryAttribute,
                                         Map<Integer, String> meterNamesByStatusCode,
                                         String otherMetricName) {
        this.registryAttribute = registryAttribute;
        this.otherMetricName = otherMetricName;
        this.meterNamesByStatusCode = meterNamesByStatusCode;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        final MetricRegistry metricsRegistry = getMetricsFactory(filterConfig);

        this.metersByStatusCode = new ConcurrentHashMap<Integer, Meter>(meterNamesByStatusCode
                .size());
        for (Entry<Integer, String> entry : meterNamesByStatusCode.entrySet()) {
            metersByStatusCode.put(entry.getKey(),
                    metricsRegistry.meter(name(AbstractInstrumentedFilter.class, entry.getValue())));
        }


        this.metersByStatusCodeGroup = new ConcurrentHashMap<Integer, Meter>(5);
        for (int i = 1; i <= 5; i++) { // http status 1xx to 5xx
            this.metersByStatusCodeGroup.put(i,
                    metricsRegistry.meter(name(AbstractInstrumentedFilter.class, i + "xx")));
        }

        this.otherMeter = metricsRegistry.meter(name(AbstractInstrumentedFilter.class,
                                                 otherMetricName));
        this.activeRequests = metricsRegistry.counter(name(AbstractInstrumentedFilter.class,
                                                           "activeRequests"));
        this.requestTimer = metricsRegistry.timer(name(AbstractInstrumentedFilter.class,
                                                       "requests"));
    }

    private MetricRegistry getMetricsFactory(FilterConfig filterConfig) {
        final MetricRegistry metricsRegistry;

        final Object o = filterConfig.getServletContext().getAttribute(this.registryAttribute);
        if (o instanceof MetricRegistry) {
            metricsRegistry = (MetricRegistry) o;
        } else {
            metricsRegistry = new MetricRegistry();
        }
        return metricsRegistry;
    }

    @Override
    public void destroy() {
        
    }

    @Override
    public void doFilter(ServletRequest request,
                         ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        final StatusExposingServletResponse wrappedResponse =
                new StatusExposingServletResponse((HttpServletResponse) response);
        activeRequests.inc();
        final Timer.Context context = requestTimer.time();
        try {
            chain.doFilter(request, wrappedResponse);
        } finally {
            context.stop();
            activeRequests.dec();
            markMetersForStatusCode(wrappedResponse.getStatus());
        }
    }

    private void markMetersForStatusCode(int status) {
        final Meter metric = metersByStatusCode.get(status);
        if (metric != null) {
            metric.mark();
        }
        
        final Meter groupMetric = metersByStatusCodeGroup.get(status / 100);
        if (groupMetric != null) {
            groupMetric.mark();
        }
        
        if (metric == null && groupMetric == null) {
            otherMeter.mark();
        }
    }

    private static class StatusExposingServletResponse extends HttpServletResponseWrapper {
        // The Servlet spec says: calling setStatus is optional, if no status is set, the default is 200.
        private int httpStatus = 200;

        public StatusExposingServletResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void sendError(int sc) throws IOException {
            httpStatus = sc;
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            httpStatus = sc;
            super.sendError(sc, msg);
        }

        @Override
        public void setStatus(int sc) {
            httpStatus = sc;
            super.setStatus(sc);
        }

        public int getStatus() {
            return httpStatus;
        }
    }
}
