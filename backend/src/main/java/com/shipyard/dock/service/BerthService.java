package com.shipyard.dock.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Berth;
import com.shipyard.dock.domain.BerthMoveLog;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.dto.BerthMoveRequest;
import com.shipyard.dock.dto.BerthMoveResult;
import com.shipyard.dock.dto.BerthRequest;
import com.shipyard.dock.repository.BerthMoveLogRepository;
import com.shipyard.dock.repository.BerthRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 模块一：船台与船坞。
 *
 * <p>规则：① 船台编号唯一；② 长度 / 宽度 / 承载能力必须 &gt; 0；
 * ③ 状态按 空闲 → 占用 → 检修 → 空闲 流转；④ 已占用的船台不允许再排入新船；
 * ⑤ 船台尺度与承载能力必须满足所排船舶。</p>
 */
@Service
public class BerthService {

    private final BerthRepository berthRepository;
    private final VesselRepository vesselRepository;
    private final BerthMoveLogRepository moveLogRepository;
    private final BerthMoveRecorder moveRecorder;

    public BerthService(BerthRepository berthRepository, VesselRepository vesselRepository,
            BerthMoveLogRepository moveLogRepository, BerthMoveRecorder moveRecorder) {
        this.berthRepository = berthRepository;
        this.vesselRepository = vesselRepository;
        this.moveLogRepository = moveLogRepository;
        this.moveRecorder = moveRecorder;
    }

    public List<Berth> list() {
        return berthRepository.findAllByOrderByIdAsc();
    }

    public Berth get(Long id) {
        return berthRepository.findById(id)
                .orElseThrow(() -> new BizException("船台不存在：" + id));
    }

    @Transactional
    public Berth create(BerthRequest request) {
        if (!StringUtils.hasText(request.getBerthNo())) {
            throw new BizException("船台编号不能为空");
        }
        if (berthRepository.existsByBerthNo(request.getBerthNo())) {
            throw new BizException("船台编号已存在：" + request.getBerthNo());
        }
        if (!Dict.isValidBerthType(request.getBerthType())) {
            throw new BizException("船台类型非法（船台 / 船坞 / 舾装码头 三选一）：" + request.getBerthType());
        }
        Berth berth = new Berth();
        apply(berth, request);
        // 默认值放在 create 里，实体不带默认值
        if (berth.getStatus() == null) {
            berth.setStatus("IDLE");
        }
        if (!Dict.isValidBerthStatus(berth.getStatus())) {
            throw new BizException("船台状态非法：" + berth.getStatus());
        }
        checkScale(berth);
        berth.setCreatedAt(LocalDateTime.now());
        return berthRepository.save(berth);
    }

    @Transactional
    public Berth update(Long id, BerthRequest request) {
        Berth berth = get(id);
        if (StringUtils.hasText(request.getBerthNo()) && !request.getBerthNo().equals(berth.getBerthNo())
                && berthRepository.existsByBerthNo(request.getBerthNo())) {
            throw new BizException("船台编号已存在：" + request.getBerthNo());
        }
        if (request.getBerthType() != null && !Dict.isValidBerthType(request.getBerthType())) {
            throw new BizException("船台类型非法（船台 / 船坞 / 舾装码头 三选一）：" + request.getBerthType());
        }
        if (request.getStatus() != null && !request.getStatus().equals(berth.getStatus())
                && !Dict.canTransferBerth(berth.getStatus(), request.getStatus())) {
            throw new BizException("船台状态不能从 " + Dict.berthStatusName(berth.getStatus())
                    + " 直接变为 " + Dict.berthStatusName(request.getStatus())
                    + "（空闲 → 占用 → 检修 → 空闲）");
        }
        applyPartial(berth, request);
        checkScale(berth);
        return berthRepository.save(berth);
    }

    /** 排船入台：船台必须空闲，且尺度与承载能力要吃得住这条船 */
    @Transactional
    public Berth occupy(Long id, Long vesselId) {
        if (vesselId == null) {
            throw new BizException("请选择要排入的船舶");
        }
        // 行锁与移泊 / 出坞互斥：两个拖放并发抢同一格时，后者拿锁后看到的是最新占用状态
        Berth berth = berthRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("船台不存在：" + id));
        Vessel vessel = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new BizException("船舶不存在：" + vesselId));
        if (!"IDLE".equals(berth.getStatus())) {
            throw new BizException("船台 " + berth.getBerthNo() + " 当前为「"
                    + Dict.berthStatusName(berth.getStatus()) + "」，不允许再排入新船");
        }
        checkFit(berth, vessel);
        berth.setVesselId(vessel.getId());
        berth.setVesselName(vessel.getVesselName());
        berth.setStatus("OCCUPIED");
        berth.setOccupiedAt(LocalDateTime.now());
        return berthRepository.save(berth);
    }

    /** 出坞 / 让位：把船台腾空 */
    @Transactional
    public Berth release(Long id) {
        Berth berth = berthRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("船台不存在：" + id));
        if (!"OCCUPIED".equals(berth.getStatus())) {
            throw new BizException("船台 " + berth.getBerthNo() + " 当前没有占用船舶，无需出坞");
        }
        clearBerth(berth);
        return berthRepository.save(berth);
    }

    /**
     * 移泊：把在台船舶从原船台整体移到另一座船台，必须作为一个完整操作处理。
     *
     * <p>单个数据库事务内：① 按船台 id 升序依次对原 / 目标船台加行级悲观写锁
     * （两艘船互抢、并发拖同一艘船时在此串行化，且不会死锁）；
     * ② 校验船舶仍在「预期的原船台」、目标船台仍空闲、目标尺度 / 承载吃得住；
     * ③ 校验全过后在同一事务里同时腾空原船台、占用目标船台。</p>
     *
     * <p>任一步失败整体回滚，原船台占位保持操作前状态，绝不先腾空再占用，也不靠前端补偿。
     * {@code requestId} 幂等：并发 / 重复 / 重试请求只产生一个有效结果，后到请求回传首次结果
     * 与当前真实船位。</p>
     */
    @Transactional
    public BerthMoveResult move(BerthMoveRequest request) {
        if (request == null) {
            throw new BizException("移泊请求不能为空");
        }
        if (request.getVesselId() == null) {
            throw new BizException("缺少要移泊的船舶");
        }
        if (request.getFromBerthId() == null || request.getToBerthId() == null) {
            throw new BizException("移泊必须指定原船台与目标船台");
        }
        if (request.getFromBerthId().equals(request.getToBerthId())) {
            throw new BizException("目标船台与原船台相同，无需移泊");
        }
        if (!StringUtils.hasText(request.getRequestId())) {
            throw new BizException("缺少移泊请求标识（requestId），无法保证操作幂等");
        }
        String requestId = request.getRequestId().trim();
        String operator = StringUtils.hasText(request.getOperator()) ? request.getOperator().trim() : null;

        // 幂等检查①：已有处理记录（成功或失败），直接回传首次结果，不重复执行
        Optional<BerthMoveLog> existed = moveLogRepository.findByRequestId(requestId);
        if (existed.isPresent()) {
            return replayResult(existed.get());
        }

        Vessel vessel = vesselRepository.findById(request.getVesselId())
                .orElseThrow(() -> new BizException("船舶不存在：" + request.getVesselId()));

        // 失败原因与失败瞬间的真实船位（全部取自锁内已加载的实体，失败处理不再回查船台，
        // 避免独立日志事务与本事务持有的行锁互相等待）
        String failReason = null;
        Long currentBerthId = null;
        String currentBerthNo = null;
        String fromNo = null;
        String toNo = null;
        try {
            // 按 id 升序加锁：任意两笔涉及相同船台的移泊都以相同顺序取锁，避免互抢时死锁
            long firstId = Math.min(request.getFromBerthId(), request.getToBerthId());
            long secondId = Math.max(request.getFromBerthId(), request.getToBerthId());
            Berth firstLocked = berthRepository.findByIdForUpdate(firstId)
                    .orElseThrow(() -> new BizException("船台不存在：" + firstId));
            Berth secondLocked = berthRepository.findByIdForUpdate(secondId)
                    .orElseThrow(() -> new BizException("船台不存在：" + secondId));
            Berth from = request.getFromBerthId() == firstId ? firstLocked : secondLocked;
            Berth to = request.getToBerthId() == firstId ? firstLocked : secondLocked;
            fromNo = from.getBerthNo();
            toNo = to.getBerthNo();

            // 幂等检查②：拿锁后再查一次，挡住并发到达、在等锁期间已被对端处理掉的同一 requestId
            existed = moveLogRepository.findByRequestId(requestId);
            if (existed.isPresent()) {
                return replayResult(existed.get());
            }

            boolean vesselOnFrom = "OCCUPIED".equals(from.getStatus()) && vessel.getId().equals(from.getVesselId());
            // 校验①：船舶必须仍在「预期的原船台」。已被别的拖放移走 / 已出坞都算前提失效
            if (!vesselOnFrom) {
                failReason = buildNotOnExpectedBerthMessage(vessel, from, to);
                Berth actual = berthRepository.findFirstByVesselIdOrderByIdAsc(vessel.getId()).orElse(null);
                currentBerthId = actual != null ? actual.getId() : null;
                currentBerthNo = actual != null ? actual.getBerthNo() : null;
                throw new BizException(failReason);
            }
            // 校验②：目标船台必须仍空闲（被抢占 / 转检修都在此被拒）
            if (!"IDLE".equals(to.getStatus())) {
                currentBerthId = from.getId();
                currentBerthNo = from.getBerthNo();
                failReason = "目标船台 " + to.getBerthNo() + " 当前为「"
                        + Dict.berthStatusName(to.getStatus())
                        + ("OCCUPIED".equals(to.getStatus()) && to.getVesselName() != null
                                ? "（已被 " + to.getVesselName() + " 占用）" : "")
                        + "」，本次移泊未执行，" + vessel.getVesselName() + " 仍在 " + from.getBerthNo();
                throw new BizException(failReason);
            }
            // 校验③：目标船台尺度与承载能力必须吃得住
            try {
                checkFit(to, vessel);
            } catch (BizException fit) {
                currentBerthId = from.getId();
                currentBerthNo = from.getBerthNo();
                failReason = fit.getMessage();
                throw fit;
            }

            // 全部通过：同一事务内原子切换——原船台腾空 + 目标船台占用
            LocalDateTime now = LocalDateTime.now();
            clearBerth(from);
            to.setVesselId(vessel.getId());
            to.setVesselName(vessel.getVesselName());
            to.setStatus("OCCUPIED");
            to.setOccupiedAt(now);
            berthRepository.save(from);
            Berth savedTo = berthRepository.save(to);

            BerthMoveLog log = newLog(requestId, vessel, from, to, operator);
            log.setCurrentBerthId(savedTo.getId());
            log.setCurrentBerthNo(savedTo.getBerthNo());
            log.setResult("SUCCESS");
            moveLogRepository.save(log);

            return buildResult("SUCCESS", false, null, vessel, from, savedTo, log.getCreatedAt());
        } catch (BizException e) {
            // 主事务标记回滚（原船台占位原样保留）；失败记录用独立事务落库，保证失败也可追溯。
            // 记录器所需的船台编号 / 真实船位全部来自锁内实体，不回查被本事务锁住的行。
            if (fromNo == null) {
                fromNo = berthRepository.findById(request.getFromBerthId())
                        .map(Berth::getBerthNo).orElse(null);
            }
            if (toNo == null) {
                toNo = berthRepository.findById(request.getToBerthId())
                        .map(Berth::getBerthNo).orElse(null);
            }
            BerthMoveLog failLog = moveRecorder.recordFailure(requestId, vessel,
                    request.getFromBerthId(), fromNo, request.getToBerthId(), toNo,
                    currentBerthId, currentBerthNo,
                    failReason != null ? failReason : e.getMessage(), operator);
            throw new BizException(e.getMessage() + "（" + vessel.getVesselName() + " 当前在 "
                    + (failLog.getCurrentBerthNo() != null ? failLog.getCurrentBerthNo() : "待排区")
                    + "，布局未改动）");
        }
    }

    /** 移泊日志查询：船台总览 / 详情追溯 */
    public List<BerthMoveLog> moveLogs() {
        return moveLogRepository.findAllByOrderByIdDesc();
    }

    public List<BerthMoveLog> moveLogsOfBerth(Long berthId) {
        return moveLogRepository.findByFromBerthIdOrToBerthIdOrderByIdDesc(berthId, berthId);
    }

    public List<BerthMoveLog> moveLogsOfVessel(Long vesselId) {
        return moveLogRepository.findByVesselIdOrderByIdDesc(vesselId);
    }

    /** 状态流转：空闲 → 占用（走排船）/ 检修；占用 → 检修 / 空闲；检修 → 空闲 */
    @Transactional
    public Berth transfer(Long id, String status) {
        // 行锁与移泊 / 排船 / 出坞互斥，避免转检修与拖放并发互相覆盖
        Berth berth = berthRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("船台不存在：" + id));
        if (!Dict.isValidBerthStatus(status)) {
            throw new BizException("船台状态非法：" + status);
        }
        if (!Dict.canTransferBerth(berth.getStatus(), status)) {
            throw new BizException("船台状态不能从 " + Dict.berthStatusName(berth.getStatus())
                    + " 变为 " + Dict.berthStatusName(status) + "（空闲 → 占用 → 检修 → 空闲）");
        }
        if ("IDLE".equals(status)) {
            clearBerth(berth);
        }
        berth.setStatus(status);
        return berthRepository.save(berth);
    }

    @Transactional
    public void delete(Long id) {
        Berth berth = get(id);
        if (berth.isOccupied()) {
            throw new BizException("船台 " + berth.getBerthNo() + " 正在占用，出坞后才能删除");
        }
        berthRepository.deleteById(id);
    }

    /** 船台平面占位图：全部船台 + 未上台的船 + 类型 / 状态元数据 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Berth> berths = berthRepository.findAllByOrderByIdAsc();
        List<Vessel> vessels = vesselRepository.findAllByOrderByIdAsc();
        List<Vessel> freeVessels = new ArrayList<>();
        for (Vessel vessel : vessels) {
            boolean onBerth = berths.stream().anyMatch(b -> vessel.getId().equals(b.getVesselId()));
            if (!onBerth) {
                freeVessels.add(vessel);
            }
        }
        data.put("berths", berths);
        data.put("vessels", vessels);
        data.put("freeVessels", freeVessels);
        data.put("types", berthRepository.statByBerthType());

        List<Map<String, Object>> typeMeta = new ArrayList<>();
        for (String type : Dict.BERTH_TYPES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", type);
            item.put("name", Dict.berthTypeName(type));
            item.put("count", berths.stream().filter(b -> type.equals(b.getBerthType())).count());
            typeMeta.add(item);
        }
        data.put("typeMeta", typeMeta);

        List<Map<String, Object>> statusMeta = new ArrayList<>();
        for (String status : Dict.BERTH_STATUSES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", status);
            item.put("name", Dict.berthStatusName(status));
            item.put("count", berths.stream().filter(b -> status.equals(b.getStatus())).count());
            statusMeta.add(item);
        }
        data.put("statusMeta", statusMeta);
        return data;
    }

    private void apply(Berth berth, BerthRequest request) {
        berth.setBerthNo(request.getBerthNo());
        berth.setBerthName(request.getBerthName());
        berth.setBerthType(request.getBerthType());
        berth.setLengthM(request.getLengthM());
        berth.setWidthM(request.getWidthM());
        berth.setCapacityT(request.getCapacityT());
        berth.setStatus(request.getStatus());
        berth.setLocation(request.getLocation());
        berth.setNote(request.getNote());
    }

    private void applyPartial(Berth berth, BerthRequest request) {
        ifNotNull(request.getBerthNo(), berth::setBerthNo);
        ifNotNull(request.getBerthName(), berth::setBerthName);
        ifNotNull(request.getBerthType(), berth::setBerthType);
        ifNotNull(request.getLengthM(), berth::setLengthM);
        ifNotNull(request.getWidthM(), berth::setWidthM);
        ifNotNull(request.getCapacityT(), berth::setCapacityT);
        ifNotNull(request.getStatus(), berth::setStatus);
        ifNotNull(request.getLocation(), berth::setLocation);
        ifNotNull(request.getNote(), berth::setNote);
    }

    /** 出坞 / 移泊腾空共用：清掉占用船舶并恢复空闲 */
    private void clearBerth(Berth berth) {
        berth.setVesselId(null);
        berth.setVesselName(null);
        berth.setStatus("IDLE");
        berth.setOccupiedAt(null);
    }

    private BerthMoveLog newLog(String requestId, Vessel vessel, Berth from, Berth to, String operator) {
        BerthMoveLog log = new BerthMoveLog();
        log.setRequestId(requestId);
        log.setVesselId(vessel.getId());
        log.setVesselNo(vessel.getVesselNo());
        log.setVesselName(vessel.getVesselName());
        log.setFromBerthId(from.getId());
        log.setFromBerthNo(from.getBerthNo());
        log.setToBerthId(to.getId());
        log.setToBerthNo(to.getBerthNo());
        log.setOperator(operator);
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 幂等回传：同一 requestId 后到请求不重复执行，只回传首次结果与当前真实船位 */
    private BerthMoveResult replayResult(BerthMoveLog log) {
        Vessel vessel = vesselRepository.findById(log.getVesselId()).orElse(null);
        Berth current = berthRepository.findFirstByVesselIdOrderByIdAsc(log.getVesselId()).orElse(null);
        BerthMoveResult result = new BerthMoveResult();
        result.setResult(log.getResult());
        result.setReplay(true);
        result.setVesselId(log.getVesselId());
        result.setVesselName(log.getVesselName());
        result.setFromBerthId(log.getFromBerthId());
        result.setFromBerthNo(log.getFromBerthNo());
        result.setToBerthId(log.getToBerthId());
        result.setToBerthNo(log.getToBerthNo());
        result.setCurrentBerthId(current != null ? current.getId() : null);
        result.setCurrentBerthNo(current != null ? current.getBerthNo() : null);
        result.setCurrentBerthName(current != null ? current.getBerthName() : null);
        result.setOperatedAt(log.getCreatedAt() != null ? log.getCreatedAt().format(LOG_TIME) : null);
        if ("SUCCESS".equals(log.getResult())) {
            result.setMessage("该移泊请求已于 " + result.getOperatedAt() + " 处理完成，"
                    + log.getVesselName() + " 当前在 " + (current != null ? current.getBerthNo() : "待排区")
                    + "，重复请求未再次执行");
        } else {
            result.setMessage("该移泊请求此前已被拒绝（" + log.getFailReason() + "），"
                    + log.getVesselName() + " 当前在 " + (current != null ? current.getBerthNo() : "待排区")
                    + "，重复请求未再次执行");
        }
        return result;
    }

    private BerthMoveResult buildResult(String result, boolean replay, String message, Vessel vessel,
            Berth from, Berth current, LocalDateTime operatedAt) {
        BerthMoveResult dto = new BerthMoveResult();
        dto.setResult(result);
        dto.setReplay(replay);
        dto.setMessage(message);
        dto.setVesselId(vessel.getId());
        dto.setVesselName(vessel.getVesselName());
        dto.setFromBerthId(from.getId());
        dto.setFromBerthNo(from.getBerthNo());
        dto.setToBerthId(current.getId());
        dto.setToBerthNo(current.getBerthNo());
        dto.setCurrentBerthId(current.getId());
        dto.setCurrentBerthNo(current.getBerthNo());
        dto.setCurrentBerthName(current.getBerthName());
        dto.setOperatedAt(operatedAt != null ? operatedAt.format(LOG_TIME) : null);
        return dto;
    }

    /** 前提失效：船舶已不在预期的原船台——并发拖走 / 已出坞，给出当前真实船位 */
    private String buildNotOnExpectedBerthMessage(Vessel vessel, Berth expected, Berth to) {
        Berth actual = berthRepository.findFirstByVesselIdOrderByIdAsc(vessel.getId()).orElse(null);
        String where = actual != null ? actual.getBerthNo() : "待排区";
        if (actual != null && actual.getId().equals(to.getId())) {
            return vessel.getVesselName() + " 已在目标船台 " + to.getBerthNo()
                    + "（此前的移泊已生效），本次重复请求未再次执行";
        }
        return vessel.getVesselName() + " 已不在预期原船台 " + expected.getBerthNo()
                + "，当前真实船位为「" + where + "」，本次移泊未执行";
    }

    /** 长度 / 宽度 / 承载能力都必须 > 0 */
    private void checkScale(Berth berth) {
        if (berth.getLengthM() == null || berth.getLengthM().signum() <= 0) {
            throw new BizException("船台 " + berth.getBerthNo() + " 有效长度必须大于 0");
        }
        if (berth.getWidthM() == null || berth.getWidthM().signum() <= 0) {
            throw new BizException("船台 " + berth.getBerthNo() + " 有效宽度必须大于 0");
        }
        if (berth.getCapacityT() == null || berth.getCapacityT().signum() <= 0) {
            throw new BizException("船台 " + berth.getBerthNo() + " 承载能力必须大于 0");
        }
    }

    /** 船台尺度与承载能力要吃得住这条船 */
    private void checkFit(Berth berth, Vessel vessel) {
        BigDecimal shipLength = vessel.getLengthM() == null ? BigDecimal.ZERO : vessel.getLengthM();
        BigDecimal shipWidth = vessel.getWidthM() == null ? BigDecimal.ZERO : vessel.getWidthM();
        BigDecimal shipWeight = vessel.getDisplacementT() == null ? BigDecimal.ZERO : vessel.getDisplacementT();
        if (berth.getLengthM() == null || berth.getLengthM().compareTo(shipLength) < 0) {
            throw new BizException("船台 " + berth.getBerthNo() + " 有效长度 " + berth.getLengthM()
                    + " m 容不下船长 " + shipLength + " m 的 " + vessel.getVesselName());
        }
        if (berth.getWidthM() == null || berth.getWidthM().compareTo(shipWidth) < 0) {
            throw new BizException("船台 " + berth.getBerthNo() + " 有效宽度 " + berth.getWidthM()
                    + " m 容不下型宽 " + shipWidth + " m 的 " + vessel.getVesselName());
        }
        if (berth.getCapacityT() == null || berth.getCapacityT().compareTo(shipWeight) < 0) {
            throw new BizException("船台 " + berth.getBerthNo() + " 承载能力 " + berth.getCapacityT()
                    + " t 低于 " + vessel.getVesselName() + " 排水量 " + shipWeight + " t");
        }
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
