package com.aideep.domain.node.repository;

import com.aideep.domain.node.entity.ProcessedNodeEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedNodeEventRepository extends JpaRepository<ProcessedNodeEvent, UUID> {
}
