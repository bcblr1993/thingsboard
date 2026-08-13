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
package org.thingsboard.server.common.data.trendz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrendzSettingsTest {

    @Test
    void shouldAcceptHttpAndHttpsUrls() {
        assertThat(new TrendzSettings(true, "https://trendz.example.com:18888/path", "key").isBaseUrlValid()).isTrue();
        assertThat(new TrendzSettings(true, "http://127.0.0.1:8888", "key").isBaseUrlValid()).isTrue();
    }

    @Test
    void shouldRejectScriptAndCredentialBearingUrls() {
        assertThat(new TrendzSettings(true, "javascript:alert(1)", "key").isBaseUrlValid()).isFalse();
        assertThat(new TrendzSettings(true, "https://user:password@trendz.example.com", "key").isBaseUrlValid()).isFalse();
        assertThat(new TrendzSettings(true, "//trendz.example.com", "key").isBaseUrlValid()).isFalse();
    }

}
