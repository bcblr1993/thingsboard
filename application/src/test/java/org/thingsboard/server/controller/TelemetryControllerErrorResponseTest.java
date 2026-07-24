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

import com.google.common.util.concurrent.FutureCallback;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.DeferredResult;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.exception.EntityNotFoundException;
import org.thingsboard.server.exception.ThingsboardErrorResponse;

import java.util.List;

public class TelemetryControllerErrorResponseTest {

    @Test
    @SuppressWarnings("unchecked")
    public void testTimeseriesFillNotFoundErrorIsWrappedAsJsonModel() {
        TelemetryController controller = new TelemetryController();
        DeferredResult<ResponseEntity> response = new DeferredResult<>();
        FutureCallback<List<TsKvEntry>> callback = (FutureCallback<List<TsKvEntry>>) ReflectionTestUtils.invokeMethod(
                controller, "getTimeseriesFillCallback", response, false);

        Assert.assertNotNull(callback);
        callback.onFailure(new EntityNotFoundException("Device with requested id wasn't found!"));

        ResponseEntity<?> responseEntity = (ResponseEntity<?>) response.getResult();
        Assert.assertNotNull(responseEntity);
        Assert.assertEquals(HttpStatus.NOT_FOUND, responseEntity.getStatusCode());
        Assert.assertTrue(responseEntity.getBody() instanceof ThingsboardErrorResponse);

        ThingsboardErrorResponse errorResponse = (ThingsboardErrorResponse) responseEntity.getBody();
        Assert.assertEquals(HttpStatus.NOT_FOUND.value(), errorResponse.getStatus().intValue());
        Assert.assertEquals("Device with requested id wasn't found!", errorResponse.getMessage());
        Assert.assertEquals(ThingsboardErrorCode.ITEM_NOT_FOUND, errorResponse.getErrorCode());
        Assert.assertTrue(errorResponse.getTimestamp() > 0);
    }

}
