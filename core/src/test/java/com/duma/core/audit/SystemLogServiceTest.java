package com.duma.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

import com.duma.core.config.ModuleProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(OutputCaptureExtension.class)
class SystemLogServiceTest {

  @Test
  void writesTheConfiguredModuleIdAsApplicationId() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    ModuleProperties properties = new ModuleProperties();
    properties.getModule().setId("lecturas");

    new SystemLogService(jdbc, properties)
        .recordRequest("GET", "/api/lecturas", 200, 12L, "req-1", "actor", "127.0.0.1", "agent");

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(anyString(), arguments.capture());
    assertThat(arguments.getValue()[0]).isEqualTo("lecturas");
  }

  @Test
  void aWriteFailureDoesNotEscapeRecordRequest() {
    SystemLogService service = serviceThatCannotWrite();

    assertThatCode(
            () ->
                service.recordRequest(
                    "GET", "/api/lecturas", 200, 12L, "req-1", "actor", "127.0.0.1", "agent"))
        .doesNotThrowAnyException();
  }

  @Test
  void aWriteFailureDoesNotEscapeRecordClient() {
    SystemLogService service = serviceThatCannotWrite();

    assertThatCode(
            () ->
                service.recordClient(
                    "VIEW_RENDERED", "SUCCESS", "actor", "carlsjr", 12L,
                    new MockHttpServletRequest()))
        .doesNotThrowAnyException();
  }

  @Test
  void aWriteFailureDoesNotEscapeRecordBusinessAction() {
    SystemLogService service = serviceThatCannotWrite();

    assertThatCode(
            () ->
                service.recordBusinessAction(
                    "SMARTAUDITS_REVIEW_APPROVED", "actor", "carlsjr",
                    new MockHttpServletRequest()))
        .doesNotThrowAnyException();
  }

  @Test
  void theSynchronousMethodsReportThatTheRowWasWritten() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    ModuleProperties properties = new ModuleProperties();
    properties.getModule().setId("smartaudits");
    SystemLogService service = new SystemLogService(jdbc, properties);

    assertThat(
            service.recordClient(
                "VIEW_RENDERED", "SUCCESS", "actor", "carlsjr", 12L, new MockHttpServletRequest()))
        .isTrue();
    assertThat(
            service.recordBusinessAction(
                "SMARTAUDITS_REVIEW_APPROVED", "actor", "carlsjr", new MockHttpServletRequest()))
        .isTrue();
  }

  @Test
  void theSynchronousMethodsReportThatTheRowWasNotWritten() {
    SystemLogService service = serviceThatCannotWrite();

    assertThat(
            service.recordClient(
                "VIEW_RENDERED", "SUCCESS", "actor", "carlsjr", 12L, new MockHttpServletRequest()))
        .isFalse();
    assertThat(
            service.recordBusinessAction(
                "SMARTAUDITS_REVIEW_APPROVED", "actor", "carlsjr", new MockHttpServletRequest()))
        .isFalse();
  }

  @Test
  void theFailedWriteWarningNamesTheRequestId(CapturedOutput output) {
    serviceThatCannotWrite()
        .recordRequest("GET", "/api/lecturas", 200, 12L, "req-88", "actor", "127.0.0.1", "agent");

    assertThat(output).contains("system_log_write_failed").contains("requestId=req-88");
  }

  private static SystemLogService serviceThatCannotWrite() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.doThrow(new RecoverableDataAccessException("audit host unreachable"))
        .when(jdbc)
        // any(Object[].class), not any(): a bare any() in the varargs position matches exactly
        // one argument and would silently never match this 12-parameter call.
        .update(anyString(), ArgumentMatchers.any(Object[].class));
    ModuleProperties properties = new ModuleProperties();
    properties.getModule().setId("lecturas");
    return new SystemLogService(jdbc, properties);
  }
}
