package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shipyard.dock.domain.BerthMoveLog;

public interface BerthMoveLogRepository extends JpaRepository<BerthMoveLog, Long> {

    /** 幂等查询：同一拖动请求只处理一次 */
    Optional<BerthMoveLog> findByRequestId(String requestId);

    List<BerthMoveLog> findAllByOrderByIdDesc();

    List<BerthMoveLog> findByVesselIdOrderByIdDesc(Long vesselId);

    List<BerthMoveLog> findByFromBerthIdOrToBerthIdOrderByIdDesc(Long fromBerthId, Long toBerthId);
}
