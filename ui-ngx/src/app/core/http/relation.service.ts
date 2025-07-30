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

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class RelationService {

  constructor(private http: HttpClient) { }

  public bulkImport(file: File): Observable<any> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    return this.http.post<any>('/api/relations/bulk_import', formData);
  }

  public validateAssetsDevices(deviceFile: File, assetFile: File, relationFile: File): Observable<any> {
    const formData = new FormData();
    formData.append('device', deviceFile, deviceFile.name);
    formData.append('asset', assetFile, assetFile.name);
    formData.append('relation', relationFile, relationFile.name);
    return this.http.post<any>('/api/relations/tree_view', formData);
  }
}
