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
    private static final long START = 1_700_000_000_000L;
    private static final long INTERVAL = 10_000L;

    @Test
    public void testUnfilledAggregationsIncludeFirstWindowAndOmitEmptyWindows() {
        List<TsKvEntry> data = sample();
        assertValues(process(null, data, Aggregation.AVG, false, 30), new long[]{0, 10, 30}, 15, 25, 60);
        assertValues(process(null, data, Aggregation.MIN, false, 30), new long[]{0, 10, 30}, 10, 10, 60);
        assertValues(process(null, data, Aggregation.MAX, false, 30), new long[]{0, 10, 30}, 20, 40, 60);
    }

    @Test
    public void testFilledAggregationsUseTimeWeightedState() {
        TsKvEntry seed = point(-12, 4);
        assertValues(process(seed, sample(), Aggregation.NONE, true, 30), new long[]{0, 10, 20, 30}, 20, 10, 10, 60);
        assertValues(process(seed, sample(), Aggregation.AVG, true, 30), new long[]{0, 10, 20, 30}, 10.8, 30, 10, 50);
        assertValues(process(seed, sample(), Aggregation.MIN, true, 30), new long[]{0, 10, 20, 30}, 4, 10, 10, 10);
        assertValues(process(seed, sample(), Aggregation.MAX, true, 30), new long[]{0, 10, 20, 30}, 20, 40, 10, 60);
    }

    @Test
    public void testUnfilledNoneRequiresExactTimestampIncludingEnd() {
        List<TsKvEntry> data = List.of(point(-1, 1), point(0, 2), point(9, 3), point(10, 4),
                point(19, 5), point(24, 6), point(25, 7), point(26, 8));
        assertValues(process(null, data, Aggregation.NONE, false, 25), new long[]{0, 10, 25}, 2, 4, 7);
        Assert.assertTrue(process(null, sample(), Aggregation.NONE, false, 30).isEmpty());
    }

    @Test
    public void testRightClosedRawWindowsExcludeLeftBoundary() {
        List<TsKvEntry> data = List.of(point(-10, 100), point(0, 10), point(10, 20), point(20, 30), point(21, 999));
        for (Aggregation agg : List.of(Aggregation.AVG, Aggregation.MIN, Aggregation.MAX)) {
            assertValues(process(null, data, agg, false, 20), new long[]{0, 10, 20}, 10, 20, 30);
        }
    }

    @Test
    public void testRightEndpointHasZeroDurationForFilledAggregations() {
        for (long endpointValue : new long[]{99, -99}) {
            for (Aggregation agg : List.of(Aggregation.AVG, Aggregation.MIN, Aggregation.MAX)) {
                assertValues(process(point(-10, 7), List.of(point(0, endpointValue)), agg, true, 10),
                        new long[]{0, 10}, 7, endpointValue);
            }
            assertValues(process(point(-10, 7), List.of(point(0, endpointValue)), Aggregation.NONE, true, 0),
                    new long[]{0}, endpointValue);
        }
    }

    @Test
    public void testFirstValueAtRightEndpointWithoutSeed() {
        for (Aggregation agg : List.of(Aggregation.AVG, Aggregation.MIN, Aggregation.MAX)) {
            assertValues(process(null, List.of(point(0, 99)), agg, true, 10), new long[]{10}, 99);
        }
        assertValues(process(null, List.of(point(0, 99)), Aggregation.NONE, true, 10), new long[]{0, 10}, 99, 99);
    }

    @Test
    public void testUnknownPrefixIsNotBackfilledOrCountedAsZero() {
        assertValues(process(null, List.of(point(-2, 20)), Aggregation.AVG, true, 10), new long[]{0, 10}, 20, 20);
        assertValues(process(null, List.of(point(12, 30)), Aggregation.AVG, true, 20), new long[]{20}, 30);
        for (Aggregation agg : List.of(Aggregation.NONE, Aggregation.AVG, Aggregation.MIN, Aggregation.MAX)) {
            Assert.assertTrue(process(null, Collections.emptyList(), agg, true, 20).isEmpty());
            Assert.assertTrue(process(null, Collections.emptyList(), agg, false, 20).isEmpty());
            assertValues(process(point(-12, 7), Collections.emptyList(), agg, true, 20), new long[]{0, 10, 20}, 7, 7, 7);
        }
    }

    @Test
    public void testFinalUnalignedWindowIsFullWidthAndOverlaps() {
        List<TsKvEntry> data = List.of(point(15, 100), point(16, 20), point(20, 40), point(25, 80));
        assertValues(process(null, data, Aggregation.AVG, false, 25), new long[]{20, 25}, 160.0 / 3, 140.0 / 3);
        assertValues(process(null, data, Aggregation.MIN, false, 25), new long[]{20, 25}, 20, 20);
        assertValues(process(null, data, Aggregation.MAX, false, 25), new long[]{20, 25}, 100, 80);
        assertValues(process(null, data, Aggregation.NONE, true, 25), new long[]{20, 25}, 40, 80);
        assertValues(process(null, data, Aggregation.AVG, true, 25), new long[]{20, 25}, 36, 38);
        assertValues(process(null, data, Aggregation.MIN, true, 25), new long[]{20, 25}, 20, 20);
        assertValues(process(null, data, Aggregation.MAX, true, 25), new long[]{20, 25}, 100, 100);
    }

    @Test
    public void testRangeShorterThanIntervalStillReturnsBothEndpoints() {
        assertValues(process(point(-12, 7), List.of(point(0, 17)), Aggregation.AVG, true, 5), new long[]{0, 5}, 7, 12);
    }

    @Test
    public void testEqualStartAndEndProducesOneTimestamp() {
        assertValues(process(null, List.of(point(0, 9)), Aggregation.NONE, false, 0), new long[]{0}, 9);
        assertValues(process(point(-12, 7), List.of(), Aggregation.AVG, true, 0), new long[]{0}, 7);
    }

    @Test
    public void testDescendingOrderAndInclusiveResultLimit() {
        List<TsKvEntry> result = TimeseriesFillProcessor.processKey(KEY, point(-12, 7), List.of(),
                START, START + 20_000, INTERVAL, Aggregation.NONE, true, "DESC", 3);
        assertValues(result, new long[]{20, 10, 0}, 7, 7, 7);
        Assert.assertThrows(TimeseriesFillProcessor.ResultLimitExceededException.class, () ->
                TimeseriesFillProcessor.processKey(KEY, point(-12, 7), List.of(), START, START + 20_000,
                        INTERVAL, Aggregation.NONE, true, "ASC", 2));
    }

    @Test
    public void testNumericTypesAndNonNumericNone() {
        List<TsKvEntry> mixed = List.of(point(-5, 10), new BasicTsKvEntry(START, new DoubleDataEntry(KEY, 5.5)));
        TsKvEntry min = process(null, mixed, Aggregation.MIN, false, 0).get(0);
        Assert.assertEquals(5.5, min.getDoubleValue().orElseThrow(), 0.0001);
        Assert.assertTrue(process(null, List.of(point(0, 10)), Aggregation.MAX, false, 0).get(0).getLongValue().isPresent());
        TsKvEntry text = new BasicTsKvEntry(START, new StringDataEntry(KEY, "warm"));
        Assert.assertFalse(TimeseriesFillProcessor.isNumericSeries(null, List.of(text)));
        Assert.assertEquals("warm", process(null, List.of(text), Aggregation.NONE, false, 0).get(0).getStrValue().orElseThrow());
    }

    private List<TsKvEntry> sample() {
        return List.of(point(-8, 10), point(-2, 20), point(2, 40), point(8, 10), point(22, 60));
    }

    private List<TsKvEntry> process(TsKvEntry seed, List<TsKvEntry> data, Aggregation agg, boolean fill, long endSeconds) {
        return TimeseriesFillProcessor.processKey(KEY, seed, data, START, START + endSeconds * 1000,
                INTERVAL, agg, fill, "ASC", 100);
    }

    private TsKvEntry point(long seconds, long value) {
        return new BasicTsKvEntry(START + seconds * 1000, new LongDataEntry(KEY, value));
    }

    private void assertValues(List<TsKvEntry> result, long[] seconds, double... values) {
        Assert.assertEquals(seconds.length, result.size());
        for (int i = 0; i < seconds.length; i++) {
            Assert.assertEquals(START + seconds[i] * 1000, result.get(i).getTs());
            double actual = result.get(i).getLongValue().isPresent()
                    ? result.get(i).getLongValue().get() : result.get(i).getDoubleValue().orElseThrow();
            Assert.assertEquals(values[i], actual, 0.0001);
        }
    }
}
