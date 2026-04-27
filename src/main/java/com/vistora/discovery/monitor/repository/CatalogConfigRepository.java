package com.vistora.discovery.monitor.repository;

import com.vistora.discovery.monitor.model.CatalogConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CatalogConfigRepository extends JpaRepository<CatalogConfig, Long> {
    
    List<CatalogConfig> findByProcessName(String processName);
    
    List<CatalogConfig> findByExtensionId(String extensionId);
    
    List<CatalogConfig> findByPort(Integer port);
    
    List<CatalogConfig> findByConfigPath(String configPath);
    
    Optional<CatalogConfig> findByCatalogId(Long catalogId);

    List<CatalogConfig> findByToolIdentifier(String toolIdentifier);
}
