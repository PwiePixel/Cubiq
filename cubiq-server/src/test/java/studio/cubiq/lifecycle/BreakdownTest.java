package studio.cubiq.lifecycle;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BreakdownTest {

    private final Breakdown breakdown = new Breakdown();

    private static long millis(final long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Test
    void stepsWithTheSameLabelAreAddedUpInTheOrderTheyBegan() {
        // Steps end in another order than they begin when they are nested or run on several threads
        this.breakdown.add(ServerStep.LOADING_WORLDS, null, null, millis(300), millis(50));
        this.breakdown.add(ServerStep.ENABLING_PLUGINS, null, null, millis(200), millis(40));
        this.breakdown.add(ServerStep.ENABLING_PLUGINS, null, null, millis(400), millis(60));
        this.breakdown.add(ServerStep.BOOTSTRAPPING, null, null, millis(100), millis(90));

        assertEquals(
            List.of(
                new Breakdown.Entry("bootstrapping", millis(90), List.of()),
                new Breakdown.Entry("enabling plugins", millis(100), List.of()),
                new Breakdown.Entry("worlds", millis(50), List.of())
            ),
            this.breakdown.entries()
        );
    }

    @Test
    void stepsForASubjectInsideAStepWithTheSameLabelAreItsDetails() {
        this.breakdown.add(ServerStep.LOADING_WORLD, "world", ServerStep.LOADING_WORLDS, millis(110), millis(30));
        this.breakdown.add(ServerStep.LOADING_WORLD, "world_nether", ServerStep.LOADING_WORLDS, millis(140), millis(20));
        this.breakdown.add(ServerStep.PREPARING_WORLD, "world", ServerStep.LOADING_WORLDS, millis(160), millis(10));
        this.breakdown.add(ServerStep.LOADING_WORLDS, null, null, millis(100), millis(75));

        assertEquals(
            List.of(new Breakdown.Entry("worlds", millis(75), List.of(new Breakdown.Detail("world", millis(40)), new Breakdown.Detail("world_nether", millis(20))))),
            this.breakdown.entries()
        );
    }

    @Test
    void stepsInsideAStepWithAnotherLabelArePartOfItsTime() {
        // For example a plugin that loads a world while it is enabled
        this.breakdown.add(ServerStep.LOADING_WORLD, "creative", ServerStep.ENABLING_PLUGINS, millis(110), millis(30));
        this.breakdown.add(ServerStep.SAVING_PLAYERS, null, ServerStep.DISABLING_PLUGINS, millis(120), millis(5));
        this.breakdown.add(ServerStep.ENABLING_PLUGINS, null, null, millis(100), millis(80));

        assertEquals(List.of(new Breakdown.Entry("enabling plugins", millis(80), List.of())), this.breakdown.entries());
    }

    @Test
    void aStepForASubjectOutsideOfOtherStepsIsAnEntryWithADetail() {
        this.breakdown.add(ServerStep.SAVING_WORLD, "world", null, millis(100), millis(30));
        this.breakdown.add(ServerStep.SAVING_WORLD, "world_nether", null, millis(130), millis(20));

        assertEquals(
            List.of(new Breakdown.Entry("worlds", millis(50), List.of(new Breakdown.Detail("world", millis(30)), new Breakdown.Detail("world_nether", millis(20))))),
            this.breakdown.entries()
        );
    }

    @Test
    void theOrderUsesTheDifferenceOfTheClockValues() {
        // System.nanoTime() can be negative and is only meaningful as a difference
        this.breakdown.add(ServerStep.SAVING_PLAYERS, null, null, Long.MAX_VALUE, millis(1));
        this.breakdown.add(ServerStep.SAVING_WORLDS, null, null, Long.MAX_VALUE + millis(1), millis(1));

        assertEquals(List.of("players", "worlds"), this.breakdown.entries().stream().map(Breakdown.Entry::label).toList());
    }
}
