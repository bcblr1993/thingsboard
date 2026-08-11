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
package org.thingsboard.server.exception;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;

import java.nio.charset.StandardCharsets;

public class ThingsboardErrorResponseHandlerTest {

    @Test
    public void testChineseErrorMessageIsWrittenAsUtf8() {
        ThingsboardErrorResponseHandler handler = new ThingsboardErrorResponseHandler();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new ThingsboardException("interval不能小于60000", ThingsboardErrorCode.BAD_REQUEST_PARAMS), response);

        Assert.assertEquals(StandardCharsets.UTF_8.name(), response.getCharacterEncoding());
        String content = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        Assert.assertTrue(content, content.contains("\"message\":\"interval不能小于60000\""));
    }

}
