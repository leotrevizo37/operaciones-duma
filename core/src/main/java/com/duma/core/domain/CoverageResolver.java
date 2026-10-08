package com.duma.core.domain;

/**
 * The one classification table behind {@link CoverageStatus}.
 *
 * <p>The four states are the product of the system, not metadata: a missing table and an empty
 * table are different answers, and neither is a zero. Before this class the rule lived as branches
 * in four repositories, and only one of them had a test.
 *
 * <p>Callers that hold two sources combine them before calling. Callers that cannot distinguish a
 * missing object from an unreadable one pass the same flag twice.
 */
public final class CoverageResolver {

  private CoverageResolver() {}

  /**
   * @param sourcePresent the required object exists
   * @param sourceReadable the required object exists <b>and</b> can be read; never true when {@code
   *     sourcePresent} is false
   * @param observedRows rows the current period actually observed
   */
  public static CoverageStatus resolve(
      boolean sourcePresent, boolean sourceReadable, long observedRows) {
    if (!sourcePresent) {
      return CoverageStatus.NOT_SUPPORTED;
    }
    if (!sourceReadable) {
      return CoverageStatus.UNAVAILABLE;
    }
    return observedRows == 0 ? CoverageStatus.NO_DATA : CoverageStatus.AVAILABLE;
  }
}
