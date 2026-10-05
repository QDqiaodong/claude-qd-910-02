package com.shipyard.dock.service;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.shipyard.dock.domain.BerthMoveLog;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.repository.BerthMoveLogRepository;

/**
 * 移泊失败记录器：失败日志必须在独立事务（REQUIRES_NEW）里提交，
 * 不能挂在移泊主事务上——主事务要整体回滚以保留原船台占位，挂在上面日志会一起被回滚掉。
 *
 * <p>独立成 Bean 是为了让 Spring 事务代理生效（同类内自调用 @Transactional 方法不会走代理）。</p>
 */
@Component
public class BerthMoveRecorder {

    private final BerthMoveLogRepository moveLogRepository;

    public BerthMoveRecorder(BerthMoveLogRepository moveLogRepository) {
        this.moveLogRepository = moveLogRepository;
    }

    /**
     * 在新事务中追加一条失败记录。
     *
     * <p>并发场景下可能与另一条同 requestId 的记录抢唯一键：谁先提交谁有效，
     * 后来者捕获唯一约束冲突并回读已落库的那条，保证调用方总能拿到「本次请求的首次结果」。</p>
     *
     * @return 本次实际生效（新写入或已存在）的日志
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BerthMoveLog recordFailure(String requestId, Vessel vessel, Long fromBerthId, String fromBerthNo,
            Long toBerthId, String toBerthNo, Long currentBerthId, String currentBerthNo,
            String reason, String operator) {
        BerthMoveLog log = new BerthMoveLog();
        log.setRequestId(requestId);
        log.setVesselId(vessel.getId());
        log.setVesselNo(vessel.getVesselNo());
        log.setVesselName(vessel.getVesselName());
        log.setFromBerthId(fromBerthId);
        log.setFromBerthNo(fromBerthNo);
        log.setToBerthId(toBerthId);
        log.setToBerthNo(toBerthNo);
        log.setCurrentBerthId(currentBerthId);
        log.setCurrentBerthNo(currentBerthNo);
        log.setResult("FAILED");
        log.setFailReason(reason);
        log.setOperator(operator);
        log.setCreatedAt(LocalDateTime.now());
        try {
            return moveLogRepository.saveAndFlush(log);
        } catch (DataIntegrityViolationException dup) {
            // 同 requestId 的记录已由并发请求落库：以那条首次结果为准
            return moveLogRepository.findByRequestId(requestId).orElseThrow(() -> dup);
        }
    }
}
