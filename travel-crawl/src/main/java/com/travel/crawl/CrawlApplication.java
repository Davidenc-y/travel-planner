package com.travel.crawl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * travel-crawl 爬虫服务入口（F104 实施）。
 *
 * <p>提供：高德官方 API 抓取（安全限频+月度配额）、0/1 串行文件队列、
 * 每小时定时任务、内部测试接口（local/test 且开关开启时注册）。
 * 运行：mvn -pl travel-crawl spring-boot:run -Dspring-boot.run.profiles=local
 * 端口：8087（避免与 8081/8082 冲突）。</p>
 */
@SpringBootApplication
@EnableScheduling
public class CrawlApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrawlApplication.class, args);
    }
}
