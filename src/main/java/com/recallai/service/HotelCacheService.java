package com.recallai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.dto.ComplexDto;
import com.recallai.dto.HotelDto;
import com.recallai.repository.HotelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
public class HotelCacheService {

    private static final Logger log = LoggerFactory.getLogger(HotelCacheService.class);

    @Autowired
    private HotelMapper hotelMapper;

    @Value("${rag.data-dir:.}")
    private String dataDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** propCd → HotelDto 인메모리 캐시 */
    private Map<String, HotelDto> hotelMap = new HashMap<>();

    @PostConstruct
    public void init() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn("호텔 정보 초기 로드 실패 (DB 미기동 가능성): {}", e.getMessage());
        }
    }

    /** DB 재조회 후 캐시 + hotels.json 갱신. 수동 새로고침 엔드포인트에서도 호출. */
    public void refresh() throws Exception {
        // 기존 hotels.json에서 수동 편집된 mobileNos/aliases 보존 (DB에 없는 데이터)
        Map<String, List<String>> savedMobileNos = loadExistingMobileNos();
        Map<String, List<String>> savedAliases = loadExistingAliases();

        List<HotelDto> properties = hotelMapper.selectAllProperties();
        List<ComplexDto> complexes = hotelMapper.selectAllComplexes();

        Map<String, HotelDto> map = new HashMap<>();
        for (HotelDto h : properties) {
            h.setComplexes(new ArrayList<>());
            List<String> prevAliases = savedAliases.get(h.getPropCd());
            if (prevAliases != null && !prevAliases.isEmpty()) h.setAliases(prevAliases);
            map.put(h.getPropCd(), h);
        }
        for (ComplexDto c : complexes) {
            HotelDto h = map.get(c.getPropCd());
            if (h != null) {
                List<String> prev = savedMobileNos.get(c.getCmpxCd());
                if (prev != null && !prev.isEmpty()) c.setMobileNos(prev);
                h.getComplexes().add(c);
            }
        }
        this.hotelMap = map;

        saveToFile();
        log.info("호텔 정보 로드 완료: {}개 property", hotelMap.size());
    }

    /** 기존 hotels.json에서 propCd → aliases 매핑 추출. 파일 없거나 파싱 실패 시 빈 맵. */
    private Map<String, List<String>> loadExistingAliases() {
        Path path = Paths.get(dataDir, "hotels.json");
        if (!path.toFile().exists()) return new HashMap<>();
        try {
            List<HotelDto> existing = objectMapper.readValue(path.toFile(),
                    new TypeReference<List<HotelDto>>() {});
            Map<String, List<String>> result = new HashMap<>();
            for (HotelDto h : existing) {
                if (h.getAliases() != null && !h.getAliases().isEmpty()) {
                    result.put(h.getPropCd(), h.getAliases());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("기존 hotels.json aliases 읽기 실패 (무시): {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /** 기존 hotels.json에서 cmpxCd → mobileNos 매핑 추출. 파일 없거나 파싱 실패 시 빈 맵. */
    private Map<String, List<String>> loadExistingMobileNos() {
        Path path = Paths.get(dataDir, "hotels.json");
        if (!path.toFile().exists()) return new HashMap<>();
        try {
            List<HotelDto> existing = objectMapper.readValue(path.toFile(),
                    new TypeReference<List<HotelDto>>() {});
            Map<String, List<String>> result = new HashMap<>();
            for (HotelDto h : existing) {
                if (h.getComplexes() == null) continue;
                for (ComplexDto c : h.getComplexes()) {
                    if (c.getMobileNos() != null && !c.getMobileNos().isEmpty()) {
                        result.put(c.getCmpxCd(), c.getMobileNos());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("기존 hotels.json mobileNos 읽기 실패 (무시): {}", e.getMessage());
            return new HashMap<>();
        }
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

    private void saveToFile() throws Exception {
        Path path = Paths.get(dataDir, "hotels.json");
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(path.toFile(), new ArrayList<>(hotelMap.values()));
        log.info("hotels.json 저장 완료: {}", path.toAbsolutePath());
    }
}
