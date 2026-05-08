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
package org.thingsboard.server.service.install;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceProfileProvisionType;
import org.thingsboard.server.common.data.DeviceProfileType;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.TenantProfile;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.device.profile.AlarmCondition;
import org.thingsboard.server.common.data.device.profile.AlarmConditionFilter;
import org.thingsboard.server.common.data.device.profile.AlarmConditionFilterKey;
import org.thingsboard.server.common.data.device.profile.AlarmConditionKeyType;
import org.thingsboard.server.common.data.device.profile.AlarmRule;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileConfiguration;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.DeviceProfileAlarm;
import org.thingsboard.server.common.data.device.profile.DeviceProfileData;
import org.thingsboard.server.common.data.device.profile.DisabledDeviceProfileProvisionConfiguration;
import org.thingsboard.server.common.data.device.profile.SimpleAlarmConditionSpec;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributesSaveResult;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.TimeseriesSaveResult;
import org.thingsboard.server.common.data.mobile.app.MobileApp;
import org.thingsboard.server.common.data.page.PageDataIterable;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.query.BooleanFilterPredicate;
import org.thingsboard.server.common.data.query.DynamicValue;
import org.thingsboard.server.common.data.query.DynamicValueSourceType;
import org.thingsboard.server.common.data.query.EntityKeyValueType;
import org.thingsboard.server.common.data.query.FilterPredicateValue;
import org.thingsboard.server.common.data.query.NumericFilterPredicate;
import org.thingsboard.server.common.data.queue.ProcessingStrategy;
import org.thingsboard.server.common.data.queue.ProcessingStrategyType;
import org.thingsboard.server.common.data.queue.Queue;
import org.thingsboard.server.common.data.queue.SubmitStrategy;
import org.thingsboard.server.common.data.queue.SubmitStrategyType;
import org.thingsboard.server.common.data.rule.RuleChainType;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.UserCredentials;
import org.thingsboard.server.common.data.security.model.JwtSettings;
import org.thingsboard.server.common.data.tenant.profile.DefaultTenantProfileConfiguration;
import org.thingsboard.server.common.data.tenant.profile.TenantProfileData;
import org.thingsboard.server.common.data.tenant.profile.TenantProfileQueueConfiguration;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.customer.CustomerService;
import org.thingsboard.server.dao.device.DeviceConnectivityConfiguration;
import org.thingsboard.server.dao.device.DeviceCredentialsService;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.mobile.MobileAppDao;
import org.thingsboard.server.dao.notification.NotificationSettingsService;
import org.thingsboard.server.dao.notification.NotificationTargetService;
import org.thingsboard.server.dao.queue.QueueService;
import org.thingsboard.server.dao.rule.RuleChainService;
import org.thingsboard.server.dao.settings.AdminSettingsService;
import org.thingsboard.server.dao.tenant.TenantProfileService;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.service.security.auth.jwt.settings.JwtSettingsService;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.thingsboard.common.util.DebugModeUtil.DEBUG_MODE_DEFAULT_DURATION_MINUTES;
import static org.thingsboard.server.common.data.DataConstants.DEFAULT_DEVICE_TYPE;
import static org.thingsboard.server.service.security.auth.jwt.settings.DefaultJwtSettingsService.isSigningKeyDefault;
import static org.thingsboard.server.service.security.auth.jwt.settings.DefaultJwtSettingsService.validateKeyLength;

@Service
@Profile("install")
@Slf4j
@RequiredArgsConstructor
public class DefaultSystemDataLoaderService implements SystemDataLoaderService {

    public static final String CUSTOMER_CRED = "customer";
    public static final String ACTIVITY_STATE = "active";

    private final InstallScripts installScripts;
    private final UserService userService;
    private final AdminSettingsService adminSettingsService;
    private final TenantService tenantService;
    private final TenantProfileService tenantProfileService;
    private final CustomerService customerService;
    private final DeviceService deviceService;
    private final DeviceProfileService deviceProfileService;
    private final AttributesService attributesService;
    private final DeviceCredentialsService deviceCredentialsService;
    private final RuleChainService ruleChainService;
    private final TimeseriesService tsService;
    private final DeviceConnectivityConfiguration connectivityConfiguration;
    private final QueueService queueService;
    private final JwtSettingsService jwtSettingsService;
    private final MobileAppDao mobileAppDao;
    private final NotificationSettingsService notificationSettingsService;
    private final NotificationTargetService notificationTargetService;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Value("${state.persistToTelemetry:false}")
    @Getter
    private boolean persistActivityToTelemetry;

    @Value("${security.jwt.tokenExpirationTime:600}")
    private Integer tokenExpirationTime;
    @Value("${security.jwt.refreshTokenExpTime:108000}")
    private Integer refreshTokenExpTime;
    @Value("${security.jwt.tokenIssuer:thingsboard.io}")
    private String tokenIssuer;
    @Value("${security.jwt.tokenSigningKey:thingsboardDefaultSigningKey}")
    private String tokenSigningKey;

    @Bean
    protected BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private ExecutorService tsCallBackExecutor;

    @PostConstruct
    public void initExecutor() {
        tsCallBackExecutor = Executors.newSingleThreadExecutor(ThingsBoardThreadFactory.forName("sys-loader-ts-callback"));
    }

    @PreDestroy
    public void shutdownExecutor() {
        if (tsCallBackExecutor != null) {
            tsCallBackExecutor.shutdownNow();
        }
    }

    @Override
    public void createSysAdmin() {
        createUser(Authority.SYS_ADMIN, null, null, "sysadmin@thingsboard.org", "sysadmin");
    }

    @Override
    public void createDefaultTenantProfiles() throws Exception {
        tenantProfileService.findOrCreateDefaultTenantProfile(TenantId.SYS_TENANT_ID);

        TenantProfileData isolatedRuleEngineTenantProfileData = new TenantProfileData();
        DefaultTenantProfileConfiguration configuration = new DefaultTenantProfileConfiguration();
        configuration.setMaxDebugModeDurationMinutes(DEBUG_MODE_DEFAULT_DURATION_MINUTES);
        isolatedRuleEngineTenantProfileData.setConfiguration(configuration);

        TenantProfileQueueConfiguration mainQueueConfiguration = new TenantProfileQueueConfiguration();
        mainQueueConfiguration.setName(DataConstants.MAIN_QUEUE_NAME);
        mainQueueConfiguration.setTopic(DataConstants.MAIN_QUEUE_TOPIC);
        mainQueueConfiguration.setPollInterval(25);
        mainQueueConfiguration.setPartitions(10);
        mainQueueConfiguration.setConsumerPerPartition(true);
        mainQueueConfiguration.setPackProcessingTimeout(2000);
        SubmitStrategy mainQueueSubmitStrategy = new SubmitStrategy();
        mainQueueSubmitStrategy.setType(SubmitStrategyType.BURST);
        mainQueueSubmitStrategy.setBatchSize(1000);
        mainQueueConfiguration.setSubmitStrategy(mainQueueSubmitStrategy);
        ProcessingStrategy mainQueueProcessingStrategy = new ProcessingStrategy();
        mainQueueProcessingStrategy.setType(ProcessingStrategyType.SKIP_ALL_FAILURES);
        mainQueueProcessingStrategy.setRetries(3);
        mainQueueProcessingStrategy.setFailurePercentage(0);
        mainQueueProcessingStrategy.setPauseBetweenRetries(3);
        mainQueueProcessingStrategy.setMaxPauseBetweenRetries(3);
        mainQueueConfiguration.setProcessingStrategy(mainQueueProcessingStrategy);

        isolatedRuleEngineTenantProfileData.setQueueConfiguration(Collections.singletonList(mainQueueConfiguration));

        TenantProfile isolatedTbRuleEngineProfile = new TenantProfile();
        isolatedTbRuleEngineProfile.setDefault(false);
        isolatedTbRuleEngineProfile.setName("Isolated TB Rule Engine");
        isolatedTbRuleEngineProfile.setDescription("Isolated TB Rule Engine tenant profile");
        isolatedTbRuleEngineProfile.setIsolatedTbRuleEngine(true);
        isolatedTbRuleEngineProfile.setProfileData(isolatedRuleEngineTenantProfileData);

        try {
            tenantProfileService.saveTenantProfile(TenantId.SYS_TENANT_ID, isolatedTbRuleEngineProfile);
        } catch (DataValidationException e) {
            log.warn(e.getMessage());
        }
    }

    @Override
    public void createAdminSettings() throws Exception {
        AdminSettings generalSettings = new AdminSettings();
        generalSettings.setTenantId(TenantId.SYS_TENANT_ID);
        generalSettings.setKey("general");
        ObjectNode node = JacksonUtil.newObjectNode();
        node.put("baseUrl", "http://localhost:8080");
        node.put("prohibitDifferentUrl", false);
        generalSettings.setJsonValue(node);
        adminSettingsService.saveAdminSettings(TenantId.SYS_TENANT_ID, generalSettings);

        AdminSettings mailSettings = new AdminSettings();
        mailSettings.setTenantId(TenantId.SYS_TENANT_ID);
        mailSettings.setKey("mail");
        node = JacksonUtil.newObjectNode();
        node.put("mailFrom", "ThingsBoard <sysadmin@localhost.localdomain>");
        node.put("smtpProtocol", "smtp");
        node.put("smtpHost", "localhost");
        node.put("smtpPort", "25");
        node.put("timeout", "10000");
        node.put("enableTls", false);
        node.put("username", "");
        node.put("password", "");
        node.put("tlsVersion", "TLSv1.2");//NOSONAR, key used to identify password field (not password value itself)
        node.put("enableProxy", false);
        node.put("showChangePassword", false);
        mailSettings.setJsonValue(node);
        adminSettingsService.saveAdminSettings(TenantId.SYS_TENANT_ID, mailSettings);

        AdminSettings connectivitySettings = new AdminSettings();
        connectivitySettings.setTenantId(TenantId.SYS_TENANT_ID);
        connectivitySettings.setKey("connectivity");
        connectivitySettings.setJsonValue(JacksonUtil.valueToTree(connectivityConfiguration.getConnectivity()));
        adminSettingsService.saveAdminSettings(TenantId.SYS_TENANT_ID, connectivitySettings);

        AdminSettings personalizationSettings = new AdminSettings();
        personalizationSettings.setTenantId(TenantId.SYS_TENANT_ID);
        personalizationSettings.setKey("personalization");
        ObjectNode personalizationNode = JacksonUtil.newObjectNode();
        personalizationNode.put("title", "国能日新物联网云平台");
        personalizationNode.put("favicon", "data:image/vnd.microsoft.icon;base64,AAABAAEAICAAAAEAIACoEAAAFgAAACgAAAAgAAAAQAAAAAEAIAAAAAAAABAAABMLAAATCwAAAAAAAAAAAAD///8A////ACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcM2Iw3DYyMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwD///8A////AP///wD///8AIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DGiMNw+kjDcP/Iw3DVCMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DAP///wD///8A////AP///wAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACUOxD8eCMLuDQC9/xkCwP8mDsT/Iw3DeSMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMA////AP///wD///8A////ACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAkDsN4IAzC/xQAv/87KMr/GwTB/xgGwP8lD8T/Iw3DsCMNwxojDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwD///8A////AP///wD///8AIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcM/JA3DyiQMw/8AALb/joPh//39///Dvu//AAC5/xcAwP8lDsP/Iw3D9iMNw28jDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DAP///wD///8A////AP///wAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMmJA3DoSYPxP8SAL//AAC3/0IyzP////////////////+LfeD/AAC6/wAAu/8jDMP/JQ7D/yMNw8wjDcNNIw3DACMNwwAjDcMAIw3DACMNwwAjDcMA////AP///wD///8A////ACMNwwAjDcMAIw3DACMNwwAjDcM9Iw3DnCYPxP8YAcD/AAC5/ywhx/+qpuj/1M/0/4aC4f+8uO7/gnvf/7Kv7P/MyfH/Y1vW/wAAvP8DALz/JA3D/yQOw/8jDcPCIw3DYCMNwwgjDcMAIw3DACMNwwD///8A////AP///wD///8AIw3DByMNwykjDcN1IgzDyyEKwv8fB8L/AAC4/xsOw/+cl+X/1tP0/6Sh6f8iH8v/AADA/wUDxv8AAMP/AADE/2xr3f/NyvP/wsDv/15W1f8AALv/BwC8/wEAuv8CALr/IQnC5yYPxJQjDcNDIw3DEP///wD///8A////AP///wAjDcN8Iw3D/ygOxP8PAL7/BAC7/wgAvP+Aed3/19T0/7Ct7P8zMdD/AADD/xAZzv8gL9X/GCrU/x0v1v8bLNT/AALJ/woNyv94d+H/z8zz/7m27f85L8v/ZlfW/2NT1f8QAL7/IQvC/yMNw/8jDcOC////AP///wD///8A////ACMNw1kjDcP/DQO9/2pc1/+2sev/wr/u/8XA8P9MR9T/AADB/woTzP8eMtf/Gjvb/xdC4P8XRuL/F0fj/xdD4f8cPt3/Fy3W/wAAyP8MDMj/hIDi//Du+////////////4B13f8EALr/IgzD/yMNw1z///8A////AP///wD///8AIw3DGCIMw/8AALf/y8bx///////c2Pb/AAC4/wIAxf8fLNP/GzbZ/xdD4f8UUej/E1fr/xJa6/8SWuv/E1jr/xRS6f8XRuP/Hz7c/wAPzv8AALv/9vT8////////////3tn2/wAAuf8hC8P/Iw3DH////wD///8A////AP///wAjDcMAIw3D5hUHv/9GNM3/ioDg/zouzf8NC8b/HynR/xo12f8WR+L/E1fr/xJf6/8FXuf/AFvl/wBf5f8QZej/E2Lr/w5W6/8AJ+D/GTXa/6+v7f/Y1vb///////////9sYdj/BwC7/yMNw+wjDcMB////AP///wD///8A////ACMNwwAjDcPGKQ/F/xEAv/8AALv/DwTE/yUm0P8cL9b/F0Th/xRX6/8TYer/AFjj/wd25P8skOj/GIfm/wBq4f8AWOL/AE3m/32d8//f5vz/oazw/xQYzf9KRNT/SDrQ/w0Avv8kDMP/Iw3DziMNwwD///8A////AP///wD///8AIw3DACMNw7EjDcP/Iw/E/yISxv8gGsr/HibQ/xo42v8VUOf/E2Dr/wBX5P9DmOr/1u36///////s+f3/a7nu/zeI5//K3Pr/yd77/0R17f8AEdX/DxrP/wQAxP8HAMD/Iw7E/yUOw/8jDcO4Iw3DAP///wD///8A////AP///wAjDcMAIw3DoSMNw/8jD8T/IhTH/yAcy/8dKdL/GD7d/xNW6v8EWOf/Hnrn////////////////////////////2PP7/26t7v8ASuT/AELn/xpG4f8dLdT/Hx7M/yEWx/8jEMX/Iw3D/yMNw6sjDcMA////AP///wD///8A////ACMNwwAjDcOaIw3D/yIPxP8hFcf/IB3L/x0q0/8YQeD/E1jq/wBN4/92s/H///////////////////////////+w3vb/AFvc/wxl5/8TXOr/F0fj/xwu1P8fH83/IRbI/yIQxf8jDcP/Iw3DoSMNwwD///8A////AP///wD///8AIw3DACMNw5ojDcP/Ig/E/yEVx/8gHcv/HSrT/xhB4P8VWuv/AEXj/1Ca7f///////////////////////////8Hn+P8AbuD/D2bn/xJa6v8XR+L/HC7U/x8fzf8hFsj/IhDF/yMNw/8jDcOhIw3DAP///wD///8A////AP///wAjDcMAIw3DoCMNw/8jD8T/IhTH/yAby/8fKtL/GDzc/wA15P8BUuf/n8v1////////////////////////////WbHs/wBl4v8RY+n/E1jq/xhC4P8dK9T/IB3M/yEVx/8jEMX/Iw3D/yMNw6sjDcMA////AP///wD///8A////ACMNwwAjDcOwJQ7D/x4Jw/8WBcP/IRnJ/wgOy/8AENL/bJDw/9rq/v+WuvX/SZLr/9Tr+v//////6/j8/3u+8P8AbOH/DWjn/xJe6/8UUuj/GTnb/x0n0f8gG8r/IhTH/yMPxP8jDcP/Iw3DuCMNwwD///8A////AP///wD///8AIw3DACcPxMYYBMD/DAC9/yEPxf8AAL7/MDHS/8PK9f/S1/f/VHvu/wBB5v8AT+P/B3Hl/yyL6f8Ygeb/AGLh/wxm5v8UYer/E1fr/xdF4f8eMtb/HB/O/wYAwv8UA8L/JxDF/yMNw/8jDcPOIw3DAP///wD///8A////AP///wAjDcMAHAfB5BkKwP+vqOn/4+D4/6ek6f/OyvL/lJPn/wAZ0v8AJ9z/FFXq/xNe7P8GW+j/AFfm/wBc5/8RYun/El7r/xNW6v8WR+P/GzXZ/xwm0f8VEMj/RjvQ/ykXxv8aC8H/JA7D/yMNw+sjDcMB////AP///wD///8A////ACMNwxYAALj/dGTa/////////////////05E0v8AALj/GiXR/x401/8ZPt7/FUzm/xRU6v8TVur/E1bq/xNV6f8VTuf/GEHg/x022P8aJdH/AAC7/2Vd2f//////19P0/w0Avf8eCML/Iw3D/yMNwx7///8A////AP///wD///8AIw3DWwUAuv9XStL/////////////////pp7p/w0Jwv8AAMH/Fx3O/x8u1P8bNdn/GT3d/xhB3/8YQd//GT7e/x052v8ZK9T/AADH/wYFxv9zcN3/6OX6///////Iw/D/DgC9/x8Iwv8jDcP/Iw3DXf///wD///8A////AP///wAjDcNvIwvD+g4Avf93adr/rqbp/2VY1f+ZlOP/1dL0/4aC4f8VD8f/AADD/xgezv8fKtL/DxzQ/xgl0v8eKtP/AAHH/wYHx/9wbd7/ycfy/8PA7/9MQtD/MCDH/yELwv8cC8H/JA7D/yMNw/8jDcN3////AP///wD///8A////ACMNwwAlD8QaIgzDZAMAuroDALr/AAC5/wAAuP9MQc//ubXs/8zJ8v+JhOP/EArF/wAAwP8iHsz/CAPF/wAAwf9oZdz/ycby/8TC7/9oX9f/AAC7/wUAu/8RAL7/GQLA/yYOxNgjDcOBIw3DMSMNwwf///8A////AP///wD///8AIw3DACMNwwAjDcMAIgzDACELwi0kDsOLJQ3E8wkAvP8AALr/T0XQ/7i06//Fv/D/l5Hl/+jl+f+xrOv/rKjq/8vH8f9pYdf/AgC8/wEAu/8jC8P/JQ7D/yMNw7EjDcNPIw3DACMNwwAjDcMAIw3DAP///wD///8A////AP///wAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMWJA7DjyQMw/8IALz/AAC5/0pFzv////////////////+Th+L/AAC6/wAAuv8iC8P/JQ7D/yMNw7wjDcM8Iw3DACMNwwAjDcMAIw3DACMNwwAjDcMA////AP///wD///8A////ACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DMCQOw7kkC8P/AAC1/1lI0v/X0vT/qaDn/wAAuf8XAcD/JQ7D/yMNw+gjDcNeIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwD///8A////AP///wD///8AIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNw2UnDsT/DQC9/xgBwP8LALz/HQjB/yYPxP8jDcOeIw3DDSMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DAP///wD///8A////AP///wAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACQNwy4iDMPgGgPB/x8Jwv8kDsP/Iw3DaSMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMA////AP///wD///8A////ACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNww0jDcPbIw3D/yMNw0QjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwAjDcMAIw3DACMNwwD///8A////AP///wD///8A////AP///wD///8A////AP///wD///8A////AP///wD///8A////AP///wD///8A////AP///wD///8A////AP///wD///8A////AP///wD///8A////AP///wD///8A////AP///wD///8A////AP///wD///8A//5////8P///+B////AH///AA///AAD//AAAH8AAAAPAAAADwAAAA8AAAAPgAAAD4AAAB+AAAAfgAAAH4AAAB+AAAAfgAAAH4AAAB+AAAAfgAAADwAAAA8AAAAPAAAAD4AAAA/wAAD//AAD//8AD///wB///+B////w///////8=");
        personalizationNode.put("logo", "data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiIHN0YW5kYWxvbmU9Im5vIj8+DQo8IURPQ1RZUEUgc3ZnIFBVQkxJQyAiLS8vVzNDLy9EVEQgU1ZHIDEuMS8vRU4iICJodHRwOi8vd3d3LnczLm9yZy9HcmFwaGljcy9TVkcvMS4xL0RURC9zdmcxMS5kdGQiPg0KPHN2ZyB2ZXJzaW9uPSIxLjEiIGlkPSJMYXllcl8xIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHhtbG5zOnhsaW5rPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hsaW5rIiB4PSIwcHgiIHk9IjBweCIgd2lkdGg9IjYwNHB4IiBoZWlnaHQ9IjIxMnB4IiB2aWV3Qm94PSIwIDAgNjA0IDIxMiIgZW5hYmxlLWJhY2tncm91bmQ9Im5ldyAwIDAgNjA0IDIxMiIgeG1sOnNwYWNlPSJwcmVzZXJ2ZSI+ICA8aW1hZ2UgaWQ9ImltYWdlMCIgd2lkdGg9IjYwNCIgaGVpZ2h0PSIyMTIiIHg9IjAiIHk9IjAiDQogICAgeGxpbms6aHJlZj0iZGF0YTppbWFnZS9wbmc7YmFzZTY0LGlWQk9SdzBLR2dvQUFBQU5TVWhFVWdBQUFsd0FBQURVQ0FRQUFBRGphVFduQUFBQUlHTklVazBBQUhvbUFBQ0FoQUFBK2dBQUFJRG8NCkFBQjFNQUFBNm1BQUFEcVlBQUFYY0p5NlVUd0FBQUFDWWt0SFJBRC9oNC9NdndBQUFBbHdTRmx6QUFBTEVnQUFDeElCMHQxKy9BQUENCkFBZDBTVTFGQitrR0ZRY3FBSDg4YUVvQUFCOXFTVVJCVkhqYTdaMjljdFRLdHNmL3VNaEh2aTlnd1gwQVJIRnppeXJ2bUNFZ3RweHcNClFrUUVHWExHamhEaDNvbmw5QkF3eExqS2NuNWN5QTl3alB3Q0c4MFQ2QWFhd2ZwV3Q5U3RibW5XVDFWZ2V6VDlvWkgrczNyMTZ0VVANClVrd1dBMENpdWhFRVFZelBudW9HOU1aQWlCQ202bVlRQkRFK0R5WnFjUmtJOFFUQUdqWWkxWTBoQ0dKY3BtbHhtUnZaQWhZSVlhbHUNCkRrRVE0ekpGNGJJUWJXUUx5S1JycWJwSkJFR015ZlNFeTBLSVJlRXZDM3lGbzdwWkJFR014OVNFeThHUGtteGxuTUZUM1RTQ0lNWmkNCldzNTVEeDlhWGowbnU0c2dkb01wQ1ZlQTQ0NHpibUJUWkJkQnpKK3BDSmZ4ZXg2eG5Uc3NLVHlDSU9iT05IeGN4WG5FTmc1b2pwRWcNCjVzOFVoTXRCaUFQbXN4ZjRDbGQxa3dtQ2tJbit3dVhockhZZXNZMVBDR0NvYmpoQkVMTFEyOGRsSU1DTG51Kzl3Ukt4Nmc0UUJDRUQNCm5TMHVDMUZ2MlFLZUlDSnZGMEhNRTMyRmk4K3pWY2NDWCtHcjdnWkJFT0xSYzZob3dPK00yV0xsQ2t1SzdTS0llYUdqY0ZrSUdJTWYNCjJGaGppVkIxcHdpQ0VJZCtRMFdYTWRTVW5RVXVhY2hJRUhOQ0w0dHJ5Q3hpRnpkd0tLYWVJT2FCVGhhWGpWaWFiQUZQRUZKZ0trSE0NCkExMkV5NENQUys1QVV6NFcrSVFWQmFZU3hQVFJZNmhvSXhnWStzRE9HZzVXcWp0TUVNUVFkTEM0UEZ5T0psdFpkQmZaWFFReGFWUmINClhHUGFXbm5JN2lLSUNhUFM0c3I4V255eWRZNlgyTWNEUE1CVHZNVk43N296dTh0VTJIdUNJSHFqenVMaXQ3Vyt3YTBzbXg1bXNhM2gNClVZUVhRVXdQTmNKbHd1Y09mSGpiSURHc3VWR2J1SUpMOFYwRU1TMVVEQlhkSGxrZlRoc3Rvd1QyZ0NFamNJZ2Y4TWxaVHhCVFlteUwNCnk0YmZ3ejY2Z3QzNnVvVWZBOXUxaG90ZzNFdEJFRVJmeHJTNFRBUzQ3RFdzOHpwZWozQStzRzBMbkNHRU5lTFZJQWlpTjJOWlhBWmMNCnVEMGo0KzhZWnYrRzIxd1o1M0FwQ1E1QjZNNDRGcGVEQ0I5NkwraFpNWndUQ1dycE1XSjQ1UEVpQ0wyUkwxdzJJcHdOQ2pKTm1NNjYNCkV0VGVCVDRnb2oyeENVSm41QXFYamJDblYrdWVPd1VSN2djNFEwd1o2d2xDVitRSlYrYUtQeHhVeGgxT1lESU9BNWM0eFZwZyt3L3cNCkZXSEhiQ1pCRUVxUTQ1dzM0UTNPR2Q4blFHSElGRUFUVi9BbzhUTkI2SVY0NFJJaldqNzhuck43SXVvdlErSkZFRm9oVnJoVWk1YkkNCmRwUWg4U0lJYlJEbjQ3SVI0dWRBdVZqakZDYThrbXgxSjZDeFNvNzBHQTRlRFE1S0xYS0lTNXB0SkFoTlNFVWN5elJNaDVLa1htcFUNClNuYlNPRTNUTkExYWFqZlNKRTNUTUxVcnIxZ0MybFVtVHAyYWR0SkJCeDBqSGtNTE1INUxpenpSU2x1bHkweWozK2ZVaVpjdFFieVMNCjFFdE4xUjhkSFhUczdqSEV4MlhDaFNOZ0RxOXVtWTBEcnhLMGVnV25rby9MZ1Y5cVFaMHZ5b1kzTURDanZ0MEJlYjFHNHhuKzAzbk8NCi8rS1dxU3lXbS80OS9sVGRaZVY4eDFIbk9SZjRRMWg5V1czN2VBd0FlSVo5QUtndi8ySFBLcFp3aEd3bGRnNlBPVG5nSVg3aUcxYUkNCkVNR0FCUnRPelhtSHVLeVVHc0tXa0NUNkdNZTRnWStWMXFzYlgrT1Y2aWFVdU1BWFJvSEowLzBJM2ZZb2xSaVh4NzlGQ1FEMk4vOXYNCi84b0J2M0NaY0dvRmc1OTYwV3EzalY0d3llVXhqbXZFeTZ5MTQ0YnhCR2Z3c1lLdmJUTENmWVpIZmx5TzhBN3Y4VGYzdTdxNFVOMngNCkFqMGVScUg4d3JYcVN3RGczY1p1MmtxVU9MaEdsc3QwSmNoTEZOVDRpTVI3bytwcUVlR1RxeVBTMUduL1RrcHZoM1BFMVl0OWhoSmYNCk1aZkd3cnZKWC9kL0J2ZmhPME10M3dkZjZTNXFTMllOaDdEZ0k4RlhRY1BEUnhWdlZiYXFVYlFmNmhnL0s3a2VBcGc0d1ozZ21qTGINCjZ4Y0NXdUhJeUY5Y1o3TllqWHBaWE9yWngwZk9xendodW9YTGhJc1lQL0JHeUZLYXE5RkVhOHVIbWtRMUFTekJLeHUzSE9NckVnU1UNCmtyQVR2cUVVeTBEeGwrb3VhWWgrSGs0K2JwdStqdHA4WENhV2NBYm1kc2hUTjk5bndaY21XVnNXK0FDM0ZJK2Z3SU12WVdWalZ0OHgNCmpuR0hGUUp0UFY5RnJvVS85Q3dXMG1NT1p6cUxjTDNlekVMVjhZdmJwOWJNUGpBaGtYeUZMNnFiMEVrbVR0dkpsZXZOdjYzWHVGNjQNClJFdFd2V2pKV0pqVFJDWmV4YzNJWklvWGNJQTNlSU03ckxEU1Btaml2ZkJoMWo3KzJ5SWp2RHhqc000dThLN0Z1cmdWSUZ5UGNZUm4NCk9NSmovREdoWVNucjFFemRlU3lmWVAza0QrdFg0WU8rM1NvTGx3VUhTOEV6YjZwRmE4c0NuK0RDSytTY3lNVExsOWFXVEw3V0cvbEsNClJ1NnhPbjdoV3VCc0prc294RFd1VzRUck1mWjdXMG5QY0lSblRPTFpqNzk3Qllld1hsM1dyNC92UFZ2L3JQYWQwcVY5SzF3R2xyQ3gNCkZHNTc2Q0phV3c1d0JxOGlYZzQ4cVczS0JvL0FGVllJSnpKODFBbTJVSWlMampKNGgweEhHOGtTWnpuVzg2WEhRLzQzL3BIY0tzM1oNCmd3MGZFWDdoRE1mQ00xazloMTJTTFJQQjRLWFlRNm5MYnlwaldYYVZRM3pDRDhRTW0zOFE5N0JFb2wyZ2EzakNHMGYwRWQveEVVZlMNClphc2YwL0d4U1dJUEFkNXdlN1BXdU9vSUtMakRpWmFpdGFVdXYya21YcUp5MXpkalZNSnVpVGJZUXlIYVFpNUZCMEFTU3RuamNoeXYNCmNZNlgySWNCR3lZZTREaysxd2hZbG5BNUtQek5nS2VOYUcwNXhHV05lTmw0TGxtOFF0VWRueGpzb1JBWGcwb2hKc1JEaE14eThybVMNCktTdEVDQmN1dk4rRHpMdVMvd2lRazFCWkZJZTRyUGpoc3BXTk1wWmxiOHZYa1NPQk5rbWZ0WWh0TGV0aUsxalhIZVVNZHhoZjR3TFgNCldpeW1FWTI0cGRKQTF5Y2hnSWVNajlFYXk4WXpmWVFJOEdSeW9yV2xlVm0yTHpRazVMNXNIWG1HajhMS3VoWW9YR3loRU1YL20wcnENCkwxeTMrSUxyV1FlNVRpZkFBd0R3RURIdU9zTWYxckJiNThJaTJGaE9VclMyMUMvTHRrckxzcThRSVlFSmUwREF5SnBtRmJsZ0M0WFkNCmN0RnkvbEdQUkRXM3VNQTFMbllpNzhTNzF1dFFuSk50dDg5SFNBajBFRURVK1JqYW5ROWJVdkZwVFVlMHR0U0pWNEJnSTE3RlYvb24NCnlZbFVkM05pOEdXRmFCTXV2cUh3QmI3c2lHQnRhWXNOdXlnSlY1dDlmanVHY08yaGUraHl5djJ3dVlqeFlXS3lsZEcwTFB0cGFZVmwNCkNLdG44RVNvdW91VGdpVVVJdTlQYVJNYXZ0UXFGNzBDUTZkTVcyL0xWKzZvWnpuQ3lDeXVOdTdnY1pVb1B1ZlYyRlJYTnRaZG93UU8NCnJCNCtzRkIxOXlZRnkxemdMN3pDWTJRWk0vYzdTcHVqVzEwVTdhSmZYSG5RNW5jY3hWdjJFRjJQVXNCUm1zRXc3SndDaTFyeHFyTEUNClQrNnlJOVdkYStCTDVhSCt5R1Nodks4UkEzSHl3Q0pjLzJZdWpXSzUybWovMUlwVEcyM0NOY29FUnJiazU2YkZjZ2c0U2t2R2FQSkkNCjFDM0xMaFBqRzJlR3NqdHRyMUUxOFRIYkRYZ3Q2UnMyRzlpSmpiNGk0V3FqZllpWEY2NmpBZVVJSXN2SEZUVytmc2NaNVIyTzBlalINCldPQlRoNFcwNGl3eDRqeC9WL2tMLytBN3ZndGUyS3c2bmJMZXRBdE9maERlZmhWSEdTcDJDVmZNV1I3ditmcVRDTzF2cExvN0UwSFcNCnR6YkZ6N2ZSSmpsNWE3WE5rempTaEVhWGNDWGpOR05uQ0ZVM1lNY2hpNnNOMW5uRk52a2ZhZm9qODNHRmphOGI0elJEWTh3QnIxYUoNClZIZG5JckIvYjJlNzJWd0F1TVV2M09JV2YrRjE0OWxrY2JYQk9xL1lKdjk4d3NXMnJXdE51c0Z0UHE3dTZQbGQ1UUJteTREUTVpcHINClRSWXNJNzhhLzE2V3FTcnRPU0w2SnhTY1A2enppbTNDTmRKUWNTdGNUV0VNRm1kNTlqak5IaFVQVHNNckptZStpMGgxVnlZR2kweFYNCkVaMVFjSGRnbTFmVVlFNHhMMXoxMC9vTFdCeVBteUY5NHdzVkhDTm9HRXo3bkNWRnFyc3lHUzd3UDcwdG85dldUQWU3RlEzUEI5dTgNCllydWZjRlFmVjlzajVUYmFHMVhNY1JvOU9xdmExWm9COXk2VHNlcU9USWdoQTdxSlpUclFDSmExbm0xemlxT3RUTmp1cXhnM25uSE0NCklVZlJLQmxFeDJlQkVHN2hMeVpISHJON0l0VWRJWWhXL3NDRHhtTnJ4LzdaY3M3L2NkWjMwYkdRL1JZWDlWOUQzUllYNEhQc3poeHYNCkV0eE1jWUYxR3d0OGdvZlZSdUNYUGZOMFJhcTdNU25FWmdqalhUTGQ1c2tSbXloeGwva0R3THVXei9udnBrd1Q5OXVUTmM4cnZvREwNCjVjMVp3WVNITjZxdmlYQVdBMU5QMDV3aUh5eVpJVmc1d212OGk4c3QzNWJrUldTaVJCMFFtZjAyUStRR3ZMWGNDMWZjRWhEeHFaSnYNCnE0d0ZJK2ZBVHVBaUdHR1A2bWtScVc3QVRyT1B2MlluT0tJUWFkdG1YTWdXcnIzZlAwV3Q1NTNCYndsR2RmRURsMWdWdkdFUmJKeGcNCkxiZjVreUpXM1lBZFo3ODF4eWN4S2U0dHJxVGp6RGRZMXVTVVIyNWJpUmV3UzlrVUFxd2s3aEk5TldMVkRlaWduTWFHYmZqd3NUTC8NCjkwWDJ0MjF2S0c1K050d0xWNGdQSGVjZTRBdytWZ2czajZBQkc4dkNBSE9CVDNEZ0ZnYU5EZ0pKbTA1TWpWaDFBenA0MXV2QnJzcmINCm1NRUkxN2xsS04zckVHbWw0bXg0eUhuK2RqUDVacDdnRXVkd2N4WmNDS3V3aGRtdUVxdHV3QXg1bjVQSi96SUwwMUJwRmJ0b2lMS0UNCjlTQnZjWW5qR012U29ORkgwQ05nYzE1RXFoc3djMjZaaFV2c0hvSkRFZTBZNStkTFMrQm9leDdjOXczdmxMNGVsTmZpWW1XQlQxakMNCnpUMnNDWllEOXNhWkE0bnFCaEJFTFcyclFOc2xTRmIrMjA3eXduVWxPSHpoRUQ5S3UxK0hNT0ZOYnRzeU1keXBia0FuNWUvT1p4MWINClQyemZWYjYxOWMrKzhKM2hISDJuR0FqSXM3aTJ2SUVEdHpBWDZTRkFzSU1SWHJIcUJuVHl2dlQ3ZHlabi9mc0pyZ3hrNmRmMGVyVlQNCjdPVitqcVRVc01BWndrS0VWd3diTHlkZ2dZZ2xVZDBBZ3BnUGVZc3JrVmJMSVg3aXRMRFoxd29oM000QWpEa1JxVzRBb1NudmUrVlUNCllCbnV6cGh4aEFzQVBzQ0JtOXNWSitFY05ONGhSSWdZMGFhZEJpeVlzR0h2c0x1Zm1BUEtITnhUSmk5Y2tlUzZEdkFWVjRXdDdHUFkNCmNPQjNPdXZQNFZkYWx5QkV0dXVqQlhjQzBmbng0QkowcGVnTGU5QzduRDY4eWszV1UzaHBmMTQzVHNTMFg5VlhqY0VTa3BkWnkzYk8NCmx6bEVCQjllN2k4QlZxMjVKTTdoZFR6MEVSeDQ4RFFYcjNod0NVU1YxOE9MSUFDODZya2Nxdm42UzE1bW5YZk9KeklyK3MwQ0h4QVgNCmN0TW5jUEcwTmdIaERaNFdMTFJtWWpoNGlwdFJla0FVMFQvOFFSVFA4QTcveGorMDVsRTk4bWNWNnppb3pTWHh0cFJMNHB3cjN6MFENCndjTDVhSDNnSlZiZEFHbU1scTVYR1VjYndmb1BQdUlWVTNRYklabTk0VVgwNUFXaVVqcGtIMlpPZUU0NGN0M2Y0K0JFV1kvYWlWVTMNCmdPRG1DTy93SFNtK2syRHBoanJoeXBZRlJhVkJvNFBudUFGdzBwRzRzSmxBVytraXBzVHJqV0RSc0ZCTGlzNzVtOUhUejlUbmt1QWINCklwWUpZTTB3Y2ZSVU9CcDFjditQWEcxc2tmNnM3TlljNWZ0R2U3TGZJbXZwbnMraWNDVnlLMnZnR012U3NxQm9ZSWt1TE0wV0ZkRzANCkFhRXp6WDVLVFJkWnF4d3EzcE10QzdJRWx1aG9salE2VWQwQWllek9yQ0p3aTcveHJ4MllqdENlc2VPNG1xbm1raGhDREErZlZIZHANClI5aUZEU2l1Y1lIYmpqMEFwMHpUUGovZCt5STF2Vk95TGFhUGNBSEFHMWdGWi8wUWZMaTBHSWdZeURVdWNJMkwyVnVWVGZ2OGROdVcNCit3M3ZsSncxcENoY2tYTFBVQ0N3TEE5bmludERUSmNML0wwRGdqVWNSVmVvNk9OS0ZGK0VkVzRSOW5CV212bTVkb081UkR0ZDRBdkoNCjFnQWszd2Q2T09lM3JJUktaeUpVQm9jUnFtN0FhT3hXR0FIUmhPUXRRUFR5Y2EwRWx4ZHF2dkI2SHFpY1k4cy9Ibk94OVFnRzlCS3UNClVQUHlpRHArRlpJK2p5dGo2bmZJSVpTZ2szQ3RoZnZZWXF4M2NtT09zZmxUZFFPSWdVeHVZWk5Pd2hWSktWUDFQT2xVcU80RXpUYjANCnF2TmxYSk5iZXlaYzRGM0hHWW9pMjNRU0xrSWxyM29PdStyZTlRY2xJOTRabW9STHNnMm4wNnhpcUxvQkJFRk1BNTBzTGtOMUE0akoNCjhYZnVHLzgxaFdMc0Rqb0psNlc2QWNUaytKSWJsQjZSY08wT09nMFZDWUlnbUppN3hVVnppcXhjRjZLeGhqSFhIQW9FRDFKVFN1b2sNClhBdVlnak96VzZxN05DRXVhQ1p3aHhreXlQNmxZczJDWGtORlcvUHlDR0tlMUFzWG0rV3NaTW1YVGhZWHNCU2ExZ2E5OWdtU2c2bTYNCkFRVFJRcjJiZ0MyTStFdURyUzdWWWFDWGNMMFFPbGcwUjkvNm82MHRCS0V2UXhadFNkMnh1b21pY0ZrcW1sREFnU2VzckJnbjhHbXQNCklrSDA0REZlYmY3WGtxSndHYXFiQXhlK3dLWFdBVmJ3YUt1eVRsNUp5SjQwNWcyZnJiTVU2eUwrMkxJRVN1eVNKaGxYZnppUDljNjgNCm9kZFFFVmpBSysxdlBZd0VMZ0w0RkJiUnltdlZEUmhJMzNXV2VqRDFxNjhFdldZVmdXekRETEZFc1BHVzBqZ1R4SHpRVDdpQTFlQWgNCnE0R29aTGY1TUhHdXNFK1d3cm9KWW5ZVWhjdFMzUndBd01IZ0ZNNGhudUFUb2tJY1Z3SUh6NVh0S0UxVEJBUWhrS0p3NmZKNEhRNksNCjV3bzJZUkJQY0ltZ1lMMkZzR2pRU0JEVDUwR2EveTN0VzR3RXp1SDJtRjgwc0NvNTR0ZHdTekpvd3NlTDBmdnpZUFFhcDg0K3cyeGINCmxtdjFNZk1jSmkxckdwZXVkSUlYYVAvMGJwdkNXUFBDWldpV2NQY0dTODV3Vk91M3RWWGtDbTRwTWJTTllPUjlycDlUb2tTQ0VFVisNCnFHaXBia3lKSnhVWGV6c2V3b1pZK1VQOGdGOGFOSm80cFVFalFVd1RIV2NWNzFuZ0UyS21GWWNPWW54bzlkRzlRWXhsNFM4ZUxGeXANCjdpSkJFUHpraGN0VTNaaGFEbkNHR0Y2alBXakJSNHd6aG9IZkFsOFJGbm9adzhaTDNJM1NEM3VVV2doaUo4ajd1RHg4VU4yY1Z1NFENCklVSzg4WHVaTUdIQjZ1R3BPaTB0S3pMZ2p0RHpVNEdyTUFsaXg1bVNjSW5qRG00cFZzeEVJSGxaMExsR1NYWUlZdUxvN0p5WHh3RysNCllsVVpOSjVJZGRhYmcwc2dDR0pEWHJnTTFZMFpsUmVJU29PM0FDWStxMjRXUVJEZDVJZUs4Y2lSVFRwd0I2Y1VYMlZKeXlWQklhZ0UNCklZaTh4YlY3c2dVYzRMSzBxTHM3bDhRTnhYOFJoRnJ1aGN0VTNSUmx2RURNbEV2aUcwN3dDQTlnd2NBRFBNVm56a0FLVzNWSENXSXUNCmtIQUJXYUJyZXk2Skt6ekNFa0Z1Q1ZJRUY2WmtoejVCRUxXUWNHMXB5eVh4Rm5iRHFza0FKbk9xSEZ0MUZ3bGlMcEJ3NVRtdUxERHkNClllRWwvSmIzSkxDVXBpZ2tpQjNrZmxaeHhaSHE1UTR4QUV1Yi9GMWlxZWFTNk1Kb1hONWRMTmRXM1RXQ21BZjNGcGZCK0k1elBJSUoNCkd6WU1QTVUzMVIyUVFEV1hSQmNKVTFROFQ0a0VRYlJ3YjNHeEpCRmN3NjdZSWc3T1ZIZENDbXM0WENta0F4eDNua09SWEFRaGhLM0YNClpUS2NXeWRiUUlBVDFaMlF3Z0pmdVpaRnM1eHJxZTRVUWN3REh1SHlHancvd1d5ZDB4SEh1VEhEN0tLcHVrTUVNUSsyd21WMW5ublgNCk1yZm1xZTZHSkZhQ3o3WlVkNGdnNWdHN2NLMWFYbU94TnFZSGI1K2l6ak1zMVYwaWlIbndjUE8vMlhsbTNQb3FTempBMUVpRW4yK3ENCjd0THZkdHkzSk9FTS9TQUlEZGdLVjNjK2hMajExVVIxUnlhQmVuRzM0V0JaaWIrN1FqQm9KOHM4VG10b1NJSUlLeUZTNlhmYXJ4RWkNCmhKejdSUFdybnpmdXI0MWxZZDFzL1hZeGJtSDNoRlZyZ0RRZlZxRXNXMWk1QUFwWldMemFQYS95OTA3WE5VMlJJalhUYnJ3VUxjZUsNCm9ZU3BrYlQydUhvNERHVmFuR1dLUEl6V1R5bE9iU0cxZUF4WElSUndIVUxHVHpGSURTbFhNMSsvbUN1M1BlSkMrNWVWMTYzU1hTcXkNCmYzYWhiTEZYTEUvVWVlOTBYTk85amM2eWFIRzdtczV2c2ZHQ2MyaG5DVHBIRGdiQzFyVVJCN2djTGJuMEljTFI2anBHT0xIUVg2ZncNCld6VVUyaStkbmFodWNBK2VjRzA4V0FPN2NMMW9mWXg5bURoVmZUV0VZM09kdldRNHgxTFdseFhEUVBWc3RFVkpDNGFobmlpZWNNNE8NCnF5WXM1T0U5S00zWk93VzN6cmVKOWUwZWI1akhOeE11bStuY29QWFZCQjRlelN5aXkrVTQxMlpLeEdncDZvbFQ4bUt1Y1lVclhGWHMNCjVHQzBGaTBFZW1hNk9KellSaVZlSWRQYm05eGRZeFN1Mm5xbzNhS1FnWjgvdThVRkhIYmUxakVjUEovTkpxdFhuUHRvc3lCM0o2Rm0NCmxybWZiL0FTQnV6TmV0TmlSckVEb1RiWE9aNy9QazRxWDJxSHdtVDhiYTZlN0NqWHRleFRyRExLYTErRDN6OTVoWWtWVDlyVXd4aTgNCkdIU3ZwV3l1K1MycmtpdlFUbGVwVStQaWl6bksxSkc0eGluYWRpeVpTN2FrdUlwNUhLTmx0MmZSMmVzTnJNbHJLY3NzdWRTSDFOWGwNCkhDL2ZnNkt2cUR6bmZIWVVKMUxjVFoveWhCSnFIY3M1djMzS2pNWjdwK09hN29IUGsvTUNNUUk0c0dIRFJZaEx2TUFad2xJWjRhUnoNCmc5N2hCQ2FYNzhEaUdHTFppbnUzcmt4RVI0VWNIekxiRjJOWkdBUlpFdXNLS3p0blRndW44UHhrSGlHL2RNYlVPZWcvMU4wRDcrMnoNCndESE9jSWxMZlBvOThEbkVaV2x6K3l3MzZPbmt4R3VORTVnbEdUSTczbU1oNU1oTVppbnU0YUxtaGwvaGJ1UHp1cEljanBvVTVNU1ENCld0ZXE4SnNwdFM3eEZJZUxDL2h3QzlNcnA1TWVKbTc1MFBkejRiVzRtam5FejFMcTR3UmU3YVlUdXJMR2FVVzBESGo0V2RvSHFNaVMNClM3WlVDVmYrQzhTSFUrcFBzTW13bHRuUmNqRUdsOENLV2ZndEhLMWVVYXdLbHZDTGdoZjFaamJyZzROK2I5dUR5SGp1WThUd1N1TGwNCjRORUUzUFdaYUhtRm1CZ0RIbUo4d0hZZklLUHlMaE1yZk9YTUEvdEVTVlRSS3ZmekFtZWJBYitwb0NYTDBXcXljejlQemZMUGNBdnQNClhoUmVtUXVIL2U2SVBjRStqUVUrVlBLMng3QTFuMnM4aDFVU0xjQkZqQSsvYjVZRlB1VzhlelpzZUlqd2t5UGQ5VDIyZ2g0R3BkK3oNCkFmL1BUWitNMGRyaEZCNi9VRm85UmlteDQycTBIb29rYnJDclBrL1Fmc3h6VlVoZ0VQUzYvNWlXYUlpWmxYTzBuR3NNVW5Qa2x2b1MNClpvTzZENysxVFN0aE0yUE5zNHBHR3BScVhRNm9KeityRjZWaDZTaGpDYitlc21jVjYrckppQ1V0WWtJNjFxeGlXS3JIcjdsM09xNHANCis1b3Zmc0theXIwMGtWWmZueGFhbFJiS2w5ZEk0bTNlZGdRZDdSS3hnckI0ODhVdFVwS21zZUFIdWhsZlFML2E2cmNsbEw4OXpNb1QNCkk3TzJzWVNyL0VWcVZlNmRqbDd1U1F5SlBNUWxncElmeGRObWFkQVZubGQyUzdRUjRvd3BBbjRJYXJ4Y2dMUFpKYktKUTRTQ3B3NE8NCmNQajdxT0tPMU8vUGsvWUlKYVhmMXpOSlErUVY3c1dBOSsxN2VDdlYrM1NNbjZWbG9qb3NEYnJCYzlnbFA0R05FSmNqUmJaYml2cnQNCnc4VGJRaVJWa1VVbHFFVWVuMGZ4TzEzaCthUmxDd2hLa3orTG1jd25Kb1hQaFgvUjljYjBXcVorR2trYkhDVTFNZEtXeEVGcUczRk4NCnBMODVVbHVTTkVnZGlSNEs5dUdIbTY0YUJ1M2h3TExaZktidTRENTBmMkt1MUNzOXpsQ3hma1dHdlByR0d5cVdyMkdTR253K3J2eVINCjNkQnlpRFZZR2xRdldsMitIeEZFcWE4MEYxZjlZYVZlemRlVk9hak1MdUZLYXFkRCtJLzhUZSttZG1xblRrbk1rcHBQVzl3eGhuQVoNCkRWOHU4dHp6NHdwWGNiSGhxcjl3YlMrWGt3WlNuT2hSVFhQR21tdXNzL3FNRVVRclNsMGhENnE4bzJ4dnVvTkt5OTk4UVdxWERuRVANClhMMXd1S1dyNzBpN2FtTUlWN01SNFV1cWNWemhLbi9OY1Z6VHRoZVhVdVNyT3BOblNKOXJURkt2OHNqSXIzV2x4YkF3dXoyOE5FakQNCk5HeWN5Y3RmQ1c5Z1hhSkthaithYnZKeUh0cmx5UFdMTzRvaWtwVHUxakhxRkZ2MlBXSHVyMDFtUzBmL3VxdGJTaGc4QmpYaUpjLzINCjhVY1hMZDJzckR6MTdjcGZmVzlRWGFxRnEyeW5KSkkrQ2RuQ1pWUlNPQmY5WFhLR2krTUxWN0ZHNW11NjErbTlYMkdKZlp3SW5YczgNClJsUzdOT2hiM3dJYk9jY2p1S1VwWmFjUUV5K1dPNXppRVN6NFdpMkJ6YzhqdXFvYkl4Mm4wTi9GaU1rUlJlSVZ3bkt1c0NxdFhUeVkNCnlleGkyTytwN3hZdUFFZ1F3TVlqbkxaTXBmTlJ2elJvS1hScDBEa2V3U2tKaUlNWVoxSkVhNDF6UEllcFpYSzNNUGZ6bTVwZ0RMT3cNClhpeFIzZHlCbE5Qd0hVNVFyRzI4eWYyMjN2U29tT3JtamZJVVNXSncrcXdrWlJPdWpCZ2VUTHdVWmhkbGkzMlhoYitGc0hFaVFCNnYNCjhMd2lXalppU2VHbE56aUJDVWZiRldSQjRiZHk5alFMcTVGV0VJNUZNV3M3U3RhOS9oaWxUMno3WlppVXJLeCtxL3gwSStsbE8vWWENCnJacXBKM0F1c0c1cGtEdkFCMVZYbmkwcFVpdEpBdzNESEtwSHVmZFI2bTJPY2poRU9MQW05VDR1cEZVUDBXcmsrb2NkZnNzblV2d2sNCmZjRTFqKy9qeW81cVVFN0hOUjNTRUVlZ0dBUzFjNDFWd3RSTGw2bWR1bWxRSzUxMWk3dGxpVllzT2NSUjVHRXg5OG9lV0pNZXdsVjENCitnN3RGMi85L1k5eXk2M0NxNmJVZnFrU3J1cjkyZEd2b1kwUkdiNVpuZjBybHI2cWlKdFRzTXZHREM4TnBVMnp5enBZdHFzVkVmZWsNCmkzQ1Y3UmJSczNDeWhLdHNLMWF2b1NleFg2cUVxNXE5cE9PYWlyblVvb0lMbXBjR05VVkJHeHNqTXhsUnRNUkVmbzkvT0oyZmtpT2cNCkZuMkVxMXNDNU5ZLy9QbzFaUklwRHF4OGdiV3JFNjd5cDlWeFRVVTF5aEFXQVYrL05NaHFxWG5WRUY0cW5pVDFKaXBhMldHMmhCU0wNCmttTjloS3RyMENXL2Z2NmpQR1N5QjV6VjUxQW5YT1YxbVIxOWVwQ0tuQjl3U3RFbmZibUJPMkJ1eTRBTFYzakl3eG8rL01tSENnREENCkVoYXMzL05SQ1NKRUNJWDF6TXpsbDRnbGhvWll1Um0xcUxIMVZtSGVUV1I3Mk9ybnhTeGs1MGdhVTlqSTZwZFJDSllKQlpXYVlYZjINCnk4NzkzSEZOeFFvWElFNjhyaXJoREd4NEpGb0VNWGZFQ3hjZ1Ryek9PY001UmRXYmgwU0xJTFJEam5BQm9rUmtEWjh4UEkxRWl5QjINCkJubkNCWWdSa3p1bW5Kd096Z1MzblVTTElMU0ZaOGtQUHdHc3didFp4d0xQWXVlOHNzc2lRUkRhSUZlNHRydFpmeDVlVUFlR3dMSysNCjRSRWNFaTJDMEJmWndnVmthZkg3cDZ4aDI3N0NFdFRXR3p6SFVzUDhEZ1JCNUJoRHVJQnR5cHArV1I4c2huTnNBVzFjNHdUV0RISWoNCkVNVHNHVXU0QUNDRTJiR3pYejF1NXhtbWdHM0ZQc09jYU1vNWd0ZzV4aFF1SU52WmozZFB4V1hudktJM3NGVlhlRnJKazBvUWhMYkkNCkRZZG93a2JBRlNaeDFUb1VYT0xyZ0xhczRaS2xSUkRUWW15TEt5T0VpVk9POHc5YnBNVWFKRHZuTkVBa2lPbWh4dUxLTUJGdytLYnENCjF5NjYrTlM3L2p1Tmt5MFRCTkdDR29zckk0Yk40YXcvUkFRL044Tm93RUU4UUxaT2FRYVJJS2FLU29zcmc4L3VBdGFJQUJoNE1xRE8NCkd6aU5DVU1JZ3RBZTljSUZBQzQ4U2ZzYzFuRTZreDNwQ0dKbjBVTzQrTzJ1dnBCZml5Qm1nRW9mVjU0WU50YzhZejgrazErTElPYUENCkxoWlhob1Zna08rcWpUVWNyRlIza0NBSUVlaGljV1ZFc0xuajZ0bTRna215UlJCelFTL2hBaEk0ZURrd2cxZVZVOWkwb0ljZzVvTmUNClE4VXRKbGJDaG94ckxNbXZSUkR6UWplTEt5T0dKV2pJZUFXVFpJc2c1b2Fld2dVQURrNEdEeGxwaUVnUXMwVFBvZUtXSWJPTU5JdEkNCkVMTkZiK0VDREFSNDBlTjlONVNBbVNEbWk3NUR4WXdFeXg2QnFlZXdTYllJWXI3b2JuRmxPUEE1MWpMU1drU0NtRG5URUM3QVFzZ2sNClhlVFpJb2dkWUNyQ0JSZ0lPeDMxYTlpVXJvWWc1by91UHE1N2tzN2xRRGN3U2JZSVloZVlqbkJseTRHYXBlc2J4V3dSeEs0d0plRUMNCnNyRFVPczZ4Sk5raWlGMWhhc0lGQkRYUzlSYU82bVlSQkRFZTAzSE81eW5PTVo3UUJtTUVzVnRNeitJQ3NyeGQyVHJHTmNrV1Fld2UNCjA3UzRBTURDQ2dhRlB4REVMakpkNFFJTUNuOGdpTjNrL3dHMTJGY2Z0VVEwZUFBQUFDVjBSVmgwWkdGMFpUcGpjbVZoZEdVQU1qQXkNCk5TMHdOaTB5TVZRd056bzBNam93TUNzd01Eb3dNR25DUHBBQUFBQWxkRVZZZEdSaGRHVTZiVzlrYVdaNUFESXdNalV0TURZdE1qRlUNCk1EYzZOREk2TURBck1EQTZNREFZbjRZc0FBQUFLSFJGV0hSa1lYUmxPblJwYldWemRHRnRjQUF5TURJMUxUQTJMVEl4VkRBM09qUXkNCk9qQXdLekF3T2pBd1Q0cW44d0FBQUFCSlJVNUVya0pnZ2c9PSIgLz4NCjwvc3ZnPg0K");
        personalizationNode.put("logoHeight", 50);
        personalizationNode.put("loginLogo", "data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiIHN0YW5kYWxvbmU9Im5vIj8+DQo8IURPQ1RZUEUgc3ZnIFBVQkxJQyAiLS8vVzNDLy9EVEQgU1ZHIDEuMS8vRU4iICJodHRwOi8vd3d3LnczLm9yZy9HcmFwaGljcy9TVkcvMS4xL0RURC9zdmcxMS5kdGQiPg0KPHN2ZyB2ZXJzaW9uPSIxLjEiIGlkPSJMYXllcl8xIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHhtbG5zOnhsaW5rPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hsaW5rIiB4PSIwcHgiIHk9IjBweCIgd2lkdGg9IjYwNHB4IiBoZWlnaHQ9IjIxMnB4IiB2aWV3Qm94PSIwIDAgNjA0IDIxMiIgZW5hYmxlLWJhY2tncm91bmQ9Im5ldyAwIDAgNjA0IDIxMiIgeG1sOnNwYWNlPSJwcmVzZXJ2ZSI+ICA8aW1hZ2UgaWQ9ImltYWdlMCIgd2lkdGg9IjYwNCIgaGVpZ2h0PSIyMTIiIHg9IjAiIHk9IjAiDQogICAgeGxpbms6aHJlZj0iZGF0YTppbWFnZS9wbmc7YmFzZTY0LGlWQk9SdzBLR2dvQUFBQU5TVWhFVWdBQUFsd0FBQURVQ0FRQUFBRGphVFduQUFBQUlHTklVazBBQUhvbUFBQ0FoQUFBK2dBQUFJRG8NCkFBQjFNQUFBNm1BQUFEcVlBQUFYY0p5NlVUd0FBQUFDWWt0SFJBRC9oNC9NdndBQUFBbHdTRmx6QUFBTEVnQUFDeElCMHQxKy9BQUENCkFBZDBTVTFGQitrR0ZRY3FBSDg4YUVvQUFCOXFTVVJCVkhqYTdaMjljdFRLdHNmL3VNaEh2aTlnd1gwQVJIRnppeXJ2bUNFZ3RweHcNClFrUUVHWExHamhEaDNvbmw5QkF3eExqS2NuNWN5QTl3alB3Q0c4MFQ2QWFhd2ZwV3Q5U3RibW5XVDFWZ2V6VDlvWkgrczNyMTZ0VVANClVrd1dBMENpdWhFRVFZelBudW9HOU1aQWlCQ202bVlRQkRFK0R5WnFjUmtJOFFUQUdqWWkxWTBoQ0dKY3BtbHhtUnZaQWhZSVlhbHUNCkRrRVE0ekpGNGJJUWJXUUx5S1JycWJwSkJFR015ZlNFeTBLSVJlRXZDM3lGbzdwWkJFR014OVNFeThHUGtteGxuTUZUM1RTQ0lNWmkNCldzNTVEeDlhWGowbnU0c2dkb01wQ1ZlQTQ0NHpibUJUWkJkQnpKK3BDSmZ4ZXg2eG5Uc3NLVHlDSU9iT05IeGN4WG5FTmc1b2pwRWcNCjVzOFVoTXRCaUFQbXN4ZjRDbGQxa3dtQ2tJbit3dVhockhZZXNZMVBDR0NvYmpoQkVMTFEyOGRsSU1DTG51Kzl3Ukt4Nmc0UUJDRUQNCm5TMHVDMUZ2MlFLZUlDSnZGMEhNRTMyRmk4K3pWY2NDWCtHcjdnWkJFT0xSYzZob3dPK00yV0xsQ2t1SzdTS0llYUdqY0ZrSUdJTWYNCjJGaGppVkIxcHdpQ0VJZCtRMFdYTWRTVW5RVXVhY2hJRUhOQ0w0dHJ5Q3hpRnpkd0tLYWVJT2FCVGhhWGpWaWFiQUZQRUZKZ0trSE0NCkExMkV5NENQUys1QVV6NFcrSVFWQmFZU3hQVFJZNmhvSXhnWStzRE9HZzVXcWp0TUVNUVFkTEM0UEZ5T0psdFpkQmZaWFFReGFWUmINClhHUGFXbm5JN2lLSUNhUFM0c3I4V255eWRZNlgyTWNEUE1CVHZNVk43N296dTh0VTJIdUNJSHFqenVMaXQ3Vyt3YTBzbXg1bXNhM2gNClVZUVhRVXdQTmNKbHd1Y09mSGpiSURHc3VWR2J1SUpMOFYwRU1TMVVEQlhkSGxrZlRoc3Rvd1QyZ0NFamNJZ2Y4TWxaVHhCVFlteUwNCnk0YmZ3ejY2Z3QzNnVvVWZBOXUxaG90ZzNFdEJFRVJmeHJTNFRBUzQ3RFdzOHpwZWozQStzRzBMbkNHRU5lTFZJQWlpTjJOWlhBWmMNCnVEMGo0KzhZWnYrRzIxd1o1M0FwQ1E1QjZNNDRGcGVEQ0I5NkwraFpNWndUQ1dycE1XSjQ1UEVpQ0wyUkwxdzJJcHdOQ2pKTm1NNjYNCkV0VGVCVDRnb2oyeENVSm41QXFYamJDblYrdWVPd1VSN2djNFEwd1o2d2xDVitRSlYrYUtQeHhVeGgxT1lESU9BNWM0eFZwZyt3L3cNCkZXSEhiQ1pCRUVxUTQ1dzM0UTNPR2Q4blFHSElGRUFUVi9BbzhUTkI2SVY0NFJJaldqNzhuck43SXVvdlErSkZFRm9oVnJoVWk1YkkNCmRwUWg4U0lJYlJEbjQ3SVI0dWRBdVZqakZDYThrbXgxSjZDeFNvNzBHQTRlRFE1S0xYS0lTNXB0SkFoTlNFVWN5elJNaDVLa1htcFUNClNuYlNPRTNUTkExYWFqZlNKRTNUTUxVcnIxZ0MybFVtVHAyYWR0SkJCeDBqSGtNTE1INUxpenpSU2x1bHkweWozK2ZVaVpjdFFieVMNCjFFdE4xUjhkSFhUczdqSEV4MlhDaFNOZ0RxOXVtWTBEcnhLMGVnV25rby9MZ1Y5cVFaMHZ5b1kzTURDanZ0MEJlYjFHNHhuKzAzbk8NCi8rS1dxU3lXbS80OS9sVGRaZVY4eDFIbk9SZjRRMWg5V1czN2VBd0FlSVo5QUtndi8ySFBLcFp3aEd3bGRnNlBPVG5nSVg3aUcxYUkNCkVNR0FCUnRPelhtSHVLeVVHc0tXa0NUNkdNZTRnWStWMXFzYlgrT1Y2aWFVdU1BWFJvSEowLzBJM2ZZb2xSaVh4NzlGQ1FEMk4vOXYNCi84b0J2M0NaY0dvRmc1OTYwV3EzalY0d3llVXhqbXZFeTZ5MTQ0YnhCR2Z3c1lLdmJUTENmWVpIZmx5TzhBN3Y4VGYzdTdxNFVOMngNCkFqMGVScUg4d3JYcVN3RGczY1p1MmtxVU9MaEdsc3QwSmNoTEZOVDRpTVI3bytwcUVlR1RxeVBTMUduL1RrcHZoM1BFMVl0OWhoSmYNCk1aZkd3cnZKWC9kL0J2ZmhPME10M3dkZjZTNXFTMllOaDdEZ0k4RlhRY1BEUnhWdlZiYXFVYlFmNmhnL0s3a2VBcGc0d1ozZ21qTGINCjZ4Y0NXdUhJeUY5Y1o3TllqWHBaWE9yWngwZk9xendodW9YTGhJc1lQL0JHeUZLYXE5RkVhOHVIbWtRMUFTekJLeHUzSE9NckVnU1UNCmtyQVR2cUVVeTBEeGwrb3VhWWgrSGs0K2JwdStqdHA4WENhV2NBYm1kc2hUTjk5bndaY21XVnNXK0FDM0ZJK2Z3SU12WVdWalZ0OHgNCmpuR0hGUUp0UFY5RnJvVS85Q3dXMG1NT1p6cUxjTDNlekVMVjhZdmJwOWJNUGpBaGtYeUZMNnFiMEVrbVR0dkpsZXZOdjYzWHVGNjQNClJFdFd2V2pKV0pqVFJDWmV4YzNJWklvWGNJQTNlSU03ckxEU1Btaml2ZkJoMWo3KzJ5SWp2RHhqc000dThLN0Z1cmdWSUZ5UGNZUm4NCk9NSmovREdoWVNucjFFemRlU3lmWVAza0QrdFg0WU8rM1NvTGx3VUhTOEV6YjZwRmE4c0NuK0RDSytTY3lNVExsOWFXVEw3V0cvbEsNClJ1NnhPbjdoV3VCc0prc294RFd1VzRUck1mWjdXMG5QY0lSblRPTFpqNzk3Qllld1hsM1dyNC92UFZ2L3JQYWQwcVY5SzF3R2xyQ3gNCkZHNTc2Q0phV3c1d0JxOGlYZzQ4cVczS0JvL0FGVllJSnpKODFBbTJVSWlMampKNGgweEhHOGtTWnpuVzg2WEhRLzQzL3BIY0tzM1oNCmd3MGZFWDdoRE1mQ00xazloMTJTTFJQQjRLWFlRNm5MYnlwaldYYVZRM3pDRDhRTW0zOFE5N0JFb2wyZ2EzakNHMGYwRWQveEVVZlMNClphc2YwL0d4U1dJUEFkNXdlN1BXdU9vSUtMakRpWmFpdGFVdXYya21YcUp5MXpkalZNSnVpVGJZUXlIYVFpNUZCMEFTU3RuamNoeXYNCmNZNlgySWNCR3lZZTREaysxd2hZbG5BNUtQek5nS2VOYUcwNXhHV05lTmw0TGxtOFF0VWRueGpzb1JBWGcwb2hKc1JEaE14eThybVMNCktTdEVDQmN1dk4rRHpMdVMvd2lRazFCWkZJZTRyUGpoc3BXTk1wWmxiOHZYa1NPQk5rbWZ0WWh0TGV0aUsxalhIZVVNZHhoZjR3TFgNCldpeW1FWTI0cGRKQTF5Y2hnSWVNajlFYXk4WXpmWVFJOEdSeW9yV2xlVm0yTHpRazVMNXNIWG1HajhMS3VoWW9YR3loRU1YL20wcnENCkwxeTMrSUxyV1FlNVRpZkFBd0R3RURIdU9zTWYxckJiNThJaTJGaE9VclMyMUMvTHRrckxzcThRSVlFSmUwREF5SnBtRmJsZ0M0WFkNCmN0RnkvbEdQUkRXM3VNQTFMbllpNzhTNzF1dFFuSk50dDg5SFNBajBFRURVK1JqYW5ROWJVdkZwVFVlMHR0U0pWNEJnSTE3RlYvb24NCnlZbFVkM05pOEdXRmFCTXV2cUh3QmI3c2lHQnRhWXNOdXlnSlY1dDlmanVHY08yaGUraHl5djJ3dVlqeFlXS3lsZEcwTFB0cGFZVmwNCkNLdG44RVNvdW91VGdpVVVJdTlQYVJNYXZ0UXFGNzBDUTZkTVcyL0xWKzZvWnpuQ3lDeXVOdTdnY1pVb1B1ZlYyRlJYTnRaZG93UU8NCnJCNCtzRkIxOXlZRnkxemdMN3pDWTJRWk0vYzdTcHVqVzEwVTdhSmZYSG5RNW5jY3hWdjJFRjJQVXNCUm1zRXc3SndDaTFyeHFyTEUNClQrNnlJOVdkYStCTDVhSCt5R1Nodks4UkEzSHl3Q0pjLzJZdWpXSzUybWovMUlwVEcyM0NOY29FUnJiazU2YkZjZ2c0U2t2R2FQSkkNCjFDM0xMaFBqRzJlR3NqdHRyMUUxOFRIYkRYZ3Q2UnMyRzlpSmpiNGk0V3FqZllpWEY2NmpBZVVJSXN2SEZUVytmc2NaNVIyTzBlalINCldPQlRoNFcwNGl3eDRqeC9WL2tMLytBN3ZndGUyS3c2bmJMZXRBdE9maERlZmhWSEdTcDJDVmZNV1I3ditmcVRDTzF2cExvN0UwSFcNCnR6YkZ6N2ZSSmpsNWE3WE5rempTaEVhWGNDWGpOR05uQ0ZVM1lNY2hpNnNOMW5uRk52a2ZhZm9qODNHRmphOGI0elJEWTh3QnIxYUoNClZIZG5JckIvYjJlNzJWd0F1TVV2M09JV2YrRjE0OWxrY2JYQk9xL1lKdjk4d3NXMnJXdE51c0Z0UHE3dTZQbGQ1UUJteTREUTVpcHINClRSWXNJNzhhLzE2V3FTcnRPU0w2SnhTY1A2enppbTNDTmRKUWNTdGNUV0VNRm1kNTlqak5IaFVQVHNNckptZStpMGgxVnlZR2kweFYNCkVaMVFjSGRnbTFmVVlFNHhMMXoxMC9vTFdCeVBteUY5NHdzVkhDTm9HRXo3bkNWRnFyc3lHUzd3UDcwdG85dldUQWU3RlEzUEI5dTgNCllydWZjRlFmVjlzajVUYmFHMVhNY1JvOU9xdmExWm9COXk2VHNlcU9USWdoQTdxSlpUclFDSmExbm0xemlxT3RUTmp1cXhnM25uSE0NCklVZlJLQmxFeDJlQkVHN2hMeVpISHJON0l0VWRJWWhXL3NDRHhtTnJ4LzdaY3M3L2NkWjMwYkdRL1JZWDlWOUQzUllYNEhQc3poeHYNCkV0eE1jWUYxR3d0OGdvZlZSdUNYUGZOMFJhcTdNU25FWmdqalhUTGQ1c2tSbXloeGwva0R3THVXei9udnBrd1Q5OXVUTmM4cnZvREwNCjVjMVp3WVNITjZxdmlYQVdBMU5QMDV3aUh5eVpJVmc1d212OGk4c3QzNWJrUldTaVJCMFFtZjAyUStRR3ZMWGNDMWZjRWhEeHFaSnYNCnE0d0ZJK2ZBVHVBaUdHR1A2bWtScVc3QVRyT1B2MlluT0tJUWFkdG1YTWdXcnIzZlAwV3Q1NTNCYndsR2RmRURsMWdWdkdFUmJKeGcNCkxiZjVreUpXM1lBZFo3ODF4eWN4S2U0dHJxVGp6RGRZMXVTVVIyNWJpUmV3UzlrVUFxd2s3aEk5TldMVkRlaWduTWFHYmZqd3NUTC8NCjkwWDJ0MjF2S0c1K050d0xWNGdQSGVjZTRBdytWZ2czajZBQkc4dkNBSE9CVDNEZ0ZnYU5EZ0pKbTA1TWpWaDFBenA0MXV2QnJzcmINCm1NRUkxN2xsS04zckVHbWw0bXg0eUhuK2RqUDVacDdnRXVkd2N4WmNDS3V3aGRtdUVxdHV3QXg1bjVQSi96SUwwMUJwRmJ0b2lMS0UNCjlTQnZjWW5qR012U29ORkgwQ05nYzE1RXFoc3djMjZaaFV2c0hvSkRFZTBZNStkTFMrQm9leDdjOXczdmxMNGVsTmZpWW1XQlQxakMNCnpUMnNDWllEOXNhWkE0bnFCaEJFTFcyclFOc2xTRmIrMjA3eXduVWxPSHpoRUQ5S3UxK0hNT0ZOYnRzeU1keXBia0FuNWUvT1p4MWINClQyemZWYjYxOWMrKzhKM2hISDJuR0FqSXM3aTJ2SUVEdHpBWDZTRkFzSU1SWHJIcUJuVHl2dlQ3ZHlabi9mc0pyZ3hrNmRmMGVyVlQNCjdPVitqcVRVc01BWndrS0VWd3diTHlkZ2dZZ2xVZDBBZ3BnUGVZc3JrVmJMSVg3aXRMRFoxd29oM000QWpEa1JxVzRBb1NudmUrVlUNCllCbnV6cGh4aEFzQVBzQ0JtOXNWSitFY05ONGhSSWdZMGFhZEJpeVlzR0h2c0x1Zm1BUEtITnhUSmk5Y2tlUzZEdkFWVjRXdDdHUFkNCmNPQjNPdXZQNFZkYWx5QkV0dXVqQlhjQzBmbng0QkowcGVnTGU5QzduRDY4eWszV1UzaHBmMTQzVHNTMFg5VlhqY0VTa3BkWnkzYk8NCmx6bEVCQjllN2k4QlZxMjVKTTdoZFR6MEVSeDQ4RFFYcjNod0NVU1YxOE9MSUFDODZya2Nxdm42UzE1bW5YZk9KeklyK3MwQ0h4QVgNCmN0TW5jUEcwTmdIaERaNFdMTFJtWWpoNGlwdFJla0FVMFQvOFFSVFA4QTcveGorMDVsRTk4bWNWNnppb3pTWHh0cFJMNHB3cjN6MFENCndjTDVhSDNnSlZiZEFHbU1scTVYR1VjYndmb1BQdUlWVTNRYklabTk0VVgwNUFXaVVqcGtIMlpPZUU0NGN0M2Y0K0JFV1kvYWlWVTMNCmdPRG1DTy93SFNtK2syRHBoanJoeXBZRlJhVkJvNFBudUFGdzBwRzRzSmxBVytraXBzVHJqV0RSc0ZCTGlzNzVtOUhUejlUbmt1QWINCklwWUpZTTB3Y2ZSVU9CcDFjditQWEcxc2tmNnM3TlljNWZ0R2U3TGZJbXZwbnMraWNDVnlLMnZnR012U3NxQm9ZSWt1TE0wV0ZkRzANCkFhRXp6WDVLVFJkWnF4d3EzcE10QzdJRWx1aG9salE2VWQwQWllek9yQ0p3aTcveHJ4MllqdENlc2VPNG1xbm1raGhDREErZlZIZHANClI5aUZEU2l1Y1lIYmpqMEFwMHpUUGovZCt5STF2Vk95TGFhUGNBSEFHMWdGWi8wUWZMaTBHSWdZeURVdWNJMkwyVnVWVGZ2OGROdVcNCit3M3ZsSncxcENoY2tYTFBVQ0N3TEE5bmludERUSmNML0wwRGdqVWNSVmVvNk9OS0ZGK0VkVzRSOW5CV212bTVkb081UkR0ZDRBdkoNCjFnQWszd2Q2T09lM3JJUktaeUpVQm9jUnFtN0FhT3hXR0FIUmhPUXRRUFR5Y2EwRWx4ZHF2dkI2SHFpY1k4cy9Ibk94OVFnRzlCS3UNClVQUHlpRHArRlpJK2p5dGo2bmZJSVpTZ2szQ3RoZnZZWXF4M2NtT09zZmxUZFFPSWdVeHVZWk5Pd2hWSktWUDFQT2xVcU80RXpUYjANCnF2TmxYSk5iZXlaYzRGM0hHWW9pMjNRU0xrSWxyM29PdStyZTlRY2xJOTRabW9STHNnMm4wNnhpcUxvQkJFRk1BNTBzTGtOMUE0akoNCjhYZnVHLzgxaFdMc0Rqb0psNlc2QWNUaytKSWJsQjZSY08wT09nMFZDWUlnbUppN3hVVnppcXhjRjZLeGhqSFhIQW9FRDFKVFN1b2sNClhBdVlnak96VzZxN05DRXVhQ1p3aHhreXlQNmxZczJDWGtORlcvUHlDR0tlMUFzWG0rV3NaTW1YVGhZWHNCU2ExZ2E5OWdtU2c2bTYNCkFRVFJRcjJiZ0MyTStFdURyUzdWWWFDWGNMMFFPbGcwUjkvNm82MHRCS0V2UXhadFNkMnh1b21pY0ZrcW1sREFnU2VzckJnbjhHbXQNCklrSDA0REZlYmY3WGtxSndHYXFiQXhlK3dLWFdBVmJ3YUt1eVRsNUp5SjQwNWcyZnJiTVU2eUwrMkxJRVN1eVNKaGxYZnppUDljNjgNCm9kZFFFVmpBSysxdlBZd0VMZ0w0RkJiUnltdlZEUmhJMzNXV2VqRDFxNjhFdldZVmdXekRETEZFc1BHVzBqZ1R4SHpRVDdpQTFlQWgNCnE0R29aTGY1TUhHdXNFK1d3cm9KWW5ZVWhjdFMzUndBd01IZ0ZNNGhudUFUb2tJY1Z3SUh6NVh0S0UxVEJBUWhrS0p3NmZKNEhRNksNCjV3bzJZUkJQY0ltZ1lMMkZzR2pRU0JEVDUwR2EveTN0VzR3RXp1SDJtRjgwc0NvNTR0ZHdTekpvd3NlTDBmdnpZUFFhcDg0K3cyeGINCmxtdjFNZk1jSmkxckdwZXVkSUlYYVAvMGJwdkNXUFBDWldpV2NQY0dTODV3Vk91M3RWWGtDbTRwTWJTTllPUjlycDlUb2tTQ0VFVisNCnFHaXBia3lKSnhVWGV6c2V3b1pZK1VQOGdGOGFOSm80cFVFalFVd1RIV2NWNzFuZ0UyS21GWWNPWW54bzlkRzlRWXhsNFM4ZUxGeXANCjdpSkJFUHpraGN0VTNaaGFEbkNHR0Y2alBXakJSNHd6aG9IZkFsOFJGbm9adzhaTDNJM1NEM3VVV2doaUo4ajd1RHg4VU4yY1Z1NFENCklVSzg4WHVaTUdIQjZ1R3BPaTB0S3pMZ2p0RHpVNEdyTUFsaXg1bVNjSW5qRG00cFZzeEVJSGxaMExsR1NYWUlZdUxvN0p5WHh3RysNCllsVVpOSjVJZGRhYmcwc2dDR0pEWHJnTTFZMFpsUmVJU29PM0FDWStxMjRXUVJEZDVJZUs4Y2lSVFRwd0I2Y1VYMlZKeXlWQklhZ0UNCklZaTh4YlY3c2dVYzRMSzBxTHM3bDhRTnhYOFJoRnJ1aGN0VTNSUmx2RURNbEV2aUcwN3dDQTlnd2NBRFBNVm56a0FLVzNWSENXSXUNCmtIQUJXYUJyZXk2Skt6ekNFa0Z1Q1ZJRUY2WmtoejVCRUxXUWNHMXB5eVh4Rm5iRHFza0FKbk9xSEZ0MUZ3bGlMcEJ3NVRtdUxERHkNClllRWwvSmIzSkxDVXBpZ2tpQjNrZmxaeHhaSHE1UTR4QUV1Yi9GMWlxZWFTNk1Kb1hONWRMTmRXM1RXQ21BZjNGcGZCK0k1elBJSUoNCkd6WU1QTVUzMVIyUVFEV1hSQmNKVTFROFQ0a0VRYlJ3YjNHeEpCRmN3NjdZSWc3T1ZIZENDbXM0WENta0F4eDNua09SWEFRaGhLM0YNClpUS2NXeWRiUUlBVDFaMlF3Z0pmdVpaRnM1eHJxZTRVUWN3REh1SHlHancvd1d5ZDB4SEh1VEhEN0tLcHVrTUVNUSsyd21WMW5ublgNCk1yZm1xZTZHSkZhQ3o3WlVkNGdnNWdHN2NLMWFYbU94TnFZSGI1K2l6ak1zMVYwaWlIbndjUE8vMlhsbTNQb3FTempBMUVpRW4yK3ENCjd0THZkdHkzSk9FTS9TQUlEZGdLVjNjK2hMajExVVIxUnlhQmVuRzM0V0JaaWIrN1FqQm9KOHM4VG10b1NJSUlLeUZTNlhmYXJ4RWkNCmhKejdSUFdybnpmdXI0MWxZZDFzL1hZeGJtSDNoRlZyZ0RRZlZxRXNXMWk1QUFwWldMemFQYS95OTA3WE5VMlJJalhUYnJ3VUxjZUsNCm9ZU3BrYlQydUhvNERHVmFuR1dLUEl6V1R5bE9iU0cxZUF4WElSUndIVUxHVHpGSURTbFhNMSsvbUN1M1BlSkMrNWVWMTYzU1hTcXkNCmYzYWhiTEZYTEUvVWVlOTBYTk85amM2eWFIRzdtczV2c2ZHQ2MyaG5DVHBIRGdiQzFyVVJCN2djTGJuMEljTFI2anBHT0xIUVg2ZncNCld6VVUyaStkbmFodWNBK2VjRzA4V0FPN2NMMW9mWXg5bURoVmZUV0VZM09kdldRNHgxTFdseFhEUVBWc3RFVkpDNGFobmlpZWNNNE8NCnF5WXM1T0U5S00zWk93VzN6cmVKOWUwZWI1akhOeE11bStuY29QWFZCQjRlelN5aXkrVTQxMlpLeEdncDZvbFQ4bUt1Y1lVclhGWHMNCjVHQzBGaTBFZW1hNk9KellSaVZlSWRQYm05eGRZeFN1Mm5xbzNhS1FnWjgvdThVRkhIYmUxakVjUEovTkpxdFhuUHRvc3lCM0o2Rm0NCmxybWZiL0FTQnV6TmV0TmlSckVEb1RiWE9aNy9QazRxWDJxSHdtVDhiYTZlN0NqWHRleFRyRExLYTErRDN6OTVoWWtWVDlyVXd4aTgNCkdIU3ZwV3l1K1MycmtpdlFUbGVwVStQaWl6bksxSkc0eGluYWRpeVpTN2FrdUlwNUhLTmx0MmZSMmVzTnJNbHJLY3NzdWRTSDFOWGwNCkhDL2ZnNkt2cUR6bmZIWVVKMUxjVFoveWhCSnFIY3M1djMzS2pNWjdwK09hN29IUGsvTUNNUUk0c0dIRFJZaEx2TUFad2xJWjRhUnoNCmc5N2hCQ2FYNzhEaUdHTFppbnUzcmt4RVI0VWNIekxiRjJOWkdBUlpFdXNLS3p0blRndW44UHhrSGlHL2RNYlVPZWcvMU4wRDcrMnoNCndESE9jSWxMZlBvOThEbkVaV2x6K3l3MzZPbmt4R3VORTVnbEdUSTczbU1oNU1oTVppbnU0YUxtaGwvaGJ1UHp1cEljanBvVTVNU1ENCld0ZXE4SnNwdFM3eEZJZUxDL2h3QzlNcnA1TWVKbTc1MFBkejRiVzRtam5FejFMcTR3UmU3YVlUdXJMR2FVVzBESGo0V2RvSHFNaVMNClM3WlVDVmYrQzhTSFUrcFBzTW13bHRuUmNqRUdsOENLV2ZndEhLMWVVYXdLbHZDTGdoZjFaamJyZzROK2I5dUR5SGp1WThUd1N1TGwNCjRORUUzUFdaYUhtRm1CZ0RIbUo4d0hZZklLUHlMaE1yZk9YTUEvdEVTVlRSS3ZmekFtZWJBYitwb0NYTDBXcXljejlQemZMUGNBdnQNClhoUmVtUXVIL2U2SVBjRStqUVUrVlBLMng3QTFuMnM4aDFVU0xjQkZqQSsvYjVZRlB1VzhlelpzZUlqd2t5UGQ5VDIyZ2g0R3BkK3oNCkFmL1BUWitNMGRyaEZCNi9VRm85UmlteDQycTBIb29rYnJDclBrL1Fmc3h6VlVoZ0VQUzYvNWlXYUlpWmxYTzBuR3NNVW5Qa2x2b1MNClpvTzZENysxVFN0aE0yUE5zNHBHR3BScVhRNm9KeityRjZWaDZTaGpDYitlc21jVjYrckppQ1V0WWtJNjFxeGlXS3JIcjdsM09xNHANCis1b3Zmc0theXIwMGtWWmZueGFhbFJiS2w5ZEk0bTNlZGdRZDdSS3hnckI0ODhVdFVwS21zZUFIdWhsZlFML2E2cmNsbEw4OXpNb1QNCkk3TzJzWVNyL0VWcVZlNmRqbDd1U1F5SlBNUWxncElmeGRObWFkQVZubGQyUzdRUjRvd3BBbjRJYXJ4Y2dMUFpKYktKUTRTQ3B3NE8NCmNQajdxT0tPMU8vUGsvWUlKYVhmMXpOSlErUVY3c1dBOSsxN2VDdlYrM1NNbjZWbG9qb3NEYnJCYzlnbFA0R05FSmNqUmJaYml2cnQNCnc4VGJRaVJWa1VVbHFFVWVuMGZ4TzEzaCthUmxDd2hLa3orTG1jd25Kb1hQaFgvUjljYjBXcVorR2trYkhDVTFNZEtXeEVGcUczRk4NCnBMODVVbHVTTkVnZGlSNEs5dUdIbTY0YUJ1M2h3TExaZktidTRENTBmMkt1MUNzOXpsQ3hma1dHdlByR0d5cVdyMkdTR253K3J2eVINCjNkQnlpRFZZR2xRdldsMitIeEZFcWE4MEYxZjlZYVZlemRlVk9hak1MdUZLYXFkRCtJLzhUZSttZG1xblRrbk1rcHBQVzl3eGhuQVoNCkRWOHU4dHp6NHdwWGNiSGhxcjl3YlMrWGt3WlNuT2hSVFhQR21tdXNzL3FNRVVRclNsMGhENnE4bzJ4dnVvTkt5OTk4UVdxWERuRVANClhMMXd1S1dyNzBpN2FtTUlWN01SNFV1cWNWemhLbi9OY1Z6VHRoZVhVdVNyT3BOblNKOXJURkt2OHNqSXIzV2x4YkF3dXoyOE5FakQNCk5HeWN5Y3RmQ1c5Z1hhSkthaithYnZKeUh0cmx5UFdMTzRvaWtwVHUxakhxRkZ2MlBXSHVyMDFtUzBmL3VxdGJTaGc4QmpYaUpjLzINCjhVY1hMZDJzckR6MTdjcGZmVzlRWGFxRnEyeW5KSkkrQ2RuQ1pWUlNPQmY5WFhLR2krTUxWN0ZHNW11NjErbTlYMkdKZlp3SW5YczgNClJsUzdOT2hiM3dJYk9jY2p1S1VwWmFjUUV5K1dPNXppRVN6NFdpMkJ6YzhqdXFvYkl4Mm4wTi9GaU1rUlJlSVZ3bkt1c0NxdFhUeVkNCnlleGkyTytwN3hZdUFFZ1F3TVlqbkxaTXBmTlJ2elJvS1hScDBEa2V3U2tKaUlNWVoxSkVhNDF6UEllcFpYSzNNUGZ6bTVwZ0RMT3cNClhpeFIzZHlCbE5Qd0hVNVFyRzI4eWYyMjN2U29tT3JtamZJVVNXSncrcXdrWlJPdWpCZ2VUTHdVWmhkbGkzMlhoYitGc0hFaVFCNnYNCjhMd2lXalppU2VHbE56aUJDVWZiRldSQjRiZHk5alFMcTVGV0VJNUZNV3M3U3RhOS9oaWxUMno3WlppVXJLeCtxL3gwSStsbE8vWWENCnJacXBKM0F1c0c1cGtEdkFCMVZYbmkwcFVpdEpBdzNESEtwSHVmZFI2bTJPY2poRU9MQW05VDR1cEZVUDBXcmsrb2NkZnNzblV2d2sNCmZjRTFqKy9qeW81cVVFN0hOUjNTRUVlZ0dBUzFjNDFWd3RSTGw2bWR1bWxRSzUxMWk3dGxpVllzT2NSUjVHRXg5OG9lV0pNZXdsVjENCitnN3RGMi85L1k5eXk2M0NxNmJVZnFrU3J1cjkyZEd2b1kwUkdiNVpuZjBybHI2cWlKdFRzTXZHREM4TnBVMnp5enBZdHFzVkVmZWsNCmkzQ1Y3UmJSczNDeWhLdHNLMWF2b1NleFg2cUVxNXE5cE9PYWlyblVvb0lMbXBjR05VVkJHeHNqTXhsUnRNUkVmbzkvT0oyZmtpT2cNCkZuMkVxMXNDNU5ZLy9QbzFaUklwRHF4OGdiV3JFNjd5cDlWeFRVVTF5aEFXQVYrL05NaHFxWG5WRUY0cW5pVDFKaXBhMldHMmhCU0wNCmttTjloS3RyMENXL2Z2NmpQR1N5QjV6VjUxQW5YT1YxbVIxOWVwQ0tuQjl3U3RFbmZibUJPMkJ1eTRBTFYzakl3eG8rL01tSENnREENCkVoYXMzL05SQ1NKRUNJWDF6TXpsbDRnbGhvWll1Um0xcUxIMVZtSGVUV1I3Mk9ybnhTeGs1MGdhVTlqSTZwZFJDSllKQlpXYVlYZjINCnk4NzkzSEZOeFFvWElFNjhyaXJoREd4NEpGb0VNWGZFQ3hjZ1Ryek9PY001UmRXYmgwU0xJTFJEam5BQm9rUmtEWjh4UEkxRWl5QjINCkJubkNCWWdSa3p1bW5Kd096Z1MzblVTTElMU0ZaOGtQUHdHc3didFp4d0xQWXVlOHNzc2lRUkRhSUZlNHRydFpmeDVlVUFlR3dMSysNCjRSRWNFaTJDMEJmWndnVmthZkg3cDZ4aDI3N0NFdFRXR3p6SFVzUDhEZ1JCNUJoRHVJQnR5cHArV1I4c2huTnNBVzFjNHdUV0RISWoNCkVNVHNHVXU0QUNDRTJiR3pYejF1NXhtbWdHM0ZQc09jYU1vNWd0ZzV4aFF1SU52WmozZFB4V1hudktJM3NGVlhlRnJKazBvUWhMYkkNCkRZZG93a2JBRlNaeDFUb1VYT0xyZ0xhczRaS2xSUkRUWW15TEt5T0VpVk9POHc5YnBNVWFKRHZuTkVBa2lPbWh4dUxLTUJGdytLYnENCjF5NjYrTlM3L2p1Tmt5MFRCTkdDR29zckk0Yk40YXcvUkFRL044Tm93RUU4UUxaT2FRYVJJS2FLU29zcmc4L3VBdGFJQUJoNE1xRE8NCkd6aU5DVU1JZ3RBZTljSUZBQzQ4U2ZzYzFuRTZreDNwQ0dKbjBVTzQrTzJ1dnBCZml5Qm1nRW9mVjU0WU50YzhZejgrazErTElPYUENCkxoWlhob1Zna08rcWpUVWNyRlIza0NBSUVlaGljV1ZFc0xuajZ0bTRna215UlJCelFTL2hBaEk0ZURrd2cxZVZVOWkwb0ljZzVvTmUNClE4VXRKbGJDaG94ckxNbXZSUkR6UWplTEt5T0dKV2pJZUFXVFpJc2c1b2Fld2dVQURrNEdEeGxwaUVnUXMwVFBvZUtXSWJPTU5JdEkNCkVMTkZiK0VDREFSNDBlTjlONVNBbVNEbWk3NUR4WXdFeXg2QnFlZXdTYllJWXI3b2JuRmxPUEE1MWpMU1drU0NtRG5URUM3QVFzZ2sNClhlVFpJb2dkWUNyQ0JSZ0lPeDMxYTlpVXJvWWc1by91UHE1N2tzN2xRRGN3U2JZSVloZVlqbkJseTRHYXBlc2J4V3dSeEs0d0plRUMNCnNyRFVPczZ4Sk5raWlGMWhhc0lGQkRYUzlSYU82bVlSQkRFZTAzSE81eW5PTVo3UUJtTUVzVnRNeitJQ3NyeGQyVHJHTmNrV1Fld2UNCjA3UzRBTURDQ2dhRlB4REVMakpkNFFJTUNuOGdpTjNrL3dHMTJGY2Z0VVEwZUFBQUFDVjBSVmgwWkdGMFpUcGpjbVZoZEdVQU1qQXkNCk5TMHdOaTB5TVZRd056bzBNam93TUNzd01Eb3dNR25DUHBBQUFBQWxkRVZZZEdSaGRHVTZiVzlrYVdaNUFESXdNalV0TURZdE1qRlUNCk1EYzZOREk2TURBck1EQTZNREFZbjRZc0FBQUFLSFJGV0hSa1lYUmxPblJwYldWemRHRnRjQUF5TURJMUxUQTJMVEl4VkRBM09qUXkNCk9qQXdLekF3T2pBd1Q0cW44d0FBQUFCSlJVNUVya0pnZ2c9PSIgLz4NCjwvc3ZnPg0K");
        personalizationNode.put("loginLogoHeight", 60);
        personalizationSettings.setJsonValue(personalizationNode);
        adminSettingsService.saveAdminSettings(TenantId.SYS_TENANT_ID, personalizationSettings);
    }

    @Override
    public void createRandomJwtSettings() throws Exception {
        if (jwtSettingsService.getJwtSettings() == null) {
            log.info("Creating JWT admin settings...");
            var jwtSettings = new JwtSettings(this.tokenExpirationTime, this.refreshTokenExpTime, this.tokenIssuer, this.tokenSigningKey);
            if (isSigningKeyDefault(jwtSettings) || !validateKeyLength(jwtSettings.getTokenSigningKey())) {
                jwtSettings.setTokenSigningKey(generateRandomKey());
            }
            jwtSettingsService.saveJwtSettings(jwtSettings);
        } else {
            log.info("Skip creating JWT admin settings because they already exist.");
        }
    }

    @Override
    public void updateSecuritySettings() {
        JwtSettings jwtSettings = jwtSettingsService.getJwtSettings();
        boolean invalidSignKey = false;
        String warningMessage = null;

        if (isSigningKeyDefault(jwtSettings)) {
            warningMessage = "The platform is using the default JWT Signing Key, which is a security risk.";
            invalidSignKey = true;
        } else if (!validateKeyLength(jwtSettings.getTokenSigningKey())) {
            warningMessage = "The JWT Signing Key is shorter than 512 bits, which is a security risk.";
            invalidSignKey = true;
        }

        if (invalidSignKey) {
            log.warn("WARNING: {}. A new JWT Signing Key has been added automatically. " +
                    "You can change the JWT Signing Key using the Web UI: " +
                    "Navigate to \"System settings -> Security settings\" while logged in as a System Administrator.", warningMessage);

            jwtSettings.setTokenSigningKey(generateRandomKey());
            jwtSettingsService.saveJwtSettings(jwtSettings);
        }

        List<MobileApp> mobiles = mobileAppDao.findByTenantId(TenantId.SYS_TENANT_ID, null, new PageLink(Integer.MAX_VALUE, 0)).getData();
        if (CollectionUtils.isNotEmpty(mobiles)) {
            mobiles.stream()
                    .filter(mobileApp -> !validateKeyLength(mobileApp.getAppSecret()))
                    .forEach(mobileApp -> {
                        log.warn("WARNING: The App secret is shorter than 512 bits, which is a security risk. " +
                                "A new Application Secret has been added automatically for Mobile Application [{}]. " +
                                "You can change the Application Secret using the Web UI: " +
                                "Navigate to \"Security settings -> OAuth2 -> Mobile applications\" while logged in as a System Administrator.", mobileApp.getPkgName());
                        mobileApp.setAppSecret(generateRandomKey());
                        mobileAppDao.save(TenantId.SYS_TENANT_ID, mobileApp);
                    });
        }
    }

    private String generateRandomKey() {
        return Base64.getEncoder().encodeToString(
                RandomStringUtils.randomAlphanumeric(64).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void createOAuth2Templates() throws Exception {
        installScripts.createOAuth2Templates();
    }

    @Override
    public void loadDemoData() throws Exception {
        Tenant demoTenant = new Tenant();
        demoTenant.setRegion("Global");
        demoTenant.setTitle("Tenant");
        demoTenant = tenantService.saveTenant(demoTenant);
        installScripts.loadDemoRuleChains(demoTenant.getId());
        createUser(Authority.TENANT_ADMIN, demoTenant.getId(), null, "tenant@thingsboard.org", "tenant");

        Customer customerA = new Customer();
        customerA.setTenantId(demoTenant.getId());
        customerA.setTitle("Customer A");
        customerA = customerService.saveCustomer(customerA);
        Customer customerB = new Customer();
        customerB.setTenantId(demoTenant.getId());
        customerB.setTitle("Customer B");
        customerB = customerService.saveCustomer(customerB);
        Customer customerC = new Customer();
        customerC.setTenantId(demoTenant.getId());
        customerC.setTitle("Customer C");
        customerC = customerService.saveCustomer(customerC);
        createUser(Authority.CUSTOMER_USER, demoTenant.getId(), customerA.getId(), "customer@thingsboard.org", CUSTOMER_CRED);
        createUser(Authority.CUSTOMER_USER, demoTenant.getId(), customerA.getId(), "customerA@thingsboard.org", CUSTOMER_CRED);
        createUser(Authority.CUSTOMER_USER, demoTenant.getId(), customerB.getId(), "customerB@thingsboard.org", CUSTOMER_CRED);
        createUser(Authority.CUSTOMER_USER, demoTenant.getId(), customerC.getId(), "customerC@thingsboard.org", CUSTOMER_CRED);

        DeviceProfile defaultDeviceProfile = this.deviceProfileService.findOrCreateDeviceProfile(demoTenant.getId(), DEFAULT_DEVICE_TYPE);

        createDevice(demoTenant.getId(), customerA.getId(), defaultDeviceProfile.getId(), "Test Device A1", "A1_TEST_TOKEN", null);
        createDevice(demoTenant.getId(), customerA.getId(), defaultDeviceProfile.getId(), "Test Device A2", "A2_TEST_TOKEN", null);
        createDevice(demoTenant.getId(), customerA.getId(), defaultDeviceProfile.getId(), "Test Device A3", "A3_TEST_TOKEN", null);
        createDevice(demoTenant.getId(), customerB.getId(), defaultDeviceProfile.getId(), "Test Device B1", "B1_TEST_TOKEN", null);
        createDevice(demoTenant.getId(), customerC.getId(), defaultDeviceProfile.getId(), "Test Device C1", "C1_TEST_TOKEN", null);

        createDevice(demoTenant.getId(), null, defaultDeviceProfile.getId(), "DHT11 Demo Device", "DHT11_DEMO_TOKEN", "Demo device that is used in sample " +
                "applications that upload data from DHT11 temperature and humidity sensor");

        createDevice(demoTenant.getId(), null, defaultDeviceProfile.getId(), "Raspberry Pi Demo Device", "RASPBERRY_PI_DEMO_TOKEN", "Demo device that is used in " +
                "Raspberry Pi GPIO control sample application");

        DeviceProfile thermostatDeviceProfile = new DeviceProfile();
        thermostatDeviceProfile.setTenantId(demoTenant.getId());
        thermostatDeviceProfile.setDefault(false);
        thermostatDeviceProfile.setName("thermostat");
        thermostatDeviceProfile.setType(DeviceProfileType.DEFAULT);
        thermostatDeviceProfile.setTransportType(DeviceTransportType.DEFAULT);
        thermostatDeviceProfile.setProvisionType(DeviceProfileProvisionType.DISABLED);
        thermostatDeviceProfile.setDescription("Thermostat device profile");
        thermostatDeviceProfile.setDefaultRuleChainId(ruleChainService.findTenantRuleChainsByType(
                demoTenant.getId(), RuleChainType.CORE, new PageLink(1, 0, "Thermostat")).getData().get(0).getId());

        DeviceProfileData deviceProfileData = new DeviceProfileData();
        DefaultDeviceProfileConfiguration configuration = new DefaultDeviceProfileConfiguration();
        DefaultDeviceProfileTransportConfiguration transportConfiguration = new DefaultDeviceProfileTransportConfiguration();
        DisabledDeviceProfileProvisionConfiguration provisionConfiguration = new DisabledDeviceProfileProvisionConfiguration(null);
        deviceProfileData.setConfiguration(configuration);
        deviceProfileData.setTransportConfiguration(transportConfiguration);
        deviceProfileData.setProvisionConfiguration(provisionConfiguration);
        thermostatDeviceProfile.setProfileData(deviceProfileData);

        DeviceProfileAlarm highTemperature = new DeviceProfileAlarm();
        highTemperature.setId("highTemperatureAlarmID");
        highTemperature.setAlarmType("High Temperature");
        AlarmRule temperatureRule = new AlarmRule();
        AlarmCondition temperatureCondition = new AlarmCondition();
        temperatureCondition.setSpec(new SimpleAlarmConditionSpec());

        AlarmConditionFilter temperatureAlarmFlagAttributeFilter = new AlarmConditionFilter();
        temperatureAlarmFlagAttributeFilter.setKey(new AlarmConditionFilterKey(AlarmConditionKeyType.ATTRIBUTE, "temperatureAlarmFlag"));
        temperatureAlarmFlagAttributeFilter.setValueType(EntityKeyValueType.BOOLEAN);
        BooleanFilterPredicate temperatureAlarmFlagAttributePredicate = new BooleanFilterPredicate();
        temperatureAlarmFlagAttributePredicate.setOperation(BooleanFilterPredicate.BooleanOperation.EQUAL);
        temperatureAlarmFlagAttributePredicate.setValue(new FilterPredicateValue<>(Boolean.TRUE));
        temperatureAlarmFlagAttributeFilter.setPredicate(temperatureAlarmFlagAttributePredicate);

        AlarmConditionFilter temperatureTimeseriesFilter = new AlarmConditionFilter();
        temperatureTimeseriesFilter.setKey(new AlarmConditionFilterKey(AlarmConditionKeyType.TIME_SERIES, "temperature"));
        temperatureTimeseriesFilter.setValueType(EntityKeyValueType.NUMERIC);
        NumericFilterPredicate temperatureTimeseriesFilterPredicate = new NumericFilterPredicate();
        temperatureTimeseriesFilterPredicate.setOperation(NumericFilterPredicate.NumericOperation.GREATER);
        FilterPredicateValue<Double> temperatureTimeseriesPredicateValue =
                new FilterPredicateValue<>(25.0, null,
                        new DynamicValue<>(DynamicValueSourceType.CURRENT_DEVICE, "temperatureAlarmThreshold"));
        temperatureTimeseriesFilterPredicate.setValue(temperatureTimeseriesPredicateValue);
        temperatureTimeseriesFilter.setPredicate(temperatureTimeseriesFilterPredicate);
        temperatureCondition.setCondition(Arrays.asList(temperatureAlarmFlagAttributeFilter, temperatureTimeseriesFilter));
        temperatureRule.setAlarmDetails("Current temperature = ${temperature}");
        temperatureRule.setCondition(temperatureCondition);
        highTemperature.setCreateRules(new TreeMap<>(Collections.singletonMap(AlarmSeverity.MAJOR, temperatureRule)));

        AlarmRule clearTemperatureRule = new AlarmRule();
        AlarmCondition clearTemperatureCondition = new AlarmCondition();
        clearTemperatureCondition.setSpec(new SimpleAlarmConditionSpec());

        AlarmConditionFilter clearTemperatureTimeseriesFilter = new AlarmConditionFilter();
        clearTemperatureTimeseriesFilter.setKey(new AlarmConditionFilterKey(AlarmConditionKeyType.TIME_SERIES, "temperature"));
        clearTemperatureTimeseriesFilter.setValueType(EntityKeyValueType.NUMERIC);
        NumericFilterPredicate clearTemperatureTimeseriesFilterPredicate = new NumericFilterPredicate();
        clearTemperatureTimeseriesFilterPredicate.setOperation(NumericFilterPredicate.NumericOperation.LESS_OR_EQUAL);
        FilterPredicateValue<Double> clearTemperatureTimeseriesPredicateValue =
                new FilterPredicateValue<>(25.0, null,
                        new DynamicValue<>(DynamicValueSourceType.CURRENT_DEVICE, "temperatureAlarmThreshold"));

        clearTemperatureTimeseriesFilterPredicate.setValue(clearTemperatureTimeseriesPredicateValue);
        clearTemperatureTimeseriesFilter.setPredicate(clearTemperatureTimeseriesFilterPredicate);
        clearTemperatureCondition.setCondition(Collections.singletonList(clearTemperatureTimeseriesFilter));
        clearTemperatureRule.setCondition(clearTemperatureCondition);
        clearTemperatureRule.setAlarmDetails("Current temperature = ${temperature}");
        highTemperature.setClearRule(clearTemperatureRule);

        DeviceProfileAlarm lowHumidity = new DeviceProfileAlarm();
        lowHumidity.setId("lowHumidityAlarmID");
        lowHumidity.setAlarmType("Low Humidity");
        AlarmRule humidityRule = new AlarmRule();
        AlarmCondition humidityCondition = new AlarmCondition();
        humidityCondition.setSpec(new SimpleAlarmConditionSpec());

        AlarmConditionFilter humidityAlarmFlagAttributeFilter = new AlarmConditionFilter();
        humidityAlarmFlagAttributeFilter.setKey(new AlarmConditionFilterKey(AlarmConditionKeyType.ATTRIBUTE, "humidityAlarmFlag"));
        humidityAlarmFlagAttributeFilter.setValueType(EntityKeyValueType.BOOLEAN);
        BooleanFilterPredicate humidityAlarmFlagAttributePredicate = new BooleanFilterPredicate();
        humidityAlarmFlagAttributePredicate.setOperation(BooleanFilterPredicate.BooleanOperation.EQUAL);
        humidityAlarmFlagAttributePredicate.setValue(new FilterPredicateValue<>(Boolean.TRUE));
        humidityAlarmFlagAttributeFilter.setPredicate(humidityAlarmFlagAttributePredicate);

        AlarmConditionFilter humidityTimeseriesFilter = new AlarmConditionFilter();
        humidityTimeseriesFilter.setKey(new AlarmConditionFilterKey(AlarmConditionKeyType.TIME_SERIES, "humidity"));
        humidityTimeseriesFilter.setValueType(EntityKeyValueType.NUMERIC);
        NumericFilterPredicate humidityTimeseriesFilterPredicate = new NumericFilterPredicate();
        humidityTimeseriesFilterPredicate.setOperation(NumericFilterPredicate.NumericOperation.LESS);
        FilterPredicateValue<Double> humidityTimeseriesPredicateValue =
                new FilterPredicateValue<>(60.0, null,
                        new DynamicValue<>(DynamicValueSourceType.CURRENT_DEVICE, "humidityAlarmThreshold"));
        humidityTimeseriesFilterPredicate.setValue(humidityTimeseriesPredicateValue);
        humidityTimeseriesFilter.setPredicate(humidityTimeseriesFilterPredicate);
        humidityCondition.setCondition(Arrays.asList(humidityAlarmFlagAttributeFilter, humidityTimeseriesFilter));

        humidityRule.setCondition(humidityCondition);
        humidityRule.setAlarmDetails("Current humidity = ${humidity}");
        lowHumidity.setCreateRules(new TreeMap<>(Collections.singletonMap(AlarmSeverity.MINOR, humidityRule)));

        AlarmRule clearHumidityRule = new AlarmRule();
        AlarmCondition clearHumidityCondition = new AlarmCondition();
        clearHumidityCondition.setSpec(new SimpleAlarmConditionSpec());

        AlarmConditionFilter clearHumidityTimeseriesFilter = new AlarmConditionFilter();
        clearHumidityTimeseriesFilter.setKey(new AlarmConditionFilterKey(AlarmConditionKeyType.TIME_SERIES, "humidity"));
        clearHumidityTimeseriesFilter.setValueType(EntityKeyValueType.NUMERIC);
        NumericFilterPredicate clearHumidityTimeseriesFilterPredicate = new NumericFilterPredicate();
        clearHumidityTimeseriesFilterPredicate.setOperation(NumericFilterPredicate.NumericOperation.GREATER_OR_EQUAL);
        FilterPredicateValue<Double> clearHumidityTimeseriesPredicateValue =
                new FilterPredicateValue<>(60.0, null,
                        new DynamicValue<>(DynamicValueSourceType.CURRENT_DEVICE, "humidityAlarmThreshold"));

        clearHumidityTimeseriesFilterPredicate.setValue(clearHumidityTimeseriesPredicateValue);
        clearHumidityTimeseriesFilter.setPredicate(clearHumidityTimeseriesFilterPredicate);
        clearHumidityCondition.setCondition(Collections.singletonList(clearHumidityTimeseriesFilter));
        clearHumidityRule.setCondition(clearHumidityCondition);
        clearHumidityRule.setAlarmDetails("Current humidity = ${humidity}");
        lowHumidity.setClearRule(clearHumidityRule);

        deviceProfileData.setAlarms(Arrays.asList(highTemperature, lowHumidity));

        DeviceProfile savedThermostatDeviceProfile = deviceProfileService.saveDeviceProfile(thermostatDeviceProfile);

        DeviceId t1Id = createDevice(demoTenant.getId(), null, savedThermostatDeviceProfile.getId(), "Thermostat T1", "T1_TEST_TOKEN", "Demo device for Thermostats dashboard").getId();
        DeviceId t2Id = createDevice(demoTenant.getId(), null, savedThermostatDeviceProfile.getId(), "Thermostat T2", "T2_TEST_TOKEN", "Demo device for Thermostats dashboard").getId();

        attributesService.save(demoTenant.getId(), t1Id, AttributeScope.SERVER_SCOPE,
                Arrays.asList(new BaseAttributeKvEntry(System.currentTimeMillis(), new DoubleDataEntry("latitude", 37.3948)),
                        new BaseAttributeKvEntry(System.currentTimeMillis(), new DoubleDataEntry("longitude", -122.1503)),
                        new BaseAttributeKvEntry(System.currentTimeMillis(), new BooleanDataEntry("temperatureAlarmFlag", true)),
                        new BaseAttributeKvEntry(System.currentTimeMillis(), new BooleanDataEntry("humidityAlarmFlag", true)),
                        new BaseAttributeKvEntry(System.currentTimeMillis(), new LongDataEntry("temperatureAlarmThreshold", (long) 20)),
                        new BaseAttributeKvEntry(System.currentTimeMillis(), new LongDataEntry("humidityAlarmThreshold", (long) 50))));

        attributesService.save(demoTenant.getId(), t2Id, AttributeScope.SERVER_SCOPE,
                Arrays.asList(new BaseAttributeKvEntry(System.currentTimeMillis(), new DoubleDataEntry("latitude", 37.493801)),
                        new BaseAttributeKvEntry(System.currentTimeMillis(), new DoubleDataEntry("longitude", -121.948769)),
                        new BaseAttributeKvEntry(System.currentTimeMillis(), new BooleanDataEntry("temperatureAlarmFlag", true)),
                        new BaseAttributeKvEntry(System.currentTimeMillis(), new BooleanDataEntry("humidityAlarmFlag", true)),
                        new BaseAttributeKvEntry(System.currentTimeMillis(), new LongDataEntry("temperatureAlarmThreshold", (long) 25)),
                        new BaseAttributeKvEntry(System.currentTimeMillis(), new LongDataEntry("humidityAlarmThreshold", (long) 30))));

        installScripts.loadDashboards(demoTenant.getId(), null);
        installScripts.createDefaultTenantDashboards(demoTenant.getId(), null);
    }

    @Override
    public void loadSystemWidgets() throws Exception {
        installScripts.loadSystemWidgets();
    }

    private User createUser(Authority authority,
                            TenantId tenantId,
                            CustomerId customerId,
                            String email,
                            String password) {
        User user = new User();
        user.setAuthority(authority);
        user.setEmail(email);
        user.setTenantId(tenantId);
        user.setCustomerId(customerId);
        user = userService.saveUser(tenantId, user);
        UserCredentials userCredentials = userService.findUserCredentialsByUserId(TenantId.SYS_TENANT_ID, user.getId());
        userCredentials.setPassword(passwordEncoder.encode(password));
        userCredentials.setEnabled(true);
        userCredentials.setActivateToken(null);
        userService.saveUserCredentials(TenantId.SYS_TENANT_ID, userCredentials);
        return user;
    }

    private Device createDevice(TenantId tenantId,
                                CustomerId customerId,
                                DeviceProfileId deviceProfileId,
                                String name,
                                String accessToken,
                                String description) {
        Device device = new Device();
        device.setTenantId(tenantId);
        device.setCustomerId(customerId);
        device.setDeviceProfileId(deviceProfileId);
        device.setName(name);
        if (description != null) {
            ObjectNode additionalInfo = JacksonUtil.newObjectNode();
            additionalInfo.put("description", description);
            device.setAdditionalInfo(additionalInfo);
        }
        device = deviceService.saveDevice(device);
        save(device.getId(), ACTIVITY_STATE, false);
        DeviceCredentials deviceCredentials = deviceCredentialsService.findDeviceCredentialsByDeviceId(TenantId.SYS_TENANT_ID, device.getId());
        deviceCredentials.setCredentialsId(accessToken);
        deviceCredentialsService.updateDeviceCredentials(TenantId.SYS_TENANT_ID, deviceCredentials);
        return device;
    }

    private void save(DeviceId deviceId, String key, boolean value) {
        if (persistActivityToTelemetry) {
            ListenableFuture<TimeseriesSaveResult> saveFuture = tsService.save(
                    TenantId.SYS_TENANT_ID,
                    deviceId,
                    Collections.singletonList(new BasicTsKvEntry(System.currentTimeMillis(), new BooleanDataEntry(key, value))), 0L);
            addTsCallback(saveFuture, new TelemetrySaveCallback<>(deviceId, key, value));
        } else {
            ListenableFuture<AttributesSaveResult> saveFuture = attributesService.save(
                    TenantId.SYS_TENANT_ID, deviceId, AttributeScope.SERVER_SCOPE, new BaseAttributeKvEntry(new BooleanDataEntry(key, value), System.currentTimeMillis())
            );
            addTsCallback(saveFuture, new TelemetrySaveCallback<>(deviceId, key, value));
        }
    }

    private static class TelemetrySaveCallback<T> implements FutureCallback<T> {
        private final DeviceId deviceId;
        private final String key;
        private final Object value;

        TelemetrySaveCallback(DeviceId deviceId, String key, Object value) {
            this.deviceId = deviceId;
            this.key = key;
            this.value = value;
        }

        @Override
        public void onSuccess(@Nullable T result) {
            log.trace("[{}] Successfully updated attribute [{}] with value [{}]", deviceId, key, value);
        }

        @Override
        public void onFailure(Throwable t) {
            log.warn("[{}] Failed to update attribute [{}] with value [{}]", deviceId, key, value, t);
        }
    }

    private <S> void addTsCallback(ListenableFuture<S> saveFuture, final FutureCallback<S> callback) {
        Futures.addCallback(saveFuture, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable S result) {
                callback.onSuccess(result);
            }

            @Override
            public void onFailure(Throwable t) {
                callback.onFailure(t);
            }
        }, tsCallBackExecutor);
    }

    @Override
    public void createQueues() {
        Queue mainQueue = queueService.findQueueByTenantIdAndName(TenantId.SYS_TENANT_ID, DataConstants.MAIN_QUEUE_NAME);
        if (mainQueue == null) {
            mainQueue = new Queue();
            mainQueue.setTenantId(TenantId.SYS_TENANT_ID);
            mainQueue.setName(DataConstants.MAIN_QUEUE_NAME);
            mainQueue.setTopic(DataConstants.MAIN_QUEUE_TOPIC);
            mainQueue.setPollInterval(25);
            mainQueue.setPartitions(10);
            mainQueue.setConsumerPerPartition(true);
            mainQueue.setPackProcessingTimeout(2000);
            SubmitStrategy mainQueueSubmitStrategy = new SubmitStrategy();
            mainQueueSubmitStrategy.setType(SubmitStrategyType.BURST);
            mainQueueSubmitStrategy.setBatchSize(1000);
            mainQueue.setSubmitStrategy(mainQueueSubmitStrategy);
            ProcessingStrategy mainQueueProcessingStrategy = new ProcessingStrategy();
            mainQueueProcessingStrategy.setType(ProcessingStrategyType.SKIP_ALL_FAILURES);
            mainQueueProcessingStrategy.setRetries(3);
            mainQueueProcessingStrategy.setFailurePercentage(0);
            mainQueueProcessingStrategy.setPauseBetweenRetries(3);
            mainQueueProcessingStrategy.setMaxPauseBetweenRetries(3);
            mainQueue.setProcessingStrategy(mainQueueProcessingStrategy);
            queueService.saveQueue(mainQueue);
        }

        Queue highPriorityQueue = queueService.findQueueByTenantIdAndName(TenantId.SYS_TENANT_ID, DataConstants.HP_QUEUE_NAME);
        if (highPriorityQueue == null) {
            highPriorityQueue = new Queue();
            highPriorityQueue.setTenantId(TenantId.SYS_TENANT_ID);
            highPriorityQueue.setName(DataConstants.HP_QUEUE_NAME);
            highPriorityQueue.setTopic(DataConstants.HP_QUEUE_TOPIC);
            highPriorityQueue.setPollInterval(25);
            highPriorityQueue.setPartitions(10);
            highPriorityQueue.setConsumerPerPartition(true);
            highPriorityQueue.setPackProcessingTimeout(2000);
            SubmitStrategy highPriorityQueueSubmitStrategy = new SubmitStrategy();
            highPriorityQueueSubmitStrategy.setType(SubmitStrategyType.BURST);
            highPriorityQueueSubmitStrategy.setBatchSize(100);
            highPriorityQueue.setSubmitStrategy(highPriorityQueueSubmitStrategy);
            ProcessingStrategy highPriorityQueueProcessingStrategy = new ProcessingStrategy();
            highPriorityQueueProcessingStrategy.setType(ProcessingStrategyType.RETRY_FAILED_AND_TIMED_OUT);
            highPriorityQueueProcessingStrategy.setRetries(0);
            highPriorityQueueProcessingStrategy.setFailurePercentage(0);
            highPriorityQueueProcessingStrategy.setPauseBetweenRetries(5);
            highPriorityQueueProcessingStrategy.setMaxPauseBetweenRetries(5);
            highPriorityQueue.setProcessingStrategy(highPriorityQueueProcessingStrategy);
            queueService.saveQueue(highPriorityQueue);
        }

        Queue sequentialByOriginatorQueue = queueService.findQueueByTenantIdAndName(TenantId.SYS_TENANT_ID, DataConstants.SQ_QUEUE_NAME);
        if (sequentialByOriginatorQueue == null) {
            sequentialByOriginatorQueue = new Queue();
            sequentialByOriginatorQueue.setTenantId(TenantId.SYS_TENANT_ID);
            sequentialByOriginatorQueue.setName(DataConstants.SQ_QUEUE_NAME);
            sequentialByOriginatorQueue.setTopic(DataConstants.SQ_QUEUE_TOPIC);
            sequentialByOriginatorQueue.setPollInterval(25);
            sequentialByOriginatorQueue.setPartitions(10);
            sequentialByOriginatorQueue.setPackProcessingTimeout(2000);
            sequentialByOriginatorQueue.setConsumerPerPartition(true);
            SubmitStrategy sequentialByOriginatorQueueSubmitStrategy = new SubmitStrategy();
            sequentialByOriginatorQueueSubmitStrategy.setType(SubmitStrategyType.SEQUENTIAL_BY_ORIGINATOR);
            sequentialByOriginatorQueueSubmitStrategy.setBatchSize(100);
            sequentialByOriginatorQueue.setSubmitStrategy(sequentialByOriginatorQueueSubmitStrategy);
            ProcessingStrategy sequentialByOriginatorQueueProcessingStrategy = new ProcessingStrategy();
            sequentialByOriginatorQueueProcessingStrategy.setType(ProcessingStrategyType.RETRY_FAILED_AND_TIMED_OUT);
            sequentialByOriginatorQueueProcessingStrategy.setRetries(3);
            sequentialByOriginatorQueueProcessingStrategy.setFailurePercentage(0);
            sequentialByOriginatorQueueProcessingStrategy.setPauseBetweenRetries(5);
            sequentialByOriginatorQueueProcessingStrategy.setMaxPauseBetweenRetries(5);
            sequentialByOriginatorQueue.setProcessingStrategy(sequentialByOriginatorQueueProcessingStrategy);
            queueService.saveQueue(sequentialByOriginatorQueue);
        }
    }

    @Override
    @SneakyThrows
    public void createDefaultNotificationConfigs() {
        log.info("Creating default notification configs for system admin");
        if (notificationTargetService.countNotificationTargetsByTenantId(TenantId.SYS_TENANT_ID) == 0) {
            notificationSettingsService.createDefaultNotificationConfigs(TenantId.SYS_TENANT_ID);
        }
        PageDataIterable<TenantId> tenants = new PageDataIterable<>(tenantService::findTenantsIds, 500);
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(Runtime.getRuntime().availableProcessors(), 4));
        log.info("Creating default notification configs for all tenants");
        AtomicInteger count = new AtomicInteger();
        for (TenantId tenantId : tenants) {
            executor.submit(() -> {
                if (notificationTargetService.countNotificationTargetsByTenantId(tenantId) == 0) {
                    notificationSettingsService.createDefaultNotificationConfigs(tenantId);
                    int n = count.incrementAndGet();
                    if (n % 500 == 0) {
                        log.info("{} tenants processed", n);
                    }
                }
            });
        }
        executor.shutdown();
        executor.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
    }

    @Override
    @SneakyThrows
    public void updateDefaultNotificationConfigs(boolean updateTenants) {
        log.info("Updating notification configs...");
        notificationSettingsService.updateDefaultNotificationConfigs(TenantId.SYS_TENANT_ID);

        if (updateTenants) {
            PageDataIterable<TenantId> tenants = new PageDataIterable<>(tenantService::findTenantsIds, 500);
            ExecutorService executor = Executors.newFixedThreadPool(Math.max(Runtime.getRuntime().availableProcessors(), 4));
            AtomicInteger count = new AtomicInteger();
            for (TenantId tenantId : tenants) {
                executor.submit(() -> {
                    notificationSettingsService.updateDefaultNotificationConfigs(tenantId);
                    int n = count.incrementAndGet();
                    if (n % 500 == 0) {
                        log.info("{} tenants processed", n);
                    }
                });
            }
            executor.shutdown();
            executor.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
        }
    }

}
