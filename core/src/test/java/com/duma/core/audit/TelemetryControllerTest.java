package com.duma.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

import com.duma.core.config.ModuleProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

class TelemetryControllerTest {

  @Test
  void stillAcceptsTheEventWhenTheAuditRowCannotBeWritten() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.doThrow(new RecoverableDataAccessException("audit host unreachable"))
        .when(jdbc)
        // any(Object[].class), not any(): a bare any() in the varargs position matches exactly
        // one argument and would silently never match this 12-parameter call.
        .update(anyString(), ArgumentMatchers.any(Object[].class));
    ModuleProperties properties = new ModuleProperties();
    properties.getModule().setId("smartaudits");

    ResponseEntity<Void> response =
        new TelemetryController(new SystemLogService(jdbc, properties))
            .record(
                new TelemetryController.Request("VIEW_RENDERED", "SUCCESS", "carlsjr", 12L),
                null,
                new MockHttpServletRequest());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }
}
