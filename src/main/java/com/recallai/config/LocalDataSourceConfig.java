package com.recallai.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration
public class LocalDataSourceConfig {

    @Value("${local.datasource.url}")
    private String url;

    @Value("${local.datasource.username}")
    private String username;

    @Value("${local.datasource.password}")
    private String password;

    @Bean("localDataSource")
    public DataSource localDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.mariadb.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean("localJdbcTemplate")
    public JdbcTemplate localJdbcTemplate(@Qualifier("localDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
