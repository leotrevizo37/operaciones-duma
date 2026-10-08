package com.duma.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

  @BeforeEach
  void startWithoutInheritedDiagnosticContext() {
    MDC.clear();
  }

  @Test
  void keepsServingTheRequestWhenTheAuditQueueRejectsTheEvent() throws Exception {
    SystemLogService logs = Mockito.mock(SystemLogService.class);
    Mockito.doThrow(new TaskRejectedException("executor queue is full"))
        .when(logs)
        .recordRequest(any(), any(), anyInt(), anyLong(), any(), any(), any(), any());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RequestIdFilter(logs).doFilterInternal(request, response, new MockFilterChain());

    assertThat(response.getHeader("X-Request-Id")).isNotBlank();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void adoptsAnInboundRequestIdThatMatchesTheGrammar() throws Exception {
    SystemLogService logs = Mockito.mock(SystemLogService.class);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
    request.addHeader("X-Request-Id", "edge-Gateway_7.2");
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RequestIdFilter(logs).doFilterInternal(request, response, new MockFilterChain());

    assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo("edge-Gateway_7.2");
    assertThat(response.getHeader("X-Request-Id")).isEqualTo("edge-Gateway_7.2");
    assertThat(recordedRequestId(logs)).isEqualTo("edge-Gateway_7.2");
  }

  @Test
  void replacesAnInboundRequestIdThatFailsTheGrammarWithAGeneratedUuid() throws Exception {
    SystemLogService logs = Mockito.mock(SystemLogService.class);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
    request.addHeader("X-Request-Id", "a b:c");
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RequestIdFilter(logs).doFilterInternal(request, response, new MockFilterChain());

    String effective = response.getHeader("X-Request-Id");
    assertThat(effective).isNotEqualTo("a b:c");
    // Round-trip instead of a bare fromString call: UUID.fromString accepts short groups such as
    // "1-1-1-1-1", so only re-rendering proves the id is a canonical UUID.
    assertThat(UUID.fromString(effective)).hasToString(effective);
    assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo(effective);
    assertThat(recordedRequestId(logs)).isEqualTo(effective);
  }

  @Test
  void publishesTheRequestIdInTheDiagnosticContextForTheChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
    request.addHeader("X-Request-Id", "mdc-Probe_3");
    AtomicReference<String> seenByTheChain = new AtomicReference<>();

    new RequestIdFilter(Mockito.mock(SystemLogService.class))
        .doFilterInternal(
            request,
            new MockHttpServletResponse(),
            (chainRequest, chainResponse) -> seenByTheChain.set(MDC.get("requestId")));

    assertThat(seenByTheChain.get()).isEqualTo("mdc-Probe_3");
  }

  @Test
  void clearsTheDiagnosticContextOnceTheRequestIsServed() throws Exception {
    new RequestIdFilter(Mockito.mock(SystemLogService.class))
        .doFilterInternal(
            new MockHttpServletRequest("GET", "/api/devices"),
            new MockHttpServletResponse(),
            new MockFilterChain());

    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  void clearsTheDiagnosticContextWhenTheChainThrows() {
    RequestIdFilter filter = new RequestIdFilter(Mockito.mock(SystemLogService.class));

    assertThatThrownBy(
            () ->
                filter.doFilterInternal(
                    new MockHttpServletRequest("GET", "/api/devices"),
                    new MockHttpServletResponse(),
                    (request, response) -> {
                      throw new ServletException("downstream blew up");
                    }))
        .isInstanceOf(ServletException.class);

    assertThat(MDC.get("requestId")).isNull();
  }

  private static String recordedRequestId(SystemLogService logs) {
    ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
    Mockito.verify(logs)
        .recordRequest(any(), any(), anyInt(), anyLong(), requestId.capture(), any(), any(), any());
    return requestId.getValue();
  }
}
