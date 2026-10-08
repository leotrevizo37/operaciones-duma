package com.duma.smartaudits.review;

public class ReviewQueueConflictException extends RuntimeException {
  public ReviewQueueConflictException() {
    super("SMARTAUDITS_REVIEW_CONFLICT");
  }
}
