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

CREATE TABLE IF NOT EXISTS menu_settings (
                                             id uuid NOT NULL CONSTRAINT menu_settings_pkey PRIMARY KEY,
                                             created_time bigint NOT NULL,
                                             authority varchar(255),
    menu_config jsonb,
    CONSTRAINT menu_settings_authority_unq_key UNIQUE (authority)
    );

INSERT INTO menu_settings (id, created_time, authority, menu_config)
VALUES
    (
        '29cce5c0-ca6f-11f0-94f2-7f2235032c22'::uuid,
        (extract(epoch from now()) * 1000)::bigint,
        'SYS_ADMIN',
        '[{"id": "home", "selected": true}, {"id": "tenants", "selected": true}, {"id": "tenant_profiles", "selected": true}, {"id": "resources", "pages": [{"id": "widget_library", "pages": [{"id": "widget_types", "selected": true}, {"id": "widgets_bundles", "selected": true}], "selected": true}, {"id": "images", "selected": true}, {"id": "scada_symbols", "selected": true}, {"id": "javascript_library", "selected": true}, {"id": "resources_library", "selected": true}], "selected": true}, {"id": "notifications_center", "pages": [{"id": "notification_inbox", "selected": true}, {"id": "notification_sent", "selected": true}, {"id": "notification_recipients", "selected": true}, {"id": "notification_templates", "selected": true}, {"id": "notification_rules", "selected": true}], "selected": true}, {"id": "mobile_center", "pages": [{"id": "mobile_bundles", "selected": true}, {"id": "mobile_apps", "selected": true}, {"id": "mobile_qr_code_widget", "selected": true}], "selected": true}, {"id": "settings", "pages": [{"id": "general", "selected": true}, {"id": "mail_server", "selected": true}, {"id": "notification_settings", "selected": true}, {"id": "queues", "selected": true}, {"id": "personalization", "selected": true}, {"id": "permission_menu_allocation", "selected": true}], "selected": true}, {"id": "security_settings", "pages": [{"id": "security_settings_general", "selected": true}, {"id": "two_fa", "selected": true}, {"id": "oauth2", "pages": [{"id": "domains", "selected": true}, {"id": "clients", "selected": true}], "selected": true}], "selected": true}]'::jsonb
    ),
    (
        '29ce9370-ca6f-11f0-94f2-7f2235032c22'::uuid,
        (extract(epoch from now()) * 1000)::bigint,
        'TENANT_ADMIN',
        '[{"id": "home", "selected": true}, {"id": "alarms", "selected": true}, {"id": "dashboards", "selected": true}, {"id": "entities", "pages": [{"id": "devices", "selected": true}, {"id": "assets", "selected": true}, {"id": "entity_views", "selected": true}, {"id": "gateways", "selected": true}], "selected": true}, {"id": "profiles", "pages": [{"id": "device_profiles", "selected": true}, {"id": "asset_profiles", "selected": true}], "selected": true}, {"id": "customers", "selected": true}, {"id": "rule_chains", "selected": true}, {"id": "edge_management", "pages": [{"id": "edges", "selected": true}, {"id": "rulechain_templates", "selected": true}], "selected": true}, {"id": "features", "pages": [{"id": "otaUpdates", "selected": true}, {"id": "version_control", "selected": true}], "selected": true}, {"id": "resources", "pages": [{"id": "widget_library", "pages": [{"id": "widget_types", "selected": true}, {"id": "widgets_bundles", "selected": true}], "selected": true}, {"id": "images", "selected": true}, {"id": "scada_symbols", "selected": true}, {"id": "javascript_library", "selected": true}, {"id": "resources_library", "selected": true}], "selected": true}, {"id": "notifications_center", "pages": [{"id": "notification_inbox", "selected": true}, {"id": "notification_sent", "selected": true}, {"id": "notification_recipients", "selected": true}, {"id": "notification_templates", "selected": true}, {"id": "notification_rules", "selected": true}], "selected": true}, {"id": "mobile_center", "pages": [{"id": "mobile_bundles", "selected": true}, {"id": "mobile_apps", "selected": true}], "selected": true}, {"id": "api_usage", "selected": true}, {"id": "settings", "pages": [{"id": "home_settings", "selected": true}, {"id": "notification_settings", "selected": true}, {"id": "repository_settings", "selected": true}, {"id": "auto_commit_settings", "selected": true}, {"id": "trendz_settings", "selected": true}], "selected": true}, {"id": "security_settings", "pages": [{"id": "audit_log", "selected": true}, {"id": "oauth2", "pages": [{"id": "clients", "selected": true}], "selected": true}], "selected": true}]'::jsonb
    ),
    (
        '29d01a10-ca6f-11f0-94f2-7f2235032c22'::uuid,
        (extract(epoch from now()) * 1000)::bigint,
        'CUSTOMER_USER',
        '[{"id": "home", "selected": true}, {"id": "alarms", "selected": true}, {"id": "dashboards", "selected": true}, {"id": "entities", "pages": [{"id": "devices", "selected": true}, {"id": "assets", "selected": true}, {"id": "entity_views", "selected": true}], "selected": true}, {"id": "edge_instances", "selected": true}, {"id": "notifications_center", "pages": [{"id": "notification_inbox", "selected": true}], "selected": true}]'::jsonb
    ) ON CONFLICT (id) DO NOTHING;;

