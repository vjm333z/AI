package com.recallai.config;

import com.recallai.repository.AiaCallMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * AIA_CALL_* 3개 테이블 전용 DataSource (host 191).
 *
 * <p>메인 PMS DataSource(host 178)는 운영 DB라 INSERT 권한이 없어서, STT 결과 저장은 별도
 * 호스트에 연결되는 이 DataSource를 사용. {@link AiaCallMapper} 만 이 SqlSessionFactory를 통해
 * 바인딩되며, 다른 매퍼(KokCallMntrMapper, HotelMapper)는 메인(@Primary) DataSource 사용.
 *
 * <p>설계 메모:
 * <ul>
 *   <li>{@code AiaCallMapper} 인터페이스에는 {@code @Mapper} 가 없어 mybatis-spring-boot-starter의
 *       자동 스캔에서 제외 → 여기서 명시적으로 빈 등록.</li>
 *   <li>전역 {@code mybatis.mapper-locations} 가 {@code AiaCallMapper.xml} 도 로드하지만, 메인 팩토리에
 *       대응되는 매퍼 인터페이스 빈이 없으므로 실제 실행 경로는 차단됨.</li>
 *   <li>{@code aia.datasource.hikari.*} 서브키는 별도 {@code @ConfigurationProperties}로 바인딩.</li>
 * </ul>
 */
@Configuration
public class AiaDataSourceConfig {

    @Bean
    @ConfigurationProperties("aia.datasource")
    public DataSourceProperties aiaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("aia.datasource.hikari")
    public DataSource aiaDataSource() {
        return aiaDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public SqlSessionFactory aiaSqlSessionFactory(@Qualifier("aiaDataSource") DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:mapper/AiaCallMapper.xml"));
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
        cfg.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(cfg);
        return factory.getObject();
    }

    @Bean
    public SqlSessionTemplate aiaSqlSessionTemplate(
            @Qualifier("aiaSqlSessionFactory") SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory);
    }

    @Bean
    public AiaCallMapper aiaCallMapper(
            @Qualifier("aiaSqlSessionTemplate") SqlSessionTemplate template) {
        return template.getMapper(AiaCallMapper.class);
    }
}
