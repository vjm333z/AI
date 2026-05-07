package com.recallai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.config.RagProperties;
import com.recallai.dto.ComplexDto;
import com.recallai.dto.HotelDto;
import com.recallai.repository.HotelMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HotelCacheService {

    private static final Logger log = LoggerFactory.getLogger(HotelCacheService.class);

    private final HotelMapper hotelMapper;
    private final RagProperties props;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** propCd → HotelDto 인메모리 캐시 */
    private Map<String, HotelDto> hotelMap = new HashMap<>();

    @PostConstruct
    public void init() {
        try {
            loadFromFile();
        } catch (Exception e) {
            log.warn("호텔 정보 파일 로드 실패: {}", e.getMessage());
        }
    }

    /** hotels.json 파일에서 인메모리 캐시 로드. DB 조회 없음. */
    private void loadFromFile() throws Exception {
        Path path = Paths.get(props.getDataDir(), "hotels.json");
        if (!path.toFile().exists()) {
            log.info("hotels.json 없음 — 빈 캐시로 시작");
            return;
        }
        List<HotelDto> list = objectMapper.readValue(path.toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<List<HotelDto>>() {});
        Map<String, HotelDto> map = new HashMap<>();
        for (HotelDto h : list) map.put(h.getPropCd(), h);
        this.hotelMap = map;
        log.info("hotels.json 로드 완료: {}개 property", hotelMap.size());
    }

    /** DB 재조회 후 캐시 + hotels.json 갱신. 수동 새로고침 엔드포인트에서도 호출. */
    public void refresh() throws Exception {
        // 기존 hotels.json에서 수동 편집된 mobileNos/aliases 보존 (DB에 없는 데이터). 파싱 1회로 두 맵 동시 추출.
        SavedOverlay overlay = loadOverlay();

        List<HotelDto> properties = hotelMapper.selectAllProperties();
        List<ComplexDto> complexes = hotelMapper.selectAllComplexes();

        Map<String, HotelDto> map = new HashMap<>();
        for (HotelDto h : properties) {
            h.setComplexes(new ArrayList<>());
            List<String> prevAliases = overlay.aliases.get(h.getPropCd());
            if (prevAliases != null && !prevAliases.isEmpty()) h.setAliases(prevAliases);
            map.put(h.getPropCd(), h);
        }
        for (ComplexDto c : complexes) {
            HotelDto h = map.get(c.getPropCd());
            if (h != null) {
                List<String> prev = overlay.mobileNos.get(c.getCmpxCd());
                if (prev != null && !prev.isEmpty()) c.setMobileNos(prev);
                h.getComplexes().add(c);
            }
        }
        this.hotelMap = map;

        saveToFile();
        log.info("호텔 정보 로드 완료: {}개 property", hotelMap.size());
    }

    /**
     * 기존 hotels.json을 한 번만 파싱해서 수동 편집된 aliases·mobileNos 추출.
     * 파일 없거나 파싱 실패 시 빈 overlay.
     */
    private SavedOverlay loadOverlay() {
        SavedOverlay overlay = new SavedOverlay();
        Path path = Paths.get(props.getDataDir(), "hotels.json");
        if (!path.toFile().exists()) return overlay;
        try {
            List<HotelDto> existing = objectMapper.readValue(path.toFile(),
                    new TypeReference<List<HotelDto>>() {});
            for (HotelDto h : existing) {
                if (h.getAliases() != null && !h.getAliases().isEmpty()) {
                    overlay.aliases.put(h.getPropCd(), h.getAliases());
                }
                if (h.getComplexes() == null) continue;
                for (ComplexDto c : h.getComplexes()) {
                    if (c.getMobileNos() != null && !c.getMobileNos().isEmpty()) {
                        overlay.mobileNos.put(c.getCmpxCd(), c.getMobileNos());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("기존 hotels.json 오버레이 읽기 실패 (무시): {}", e.getMessage());
        }
        return overlay;
    }

    /** refresh()에서 보존할 수동 편집값. */
    private static final class SavedOverlay {
        final Map<String, List<String>> aliases   = new HashMap<>();
        final Map<String, List<String>> mobileNos = new HashMap<>();
    }

    /** propCd로 짧은 호텔명 조회. 없으면 빈 문자열. */
    public String getShrtNm(String propCd) {
        if (propCd == null) return "";
        HotelDto h = hotelMap.get(propCd);
        return (h != null && h.getPropShrtNm() != null) ? h.getPropShrtNm() : "";
    }

    public HotelDto getHotel(String propCd) {
        return propCd != null ? hotelMap.get(propCd) : null;
    }

    public Collection<HotelDto> getAllHotels() {
        return hotelMap.values();
    }

    /**
     * propCd가 hotels.json에 없으면 새 호텔 추가.
     * PMS 통화업무등록 시 신규 호텔 자동 등록용.
     * @return true: 추가됨, false: 이미 존재
     */
    public boolean addHotelIfAbsent(String propCd, String propShrtNm, String propFullNm,
                                    String cmpxCd, String cmpxNm, String cmpxReprTel) throws Exception {
        if (propCd == null || propCd.isBlank()) return false;
        if (hotelMap.containsKey(propCd)) {
            log.debug("hotels.json 이미 존재: propCd={}", propCd);
            return false;
        }

        HotelDto hotel = new HotelDto();
        hotel.setPropCd(propCd);
        hotel.setPropShrtNm(propShrtNm);
        hotel.setPropFullNm(propFullNm != null ? propFullNm : propShrtNm);
        hotel.setAliases(new ArrayList<>());
        hotel.setComplexes(new ArrayList<>());

        if (cmpxCd != null && !cmpxCd.isBlank()) {
            ComplexDto complex = new ComplexDto();
            complex.setPropCd(propCd);
            complex.setCmpxCd(cmpxCd);
            complex.setCmpxNm(cmpxNm);
            complex.setCmpxReprTel(cmpxReprTel);
            complex.setMobileNos(new ArrayList<>());
            hotel.getComplexes().add(complex);
        }

        hotelMap.put(propCd, hotel);
        saveToFile();
        log.info("hotels.json 호텔 추가: propCd={}, name={}", propCd, propShrtNm);
        return true;
    }

    private void saveToFile() throws Exception {
        Path path = Paths.get(props.getDataDir(), "hotels.json");
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(path.toFile(), new ArrayList<>(hotelMap.values()));
        log.info("hotels.json 저장 완료: {}", path.toAbsolutePath());
    }
}
