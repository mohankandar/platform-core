package com.documentplatform.monitoring.spring;

import com.documentplatform.errors.ReferenceId;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * For every request a new reference ({@link ReferenceId#next()}): it is the request's reference
 * ({@link ReferenceId#current()}, so a failure logged while handling it carries it), is answered in the
 * {@code X-Request-Id} header, and is in the logging MDC as {@code ref} while the request is handled.
 *
 * <p>P045, SEC-21: a caller never chooses the reference. An {@code X-Request-Id} the caller sent, when it is a short safe
 * token, is kept apart in the MDC as {@code requestId}, so both sides' logs can be matched; any other is ignored.
 */
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10) // before Spring Security (SEC-55)
public final class RequestReferenceFilter implements Filter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "ref";
    /** The caller's own request ID in the MDC. */
    public static final String MDC_REQUEST_ID = "requestId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final boolean MDC = org.springframework.util.ClassUtils.isPresent("org.slf4j.MDC",
            RequestReferenceFilter.class.getClassLoader());

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String given = req instanceof HttpServletRequest h ? h.getHeader(HEADER) : null;
        boolean keep = given != null && SAFE.matcher(given).matches();
        String ref = ReferenceId.next();
        if (res instanceof HttpServletResponse h) h.setHeader(HEADER, ref);
        String before = ReferenceId.current();
        ReferenceId.setCurrent(ref);
        if (MDC) {
            org.slf4j.MDC.put(MDC_KEY, ref);
            if (keep) org.slf4j.MDC.put(MDC_REQUEST_ID, given);
        }
        try {
            chain.doFilter(req, res);
        } finally {
            ReferenceId.setCurrent(before);
            if (MDC) {
                org.slf4j.MDC.remove(MDC_KEY);
                org.slf4j.MDC.remove(MDC_REQUEST_ID);
            }
        }
    }
}
