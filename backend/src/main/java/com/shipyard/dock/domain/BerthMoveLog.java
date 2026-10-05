package com.shipyard.dock.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 船台移泊操作日志：平面占位图上把在台船舶从原船台拖到目标船台的每一次尝试。
 *
 * <p>只追加、不修改、不删除。成功（原子切换完成）与失败（校验未过、原占位保留）都留痕，
 * {@code requestId} 唯一，作为重复提交 / 网络重试的幂等键。</p>
 */
@Entity
@Table(name = "berth_move_log")
@Getter
@Setter
public class BerthMoveLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一次拖动的幂等键：同一 request_id 只允许一个有效结果 */
    @Column(name = "request_id", nullable = false, unique = true, length = 48)
    private String requestId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "vessel_no", length = 24)
    private String vesselNo;

    @Column(name = "vessel_name", length = 48)
    private String vesselName;

    /** 操作前的原船台（预期船位） */
    @Column(name = "from_berth_id")
    private Long fromBerthId;

    @Column(name = "from_berth_no", length = 16)
    private String fromBerthNo;

    /** 目标船台 */
    @Column(name = "to_berth_id")
    private Long toBerthId;

    @Column(name = "to_berth_no", length = 16)
    private String toBerthNo;

    /** 处理后的真实船位（成功为目标船台，失败为原船台 / 实际占用船台；待排区为 null） */
    @Column(name = "current_berth_id")
    private Long currentBerthId;

    @Column(name = "current_berth_no", length = 16)
    private String currentBerthNo;

    /** 结果：SUCCESS 成功 / FAILED 失败（原占位保留） / DUPLICATE 重复请求回传首次结果 */
    @Column(name = "result", nullable = false, length = 16)
    private String result;

    /** 失败原因（成功为 null） */
    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
