
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
