package com.rdms.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.rdms.mapper")
public class MybatisPlusConfig {
}
