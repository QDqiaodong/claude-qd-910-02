package com.shipyard.dock.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shipyard.dock.common.ApiResponse;
import com.shipyard.dock.domain.Berth;
import com.shipyard.dock.domain.BerthMoveLog;
import com.shipyard.dock.dto.BerthMoveRequest;
import com.shipyard.dock.dto.BerthMoveResult;
import com.shipyard.dock.dto.BerthRequest;
import com.shipyard.dock.service.BerthService;

/**
 * 模块一：船台与船坞。
 *
 * <p>页面形态为「船台平面占位图」：船台 / 船坞排成格子，格子里的船占位块可以拖来拖去改船位。</p>
 */
@RestController
@RequestMapping("/api/berth")
public class BerthController {

    private final BerthService service;

    public BerthController(BerthService service) {
        this.service = service;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/list")
    public ApiResponse<List<Berth>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Berth> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<Berth> create(@RequestBody BerthRequest request) {
        return ApiResponse.success("船台已建档", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Berth> update(@PathVariable Long id, @RequestBody BerthRequest request) {
        return ApiResponse.success("船台已更新", service.update(id, request));
    }

    /** 排船入台（占位图上把船拖进格子） */
    @PostMapping("/{id}/occupy")
    public ApiResponse<Berth> occupy(@PathVariable Long id, @RequestParam Long vesselId) {
        return ApiResponse.success("船舶已排入船台", service.occupy(id, vesselId));
    }

    /** 出坞 / 让位（把船拖出格子） */
    @PostMapping("/{id}/release")
    public ApiResponse<Berth> release(@PathVariable Long id) {
        return ApiResponse.success("船台已腾空", service.release(id));
    }

    /**
     * 移泊（把在台船舶拖到另一座空闲船台）：后端单事务完成并发校验与原子切换，
     * 失败整体回滚、原船台占位保留；requestId 幂等，重复 / 重试请求只回传首次结果与真实船位。
     */
    @PostMapping("/move")
    public ApiResponse<BerthMoveResult> move(@RequestBody BerthMoveRequest request) {
        BerthMoveResult result = service.move(request);
        if (result.isReplay()) {
            return ApiResponse.success(result.getMessage(), result);
        }
        return ApiResponse.success("移泊成功：" + result.getVesselName() + " 已由 "
                + result.getFromBerthNo() + " 移至 " + result.getCurrentBerthNo(), result);
    }

    /** 移泊操作日志（船台总览追溯：原船台 / 目标船台 / 操作时间 / 结果）；可按船舶过滤 */
    @GetMapping("/move-logs")
    public ApiResponse<List<BerthMoveLog>> moveLogs(@RequestParam(required = false) Long vesselId) {
        return ApiResponse.success(vesselId == null ? service.moveLogs() : service.moveLogsOfVessel(vesselId));
    }

    /** 单座船台相关的移泊日志（原船台或目标船台） */
    @GetMapping("/{id}/move-logs")
    public ApiResponse<List<BerthMoveLog>> berthMoveLogs(@PathVariable Long id) {
        return ApiResponse.success(service.moveLogsOfBerth(id));
    }

    /** 状态流转：空闲 → 占用 → 检修 → 空闲 */
    @PostMapping("/{id}/status")
    public ApiResponse<Berth> transfer(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("船台状态已更新", service.transfer(id, status));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("船台已删除", null);
    }
}
