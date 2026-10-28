package studio.cubiq.lifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LifecycleReportTest {

    private static final TimingReport STARTUP = new TimingReport(
        TimeUnit.MILLISECONDS.toNanos(12_000),
        List.of(
            new Breakdown.Entry("bootstrapping", TimeUnit.MILLISECONDS.toNanos(3_100), List.of()),
            new Breakdown.Entry("worlds", TimeUnit.MILLISECONDS.toNanos(7_900), List.of(
                new Breakdown.Detail("world", 4_400_123_456L),
                new Breakdown.Detail("world_nether", TimeUnit.MILLISECONDS.toNanos(3_500))
            ))
        ),
        List.of(
            new PluginTiming("Example", PluginStep.LOAD, 0L, TimeUnit.MILLISECONDS.toNanos(20)),
            new PluginTiming("Other", PluginStep.ENABLE, 0L, TimeUnit.MILLISECONDS.toNanos(5)),
            new PluginTiming("Example", PluginStep.ENABLE, 0L, TimeUnit.MILLISECONDS.toNanos(300)),
            new PluginTiming("Example", PluginStep.ENABLE, 0L, TimeUnit.MILLISECONDS.toNanos(100))
        )
    );

    @Test
    void theReportListsTheStepsTheTimeNoStepAccountsForAndThePlugins() {
        assertEquals(
            """
                {
                  "formatVersion": 1,
                  "startup": {
                    "totalMillis": 12000.0,
                    "steps": [
                      {
                        "name": "bootstrapping",
                        "millis": 3100.0
                      },
                      {
                        "name": "worlds",
                        "millis": 7900.0,
                        "details": [
                          {
                            "name": "world",
                            "millis": 4400.123
                          },
                          {
                            "name": "world_nether",
                            "millis": 3500.0
                          }
                        ]
                      },
                      {
                        "name": "other",
                        "millis": 1000.0
                      }
                    ],
                    "plugins": [
                      {
                        "name": "Example",
                        "loadMillis": 20.0,
                        "enableMillis": 400.0
                      },
                      {
                        "name": "Other",
                        "enableMillis": 5.0
                      }
                    ]
                  }
                }
                """,
            LifecycleReport.toJson(STARTUP, null)
        );
    }

    @Test
    void theShutdownFollowsTheStartup() {
        final TimingReport shutdown = new TimingReport(TimeUnit.MILLISECONDS.toNanos(1500), List.of(), List.of());

        final String json = LifecycleReport.toJson(null, shutdown);

        assertEquals(
            """
                {
                  "formatVersion": 1,
                  "shutdown": {
                    "totalMillis": 1500.0,
                    "steps": [
                      {
                        "name": "other",
                        "millis": 1500.0
                      }
                    ],
                    "plugins": []
                  }
                }
                """,
            json
        );
    }

    @Test
    void writingReplacesTheFileAndCreatesItsDirectory(@TempDir final Path directory) throws IOException {
        final Path report = directory.resolve("reports/lifecycle.json");

        LifecycleReport.write(report, STARTUP, null);
        LifecycleReport.write(report, null, new TimingReport(0L, List.of(), List.of()));

        assertEquals(LifecycleReport.toJson(null, new TimingReport(0L, List.of(), List.of())), Files.readString(report));
        assertFalse(Files.exists(report.resolveSibling("lifecycle.json.tmp")));
    }

    @Test
    void timesHaveThreeDecimals() {
        assertEquals(1.0, LifecycleReport.millis(1_000_000L));
        assertEquals(0.002, LifecycleReport.millis(1_500L));
        assertEquals(0.001, LifecycleReport.millis(1_499L));
        assertEquals(4400.123, LifecycleReport.millis(4_400_123_456L));
    }
}
