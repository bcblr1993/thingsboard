--
-- Copyright © 2016-2025 The Thingsboard Authors
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

CREATE TABLE IF NOT EXISTS topology_template (
    id uuid NOT NULL CONSTRAINT topology_template_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    tenant_id uuid,
    name varchar(255),
    type varchar(255),
    configuration jsonb,
    additional_info varchar,
    description varchar(1024),
    model_version varchar(255),
    external_id uuid,
    CONSTRAINT topology_template_name_unq_key UNIQUE (tenant_id, name)
    );