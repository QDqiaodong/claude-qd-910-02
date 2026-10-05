package com.shipyard.dock.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Berth;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.dto.BerthRequest;
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

    public BerthService(BerthRepository berthRepository, VesselRepository vesselRepository) {
        this.berthRepository = berthRepository;
        this.vesselRepository = vesselRepository;
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
        Berth berth = get(id);
        if (vesselId == null) {
            throw new BizException("请选择要排入的船舶");
        }
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
        Berth berth = get(id);
        if (!"OCCUPIED".equals(berth.getStatus())) {
            throw new BizException("船台 " + berth.getBerthNo() + " 当前没有占用船舶，无需出坞");
        }
        berth.setVesselId(null);
        berth.setVesselName(null);
        berth.setStatus("IDLE");
        berth.setOccupiedAt(null);
        return berthRepository.save(berth);
    }

    /** 状态流转：空闲 → 占用（走排船）/ 检修；占用 → 检修 / 空闲；检修 → 空闲 */
    @Transactional
    public Berth transfer(Long id, String status) {
        Berth berth = get(id);
        if (!Dict.isValidBerthStatus(status)) {
            throw new BizException("船台状态非法：" + status);
        }
        if (!Dict.canTransferBerth(berth.getStatus(), status)) {
            throw new BizException("船台状态不能从 " + Dict.berthStatusName(berth.getStatus())
                    + " 变为 " + Dict.berthStatusName(status) + "（空闲 → 占用 → 检修 → 空闲）");
        }
        if ("IDLE".equals(status)) {
            berth.setVesselId(null);
            berth.setVesselName(null);
            berth.setOccupiedAt(null);
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
