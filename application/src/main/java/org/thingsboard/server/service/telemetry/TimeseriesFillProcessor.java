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

import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.JsonDataEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class TimeseriesFillProcessor {

    private TimeseriesFillProcessor() {
    }

    static boolean isNumericSeries(TsKvEntry seed, List<TsKvEntry> data) {
        if (seed != null && !isNumeric(seed)) {
            return false;
        }
        for (TsKvEntry entry : data) {
            if (!isNumeric(entry)) {
                return false;
            }
        }
        return true;
    }

    static List<TsKvEntry> processKey(String key,
                                      TsKvEntry seed,
                                      List<TsKvEntry> rawData,
                                      long startTs,
                                      long endTs,
                                      long interval,
                                      Aggregation aggregation,
                                      boolean fillMissing,
                                      String orderBy,
                                      int resultLimit) {
        List<TsKvEntry> data = filterAndSort(rawData, startTs - interval, endTs);
        List<TsKvEntry> result = new ArrayList<>();
        if (!fillMissing && Aggregation.NONE.equals(aggregation)) {
            // NONE without filling is an exact-time lookup at each output timestamp.
            for (TsKvEntry entry : data) {
                long ts = entry.getTs();
                if (ts >= startTs && (ts == endTs || (ts - startTs) % interval == 0)) {
                    addResult(result, new BasicTsKvEntry(ts, copyKv(entry)), resultLimit);
                }
            }
        } else if (seed != null || !data.isEmpty()) {
            int leftIndex = 0;
            TsKvEntry preceding = seed;
            long bucketEnd = startTs;
            while (true) {
                long bucketStart = bucketEnd - interval;
                // Keep the state at the left boundary. Re-evaluate the final full-width
                // window independently because it may overlap the preceding window.
                while (leftIndex < data.size() && data.get(leftIndex).getTs() <= bucketStart) {
                    preceding = data.get(leftIndex++);
                }
                if (!fillMissing) {
                    if (leftIndex == data.size()) {
                        break;
                    }
                    long nextTs = data.get(leftIndex).getTs();
                    if (nextTs > bucketEnd) {
                        // Skip empty windows without scanning every output timestamp.
                        long remainder = (nextTs - startTs) % interval;
                        long advance = remainder == 0 ? 0 : interval - remainder;
                        bucketEnd = advance >= endTs - nextTs ? endTs : nextTs + advance;
                        continue;
                    }
                }
                TsKvEntry entry = aggregateBucket(key, preceding, data, leftIndex,
                        bucketStart, bucketEnd, aggregation, fillMissing);
                if (entry != null) {
                    addResult(result, entry, resultLimit);
                }
                if (bucketEnd == endTs) {
                    break;
                }
                bucketEnd = interval >= endTs - bucketEnd ? endTs : bucketEnd + interval;
            }
        }
        if ("DESC".equalsIgnoreCase(orderBy)) {
            Collections.reverse(result);
        }
        return result;
    }

    private static TsKvEntry aggregateBucket(String key, TsKvEntry preceding, List<TsKvEntry> data,
                                              int index, long bucketStart, long bucketEnd,
                                              Aggregation aggregation, boolean fillMissing) {
        NumericAccumulator accumulator = new NumericAccumulator(key, aggregation, fillMissing);
        TsKvEntry active = preceding;
        long segmentStart = bucketStart;
        while (index < data.size() && data.get(index).getTs() <= bucketEnd) {
            TsKvEntry next = data.get(index++);
            if (!Aggregation.NONE.equals(aggregation)) {
                if (!fillMissing) {
                    accumulator.add(next);
                } else if (active != null) {
                    accumulator.add(active, segmentStart, next.getTs());
                }
            }
            active = next;
            segmentStart = next.getTs();
        }
        if (Aggregation.NONE.equals(aggregation)) {
            return active == null ? null : new BasicTsKvEntry(bucketEnd, copyKv(active));
        }
        if (fillMissing && active != null) {
            // A new value exactly at bucketEnd has zero duration and is excluded.
            accumulator.add(active, segmentStart, bucketEnd);
        }
        return accumulator.toEntry(bucketEnd);
    }

    private static List<TsKvEntry> filterAndSort(List<TsKvEntry> rawData, long lowerExclusive, long endTs) {
        List<TsKvEntry> result = new ArrayList<>();
        if (rawData != null) {
            for (TsKvEntry entry : rawData) {
                if (entry.getTs() > lowerExclusive && entry.getTs() <= endTs) {
                    result.add(entry);
                }
            }
        }
        result.sort(Comparator.comparingLong(TsKvEntry::getTs));
        return result;
    }

    private static boolean isNumeric(TsKvEntry entry) {
        return DataType.LONG.equals(entry.getDataType()) && entry.getLongValue().isPresent()
                || DataType.DOUBLE.equals(entry.getDataType()) && entry.getDoubleValue().isPresent();
    }

    private static void addResult(List<TsKvEntry> result, TsKvEntry entry, int resultLimit) {
        if (result.size() >= resultLimit) {
            throw new ResultLimitExceededException();
        }
        result.add(entry);
    }

    private static KvEntry copyKv(TsKvEntry source) {
        String key = source.getKey();
        return switch (source.getDataType()) {
            case BOOLEAN -> new BooleanDataEntry(key, source.getBooleanValue().orElse(null));
            case LONG -> new LongDataEntry(key, source.getLongValue().orElse(null));
            case DOUBLE -> new DoubleDataEntry(key, source.getDoubleValue().orElse(null));
            case STRING -> new StringDataEntry(key, source.getStrValue().orElse(null));
            case JSON -> new JsonDataEntry(key, source.getJsonValue().orElse(null));
        };
    }

    static final class ResultLimitExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class NumericAccumulator {
        private final String key;
        private final Aggregation aggregation;
        private final boolean timeWeighted;
        private boolean onlyLongValues = true;
        private long weight;
        private double sum;
        private Double min;
        private Double max;

        private NumericAccumulator(String key, Aggregation aggregation, boolean timeWeighted) {
            this.key = key;
            this.aggregation = aggregation;
            this.timeWeighted = timeWeighted;
        }

        private void add(TsKvEntry entry) {
            add(entry, 0, 1);
        }

        private void add(TsKvEntry entry, long fromTs, long toTs) {
            long currentWeight = timeWeighted ? toTs - fromTs : 1;
            if (currentWeight <= 0) {
                return;
            }
            double value = numericValue(entry);
            onlyLongValues &= DataType.LONG.equals(entry.getDataType());
            weight += currentWeight;
            if (Aggregation.AVG.equals(aggregation)) {
                sum += value * currentWeight;
            } else if (Aggregation.MIN.equals(aggregation)) {
                min = min == null ? value : Math.min(min, value);
            } else if (Aggregation.MAX.equals(aggregation)) {
                max = max == null ? value : Math.max(max, value);
            }
        }

        private TsKvEntry toEntry(long ts) {
            if (weight == 0) {
                return null;
            }
            if (Aggregation.AVG.equals(aggregation)) {
                return new BasicTsKvEntry(ts, new DoubleDataEntry(key, sum / weight));
            }
            double value = Aggregation.MIN.equals(aggregation) ? min : max;
            if (onlyLongValues) {
                return new BasicTsKvEntry(ts, new LongDataEntry(key, (long) value));
            }
            return new BasicTsKvEntry(ts, new DoubleDataEntry(key, value));
        }

        private double numericValue(TsKvEntry entry) {
            if (DataType.LONG.equals(entry.getDataType())) {
                return entry.getLongValue().orElseThrow();
            }
            if (DataType.DOUBLE.equals(entry.getDataType())) {
                return entry.getDoubleValue().orElseThrow();
            }
            throw new IllegalArgumentException("Aggregation " + aggregation + " supports only numeric values. Key: " + key);
        }
    }
}
