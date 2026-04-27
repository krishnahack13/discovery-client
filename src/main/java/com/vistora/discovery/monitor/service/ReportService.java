package com.vistora.discovery.monitor.service;

import com.vistora.discovery.monitor.model.DeviceAIDetection;
import com.vistora.discovery.monitor.repository.DeviceAIDetectionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Report Service - Uses merged device_ai_detection table (one row per device)
 */
@Service
public class ReportService {

    private final DeviceAIDetectionRepository deviceAIDetectionRepository;

    public ReportService(DeviceAIDetectionRepository deviceAIDetectionRepository) {
        this.deviceAIDetectionRepository = deviceAIDetectionRepository;
    }

    // Get all devices with AI detections
    public List<DeviceAIDetection> getAllDetections() {
        return deviceAIDetectionRepository.findAll();
    }

    // Get by user email
    public List<DeviceAIDetection> getDetectionsByUserEmail(String userEmail) {
        return deviceAIDetectionRepository.findByUserEmail(userEmail);
    }

    // Get specific device detection
    public DeviceAIDetection getDeviceDetection(String deviceId) {
        return deviceAIDetectionRepository.findById(deviceId).orElse(null);
    }
}
