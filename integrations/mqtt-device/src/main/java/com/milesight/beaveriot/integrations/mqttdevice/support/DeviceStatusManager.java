package com.milesight.beaveriot.integrations.mqttdevice.support;

import com.milesight.beaveriot.context.api.DeviceServiceProvider;
import com.milesight.beaveriot.context.api.EntityServiceProvider;
import com.milesight.beaveriot.context.api.EntityValueServiceProvider;
import com.milesight.beaveriot.context.integration.enums.AccessMod;
import com.milesight.beaveriot.context.integration.enums.EntityValueType;
import com.milesight.beaveriot.context.integration.model.Device;
import com.milesight.beaveriot.context.integration.model.Entity;
import com.milesight.beaveriot.context.integration.model.EntityBuilder;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * author: Luxb
 * create: 2025/7/21 14:46
 **/
@Component
public class DeviceStatusManager {
    private static final String IDENTIFIER_DEVICE_STATUS = "device_status";
    private static final String NAME_DEVICE_STATUS = "Device status";
    private static final String STATUS_VALUE_ONLINE = "Online";
    private static final String STATUS_VALUE_OFFLINE = "Offline";
    private final DeviceServiceProvider deviceServiceProvider;
    private final EntityServiceProvider entityServiceProvider;
    private final EntityValueServiceProvider entityValueServiceProvider;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    private final Map<String, ScheduledFuture<?>> deviceTimerFutures = new ConcurrentHashMap<>();
    private final Map<String, UpdateDeviceStatusFunction> integrationUpdateDeviceStatusFunctions = new ConcurrentHashMap<>();

    public DeviceStatusManager(DeviceServiceProvider deviceServiceProvider, EntityServiceProvider entityServiceProvider, EntityValueServiceProvider entityValueServiceProvider) {
        this.deviceServiceProvider = deviceServiceProvider;
        this.entityServiceProvider = entityServiceProvider;
        this.entityValueServiceProvider = entityValueServiceProvider;
    }

    public void register(String integrationId) {
        integrationUpdateDeviceStatusFunctions.put(integrationId,
                UpdateDeviceStatusFunction.of(this::updateDeviceStatusToOnline, this::updateDeviceStatusToOffline));
        init(integrationId);
    }

    public void register(String integrationId, DeviceStatusOnlineUpdater updateDeviceStatusToOnlineFunction, DeviceStatusOfflineUpdater updateDeviceStatusToOfflineFunction) {
        integrationUpdateDeviceStatusFunctions.put(integrationId,
                UpdateDeviceStatusFunction.of(updateDeviceStatusToOnlineFunction, updateDeviceStatusToOfflineFunction));
        init(integrationId);
    }

    public void init(String integrationId) {
        UpdateDeviceStatusFunction updateDeviceStatusFunction = integrationUpdateDeviceStatusFunctions.get(integrationId);
        List<Device> devices = deviceServiceProvider.findAll(integrationId);
        if (updateDeviceStatusFunction != null && !CollectionUtils.isEmpty(devices)) {
            devices.forEach(device -> startOfflineCountdown(device, 300));
        }
    }

    public void dataUploaded(Device device, ExchangePayload payload, long offlineSeconds) {
        cancelOfflineCountdown(device);
        UpdateDeviceStatusFunction updateDeviceStatusFunction = integrationUpdateDeviceStatusFunctions.get(device.getIntegrationId());
        updateDeviceStatusFunction.getUpdateDeviceStatusToOnlineFunction().update(device, payload);
        startOfflineCountdown(device, offlineSeconds);
    }

    public void destroy() {
        deviceTimerFutures.values().forEach(future -> future.cancel(true));
        scheduler.shutdown();
    }

    private void startOfflineCountdown(Device device, long offlineSeconds) {
        if (deviceTimerFutures.containsKey(device.getKey())) {
            return;
        }

        UpdateDeviceStatusFunction updateDeviceStatusFunction = integrationUpdateDeviceStatusFunctions.get(device.getIntegrationId());
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            updateDeviceStatusFunction.getUpdateDeviceStatusToOfflineFunction().update(device);
            deviceTimerFutures.remove(device.getKey());
        }, offlineSeconds, TimeUnit.SECONDS);
        deviceTimerFutures.put(device.getKey(), future);
    }

    private void updateDeviceStatusToOnline(Device device, ExchangePayload payload) {
        updateDeviceStatus(device, STATUS_VALUE_ONLINE);
    }

    private void updateDeviceStatusToOffline(Device device) {
        updateDeviceStatus(device, STATUS_VALUE_OFFLINE);
    }

    private void cancelOfflineCountdown(Device device) {
        ScheduledFuture<?> future = deviceTimerFutures.get(device.getKey());
        if (future != null) {
            future.cancel(false);
            deviceTimerFutures.remove(device.getKey());
        }
    }

    private void updateDeviceStatus(Device device, String deviceStatus) {
        String entityKey = device.getKey() + "." + IDENTIFIER_DEVICE_STATUS;
        Entity entity = entityServiceProvider.findByKey(entityKey);
        if (entity == null) {
            entity = new EntityBuilder(device.getIntegrationId(), device.getKey())
                    .identifier(IDENTIFIER_DEVICE_STATUS)
                    .property(NAME_DEVICE_STATUS, AccessMod.R)
                    .valueType(EntityValueType.STRING)
                    .build();
            entityServiceProvider.save(entity);
        }

        ExchangePayload payload = ExchangePayload.create(entityKey, deviceStatus);
        entityValueServiceProvider.saveValues(payload);
    }

    @Data
    public static class UpdateDeviceStatusFunction {
        private DeviceStatusOnlineUpdater updateDeviceStatusToOnlineFunction;
        private DeviceStatusOfflineUpdater updateDeviceStatusToOfflineFunction;

        public static UpdateDeviceStatusFunction of(DeviceStatusOnlineUpdater onlineUpdater, DeviceStatusOfflineUpdater offlineUpdater) {
            UpdateDeviceStatusFunction function = new UpdateDeviceStatusFunction();
            function.setUpdateDeviceStatusToOnlineFunction(onlineUpdater);
            function.setUpdateDeviceStatusToOfflineFunction(offlineUpdater);
            return function;
        }
    }

    @FunctionalInterface
    public interface DeviceStatusOnlineUpdater {
        void update(Device device, ExchangePayload payload);
    }

    @FunctionalInterface
    public interface DeviceStatusOfflineUpdater {
        void update(Device device);
    }
}
