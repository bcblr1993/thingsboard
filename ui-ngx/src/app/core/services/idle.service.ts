///
/// Copyright © 2016-2025 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { Injectable, NgZone } from '@angular/core';

const DEFAULT_IDLE_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes

const IDLE_EVENTS: (keyof DocumentEventMap)[] = ['mousemove', 'keydown', 'click', 'touchstart'];

@Injectable({
    providedIn: 'root'
})
export class IdleService {

    private timeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
    private timer: ReturnType<typeof setTimeout> | null = null;
    private timeoutCallback: (() => void) | null = null;
    private boundHandleActivity: () => void;

    constructor(private ngZone: NgZone) {
        this.boundHandleActivity = this.handleActivity.bind(this);
    }

    startMonitoring(timeoutCallback: () => void, timeoutMs?: number): void {
        this.stopMonitoring();
        this.timeoutCallback = timeoutCallback;
        if (timeoutMs && timeoutMs > 0) {
            this.timeoutMs = timeoutMs;
        }
        for (const event of IDLE_EVENTS) {
            document.addEventListener(event, this.boundHandleActivity, { passive: true });
        }
        this.startTimer();
    }

    stopMonitoring(): void {
        this.clearTimer();
        for (const event of IDLE_EVENTS) {
            document.removeEventListener(event, this.boundHandleActivity);
        }
        this.timeoutCallback = null;
    }

    private handleActivity(): void {
        this.startTimer();
    }

    private startTimer(): void {
        this.clearTimer();
        this.ngZone.runOutsideAngular(() => {
            this.timer = setTimeout(() => {
                this.ngZone.run(() => {
                    if (this.timeoutCallback) {
                        this.timeoutCallback();
                    }
                });
            }, this.timeoutMs);
        });
    }

    private clearTimer(): void {
        if (this.timer) {
            clearTimeout(this.timer);
            this.timer = null;
        }
    }
}
