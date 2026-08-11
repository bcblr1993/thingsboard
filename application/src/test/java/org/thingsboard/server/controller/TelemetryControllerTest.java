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
package org.thingsboard.server.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.SaveDeviceWithCredentialsRequest;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.SingleEntityFilter;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.dao.service.DaoSqlTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.thingsboard.server.common.data.query.EntityKeyType.TIME_SERIES;

@DaoSqlTest
@TestPropertySource(properties = {
        "sql.attributes.value_no_xss_validation=true",
        "sql.ts.value_no_xss_validation=true"
})
public class TelemetryControllerTest extends AbstractControllerTest {

    @Test
    public void testConstraintValidator() throws Exception {
        loginTenantAdmin();
        Device device = createDevice();
        String correctRequestBody = "{\"data\": \"value\"}";
        doPostAsync("/api/plugins/telemetry/" + device.getId() + "/SHARED_SCOPE", correctRequestBody, String.class, status().isOk());
        doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/timeseries/smth", correctRequestBody, String.class, status().isOk());
        String invalidRequestBody = "{\"<object data=\\\"data:text/html,<script>alert(document)</script>\\\"></object>\": \"data\"}";
        doPostAsync("/api/plugins/telemetry/" + device.getId() + "/SHARED_SCOPE", invalidRequestBody, String.class, status().isBadRequest());
        doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/timeseries/smth", invalidRequestBody, String.class, status().isBadRequest());
    }

    @Test
    public void testTelemetryRequests() throws Exception {
        loginTenantAdmin();
        Device device = createDevice();

        var startTs = 1704899727000L; // Wednesday, January 10 15:15:27 GMT
        var endOfWeek1Ts = 1705269600000L;  // Monday, January 15, 2024 0:00:00 GMT+02:00
        var endOfWeek2Ts = 1705874400000L;  // Monday, January 22, 2024 0:00:00 GMT+02:00
        var endTs = endOfWeek2Ts + TimeUnit.DAYS.toMillis(1) + TimeUnit.HOURS.toMillis(1); // Monday, January 23, 2024 1:00:00 GMT+02:00

        var firstIntervalTs = startTs + (endOfWeek1Ts - startTs) / 2;
        var secondIntervalTs = endOfWeek1Ts + (endOfWeek2Ts - endOfWeek1Ts) / 2;
        var thirdIntervalTs = endOfWeek2Ts + (endTs - endOfWeek2Ts) / 2;

        var middleOfTheInterval = startTs + (endTs - startTs) / 2;

        tsService.save(tenantId, device.getId(), new BasicTsKvEntry(1704899728000L, new LongDataEntry("t", 1L))); // Wednesday, January 10 15:15:28 GMT
        tsService.save(tenantId, device.getId(), new BasicTsKvEntry(1704899729000L, new LongDataEntry("t", 3L))); // Wednesday, January 10 15:15:29 GMT
        tsService.save(tenantId, device.getId(), new BasicTsKvEntry(endOfWeek1Ts, new LongDataEntry("t", 2L))); // Monday, January 15, 2024 0:00:00 GMT+02:00
        tsService.save(tenantId, device.getId(), new BasicTsKvEntry(endOfWeek1Ts + 1000, new LongDataEntry("t", 5L))); // Monday, January 15, 2024 0:00:01 GMT+02:00
        tsService.save(tenantId, device.getId(), new BasicTsKvEntry(endOfWeek2Ts, new LongDataEntry("t", 9L))); // Monday, January 22, 2024 0:00:00 GMT+02:00
        tsService.save(tenantId, device.getId(), new BasicTsKvEntry(endOfWeek2Ts + 1000, new LongDataEntry("t", 2L))); // Monday, January 22, 2024 0:00:01 GMT+02:00

        ObjectNode result = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() +
                        "/values/timeseries?keys=t&startTs={startTs}&endTs={endTs}&agg={agg}&intervalType={intervalType}&timeZone={timeZone}",
                ObjectNode.class, startTs, endTs, "SUM", "WEEK_ISO", "Europe/Kyiv");
        Assert.assertNotNull(result);
        Assert.assertNotNull(result.get("t"));
        Assert.assertEquals(3, result.get("t").size());

        var firstIntervalResult = result.get("t").get(0);
        Assert.assertEquals(4L, firstIntervalResult.get("value").asLong());
        Assert.assertEquals(firstIntervalTs, firstIntervalResult.get("ts").asLong());

        var secondIntervalResult = result.get("t").get(1);
        Assert.assertEquals(7L, secondIntervalResult.get("value").asLong());
        Assert.assertEquals(secondIntervalTs, secondIntervalResult.get("ts").asLong());

        var thirdIntervalResult = result.get("t").get(2);
        Assert.assertEquals(11L, thirdIntervalResult.get("value").asLong());
        Assert.assertEquals(thirdIntervalTs, thirdIntervalResult.get("ts").asLong());

        result = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() +
                        "/values/timeseries?keys=t&startTs={startTs}&endTs={endTs}&agg={agg}&intervalType={intervalType}&timeZone={timeZone}",
                ObjectNode.class, startTs, endTs, "SUM", "MONTH", "Europe/Kyiv");

        Assert.assertNotNull(result);
        Assert.assertNotNull(result.get("t"));
        Assert.assertEquals(1, result.get("t").size());

        var monthResult = result.get("t").get(0);
        Assert.assertEquals(22L, monthResult.get("value").asLong());
        Assert.assertEquals(middleOfTheInterval, monthResult.get("ts").asLong());
    }

    @Test
    public void testTimeseriesFillAvgUsesTimeWeightedState() throws Exception {
        loginTenantAdmin();
        Device device = createDevice();
        long startTs = 1_700_010_000_000L;
        long interval = TimeUnit.MINUTES.toMillis(1);
        long endTs = startTs + 3 * interval;

        tsService.save(tenantId, device.getId(),
                new BasicTsKvEntry(startTs - 1, new LongDataEntry("temperature", 10L))).get();
        tsService.save(tenantId, device.getId(),
                new BasicTsKvEntry(startTs + interval + 30_000, new LongDataEntry("temperature", 20L))).get();

        ObjectNode result = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() +
                        "/values/timeseries/fill?keys=temperature&startTs={startTs}&endTs={endTs}" +
                        "&interval={interval}&agg=AVG&fillMissing=true&orderBy=ASC&useStrictDataTypes=true",
                ObjectNode.class, startTs, endTs, interval);

        Assert.assertEquals(3, result.get("temperature").size());
        Assert.assertEquals(10.0, result.get("temperature").get(0).get("value").asDouble(), 0.0001);
        Assert.assertEquals(15.0, result.get("temperature").get(1).get("value").asDouble(), 0.0001);
        Assert.assertEquals(20.0, result.get("temperature").get(2).get("value").asDouble(), 0.0001);
    }

    @Test
    public void testTimeseriesFillDefaultsToNoneAggregation() throws Exception {
        loginTenantAdmin();
        Device device = createDevice();
        long startTs = 1_700_020_000_000L;
        long interval = TimeUnit.SECONDS.toMillis(1);
        long endTs = startTs + 3 * interval;

        tsService.save(tenantId, device.getId(),
                new BasicTsKvEntry(startTs - 1, new LongDataEntry("state", 7L))).get();
        tsService.save(tenantId, device.getId(),
                new BasicTsKvEntry(startTs + interval + 500, new LongDataEntry("state", 8L))).get();

        ObjectNode result = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() +
                        "/values/timeseries/fill?keys=state&startTs={startTs}&endTs={endTs}" +
                        "&interval={interval}&fillMissing=true&orderBy=ASC&useStrictDataTypes=true",
                ObjectNode.class, startTs, endTs, interval);

        Assert.assertEquals(3, result.get("state").size());
        Assert.assertEquals(7L, result.get("state").get(0).get("value").asLong());
        Assert.assertEquals(8L, result.get("state").get(1).get("value").asLong());
        Assert.assertEquals(8L, result.get("state").get(2).get("value").asLong());
    }

    @Test
    public void testTimeseriesFillDisabledOmitsEmptyBuckets() throws Exception {
        loginTenantAdmin();
        Device device = createDevice();
        long startTs = 1_700_030_000_000L;
        long interval = TimeUnit.MINUTES.toMillis(1);
        long endTs = startTs + 3 * interval;

        tsService.save(tenantId, device.getId(),
                new BasicTsKvEntry(startTs + 1_000, new LongDataEntry("temperature", 10L))).get();
        tsService.save(tenantId, device.getId(),
                new BasicTsKvEntry(startTs + 2_000, new LongDataEntry("temperature", 20L))).get();
        tsService.save(tenantId, device.getId(),
                new BasicTsKvEntry(startTs + 2 * interval + 1_000, new LongDataEntry("temperature", 30L))).get();

        ObjectNode result = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() +
                        "/values/timeseries/fill?keys=temperature&startTs={startTs}&endTs={endTs}" +
                        "&interval={interval}&agg=AVG&fillMissing=false&orderBy=ASC&useStrictDataTypes=true",
                ObjectNode.class, startTs, endTs, interval);

        Assert.assertEquals(2, result.get("temperature").size());
        Assert.assertEquals(startTs, result.get("temperature").get(0).get("ts").asLong());
        Assert.assertEquals(15.0, result.get("temperature").get(0).get("value").asDouble(), 0.0001);
        Assert.assertEquals(startTs + 2 * interval, result.get("temperature").get(1).get("ts").asLong());
    }

    @Test
    public void testTimeseriesFillValidationAndNonNumericSkip() throws Exception {
        loginTenantAdmin();
        Device device = createDevice();
        long startTs = 1_700_040_000_000L;
        long endTs = startTs + TimeUnit.MINUTES.toMillis(1);

        doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() +
                "/values/timeseries/fill?keys=temperature&startTs={startTs}&endTs={endTs}" +
                "&interval=60000&agg=SUM&fillMissing=true", startTs, endTs)
                .andExpect(status().isBadRequest());

        tsService.save(tenantId, device.getId(),
                new BasicTsKvEntry(startTs, new StringDataEntry("temperature", "warm"))).get();
        ObjectNode result = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() +
                        "/values/timeseries/fill?keys=temperature&startTs={startTs}&endTs={endTs}" +
                        "&interval=60000&agg=AVG&fillMissing=true",
                ObjectNode.class, startTs, endTs);

        Assert.assertTrue(result.isEmpty());
    }

    @Test
    public void testTimeseriesFillNotFoundReturnsJsonError() throws Exception {
        loginTenantAdmin();
        long startTs = 1_700_050_000_000L;
        long endTs = startTs + TimeUnit.MINUTES.toMillis(1);

        doGetAsync("/api/plugins/telemetry/DEVICE/" + UUID.randomUUID() +
                "/values/timeseries/fill?keys=temperature&startTs={startTs}&endTs={endTs}" +
                "&interval=60000&agg=NONE&fillMissing=false", startTs, endTs)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Device with requested id wasn't found!"))
                .andExpect(jsonPath("$.errorCode").value(32))
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    public void testTimeseriesFirstOfIntervalSupportsCrossDayRange() throws Exception {
        loginTenantAdmin();
        Device device = createDevice();
        long startTs = 1_700_000_000_000L;
        long interval = TimeUnit.HOURS.toMillis(12);
        long endTs = startTs + TimeUnit.HOURS.toMillis(26);

        tsService.save(tenantId, device.getId(),
                new BasicTsKvEntry(startTs + TimeUnit.HOURS.toMillis(1), new LongDataEntry("temperature", 10L))).get();
        tsService.save(tenantId, device.getId(),
                new BasicTsKvEntry(startTs + TimeUnit.HOURS.toMillis(25), new LongDataEntry("temperature", 20L))).get();

        ObjectNode result = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() +
                        "/values/timeseries/firstOfInterval?keys=temperature&startTs={startTs}&endTs={endTs}" +
                        "&interval={interval}&useStrictDataTypes=true",
                ObjectNode.class, startTs, endTs, interval);

        Assert.assertEquals(2, result.get("temperature").size());
        Assert.assertEquals(startTs, result.get("temperature").get(0).get("ts").asLong());
        Assert.assertEquals(startTs + TimeUnit.HOURS.toMillis(1),
                result.get("temperature").get(0).get("originalTs").asLong());
        Assert.assertEquals(startTs + TimeUnit.HOURS.toMillis(24),
                result.get("temperature").get(1).get("ts").asLong());
        Assert.assertEquals(startTs + TimeUnit.HOURS.toMillis(25),
                result.get("temperature").get(1).get("originalTs").asLong());
    }

    @Test
    public void testDeleteAllTelemetryWithLatest() throws Exception {
        loginTenantAdmin();
        Device device = createDevice();

        SingleEntityFilter filter = new SingleEntityFilter();
        filter.setSingleEntity(device.getId());

        getWsClient().subscribeLatestUpdate(List.of(new EntityKey(TIME_SERIES, "data")), filter);

        getWsClient().registerWaitForUpdate(1);

        long startTs = System.currentTimeMillis();

        String testBody = "{\"data\": \"value\"}";
        doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/timeseries/smth", testBody, String.class, status().isOk());

        long endTs = System.currentTimeMillis();

        ObjectNode latest = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/values/timeseries?keys=data", ObjectNode.class);

        Assert.assertNotNull(latest);
        var data = latest.get("data");
        Assert.assertNotNull(data);

        Assert.assertEquals("value", data.get(0).get("value").asText());

        ObjectNode timeseries = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/values/timeseries?keys=data&startTs={startTs}&endTs={endTs}", ObjectNode.class, startTs, endTs);

        Assert.assertNotNull(timeseries);

        Assert.assertEquals("value", timeseries.get("data").get(0).get("value").asText());

        doDeleteAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/timeseries/delete?keys=data&deleteAllDataForKeys=true", String.class);

        latest = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/values/timeseries?keys=data", ObjectNode.class);

        Assert.assertTrue(latest.get("data").get(0).get("value").isNull());

        timeseries = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/values/timeseries?keys=data&startTs={startTs}&endTs={endTs}", ObjectNode.class, startTs, endTs);

        Assert.assertTrue(timeseries.isEmpty());
    }

    @Test
    public void testDeleteAllTelemetryWithoutLatest() throws Exception {
        loginTenantAdmin();
        Device device = createDevice();

        SingleEntityFilter filter = new SingleEntityFilter();
        filter.setSingleEntity(device.getId());

        getWsClient().subscribeLatestUpdate(List.of(new EntityKey(TIME_SERIES, "data")), filter);

        getWsClient().registerWaitForUpdate(1);

        long startTs = System.currentTimeMillis();

        String testBody = "{\"data\": \"value\"}";
        doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/timeseries/smth", testBody, String.class, status().isOk());

        long endTs = System.currentTimeMillis();

        ObjectNode latest = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/values/timeseries?keys=data", ObjectNode.class);

        Assert.assertNotNull(latest);

        Assert.assertEquals("value", latest.get("data").get(0).get("value").asText());

        ObjectNode timeseries = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/values/timeseries?keys=data&startTs={startTs}&endTs={endTs}", ObjectNode.class, startTs, endTs);

        Assert.assertNotNull(timeseries);

        Assert.assertEquals("value", timeseries.get("data").get(0).get("value").asText());

        doDeleteAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/timeseries/delete?keys=data&deleteAllDataForKeys=true&deleteLatest=false", String.class);

        latest = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/values/timeseries?keys=data", ObjectNode.class);

        Assert.assertEquals("value", latest.get("data").get(0).get("value").asText());

        timeseries = doGetAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/values/timeseries?keys=data&startTs={startTs}&endTs={endTs}", ObjectNode.class, startTs, endTs);

        Assert.assertTrue(timeseries.isEmpty());
    }

    @Test
    public void testValueConstraintValidator() throws Exception {
        loginTenantAdmin();
        Device device = createDevice();
        String correctRequestBody = "{\"data\": \"value\"}";
        doPostAsync("/api/plugins/telemetry/" + device.getId() + "/SHARED_SCOPE", correctRequestBody, String.class, status().isOk());
        doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/timeseries/smth", correctRequestBody, String.class, status().isOk());
        String invalidRequestBody = "{\"data\": \"<object data=\\\"data:text/html,<script>alert(document)</script>\\\"></object>\"}";
        doPostAsync("/api/plugins/telemetry/" + device.getId() + "/SHARED_SCOPE", invalidRequestBody, String.class, status().isBadRequest());
        doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/timeseries/smth", invalidRequestBody, String.class, status().isBadRequest());
    }

    @Test
    public void testEmptyKeyIsProhibited() throws Exception {
        loginTenantAdmin();
        Device device = createDevice();
        String invalidRequestBody = "{\"\": \"value\"}";
        doPostAsync("/api/plugins/telemetry/" + device.getId() + "/SHARED_SCOPE", invalidRequestBody, String.class, status().isBadRequest());
        doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/timeseries/smth", invalidRequestBody, String.class, status().isBadRequest());

        String invalidRequestBody2 = "{\" \": \"value\"}";
        doPostAsync("/api/plugins/telemetry/" + device.getId() + "/SHARED_SCOPE", invalidRequestBody2, String.class, status().isBadRequest());
        doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId() + "/timeseries/smth", invalidRequestBody2, String.class, status().isBadRequest());
    }

    private Device createDevice() throws Exception {
        String testToken = "TEST_TOKEN";

        Device device = new Device();
        device.setName("My device");
        device.setType("default");

        DeviceCredentials deviceCredentials = new DeviceCredentials();
        deviceCredentials.setCredentialsType(DeviceCredentialsType.ACCESS_TOKEN);
        deviceCredentials.setCredentialsId(testToken);

        SaveDeviceWithCredentialsRequest saveRequest = new SaveDeviceWithCredentialsRequest(device, deviceCredentials);

        return readResponse(doPost("/api/device-with-credentials", saveRequest).andExpect(status().isOk()), Device.class);
    }
}
