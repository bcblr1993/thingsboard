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

import { Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { AssetNodeConfig, AssetNodeType } from '@shared/models/topology.models';
import { isObject } from '@core/utils';
import { debounceTime } from 'rxjs/operators';

@Component({
    selector: 'tb-model-node-details',
    templateUrl: './model-node-details.component.html',
    styleUrls: ['./model-node-details.component.scss']
})
export class ModelNodeDetailsComponent implements OnInit, OnChanges {

    @Input() node: AssetNodeConfig;
    @Input() isReadOnly = false;
    @Input() isCountLocked = false; // 第二层节点数量锁定为1
    @Output() nodeChange = new EventEmitter<AssetNodeConfig>();

    editForm: FormGroup;
    assetNodeTypes = Object.values(AssetNodeType);
    private isPatching = false;

    attributeValueTypes = [
        { value: 'STRING', name: 'value.string', icon: 'mdi:format-text' },
        { value: 'INTEGER', name: 'value.integer', icon: 'mdi:numeric' },
        { value: 'DOUBLE', name: 'value.double', icon: 'mdi:numeric' },
        { value: 'BOOLEAN', name: 'value.boolean', icon: 'mdi:checkbox-marked-outline' },
        { value: 'JSON', name: 'value.json', icon: 'mdi:code-json' },
        { value: 'EXPRESSION', name: 'value.expression', icon: 'mdi:function-variant' }
    ];

    constructor(private fb: FormBuilder) {
        this.editForm = this.fb.group({
            type: [AssetNodeType.DEVICE, Validators.required],
            entityTypeLabel: ['', Validators.required],
            namePattern: ['${StationSn}-${Type}${HierarchicalIndex}', Validators.required],
            defaultCount: [1, [Validators.required, Validators.min(1)]],
            profileType: ['default'],
            customProfileName: [''],
            credentialStrategy: ['name'],
            customCredentialName: [''],
            relationAdditionalInfo: [null],
            attributes: this.fb.array([])
        });

        this.editForm.get('profileType').valueChanges.subscribe(type => {
            if (!this.isPatching) {
                this.updateProfileValidators(type);
            }
        });

        this.editForm.get('credentialStrategy').valueChanges.subscribe(strategy => {
            if (!this.isPatching) {
                this.updateCredentialValidators(strategy);
            }
        });
    }

    private updateProfileValidators(type: string) {
        const customNameControl = this.editForm.get('customProfileName');
        if (type === 'custom') {
            customNameControl.setValidators([Validators.required]);
        } else {
            customNameControl.clearValidators();
        }
        customNameControl.updateValueAndValidity({ emitEvent: false });
    }

    private updateCredentialValidators(strategy: string) {
        const customCredentialControl = this.editForm.get('customCredentialName');
        if (strategy === 'custom') {
            customCredentialControl.setValidators([Validators.required]);
        } else {
            customCredentialControl.clearValidators();
        }
        customCredentialControl.updateValueAndValidity({ emitEvent: false });
    }

    ngOnInit(): void {
        this.editForm.valueChanges.pipe(debounceTime(300)).subscribe(() => {
            if (!this.isPatching) {
                this.updateNode();
            }
        });
    }

    ngOnChanges(changes: SimpleChanges): void {
        if (changes.node && this.node) {
            this.patchForm();
        }
        if (changes.isReadOnly || (changes.node && this.node) || changes.isCountLocked) {
            this.updateFormState();
        }
    }

    updateFormState() {
        this.isPatching = true;
        try {
            if (this.isReadOnly) {
                this.editForm.disable({ emitEvent: false });
            } else {
                this.editForm.enable({ emitEvent: false });
                // 第二层节点数量锁定为1
                if (this.isCountLocked) {
                    this.editForm.get('defaultCount').setValue(1, { emitEvent: false });
                    this.editForm.get('defaultCount').disable({ emitEvent: false });
                }
            }
        } finally {
            this.isPatching = false;
        }
    }

    get attributesFormArray(): FormArray {
        return this.editForm.get('attributes') as FormArray;
    }

    patchForm() {
        this.isPatching = true;
        try {
            const profileType = this.node.profileType || 'default';
            const credentialStrategy = this.node.credentialStrategy || 'name';

            this.updateProfileValidators(profileType);
            this.updateCredentialValidators(credentialStrategy);

            this.editForm.patchValue({
                type: this.node.type,
                entityTypeLabel: this.node.entityTypeLabel,
                namePattern: this.node.namePattern || '${StationSn}-${Type}${HierarchicalIndex}',
                defaultCount: this.node.defaultCount,
                profileType: profileType,
                customProfileName: this.node.customProfileName || '',
                credentialStrategy: credentialStrategy,
                customCredentialName: this.node.customCredentialName || '',
                relationAdditionalInfo: this.node.relationAdditionalInfo || null
            }, { emitEvent: false });

            this.attributesFormArray.clear();
            if (this.node.attributes) {
                Object.keys(this.node.attributes).forEach(key => {
                    let value = this.node.attributes[key];
                    let valueType = 'STRING';

                    if (typeof value === 'boolean') {
                        valueType = 'BOOLEAN';
                    } else if (typeof value === 'number') {
                        valueType = value.toString().indexOf('.') === -1 ? 'INTEGER' : 'DOUBLE';
                    } else if (isObject(value)) {
                        valueType = 'JSON';
                    }

                    if (typeof value === 'string' && value.startsWith('EXP::')) {
                        valueType = 'EXPRESSION';
                        value = value.substring(5);
                    }
                    this.attributesFormArray.push(this.fb.group({
                        key: [key, Validators.required],
                        valueType: [valueType],
                        value: [value, Validators.required]
                    }), { emitEvent: false });
                });
            }
        } finally {
            // Give pending debounced changes a moment to flush if they were triggered sync
            setTimeout(() => {
                this.isPatching = false;
            }, 0);
        }
    }
    addAttribute() {
        this.attributesFormArray.push(this.fb.group({
            key: ['', Validators.required],
            valueType: ['STRING'],
            value: ['', Validators.required]
        }));
    }

    removeAttribute(index: number) {
        this.attributesFormArray.removeAt(index);
        this.editForm.markAsDirty();
        this.updateNode();
    }

    updateNode() {
        if (this.editForm.valid) {
            // 使用 getRawValue 以包含由于 isCountLocked 而处于 disabled 状态的控件的值（如 defaultCount）
            const formValue = this.editForm.getRawValue();
            const attributes: Record<string, any> = {};

            formValue.attributes.forEach((attr: any) => {
                if (attr.key && attr.value !== null && attr.value !== undefined && String(attr.value).trim() !== '') {
                    if (attr.valueType === 'EXPRESSION') {
                        attributes[attr.key] = 'EXP::' + attr.value;
                    } else {
                        attributes[attr.key] = attr.value;
                    }
                }
            });

            // Update the node object directly (by reference) so tree updates
            // Also emit event if parent needs to react specifically

            this.node.type = formValue.type;
            this.node.entityTypeLabel = formValue.entityTypeLabel;
            this.node.namePattern = formValue.namePattern;
            this.node.defaultCount = formValue.defaultCount;
            this.node.profileType = formValue.profileType;
            this.node.customProfileName = formValue.customProfileName;
            this.node.credentialStrategy = formValue.credentialStrategy;
            this.node.customCredentialName = formValue.customCredentialName;
            this.node.relationAdditionalInfo = formValue.relationAdditionalInfo;
            this.node.attributes = attributes;

            this.nodeChange.emit(this.node);
        }
    }

    getSelectedIcon(valueType: string): string {
        const vt = this.attributeValueTypes.find(t => t.value === valueType);
        return vt ? vt.icon : 'mdi:format-text';
    }

    getSelectedName(valueType: string): string {
        const vt = this.attributeValueTypes.find(t => t.value === valueType);
        return vt ? vt.name : 'value.string';
    }
}
