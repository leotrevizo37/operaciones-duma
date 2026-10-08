package com.duma.smartaudits.audit;

import com.duma.core.audit.SystemLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ReviewAuditService {
  private static final Logger log = LoggerFactory.getLogger(ReviewAuditService.class);
  private final SystemLogService logs;

  public ReviewAuditService(SystemLogService logs) {
    this.logs = logs;
  }

  public boolean recordApproval(String actor, boolean idempotent, HttpServletRequest request) {
    boolean recorded =
        logs.recordBusinessAction("SMARTAUDITS_REVIEW_APPROVED", actor, "carlsjr", request);
    if (idempotent) {
      log.info("smartaudits_approval_idempotent tenant=carlsjr");
    }
    return recorded;
  }
}
