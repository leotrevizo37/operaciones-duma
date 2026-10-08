package com.duma.core.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
  public static final String ATTRIBUTE = "duma.requestId";
  private static final String MDC_KEY = "requestId";
  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private final SystemLogService logs;

  public RequestIdFilter(SystemLogService logs) {
    this.logs = logs;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader("X-Request-Id");
    String id =
        incoming != null && SAFE.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, id);
    response.setHeader("X-Request-Id", id);
    MDC.put(MDC_KEY, id);
    long start = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      Principal principal = request.getUserPrincipal();
      try {
        logs.recordRequest(
            request.getMethod(),
            request.getRequestURI(),
            response.getStatus(),
            (System.nanoTime() - start) / 1_000_000,
            id,
            principal == null ? null : principal.getName(),
            request.getRemoteAddr(),
            request.getHeader("User-Agent"));
      } catch (TaskRejectedException rejected) {
        // A full executor queue rejects synchronously here: TaskRejectedException is unchecked and
        // the @Async interceptor does not wrap the dispatch, so it would otherwise escape into the
        // filter chain of a request whose response is already committed.
        log.warn("system_log_request_dropped requestId={}", id);
      }
      MDC.remove(MDC_KEY);
    }
  }

  public static String get(HttpServletRequest request) {
    Object value = request.getAttribute(ATTRIBUTE);
    return value == null ? null : value.toString();
  }
}
