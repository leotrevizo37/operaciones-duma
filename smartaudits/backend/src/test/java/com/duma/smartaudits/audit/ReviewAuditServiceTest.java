package com.duma.smartaudits.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

import com.duma.core.audit.RequestIdFilter;
import com.duma.core.audit.SystemLogService;
import com.duma.core.config.ModuleProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

class ReviewAuditServiceTest {

  @Test
  void recordsTheApprovalWithItsOwnEventNameAndTenant() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    ModuleProperties properties = new ModuleProperties();
    properties.getModule().setId("smartaudits");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(RequestIdFilter.ATTRIBUTE, "req-7");

    new ReviewAuditService(new SystemLogService(jdbc, properties))
        .recordApproval("reviewer@duma", false, request);

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(anyString(), arguments.capture());
    Object[] row = arguments.getValue();
    assertThat(row[0]).isEqualTo("smartaudits");
    assertThat(row[1]).isEqualTo("BUSINESS_ACTION");
    assertThat(row[2]).isEqualTo("SMARTAUDITS_REVIEW_APPROVED");
    assertThat(row[3]).isEqualTo("SUCCESS");
    assertThat(row[5]).isEqualTo("req-7");
    assertThat(row[6]).isEqualTo("reviewer@duma");
    assertThat(row[7]).isEqualTo("carlsjr");
  }

  @Test
  void reportsWhetherTheApprovalRowWasWritten() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    ModuleProperties properties = new ModuleProperties();
    properties.getModule().setId("smartaudits");

    assertThat(
            new ReviewAuditService(new SystemLogService(jdbc, properties))
                .recordApproval("reviewer@duma", false, new MockHttpServletRequest()))
        .isTrue();

    Mockito.doThrow(new RecoverableDataAccessException("audit host unreachable"))
        .when(jdbc)
        // any(Object[].class), not any(): a bare any() in the varargs position matches exactly
        // one argument and would silently never match this 12-parameter call.
        .update(anyString(), ArgumentMatchers.any(Object[].class));

    assertThat(
            new ReviewAuditService(new SystemLogService(jdbc, properties))
                .recordApproval("reviewer@duma", false, new MockHttpServletRequest()))
        .isFalse();
  }

  @Test
  void writesTheRowAlsoWhenTheApprovalWasIdempotent() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    ModuleProperties properties = new ModuleProperties();
    properties.getModule().setId("smartaudits");

    new ReviewAuditService(new SystemLogService(jdbc, properties))
        .recordApproval("reviewer@duma", true, new MockHttpServletRequest());

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(anyString(), arguments.capture());
    assertThat(arguments.getValue()[2]).isEqualTo("SMARTAUDITS_REVIEW_APPROVED");
  }
}
