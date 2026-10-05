package com.shipyard.dock.dto;

import lombok.Data;

/**
 * 移泊结果：前端无论成功、失败还是重复请求，都以此回传的「当前真实船位」为准刷新页面，
 * 不允许前端自行用「再占一次原船台」补偿。
 */
@Data
public class BerthMoveResult {

    /** SUCCESS 本次移泊成功 / FAILED 本次被拒绝（原占位保留） / DUPLICATE 重复请求，回传首次结果 */
    private String result;

    /** 是否为幂等回传（requestId 此前已处理过） */
    private boolean replay;

    /** 被拒绝时的原因；成功或幂等回传成功时为 null */
    private String message;

    private Long vesselId;

    private String vesselName;

    /** 处理后船舶实际所在船台（在待排区时为 null） */
    private Long currentBerthId;

    private String currentBerthNo;

    private String currentBerthName;

    private Long fromBerthId;

    private String fromBerthNo;

    private Long toBerthId;

    private String toBerthNo;

    /** 操作时间（首次处理时间） */
    private String operatedAt;
}
