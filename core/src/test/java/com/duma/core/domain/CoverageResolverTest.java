package com.duma.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CoverageResolverTest {

  @ParameterizedTest
  @CsvSource({
    // A missing object is never a zero, and the row count cannot rescue it.
    "false,false,0,NOT_SUPPORTED",
    "false,false,5,NOT_SUPPORTED",
    // Present but unreadable is a failure to answer, not an absence of coverage.
    "true,false,0,UNAVAILABLE",
    "true,false,5,UNAVAILABLE",
    "true,true,0,NO_DATA",
    "true,true,1,AVAILABLE"
  })
  void distinguishesMissingFromUnreadableFromEmptyFromAvailable(
      boolean sourcePresent, boolean sourceReadable, long observedRows, CoverageStatus expected) {
    assertThat(CoverageResolver.resolve(sourcePresent, sourceReadable, observedRows))
        .isEqualTo(expected);
  }

  /**
   * The six rows the experience module fixed before this rule moved to core, replayed through its
   * new call site. Experience holds two independent sources and combines them with OR, so its old
   * two-flag pairs are not this signature's first two arguments: passing them positionally would
   * turn {@code (users, no availability, no rows)} from NO_DATA into UNAVAILABLE.
   */
  @ParameterizedTest
  @CsvSource({
    "false,false,0,NOT_SUPPORTED",
    "true,false,0,NO_DATA",
    "false,true,0,NO_DATA",
    "true,true,0,NO_DATA",
    "true,false,1,AVAILABLE",
    "false,true,1,AVAILABLE"
  })
  void preservesTheExperienceTableThroughTheOrCollapse(
      boolean usersPresent, boolean availabilityPresent, long observedRows, CoverageStatus expected) {
    boolean anySource = usersPresent || availabilityPresent;

    assertThat(CoverageResolver.resolve(anySource, anySource, observedRows)).isEqualTo(expected);
  }
}
