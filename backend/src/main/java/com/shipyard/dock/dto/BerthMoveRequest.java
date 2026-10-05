package com.shipyard.dock.dto;

import lombok.Data;

/**
 * 船台移泊请求：把在台船舶从原船台整体移到另一座船台。
 *
 * <p>{@code requestId} 为一次拖动的幂等键：并发拖动、重复提交、网络重试带上同一个键，
 * 服务端只产生一个有效结果，后到请求回传首次结果与当前真实船位。</p>
 */
@Data
public class BerthMoveRequest {

    /** 要移泊的船舶 */
    private Long vesselId;

    /** 预期的原船台（船舶当前必须仍在这座船台，否则拒绝） */
    private Long fromBerthId;

    /** 目标船台（必须仍空闲，且尺度 / 承载校验通过） */
    private Long toBerthId;

    /** 幂等键，一次拖动生成一个，重试沿用 */
    private String requestId;

    /** 操作人，留痕用 */
    private String operator;
}
