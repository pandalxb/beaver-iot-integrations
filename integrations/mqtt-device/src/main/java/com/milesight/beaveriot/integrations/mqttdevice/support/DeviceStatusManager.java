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
import jakarta.annotation.PreDestroy;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * author: Luxb
 * create: 2025/7/21 14:46
 **/
@SuppressWarnings("unused")
@Component
public class DeviceStatusManager {
    private static final String IDENTIFIER_DEVICE_STATUS = "status";
    private static final String NAME_DEVICE_STATUS = "Status";
    private static final String STATUS_VALUE_ONLINE = "Online";
    private static final String STATUS_VALUE_OFFLINE = "Offline";
    private static final long DEFAULT_OFFLINE_SECONDS = 300;
    private final DeviceServiceProvider deviceServiceProvider;
    private final EntityServiceProvider entityServiceProvider;
    private final EntityValueServiceProvider entityValueServiceProvider;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    private final Map<String, ScheduledFuture<?>> deviceTimerFutures = new ConcurrentHashMap<>();
    private final Map<String, DeviceStatusConfig> integrationDeviceStatusConfigs = new ConcurrentHashMap<>();

    public DeviceStatusManager(DeviceServiceProvider deviceServiceProvider, EntityServiceProvider entityServiceProvider, EntityValueServiceProvider entityValueServiceProvider) {
        this.deviceServiceProvider = deviceServiceProvider;
        this.entityServiceProvider = entityServiceProvider;
        this.entityValueServiceProvider = entityValueServiceProvider;
    }

    /**
     * Registers an integration with the device status manager.
     *
     * @param integrationId the ID of the integration
     */
    public void register(String integrationId) {
        register(integrationId, null);
    }

    /**
     * Registers an integration with the device status manager.
     *
     * @param integrationId         the ID of the integration
     * @param offlineSecondsFetcher a {@link Function} that returns the offline timeout in seconds for a given device
     *                              (e.g., returning a fixed value or calculating based on device)
     */
    public void register(String integrationId, Function<Device, Long> offlineSecondsFetcher) {
        register(integrationId, null, null, offlineSecondsFetcher);
    }

    /**
     * Registers an integration with the device status manager.
     *
     * @param integrationId         the ID of the integration
     * @param onlineUpdater         a {@link BiConsumer} that updates device status when the device is online
     *                              (e.g., setting the device's entity "status" to "online")
     * @param offlineUpdater        a {@link Consumer} that updates device status when the device is offline
     *                              (e.g., setting the device's entity "status" to "offline")
     * @param offlineSecondsFetcher a {@link Function} that returns the offline timeout in seconds for a given device
     *                              (e.g., returning a fixed value or calculating based on device)
     */
    public void register(String integrationId, BiConsumer<Device, ExchangePayload> onlineUpdater, Consumer<Device> offlineUpdater, Function<Device, Long> offlineSecondsFetcher) {
        if (onlineUpdater == null) {
            onlineUpdater = this::updateDeviceStatusToOnline;
        }
        if (offlineUpdater == null) {
            offlineUpdater = this::updateDeviceStatusToOffline;
        }
        if (offlineSecondsFetcher == null) {
            offlineSecondsFetcher = this::getDeviceOfflineSeconds;
        }
        DeviceStatusConfig config = DeviceStatusConfig.of(onlineUpdater, offlineUpdater, offlineSecondsFetcher);
        integrationDeviceStatusConfigs.put(integrationId, config);
        initDevices(integrationId, config);
    }

    /**
     * Callback invoked when the device has successfully uploaded data.
     *
     * @param device  the device that uploaded the data
     * @param payload the exchange payload containing the uploaded data
     */
    public void dataUploaded(Device device, ExchangePayload payload) {
        cancelOfflineCountdown(device);
        DeviceStatusConfig config = integrationDeviceStatusConfigs.get(device.getIntegrationId());
        config.getOnlineUpdater().accept(device, payload);
        long offlineSeconds = Optional.ofNullable(config.getOfflineSecondsFetcher())
                .map(f -> f.apply(device))
                .orElse(DEFAULT_OFFLINE_SECONDS);
        startOfflineCountdown(device, offlineSeconds);
    }

    public void updateDeviceStatusToOnline(Device device) {
        updateDeviceStatus(device, STATUS_VALUE_ONLINE);
    }

    public void updateDeviceStatusToOffline(Device device) {
        updateDeviceStatus(device, STATUS_VALUE_OFFLINE);
    }

    @PreDestroy
    private void destroy() {
        deviceTimerFutures.values().forEach(future -> future.cancel(true));
        scheduler.shutdown();
    }

    private void initDevices(String integrationId, DeviceStatusConfig config) {
        List<Device> devices = deviceServiceProvider.findAll(integrationId);
        if (config != null && !CollectionUtils.isEmpty(devices)) {
            Function<Device, Long> offlineSecondsFetcher = config.getOfflineSecondsFetcher();
            devices.forEach(device -> {
                long offlineSeconds = Optional.ofNullable(offlineSecondsFetcher)
                        .map(f -> f.apply(device))
                        .orElse(DEFAULT_OFFLINE_SECONDS);
                startOfflineCountdown(device, offlineSeconds);
            });
        }
    }

    private void startOfflineCountdown(Device device, long offlineSeconds) {
        if (deviceTimerFutures.containsKey(device.getKey())) {
            return;
        }

        DeviceStatusConfig deviceStatusConfig = integrationDeviceStatusConfigs.get(device.getIntegrationId());
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            deviceStatusConfig.getOfflineUpdater().accept(device);
            deviceTimerFutures.remove(device.getKey());
        }, offlineSeconds, TimeUnit.SECONDS);
        deviceTimerFutures.put(device.getKey(), future);
    }

    private void updateDeviceStatusToOnline(Device device, ExchangePayload payload) {
        updateDeviceStatus(device, STATUS_VALUE_ONLINE);
    }

    private long getDeviceOfflineSeconds(Device device) {
        return DEFAULT_OFFLINE_SECONDS;
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
    public static class DeviceStatusConfig {
        private BiConsumer<Device, ExchangePayload> onlineUpdater;
        private Consumer<Device> offlineUpdater;
        private Function<Device, Long> offlineSecondsFetcher;

        public static DeviceStatusConfig of(BiConsumer<Device, ExchangePayload> onlineUpdater, Consumer<Device> offlineUpdater, Function<Device, Long> offlineSecondsFetcher) {
            DeviceStatusConfig config = new DeviceStatusConfig();
            config.setOnlineUpdater(onlineUpdater);
            config.setOfflineUpdater(offlineUpdater);
            config.setOfflineSecondsFetcher(offlineSecondsFetcher);
            return config;
        }
    }
}
