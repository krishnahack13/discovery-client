package com.vistora.discovery.monitor.repository;

import com.vistora.discovery.monitor.model.DeviceAIDetection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for device_detection table - one row per device with JSON
 */
@Repository
public interface DeviceAIDetectionRepository extends JpaRepository<DeviceAIDetection, String> {

    Optional<DeviceAIDetection> findById(String deviceId);

    List<DeviceAIDetection> findByUserEmail(String userEmail);

    boolean existsById(String deviceId);
}
