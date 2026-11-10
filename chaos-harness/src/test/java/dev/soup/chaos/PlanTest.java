package dev.soup.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Task 7.2: same seed → identical plan; proportions match the spec. */
class PlanTest {

    @Test
    void sameSeedGivesTheSamePlanAndADifferentSeedDoesNot() {
        assertThat(Plan.generate(42, 2000)).isEqualTo(Plan.generate(42, 2000));
        assertThat(Plan.generate(42, 2000)).isNotEqualTo(Plan.generate(43, 2000));
    }

    @Test
    void proportionsMatchTheSpec() {
        List<Plan.KeyPlan> plan = Plan.generate(1, 10_000);
        Plan.Summary s = Plan.summarize(plan);
        assertThat(s.withDuplicates()).isGreaterThanOrEqualTo(3000);          // at least 30% duplicated
        assertThat(s.concurrentGroups()).isGreaterThan(1000);
        assertThat(s.conflicting()).isBetween(50, 150);                       // about 1%
        assertThat(s.randomCancels() + s.targetedCancels()).isBetween(900, 1100); // about 10%
        assertThat(s.targetedCancels()).isBetween(400, 600);                  // about half of the cancels
        assertThat(plan.stream().map(Plan.KeyPlan::key).distinct().count()).isEqualTo(10_000);
    }
}
