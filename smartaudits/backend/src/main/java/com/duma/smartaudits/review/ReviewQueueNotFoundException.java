package com.duma.smartaudits.review;

public class ReviewQueueNotFoundException extends RuntimeException {
  public ReviewQueueNotFoundException() {
    super("SMARTAUDITS_REVIEW_NOT_FOUND");
  }
}
