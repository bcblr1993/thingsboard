/**
 * Copyright © 2016-2025 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.telemetry;

import org.junit.Assert;
import org.junit.Test;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.util.Collections;
import java.util.List;

public class TimeseriesFillProcessorTest {

    private static final String KEY = "temperature";
    private static final long START_TS = 1_700_000_000_000L;
    private static final long INTERVAL = 60_000L;

    @Test
    public void testFilledAvgUsesTimeWeightedState() {
        TsKvEntry seed = longEntry(START_TS - 1, 10);
        TsKvEntry changedValue = longEntry(START_TS + INTERVAL + 30_000, 20);

        List<TsKvEntry> result = process(seed, List.of(changedValue), Aggregation.AVG, true,
                START_TS + 3 * INTERVAL, 10);

        Assert.assertEquals(3, result.size());
        assertDoubleEntry(result.get(0), START_TS, 10.0);
        assertDoubleEntry(result.get(1), START_TS + INTERVAL, 15.0);
        assertDoubleEntry(result.get(2), START_TS + 2 * INTERVAL, 20.0);
    }

    @Test
    public void testFilledNoneReturnsLatestStateAtBucketEnd() {
        TsKvEntry seed = longEntry(START_TS - 1, 7);
        TsKvEntry changedValue = longEntry(START_TS + INTERVAL + 30_000, 8);

        List<TsKvEntry> result = process(seed, List.of(changedValue), Aggregation.NONE, true,
                START_TS + 3 * INTERVAL, 10);

        Assert.assertEquals(3, result.size());
        assertLongEntry(result.get(0), START_TS, 7);
        assertLongEntry(result.get(1), START_TS + INTERVAL, 8);
        assertLongEntry(result.get(2), START_TS + 2 * INTERVAL, 8);
    }

    @Test
    public void testUnfilledNoneReturnsLastRawPointAndOmitsEmptyBucket() {
        List<TsKvEntry> data = List.of(
                longEntry(START_TS + 1_000, 10),
                longEntry(START_TS + 2_000, 20),
                longEntry(START_TS + 2 * INTERVAL + 1_000, 30));

        List<TsKvEntry> result = process(null, data, Aggregation.NONE, false,
                START_TS + 3 * INTERVAL, 10);

        Assert.assertEquals(2, result.size());
        assertLongEntry(result.get(0), START_TS, 20);
        assertLongEntry(result.get(1), START_TS + 2 * INTERVAL, 30);
    }

    @Test
    public void testUnfilledAvgUsesOnlyRawPoints() {
        List<TsKvEntry> data = List.of(
                longEntry(START_TS + 1_000, 10),
                longEntry(START_TS + 2_000, 20),
                longEntry(START_TS + 2 * INTERVAL + 1_000, 30));

        List<TsKvEntry> result = process(null, data, Aggregation.AVG, false,
                START_TS + 3 * INTERVAL, 10);

        Assert.assertEquals(2, result.size());
        assertDoubleEntry(result.get(0), START_TS, 15.0);
        assertDoubleEntry(result.get(1), START_TS + 2 * INTERVAL, 30.0);
    }

    @Test
    public void testFilledSeriesStartsAtFirstKnownValueWithoutSeed() {
        TsKvEntry firstValue = longEntry(START_TS + 30_000, 20);

        List<TsKvEntry> result = process(null, List.of(firstValue), Aggregation.AVG, true,
                START_TS + 2 * INTERVAL, 10);

        Assert.assertEquals(2, result.size());
        assertDoubleEntry(result.get(0), START_TS, 20.0);
        assertDoubleEntry(result.get(1), START_TS + INTERVAL, 20.0);
    }

    @Test
    public void testMinAndMaxPreserveLongTypeForLongSeries() {
        List<TsKvEntry> data = List.of(
                longEntry(START_TS + 1_000, 12),
                longEntry(START_TS + 2_000, 5),
                longEntry(START_TS + 3_000, 18));

        List<TsKvEntry> min = process(null, data, Aggregation.MIN, false, START_TS + INTERVAL, 10);
        List<TsKvEntry> max = process(null, data, Aggregation.MAX, false, START_TS + INTERVAL, 10);

        assertLongEntry(min.get(0), START_TS, 5);
        assertLongEntry(max.get(0), START_TS, 18);
    }

    @Test
    public void testNonNumericSeriesCanBeDetectedBeforeAggregation() {
        TsKvEntry stringEntry = new BasicTsKvEntry(START_TS, new StringDataEntry(KEY, "warm"));

        Assert.assertFalse(TimeseriesFillProcessor.isNumericSeries(null, List.of(stringEntry)));
        Assert.assertTrue(TimeseriesFillProcessor.isNumericSeries(null, List.of(longEntry(START_TS, 1))));
    }

    @Test
    public void testResultLimitRejectsNextGeneratedPoint() {
        TsKvEntry seed = longEntry(START_TS - 1, 10);

        Assert.assertThrows(TimeseriesFillProcessor.ResultLimitExceededException.class,
                () -> process(seed, Collections.emptyList(), Aggregation.NONE, true,
                        START_TS + 3 * INTERVAL, 2));
    }

    @Test
    public void testResultLimitAllowsExactNumberOfPoints() {
        TsKvEntry seed = longEntry(START_TS - 1, 10);

        List<TsKvEntry> result = process(seed, Collections.emptyList(), Aggregation.NONE, true,
                START_TS + 2 * INTERVAL, 2);

        Assert.assertEquals(2, result.size());
    }

    @Test
    public void testDescOrderIsAppliedPerKey() {
        TsKvEntry seed = longEntry(START_TS - 1, 10);

        List<TsKvEntry> result = TimeseriesFillProcessor.processKey(KEY, seed, Collections.emptyList(),
                START_TS, START_TS + 2 * INTERVAL, INTERVAL, Aggregation.NONE, true, "DESC", 10);

        Assert.assertEquals(START_TS + INTERVAL, result.get(0).getTs());
        Assert.assertEquals(START_TS, result.get(1).getTs());
    }

    @Test
    public void testMixedLongAndDoubleMinReturnsDouble() {
        List<TsKvEntry> data = List.of(
                longEntry(START_TS + 1_000, 10),
                new BasicTsKvEntry(START_TS + 2_000, new DoubleDataEntry(KEY, 5.5)));

        List<TsKvEntry> result = process(null, data, Aggregation.MIN, false, START_TS + INTERVAL, 10);

        assertDoubleEntry(result.get(0), START_TS, 5.5);
    }

    private List<TsKvEntry> process(TsKvEntry seed, List<TsKvEntry> data, Aggregation aggregation,
                                    boolean fillMissing, long endTs, int resultLimit) {
        return TimeseriesFillProcessor.processKey(KEY, seed, data, START_TS, endTs, INTERVAL,
                aggregation, fillMissing, "ASC", resultLimit);
    }

    private TsKvEntry longEntry(long ts, long value) {
        return new BasicTsKvEntry(ts, new LongDataEntry(KEY, value));
    }

    private void assertLongEntry(TsKvEntry entry, long ts, long value) {
        Assert.assertEquals(ts, entry.getTs());
        Assert.assertEquals(value, entry.getLongValue().orElseThrow().longValue());
    }

    private void assertDoubleEntry(TsKvEntry entry, long ts, double value) {
        Assert.assertEquals(ts, entry.getTs());
        Assert.assertEquals(value, entry.getDoubleValue().orElseThrow(), 0.0001);
    }
}
