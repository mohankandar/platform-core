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
 * For every request: the {@code X-Request-Id} header is echoed back, or one is made ({@link ReferenceId#next()}); it
 * is the request's reference ({@link ReferenceId#current()}, so a failure logged while handling it carries it) and is
 * in the logging MDC as {@code ref} while the request is handled. A header that is not a short safe token is ignored
 * and a new reference made (a header never carries values into the log).
 */
public final class RequestReferenceFilter implements Filter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "ref";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final boolean MDC = org.springframework.util.ClassUtils.isPresent("org.slf4j.MDC",
            RequestReferenceFilter.class.getClassLoader());

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String given = req instanceof HttpServletRequest h ? h.getHeader(HEADER) : null;
        String ref = given != null && SAFE.matcher(given).matches() ? given : ReferenceId.next();
        if (res instanceof HttpServletResponse h) h.setHeader(HEADER, ref);
        String before = ReferenceId.current();
        ReferenceId.setCurrent(ref);
        if (MDC) org.slf4j.MDC.put(MDC_KEY, ref);
        try {
            chain.doFilter(req, res);
        } finally {
            ReferenceId.setCurrent(before);
            if (MDC) org.slf4j.MDC.remove(MDC_KEY);
        }
    }
}
