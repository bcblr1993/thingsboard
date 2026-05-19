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
import { HttpClient } from '@angular/common/http';

const IDLE_EVENTS: (keyof DocumentEventMap)[] = ['mousemove', 'keydown', 'click', 'touchstart'];

@Injectable({
    providedIn: 'root'
})
export class IdleService {

    private timeoutMs = 10 * 60 * 1000;
    private timer: ReturnType<typeof setTimeout> | null = null;
    private timeoutCallback: (() => void) | null = null;
    private boundHandleActivity: () => void;

    constructor(private ngZone: NgZone,
                private http: HttpClient) {
        this.boundHandleActivity = this.handleActivity.bind(this);
    }

    fetchConfigAndStart(timeoutCallback: () => void): void {
        this.http.get<number>('/api/admin/idleTimeout').subscribe(
            (idleMinutes) => {
                if (idleMinutes && idleMinutes > 0) {
                    this.startMonitoring(timeoutCallback, idleMinutes * 60 * 1000);
                } else {
                    this.stopMonitoring();
                }
            },
            () => {
                this.startMonitoring(timeoutCallback);
            }
        );
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
